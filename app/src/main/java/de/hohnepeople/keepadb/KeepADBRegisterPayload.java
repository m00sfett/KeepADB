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
 * #539: builds the register/webhook wire payloads for every currently verified ADB transport.
 *
 * <p>Pure and side-effect free: it neither performs I/O nor reads app state, so the exact bytes
 * that go on the wire are unit-testable without a device, a network or the live register server.
 * The contract itself is specified in {@code docs/design/issue-539-multi-transport-register-contract.md};
 * this class is its only producer inside the app.
 *
 * <p>Key properties, all covered by {@code KeepADBRegisterPayloadTest}:
 * <ul>
 *   <li><b>One event per transport.</b> Every verified transport becomes its own event carrying
 *       its own {@code method}. The register server keeps one slot per method, so WLAN, Tailscale
 *       and USB updates never overwrite or clear one another.</li>
 *   <li><b>Only verified transports.</b> The input list is by construction the set of transports
 *       whose own class confirmed ADB reachability (see {@code KeepADBTransportOverview} once
 *       #538 has landed). A candidate or merely active interface never reaches this class, and an
 *       empty input produces no events rather than a clearing event.</li>
 *   <li><b>Idempotent repetition.</b> {@link #eventIdFor} derives the {@code event_id} from the
 *       reported state alone, not from the time of reporting. Re-sending an unchanged state
 *       therefore repeats the same {@code event_id}, which the server answers as {@code duplicate}
 *       without touching the stored slot. A genuine state change yields a different id and is
 *       accepted as a new event.</li>
 *   <li><b>No new secrets.</b> The payload carries exactly the endpoint data the register already
 *       held; the destination stays the user-entered, app-bound webhook URL.</li>
 * </ul>
 */
final class KeepADBRegisterPayload {

    /** Wire contract version. Must match the deployed server's {@code REGISTER_CONTRACT_VERSION}. */
    static final int CONTRACT_VERSION = 2;

    /** Value of the {@code source} field, distinguishing app-originated from host-originated events. */
    static final String SOURCE = "keepadb-app";

    private KeepADBRegisterPayload() {}

    /**
     * Transport classes and their wire {@code method} names. The names mirror the register's own
     * slot keys; {@link Type#WLAN_LAN} and {@link Type#TAILSCALE_VPN} are deliberately distinct
     * slots even when they currently share the same ADB port, because they are reachable over
     * different paths and expire independently.
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
     * Methods the currently deployed {@code phone-register-server} accepts. Everything else is
     * still built and returned, but flagged {@link Event#serverSupported} {@code false} so the
     * sender can hold it back instead of provoking an HTTP 400 against the live register.
     *
     * <p>This is the single constant to widen once the server-side change (its
     * {@code VALID_METHODS} set, and accepting an active USB event that carries no endpoint yet)
     * has been deployed. It is intentionally not a user-facing setting.
     */
    static final Set<String> SERVER_SUPPORTED_METHODS =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList(Type.WLAN_LAN.method)));

    /**
     * Test-only override of {@link #SERVER_SUPPORTED_METHODS}. Exists so a test can simulate the
     * future, widened server without editing the constant: "events are held back today" and
     * "the very same production path sends them once the server accepts them" are two different
     * claims, and only the second one proves the reporting path is actually wired up.
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

    /** One verified transport, as handed in by the caller. Mirrors the #538 snapshot entry. */
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
     * Builds the single WLAN event for an already-formatted {@code host:port} endpoint -- the path
     * the current register client uses. Kept separate from {@link #buildEvents} so the existing
     * WLAN reporting keeps working unchanged while the multi-transport path is introduced.
     */
    static Event wlanEvent(String endpoint, long verifiedAtMs) {
        return activeEvent(Type.WLAN_LAN, emptyToNull(endpoint), verifiedAtMs);
    }

    /**
     * Builds the deactivation event for one transport. It names its {@code method} explicitly, so
     * it can only ever clear its own slot -- the server rejects a deactivation whose slot would
     * have to be inherited from the legacy projection.
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
     * Derives the idempotency key from the reported state only. Two reports of the same transport
     * state share an id and the second one is a no-op on the server; any change of endpoint or of
     * the active flag produces a different id and is applied. The digest keeps the id a bounded,
     * opaque token instead of embedding the endpoint in a second place on the wire.
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

    /** ISO-8601 in UTC, the {@code observed_at} format the register server parses. */
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
