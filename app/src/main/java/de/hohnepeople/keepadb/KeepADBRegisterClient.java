package de.hohnepeople.keepadb;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Sends optional background reachability updates to a custom register or webhook endpoint. */
final class KeepADBRegisterClient {
    private static final String TAG = "KeepADBRegisterClient";
    /**
     * #562: coordinated retry staffage for {@link #markUnavailableAsync}. Repeated notification-
     * /service refreshes and network callbacks used to re-issue an immediate DELETE on every call
     * (observed as 51 failed DELETEs, mostly ~60s apart, in a single P60 run). The first attempt is
     * still immediate; each subsequent attempt after a failure waits at least the matching entry
     * here, then settles into {@link #MARK_UNAVAILABLE_MAX_BACKOFF_MS} once the table is exhausted.
     * User-approved staffing from issue #562: 5s, 10s, 15s, 30s, 1min, 3min, then <=5min forever.
     */
    static final long[] MARK_UNAVAILABLE_RETRY_BACKOFFS_MS = {
            5_000L, 10_000L, 15_000L, 30_000L, 60_000L, 180_000L
    };
    static final long MARK_UNAVAILABLE_MAX_BACKOFF_MS = 5L * 60L * 1000L;
    private static volatile Long pendingCleanupNowForTesting;
    private static volatile Long markUnavailableNowForTesting;
    private static volatile String coordinatorEndpointForTesting;
    private static volatile Runnable beforeResyncDispatchForTesting;
    private static Handler mainHandler;

