package de.hohnepeople.keepadb;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * #594: process-wide owner of the Wireless Debugging endpoint and its lifecycle.
 *
 * <p>This class alone owns -- under its single class monitor -- the cached endpoint
 * (host/port/verification time), the discovery owner (global vs. short-lived Tile), the discovery
 * request generation, the verification token plus in-flight flag, the bounded discovery retry and
 * cancellation/stop. {@link KeepADBEndpoint} remains the low-level mDNS/probe discovery;
 * {@link KeepADBNotification} only renders what this class decides and owns no endpoint state.
 *
 * <p>Readers get the endpoint exclusively as an immutable {@link Snapshot} taken under the
 * monitor, so host, port and verification time always describe the same endpoint (no torn
 * reads across separate getter calls). Interested surfaces receive state changes through
 * {@link EndpointListener} (MainActivity) and the posted cross-surface refresh (widget, tile).
 *
 * <p>Rendering calls into {@link KeepADBNotification} happen while this monitor is held, exactly
 * as before the split (when both lived in one class): the notification's own entry points that
 * post independently ({@code showPermissionMissing}) synchronize on this same monitor, so the
 * lock order is always coordinator -> notification rendering, never the reverse.
 */
final class KeepADBEndpointCoordinator {
    private static final String TAG = "KeepADBEndpointCoord";
    private static final long RETRY_DELAY_INITIAL_MS = 2000;
    private static final long RETRY_DELAY_MAX_MS = 30_000;
    private static final int MAX_RETRY_ATTEMPTS = 5;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final Object GLOBAL_DISCOVERY_OWNER = new Object();

    private static KeepADBEndpoint endpoint;
    private static String currentHost;
    private static int currentPort;
    // #538: epoch-millis of the last confirmed reachability of currentHost/currentPort -- set at
    // every site that establishes or reconfirms the cached endpoint, reset at every site that
    // clears it, so it always describes exactly the endpoint currently cached, never a stale one.
    // Published only through snapshot(), together with host and port.
    private static long currentEndpointVerifiedAtMs;
    private static EndpointListener endpointListener;
    private static Runnable pendingRetryRunnable;
    private static int retryAttempt;
    private static long discoveryRequestGeneration;
    private static long endpointVerificationToken;
    private static Object activeDiscoveryOwner;
    // #351: whether a verifyCachedEndpointAsync() worker Thread is currently running. Repeated
    // triggers (the unthrottled 60s heartbeat and the throttled roam callback can both land here
    // within a short window) used to each spawn their own raw Thread + blocking socket check, so
    // they could pile up while older ones were still in flight. The #315 token/owner/state checks
    // already made a superseded worker's *result* harmless, but did nothing to bound how many
    // socket checks ran concurrently. This flag coalesces same-target triggers into the single
    // in-flight worker instead of stacking a new one; it is only ever read/written under the
    // class monitor and is always cleared by the worker itself once its socket check returns.
    private static boolean verificationInFlight;
    // Test-only seam (#351): lets a test substitute a slow/deterministic fake for the real
    // blocking socket check in KeepADBEndpoint.isPortReachable(), which a raw unit/Robolectric
    // test cannot otherwise drive without hanging or flaking (see KeepADBNotificationRobolectricTest).
    // #394 switched this to the #363 TLS-sniff probe to close the "foreign service took over the
    // port" gap, but #404 found that probe never actually matches genuine adbd on real devices --
    // it always returns false. #435 falls back to the plain-connect KeepADBEndpoint.isPortReachable(),
    // consistent with the #412 decision for the mDNS path: the cache still avoids most unnecessary
    // rediscoveries (a foreign responder on the exact cached host:port is an accepted, narrow
    // false-positive trade-off, same as mDNS).
    private static ReachabilityProbe reachabilityProbe = KeepADBEndpoint::isPortReachable;
    // Test-only seam (review repair on #351): see setWorkerStarterForTesting().
    private static WorkerStarter workerStarter = Thread::start;
    // Test-only counter (#351): number of verification worker Threads actually started, so a test
    // can assert that coalesced triggers did not each spawn their own worker.
    private static int verificationWorkerStartCountForTesting;
    // #297: verifyCachedEndpointAsync() is reached both by the throttled roam trigger and by the
    // unthrottled 60s heartbeat. Without this flag every routine "still reachable" heartbeat
    // confirmation persisted its own endpoint_verified/reachable diagnostics event, flooding the
    // bounded MAX_EVENTS ring buffer with redundant confirmations and pushing out rarer events.
    // Reset to false whenever the cached endpoint changes (fresh discovery), is invalidated as
    // stale, or torn down, so the next confirmation for the new state is logged exactly once.
    private static boolean endpointReachableConfirmed;

