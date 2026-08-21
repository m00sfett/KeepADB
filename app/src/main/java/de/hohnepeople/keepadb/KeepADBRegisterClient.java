package de.hohnepeople.keepadb;

import android.content.Context;
import android.util.Log;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONException;
import org.json.JSONObject;

/** Sends optional background reachability updates to a custom register or webhook endpoint. */
final class KeepADBRegisterClient {
    private static final String TAG = "KeepADBRegisterClient";
    private static final int TIMEOUT_MS = 2000;
    private static final String UNAVAILABLE_MARKER = "__UNAVAILABLE__";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "KeepADBRegisterPush");
        t.setDaemon(true);
        return t;
    });

    private static final AtomicReference<String> latestPendingEndpoint = new AtomicReference<>();
    private static volatile String lastRegisteredEndpoint = null;

    private KeepADBRegisterClient() {}

    static void updateEndpointAsync(Context context, String host, int port) {
        if (context == null || host == null || port <= 0) return;
        final String targetUrl = KeepADBPreferences.getRegisterWebhookUrl(context);
        if (targetUrl == null || targetUrl.trim().isEmpty()) {
            return;
        }
        String endpoint = host + ":" + port;
        if (endpoint.equals(lastRegisteredEndpoint)) {
            return;
        }
        latestPendingEndpoint.set(endpoint);
        EXECUTOR.execute(() -> {
            String target = latestPendingEndpoint.getAndSet(null);
            if (target == null || target.equals(lastRegisteredEndpoint)) {
                return;
            }
            if (UNAVAILABLE_MARKER.equals(target)) {
                if (deleteEndpoint(targetUrl)) {
                    lastRegisteredEndpoint = null;
                }
            } else {
                if (postEndpoint(targetUrl, target)) {
                    lastRegisteredEndpoint = target;
                }
            }
        });
    }

    static void markUnavailableAsync(Context context) {
        if (context == null) return;
        final String targetUrl = KeepADBPreferences.getRegisterWebhookUrl(context);
        if (targetUrl == null || targetUrl.trim().isEmpty()) {
            return;
        }
        if (lastRegisteredEndpoint == null && latestPendingEndpoint.get() == null) {
            return;
        }
        latestPendingEndpoint.set(UNAVAILABLE_MARKER);
        EXECUTOR.execute(() -> {
            String target = latestPendingEndpoint.getAndSet(null);
            if (target == null) {
                return;
            }
            if (UNAVAILABLE_MARKER.equals(target)) {
                if (deleteEndpoint(targetUrl)) {
                    lastRegisteredEndpoint = null;
                }
            } else {
                if (postEndpoint(targetUrl, target)) {
                    lastRegisteredEndpoint = target;
                }
            }
        });
    }

    private static boolean postEndpoint(String targetUrl, String endpoint) {
        HttpURLConnection conn = null;
        try {
            JSONObject payloadJson = new JSONObject();
            payloadJson.put("method", "wlan-adb");
            payloadJson.put("endpoint", endpoint);
            byte[] bytes = payloadJson.toString().getBytes(StandardCharsets.UTF_8);

            URL url = new URL(targetUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
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
            Log.w(TAG, "Could not update register at " + targetUrl + ": " + e.getMessage());
            return false;
        } catch (JSONException e) {
            Log.e(TAG, "Failed to build JSON payload for " + endpoint, e);
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static boolean deleteEndpoint(String targetUrl) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(targetUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("DELETE");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);

            int code = conn.getResponseCode();
            Log.d(TAG, "Register delete returned HTTP " + code);
            return code >= 200 && code < 300;
        } catch (IOException e) {
            Log.w(TAG, "Could not reach register to unregister at " + targetUrl + ": " + e.getMessage());
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
