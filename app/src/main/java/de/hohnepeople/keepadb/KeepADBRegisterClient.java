package de.hohnepeople.keepadb;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sends optional background reachability updates to a custom register or webhook endpoint.
 *
 * <p>#1049: the transaction orchestrator and facade. It owns the operation generation, the single
 * register executor, the class monitor and the local report snapshot; the retry series ({@link
 * RegisterRetrySeries}), the endpoint-loss DELETE gate ({@link UnavailableDeleteBackoff}) and the
 * deferred withdrawals ({@link PendingCleanupCoordinator}) are separate components. The first two
 * are only touched under this class's monitor; the coordinator sends its requests only from the
 * executor, outside it (its pure preference bookkeeping may run under it). Lock order: {@code KeepADBEndpointCoordinator} may call into this class while holding its
 * own monitor, never the reverse.
 */
final class KeepADBRegisterClient {
    private static final String TAG = "KeepADBRegisterClient";
    static final long[] MARK_UNAVAILABLE_RETRY_BACKOFFS_MS = UnavailableDeleteBackoff.RETRY_BACKOFFS_MS;
    static final long MARK_UNAVAILABLE_MAX_BACKOFF_MS = UnavailableDeleteBackoff.MAX_BACKOFF_MS;
    static final long EPISODE_REATTEMPT_INTERVAL_MS = RegisterRetrySeries.EPISODE_REATTEMPT_INTERVAL_MS;
    /** #867: quiet period that coalesces a burst of network callbacks into one report. */
    static final long RESYNC_DEBOUNCE_MS = 2_000L;

    /** Guarded by the class monitor. */
    private static final RegisterRetrySeries SERIES = new RegisterRetrySeries();
    /** Guarded by the class monitor. */
    private static final UnavailableDeleteBackoff UNAVAILABLE = new UnavailableDeleteBackoff();

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

    private static volatile String lastRegisteredUrl = null;
    private static volatile String lastRegisteredEndpoint = null;
    private static volatile boolean stateInitialized = false;
    private static volatile long currentOpGeneration = 0;
    private static volatile boolean wlanUpdateInFlight = false;
    /** #1049 review: the generation of the last report series started, to tell it from a DELETE. */
    private static volatile long lastReportOpGeneration = 0;

    private KeepADBRegisterClient() {}

    /**
     * Settled reports refresh the surfaces through {@link RegisterStateNotifier} (#734, #910,
     * #1036). #911: something other than a report result changed what they show. Nothing is sent.
     */
    static void notifyStateChanged(Context context) {
        RegisterStateNotifier.notifyChanged(context);
    }

    /** #910: what the report client is doing right now, for the widgets. */
    enum Activity {
        /** No POST is running and none is waiting for its retry. */
        IDLE,
        /** A report POST is queued or running. */
        SENDING,
        /** The last POST failed and the next retry of the series is scheduled. */
        RETRY_WAITING
    }

    /** #910: a pure read of the series state; it starts nothing and sends nothing. */
    static synchronized Activity activity() {
        if (wlanUpdateInFlight) return Activity.SENDING;
        if (SERIES.pendingRetry() != null) return Activity.RETRY_WAITING;
        return Activity.IDLE;
    }

    // ---- Entry points ----

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

    /** The URL of the last successful report, read under the monitor (for the coordinator). */
    static synchronized String registeredUrl() {
        return lastRegisteredUrl;
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
            if (SERIES.joins(targetUrl, endpoint, wlanUpdateInFlight)) {
                return;
            }
            opGen = startSeriesLocked(targetUrl, endpoint);
        }