    private KeepADBEndpointCoordinator() {}

    /**
     * #594: immutable, internally consistent view of the cached endpoint. All three values are
     * read under the coordinator monitor in one step, so they always belong to the same endpoint.
     */
    static final class Snapshot {
        final String host;
        final int port;
        /** Epoch-millis of the last confirmed reachability, or 0 without an endpoint (#538). */
        final long verifiedAtMs;

        Snapshot(String host, int port, long verifiedAtMs) {
            this.host = host;
            this.port = port;
            this.verifiedAtMs = verifiedAtMs;
        }

        boolean hasEndpoint() {
            return host != null && port > 0;
        }
    }

    interface EndpointListener {
        void onEndpoint(String host, int port);
        void onUnavailable();
    }

    /** Test-only seam (#351) for the blocking reachability check performed by a verification worker. */
    interface ReachabilityProbe {
        boolean isReachable(String host, int port, int timeoutMs);
    }

    /**
     * Test-only seam (review repair on #351) for actually starting a verification worker, so a
     * test can simulate a platform that refuses another thread without having to exhaust the real
     * thread limit.
     */
    interface WorkerStarter {
        void start(Thread worker);
    }

    /** Substitutes the real {@link Thread#start()} of a verification worker for a test. */
    static synchronized void setWorkerStarterForTesting(WorkerStarter starter) {
        workerStarter = starter != null ? starter : Thread::start;
    }

    /** Substitutes the real socket-based reachability check with {@code probe} for a test. */
    static synchronized void setReachabilityProbeForTesting(ReachabilityProbe probe) {
        reachabilityProbe = probe != null ? probe : KeepADBEndpoint::isPortReachable;
    }

    /**
     * Test-only seam (#453): pre-installs {@code fakeEndpoint} so {@link
     * #startDiscoveryDirectLocked} adopts it instead of lazily constructing a real {@link
     * KeepADBEndpoint} bound to a real {@code NsdManager}. Combined with the {@link
     * KeepADBFakeNsdProbe}/{@link KeepADBFakeScheduler} seams {@code KeepADBEndpoint} already
     * exposes for its own tests (#249), this lets a test drive a real discovery attempt to a
     * deterministic {@code onUnavailable()} without real mDNS or real elapsed time.
     */
    static synchronized void setEndpointForTesting(KeepADBEndpoint fakeEndpoint) {
        endpoint = fakeEndpoint;
    }

    /** Number of verification worker Threads actually started since the last {@link #resetForTesting()}. */
    static synchronized int getVerificationWorkerStartCountForTesting() {
        return verificationWorkerStartCountForTesting;
    }

    static synchronized boolean isVerificationInFlightForTesting() {
        return verificationInFlight;
    }

    /**
     * Whether a real {@link KeepADBEndpoint} discovery attempt is currently in flight (#303) --
     * {@code endpoint} is only ever assigned inside {@code startDiscoveryDirectLocked()}, so this
     * is an observable proxy for "discovery was actually started", usable by a test that drives
     * {@link #refresh(Context)} end-to-end via the {@link KeepADBWifiProbe} seam in {@link
     * KeepADBNetwork} instead of grepping {@code refreshInternal()}'s source.
     */
    static synchronized boolean hasActiveDiscoveryAttemptForTesting() {
        return endpoint != null;
    }

