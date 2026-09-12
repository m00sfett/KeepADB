package de.hohnepeople.keepadb;

import java.util.Locale;

/**
 * #350: the single place that decides how a webhook URL may be shown to a human.
 *
 * <p>Before this class existed, three call sites redacted URLs on their own: the settings/status
 * screen masked only IPv4 hosts, the register client's log helper rebuilt {@code scheme://host:port}
 * through {@link java.net.URI}, and nothing at all handled query strings or IPv6 literals. A webhook
 * URL such as {@code http://user:pass@[fe80::1%eth0]:50829/register/s20?token=secret} therefore
 * leaked its query parameters into the UI and its host into logs.
 *
 * <p><b>This class never produces a transport URL.</b> Its output is display/diagnostic text only;
 * the URL that is actually requested comes from
 * {@link KeepADBPreferences#getRegisterWebhookUrl(android.content.Context)} and is untouched by
 * anything here. Redacting a URL that is about to be sent would break the webhook.
 *
 * <h2>The single rule (identical for UI and logs)</h2>
 * <ul>
 *   <li><b>Scheme</b> — kept, lower-cased. Only {@code http} and {@code https} are recognised;
 *       anything else yields {@link #UNPARSEABLE}, because an unrecognised shape cannot be
 *       decomposed safely and guessing risks printing the sensitive part.</li>
 *   <li><b>Userinfo</b> — removed entirely. It is credential material by definition and is never
 *       hinted at, not even by a placeholder.</li>
 *   <li><b>Host</b> — an IPv4 literal keeps its first two octets and masks the last two, one
 *       {@code *} per digit (the pre-existing rule, kept so the user still recognises their own
 *       subnet). Any IPv6 literal — bracketed or not, compressed or not, with or without a zone
 *       id — collapses to {@link #IPV6_MASK}: IPv6 addresses are frequently globally routable and
 *       have no octet boundary that would make a partial mask meaningful. A registered name
 *       (DNS host) is kept: it is the only remaining hint about which register the app talks to,
 *       and it is not an address of the user's local network.</li>
 *   <li><b>Port</b> — kept. It is load-bearing for diagnosis ("did it report to :50829?") and is
 *       not secret on its own. A port that cannot be recognised as digits is dropped together with
 *       the ambiguous host it belongs to.</li>
 *   <li><b>Path</b> — kept for {@link #forDisplay(String)} (it carries the device alias, which is
 *       exactly what the status line is for), dropped for {@link #forLog(String)}.</li>
 *   <li><b>Query</b> — never shown. Its presence is reported as {@code ?}{@link #QUERY_MASK} in
 *       display output so a user can tell that parameters exist; the values themselves are gone.</li>
 *   <li><b>Fragment</b> — removed entirely, without a marker.</li>
 * </ul>
 *
 * <p>Parsing is deliberately done by hand instead of via {@link java.net.URI}: a stored legacy value
 * may contain an un-encoded IPv6 zone id ({@code [fe80::1%eth0]}), which {@code URI} rejects with an
 * exception. A parser that throws on exactly the input that most needs redacting is the wrong tool
 * here — the fallback would either print the raw string or lose the diagnostic entirely.
 */
final class KeepADBUrlRedaction {

    /** Stands in for a query string; shows that parameters exist without showing any of them. */
    static final String QUERY_MASK = "***";

    /** Replaces any IPv6 literal, brackets included. */
    static final String IPV6_MASK = "[***]";

    /** Returned when the input cannot be decomposed with confidence. */
    static final String UNPARSEABLE = "[redacted-url]";

    private KeepADBUrlRedaction() {}

    /** UI text: scheme, redacted host, port, path, and a marker if a query was present. */
    static String forDisplay(String rawUrl) {
        return redact(rawUrl, true);
    }

    /** Log/diagnostics text: scheme, redacted host and port only — no path, no query marker. */
    static String forLog(String rawUrl) {
        return redact(rawUrl, false);
    }

    private static String redact(String rawUrl, boolean keepPathAndQueryMarker) {
        if (rawUrl == null) return "";
        String trimmed = rawUrl.trim();
        if (trimmed.isEmpty()) return "";

        // Fragment first: everything after '#' is out, and a '#' may itself contain '?' or '/'.
        String work = trimmed;
        int hash = work.indexOf('#');
        if (hash >= 0) work = work.substring(0, hash);

        boolean hadQuery = false;
        int question = work.indexOf('?');
        if (question >= 0) {
            hadQuery = true;
            work = work.substring(0, question);
        }

        int schemeEnd = work.indexOf("://");
        if (schemeEnd <= 0) return UNPARSEABLE;
        String scheme = work.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) return UNPARSEABLE;
        String rest = work.substring(schemeEnd + 3);

