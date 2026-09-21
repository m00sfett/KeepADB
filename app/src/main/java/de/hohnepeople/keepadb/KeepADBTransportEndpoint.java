package de.hohnepeople.keepadb;

/**
 * A single verified ADB transport surfaced to the UI (#538): WLAN/LAN, Tailscale/VPN or USB.
 * Immutable value object, always built fresh by {@link KeepADBTransportOverview#current} --
 * never cached beyond that one snapshot, matching the project convention that every surface
 * reads live state instead of keeping its own persistent app state.
 *
 * <p>An instance only ever exists once its own transport class has actually confirmed ADB
 * reachability -- a bare interface/candidate address that was never confirmed is not represented
 * here at all (see {@link KeepADBTransportOverview.Snapshot#vpnActiveNotAdbVerified} for the one
 * case, an active-but-unverified VPN, that is surfaced separately instead).
 */
final class KeepADBTransportEndpoint {

    enum Type { WLAN_LAN, TAILSCALE_VPN, USB }

    final Type type;
    /** Host (an IPv4/IPv6 literal). {@code null} for {@link Type#USB}, which has no network
     * endpoint of its own. */
    final String host;
    /** 0 when {@link #host} is {@code null}. */
    final int port;
    /** Epoch-millis ({@link System#currentTimeMillis()}) of the last successful ADB-reachability
     * confirmation for this transport, suitable for direct display via
     * {@link java.text.DateFormat}. */
    final long verifiedAtMs;
    /** Whether this is the transport a client would use by default among every transport in the
     * same {@link KeepADBTransportOverview.Snapshot}, in priority order WLAN/LAN, then
     * Tailscale/VPN, then USB. Exactly one entry in a non-empty snapshot carries {@code true}. */
    final boolean primary;

    KeepADBTransportEndpoint(Type type, String host, int port, long verifiedAtMs, boolean primary) {
        this.type = type;
        this.host = host;
        this.port = port;
        this.verifiedAtMs = verifiedAtMs;
        this.primary = primary;
    }

    /** Returns a copy with {@link #primary} replaced -- used once, by the aggregator, to flag
     * the first (highest-priority) entry of an otherwise-immutable list. */
    KeepADBTransportEndpoint withPrimary(boolean newPrimary) {
        if (newPrimary == primary) return this;
        return new KeepADBTransportEndpoint(type, host, port, verifiedAtMs, newPrimary);
    }

    boolean hasNetworkEndpoint() {
        return host != null && port > 0;
    }
}
