package de.hohnepeople.keepadb;

/**
 * #1050: validation and authority stripping of the user-entered webhook URL, split out of
 * {@link KeepADBPreferences}. Pure functions of their argument: no preferences, no context.
 *
 * <ul>
 *   <li>{@link #isValid}: an {@code http://} or {@code https://} URL with a host.</li>
 *   <li>{@link #sanitize}: the stored form -- trimmed, scheme lower-cased, userinfo and fragment
 *       removed (#377); {@code null} for a value with control characters, {@code ""} for a blank
 *       one. A value {@link java.net.URI} rejects (e.g. an IPv6 zone id) is stripped by hand.</li>
 * </ul>
 */
final class WebhookUrlCodec {
    private WebhookUrlCodec() {}

    static boolean isValid(String url) {
        if (url == null) return false;
        String trimmed = url.trim();
        if (trimmed.isEmpty()) return false;
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return false;
        }
        try {
            java.net.URI uri = new java.net.URI(trimmed);
            return uri.getHost() != null && !uri.getHost().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    static String sanitize(String rawUrl) {
        if (rawUrl == null) return null;
        String trimmed = rawUrl.trim();
        if (trimmed.isEmpty()) return "";
        for (int i = 0; i < trimmed.length(); i++) {
            if (Character.isISOControl(trimmed.charAt(i))) return null;
        }

        try {
            java.net.URI uri = new java.net.URI(trimmed);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme != null && host != null) {
                String path = uri.getPath();
                if (path != null && path.isEmpty()) {
                    path = null;
                }
                java.net.URI sanitized = new java.net.URI(
                        scheme.toLowerCase(java.util.Locale.ROOT),
                        null,
                        host,
                        uri.getPort(),
                        path,
                        uri.getQuery(),
                        null);
                return sanitized.toString();
            }
        } catch (Exception ignored) {
            // Non-standard URI syntax; fallback to manual stripping below.
        }

        String result = trimmed;
        int hashIdx = result.indexOf('#');
        if (hashIdx >= 0) {
            result = result.substring(0, hashIdx);
        }
        int schemeIdx = result.indexOf("://");
        if (schemeIdx >= 0) {
            String schemePart = result.substring(0, schemeIdx + 3).toLowerCase(java.util.Locale.ROOT);
            String remainder = result.substring(schemeIdx + 3);
            int slashIdx = remainder.indexOf('/');
            int queryIdx = remainder.indexOf('?');
            int authEnd = (slashIdx >= 0 && queryIdx >= 0)
                    ? Math.min(slashIdx, queryIdx)
                    : (slashIdx >= 0 ? slashIdx : queryIdx);
            // A password may contain '@'. Strip the complete userinfo authority suffix, not just
            // the part before its first embedded '@' (legacy values can reach this fallback when
            // URI parsing rejects a non-standard host such as an IPv6 zone-id literal). Ignore
            // any '@' that belongs to the path or query when locating that authority delimiter.
            int atIdx = authEnd >= 0
                    ? remainder.lastIndexOf('@', authEnd - 1)
                    : remainder.lastIndexOf('@');
            if (atIdx >= 0 && (authEnd < 0 || atIdx < authEnd)) {
                remainder = remainder.substring(atIdx + 1);
            }
            result = schemePart + remainder;
        }
        return result;
    }
}