        int slash = rest.indexOf('/');
        String authority = slash >= 0 ? rest.substring(0, slash) : rest;
        String path = slash >= 0 ? rest.substring(slash) : "";

        // Userinfo: the last '@' inside the authority wins; an '@' may legally appear in a password.
        int at = authority.lastIndexOf('@');
        if (at >= 0) authority = authority.substring(at + 1);

        String hostPart;
        String portPart;
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close < 0) return UNPARSEABLE;
            hostPart = IPV6_MASK;
            portPart = bracketedPort(authority.substring(close + 1));
            if (portPart == null) return UNPARSEABLE;
        } else {
            int colon = authority.lastIndexOf(':');
            String host = authority;
            portPart = "";
            if (colon >= 0 && isDigits(authority.substring(colon + 1))) {
                host = authority.substring(0, colon);
                String port = authority.substring(colon + 1);
                portPart = port.isEmpty() ? "" : ":" + port;
            }
            if (host.indexOf(':') >= 0) {
                // A colon still left in an unbracketed host means a bare IPv6 literal such as
                // "http://::1:8080/". Host and port cannot be told apart there, so both go.
                hostPart = IPV6_MASK;
                portPart = "";
            } else if (host.isEmpty()) {
                return UNPARSEABLE;
            } else {
                hostPart = maskIpv4Host(host);
            }
        }

        StringBuilder result = new StringBuilder(scheme).append("://").append(hostPart).append(portPart);
        if (keepPathAndQueryMarker) {
            result.append(path);
            if (hadQuery) result.append('?').append(QUERY_MASK);
        }
        return result.toString();
    }

    /** {@code ""} or {@code ":<digits>"} after a bracketed host; {@code null} if it is neither. */
    private static String bracketedPort(String remainder) {
        if (remainder.isEmpty()) return "";
        if (remainder.charAt(0) != ':') return null;
        String port = remainder.substring(1);
        if (!isDigits(port)) return null;
        return port.isEmpty() ? "" : ":" + port;
    }

    /** Empty counts as digits, so a trailing ':' is treated as "no port" rather than as a host. */
    private static boolean isDigits(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) return false;
        }
        return true;
    }

    /** Keeps the first two octets of an IPv4 literal and masks the rest digit by digit. */
    private static String maskIpv4Host(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length == 4 && areDecimalOctets(parts)) {
            return maskIpv4Parts(parts);
        }

        Long address = parseIpv4Address(host);
        if (address == null) return host;
        String[] canonicalParts = {
            String.valueOf((address >>> 24) & 0xff),
            String.valueOf((address >>> 16) & 0xff),
            String.valueOf((address >>> 8) & 0xff),
            String.valueOf(address & 0xff)
        };
        return maskIpv4Parts(canonicalParts);
    }

    private static boolean areDecimalOctets(String[] parts) {
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) return false;
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) return false;
            }
            if (Integer.parseInt(part) > 255) return false;
        }
        return true;
    }

    /** Parses the historical one- to four-part IPv4 notation, including hexadecimal parts. */
    private static Long parseIpv4Address(String host) {
        String normalized = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        if (normalized.isEmpty()) return null;
        String[] parts = normalized.split("\\.", -1);
        if (parts.length < 1 || parts.length > 4) return null;

        long address = 0L;
        for (int i = 0; i < parts.length; i++) {
            int bits;
            if (parts.length == 1) {
                bits = 32;
            } else if (i == parts.length - 1) {
                bits = parts.length == 2 ? 24 : (parts.length == 3 ? 16 : 8);
            } else {
                bits = 8;
            }
            Long value = parseIpv4Part(parts[i]);
            if (value == null || value < 0 || value >= (1L << bits)) return null;
            address = (address << bits) | value;
        }
        return address;
    }

    private static Long parseIpv4Part(String part) {
        if (part.isEmpty()) return null;
        int radix = 10;
        int offset = 0;
        if (part.startsWith("0x") || part.startsWith("0X")) {
            radix = 16;
            offset = 2;
            if (offset == part.length()) return null;
        }
        for (int i = offset; i < part.length(); i++) {
            if (Character.digit(part.charAt(i), radix) < 0) return null;
        }
        try {
            return Long.parseLong(part.substring(offset), radix);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String maskIpv4Parts(String[] parts) {
        StringBuilder sb = new StringBuilder();
        sb.append(parts[0]).append('.').append(parts[1]).append('.');
        for (int i = 0; i < parts[2].length(); i++) sb.append('*');
        sb.append('.');
        for (int i = 0; i < parts[3].length(); i++) sb.append('*');
        return sb.toString();
    }
}
