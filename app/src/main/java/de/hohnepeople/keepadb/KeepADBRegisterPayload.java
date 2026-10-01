package de.hohnepeople.keepadb;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Builds JSON events from locally verified ADB transports.
 *
 * <p>This class performs no I/O. KeepADBRegisterPayloadTest checks the generated data without a
 * device or live receiver. The current sender gate permits only WLAN-ADB events; locally built
 * Tailscale and USB candidates are held back before transmission. See docs/webhook-register.md.
 */
final class KeepADBRegisterPayload {

    /** Locally emitted event schema version; external receiver compatibility is not verified here. */
    static final int CONTRACT_VERSION = 2;

    /** Value of the {@code source} field, distinguishing app-originated from host-originated events. */
    static final String SOURCE = "keepadb-app";

    private KeepADBRegisterPayload() {}

    /**
     * Locally generated transport labels. The app can build WLAN, Tailscale and USB candidates,
     * while the separate sender allowlist currently permits WLAN-ADB only.
     */
    enum Type {
        WLAN_LAN("wlan-adb"),
        TAILSCALE_VPN("tailscale-adb"),
        USB("usb-adb");

        final String method;

        Type(String method) {
            this.method = method;
        }
    }

    /**
     * Local sender allowlist retained under its existing name: only wlan-adb is currently sent.
     * This value describes app behavior, not the methods accepted by an external server.
     */
    static final Set<String> SERVER_SUPPORTED_METHODS =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList(Type.WLAN_LAN.method)));

    /**
     * Test-only override of the local send allowlist, used to exercise event dispatch without
     * changing production policy or relying on an external receiver.
     */
    private static volatile Set<String> serverSupportedMethodsForTesting;

    static void setServerSupportedMethodsForTesting(Set<String> methods) {
        serverSupportedMethodsForTesting =
                (methods == null) ? null : Collections.unmodifiableSet(new HashSet<>(methods));
    }

    private static boolean serverSupports(String method) {
        Set<String> override = serverSupportedMethodsForTesting;
        return (override != null ? override : SERVER_SUPPORTED_METHODS).contains(method);
    }

    /** One transport from the app's verified-transport snapshot. */
    static final class VerifiedTransport {
        final Type type;
        /** {@code null} for {@link Type#USB}, which has no network endpoint of its own. */
        final String host;
        /** 0 when {@link #host} is {@code null}. */
        final int port;
        /** Epoch millis of the last successful ADB-reachability confirmation. */
        final long verifiedAtMs;

        VerifiedTransport(Type type, String host, int port, long verifiedAtMs) {
            this.type = type;
            this.host = host;
            this.port = port;
            this.verifiedAtMs = verifiedAtMs;
        }
    }

    /** One ready-to-send register event. */
    static final class Event {
        final String method;
        final String eventId;
        final String json;
        final boolean serverSupported;

        Event(String method, String eventId, String json, boolean serverSupported) {
            this.method = method;
            this.eventId = eventId;
            this.json = json;
            this.serverSupported = serverSupported;
        }
    }

    /**
     * Adapts a #538 transport snapshot into this class' input. The snapshot is the app's single
     * source of verified transports, so this is the only place that has to know both shapes; the
     * order (and with it the snapshot's primary-first priority) is preserved.
     *
     * <p>{@link KeepADBTransportOverview.Snapshot#vpnActiveNotAdbVerified} is deliberately not
     * mapped: an active but unverified VPN interface is a display state, never a reported
     * endpoint.
     */
    static List<VerifiedTransport> fromSnapshot(KeepADBTransportOverview.Snapshot snapshot) {
        List<VerifiedTransport> verified = new ArrayList<>();
        if (snapshot == null || snapshot.transports == null) {
            return Collections.unmodifiableList(verified);
        }
        for (KeepADBTransportEndpoint entry : snapshot.transports) {
            if (entry == null) continue;
            Type type = typeFor(entry.type);
            if (type == null) continue;
            verified.add(new VerifiedTransport(type, entry.host, entry.port, entry.verifiedAtMs));
        }
        return Collections.unmodifiableList(verified);
    }

    private static Type typeFor(KeepADBTransportEndpoint.Type type) {
        if (type == null) return null;
        switch (type) {
            case WLAN_LAN:
                return Type.WLAN_LAN;
            case TAILSCALE_VPN:
                return Type.TAILSCALE_VPN;
            case USB:
                return Type.USB;
            default:
                // A transport kind this wire contract has no slot for is dropped rather than
                // reported under a guessed method.
                return null;
        }
    }

    /**
     * Builds one event per verified transport, in the order given. Entries with an unusable
     * description (unknown type, or a network transport without a usable host/port) are skipped
     * rather than published as reachable.
     */
    static List<Event> buildEvents(List<VerifiedTransport> verified) {
        List<Event> events = new ArrayList<>();
        if (verified == null) return Collections.unmodifiableList(events);
        for (VerifiedTransport entry : verified) {
            if (entry == null || entry.type == null) continue;
            String endpoint = endpointFor(entry);
            if (entry.type != Type.USB && endpoint == null) {
                // A network transport without a confirmed host:port is not a reachable route.
                continue;
            }
            events.add(activeEvent(entry.type, endpoint, entry.verifiedAtMs));
        }
        return Collections.unmodifiableList(events);
    }

    /**
     * Builds the WLAN event from the confirmed host:port value supplied by the register client.
     */
    static Event wlanEvent(String endpoint, long verifiedAtMs) {
        return activeEvent(Type.WLAN_LAN, emptyToNull(endpoint), verifiedAtMs);
    }

    /**
     * Builds an inactive event for contract tests. The production client currently unregisters
     * with HTTP DELETE instead; receiver-side semantics are outside this app-source contract.
     */
    static Event inactiveEvent(Type type, long observedAtMs) {
        String method = type.method;
        String eventId = eventIdFor(method, null, false);
        String json = "{"
                + "\"contract_version\":" + CONTRACT_VERSION
                + ",\"method\":" + quote(method)
                + ",\"active\":false"
                + ",\"source\":" + quote(SOURCE)
                + ",\"observed_at\":" + quote(isoUtc(observedAtMs))
                + ",\"event_id\":" + quote(eventId)
                + "}";
        return new Event(method, eventId, json, serverSupports(method));
    }

    private static Event activeEvent(Type type, String endpoint, long verifiedAtMs) {
        String method = type.method;
        String eventId = eventIdFor(method, endpoint, true);
        StringBuilder json = new StringBuilder(160);
        json.append("{\"contract_version\":").append(CONTRACT_VERSION)
                .append(",\"method\":").append(quote(method))
                .append(",\"active\":true");
        if (endpoint != null) {
            json.append(",\"endpoint\":").append(quote(endpoint));
        }
        json.append(",\"source\":").append(quote(SOURCE))
                .append(",\"observed_at\":").append(quote(isoUtc(verifiedAtMs)))
                .append(",\"event_id\":").append(quote(eventId))
                .append('}');
        return new Event(method, eventId, json.toString(), serverSupports(method));
    }

    /**
     * Derives a deterministic, bounded identifier from the method, endpoint and active state.
     * Repeating the same reported state therefore produces the same identifier.
     */
    static String eventIdFor(String method, String endpoint, boolean active) {
        String state = method + "|" + (endpoint == null ? "" : endpoint) + "|" + active;
        return "keepadb-v" + CONTRACT_VERSION + "-" + method + "-" + shortDigest(state);
    }

    private static String endpointFor(VerifiedTransport entry) {
        if (entry.type == Type.USB) return null;
        if (entry.host == null || entry.host.trim().isEmpty() || entry.port <= 0) return null;
        return KeepADBEndpoint.formatEndpoint(entry.host, entry.port);
    }

    private static String shortDigest(String value) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory on every supported Android release; fall back to a stable,
            // non-cryptographic key rather than dropping the event's idempotency entirely.
            return String.format(Locale.US, "%08x", value.hashCode());
        }
        StringBuilder hex = new StringBuilder(16);
        for (int i = 0; i < 8; i++) {
            hex.append(String.format(Locale.US, "%02x", digest[i]));
        }
        return hex.toString();
    }

    /** Formats the observed-at timestamp as ISO-8601 in UTC. */
    static String isoUtc(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).toString();
    }

    private static String emptyToNull(String value) {
        return (value == null || value.trim().isEmpty()) ? null : value;
    }

    /** Minimal JSON string escaping; every value produced here is ASCII endpoint/method data. */
    private static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c < 0x20) {
                out.append(String.format(Locale.US, "\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.append('"').toString();
    }
}
