package de.hohnepeople.keepadb;

import android.util.Log;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * #700: the default {@link KeepADBRegisterClient.HttpTransport}, the only place that opens an
 * {@link HttpURLConnection}. It bundles the HTTP configuration (no redirects, 2000 ms connect and
 * read timeout, UTF-8 body with a fixed length) and the release of the connection, and reports
 * success for a 2xx response only. It is a stateless adapter: no executor, queue, timer, retry or
 * operation generation lives here; {@link KeepADBRegisterClient} still owns all of them and
 * decides what a result means.
 */
final class KeepADBHttpTransport implements KeepADBRegisterClient.HttpTransport {
    // Same tag as before the extraction, so existing log filters keep matching.
    private static final String TAG = "KeepADBRegisterClient";
    private static final int TIMEOUT_MS = 2000;

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
            Log.d(TAG, "Register update for " + KeepADBAddressMask.maskEndpointForDisplay(logLabel)
                    + " returned HTTP " + code);
            return code >= 200 && code < 300;
        } catch (IOException e) {
            Log.w(TAG, "Could not update register at " + KeepADBRegisterClient.sanitizeUrl(targetUrl));
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
            Log.w(TAG, "Could not reach register to unregister at " + KeepADBRegisterClient.sanitizeUrl(targetUrl));
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