    /**
     * Resets every static field this class keeps as process-wide discovery/retry/endpoint state
     * (#303) back to a clean slate. A test that drives {@link #refresh(Context)} or {@link
     * #refreshForTile(Context, Object)} end-to-end must call this in an {@code @After} so no
     * cached endpoint/retry/owner state leaks into a later test sharing this JVM.
     */
    static synchronized void resetForTesting() {
        cancelRetryLocked();
        retryAttempt = 0;
        discoveryRequestGeneration++;
        endpointVerificationToken++;
        activeDiscoveryOwner = null;
        if (endpoint != null) {
            endpoint.stop();
            endpoint = null;
        }
        currentHost = null;
        currentPort = 0;
        currentEndpointVerifiedAtMs = 0;
        endpointListener = null;
        resetReachableConfirmed();
        verificationInFlight = false;
        verificationWorkerStartCountForTesting = 0;
        reachabilityProbe = KeepADBEndpoint::isPortReachable;
        workerStarter = Thread::start;
    }

    /** #594: the one read path for the cached endpoint -- see {@link Snapshot}. */
    static synchronized Snapshot snapshot() {
        return new Snapshot(currentHost, currentPort, currentEndpointVerifiedAtMs);
    }

    static synchronized void setEndpointListener(EndpointListener listener) {
        endpointListener = listener;
        if (currentHost != null && currentPort > 0) {
            listener.onEndpoint(currentHost, currentPort);
        } else {
            listener.onUnavailable();
        }
    }

    static synchronized void clearEndpointListener() {
        endpointListener = null;
    }

    static synchronized void invalidateEndpoint(Context context) {
        KeepADBDiagnostics.event(context, "endpoint_invalidated", "network", "success", "cached_endpoint_cleared");
        cancelRetryLocked();
        retryAttempt = 0;
        discoveryRequestGeneration++;
        endpointVerificationToken++;
        activeDiscoveryOwner = null;
        if (endpoint != null) {
            endpoint.stop();
        }
        currentHost = null;
        currentPort = 0;
        currentEndpointVerifiedAtMs = 0;
        resetReachableConfirmed();
        if (endpointListener != null) {
            endpointListener.onUnavailable();
        }
        postSurfaceRefresh(context.getApplicationContext());
    }

    static synchronized void verifyEndpointHealth(Context context) {
        Context appContext = KeepADBLocaleHelper.wrapContext(context.getApplicationContext());
        // #594: behavior-preserving -- the former KeepADBNotification.verifyEndpointHealth()
        // skipped verification without a NotificationManager, before touching any state.
        if (!KeepADBNotification.canRender(appContext) || currentHost == null || currentPort <= 0) return;
        claimDiscoveryOwnerLocked(GLOBAL_DISCOVERY_OWNER);
        verifyCachedEndpointAsync(appContext, currentHost, currentPort, GLOBAL_DISCOVERY_OWNER);
    }

    static synchronized void refresh(Context context) {
        refreshInternal(context, GLOBAL_DISCOVERY_OWNER);
    }

    static synchronized void refreshForTile(Context context, Object tileOwner) {
        refreshInternal(context, tileOwner);
    }

