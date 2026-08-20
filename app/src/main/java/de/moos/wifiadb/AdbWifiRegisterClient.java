package de.moos.wifiadb;

import android.util.Log;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Sends background reachability updates to the central Tailscale phone register on moosgames2020. */
final class AdbWifiRegisterClient {
    private static final String TAG = "AdbWifiRegisterClient";
    private static final String TAILSCALE_REGISTER_URL = "http://100.111.111.21:50829/register/s20";
    private static final int TIMEOUT_MS = 2000;

    private AdbWifiRegisterClient() {}

    static void updateEndpointAsync(String host, int port) {
        if (host == null || port <= 0) return;
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(TAILSCALE_REGISTER_URL);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setConnectTimeout(TIMEOUT_MS);
                conn.setReadTimeout(TIMEOUT_MS);
                conn.setDoOutput(true);

                String payload = "{\"method\":\"wlan-adb\",\"endpoint\":\"" + host + ":" + port + "\"}";
                byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(bytes.length);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bytes);
                    os.flush();
                }

                int code = conn.getResponseCode();
                Log.d(TAG, "Register update for " + host + ":" + port + " returned HTTP " + code);
            } catch (Exception e) {
                // Tailnet might be unreachable when device is off-tailscale; non-fatal
                Log.w(TAG, "Could not update phone register at " + TAILSCALE_REGISTER_URL + ": " + e.getMessage());
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }, "AdbWifiRegisterPush").start();
    }
}
