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

    private static void notifyRegisterStateListener() {
        RegisterStateListener listener = registerStateListener;
        if (listener != null) {
            mainHandler().post(listener::onRegisterStateChanged);
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
    private KeepADBRegisterClient() {}

    static synchronized void ensureStateInitializedLocked(Context context) {
        if (stateInitialized) return;
        if (context != null) {
            lastRegisteredEndpoint = KeepADBPreferences.getWebhookLastReportedEndpoint(context);
            lastRegisteredUrl = KeepADBPreferences.getWebhookLastReportedUrl(context);
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
            opGen = ++currentOpGeneration;
            wlanUpdateInFlight = true;
        }

        EXECUTOR.execute(() -> {
            if (opGen != currentOpGeneration) return;
            performUpdateTransaction(appContext, targetUrl, endpoint, opGen);
        });
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
            boolean hadPrior = (lastRegisteredEndpoint != null || lastRegisteredUrl != null);
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
     */
    private static void flushPendingCleanups(Context context) {
        if (context == null) return;
        for (String url : KeepADBPreferences.getPendingWebhookCleanupUrls(context)) {
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
        if (postEndpoint(targetUrl, targetEndpoint)) {
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
                        KeepADBPreferences.addPendingWebhookCleanupUrl(context, cleanupToRemember);
                    }
                    // A cleanup queued before this successful POST is stale local work. Drop it
                    // so it cannot issue an outdated DELETE after the newer registration attempt.
                    removePendingCleanupsForResource(context, targetUrl);
                    wlanUpdateInFlight = false;
                    // A successful WLAN report supersedes any pending unavailable retry for the
                    // previous local report.
                    resetMarkUnavailableRetryLocked();
                    lastRegisteredUrl = targetUrl;
                    lastRegisteredEndpoint = targetEndpoint;
                    // #317: one editor transaction; four separate apply() calls could be torn apart
                    // by a crash and leave a URL without its endpoint.
                    KeepADBPreferences.setWebhookReportSnapshot(context, targetUrl, targetEndpoint,
                            KeepADBPreferences.WEBHOOK_STATUS_SUCCESS, true);
                    notifyRegisterStateListener();
                }
            }
        } else {
            synchronized (KeepADBRegisterClient.class) {
                if (opGen == currentOpGeneration) {
                    wlanUpdateInFlight = false;
                    KeepADBPreferences.setWebhookLastReportStatus(
                            context, KeepADBPreferences.WEBHOOK_STATUS_FAILED);
                    notifyRegisterStateListener();
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
                notifyRegisterStateListener();
                return;
            }
        }

        boolean cleanupCompleted = deleteEndpoint(urlToDelete);
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
                    notifyRegisterStateListener();
                }
            }
        } else {
            synchronized (KeepADBRegisterClient.class) {
                if (opGen == currentOpGeneration) {
                    wlanUpdateInFlight = false;
                    recordMarkUnavailableRetryFailureLocked();
                    KeepADBPreferences.setWebhookLastReportStatus(
                            context, KeepADBPreferences.WEBHOOK_STATUS_FAILED);
                    notifyRegisterStateListener();
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
        lastRegisteredUrl = null;
        lastRegisteredEndpoint = null;
        stateInitialized = false;
        currentOpGeneration = 0;
        wlanUpdateInFlight = false;
        markUnavailableRetryAttempts = 0;
        markUnavailableNextAttemptAt = 0L;
        markUnavailableInFlightOpGen = 0L;
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
    static void awaitIdleForTesting(long timeoutMs) {
        java.util.concurrent.CountDownLatch drained = new java.util.concurrent.CountDownLatch(1);
        EXECUTOR.execute(drained::countDown);
        try {
            drained.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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

    private static boolean sendJsonPost(String targetUrl, String payload, String logLabel) {
        return httpTransport.postJson(targetUrl, payload, logLabel);
    }

    static boolean deleteEndpoint(String targetUrl) {
        return httpTransport.delete(targetUrl);
    }
}
