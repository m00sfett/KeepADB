package de.hohnepeople.keepadb;

import android.content.Context;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;

/**
 * #1049: the send paths of {@link KeepADBRegisterClient}, extracted from the client: the
 * replaceable {@link HttpTransport} seam (#326, default {@link KeepADBHttpTransport}, #700) and the
 * request builders for the WLAN event, the other verified transports and the DELETE.
 *
 * <p>Holds only the selected transport. It decides nothing: no generation, retry, executor or
 * monitor lives here, and the client only calls it from its register executor, outside its class
 * monitor. Every request returns a {@link TransportResult}.
 */
final class RegisterTransport {
    // Same tag as the client, so existing log filters keep matching.
    private static final String TAG = "KeepADBRegisterClient";

    interface HttpTransport {
        TransportResult postJson(String targetUrl, String payload, String logLabel);
        TransportResult delete(String targetUrl);
    }

    private static final HttpTransport DEFAULT_TRANSPORT = new KeepADBHttpTransport();
    private static volatile HttpTransport httpTransport = DEFAULT_TRANSPORT;

    private RegisterTransport() {}

    static void set(HttpTransport transport) {
        httpTransport = (transport != null) ? transport : DEFAULT_TRANSPORT;
    }

    static void reset() {
        httpTransport = DEFAULT_TRANSPORT;
    }

    /**
     * Sends the locally generated WLAN event as a JSON POST. A successful result reports only the
     * HTTP response class; processing and storage by an external receiver are not observed here.
     */
    static TransportResult postEndpoint(String targetUrl, String endpoint) {
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
            List<KeepADBRegisterPayload.VerifiedTransport> verified) {
        boolean allSent = true;
        for (KeepADBRegisterPayload.Event event : KeepADBRegisterPayload.buildEvents(verified)) {
            if (!event.serverSupported) {
                Log.i(TAG, "Holding back register event for unsupported method " + event.method);
                continue;
            }
            if (!sendJsonPost(targetUrl, event.json, event.method).ok) {
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
    static void reportAdditionalVerifiedTransports(Context context, String targetUrl) {
        if (context == null || targetUrl == null || targetUrl.trim().isEmpty()) return;
        List<KeepADBRegisterPayload.VerifiedTransport> additional = new ArrayList<>();
        for (KeepADBRegisterPayload.VerifiedTransport transport
                : KeepADBRegisterPayload.fromSnapshot(KeepADBTransportOverview.current(context))) {
            if (transport.type == KeepADBRegisterPayload.Type.WLAN_LAN) continue;
            additional.add(transport);
        }
        if (additional.isEmpty()) return;
        postTransports(targetUrl, additional);
    }

    private static TransportResult sendJsonPost(String targetUrl, String payload, String logLabel) {
        return httpTransport.postJson(targetUrl, payload, logLabel);
    }

    static TransportResult deleteEndpoint(String targetUrl) {
        return httpTransport.delete(targetUrl);
    }
}