    private static synchronized Handler mainHandler() {
        if (mainHandler == null) {
            mainHandler = new Handler(Looper.getMainLooper());
        }
        return mainHandler;
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "KeepADBRegisterPush");
        t.setDaemon(true);
        return t;
    });

    interface RegisterStateListener {
        void onRegisterStateChanged();
    }

    private static volatile RegisterStateListener registerStateListener;

    static void setRegisterStateListener(RegisterStateListener listener) {
        registerStateListener = listener;
    }

    static void clearRegisterStateListener() {
        registerStateListener = null;
    }

    private static void notifyRegisterStateListener(Context context) {
        RegisterStateListener listener = registerStateListener;
        if (listener != null) {
            mainHandler().post(listener::onRegisterStateChanged);
        }
        // #734: the listener only exists while MainActivity is visible; the persistent
        // notification must follow the result regardless. Posted to the main handler so it runs
        // outside the class monitor this is called under (the notification path takes the
        // coordinator monitor, which already calls into this class).
        if (context != null) {
            final Context appContext = context.getApplicationContext();
            mainHandler().post(() -> KeepADBNotification.refreshIfActive(appContext));
        }
    }

    private static volatile String lastRegisteredUrl = null;
    private static volatile String lastRegisteredEndpoint = null;
    private static volatile boolean stateInitialized = false;
    private static volatile long currentOpGeneration = 0;
    private static volatile boolean wlanUpdateInFlight = false;
    /** #562: consecutive markUnavailableAsync DELETE failures, and when the next retry may run. */
    private static volatile int markUnavailableRetryAttempts = 0;
    private static volatile long markUnavailableNextAttemptAt = 0L;
    /**
     * #576 part C: the opGen of the markUnavailableAsync-dispatched DELETE that is currently
     * queued or running, or {@code 0} when none is. Repeated calls (toggle path, lifecycle
     * observer, service_sync all reacting to the same disconnect within milliseconds) coalesce
     * into the one already outstanding instead of each dispatching their own transaction --
     * device log in the issue showed two successful DELETEs 13-14ms apart for a single manual
     * toggle.
     *
     * <p>Deliberately an opGen, not a plain flag: coalescing must only apply while nothing newer
     * has been dispatched since this DELETE was queued. A newer registration
     * ({@link #updateEndpointAsync}) or a genuinely new disconnect bumps
     * {@link #currentOpGeneration}, which breaks the equality check in
     * {@link #markUnavailableAsync} even though this field has not changed yet -- so that later
     * call is never swallowed just because an older, now-superseded DELETE for a different state
     * happens to still be queued or running.
     *
     * <p>Cleared in a {@code finally} around the dispatched task itself (see
     * {@link #markUnavailableAsync}), and only if it still points at that task's own opGen -- a
     * newer markUnavailableAsync call may already have overwritten it with its own opGen by the
     * time this task's finally runs, and that newer task's own finally is then responsible for
     * clearing it. Not touched by {@link #performDeleteTransaction} directly -- that method is
     * shared with {@link #unregisterAndDisableAsync}, which never sets this field and must not be
     * affected by it.
     */
    private static volatile long markUnavailableInFlightOpGen = 0L;
    /**
     * #866: the current POST series -- the URL/endpoint pair whose report is queued, running,
     * waiting for its next retry or, once {@link #seriesExhausted}, given up on. A repeated call for
     * the same pair joins the series instead of dispatching a parallel POST or postponing the
     * scheduled retry. Only a different pair, a network event (#867), an explicit user action
     * (#869) or a configuration change starts a new series.
     */
    private static String seriesUrl = null;
    private static String seriesEndpoint = null;
    private static boolean seriesExhausted = false;
    /** #881: the current series is an episode re-attempt: one request, never a retry. */
    private static boolean singleShotSeries = false;
    /** #881: elapsed realtime when the series was given up on, or of the last episode re-attempt. */
    private static long lastGiveUpOrReattemptAt = 0L;
    /** #881: the sparse re-attempt while a failure episode lasts and nothing else reports. */
    static final long EPISODE_REATTEMPT_INTERVAL_MS = 15L * 60L * 1000L;
    /** #866: retries already scheduled in the current series; 0 before its first failure. */
    private static int postRetryNumber = 0;
    /** #866: the one scheduled retry of the current series on the main handler, or null. */
    private static Runnable pendingPostRetry = null;
    private KeepADBRegisterClient() {}

    static synchronized void ensureStateInitializedLocked(Context context) {
        if (stateInitialized) return;
        if (context != null) {
            String endpoint = KeepADBPreferences.getWebhookLastReportedEndpoint(context);
            lastRegisteredEndpoint = (endpoint != null && !endpoint.trim().isEmpty()) ? endpoint.trim() : null;
            String url = KeepADBPreferences.getWebhookLastReportedUrl(context);
            lastRegisteredUrl = (url != null && !url.trim().isEmpty()) ? url.trim() : null;
        }
        stateInitialized = true;
    }

    static void updateEndpointAsync(Context context, String host, int port) {
        if (context == null || host == null || port <= 0) return;
        Context appContext = context.getApplicationContext();
        if (!KeepADBPreferences.isRegisterWebhookEnabled(appContext)) {
            return;
        }
        final String targetUrl = KeepADBPreferences.getRegisterWebhookUrl(appContext);
        if (targetUrl == null || targetUrl.trim().isEmpty()) {
            return;
        }
        final String endpoint = KeepADBEndpoint.formatEndpoint(host, port);
        final long opGen;
        synchronized (KeepADBRegisterClient.class) {
            ensureStateInitializedLocked(appContext);
            if (targetUrl.equals(lastRegisteredUrl) && endpoint.equals(lastRegisteredEndpoint)) {
                return;
            }
            // #866: the same report is already queued, running, waiting for its retry or was given
            // up on. Coordinator refreshes repeat this call for an unchanged endpoint; they must
            // neither send a parallel POST nor restart (and so postpone) the retry series.
            if (targetUrl.equals(seriesUrl) && endpoint.equals(seriesEndpoint)
                    && (wlanUpdateInFlight || pendingPostRetry != null || seriesExhausted)) {
                return;
            }
            opGen = startSeriesLocked(targetUrl, endpoint);
        }

        dispatchUpdate(appContext, targetUrl, endpoint, opGen);
    }

    /** #867: quiet period that coalesces a burst of network callbacks into one report. */
    static final long RESYNC_DEBOUNCE_MS = 2_000L;
    /** #867: the one debounced resync waiting on the main handler, or null. */
    private static Runnable pendingResync = null;

    /**
     * #867: reports the current endpoint again after an Android network change, even when it is
     * unchanged -- the previous report may have failed or been lost while the network moved.
     * Bursts of callbacks are coalesced into one attempt {@link #RESYNC_DEBOUNCE_MS} after the last
     * of them. That attempt joins a POST that is already running or a retry that is already
     * scheduled (#866) instead of adding a parallel request or postponing that retry; otherwise it
     * starts a new series. The callback is only a reason to try; it does not prove that the
     * endpoint is reachable.
     */
    static void requestResyncAsync(Context context) {
        if (context == null) return;
        final Context appContext = context.getApplicationContext();
        if (!KeepADBPreferences.isRegisterWebhookEnabled(appContext)) return;
        synchronized (KeepADBRegisterClient.class) {
            if (pendingResync != null) {
                mainHandler().removeCallbacks(pendingResync);
            }
            Runnable resync = new Runnable() {
                @Override
                public void run() {
                    runResync(appContext, this);
                }
            };
            pendingResync = resync;
            mainHandler().postDelayed(resync, RESYNC_DEBOUNCE_MS);
        }
    }

    private static void runResync(Context appContext, Runnable self) {
        // A disconnect between reading the endpoint and taking the monitor bumps the generation;
        // the read endpoint is then stale and must not be posted after that cleanup.
        long generationBefore = currentOpGeneration;
        String endpoint = currentCoordinatorEndpoint();
        Runnable hook = beforeResyncDispatchForTesting;
        if (hook != null) hook.run();
        boolean enabled = KeepADBPreferences.isRegisterWebhookEnabled(appContext);
        String targetUrl = KeepADBPreferences.getRegisterWebhookUrl(appContext);
        final long opGen;
        synchronized (KeepADBRegisterClient.class) {
            if (pendingResync != self) return;
            pendingResync = null;
            opGen = startResyncSeriesLocked(appContext, enabled, targetUrl, endpoint, generationBefore);
            if (opGen == 0L) return;
        }
        dispatchUpdate(appContext, targetUrl, endpoint, opGen);
    }

    /**
     * #869: an explicit user action -- saving the webhook URL in Settings, also unchanged -- reports
     * the current endpoint at once, even when the series for it was already given up on. Ordinary
     * refreshes keep joining an exhausted series; only this call restarts it. Like the debounced
     * resync it joins a POST that is running or a retry that is scheduled, so it neither sends a
     * parallel POST nor postpones that retry; a debounced resync still waiting is replaced by it.
     */
    static void requestResyncNow(Context context) {
        if (context == null) return;
        final Context appContext = context.getApplicationContext();
        if (!KeepADBPreferences.isRegisterWebhookEnabled(appContext)) return;
        long generationBefore = currentOpGeneration;
        String endpoint = currentCoordinatorEndpoint();
        String targetUrl = KeepADBPreferences.getRegisterWebhookUrl(appContext);
        final long opGen;
        synchronized (KeepADBRegisterClient.class) {
            if (pendingResync != null) {
                mainHandler().removeCallbacks(pendingResync);
                pendingResync = null;
            }
            opGen = startResyncSeriesLocked(appContext, true, targetUrl, endpoint, generationBefore);
            if (opGen == 0L) return;
        }
        dispatchUpdate(appContext, targetUrl, endpoint, opGen);
    }

    /**
     * #881: called on the service heartbeat. While a failure episode lasts and the retry series
     * has been given up on, nothing else reports until the endpoint or the default network
     * changes, which left s20 unreported for about two hours. This sends one report at most every
     * {@link #EPISODE_REATTEMPT_INTERVAL_MS} after the series was given up on: a single attempt,
     * not a new series, so a lasting outage costs one request per quarter hour. It joins nothing
     * and never starts while a POST runs or a retry is scheduled; a success ends the episode.
     *
     * <p>#884: the episode is persisted, the series state is not. After a process restart during an
     * episode no series exists, and an unchanged endpoint never starts one, so the absence of any
     * series counts as given up on as well. The interval then runs from boot, not from the restart.
     */
    static void reattemptStaleEpisode(Context context) {
        if (context == null) return;
        final Context appContext = context.getApplicationContext();
        if (!KeepADBPreferences.isRegisterWebhookEnabled(appContext)) return;
        if (KeepADBPreferences.getWebhookFailureSince(appContext) <= 0L) return;
        long generationBefore = currentOpGeneration;
        String endpoint = currentCoordinatorEndpoint();
        String targetUrl = KeepADBPreferences.getRegisterWebhookUrl(appContext);
        final long opGen;
        synchronized (KeepADBRegisterClient.class) {
            if ((!seriesExhausted && seriesUrl != null) || pendingResync != null) return;
            long now = SystemClock.elapsedRealtime();
            if (now - lastGiveUpOrReattemptAt < EPISODE_REATTEMPT_INTERVAL_MS) return;
            opGen = startResyncSeriesLocked(appContext, true, targetUrl, endpoint, generationBefore);
            if (opGen == 0L) return;
            lastGiveUpOrReattemptAt = now;
            // One attempt only, independent of the retry-count setting, which may change while
            // the attempt runs (Codex review): a failure ends this series at once.
            singleShotSeries = true;
        }
        dispatchUpdate(appContext, targetUrl, endpoint, opGen, true);
    }

    /** #881: reachability check of the cached endpoint before an episode re-attempt. */
    interface EndpointProbe {
        boolean isReachable(String endpoint);
    }

    private static final int REATTEMPT_PROBE_WAIT_MILLIS = 400;
    private static volatile EndpointProbe endpointProbe = KeepADBRegisterClient::probeEndpoint;

    static void setEndpointProbeForTesting(EndpointProbe probe) {
        endpointProbe = probe != null ? probe : KeepADBRegisterClient::probeEndpoint;
    }

    /** Parses {@code host:port} or {@code [v6]:port} as written by {@link KeepADBEndpoint#formatEndpoint}. */
    private static boolean probeEndpoint(String endpoint) {
        if (endpoint == null) return false;
        int colon = endpoint.lastIndexOf(':');
        if (colon <= 0) return false;
        String host = endpoint.substring(0, colon);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        try {
            int port = Integer.parseInt(endpoint.substring(colon + 1));
            return KeepADBEndpoint.isPortReachable(host, port, REATTEMPT_PROBE_WAIT_MILLIS);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * #867/#869: caller must hold the class monitor. Starts a series that reports the endpoint
     * again, or returns 0 when there is nothing to report, a POST is running or a retry is
     * scheduled (joined), or a newer operation made the endpoint read before the monitor stale.
     */
    private static long startResyncSeriesLocked(Context appContext, boolean enabled,
            String targetUrl, String endpoint, long generationBefore) {
        if (!enabled || endpoint == null || targetUrl == null || targetUrl.trim().isEmpty()) {
            return 0L;
        }
        ensureStateInitializedLocked(appContext);
        if (wlanUpdateInFlight || pendingPostRetry != null
                || currentOpGeneration != generationBefore) {
            return 0L;
        }
        return startSeriesLocked(targetUrl, endpoint);
    }

    /** #866: caller must hold the class monitor. Starts a new POST series and returns its opGen. */
    private static long startSeriesLocked(String targetUrl, String endpoint) {
        cancelPostRetryLocked();
        seriesUrl = targetUrl;
        seriesEndpoint = endpoint;
        wlanUpdateInFlight = true;
        return ++currentOpGeneration;
    }

    private static void dispatchUpdate(Context appContext, String targetUrl, String endpoint, long opGen) {
        dispatchUpdate(appContext, targetUrl, endpoint, opGen, false);
    }

    /**
     * @param probeFirst #881: probe the endpoint before reporting it. Codex review: adbd may have
     *         rotated its port without a network callback, and the cached endpoint is only
     *         re-verified asynchronously. An episode re-attempt never reports an address that does
     *         not answer; the endpoint health check reports the new one itself.
     */
    private static void dispatchUpdate(Context appContext, String targetUrl, String endpoint,
            long opGen, boolean probeFirst) {
        EXECUTOR.execute(() -> {
            if (opGen != currentOpGeneration) return;
            if (probeFirst && !endpointProbe.isReachable(endpoint)) {
                synchronized (KeepADBRegisterClient.class) {
                    if (opGen == currentOpGeneration) {
                        wlanUpdateInFlight = false;
                        seriesExhausted = true;
                    }
                }
                KeepADBDiagnostics.event(appContext, "register_report", "webhook", "skipped",
                        "method=post reason=endpoint_unreachable reattempt=true");
                return;
            }
            performUpdateTransaction(appContext, targetUrl, endpoint, opGen);
        });
    }

    /** #866: caller must hold the class monitor. Drops the scheduled retry and the series state. */
    private static void cancelPostRetryLocked() {
        if (pendingPostRetry != null) {
            mainHandler().removeCallbacks(pendingPostRetry);
            pendingPostRetry = null;
        }
        postRetryNumber = 0;
        singleShotSeries = false;
        seriesUrl = null;
        seriesEndpoint = null;
        seriesExhausted = false;
    }

    /**
     * #866: caller must hold the class monitor. Schedules the next retry of a failed POST once that
     * attempt has completed, or marks the series exhausted after the selected number of retries.
     *
     * <p>#870: deliberately a main-thread timer on uptime, unlike the alert's alarm. The series is
     * short and bounded, and a retry that waits through deep sleep loses nothing: the network event
     * on wake-up (#867) starts a fresh report anyway.
     */
    private static void schedulePostRetryLocked(Context context, String targetUrl, String endpoint,
            long opGen) {
        if (singleShotSeries || postRetryNumber >= KeepADBPreferences.getWebhookRetryCount(context)) {
            seriesExhausted = true;
            lastGiveUpOrReattemptAt = SystemClock.elapsedRealtime();
            return;
        }
        postRetryNumber++;
        long delayMs = KeepADBPreferences.webhookRetryDelayMs(
                KeepADBPreferences.getWebhookRetryProfile(context), postRetryNumber);
        Runnable retry = new Runnable() {
            @Override
            public void run() {
                runPostRetry(context, targetUrl, endpoint, opGen, this);
            }
        };
        pendingPostRetry = retry;
        mainHandler().postDelayed(retry, delayMs);
    }

    /**
     * #866: rechecks the configuration and the current endpoint before the retry is sent. Disabled
     * sync, a changed URL, an invalidated or changed endpoint or a newer operation make it
     * obsolete; it then ends the series without a request.
     */
    private static void runPostRetry(Context context, String targetUrl, String endpoint, long opGen,
            Runnable self) {
        String currentEndpoint = currentCoordinatorEndpoint();
        boolean enabled = KeepADBPreferences.isRegisterWebhookEnabled(context);
        String currentUrl = KeepADBPreferences.getRegisterWebhookUrl(context);
        synchronized (KeepADBRegisterClient.class) {
            if (pendingPostRetry != self) return;
            pendingPostRetry = null;
            if (opGen != currentOpGeneration || !enabled || !targetUrl.equals(currentUrl)
                    || !endpoint.equals(currentEndpoint)) {
                cancelPostRetryLocked();
                // #868: without sync or an endpoint there is nothing left to report, so the failure
                // episode must not go on to warn about it.
                if (!enabled || currentEndpoint == null) {
                    KeepADBWebhookAlert.endEpisode(context);
                }
                return;
            }
            wlanUpdateInFlight = true;
        }
        dispatchUpdate(context, targetUrl, endpoint, opGen);
    }

    /**
     * The endpoint the coordinator currently holds, formatted like a report, or null without one.
     * Read outside this class's monitor: the coordinator calls into this class while holding its
     * own monitor, so taking them in the opposite order could deadlock.
     */
    private static String currentCoordinatorEndpoint() {
        String override = coordinatorEndpointForTesting;
        if (override != null) {
            return override.isEmpty() ? null : override;
        }
        KeepADBEndpointCoordinator.Snapshot snapshot = KeepADBEndpointCoordinator.snapshot();
        return snapshot.hasEndpoint()
                ? KeepADBEndpoint.formatEndpoint(snapshot.host, snapshot.port) : null;
    }

    static void markUnavailableAsync(Context context) {
        if (context == null) return;
        Context appContext = context.getApplicationContext();
        if (!KeepADBPreferences.isRegisterWebhookEnabled(appContext)) {
            return;
        }
        final String targetUrl = KeepADBPreferences.getRegisterWebhookUrl(appContext);
        final long opGen;
        synchronized (KeepADBRegisterClient.class) {
            ensureStateInitializedLocked(appContext);
            // #866: the endpoint is gone, so a scheduled POST retry for it is obsolete.
            cancelPostRetryLocked();
            // #868: without an endpoint there is nothing to report, so no failure episode either.
            KeepADBWebhookAlert.endEpisode(appContext);
            // #576 part C: a DELETE for this exact registered state is already queued or running
            // -- coalesce instead of dispatching another one. Comparing against
            // currentOpGeneration (not a plain flag) means a newer registration or disconnect
            // dispatched in the meantime breaks the equality, so that call is never swallowed.
            // See the field doc on markUnavailableInFlightOpGen for why the flag lives here
            // rather than in performDeleteTransaction.
            if (markUnavailableInFlightOpGen != 0L
                    && markUnavailableInFlightOpGen == currentOpGeneration) {
                return;
            }
            boolean wasInFlight = wlanUpdateInFlight;
            boolean hadPrior = (lastRegisteredEndpoint != null && !lastRegisteredEndpoint.trim().isEmpty())
                    || (lastRegisteredUrl != null && !lastRegisteredUrl.trim().isEmpty());
            if (!wasInFlight && !hadPrior) {
                return;
            }
            // #562: only gate repeat calls once a previous attempt actually failed -- the very
            // first request for a given unavailability is always immediate, matching the
            // approved staffing ("sofortiger Erstversuch"). This keeps rapid-fire refreshes and
            // network callbacks from re-issuing the DELETE on every call.
            // #576 part B: monotonic clock -- this gate is in-memory only (never persisted), so a
            // wall-clock jump (NTP correction, manual change) must not stall it indefinitely. See
            // markUnavailableNow() for why this is a different clock than pendingCleanupNow().
            long now = markUnavailableNow();
            if (markUnavailableRetryAttempts > 0 && now < markUnavailableNextAttemptAt) {
                return;
            }
            wlanUpdateInFlight = false;
            opGen = ++currentOpGeneration;
            markUnavailableInFlightOpGen = opGen;
        }

        EXECUTOR.execute(() -> {
            try {
                if (opGen != currentOpGeneration) return;
                performDeleteTransaction(appContext, targetUrl, opGen);
            } finally {
                // #576 part C: only release the coalescing gate if it still points at this exact
                // dispatch -- a newer markUnavailableAsync call may already have overwritten it
                // with its own opGen (see the field doc), and that call's own finally is then
                // responsible for clearing it instead.
                synchronized (KeepADBRegisterClient.class) {
                    if (markUnavailableInFlightOpGen == opGen) {
                        markUnavailableInFlightOpGen = 0L;
                    }
                }
            }
        });
    }

    static void unregisterAndDisableAsync(Context context) {
        if (context == null) return;
        Context appContext = context.getApplicationContext();
        final String targetUrl = KeepADBPreferences.getRegisterWebhookUrl(appContext);
        final long opGen;
        synchronized (KeepADBRegisterClient.class) {
            ensureStateInitializedLocked(appContext);
            cancelPostRetryLocked();
            KeepADBWebhookAlert.endEpisode(appContext);
            wlanUpdateInFlight = false;
            opGen = ++currentOpGeneration;
        }

        EXECUTOR.execute(() -> {
            if (opGen != currentOpGeneration) return;
            performDeleteTransaction(appContext, targetUrl, opGen);
        });
    }

    /**
     * #317: retries cleanups that a previous URL change could not deliver. Runs on the register
     * executor before the transaction that triggered it, so a lost cleanup is retried at the next
     * register activity (endpoint change, deregistration) rather than being forgotten.
     * The backlog is capped by {@link KeepADBPreferences#MAX_PENDING_CLEANUPS}.
     *
     * <p>#707: first sweeps the retry records whose entry is no longer pending (left behind by an
     * eviction of an earlier build, or by a crash between removing an entry and its record). That is
     * local bookkeeping on the preferences file: it runs here, on the register executor and outside
     * the class monitor, sends nothing and changes no entry.
     */
    private static void flushPendingCleanups(Context context) {
        if (context == null) return;
        PendingCleanupRetryRepository.removeOrphans(context,
                KeepADBPreferences.getPendingWebhookCleanupUrls(context).toArray(new String[0]));
        for (String url : KeepADBPreferences.getPendingWebhookCleanupUrls(context)) {
            // #710: an earlier iteration may already have removed this snapshot entry (another
            // spelling of the same resource); skip it so no redundant DELETE or orphan record.
            if (!KeepADBPreferences.getPendingWebhookCleanupUrls(context).contains(url)) {
                continue;
            }
            String sanitizedUrl = sanitizePendingCleanupUrl(url);
            if (hasLiveRegistrationAtUrl(sanitizedUrl)) {
                removePendingCleanupsForResource(context, sanitizedUrl);
                continue;
            }
            if (!shouldAttemptPendingCleanup(context, url)) {
                continue;
            }
            if (sanitizedUrl != null && deleteEndpoint(sanitizedUrl)) {
                removePendingCleanupsForResource(context, sanitizedUrl);
            } else {
                recordPendingCleanupFailure(context, url);
            }
        }
    }

    /**
     * A pending cleanup is deliberately bounded independently of the four-entry FIFO cap in
     * {@link KeepADBPreferences}. Each entry gets a persisted expiry, attempt budget and
     * exponential backoff, so an unreachable host cannot block every later register transaction.
     * The retry record and its numbers live in {@link PendingCleanupRetryRepository} (#701); this
     * client keeps the decision when an entry is attempted or discarded, and the clock.
     */
    private static boolean shouldAttemptPendingCleanup(Context context, String entry) {
        long now = pendingCleanupNow();
        PendingCleanupRetryRepository.RetryState state =
                PendingCleanupRetryRepository.read(context, entry, now);
        if (state.isExpired(now)) {
            discardPendingCleanup(context, entry, "expired");
            return false;
        }
        if (state.isExhausted()) {
            discardPendingCleanup(context, entry, "attempt_limit");
            return false;
        }
        return state.isDue(now);
    }

    private static void recordPendingCleanupFailure(Context context, String entry) {
        if (PendingCleanupRetryRepository.recordFailure(context, entry, pendingCleanupNow())) {
            discardPendingCleanup(context, entry, "attempt_limit");
        }
    }

    private static void discardPendingCleanup(Context context, String entry, String reason) {
        if (entry == null) return;
        KeepADBPreferences.removePendingWebhookCleanupUrl(context, entry);
        PendingCleanupRetryRepository.remove(context, entry);
        Log.w(TAG, "Dropping pending register cleanup (" + reason + "): " + sanitizeUrl(entry));
    }

    /**
     * Drops queued cleanup requests for the same canonical URL after a later request succeeds.
     * Legacy stored URLs may contain userinfo or fragments, so compare sanitized resource keys
     * while removing the exact stored entry.
     */
    private static void removePendingCleanupsForResource(Context context, String targetUrl) {
        if (context == null || targetUrl == null) return;
        String targetKey = registrationResourceKey(targetUrl);
        if (targetKey == null) return;

        for (String rawUrl : KeepADBPreferences.getPendingWebhookCleanupUrls(context)) {
            if (targetKey.equals(registrationResourceKey(rawUrl))) {
                KeepADBPreferences.removePendingWebhookCleanupUrl(context, rawUrl);
                PendingCleanupRetryRepository.remove(context, rawUrl);
            }
        }
    }

    /**
     * #576 (NET-02 / part B): wall-clock time for the *persisted* pending-cleanup queue (#317).
     * Deliberately kept on {@link System#currentTimeMillis()} rather than switched to a monotonic
     * clock: {@code nextAttemptAt}/{@code expiresAt} are written to SharedPreferences and must
     * survive an app or device restart, but {@link SystemClock#elapsedRealtime()} resets to
     * (near) zero on every reboot. Switching this gate to elapsedRealtime would make a stored 24h
     * expiry (or backoff) effectively unreachable after a reboot until uptime climbs back up to
     * the old absolute value -- a far worse regression than the wall-clock-jump risk this gate
     * already accepts. See {@link #markUnavailableNow()} for the in-memory-only #562 gate, which
     * has no such persistence and does get the monotonic clock. The persisted record itself lives
     * in {@link PendingCleanupRetryRepository} (#701), which reads no clock and only receives
     * this value.
     */
    private static long pendingCleanupNow() {
        Long testNow = pendingCleanupNowForTesting;
        return testNow != null ? testNow : System.currentTimeMillis();
    }

    /**
     * #576 (NET-02 / part B): monotonic clock for the *in-memory-only* #562 retry gate
     * ({@link #markUnavailableNextAttemptAt}). This state is never persisted -- it resets to zero
     * whenever the process restarts anyway (see {@link #resetForTesting()} and the field's
     * default) -- so {@link SystemClock#elapsedRealtime()} resetting on reboot is harmless here,
     * while it protects the gate from a wall-clock jump (NTP correction, manual change) that could
     * otherwise lock it for an arbitrary duration, exactly as #309 already argued for
     * {@code KeepADBEndpoint}'s cooldown.
     */
    private static long markUnavailableNow() {
        Long testNow = markUnavailableNowForTesting;
        return testNow != null ? testNow : SystemClock.elapsedRealtime();
    }

    private static long saturatingAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }

    /**
     * #377: legacy pending entries may contain userinfo even though new entries are sanitised on
     * write. Keep the stored entry unchanged so exact set removal still works, and only use the
     * sanitised URL for the outgoing cleanup request.
     */
    private static String sanitizePendingCleanupUrl(String rawUrl) {
        String sanitized = KeepADBPreferences.sanitizeWebhookUrl(rawUrl);
        return sanitized == null || sanitized.trim().isEmpty() ? null : sanitized;
    }

    private static void performUpdateTransaction(Context context, String targetUrl, String targetEndpoint, long opGen) {
        flushPendingCleanups(context);

        String oldUrl;
        String oldEndpoint;
        synchronized (KeepADBRegisterClient.class) {
            oldUrl = lastRegisteredUrl;
            oldEndpoint = lastRegisteredEndpoint;
        }

        // If URL changed and a prior local report exists, try DELETE at the old URL first
        String unfinishedCleanupUrl = null;
        if (oldUrl != null && !oldUrl.equals(targetUrl) && oldEndpoint != null) {
            if (deleteEndpoint(oldUrl)) {
                // Clear the matching local report snapshot after the DELETE request reports
                // success. The single-threaded executor prevents a later transaction from
                // overlapping this update.
                synchronized (KeepADBRegisterClient.class) {
                    if (oldUrl.equals(lastRegisteredUrl)) {
                        lastRegisteredUrl = null;
                        lastRegisteredEndpoint = null;
                    }
                }
            } else {
                // Keep the previous successful report until the replacement POST succeeds. If the
                // new target fails, the UI must still show the last endpoint that was actually
                // reported successfully rather than losing it during this transition.
                Log.w(TAG, "Failed to deregister from old URL " + sanitizeUrl(oldUrl)
                        + " during URL change; keeping the cleanup for a later retry");
                unfinishedCleanupUrl = oldUrl;
            }
        }

        if (opGen != currentOpGeneration) return;

        // POST to new target URL
        final String cleanupToRemember = unfinishedCleanupUrl;
        boolean failingBefore = isReportFailing(context);
        lastTransportOutcome = "unknown";
        boolean posted = postEndpoint(targetUrl, targetEndpoint);
        recordReportOutcome(context, "post", targetEndpoint, posted, failingBefore, opGen);
        if (posted) {
            // #671: the primary POST can block for seconds, during which a disconnect or a newer
            // endpoint may have superseded this transaction. Abort before any secondary transport
            // is sent, so a stale snapshot never goes out for an outdated target. Same outcome as
            // the stale branch of the synchronized block below: no state, preference or listener
            // side effects (the newer operation owns those).
            if (opGen != currentOpGeneration) return;
            // Consider other verified transport candidates only after the WLAN POST. The local
            // sender allowlist currently holds these candidates back; WLAN success accounting and
            // the stored report snapshot remain owned by this transaction.
            reportAdditionalVerifiedTransports(context, targetUrl);
            synchronized (KeepADBRegisterClient.class) {
                if (opGen == currentOpGeneration) {
                    // Persist pending cleanup before changing the local report snapshot, so a
                    // crash cannot forget the old URL. If the replacement POST failed, the local
                    // snapshot stays with the old target for a later retry.
                    if (cleanupToRemember != null) {
                        String evictedCleanup =
                                KeepADBPreferences.addPendingWebhookCleanupUrl(context, cleanupToRemember);
                        // #707: a full backlog evicted its oldest entry for this one. That entry
                        // is gone for good, so its retry record goes with it; left behind, it
                        // would leak and be inherited if the same entry is queued again.
                        if (evictedCleanup != null) {
                            PendingCleanupRetryRepository.remove(context, evictedCleanup);
                        }
                    }
                    // A cleanup queued before this successful POST is stale local work. Drop it
                    // so it cannot issue an outdated DELETE after the newer registration attempt.
                    removePendingCleanupsForResource(context, targetUrl);
                    wlanUpdateInFlight = false;
                    // #866: success ends the retry series, #868 the failure episode.
                    cancelPostRetryLocked();
                    KeepADBWebhookAlert.endEpisode(context);
                    // A successful WLAN report supersedes any pending unavailable retry for the
                    // previous local report.
                    resetMarkUnavailableRetryLocked();
                    lastRegisteredUrl = targetUrl;
                    lastRegisteredEndpoint = targetEndpoint;
                    // #317: one editor transaction; four separate apply() calls could be torn apart
                    // by a crash and leave a URL without its endpoint.
                    KeepADBPreferences.setWebhookReportSnapshot(context, targetUrl, targetEndpoint,
                            KeepADBPreferences.WEBHOOK_STATUS_SUCCESS, true);
                    notifyRegisterStateListener(context);
                }
            }
        } else {
            synchronized (KeepADBRegisterClient.class) {
                if (opGen == currentOpGeneration) {
                    wlanUpdateInFlight = false;
                    KeepADBPreferences.setWebhookLastReportStatus(
                            context, KeepADBPreferences.WEBHOOK_STATUS_FAILED);
                    schedulePostRetryLocked(context, targetUrl, targetEndpoint, opGen);
                    KeepADBWebhookAlert.onReportFailed(context);
                    notifyRegisterStateListener(context);
                }
            }
        }
    }

    private static void performDeleteTransaction(Context context, String targetUrl, long opGen) {
        flushPendingCleanups(context);

        String urlToDelete;
        synchronized (KeepADBRegisterClient.class) {
            urlToDelete = (lastRegisteredUrl != null) ? lastRegisteredUrl : targetUrl;
            if (urlToDelete == null || urlToDelete.trim().isEmpty()) {
                wlanUpdateInFlight = false;
                lastRegisteredUrl = null;
                lastRegisteredEndpoint = null;
                resetMarkUnavailableRetryLocked();
                KeepADBPreferences.setWebhookReportSnapshot(context, null, null, null, false);
                notifyRegisterStateListener(context);
                return;
            }
        }

        boolean failingBefore = isReportFailing(context);
        String endpointToDelete;
        synchronized (KeepADBRegisterClient.class) {
            endpointToDelete = lastRegisteredEndpoint;
        }
        lastTransportOutcome = "unknown";
        boolean cleanupCompleted = deleteEndpoint(urlToDelete);
        recordReportOutcome(context, "delete", endpointToDelete, cleanupCompleted, failingBefore,
                opGen);
        if (cleanupCompleted) {
            // Record the successful DELETE response before the generation check, so a newer
            // operation does not leave the local report snapshot pointing at the old URL.
            synchronized (KeepADBRegisterClient.class) {
                if (urlToDelete.equals(lastRegisteredUrl)) {
                    lastRegisteredUrl = null;
                    lastRegisteredEndpoint = null;
                }
            }
            removePendingCleanupsForResource(context, urlToDelete);
            synchronized (KeepADBRegisterClient.class) {
                if (opGen == currentOpGeneration) {
                    wlanUpdateInFlight = false;
                    resetMarkUnavailableRetryLocked();
                    KeepADBPreferences.setWebhookReportSnapshot(context, null, null,
                            KeepADBPreferences.WEBHOOK_STATUS_DEREGISTERED, true);
                    notifyRegisterStateListener(context);
                }
            }
        } else {
            synchronized (KeepADBRegisterClient.class) {
                if (opGen == currentOpGeneration) {
                    wlanUpdateInFlight = false;
                    recordMarkUnavailableRetryFailureLocked();
                    KeepADBPreferences.setWebhookLastReportStatus(
                            context, KeepADBPreferences.WEBHOOK_STATUS_FAILED);
                    notifyRegisterStateListener(context);
                }
            }
        }
    }

    /** #562: caller must hold the class monitor. */
    private static void resetMarkUnavailableRetryLocked() {
        markUnavailableRetryAttempts = 0;
        markUnavailableNextAttemptAt = 0L;
    }

    /** #562: caller must hold the class monitor. */
    private static void recordMarkUnavailableRetryFailureLocked() {
        markUnavailableRetryAttempts++;
        markUnavailableNextAttemptAt = saturatingAdd(markUnavailableNow(),
                markUnavailableBackoffMs(markUnavailableRetryAttempts));
    }

    /**
     * #562: user-approved staffing -- 5s, 10s, 15s, 30s, 1min, 3min after the first through sixth
     * consecutive failure, then a flat 5min ceiling for every failure after that. No age-based
     * cutoff: retries continue until DELETE returns a successful status or a newer report supersedes it.
     */
    private static long markUnavailableBackoffMs(int consecutiveFailures) {
        if (consecutiveFailures <= 0) return 0L;
        if (consecutiveFailures <= MARK_UNAVAILABLE_RETRY_BACKOFFS_MS.length) {
            return MARK_UNAVAILABLE_RETRY_BACKOFFS_MS[consecutiveFailures - 1];
        }
        return MARK_UNAVAILABLE_MAX_BACKOFF_MS;
    }

    private static synchronized boolean hasLiveRegistrationAtUrl(String cleanupUrl) {
        if (cleanupUrl == null || cleanupUrl.trim().isEmpty()) {
            return false;
        }
        return sameRegistrationResource(cleanupUrl, lastRegisteredUrl);
    }

    private static boolean sameRegistrationResource(String leftUrl, String rightUrl) {
        String leftKey = registrationResourceKey(leftUrl);
        String rightKey = registrationResourceKey(rightUrl);
        return leftKey != null && leftKey.equals(rightKey);
    }

    private static String registrationResourceKey(String rawUrl) {
        String sanitizedUrl = sanitizePendingCleanupUrl(rawUrl);
        if (sanitizedUrl == null) return null;
        try {
            java.net.URI uri = new java.net.URI(sanitizedUrl);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) return sanitizedUrl;
            scheme = scheme.toLowerCase(java.util.Locale.ROOT);
            host = host.toLowerCase(java.util.Locale.ROOT);
            if (host.indexOf(':') >= 0) {
                host = "[" + host + "]";
            }
            int port = uri.getPort();
            if (port < 0) {
                port = "https".equals(scheme) ? 443 : 80;
            }
            String path = uri.getRawPath();
            if (path == null || path.isEmpty()) path = "/";
            while (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            return scheme + "://" + host + ":" + port + path;
        } catch (Exception ignored) {
            return sanitizedUrl;
        }
    }

    static synchronized void resetForTesting() {
        lastGiveUpOrReattemptAt = 0L;
        endpointProbe = KeepADBRegisterClient::probeEndpoint;
        lastRegisteredUrl = null;
        lastRegisteredEndpoint = null;
        stateInitialized = false;
        currentOpGeneration = 0;
        wlanUpdateInFlight = false;
        markUnavailableRetryAttempts = 0;
        markUnavailableNextAttemptAt = 0L;
        markUnavailableInFlightOpGen = 0L;
        cancelPostRetryLocked();
        if (pendingResync != null) {
            mainHandler().removeCallbacks(pendingResync);
            pendingResync = null;
        }
        coordinatorEndpointForTesting = null;
        beforeResyncDispatchForTesting = null;
        KeepADBWebhookAlert.resetForTesting();
        registerStateListener = null;
        pendingCleanupNowForTesting = null;
        markUnavailableNowForTesting = null;
        resetHttpTransport();
        mainHandler = null;
    }

    // ---- Test-only accessors: keep WLAN state verifiable. ----

    /**
     * Blocks until the register executor has drained. Register work is dispatched to a single
     * background thread, so a test that only waits for its own observable outcome can end while a
     * trailing request of that same transaction is still in flight; because the transport is a
     * static field, that request would then be recorded against the *next* test's fake and make
     * its request count non-deterministic. Submitting a barrier onto the same single-threaded
     * executor is exact rather than timing-based: it can only run once every task queued before it
     * has finished. Deliberately not {@code synchronized} -- the queued tasks take the class
     * monitor themselves, so holding it here would deadlock.
     */
    static boolean awaitIdleForTesting(long timeoutMs) {
        java.util.concurrent.CountDownLatch drained = new java.util.concurrent.CountDownLatch(1);
        EXECUTOR.execute(drained::countDown);
        try {
            return drained.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * #576: bumps {@link #currentOpGeneration} without dispatching any transaction, so a test can
     * model "some other, already-confirmed operation superseded this one" at an exact point (e.g.
     * from inside a fake transport callback while a delete/post is in flight) without that other
     * operation's own executor task running and overwriting the state the test wants to observe --
     * the single-threaded EXECUTOR would otherwise never let a second real transaction complete
     * before the first one returns.
     */
    static synchronized void bumpOpGenerationForTesting() {
        ++currentOpGeneration;
    }

    static void flushPendingCleanupsForTesting(Context context) {
        flushPendingCleanups(context);
    }

    static void setPendingCleanupNowForTesting(long now) {
        pendingCleanupNowForTesting = now;
    }

    static void setMarkUnavailableNowForTesting(long now) {
        markUnavailableNowForTesting = now;
    }

    static void setWlanStateForTesting(String url, String endpoint) {
        lastRegisteredUrl = url;
        lastRegisteredEndpoint = endpoint;
        stateInitialized = true;
    }

    static String getLastRegisteredUrlForTesting() {
        return lastRegisteredUrl;
    }

    static String getLastRegisteredEndpointForTesting() {
        return lastRegisteredEndpoint;
    }

    static boolean isWlanUpdateInFlightForTesting() {
        return wlanUpdateInFlight;
    }

    /**
     * #866: the endpoint a retry compares against, instead of the coordinator's; an empty string
     * stands for "no endpoint". Null restores the coordinator.
     */
    static void setCoordinatorEndpointForTesting(String endpoint) {
        coordinatorEndpointForTesting = endpoint;
    }

    /** #867: runs after the resync read its endpoint and before it takes the monitor. */
    static void setBeforeResyncDispatchForTesting(Runnable hook) {
        beforeResyncDispatchForTesting = hook;
    }

    static synchronized int getPostRetryNumberForTesting() {
        return postRetryNumber;
    }

    static synchronized boolean hasPendingPostRetryForTesting() {
        return pendingPostRetry != null;
    }

    static synchronized boolean hasPendingResyncForTesting() {
        return pendingResync != null;
    }

    static synchronized boolean isSeriesExhaustedForTesting() {
        return seriesExhausted;
    }

    static int getMarkUnavailableRetryAttemptsForTesting() {
        return markUnavailableRetryAttempts;
    }

    static long getMarkUnavailableNextAttemptAtForTesting() {
        return markUnavailableNextAttemptAt;
    }

    /**
     * #350: log-facing redaction. Delegates to {@link KeepADBUrlRedaction} so logs and UI apply the
     * same rule for userinfo, host, port and fragment; the log variant additionally drops the path.
     * The previous {@link java.net.URI}-based version left IPv4 hosts unmasked and threw on an
     * un-encoded IPv6 zone id, degrading the very inputs that most needed redacting.
     */
    static String sanitizeUrl(String rawUrl) {
        if (rawUrl == null) return "null";
        String redacted = KeepADBUrlRedaction.forLog(rawUrl);
        return redacted.isEmpty() ? KeepADBUrlRedaction.UNPARSEABLE : redacted;
    }

    interface HttpTransport {
        boolean postJson(String targetUrl, String payload, String logLabel);
        boolean delete(String targetUrl);
    }

    /**
     * #881: how the last request ended, for diagnostics only: {@code http=<code>} or {@code
     * error=<exception class>}, reported by the transport, which itself stays stateless (#700).
     * Never a URL or an endpoint. Written and read on the single register executor.
     */
    private static volatile String lastTransportOutcome = "unknown";

    static void noteTransportOutcome(String outcome) {
        lastTransportOutcome = outcome;
    }

    static String lastTransportOutcomeForTesting() {
        return lastTransportOutcome;
    }

    private static final HttpTransport DEFAULT_TRANSPORT = new KeepADBHttpTransport();
    private static volatile HttpTransport httpTransport = DEFAULT_TRANSPORT;

    static void setHttpTransport(HttpTransport transport) {
        httpTransport = (transport != null) ? transport : DEFAULT_TRANSPORT;
    }

    static void resetHttpTransport() {
        httpTransport = DEFAULT_TRANSPORT;
    }

    /**
     * Sends the locally generated WLAN event as a JSON POST. A successful return reports only the
     * HTTP response class; processing and storage by an external receiver are not observed here.
     */
    static boolean postEndpoint(String targetUrl, String endpoint) {
        KeepADBRegisterPayload.Event event =
                KeepADBRegisterPayload.wlanEvent(endpoint, System.currentTimeMillis());
        return sendJsonPost(targetUrl, event.json, endpoint);
    }

    /**
     * Attempts locally enabled events and skips candidates excluded by the sender allowlist.
     * An empty list is a no-op. HTTP success reports request status only; receiver-side
     * persistence and reachability semantics are outside this client.
     */
    static boolean postTransports(String targetUrl,
            java.util.List<KeepADBRegisterPayload.VerifiedTransport> verified) {
        boolean allSent = true;
        for (KeepADBRegisterPayload.Event event : KeepADBRegisterPayload.buildEvents(verified)) {
            if (!event.serverSupported) {
                Log.i(TAG, "Holding back register event for unsupported method " + event.method);
                continue;
            }
            if (!sendJsonPost(targetUrl, event.json, event.method)) {
                allSent = false;
            }
        }
        return allSent;
    }

    /**
     * Collects other verified transports after the WLAN update, using the same explicit webhook
     * opt-in and user-entered URL. The local sender filter currently holds these candidates back.
     * WLAN reporting remains owned by the surrounding transaction.
     */
    private static void reportAdditionalVerifiedTransports(Context context, String targetUrl) {
        if (context == null || targetUrl == null || targetUrl.trim().isEmpty()) return;
        java.util.List<KeepADBRegisterPayload.VerifiedTransport> additional = new java.util.ArrayList<>();
        for (KeepADBRegisterPayload.VerifiedTransport transport
                : KeepADBRegisterPayload.fromSnapshot(KeepADBTransportOverview.current(context))) {
            if (transport.type == KeepADBRegisterPayload.Type.WLAN_LAN) continue;
            additional.add(transport);
        }
        if (additional.isEmpty()) return;
        postTransports(targetUrl, additional);
    }

    private static boolean isReportFailing(Context context) {
        return KeepADBPreferences.WEBHOOK_STATUS_FAILED.equals(
                KeepADBPreferences.getWebhookLastReportStatus(context));
    }

    /**
     * #881: a diagnostics event for every failed primary report and for the success that ends a
     * failure episode, so an episode can be explained afterwards (timeout, refused, unknown host,
     * HTTP status). Routine successes add no diagnostics event, and the event carries no URL and
     * no endpoint. #892: every attempt also goes into the separate, app-private report history,
     * which does keep the endpoint (never the URL); the #317 report snapshot stays one write.
     */
    private static void recordReportOutcome(Context context, String method, String endpoint,
            boolean ok, boolean failingBefore, long opGen) {
        int retry;
        boolean superseded;
        synchronized (KeepADBRegisterClient.class) {
            retry = "delete".equals(method) ? markUnavailableRetryAttempts : postRetryNumber;
            superseded = opGen != currentOpGeneration;
        }
        // #892: every attempt goes into the local history, routine successes included; the
        // endpoint is kept for that local view, the URL never.
        String outcome = lastTransportOutcome;
        if ("delete".equals(method)) {
            KeepADBReportHistory.unregister(context, endpoint, ok, outcome, retry);
        } else {
            KeepADBReportHistory.report(context, endpoint, ok, outcome, retry,
                    KeepADBPreferences.getWebhookRetryCount(context));
        }
        if (ok && !failingBefore) return;
        // A success of a superseded transaction ends nothing: the newer operation owns the
        // report status (Codex review).
        if (ok && superseded) return;
        KeepADBDiagnostics.event(context, "register_report", "webhook", ok ? "success" : "failed",
                "method=" + method + " " + lastTransportOutcome + " retry=" + retry);
    }

    private static boolean sendJsonPost(String targetUrl, String payload, String logLabel) {
        return httpTransport.postJson(targetUrl, payload, logLabel);
    }

    static boolean deleteEndpoint(String targetUrl) {
        return httpTransport.delete(targetUrl);
    }
}