    private static synchronized void refreshInternal(Context context, Object discoveryOwner) {
        Context appContext = KeepADBLocaleHelper.wrapContext(context.getApplicationContext());
        // #594: behavior-preserving -- the former KeepADBNotification.refreshInternal() returned
        // here, before touching any endpoint state, when no NotificationManager was available.
        if (!KeepADBNotification.prepareChannel(appContext)) return;

        // #582: an unreadable value is treated like a confirmed "off" -- never a false "on" -- so
        // the notification never keeps showing (or claims) an active endpoint it cannot confirm.
        Boolean adbEnabledOrNull = KeepADB.isEnabledOrNull(appContext, "notification");
        if (adbEnabledOrNull == null || !adbEnabledOrNull) {
            stopOrShowKeepAliveWaiting(appContext);
            return;
        }

        if (currentHost != null && currentPort > 0) {
            cancelRetryLocked();
            KeepADBNotification.renderEndpoint(appContext, currentHost, currentPort);
            if (endpointListener != null) {
                endpointListener.onEndpoint(currentHost, currentPort);
            }
            // The cached endpoint may be stale: adbd rotates its wireless-debugging port on
            // its own (e.g. after a Wi-Fi roam or an internal restart) without adb_wifi_enabled
            // changing, so the ContentObserver never fires. Without this check the notification
            // kept showing a dead port until the process was killed and relaunched.
            claimDiscoveryOwnerLocked(discoveryOwner);
            verifyCachedEndpointAsync(appContext, currentHost, currentPort, discoveryOwner);
            // Otherwise, enabling the webhook while a connection is already cached never
            // reports it: updateEndpointAsync() is normally only reached from a fresh
            // discovery's onEndpoint() callback below, which won't fire again until the next
            // full reconnect (#118). This call is a cheap no-op once already registered.
            KeepADBRegisterClient.updateEndpointAsync(appContext, currentHost, currentPort);
            return;
        }

        if (endpointListener != null) endpointListener.onUnavailable();
        if (KeepADBPreferences.isKeepAliveEnabled(appContext)) {
            KeepADBNotification.renderSearching(appContext);
        }

        cancelRetryLocked();
        // #296: never start discovery without an active Wi-Fi connection -- there is nothing on
        // the network for adbd's wireless-debugging listener to be reachable on, so scanning was
        // guaranteed to fail and previously just fed straight into an unbounded retry loop (see
        // scheduleRetryLocked()). This also covers a service restart (process kill/reboot) while
        // Wi-Fi is already off: onCreate()/onStartCommand() route through recheckAndEnable() ->
        // refresh() -> this method on every (re)start.
        if (KeepADBService.isWifiConnected(appContext)) {
            startDiscoveryDirectLocked(appContext, discoveryOwner);
        } else {
            retryAttempt = 0;
            activeDiscoveryOwner = null;
            KeepADBDiagnostics.event(appContext, "endpoint_discovery_skipped", "network", "skipped",
                    "wifi_disconnected");
        }
        postSurfaceRefresh(appContext);
    }

    private static void verifyCachedEndpointAsync(Context appContext,
            String host, int port, Object discoveryOwner) {
        final long verificationToken;
        synchronized (KeepADBEndpointCoordinator.class) {
            // A Tile must not supersede a verification retained by the global owner.
            if (activeDiscoveryOwner != discoveryOwner) return;
            // #351: coalesce this trigger into an already-running verification worker instead of
            // stacking another concurrent socket check. The in-flight worker re-reads
            // currentHost/currentPort (and the token/owner below) right before it would mutate
            // anything, so it already covers this trigger's target as long as the cached endpoint
            // hasn't changed since it started; if it has, the checks below make it a no-op and the
            // next trigger (next heartbeat tick, or an explicit invalidate/rediscovery) starts a
            // fresh worker for the new state.
            if (verificationInFlight) return;
            verificationToken = ++endpointVerificationToken;
            verificationInFlight = true;
            verificationWorkerStartCountForTesting++;
        }
        Thread worker = new Thread(() -> {
            boolean reachable = reachabilityProbe.isReachable(host, port, 500);
            synchronized (KeepADBEndpointCoordinator.class) {
                verificationInFlight = false;
                if (verificationToken != endpointVerificationToken) return;
                if (activeDiscoveryOwner != discoveryOwner) return;
                if (!host.equals(currentHost) || port != currentPort) {
                    return; // superseded by a newer refresh/discovery in the meantime
                }
                // #582: see the matching comment in refreshInternal() above -- same fallback.
                Boolean adbEnabledOrNull = KeepADB.isEnabledOrNull(appContext, "notification");
                if (adbEnabledOrNull == null || !adbEnabledOrNull) {
                    stopOrShowKeepAliveWaiting(appContext);
                    return;
                }
                if (reachable) {
                    currentEndpointVerifiedAtMs = System.currentTimeMillis();
                    activeDiscoveryOwner = null;
                    if (shouldLogReachable()) {
                        KeepADBDiagnostics.event(appContext, "endpoint_verified", "nsd_or_probe",
                                "reachable", "host=" + host + " port=" + port);
                    }
                    return;
                }
                Log.w(TAG, "Cached endpoint " + host + ":" + port
                        + " no longer reachable; invalidating and rediscovering");
                KeepADBDiagnostics.event(appContext, "endpoint_verified", "nsd_or_probe",
                        "stale_invalidated", "host=" + host + " port=" + port);
                resetReachableConfirmed();
                currentHost = null;
                currentPort = 0;
                currentEndpointVerifiedAtMs = 0;
                if (endpointListener != null) {
                    endpointListener.onUnavailable();
                }
                if (KeepADBPreferences.isKeepAliveEnabled(appContext)) {
                    KeepADBNotification.renderSearching(appContext);
                }
                cancelRetryLocked();
                if (KeepADBService.isWifiConnected(appContext)) {
                    startDiscoveryDirectLocked(appContext, discoveryOwner);
                } else {
                    endpointVerificationToken++;
                    retryAttempt = 0;
                    activeDiscoveryOwner = null;
                    if (endpoint != null) {
                        endpoint.stop();
                        endpoint = null;
                    }
                    KeepADBDiagnostics.event(appContext, "endpoint_discovery_skipped", "network",
                            "skipped", "wifi_disconnected");
                    KeepADBRegisterClient.markUnavailableAsync(appContext);
                }
            }
            postSurfaceRefresh(appContext);
        }, "KeepADBEndpointVerify");
        try {
            workerStarter.start(worker);
        } catch (Throwable t) {
            // Review repair on #351: verificationInFlight is cleared by the worker body, so a
            // worker that never ran would leave it set forever and silently disable endpoint
            // verification for the rest of this process -- a permanently switched-off alarm.
            // #359 already treats exactly this failure (the platform refusing another thread) as
            // realistic for its own coordinator thread, so it must not be ignored here.
            synchronized (KeepADBEndpointCoordinator.class) {
                verificationInFlight = false;
            }
            Log.w(TAG, "Could not start endpoint verification worker", t);
        }
    }

