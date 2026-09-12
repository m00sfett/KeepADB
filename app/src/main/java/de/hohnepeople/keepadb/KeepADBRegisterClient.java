package de.hohnepeople.keepadb;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONException;
import org.json.JSONObject;

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

    // USB-ADB registration state is intentionally separate from the WLAN-ADB fields above:
    // the two register calls must be able to run concurrently without racing or clobbering
    // each other's dedup/idempotency state.
    private static volatile String lastRegisteredUsbUrl = null;
    private static volatile String lastRegisteredUsbPayload = null;
    private static volatile Integer lastRegisteredUsbProfileId = null;
    private static volatile String lastRegisteredUsbProfileName = null;
    private static volatile String lastRegisteredUsbIpAddress = null;
    private static volatile String lastRegisteredUsbHostname = null;
    private static volatile String lastRegisteredUsbTailnetHostname = null;
    private static volatile boolean usbStateInitialized = false;
    private static volatile long currentUsbOpGeneration = 0;
    private static volatile boolean usbUpdateInFlight = false;

    private static volatile String inFlightUsbTargetUrl = null;
    private static volatile Integer inFlightUsbProfileId = null;
    private static volatile String inFlightUsbProfileName = null;
    private static volatile String inFlightUsbIpAddress = null;
    private static volatile String inFlightUsbHostname = null;
    private static volatile String inFlightUsbTailnetHostname = null;

    private KeepADBRegisterClient() {}

    static synchronized void ensureStateInitializedLocked(Context context) {
        ensureUsbStateInitializedLocked(context);
        if (stateInitialized) return;
        if (context != null) {
            lastRegisteredEndpoint = KeepADBPreferences.getWebhookLastReportedEndpoint(context);
            lastRegisteredUrl = KeepADBPreferences.getWebhookLastReportedUrl(context);
        }
        stateInitialized = true;
    }

    static synchronized void ensureUsbStateInitializedLocked(Context context) {
        if (usbStateInitialized) return;
        if (context != null) {
            lastRegisteredUsbUrl = KeepADBPreferences.getUsbWebhookLastReportedUrl(context);
            lastRegisteredUsbPayload = KeepADBPreferences.getUsbWebhookLastReportedPayload(context);
            lastRegisteredUsbProfileId = KeepADBPreferences.getUsbWebhookLastProfileId(context);
            lastRegisteredUsbProfileName = KeepADBPreferences.getUsbWebhookLastProfileName(context);
            lastRegisteredUsbIpAddress = KeepADBPreferences.getUsbWebhookLastIpAddress(context);
            lastRegisteredUsbHostname = KeepADBPreferences.getUsbWebhookLastHostname(context);
            lastRegisteredUsbTailnetHostname = KeepADBPreferences.getUsbWebhookLastTailnetHostname(context);
        }
        usbStateInitialized = true;
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

    /** Fires a USB-ADB register update for the given (already-selected) profile, if configured. */
    static void updateUsbEndpointAsync(Context context, KeepADBUsbProfile.Profile profile) {
        if (context == null || profile == null) return;
        Context appContext = context.getApplicationContext();
        boolean webhookEnabled = KeepADBPreferences.isRegisterWebhookEnabled(appContext);
        String targetUrl = KeepADBPreferences.getRegisterWebhookUrl(appContext);
        String deviceId = getDeviceId(appContext);
        updateUsbEndpointAsyncInternal(appContext, webhookEnabled, targetUrl, deviceId, profile.id, profile.name,
                profile.ipAddress, profile.hostname, profile.tailnetHostname);
    }

    static void updateUsbEndpointAsyncInternal(boolean webhookEnabled, String targetUrl, String deviceId,
            int profileId, String profileName, String ipAddress, String hostname, String tailnetHostname) {
        updateUsbEndpointAsyncInternal(null, webhookEnabled, targetUrl, deviceId, profileId, profileName,
                ipAddress, hostname, tailnetHostname);
    }

    static void updateUsbEndpointAsyncInternal(Context context, boolean webhookEnabled, String targetUrl, String deviceId,
            int profileId, String profileName, String ipAddress, String hostname, String tailnetHostname) {
        if (!webhookEnabled) return;
        if (targetUrl == null || targetUrl.trim().isEmpty()) return;
        final String payload = buildUsbPayload(deviceId, profileId, profileName, ipAddress, hostname,
                tailnetHostname, true);
        final long opGen;
        synchronized (KeepADBRegisterClient.class) {
            ensureUsbStateInitializedLocked(context);
            if (targetUrl.equals(lastRegisteredUsbUrl) && payload.equals(lastRegisteredUsbPayload)) {
                return;
            }
            opGen = ++currentUsbOpGeneration;
            usbUpdateInFlight = true;
            inFlightUsbTargetUrl = targetUrl;
            inFlightUsbProfileId = profileId;
            inFlightUsbProfileName = profileName;
            inFlightUsbIpAddress = ipAddress;
            inFlightUsbHostname = hostname;
            inFlightUsbTailnetHostname = tailnetHostname;
        }

        EXECUTOR.execute(() -> {
            if (opGen != currentUsbOpGeneration) return;
            flushPendingCleanups(context);
            final PendingUsbCleanup unfinishedCleanup = deactivateSupersededUsbRegistration(targetUrl, deviceId);
            if (opGen != currentUsbOpGeneration) return;
            if (sendJsonPost(targetUrl, payload, "usb-adb")) {
                synchronized (KeepADBRegisterClient.class) {
                    if (opGen == currentUsbOpGeneration) {
                        // Write-ahead, as in the WLAN path: remember the unfinished cleanup before
                        // the state stops pointing at the old URL.
                        if (unfinishedCleanup != null) {
                            KeepADBPreferences.addPendingUsbWebhookCleanup(context,
                                    unfinishedCleanup.url, unfinishedCleanup.payload);
                        }
                        // A queued cleanup for the URL we just registered with is obsolete: the
                        // POST above overwrote the very record it was meant to retire. Keeping it
                        // would let a later flush deactivate the live registration.
                        removePendingUsbCleanupRetryStateForUrl(context, targetUrl);
                        KeepADBPreferences.removePendingUsbWebhookCleanupsForUrl(context, targetUrl);
                        usbUpdateInFlight = false;
                        inFlightUsbTargetUrl = null;
                        inFlightUsbProfileId = null;
                        inFlightUsbProfileName = null;
                        inFlightUsbIpAddress = null;
                        inFlightUsbHostname = null;
                        inFlightUsbTailnetHostname = null;
                        lastRegisteredUsbUrl = targetUrl;
                        lastRegisteredUsbPayload = payload;
                        lastRegisteredUsbProfileId = profileId;
                        lastRegisteredUsbProfileName = profileName;
                        lastRegisteredUsbIpAddress = ipAddress;
                        lastRegisteredUsbHostname = hostname;
                        lastRegisteredUsbTailnetHostname = tailnetHostname;
                        if (context != null) {
                            KeepADBPreferences.setUsbWebhookLastReportedState(context, targetUrl, payload,
                                    profileId, profileName, ipAddress, hostname, tailnetHostname,
                                    KeepADBPreferences.WEBHOOK_STATUS_SUCCESS);
                        }
                        notifyRegisterStateListener();
                    }
                }
            } else {
                synchronized (KeepADBRegisterClient.class) {
                    if (opGen == currentUsbOpGeneration) {
                        usbUpdateInFlight = false;
                        inFlightUsbTargetUrl = null;
                        inFlightUsbProfileId = null;
                        inFlightUsbProfileName = null;
                        inFlightUsbIpAddress = null;
                        inFlightUsbHostname = null;
                        inFlightUsbTailnetHostname = null;
                        // #317: symmetric to the WLAN path -- a failed USB report is recorded and
                        // surfaced instead of dying silently inside the executor.
                        KeepADBPreferences.setUsbWebhookLastReportStatus(
                                context, KeepADBPreferences.WEBHOOK_STATUS_FAILED);
                        notifyRegisterStateListener();
                    }
                }
            }
        });
    }

    /**
     * #317: when the webhook URL changes, tell the previous URL that this device is gone.
     * The WLAN path always did this (via DELETE); USB silently left an active registration behind.
     * A failed deactivation is kept as a retryable pending cleanup instead of being dropped.
     */
    private static PendingUsbCleanup deactivateSupersededUsbRegistration(String newUrl, String deviceId) {
        final String oldUrl;
        final Integer oldProfileId;
        final String oldProfileName;
        final String oldIpAddress;
        final String oldHostname;
        final String oldTailnetHostname;
        synchronized (KeepADBRegisterClient.class) {
            oldUrl = lastRegisteredUsbUrl;
            if (oldUrl == null || oldUrl.equals(newUrl)) {
                return null;
            }
            oldProfileId = lastRegisteredUsbProfileId;
            oldProfileName = lastRegisteredUsbProfileName;
            oldIpAddress = lastRegisteredUsbIpAddress;
            oldHostname = lastRegisteredUsbHostname;
            oldTailnetHostname = lastRegisteredUsbTailnetHostname;
        }

        String inactivePayload = buildUsbPayload(deviceId, oldProfileId, oldProfileName, oldIpAddress,
                oldHostname, oldTailnetHostname, false);
        if (hasLiveOtherProtocolRegistrationAtUrl(oldUrl, true)) {
            return null;
        }
        if (sendJsonPost(oldUrl, inactivePayload, "usb-adb")) {
            return null;
        }
        Log.w(TAG, "Failed to deactivate USB registration at old URL " + sanitizeUrl(oldUrl)
                + " during URL change; keeping the cleanup for a later retry");
        return new PendingUsbCleanup(oldUrl, inactivePayload);
    }

    /** An {@code active:false} report for a superseded URL that still has to be delivered. */
    private static final class PendingUsbCleanup {
        final String url;
        final String payload;

        PendingUsbCleanup(String url, String payload) {
            this.url = url;
            this.payload = payload;
        }
    }

    /**
     * #317: retries cleanups that a previous URL change could not deliver. Runs on the register
     * executor before the transaction that triggered it, so a lost cleanup is retried at the next
     * register activity (endpoint change, deregistration, USB connect) rather than being forgotten.
     * The backlog is capped by {@link KeepADBPreferences#MAX_PENDING_CLEANUPS}.
     */
    private static void flushPendingCleanups(Context context) {
        if (context == null) return;
        for (String url : KeepADBPreferences.getPendingWebhookCleanupUrls(context)) {
            if (hasLiveOtherProtocolRegistrationAtUrl(url, false)) {
                continue;
            }
            if (!shouldAttemptPendingCleanup(context, url)) {
                continue;
            }
            if (deleteEndpoint(url)) {
                KeepADBPreferences.removePendingWebhookCleanupUrl(context, url);
                removePendingCleanupRetryState(context, url);
            } else {
                recordPendingCleanupFailure(context, url);
            }
        }
        for (String entry : KeepADBPreferences.getPendingUsbWebhookCleanups(context)) {
            String url = KeepADBPreferences.pendingCleanupUrl(entry);
            String payload = KeepADBPreferences.pendingCleanupPayload(entry);
            if (url == null || payload == null) {
                KeepADBPreferences.removePendingUsbWebhookCleanup(context, entry);
                removePendingCleanupRetryState(context, entry);
                continue;
            }
            if (hasLiveOtherProtocolRegistrationAtUrl(url, true)) {
                continue;
            }
            if (!shouldAttemptPendingCleanup(context, entry)) {
                continue;
            }
            if (sendJsonPost(url, payload, "usb-adb")) {
                KeepADBPreferences.removePendingUsbWebhookCleanup(context, entry);
                removePendingCleanupRetryState(context, entry);
            } else {
                recordPendingCleanupFailure(context, entry);
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
        String logTarget;
        if (entry.indexOf('\n') >= 0) {
            KeepADBPreferences.removePendingUsbWebhookCleanup(context, entry);
            logTarget = KeepADBPreferences.pendingCleanupUrl(entry);
        } else {
            KeepADBPreferences.removePendingWebhookCleanupUrl(context, entry);
            logTarget = entry;
        }
        removePendingCleanupRetryState(context, entry);
        Log.w(TAG, "Dropping pending register cleanup (" + reason + "): " + sanitizeUrl(logTarget));
    }

    private static void removePendingUsbCleanupRetryStateForUrl(Context context, String url) {
        if (context == null || url == null) return;
        for (String entry : KeepADBPreferences.getPendingUsbWebhookCleanups(context)) {
            if (url.equals(KeepADBPreferences.pendingCleanupUrl(entry))) {
                removePendingCleanupRetryState(context, entry);
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
        String stored = prefs.getString(KEY_PENDING_CLEANUP_RETRY_STATE, null);
        if (stored == null || stored.trim().isEmpty()) return fallback;
        try {
            JSONObject root = new JSONObject(stored);
            JSONObject record = root.optJSONObject(entry);
            if (record == null) return fallback;
            int attempts = Math.max(0, record.optInt("attempts", 0));
            long nextAttemptAt = record.optLong("nextAttemptAt", now);
            long expiresAt = record.optLong("expiresAt", fallback.expiresAt);
            return new PendingCleanupRetryState(attempts, nextAttemptAt, expiresAt);
        } catch (JSONException e) {
            Log.w(TAG, "Ignoring malformed pending cleanup retry state");
            return fallback;
        }
    }

    private static void writePendingCleanupRetryState(Context context, String entry,
            PendingCleanupRetryState state) {
        if (context == null || entry == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        JSONObject root;
        try {
            String stored = prefs.getString(KEY_PENDING_CLEANUP_RETRY_STATE, null);
            root = (stored == null || stored.trim().isEmpty()) ? new JSONObject() : new JSONObject(stored);
            JSONObject record = new JSONObject();
            record.put("attempts", state.attempts);
            record.put("nextAttemptAt", state.nextAttemptAt);
            record.put("expiresAt", state.expiresAt);
            root.put(entry, record);
            prefs.edit().putString(KEY_PENDING_CLEANUP_RETRY_STATE, root.toString()).apply();
        } catch (JSONException e) {
            Log.w(TAG, "Could not persist pending cleanup retry state");
        }
    }

    private static void removePendingCleanupRetryState(Context context, String entry) {
        if (context == null || entry == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String stored = prefs.getString(KEY_PENDING_CLEANUP_RETRY_STATE, null);
        if (stored == null || stored.trim().isEmpty()) return;
        try {
            JSONObject root = new JSONObject(stored);
            if (root.optJSONObject(entry) == null) return;
            root.remove(entry);
            SharedPreferences.Editor editor = prefs.edit();
            if (root.length() == 0) {
                editor.remove(KEY_PENDING_CLEANUP_RETRY_STATE);
            } else {
                editor.putString(KEY_PENDING_CLEANUP_RETRY_STATE, root.toString());
            }
            editor.apply();
        } catch (JSONException e) {
            prefs.edit().remove(KEY_PENDING_CLEANUP_RETRY_STATE).apply();
            Log.w(TAG, "Clearing malformed pending cleanup retry state");
        }
    }

    /** Marks the previously-registered USB-ADB profile inactive; a no-op if nothing was registered. */
    static void markUsbInactiveAsync(Context context) {
        if (context == null) return;
        Context appContext = context.getApplicationContext();
        boolean webhookEnabled = KeepADBPreferences.isRegisterWebhookEnabled(appContext);
        String targetUrl = KeepADBPreferences.getRegisterWebhookUrl(appContext);
        String deviceId = getDeviceId(appContext);
        markUsbInactiveAsyncInternal(appContext, webhookEnabled, targetUrl, deviceId);
    }

    static void markUsbInactiveAsyncInternal(boolean webhookEnabled, String configuredUrl, String deviceId) {
        markUsbInactiveAsyncInternal(null, webhookEnabled, configuredUrl, deviceId);
    }

    static void markUsbInactiveAsyncInternal(Context context, boolean webhookEnabled, String configuredUrl, String deviceId) {
        if (!webhookEnabled) return;
        final String urlToUse;
        final Integer profileId;
        final String profileName;
        final String ipAddress;
        final String hostname;
        final String tailnetHostname;
        final long opGen;
        synchronized (KeepADBRegisterClient.class) {
            ensureUsbStateInitializedLocked(context);
            boolean wasInFlight = usbUpdateInFlight;
            boolean hadPrior = (lastRegisteredUsbUrl != null || lastRegisteredUsbPayload != null);
            usbUpdateInFlight = false;
            opGen = ++currentUsbOpGeneration;

            urlToUse = (lastRegisteredUsbUrl != null) ? lastRegisteredUsbUrl
                    : ((inFlightUsbTargetUrl != null) ? inFlightUsbTargetUrl : configuredUrl);
            profileId = (lastRegisteredUsbProfileId != null) ? lastRegisteredUsbProfileId : inFlightUsbProfileId;
            profileName = (lastRegisteredUsbProfileName != null) ? lastRegisteredUsbProfileName : inFlightUsbProfileName;
            ipAddress = (lastRegisteredUsbIpAddress != null) ? lastRegisteredUsbIpAddress : inFlightUsbIpAddress;
            hostname = (lastRegisteredUsbHostname != null) ? lastRegisteredUsbHostname : inFlightUsbHostname;
            tailnetHostname = (lastRegisteredUsbTailnetHostname != null) ? lastRegisteredUsbTailnetHostname : inFlightUsbTailnetHostname;

            inFlightUsbTargetUrl = null;
            inFlightUsbProfileId = null;
            inFlightUsbProfileName = null;
            inFlightUsbIpAddress = null;
            inFlightUsbHostname = null;
            inFlightUsbTailnetHostname = null;

            if (!wasInFlight && !hadPrior) {
                return;
            }
        }

        if (urlToUse == null || urlToUse.trim().isEmpty()) {
            synchronized (KeepADBRegisterClient.class) {
                if (opGen == currentUsbOpGeneration) {
                    // #372: an existing USB report with no usable URL cannot be sent anywhere,
                    // but it still needs to leave the local and persisted state as explicitly
                    // inactive. The no-op return above remains before this branch when nothing
                    // was registered or in flight.
                    clearUsbStateLocked(context, KeepADBPreferences.WEBHOOK_STATUS_DEREGISTERED);
                    notifyRegisterStateListener();
                }
            }
            return;
        }

        final String payload = buildUsbPayload(deviceId, profileId, profileName, ipAddress, hostname,
                tailnetHostname, false);

        EXECUTOR.execute(() -> {
            if (opGen != currentUsbOpGeneration) return;
            boolean cleanupCompleted = hasLiveOtherProtocolRegistrationAtUrl(urlToUse, true)
                    || sendJsonPost(urlToUse, payload, "usb-adb");
            if (cleanupCompleted) {
                synchronized (KeepADBRegisterClient.class) {
                    if (opGen == currentUsbOpGeneration) {
                        clearUsbStateLocked(context, KeepADBPreferences.WEBHOOK_STATUS_DEREGISTERED);
                        notifyRegisterStateListener();
                    }
                }
            } else {
                // #317: a failed deactivation is reported like a failed WLAN deregistration, and
                // the registration stays known so a later attempt can still clean it up.
                synchronized (KeepADBRegisterClient.class) {
                    if (opGen == currentUsbOpGeneration) {
                        KeepADBPreferences.setUsbWebhookLastReportStatus(
                                context, KeepADBPreferences.WEBHOOK_STATUS_FAILED);
                        notifyRegisterStateListener();
                    }
                }
            }
        });
    }

    private static void clearUsbStateLocked(Context context) {
        clearUsbStateLocked(context, null);
    }

    private static void clearUsbStateLocked(Context context, String status) {
        usbUpdateInFlight = false;
        inFlightUsbTargetUrl = null;
        inFlightUsbProfileId = null;
        inFlightUsbProfileName = null;
        inFlightUsbIpAddress = null;
        inFlightUsbHostname = null;
        inFlightUsbTailnetHostname = null;
        lastRegisteredUsbUrl = null;
        lastRegisteredUsbPayload = null;
        lastRegisteredUsbProfileId = null;
        lastRegisteredUsbProfileName = null;
        lastRegisteredUsbIpAddress = null;
        lastRegisteredUsbHostname = null;
        lastRegisteredUsbTailnetHostname = null;
        if (context != null) {
            KeepADBPreferences.clearUsbWebhookReportedState(context, status);
        }
    }

    static String getDeviceId(Context context) {
        if (context == null) return "";
        try {
            String id = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
            return id == null ? "" : id;
        } catch (Exception e) {
            return "";
        }
    }

    static String buildUsbPayload(String deviceId, Integer profileId, String profileName, String ipAddress,
            String hostname, String tailnetHostname, boolean active) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"method\":\"usb-adb\"");
        sb.append(",\"deviceId\":\"").append(jsonEscape(deviceId)).append("\"");
        if (profileId != null) {
            sb.append(",\"profileId\":").append(profileId);
        }
        sb.append(",\"profileName\":\"").append(jsonEscape(profileName)).append("\"");
        sb.append(",\"ipAddress\":\"").append(jsonEscape(ipAddress)).append("\"");
        sb.append(",\"hostname\":\"").append(jsonEscape(hostname)).append("\"");
        sb.append(",\"tailnetHostname\":\"").append(jsonEscape(tailnetHostname)).append("\"");
        sb.append(",\"active\":").append(active);
        sb.append("}");
        return sb.toString();
    }

    private static String jsonEscape(String value) {
        if (value == null) return "";
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format(java.util.Locale.US, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
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
            if (hasLiveOtherProtocolRegistrationAtUrl(oldUrl, false)) {
                // phone-register-server stores one last_successful_reach per alias; DELETE is not
                // protocol-specific. Leave the shared record alone while USB still uses this URL.
            } else if (!deleteEndpoint(oldUrl)) {
                Log.w(TAG, "Failed to deregister from old URL " + sanitizeUrl(oldUrl)
                        + " during URL change; keeping the cleanup for a later retry");
                unfinishedCleanupUrl = oldUrl;
            }
        }

        if (opGen != currentOpGeneration) return;

        // POST to new target URL
        final String cleanupToRemember = unfinishedCleanupUrl;
        if (postEndpoint(targetUrl, targetEndpoint)) {
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
                    // later -- from a USB transaction, which does not re-post -- would silently
                    // erase the live registration.
                    removePendingCleanupRetryState(context, targetUrl);
                    KeepADBPreferences.removePendingWebhookCleanupUrl(context, targetUrl);
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

        boolean cleanupCompleted = hasLiveOtherProtocolRegistrationAtUrl(urlToDelete, false)
                || deleteEndpoint(urlToDelete);
        if (cleanupCompleted) {
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

    /**
     * The register server has one {@code last_successful_reach} record per alias, so a cleanup
     * request for one protocol also clears a live registration made by the other protocol. The
     * protocol-local successful snapshots are the client-side ownership signal; skip only the
     * cross-protocol cleanup when that peer snapshot still points at the same URL.
     */
    private static synchronized boolean hasLiveOtherProtocolRegistrationAtUrl(String cleanupUrl,
            boolean cleanupIsUsb) {
        if (cleanupUrl == null || cleanupUrl.trim().isEmpty()) {
            return false;
        }
        if (cleanupIsUsb) {
            return cleanupUrl.equals(lastRegisteredUrl) && hasText(lastRegisteredEndpoint);
        }
        return cleanupUrl.equals(lastRegisteredUsbUrl) && hasText(lastRegisteredUsbPayload);
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    static synchronized void resetForTesting() {
        lastRegisteredUrl = null;
        lastRegisteredEndpoint = null;
        stateInitialized = false;
        currentOpGeneration = 0;
        wlanUpdateInFlight = false;
        registerStateListener = null;
        clearUsbStateLocked(null);
        usbStateInitialized = false;
        currentUsbOpGeneration = 0;
        usbUpdateInFlight = false;
        pendingCleanupNowForTesting = null;
        resetHttpTransport();
        mainHandler = null;
    }

    // ---- Test-only accessors: keep WLAN and USB state independently verifiable. ----

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

    static void setUsbStateForTesting(String url, String payload, Integer profileId, String profileName,
            String ipAddress, String hostname, String tailnetHostname) {
        lastRegisteredUsbUrl = url;
        lastRegisteredUsbPayload = payload;
        lastRegisteredUsbProfileId = profileId;
        lastRegisteredUsbProfileName = profileName;
        lastRegisteredUsbIpAddress = ipAddress;
        lastRegisteredUsbHostname = hostname;
        lastRegisteredUsbTailnetHostname = tailnetHostname;
        usbStateInitialized = true;
    }

    static String getLastRegisteredUsbUrlForTesting() {
        return lastRegisteredUsbUrl;
    }

    static String getLastRegisteredUsbPayloadForTesting() {
        return lastRegisteredUsbPayload;
    }

    static Integer getLastRegisteredUsbProfileIdForTesting() {
        return lastRegisteredUsbProfileId;
    }

    static String getLastRegisteredUsbProfileNameForTesting() {
        return lastRegisteredUsbProfileName;
    }

    static String getLastRegisteredUsbIpAddressForTesting() {
        return lastRegisteredUsbIpAddress;
    }

    static String getLastRegisteredUsbHostnameForTesting() {
        return lastRegisteredUsbHostname;
    }

    static String getLastRegisteredUsbTailnetHostnameForTesting() {
        return lastRegisteredUsbTailnetHostname;
    }

    static boolean isUsbStateInitializedForTesting() {
        return usbStateInitialized;
    }

    static boolean isWlanUpdateInFlightForTesting() {
        return wlanUpdateInFlight;
    }

    static boolean isUsbUpdateInFlightForTesting() {
        return usbUpdateInFlight;
    }

    static Integer getInFlightUsbProfileIdForTesting() {
        return inFlightUsbProfileId;
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

    static boolean postEndpoint(String targetUrl, String endpoint) {
        String payload = String.format(java.util.Locale.US, "{\"method\":\"wlan-adb\",\"endpoint\":\"%s\"}", endpoint);
        return sendJsonPost(targetUrl, payload, endpoint);
    }

    private static boolean sendJsonPost(String targetUrl, String payload, String logLabel) {
        return httpTransport.postJson(targetUrl, payload, logLabel);
    }

    static boolean deleteEndpoint(String targetUrl) {
        return httpTransport.delete(targetUrl);
    }
}
