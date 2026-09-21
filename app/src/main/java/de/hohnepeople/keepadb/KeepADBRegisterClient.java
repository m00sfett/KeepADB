package de.hohnepeople.keepadb;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Sends optional background reachability updates to a custom register or webhook endpoint. */
final class KeepADBRegisterClient {
    private static final String TAG = "KeepADBRegisterClient";
    private static final int TIMEOUT_MS = 2000;
    private static final String PREFS_NAME = "keepadb_prefs";
    private static final String KEY_PENDING_CLEANUP_RETRY_STATE =
            "register_webhook_pending_cleanup_retry_state";
    static final int MAX_PENDING_CLEANUP_ATTEMPTS = 3;
    static final long PENDING_CLEANUP_EXPIRY_MS = 24L * 60L * 60L * 1000L;
    static final long PENDING_CLEANUP_INITIAL_BACKOFF_MS = 30_000L;
    static final long PENDING_CLEANUP_MAX_BACKOFF_MS = 5L * 60L * 1000L;
    private static volatile Long pendingCleanupNowForTesting;
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
            boolean wasInFlight = wlanUpdateInFlight;
            boolean hadPrior = (lastRegisteredEndpoint != null || lastRegisteredUrl != null);
            wlanUpdateInFlight = false;
            opGen = ++currentOpGeneration;
            if (!wasInFlight && !hadPrior) {
                return;
            }
        }

        EXECUTOR.execute(() -> {
            if (opGen != currentOpGeneration) return;
            performDeleteTransaction(appContext, targetUrl, opGen);
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
     */
    private static boolean shouldAttemptPendingCleanup(Context context, String entry) {
        long now = pendingCleanupNow();
        PendingCleanupRetryState state = readPendingCleanupRetryState(context, entry, now);
        if (now >= state.expiresAt) {
            discardPendingCleanup(context, entry, "expired");
            return false;
        }
        if (state.attempts >= MAX_PENDING_CLEANUP_ATTEMPTS) {
            discardPendingCleanup(context, entry, "attempt_limit");
            return false;
        }
        return now >= state.nextAttemptAt;
    }

    private static void recordPendingCleanupFailure(Context context, String entry) {
        long now = pendingCleanupNow();
        PendingCleanupRetryState state = readPendingCleanupRetryState(context, entry, now);
        state.attempts++;
        if (state.attempts >= MAX_PENDING_CLEANUP_ATTEMPTS) {
            discardPendingCleanup(context, entry, "attempt_limit");
            return;
        }
        state.nextAttemptAt = saturatingAdd(now, pendingCleanupBackoffMs(state.attempts));
        writePendingCleanupRetryState(context, entry, state);
    }

    private static void discardPendingCleanup(Context context, String entry, String reason) {
        if (entry == null) return;
        KeepADBPreferences.removePendingWebhookCleanupUrl(context, entry);
        removePendingCleanupRetryState(context, entry);
        Log.w(TAG, "Dropping pending register cleanup (" + reason + "): " + sanitizeUrl(entry));
    }

    /**
     * A successful registration or cleanup overwrites/clears the shared register record for its
     * URL. Drop obsolete cleanups, including legacy entries whose stored
     * URL still contains userinfo or a fragment. The stored raw entry is still removed exactly;
     * only the resource comparison is canonicalized for server route identity.
     */
    private static void removePendingCleanupsForResource(Context context, String targetUrl) {
        if (context == null || targetUrl == null) return;
        String targetKey = registrationResourceKey(targetUrl);
        if (targetKey == null) return;

        for (String rawUrl : KeepADBPreferences.getPendingWebhookCleanupUrls(context)) {
            if (targetKey.equals(registrationResourceKey(rawUrl))) {
                KeepADBPreferences.removePendingWebhookCleanupUrl(context, rawUrl);
                removePendingCleanupRetryState(context, rawUrl);
            }
        }
    }

    private static long pendingCleanupNow() {
        Long testNow = pendingCleanupNowForTesting;
        return testNow != null ? testNow : System.currentTimeMillis();
    }

    private static long pendingCleanupBackoffMs(int attempts) {
        long backoff = PENDING_CLEANUP_INITIAL_BACKOFF_MS;
        for (int i = 1; i < attempts && backoff < PENDING_CLEANUP_MAX_BACKOFF_MS; i++) {
            if (backoff > PENDING_CLEANUP_MAX_BACKOFF_MS / 2L) {
                return PENDING_CLEANUP_MAX_BACKOFF_MS;
            }
            backoff *= 2L;
        }
        return Math.min(backoff, PENDING_CLEANUP_MAX_BACKOFF_MS);
    }

    private static long saturatingAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }

    private static final class PendingCleanupRetryState {
        int attempts;
        long nextAttemptAt;
        long expiresAt;

        PendingCleanupRetryState(int attempts, long nextAttemptAt, long expiresAt) {
            this.attempts = attempts;
            this.nextAttemptAt = nextAttemptAt;
            this.expiresAt = expiresAt;
        }
    }

    private static PendingCleanupRetryState readPendingCleanupRetryState(Context context, String entry,
            long now) {
        PendingCleanupRetryState fallback = new PendingCleanupRetryState(0, now,
                saturatingAdd(now, PENDING_CLEANUP_EXPIRY_MS));
        if (context == null || entry == null) return fallback;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String stored = prefs.getString(pendingCleanupRetryStateKey(entry), null);
        if (stored == null || stored.trim().isEmpty()) return fallback;
        String[] fields = stored.split(",", -1);
        if (fields.length != 3) {
            Log.w(TAG, "Ignoring malformed pending cleanup retry state");
            return fallback;
        }
        try {
            int attempts = Math.max(0, Integer.parseInt(fields[0]));
            long nextAttemptAt = Long.parseLong(fields[1]);
            long expiresAt = Long.parseLong(fields[2]);
            return new PendingCleanupRetryState(attempts, nextAttemptAt, expiresAt);
        } catch (NumberFormatException e) {
            Log.w(TAG, "Ignoring malformed pending cleanup retry state");
            return fallback;
        }
    }

    private static void writePendingCleanupRetryState(Context context, String entry,
            PendingCleanupRetryState state) {
        if (context == null || entry == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String encoded = state.attempts + "," + state.nextAttemptAt + "," + state.expiresAt;
        prefs.edit().putString(pendingCleanupRetryStateKey(entry), encoded).apply();
    }

    private static void removePendingCleanupRetryState(Context context, String entry) {
        if (context == null || entry == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String key = pendingCleanupRetryStateKey(entry);
        if (prefs.getString(key, null) == null) return;
        prefs.edit().remove(key).apply();
    }

    private static String pendingCleanupRetryStateKey(String entry) {
        return KEY_PENDING_CLEANUP_RETRY_STATE + ":" + entry;
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

        // If URL changed and an old URL was registered, DELETE from old URL first
        String unfinishedCleanupUrl = null;
        if (oldUrl != null && !oldUrl.equals(targetUrl) && oldEndpoint != null) {
            // Keep the previous successful report until the replacement POST succeeds. If the
            // new target fails, the UI must still show the last endpoint that was actually
            // reported successfully rather than losing it during this transition.
            if (!deleteEndpoint(oldUrl)) {
                Log.w(TAG, "Failed to deregister from old URL " + sanitizeUrl(oldUrl)
                        + " during URL change; keeping the cleanup for a later retry");
                unfinishedCleanupUrl = oldUrl;
            }
        }

        if (opGen != currentOpGeneration) return;

        // POST to new target URL
        final String cleanupToRemember = unfinishedCleanupUrl;
        if (postEndpoint(targetUrl, targetEndpoint)) {
            // #539: the same trigger now also reports every OTHER currently verified transport,
            // each into its own register slot. Deliberately after the WLAN POST and outside this
            // transaction's success accounting: the transaction is about `targetEndpoint`, whose
            // value must keep driving lastRegisteredEndpoint and the stored report snapshot
            // exactly as before. The additional transports are best-effort extra slots, never a
            // reason to mark the WLAN report failed.
            reportAdditionalVerifiedTransports(context, targetUrl);
            synchronized (KeepADBRegisterClient.class) {
                if (opGen == currentOpGeneration) {
                    // #317: write-ahead. The retry entry is persisted BEFORE the in-memory and
                    // stored state move on to the new URL, so a crash in between can only cause a
                    // redundant cleanup of a still-registered URL -- never a forgotten one. If the
                    // POST below had failed instead, the old URL would still be the registered one
                    // and the next transaction retries the migration on its own.
                    if (cleanupToRemember != null) {
                        KeepADBPreferences.addPendingWebhookCleanupUrl(context, cleanupToRemember);
                    }
                    // A queued DELETE for the URL we just registered with is obsolete: the POST
                    // above replaced the very record it was meant to retire. A DELETE at this URL
                    // can be raised by a failing cleanup (an already-absent record answers 404,
                    // which counts as a failure) while the POST that follows succeeds; flushing it
                    // later would silently erase the live registration.
                    removePendingCleanupsForResource(context, targetUrl);
                    wlanUpdateInFlight = false;
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
                KeepADBPreferences.setWebhookReportSnapshot(context, null, null, null, false);
                notifyRegisterStateListener();
                return;
            }
        }

        boolean cleanupCompleted = deleteEndpoint(urlToDelete);
        if (cleanupCompleted) {
            removePendingCleanupsForResource(context, urlToDelete);
            synchronized (KeepADBRegisterClient.class) {
                if (opGen == currentOpGeneration) {
                    wlanUpdateInFlight = false;
                    lastRegisteredUrl = null;
                    lastRegisteredEndpoint = null;
                    KeepADBPreferences.setWebhookReportSnapshot(context, null, null,
                            KeepADBPreferences.WEBHOOK_STATUS_DEREGISTERED, true);
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
        registerStateListener = null;
        pendingCleanupNowForTesting = null;
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

    static void flushPendingCleanupsForTesting(Context context) {
        flushPendingCleanups(context);
    }

    static void setPendingCleanupNowForTesting(long now) {
        pendingCleanupNowForTesting = now;
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

    private static final class DefaultHttpTransport implements HttpTransport {
        @Override
        public boolean postJson(String targetUrl, String payload, String logLabel) {
            HttpURLConnection conn = null;
            try {
                byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);

                URL url = new URL(targetUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setInstanceFollowRedirects(false);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setConnectTimeout(TIMEOUT_MS);
                conn.setReadTimeout(TIMEOUT_MS);
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(bytes.length);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bytes);
                    os.flush();
                }

                int code = conn.getResponseCode();
                Log.d(TAG, "Register update for " + logLabel + " returned HTTP " + code);
                return code >= 200 && code < 300;
            } catch (IOException e) {
                Log.w(TAG, "Could not update register at " + sanitizeUrl(targetUrl));
                return false;
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }

        @Override
        public boolean delete(String targetUrl) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(targetUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("DELETE");
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(TIMEOUT_MS);
                conn.setReadTimeout(TIMEOUT_MS);

                int code = conn.getResponseCode();
                Log.d(TAG, "Register delete returned HTTP " + code);
                return code >= 200 && code < 300;
            } catch (IOException e) {
                Log.w(TAG, "Could not reach register to unregister at " + sanitizeUrl(targetUrl));
                return false;
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
    }

    private static final HttpTransport DEFAULT_TRANSPORT = new DefaultHttpTransport();
    private static volatile HttpTransport httpTransport = DEFAULT_TRANSPORT;

    static void setHttpTransport(HttpTransport transport) {
        httpTransport = (transport != null) ? transport : DEFAULT_TRANSPORT;
    }

    static void resetHttpTransport() {
        httpTransport = DEFAULT_TRANSPORT;
    }

    /**
     * #539: reports the WLAN transport as a contract-v2 event. The payload keeps {@code method}
     * and {@code endpoint} exactly where the pre-v2 contract had them, so the register's legacy
     * projection and every existing {@code GET /register/<alias>} consumer keep the same view;
     * {@code contract_version}, {@code observed_at} and {@code event_id} are additive.
     */
    static boolean postEndpoint(String targetUrl, String endpoint) {
        KeepADBRegisterPayload.Event event =
                KeepADBRegisterPayload.wlanEvent(endpoint, System.currentTimeMillis());
        return sendJsonPost(targetUrl, event.json, endpoint);
    }

    /**
     * #539: reports every currently verified transport as its own contract-v2 event, so the
     * register keeps one independent slot per transport and a WLAN report can never clear the
     * Tailscale or USB slot. Events whose method the deployed register does not accept yet are
     * held back instead of being sent into a guaranteed HTTP 400; see
     * {@link KeepADBRegisterPayload#SERVER_SUPPORTED_METHODS}.
     *
     * @return {@code true} when every event that was actually sent succeeded. An empty transport
     *     list is a no-op returning {@code true}: "nothing verified right now" must not be turned
     *     into a clearing request here -- deactivation is an explicit, per-method event.
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
     * #539: the production entry into {@link #postTransports}. Called from
     * {@link #performUpdateTransaction} -- so it inherits that path's opt-in exactly: it is only
     * ever reached after {@link #updateEndpointAsync} confirmed
     * {@link KeepADBPreferences#isRegisterWebhookEnabled} and a non-empty webhook URL, and it
     * posts to that same user-entered URL. No new destination, no new trigger, no traffic for a
     * user who has not enabled the webhook.
     *
     * <p>The WLAN/LAN transport is filtered out here because the surrounding transaction already
     * reported it from its own authoritative {@code targetEndpoint}; re-deriving it from the
     * snapshot could publish a different (possibly newer or staler) value under the same slot and
     * desynchronise it from the stored report snapshot.
     *
     * <p>Runs on the register executor, which is where the blocking work belongs:
     * {@link KeepADBTransportOverview#current} may perform a socket connect while verifying a
     * Tailscale route.
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
