package de.hohnepeople.keepadb;

/**
 * #483: display-only masking of network addresses while the privacy mode from #482 is on.
 *
 * <p><b>Nothing here ever produces a transport value.</b> Every method takes a value that is about
 * to be rendered and returns text for that render pass only. The stored originals
 * ({@code register_webhook_url}, {@code register_webhook_last_endpoint}) and the addresses the app
 * actually connects to are untouched, so switching the toggle can never break the webhook.
 *
 * <h2>The rule (user decision of 2026-09-18)</h2>
 * <ul>
 *   <li><b>IPv4 literal</b> — first octet stays, the remaining three are masked:
 *       {@code 192.168.0.42} becomes {@code 192.*.*.*}. The subnet hint that #350 kept for logs is
 *       deliberately dropped here; this mode exists for screenshots and shoulder surfers.</li>
 *   <li><b>IPv6 literal</b> — the first address group stays, everything after it collapses to
 *       {@link #MASKED} : {@code fe80::1%wlan0} becomes {@code fe80:***}. A zone id is part of the
 *       masked remainder. An address that starts with {@code ::} has no first group to show and
 *       becomes {@link #MASKED} alone.</li>
 *   <li><b>Registered name (DNS host)</b> — unchanged. Explicitly out of scope per the same user
 *       decision: a hostname is not an address of the user's local network.</li>
 *   <li><b>Port</b> — always kept. "Did it report to :50829?" has to stay answerable.</li>
 * </ul>
 *
 * <p>This masking is <em>additional</em> to the URL redaction of #350/#378, not a replacement:
 * userinfo, query and fragment handling stay in {@link KeepADBUrlRedaction}, which calls
 * {@link #maskIpv4Octets(String[], int)} for the host part so both surfaces share one rule.
 */
final class KeepADBAddressMask {

    /** Stands in for every address part that is not the first one. */
    static final String MASKED = "***";

    private KeepADBAddressMask() {}

    /**
     * Masks a bare host (no port, no scheme). Hostnames pass through unchanged.
     *
     * @param host may be {@code null}, a hostname, an IPv4 literal, or an IPv6 literal with or
     *             without surrounding brackets and with or without a zone id.
     */
    static String maskHost(String host) {
        if (host == null) return null;
        String trimmed = host.trim();
        if (trimmed.isEmpty()) return host;

        if (trimmed.startsWith("[") && trimmed.endsWith("]") && trimmed.length() > 2) {
            return "[" + maskIpv6(trimmed.substring(1, trimmed.length() - 1)) + "]";
        }

        String[] octets = decimalOctets(trimmed);
        if (octets != null) return maskIpv4Octets(octets, 1);

        if (trimmed.indexOf(':') >= 0) return maskIpv6(trimmed);

        // Registered name: kept by explicit user decision.
        return trimmed;
    }

    /**
     * Masks the host inside a {@code host:port} / {@code [v6]:port} endpoint as produced by
     * {@link KeepADBEndpoint#formatEndpoint(String, int)}; the port stays visible.
     *
     * <p>A value that carries no recognisable port is masked as a bare host, so a partially
     * written or legacy stored endpoint is never rendered unmasked by accident.
     */
    static String maskEndpointForDisplay(String hostOrIpWithPort) {
        if (hostOrIpWithPort == null) return null;
        String trimmed = hostOrIpWithPort.trim();
        if (trimmed.isEmpty()) return hostOrIpWithPort;

        if (trimmed.startsWith("[")) {
            int close = trimmed.indexOf(']');
            if (close < 0) return maskHost(trimmed);
            String remainder = trimmed.substring(close + 1);
            return maskHost(trimmed.substring(0, close + 1)) + remainder;
        }

        int colon = trimmed.lastIndexOf(':');
        if (colon >= 0 && isDigits(trimmed.substring(colon + 1))
                && trimmed.substring(colon + 1).length() > 0
                && trimmed.substring(0, colon).indexOf(':') < 0) {
            return maskHost(trimmed.substring(0, colon)) + trimmed.substring(colon);
        }
        return maskHost(trimmed);
    }

    /**
     * Shared IPv4 rule for this class and {@link KeepADBUrlRedaction}: keep {@code visibleOctets}
     * leading octets verbatim, replace every remaining octet with a single {@code *}.
     */
    static String maskIpv4Octets(String[] parts, int visibleOctets) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append('.');
            if (i < visibleOctets) {
                sb.append(parts[i]);
            } else {
                sb.append('*');
            }
        }
        return sb.toString();
    }

    /** First group only; the rest — zone id included — collapses to {@link #MASKED}. */
    private static String maskIpv6(String address) {
        String withoutZone = address;
        int percent = withoutZone.indexOf('%');
        if (percent >= 0) withoutZone = withoutZone.substring(0, percent);
        int colon = withoutZone.indexOf(':');
        if (colon < 0) return withoutZone.isEmpty() ? MASKED : withoutZone;
        String first = withoutZone.substring(0, colon);
        return first.isEmpty() ? MASKED : first + ":" + MASKED;
    }

    /** {@code null} unless the value is exactly four decimal octets in range. */
    private static String[] decimalOctets(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return null;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) return null;
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) return null;
            }
            if (Integer.parseInt(part) > 255) return null;
        }
        return parts;
    }

    private static boolean isDigits(String value) {
        if (value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) return false;
        }
        return true;
    }
}
