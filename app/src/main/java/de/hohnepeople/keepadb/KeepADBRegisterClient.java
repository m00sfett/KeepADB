package de.hohnepeople.keepadb;

import android.content.Context;
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
            if (lastRegisteredEndpoint == null && lastRegisteredUrl == null) {
                return;
            }
            opGen = ++currentOpGeneration;
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
        }

        EXECUTOR.execute(() -> {
            if (opGen != currentUsbOpGeneration) return;
            if (sendJsonPost(targetUrl, payload, "usb-adb")) {
                synchronized (KeepADBRegisterClient.class) {
                    if (opGen == currentUsbOpGeneration) {
                        lastRegisteredUsbUrl = targetUrl;
                        lastRegisteredUsbPayload = payload;
                        lastRegisteredUsbProfileId = profileId;
                        lastRegisteredUsbProfileName = profileName;
                        lastRegisteredUsbIpAddress = ipAddress;
                        lastRegisteredUsbHostname = hostname;
                        lastRegisteredUsbTailnetHostname = tailnetHostname;
                        if (context != null) {
                            KeepADBPreferences.setUsbWebhookLastReportedState(context, targetUrl, payload,
                                    profileId, profileName, ipAddress, hostname, tailnetHostname);
                        }
                        notifyRegisterStateListener();
                    }
                }
            }
        });
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
            if (lastRegisteredUsbUrl == null && lastRegisteredUsbPayload == null) {
                return;
            }
            urlToUse = (lastRegisteredUsbUrl != null) ? lastRegisteredUsbUrl : configuredUrl;
            profileId = lastRegisteredUsbProfileId;
            profileName = lastRegisteredUsbProfileName;
            ipAddress = lastRegisteredUsbIpAddress;
            hostname = lastRegisteredUsbHostname;
            tailnetHostname = lastRegisteredUsbTailnetHostname;
            opGen = ++currentUsbOpGeneration;
        }

        if (urlToUse == null || urlToUse.trim().isEmpty()) {
            synchronized (KeepADBRegisterClient.class) {
                if (opGen == currentUsbOpGeneration) {
                    clearUsbStateLocked(context);
                }
            }
            return;
        }

        final String payload = buildUsbPayload(deviceId, profileId, profileName, ipAddress, hostname,
                tailnetHostname, false);

        EXECUTOR.execute(() -> {
            if (opGen != currentUsbOpGeneration) return;
            if (sendJsonPost(urlToUse, payload, "usb-adb")) {
                synchronized (KeepADBRegisterClient.class) {
                    if (opGen == currentUsbOpGeneration) {
                        clearUsbStateLocked(context);
                        notifyRegisterStateListener();
                    }
                }
            }
        });
    }

    private static void clearUsbStateLocked(Context context) {
        lastRegisteredUsbUrl = null;
        lastRegisteredUsbPayload = null;
        lastRegisteredUsbProfileId = null;
        lastRegisteredUsbProfileName = null;
        lastRegisteredUsbIpAddress = null;
        lastRegisteredUsbHostname = null;
        lastRegisteredUsbTailnetHostname = null;
        if (context != null) {
            KeepADBPreferences.clearUsbWebhookReportedState(context);
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
        String oldUrl;
        String oldEndpoint;
        synchronized (KeepADBRegisterClient.class) {
            oldUrl = lastRegisteredUrl;
            oldEndpoint = lastRegisteredEndpoint;
        }

        // If URL changed and an old URL was registered, DELETE from old URL first
        if (oldUrl != null && !oldUrl.equals(targetUrl) && oldEndpoint != null) {
            boolean deleted = deleteEndpoint(oldUrl);
            if (deleted) {
                synchronized (KeepADBRegisterClient.class) {
                    if (oldUrl.equals(lastRegisteredUrl)) {
                        lastRegisteredUrl = null;
                        lastRegisteredEndpoint = null;
                        KeepADBPreferences.setWebhookLastReportedUrl(context, null);
                        KeepADBPreferences.setWebhookLastReportedEndpoint(context, null);
                    }
                }
            } else {
                Log.w(TAG, "Failed to deregister from old URL " + sanitizeUrl(oldUrl)
                        + " during URL change; proceeding with new registration");
            }
        }

        if (opGen != currentOpGeneration) return;

        // POST to new target URL
        if (postEndpoint(targetUrl, targetEndpoint)) {
            synchronized (KeepADBRegisterClient.class) {
                if (opGen == currentOpGeneration) {
                    lastRegisteredUrl = targetUrl;
                    lastRegisteredEndpoint = targetEndpoint;
                    KeepADBPreferences.setWebhookLastReportedAtNow(context);
                    KeepADBPreferences.setWebhookLastReportedUrl(context, targetUrl);
                    KeepADBPreferences.setWebhookLastReportedEndpoint(context, targetEndpoint);
                    notifyRegisterStateListener();
                }
            }
        }
    }

    private static void performDeleteTransaction(Context context, String targetUrl, long opGen) {
        String urlToDelete;
        synchronized (KeepADBRegisterClient.class) {
            urlToDelete = (lastRegisteredUrl != null) ? lastRegisteredUrl : targetUrl;
            if (urlToDelete == null || urlToDelete.trim().isEmpty()) {
                lastRegisteredUrl = null;
                lastRegisteredEndpoint = null;
                KeepADBPreferences.setWebhookLastReportedUrl(context, null);
                KeepADBPreferences.setWebhookLastReportedEndpoint(context, null);
                notifyRegisterStateListener();
                return;
            }
        }

        if (deleteEndpoint(urlToDelete)) {
            synchronized (KeepADBRegisterClient.class) {
                if (opGen == currentOpGeneration) {
                    lastRegisteredUrl = null;
                    lastRegisteredEndpoint = null;
                    KeepADBPreferences.setWebhookLastReportedAtNow(context);
                    KeepADBPreferences.setWebhookLastReportedUrl(context, null);
                    KeepADBPreferences.setWebhookLastReportedEndpoint(context, null);
                    notifyRegisterStateListener();
                }
            }
        }
    }

    static synchronized void resetForTesting() {
        lastRegisteredUrl = null;
        lastRegisteredEndpoint = null;
        stateInitialized = false;
        currentOpGeneration = 0;
        registerStateListener = null;
        clearUsbStateLocked(null);
        usbStateInitialized = false;
        currentUsbOpGeneration = 0;
    }

    // ---- Test-only accessors: keep WLAN and USB state independently verifiable. ----

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

    static String sanitizeUrl(String rawUrl) {
        if (rawUrl == null) return "null";
        try {
            java.net.URI uri = new java.net.URI(rawUrl);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            int port = uri.getPort();
            StringBuilder sb = new StringBuilder();
            if (scheme != null) sb.append(scheme).append("://");
            if (host != null) sb.append(host);
            if (port > 0) sb.append(":").append(port);
            return sb.toString();
        } catch (Exception e) {
            return "redacted-url";
        }
    }

    static boolean postEndpoint(String targetUrl, String endpoint) {
        String payload = String.format(java.util.Locale.US, "{\"method\":\"wlan-adb\",\"endpoint\":\"%s\"}", endpoint);
        return sendJsonPost(targetUrl, payload, endpoint);
    }

    private static boolean sendJsonPost(String targetUrl, String payload, String logLabel) {
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

    static boolean deleteEndpoint(String targetUrl) {
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