    /**
     * Returns {@code true} exactly once per confirmed-reachable state, i.e. the first time it is
     * called after the cached endpoint changed, was invalidated, or was torn down; every
     * subsequent call before the next such reset returns {@code false}. Pulled out as a small,
     * pure static so #297's state-change gate can be unit-tested without spinning up the
     * background verification thread or a real socket (see the field comment on
     * {@code endpointReachableConfirmed}).
     */
    static synchronized boolean shouldLogReachable() {
        if (endpointReachableConfirmed) {
            return false;
        }
        endpointReachableConfirmed = true;
        return true;
    }

    static synchronized void resetReachableConfirmed() {
        endpointReachableConfirmed = false;
    }

    private static void postSurfaceRefresh(Context appContext) {
        MAIN_HANDLER.post(() -> {
            KeepADBWidget.refreshAllState(appContext);
            KeepADBTileService.refreshListeningTile();
        });
    }

    private static void cancelRetryLocked() {
        if (pendingRetryRunnable != null) {
            MAIN_HANDLER.removeCallbacks(pendingRetryRunnable);
            pendingRetryRunnable = null;
        }
    }

    private static void scheduleRetryLocked(Context appContext) {
        // #582: an unreadable value abandons the retry circuit exactly like a confirmed "off"
        // would -- a discovery retry is pointless without a confirmed "on", and this also bounds a
        // persistent read restriction to the existing MAX_RETRY_ATTEMPTS budget instead of an
        // unbounded planning loop.
        Boolean adbEnabledOrNull = KeepADB.isEnabledOrNull(appContext, "notification");
        if (adbEnabledOrNull == null || !adbEnabledOrNull) {
            retryAttempt = 0;
            activeDiscoveryOwner = null;
            return;
        }
        // #296: without an active Wi-Fi connection there is nothing for discovery to find, so
        // planning yet another retry would otherwise keep a discovery circuit alive forever while
        // Wi-Fi stays unavailable. The event-driven NetworkCallback.onLost() path already cancels
        // any pending retry as soon as Wi-Fi drops (see KeepADBService/invalidateEndpoint()); the
        // finite budget below also covers repeated discovery failures while Wi-Fi remains up.
        if (!KeepADBService.isWifiConnected(appContext)) {
            retryAttempt = 0;
            activeDiscoveryOwner = null;
            return;
        }
        if (retryAttempt >= MAX_RETRY_ATTEMPTS) {
            retryAttempt = 0;
            activeDiscoveryOwner = null;
            KeepADBDiagnostics.event(appContext, "endpoint_discovery", "retry", "exhausted",
                    "max_attempts=" + MAX_RETRY_ATTEMPTS);
            return;
        }
        cancelRetryLocked();
        long delay = retryDelayMsForAttemptForTesting(retryAttempt);
        retryAttempt++;
        pendingRetryRunnable = () -> {
            synchronized (KeepADBEndpointCoordinator.class) {
                pendingRetryRunnable = null;
                // #582: see the matching comment above -- same fallback.
                Boolean stillEnabledOrNull = KeepADB.isEnabledOrNull(appContext, "notification");
                if (stillEnabledOrNull == null || !stillEnabledOrNull) {
                    retryAttempt = 0;
                    activeDiscoveryOwner = null;
                    return;
                }
                if (currentHost != null && currentPort > 0) {
                    retryAttempt = 0;
                    activeDiscoveryOwner = null;
                    return;
                }
                if (!KeepADBService.isWifiConnected(appContext)) {
                    retryAttempt = 0;
                    activeDiscoveryOwner = null;
                    return;
                }
                startDiscoveryDirectLocked(appContext, activeDiscoveryOwner);
            }
        };
        MAIN_HANDLER.postDelayed(pendingRetryRunnable, delay);
    }

