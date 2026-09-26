package de.hohnepeople.keepadb;

import java.net.URI;
import java.util.Locale;

/**
 * #561: classifies whether a configured register-webhook target looks like a Tailscale/tailnet
 * address, purely to decide whether a failed webhook call should offer a "Tailscale might be
 * disconnected" hint. This is a heuristic on the already user-entered URL, nothing more:
 *
 * <ul>
 *   <li>It never proves a Tailscale outage -- a matching host can fail for any other reason
 *       (DNS, firewall, the register server itself being down), and the caller must keep
 *       phrasing the hint as a possibility, not a diagnosis.</li>
 *   <li>It never inspects, starts, stops or configures Tailscale, VPN state or any other network
 *       setting -- see #537/#548 for why KeepADB deliberately stays out of that.</li>
 *   <li>It only reads the host component of the URL the user already entered in Settings; no
 *       additional data leaves the device and nothing here is logged.</li>
 * </ul>
 *
 * <p>Matches per the #561 acceptance criteria: an IPv4 literal inside the Tailscale CGNAT range
 * {@code 100.64.0.0/10} (i.e. first octet 100, second octet 64-127), or a hostname ending in the
 * Tailscale MagicDNS suffix {@code .ts.net}. Anything else -- including a host that merely looks
 * unusual, or a URL that fails to parse -- is treated as "not classifiable" and must fall back to
 * the existing generic failure message.
 */
final class KeepADBTailnetHeuristic {

    private static final String MAGIC_DNS_SUFFIX = ".ts.net";

    private KeepADBTailnetHeuristic() {}

    static boolean looksLikeTailnetTarget(String url) {
        if (url == null) return false;
        String host = extractHost(url);
        if (host == null) return false;
        host = host.trim();
        if (host.isEmpty()) return false;
        return isMagicDnsHostname(host) || isCgnatIpv4(host);
    }

    private static String extractHost(String rawUrl) {
        try {
            return new URI(rawUrl.trim()).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isMagicDnsHostname(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        return lower.endsWith(MAGIC_DNS_SUFFIX) && lower.length() > MAGIC_DNS_SUFFIX.length();
    }

    /** {@code true} for an IPv4 literal whose first two octets fall inside {@code 100.64.0.0/10}. */
    private static boolean isCgnatIpv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return false;
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            Integer value = parseOctet(parts[i]);
            if (value == null) return false;
            octets[i] = value;
        }
        return octets[0] == 100 && octets[1] >= 64 && octets[1] <= 127;
    }

    private static Integer parseOctet(String part) {
        if (part.isEmpty() || part.length() > 3) return null;
        for (int i = 0; i < part.length(); i++) {
            if (!Character.isDigit(part.charAt(i))) return null;
        }
        int value;
        try {
            value = Integer.parseInt(part);
        } catch (NumberFormatException e) {
            return null;
        }
        return (value >= 0 && value <= 255) ? value : null;
    }
}