        dispatchUpdate(appContext, targetUrl, endpoint, opGen);
    }

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
            Runnable resync = new Runnable() {
                @Override
                public void run() {
                    runResync(appContext, this);
                }
            };
            SERIES.replaceResync(mainHandler(), resync, RESYNC_DEBOUNCE_MS);
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
            if (!SERIES.takeResync(self)) return;
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
        requestResyncNowSince(context, currentOpGeneration);
    }

    /** The operation generation now; pass it to {@link #requestResyncNowSince}. */
    static long currentOperation() {
        return currentOpGeneration;
    }

    /**
     * #918: {@link #requestResyncNow} for a caller that has just kicked a refresh. Any report
     * started since {@code generationBefore} (taken before that refresh) is the report this action
     * asked for, so it is joined even when it already finished; without this, a refresh whose POST
     * completed before this call caused a second, identical POST right after it.
     */
    static void requestResyncNowSince(Context context, long generationBefore) {
        if (context == null) return;
        final Context appContext = context.getApplicationContext();
        if (!KeepADBPreferences.isRegisterWebhookEnabled(appContext)) return;
        String endpoint = currentCoordinatorEndpoint();
        String targetUrl = KeepADBPreferences.getRegisterWebhookUrl(appContext);
        final long opGen;
        synchronized (KeepADBRegisterClient.class) {
            SERIES.dropResync(mainHandler());
            opGen = startResyncSeriesLocked(appContext, true, targetUrl, endpoint, generationBefore);
            if (opGen == 0L) return;
        }
        dispatchUpdate(appContext, targetUrl, endpoint, opGen);
    }

    /** What a manual "Report now" did with the tap; decided before anything is sent. */
    enum ReportNowResult {
        /** A report of the current endpoint was started for this tap, or a waiting retry was sent at once. */
        STARTED,
        /** A report is already running; the tap joined it and added no request. */
        JOINED,
        /** No valid destination is saved; nothing was sent. Lead the user to the setup. */
        NEEDS_SETUP,
        /** Automatic synchronization is off; nothing was sent and nothing was changed. */
        SYNC_OFF,
        /** No verified Wi-Fi endpoint right now; nothing was sent and no address was reused. */
        NO_ENDPOINT
    }

    /**
     * #911: the single entry point of every "Report now" (the webhook alert's button and the
     * widgets): a manual push into the normal flow of an <em>enabled</em> synchronization. It
     * reports the current, verified Wi-Fi ADB endpoint to the saved destination at once and
     * everything afterwards continues as usual (retries, the failure episode, cleanups).
     *
     * <ul>
     *   <li>It is only a push of automatic synchronization. With synchronization off it does
     *       nothing: no request, no change of any preference and no state, and the result says
     *       {@link ReportNowResult#SYNC_OFF}. It never switches synchronization on.
     *   <li>Without a valid saved URL or without a verified endpoint (Wi-Fi up) nothing is sent
     *       and the cause is returned and logged ({@code register_report skipped reason=...},
     *       never the URL or the endpoint). Wireless Debugging is never switched on and a cached
     *       address is not reused without Wi-Fi.
     *   <li>#893 (Codex review): a retry that is only waiting for its timer is sent at once, a POST
     *       that is already running is joined and a debounced resync that is still waiting is
     *       dropped, so one tap never sends two requests, and the network being untrusted does not
     *       matter (the report path has no trust gate).
     *   <li>#1049: what the tap does and the result it returns are decided under one acquisition of
     *       the class monitor, so the result always describes what was dispatched.
     * </ul>
     *
     * @param onDone runs on the register executor once the attempt this tap caused or joined has
     *         finished (or at once when nothing was sent), so the receiver can keep the process
     *         alive until then ({@code goAsync}).
     */
    static ReportNowResult reportNow(Context context, Runnable onDone) {
        if (context == null) {
            if (onDone != null) onDone.run();
            return ReportNowResult.NO_ENDPOINT;
        }
        final Context appContext = context.getApplicationContext();
        if (!KeepADBPreferences.isValidWebhookUrl(KeepADBPreferences.getRegisterWebhookUrl(appContext))) {
            return refuseReportNow(appContext, ReportNowResult.NEEDS_SETUP, "needs_setup", onDone);
        }
        if (!KeepADBPreferences.isRegisterWebhookEnabled(appContext)) {
            return refuseReportNow(appContext, ReportNowResult.SYNC_OFF, "sync_off", onDone);
        }
        if (verifiedWifiEndpoint(appContext) == null) {
            return refuseReportNow(appContext, ReportNowResult.NO_ENDPOINT, "no_endpoint", onDone);
        }
        // Read outside the monitor (lock order); a newer operation in between bumps the generation
        // and makes startResyncSeriesLocked refuse the stale endpoint.
        long generationBefore = currentOpGeneration;
        String endpoint = currentCoordinatorEndpoint();
        String targetUrl = KeepADBPreferences.getRegisterWebhookUrl(appContext);
        Runnable hook = beforeResyncDispatchForTesting;
        if (hook != null) hook.run();
        ReportNowResult result;
        long opGen = 0L;
        synchronized (KeepADBRegisterClient.class) {
            SERIES.dropResync(mainHandler());
            Runnable retry = SERIES.pendingRetry();
            if (wlanUpdateInFlight) {
                result = ReportNowResult.JOINED;
            } else if (retry != null) {
                mainHandler().removeCallbacks(retry);
                mainHandler().post(retry);
                result = ReportNowResult.STARTED;
            } else {
                opGen = startResyncSeriesLocked(appContext, true, targetUrl, endpoint,
                        generationBefore);
                if (opGen != 0L) {
                    result = ReportNowResult.STARTED;
                } else if (currentOpGeneration != generationBefore
                        && currentOpGeneration == lastReportOpGeneration) {
                    // #918 / review: a report started since the read, and already finished, is the
                    // attempt this tap asked for; it is joined, not repeated.
                    result = ReportNowResult.JOINED;
                } else {
                    // A newer withdrawal (an endpoint loss) took over between the read and the
                    // monitor, so there is no verified endpoint to report.
                    result = ReportNowResult.NO_ENDPOINT;
                }
            }
        }
        if (opGen != 0L) dispatchUpdate(appContext, targetUrl, endpoint, opGen);
        if (onDone != null) {
            // Queued behind the retry on the main thread, then behind its transaction on the
            // single register executor.
            mainHandler().post(() -> EXECUTOR.execute(onDone));
        }
        return result;
    }

    private static ReportNowResult refuseReportNow(Context appContext, ReportNowResult result,
            String reason, Runnable onDone) {
        KeepADBDiagnostics.event(appContext, "register_report", "webhook", "skipped",
                "method=post reason=" + reason + " manual=true");
        if (onDone != null) onDone.run();
        return result;
    }

    /**
     * The current endpoint, formatted like a report, but only while Wi-Fi is up (a cached endpoint
     * is not believed without it, as in {@link KeepADBWidgetState}); null otherwise.
     */
    private static String verifiedWifiEndpoint(Context appContext) {
        String endpoint = currentCoordinatorEndpoint();
        return endpoint != null && KeepADBService.isWifiConnected(appContext) ? endpoint : null;
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
            long now = SystemClock.elapsedRealtime();
            if (!SERIES.reattemptDue(now)) return;
            opGen = startResyncSeriesLocked(appContext, true, targetUrl, endpoint, generationBefore);
            if (opGen == 0L) return;
            // One attempt only, independent of the fixed retry budget of a normal series: a
            // failure ends this series at once.
            SERIES.markReattempt(now);
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
        if (wlanUpdateInFlight || SERIES.pendingRetry() != null
                || currentOpGeneration != generationBefore) {
            return 0L;
        }
        return startSeriesLocked(targetUrl, endpoint);
    }

    /** #866: caller must hold the class monitor. Starts a new POST series and returns its opGen. */
    private static long startSeriesLocked(String targetUrl, String endpoint) {
        SERIES.start(mainHandler(), targetUrl, endpoint);
        wlanUpdateInFlight = true;
        lastReportOpGeneration = ++currentOpGeneration;
        return lastReportOpGeneration;
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
        RegisterStateNotifier.notifyReportQueued(appContext);
        EXECUTOR.execute(() -> {
            if (opGen != currentOpGeneration) return;
            if (probeFirst && !endpointProbe.isReachable(endpoint)) {
                synchronized (KeepADBRegisterClient.class) {
                    if (opGen == currentOpGeneration) {
                        wlanUpdateInFlight = false;
                        SERIES.giveUp();
                    }
                }
                KeepADBDiagnostics.event(appContext, "register_report", "webhook", "skipped",
                        "method=post reason=endpoint_unreachable reattempt=true");
                return;
            }
            performUpdateTransaction(appContext, targetUrl, endpoint, opGen);
        });
    }

    /** #866: caller must hold the class monitor. Schedules the next retry or gives the series up. */
    private static void schedulePostRetryLocked(Context context, String targetUrl, String endpoint,
            long opGen) {
        Runnable retry = new Runnable() {
            @Override
            public void run() {
                runPostRetry(context, targetUrl, endpoint, opGen, this);
            }
        };
        SERIES.scheduleRetry(mainHandler(), retry, SystemClock.elapsedRealtime());
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
            if (!SERIES.takeRetry(self)) return;
            if (opGen != currentOpGeneration || !enabled || !targetUrl.equals(currentUrl)
                    || !endpoint.equals(currentEndpoint)) {
                SERIES.cancel(mainHandler());
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
            SERIES.cancel(mainHandler());
            // #868: without an endpoint there is nothing to report, so no failure episode either.
            KeepADBWebhookAlert.endEpisode(appContext);
            // #576 part C: a DELETE for this exact registered state is already queued or running.
            if (UNAVAILABLE.coalesces(currentOpGeneration)) {
                return;
            }
            boolean wasInFlight = wlanUpdateInFlight;
            // #923 (review R2): an unsettled dispatch (a POST whose answer was lost, or a stale
            // success at the target) may hold a registration as well; it is withdrawn here too.
            // Review R3: so is a queued withdrawal that is due again after a failed DELETE; only a
            // due one counts, so its own backoff and attempt budget still bound the traffic.
            boolean hadPrior = (lastRegisteredEndpoint != null && !lastRegisteredEndpoint.trim().isEmpty())
                    || (lastRegisteredUrl != null && !lastRegisteredUrl.trim().isEmpty())
                    || KeepADBPreferences.getWebhookDispatchedUrl(appContext) != null
                    || PendingCleanupCoordinator.hasDue(appContext);
            if (!wasInFlight && !hadPrior) {
                return;
            }
            // #562: rapid-fire refreshes and network callbacks must not re-issue the DELETE on
            // every call once an attempt failed.
            if (UNAVAILABLE.isWaiting()) {
                return;
            }
            wlanUpdateInFlight = false;
            opGen = ++currentOpGeneration;
            UNAVAILABLE.dispatched(opGen);
        }

        EXECUTOR.execute(() -> {
            try {
                if (opGen != currentOpGeneration) return;
                performDeleteTransaction(appContext, targetUrl, opGen, true);
            } finally {
                synchronized (KeepADBRegisterClient.class) {
                    UNAVAILABLE.finished(opGen);
                }
            }
        });
    }

    static void unregisterAndDisableAsync(Context context) {
        unregisterAndDisableAsync(context, null);
    }

    /**
     * @param onDone #911: runs on the register executor once the withdrawal queued here has been
     *         attempted (or found obsolete), so a receiver can keep the process alive until then
     *         ({@code goAsync}) instead of letting it die with the DELETE still queued.
     */
    static void unregisterAndDisableAsync(Context context, Runnable onDone) {
        if (context == null) {
            if (onDone != null) onDone.run();
            return;
        }
        Context appContext = context.getApplicationContext();
        final String targetUrl = KeepADBPreferences.getRegisterWebhookUrl(appContext);
        final long opGen;
        synchronized (KeepADBRegisterClient.class) {
            ensureStateInitializedLocked(appContext);
            SERIES.cancel(mainHandler());
            KeepADBWebhookAlert.endEpisode(appContext);
            wlanUpdateInFlight = false;
            opGen = ++currentOpGeneration;
        }

        EXECUTOR.execute(() -> {
            if (opGen != currentOpGeneration) return;
            performDeleteTransaction(appContext, targetUrl, opGen, false);
        });
        // FIFO on the single register executor: behind the withdrawal just queued.
        if (onDone != null) EXECUTOR.execute(onDone);
    }

    /** Runs {@code work} on the register executor behind everything queued so far. */
    static void afterQueuedWork(Runnable work) {
        EXECUTOR.execute(work);
    }

    /**
     * #911: whether an opt-out has a withdrawal outstanding, so "off" while already off retries it.
     * That is the persisted report snapshot still naming the registration at the URL that is saved
     * now (a completed withdrawal clears the snapshot, a failed or interrupted one leaves it; a
     * record of a replaced or cleared URL is not contacted from here), and (#935) a destination the
     * app may have registered to without a record: a dispatch marker or a queued withdrawal that
     * is due now (#923). Read from the preferences, which survive the process.
     */
    static boolean hasOutstandingWithdrawal(Context context) {
        Context app = context.getApplicationContext();
        String recorded = KeepADBPreferences.getWebhookLastReportedUrl(app);
        String saved = KeepADBPreferences.getRegisterWebhookUrl(app);
        if (recorded != null && !recorded.trim().isEmpty() && recorded.equals(saved)) return true;
        return KeepADBPreferences.getWebhookDispatchedUrl(app) != null
                || PendingCleanupCoordinator.hasDue(app);
    }

    // ---- Transactions (register executor only) ----

    private static void performUpdateTransaction(Context context, String targetUrl, String targetEndpoint, long opGen) {
        PendingCleanupCoordinator.flush(context, targetUrl, false);

        String oldUrl;
        String oldEndpoint;
        synchronized (KeepADBRegisterClient.class) {
            oldUrl = lastRegisteredUrl;
            oldEndpoint = lastRegisteredEndpoint;
        }

        // If URL changed and a prior local report exists, try DELETE at the old URL first
        String unfinishedCleanupUrl = null;
        if (oldUrl != null && !oldUrl.equals(targetUrl) && oldEndpoint != null) {
            TransportResult oldDelete = RegisterTransport.deleteEndpoint(oldUrl);
            // #892 (Codex review): the DELETE at the previous URL belongs in the history too.
            KeepADBReportHistory.unregister(context, oldEndpoint, oldDelete.ok, oldDelete.outcome, 0);
            if (oldDelete.ok) {
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
        int retry;
        synchronized (KeepADBRegisterClient.class) {
            // Codex review: a new series may reset the counter while this request is in flight.
            retry = SERIES.retryNumber();
        }
        // Codex review: the budget in effect when the request is sent, not after its answer.
        int maxRetries = SERIES.retryBudget();
        // #923: persisted before the request, settled after it (below), so a registration made by
        // a POST whose transaction went stale, or whose process died, is still withdrawn later.
        String markerBefore = KeepADBPreferences.getWebhookDispatchedUrl(context);
        KeepADBPreferences.setWebhookDispatchedUrl(context, targetUrl);
        TransportResult post = RegisterTransport.postEndpoint(targetUrl, targetEndpoint);
        // #923 (review): only an HTTP answer proves this request registered nothing; an I/O error
        // can come after the body was delivered, so the marker stays as evidence then. A definite
        // rejection restores the marker this POST found (an earlier, unsettled one stays).
        if (!post.ok && post.isHttpAnswer()) {
            KeepADBPreferences.setWebhookDispatchedUrl(context, markerBefore);
        }
        recordReportOutcome(context, "post", targetEndpoint, post, failingBefore, opGen, retry,
                maxRetries);
        if (post.ok) {
            // #671: the primary POST can block for seconds, during which a disconnect or a newer
            // endpoint may have superseded this transaction. Abort before any secondary transport
            // is sent, so a stale snapshot never goes out for an outdated target. Same outcome as
            // the stale branch of the synchronized block below: no state, preference or listener
            // side effects (the newer operation owns those) -- except #923: the receiver may now
            // hold a registration at targetUrl that no state accounts for; queue its withdrawal.
            if (opGen != currentOpGeneration) {
                PendingCleanupCoordinator.rememberPossibleRegistration(context, targetUrl, false);
                return;
            }
            // Consider other verified transport candidates only after the WLAN POST. The local
            // sender allowlist currently holds these candidates back; WLAN success accounting and
            // the stored report snapshot remain owned by this transaction.
            RegisterTransport.reportAdditionalVerifiedTransports(context, targetUrl);
            synchronized (KeepADBRegisterClient.class) {
                if (opGen == currentOpGeneration) {
                    // Persist pending cleanup before changing the local report snapshot, so a
                    // crash cannot forget the old URL. If the replacement POST failed, the local
                    // snapshot stays with the old target for a later retry. (#707: queuing also
                    // removes the record of an entry a full backlog evicted for it.)
                    if (cleanupToRemember != null) {
                        PendingCleanupCoordinator.queue(context, cleanupToRemember);
                    }
                    // A cleanup queued before this successful POST is stale local work. Drop it
                    // so it cannot issue an outdated DELETE after the newer registration attempt.
                    PendingCleanupCoordinator.removeForResource(context, targetUrl);
                    wlanUpdateInFlight = false;
                    // #866: success ends the retry series, #868 the failure episode.
                    SERIES.cancel(mainHandler());
                    KeepADBWebhookAlert.endEpisode(context);
                    // A successful WLAN report supersedes any pending unavailable retry for the
                    // previous local report.
                    UNAVAILABLE.reset();
                    lastRegisteredUrl = targetUrl;
                    lastRegisteredEndpoint = targetEndpoint;
                    // #317: one editor transaction; four separate apply() calls could be torn apart
                    // by a crash and leave a URL without its endpoint.
                    KeepADBPreferences.setWebhookReportSnapshot(context, targetUrl, targetEndpoint,
                            KeepADBPreferences.WEBHOOK_STATUS_SUCCESS, true);
                    // #923: the snapshot (which also clears the dispatch marker) now accounts
                    // for this registration.
                    RegisterStateNotifier.notifyChanged(context);
                    return;
                }
            }
            // #923: superseded during the secondary transports.
            PendingCleanupCoordinator.rememberPossibleRegistration(context, targetUrl, false);
        } else {
            synchronized (KeepADBRegisterClient.class) {
                if (opGen == currentOpGeneration) {
                    wlanUpdateInFlight = false;
                    KeepADBPreferences.setWebhookLastReportStatus(
                            context, KeepADBPreferences.WEBHOOK_STATUS_FAILED);
                    schedulePostRetryLocked(context, targetUrl, targetEndpoint, opGen);
                    KeepADBWebhookAlert.onReportFailed(context);
                    RegisterStateNotifier.notifyChanged(context);
                }
            }
        }
    }

    /**
     * {@code endpointLoss}: the DELETE belongs to the #562 endpoint-loss series, whose failures
     * count as retries in the #892 history; an explicit unregister always starts at zero.
     */
    private static void performDeleteTransaction(Context context, String targetUrl, long opGen,
            boolean endpointLoss) {
        List<String> cleanupsLeftHere = PendingCleanupCoordinator.flush(context, targetUrl, true);

        String urlToDelete;
        synchronized (KeepADBRegisterClient.class) {
            urlToDelete = (lastRegisteredUrl != null) ? lastRegisteredUrl : targetUrl;
            if (urlToDelete == null || urlToDelete.trim().isEmpty()) {
                wlanUpdateInFlight = false;
                lastRegisteredUrl = null;
                lastRegisteredEndpoint = null;
                UNAVAILABLE.reset();
                KeepADBPreferences.setWebhookReportSnapshot(context, null, null, null, false);
                RegisterStateNotifier.notifyChanged(context);
                return;
            }
        }

        boolean failingBefore = isReportFailing(context);
        String endpointToDelete;
        int retry;
        synchronized (KeepADBRegisterClient.class) {
            endpointToDelete = lastRegisteredEndpoint;
            // Codex review: captured before the request, and only for the endpoint-loss series;
            // its counter outlives the series, and an explicit unregister is none of its retries.
            retry = endpointLoss ? UNAVAILABLE.attempts() : 0;
        }
        TransportResult delete = RegisterTransport.deleteEndpoint(urlToDelete);
        recordReportOutcome(context, "delete", endpointToDelete, delete, failingBefore,
                opGen, retry, KeepADBReportHistory.NO_MAX);
        if (delete.ok) {
            // Record the successful DELETE response before the generation check, so a newer
            // operation does not leave the local report snapshot pointing at the old URL.
            synchronized (KeepADBRegisterClient.class) {
                if (urlToDelete.equals(lastRegisteredUrl)) {
                    lastRegisteredUrl = null;
                    lastRegisteredEndpoint = null;
                }
            }
            PendingCleanupCoordinator.removeForResource(context, urlToDelete);
            synchronized (KeepADBRegisterClient.class) {
                if (opGen == currentOpGeneration) {
                    wlanUpdateInFlight = false;
                    UNAVAILABLE.reset();
                    KeepADBPreferences.setWebhookReportSnapshot(context, null, null,
                            KeepADBPreferences.WEBHOOK_STATUS_DEREGISTERED, true);
                    RegisterStateNotifier.notifyChanged(context);
                }
            }
        } else {
            // #934: a due queued withdrawal of this resource that the flush left to this DELETE
            // has its failure booked, so its budget, backoff and expiry apply; an entry that was
            // not due is not charged for the transaction's own (#562-paced) attempt.
            PendingCleanupCoordinator.recordFailuresLeftToTransaction(context, cleanupsLeftHere);
            synchronized (KeepADBRegisterClient.class) {
                if (opGen == currentOpGeneration) {
                    wlanUpdateInFlight = false;
                    UNAVAILABLE.recordFailure();
                    KeepADBPreferences.setWebhookLastReportStatus(
                            context, KeepADBPreferences.WEBHOOK_STATUS_FAILED);
                    RegisterStateNotifier.notifyChanged(context);
                }
            }
        }
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
            TransportResult result, boolean failingBefore, long opGen, int retry, int maxRetries) {
        boolean superseded;
        synchronized (KeepADBRegisterClient.class) {
            superseded = opGen != currentOpGeneration;
        }
        boolean ok = result.ok;
        if ("delete".equals(method)) {
            KeepADBReportHistory.unregister(context, endpoint, ok, result.outcome, retry);
        } else {
            KeepADBReportHistory.report(context, endpoint, ok, result.outcome, retry, maxRetries);
        }
        if (ok && !failingBefore) return;
        // A success of a superseded transaction ends nothing: the newer operation owns the
        // report status (Codex review).
        if (ok && superseded) return;
        KeepADBDiagnostics.event(context, "register_report", "webhook", ok ? "success" : "failed",
                "method=" + method + " " + result.outcome + " retry=" + retry);
    }

    // ---- Transport (see RegisterTransport) ----

    static void setHttpTransport(RegisterTransport.HttpTransport transport) {
        RegisterTransport.set(transport);
    }

    static void resetHttpTransport() {
        RegisterTransport.reset();
    }

    /**
     * #350: log-facing redaction. Delegates to {@link KeepADBUrlRedaction} so logs and UI apply the
     * same rule for userinfo, host, port and fragment; the log variant additionally drops the path.
     */
    static String sanitizeUrl(String rawUrl) {
        if (rawUrl == null) return "null";
        String redacted = KeepADBUrlRedaction.forLog(rawUrl);
        return redacted.isEmpty() ? KeepADBUrlRedaction.UNPARSEABLE : redacted;
    }

    // ---- Test-only accessors: keep WLAN state verifiable. ----

    static synchronized void resetForTesting() {
        SERIES.resetForTesting(mainHandler());
        UNAVAILABLE.resetForTesting();
        endpointProbe = KeepADBRegisterClient::probeEndpoint;
        lastRegisteredUrl = null;
        lastRegisteredEndpoint = null;
        stateInitialized = false;
        currentOpGeneration = 0;
        lastReportOpGeneration = 0;
        wlanUpdateInFlight = false;
        coordinatorEndpointForTesting = null;
        beforeResyncDispatchForTesting = null;
        KeepADBWebhookAlert.resetForTesting();
        RegisterStateNotifier.resetForTesting();
        PendingCleanupCoordinator.setNowForTesting(null);
        resetHttpTransport();
        mainHandler = null;
    }

    /**
     * Blocks until the register executor has drained, exactly rather than timing-based: a barrier
     * on the single-threaded executor only runs once every task queued before it has finished.
     * Deliberately not {@code synchronized} -- the queued tasks take the class monitor themselves.
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
     * model "another operation superseded this one" at an exact point (e.g. inside a fake
     * transport callback while a request is in flight).
     */
    static synchronized void bumpOpGenerationForTesting() {
        ++currentOpGeneration;
    }

    static void flushPendingCleanupsForTesting(Context context) {
        PendingCleanupCoordinator.flush(context, null, false);
    }

    static void setPendingCleanupNowForTesting(long now) {
        PendingCleanupCoordinator.setNowForTesting(now);
    }

    static synchronized void setMarkUnavailableNowForTesting(long now) {
        UNAVAILABLE.setNowForTesting(now);
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

    /** #866: the endpoint a retry compares against; "" stands for none, null restores the coordinator. */
    static void setCoordinatorEndpointForTesting(String endpoint) {
        coordinatorEndpointForTesting = endpoint;
    }

    /** #867/#1049: runs after a resync or Report now read the endpoint, before the monitor. */
    static void setBeforeResyncDispatchForTesting(Runnable hook) {
        beforeResyncDispatchForTesting = hook;
    }

    static void setRetryBudgetForTesting(int retries) {
        SERIES.setRetryBudgetForTesting(retries);
    }

    static synchronized int getPostRetryNumberForTesting() {
        return SERIES.retryNumber();
    }

    static synchronized boolean hasPendingPostRetryForTesting() {
        return SERIES.pendingRetry() != null;
    }

    static synchronized boolean hasPendingResyncForTesting() {
        return SERIES.hasPendingResync();
    }

    static synchronized boolean isSeriesExhaustedForTesting() {
        return SERIES.isExhausted();
    }

    static synchronized int getMarkUnavailableRetryAttemptsForTesting() {
        return UNAVAILABLE.attempts();
    }

    static synchronized long getMarkUnavailableNextAttemptAtForTesting() {
        return UNAVAILABLE.nextAttemptAt();
    }
}