    /** Returns the bounded exponential delay used by the discovery retry circuit. */
    static long retryDelayMsForAttemptForTesting(int attempt) {
        long delay = RETRY_DELAY_INITIAL_MS;
        for (int i = 0; i < Math.max(0, attempt); i++) {
            if (delay >= RETRY_DELAY_MAX_MS / 2) return RETRY_DELAY_MAX_MS;
            delay *= 2;
        }
        return Math.min(delay, RETRY_DELAY_MAX_MS);
    }

    private static void startDiscoveryDirectLocked(Context appContext, Object discoveryOwner) {
        endpointVerificationToken++;
        if (endpoint == null) endpoint = new KeepADBEndpoint(appContext);
        claimDiscoveryOwnerLocked(discoveryOwner);
        final long requestGeneration = ++discoveryRequestGeneration;
        endpoint.discover(new KeepADBEndpoint.Listener() {
            @Override
            public void onEndpoint(String host, int port) {
                synchronized (KeepADBEndpointCoordinator.class) {
                    if (requestGeneration != discoveryRequestGeneration) return;
                    endpointVerificationToken++;
                    currentHost = host;
                    currentPort = port;
                    currentEndpointVerifiedAtMs = System.currentTimeMillis();
                    resetReachableConfirmed();
                    activeDiscoveryOwner = null;
                    retryAttempt = 0;
                    cancelRetryLocked();
                    KeepADBNotification.renderEndpoint(appContext, host, port);
                    KeepADBRegisterClient.updateEndpointAsync(appContext, host, port);
                    if (endpointListener != null) {
                        endpointListener.onEndpoint(host, port);
                    }
                }
                KeepADBDiagnostics.event(appContext, "endpoint_discovered", "nsd_or_probe", "success",
                        "host=" + host + " port=" + port);
                postSurfaceRefresh(appContext);
            }

            @Override
            public void onUnavailable() {
                synchronized (KeepADBEndpointCoordinator.class) {
                    if (requestGeneration != discoveryRequestGeneration) return;
                    // Every endpoint-state mutation must bump this token at its mutation site,
                    // independent of caller-side bumps.
                    endpointVerificationToken++;
                    currentHost = null;
                    currentPort = 0;
                    currentEndpointVerifiedAtMs = 0;
                    scheduleRetryLocked(appContext);
                    // #582: an unreadable value picks the "disabled" wording, not "searching" --
                    // "searching" would imply a confirmed "on" the read did not establish.
                    Boolean adbEnabledOrNull = KeepADB.isEnabledOrNull(appContext, "notification");
                    if (KeepADBPreferences.isKeepAliveEnabled(appContext)) {
                        if (adbEnabledOrNull != null && adbEnabledOrNull) {
                            KeepADBNotification.renderSearching(appContext);
                        } else {
                            KeepADBNotification.renderDisabledKeepAliveWaiting(appContext);
                        }
                    } else {
                        KeepADBNotification.remove(appContext);
                    }
                    KeepADBRegisterClient.markUnavailableAsync(appContext);
                    if (endpointListener != null) {
                        endpointListener.onUnavailable();
                    }
                }
                KeepADBDiagnostics.event(appContext, "endpoint_discovered", "nsd_or_probe", "unavailable",
                        "no_live_endpoint");
                postSurfaceRefresh(appContext);
            }
        }, activeDiscoveryOwner == GLOBAL_DISCOVERY_OWNER);
    }

