package de.moos.wifiadb;

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

/** Sends background reachability updates to the central Tailscale phone register on moosgames2020. */
final class AdbWifiRegisterClient {
    private static final String TAG = "AdbWifiRegisterClient";
    private static final String TAILSCALE_REGISTER_URL = "http://100.111.111.21:50829/register/s20";
    private static final int TIMEOUT_MS = 2000;
    private static final String UNAVAILABLE_MARKER = "__UNAVAILABLE__";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AdbWifiRegisterPush");
        t.setDaemon(true);
        return t;
    });

    private static final AtomicReference<String> latestPendingEndpoint = new AtomicReference<>();
    private static volatile String lastRegisteredEndpoint = null;

    private AdbWifiRegisterClient() {}

    static void updateEndpointAsync(String host, int port) {
        if (host == null || port <= 0) return;
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
                if (deleteEndpoint()) {
                    lastRegisteredEndpoint = null;
                }
            } else {
                if (postEndpoint(target)) {
                    lastRegisteredEndpoint = target;
                }
            }
        });
    }

    static void markUnavailableAsync() {
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
                if (deleteEndpoint()) {
                    lastRegisteredEndpoint = null;
                }
            } else {
                if (postEndpoint(target)) {
                    lastRegisteredEndpoint = target;
                }
            }
        });
    }

    private static boolean postEndpoint(String endpoint) {
        HttpURLConnection conn = null;
        try {
            JSONObject payloadJson = new JSONObject();
            payloadJson.put("method", "wlan-adb");
            payloadJson.put("endpoint", endpoint);
            byte[] bytes = payloadJson.toString().getBytes(StandardCharsets.UTF_8);

            URL url = new URL(TAILSCALE_REGISTER_URL);
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
            // Tailnet might be unreachable when device is off-tailscale; non-fatal
            Log.w(TAG, "Could not update phone register at " + TAILSCALE_REGISTER_URL + ": " + e.getMessage());
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

    private static boolean deleteEndpoint() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(TAILSCALE_REGISTER_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("DELETE");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);

            int code = conn.getResponseCode();
            Log.d(TAG, "Register delete returned HTTP " + code);
            return code >= 200 && code < 300;
        } catch (IOException e) {
            // Tailnet might be unreachable when device is off-tailscale; non-fatal
            Log.w(TAG, "Could not reach phone register to unregister at " + TAILSCALE_REGISTER_URL + ": " + e.getMessage());
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
