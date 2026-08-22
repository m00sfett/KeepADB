package de.hohnepeople.keepadb;

import android.content.Context;
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

    private KeepADBRegisterClient() {}

    static synchronized void ensureStateInitializedLocked(Context context) {
        if (stateInitialized) return;
        lastRegisteredEndpoint = KeepADBPreferences.getWebhookLastReportedEndpoint(context);
        lastRegisteredUrl = KeepADBPreferences.getWebhookLastReportedUrl(context);
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
                Log.w(TAG, "Failed to deregister from old URL " + oldUrl + " during URL change; proceeding with new registration");
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
    }

    static String sanitizeUrl(String rawUrl) {
        if (rawUrl == null) return "null";
        try {
            java.net.URI uri = new java.net.URI(rawUrl);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            int port = uri.getPort();
            String path = uri.getPath();
            StringBuilder sb = new StringBuilder();
            if (scheme != null) sb.append(scheme).append("://");
            if (host != null) sb.append(host);
            if (port > 0) sb.append(":").append(port);
            if (path != null && !path.isEmpty()) sb.append(path);
            return sb.toString();
        } catch (Exception e) {
            return "redacted-url";
        }
    }

    static boolean postEndpoint(String targetUrl, String endpoint) {
        HttpURLConnection conn = null;
        try {
            String payload = String.format(java.util.Locale.US, "{\"method\":\"wlan-adb\",\"endpoint\":\"%s\"}", endpoint);
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
            Log.d(TAG, "Register update for " + endpoint + " returned HTTP " + code);
            return code >= 200 && code < 300;
        } catch (IOException e) {
            Log.w(TAG, "Could not update register at " + sanitizeUrl(targetUrl) + ": " + e.getMessage());
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
            Log.w(TAG, "Could not reach register to unregister at " + sanitizeUrl(targetUrl) + ": " + e.getMessage());
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