    private static void claimDiscoveryOwnerLocked(Object discoveryOwner) {
        // A Tile can replace another Tile owner, and a global caller can promote a Tile run.
        // A Tile must never adopt an active global discovery or its pending retry.
        if (activeDiscoveryOwner == null || activeDiscoveryOwner != GLOBAL_DISCOVERY_OWNER) {
            activeDiscoveryOwner = discoveryOwner;
        }
    }

    /**
     * #445: routes between actually stopping (the service is really going away) and merely
     * reflecting that Wireless Debugging turned off while Keep-Alive keeps the service running,
     * waiting for it to come back. {@link KeepADBService#shouldRun} is the exact same predicate
     * the service itself uses to decide whether to stay in the foreground, so this stays in sync
     * with {@link KeepADBService#onStartCommand} by construction: whenever the service will still
     * be foreground after this call, {@link #stop}'s notification removal would be silently
     * ignored by Android (a foreground service's notification cannot be cancelled that way),
     * leaving the notification frozen on its last "ENABLED" content for as long as Keep-Alive
     * keeps waiting.
     */
    private static void stopOrShowKeepAliveWaiting(Context appContext) {
        if (KeepADBService.shouldRun(appContext)) {
            showDisabledKeepAliveWaiting(appContext);
        } else {
            stop(appContext);
        }
    }

    /**
     * #445: Wireless Debugging turned off (e.g. a roam/timeout/AP loss, not an explicit user-off)
     * while Keep-Alive is still watching for it to come back -- the foreground service keeps
     * running, so the notification must reflect the true "off, waiting" state instead of either
     * freezing on the old active-endpoint content or disappearing via {@link #stop}'s removal.
     * Deliberately mirrors {@link #stop}'s cached-endpoint/discovery cleanup inline rather than
     * sharing it via a helper -- several text-contract tests pin {@link #stop}'s exact body.
     */
    private static synchronized void showDisabledKeepAliveWaiting(Context appContext) {
        KeepADBDiagnostics.event(appContext, "notification_updated", "notification", "waiting_state",
                "wireless_debugging_off_keep_alive_waiting");
        cancelRetryLocked();
        retryAttempt = 0;
        discoveryRequestGeneration++;
        endpointVerificationToken++;
        activeDiscoveryOwner = null;
        if (endpoint != null) {
            endpoint.stop();
            endpoint = null;
        }
        currentHost = null;
        currentPort = 0;
        currentEndpointVerifiedAtMs = 0;
        resetReachableConfirmed();
        if (endpointListener != null) endpointListener.onUnavailable();
        KeepADBNotification.renderDisabledKeepAliveWaiting(appContext);
        KeepADBRegisterClient.markUnavailableAsync(appContext.getApplicationContext());
        postSurfaceRefresh(appContext.getApplicationContext());
    }

    private static synchronized void stop(Context context) {
        KeepADBDiagnostics.event(context, "notification_removed", "notification", "success", "wireless_debugging_off");
        cancelRetryLocked();
        retryAttempt = 0;
        discoveryRequestGeneration++;
        endpointVerificationToken++;
        activeDiscoveryOwner = null;
        if (endpoint != null) {
            endpoint.stop();
            endpoint = null;
        }
        currentHost = null;
        currentPort = 0;
        currentEndpointVerifiedAtMs = 0;
        resetReachableConfirmed();
        if (endpointListener != null) endpointListener.onUnavailable();
        KeepADBNotification.remove(context);
        KeepADBRegisterClient.markUnavailableAsync(context.getApplicationContext());
        postSurfaceRefresh(context.getApplicationContext());
    }

    static synchronized void cancelTileDiscovery(Object tileOwner) {
        if (tileOwner == null || activeDiscoveryOwner != tileOwner) return;
        discoveryRequestGeneration++;
        endpointVerificationToken++;
        activeDiscoveryOwner = null;
        cancelRetryLocked();
        retryAttempt = 0;
        if (endpoint != null) {
            endpoint.stop();
            endpoint = null;
        }
    }
}
