package de.hohnepeople.keepadb;

import android.content.Context;
import android.content.SharedPreferences;

/** Manages persistent preferences such as keep-alive auto-reconnect and webhook sync. */
final class KeepADBPreferences {
    private static final String PREFS_NAME = "keepadb_prefs";
    private static final String KEY_KEEP_ALIVE = "keep_alive_enabled";
    private static final String KEY_REGISTER_URL = "register_webhook_url";
    private static final String KEY_WEBHOOK_ENABLED = "register_webhook_enabled";
    private static final String KEY_WEBHOOK_LAST_REPORTED = "register_webhook_last_reported";
    private static final String KEY_WEBHOOK_LAST_ENDPOINT = "register_webhook_last_endpoint";
    private static final String KEY_WEBHOOK_LAST_URL = "register_webhook_last_url";
    private static final String KEY_WEBHOOK_LAST_STATUS = "register_webhook_last_status";
    private static final String KEY_WEBHOOK_PENDING_CLEANUP = "register_webhook_pending_cleanup";
    private static final String KEY_APP_LANGUAGE = "app_language";
    private static final String KEY_SERVICE_LAST_HEARTBEAT = "service_last_heartbeat";
    private static final String KEY_HIDE_NOTIFICATION = "hide_notification_enabled";
    private static final String KEY_USB_WLAN_HANDOVER_MODE = "usb_wlan_handover_mode";
    private static final String KEY_LAST_DESIRED_ON = "last_desired_on";
    private static final String KEY_KEEP_DISPLAY_ON = "keep_display_on_enabled";
    private static final String KEY_ADVICE_BANNER_VISIBLE = "advice_banner_visible";
    // #482: display-only privacy toggle. Persists whether network addresses currently shown in
    // the UI should be masked -- purely a rendering preference, never the toggle facade's own
    // WRITE_SECURE_SETTINGS state and never the real ADB transport. The actual masking logic
    // (#483) reads this via isPrivacyModeEnabled().
    private static final String KEY_PRIVACY_MODE_ENABLED = "privacy_mode_enabled";

    // #168: optional USB-ADB -> WLAN-ADB handover offered from the USB notification.
    static final String USB_WLAN_HANDOVER_MODE_OFF = "off";
    static final String USB_WLAN_HANDOVER_MODE_MANUAL = "manual";
    static final String USB_WLAN_HANDOVER_MODE_AUTOMATIC = "automatic";

    private KeepADBPreferences() {}

    static final String WEBHOOK_STATUS_NEVER = "never";
    static final String WEBHOOK_STATUS_SUCCESS = "success";
    static final String WEBHOOK_STATUS_DEREGISTERED = "deregistered";
    static final String WEBHOOK_STATUS_FAILED = "failed";

    /**
     * Persisted record of the last explicit on/off user intent. Survives process death and LMK
     * so recovery and automatic handover don't fail open after process resurrection. Defaults to true.
     */
    static boolean getLastDesiredOn(Context context) {
        if (context == null) return true;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_LAST_DESIRED_ON, true);
    }

    static void setLastDesiredOn(Context context, boolean on) {
        if (context == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_LAST_DESIRED_ON, on).apply();
    }

    /** Default OFF; any unrecognized stored value is treated as OFF rather than failing open. */
    static String getUsbWlanHandoverMode(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String value = prefs.getString(KEY_USB_WLAN_HANDOVER_MODE, USB_WLAN_HANDOVER_MODE_OFF);
        if (USB_WLAN_HANDOVER_MODE_MANUAL.equals(value) || USB_WLAN_HANDOVER_MODE_AUTOMATIC.equals(value)) {
            return value;
        }
        return USB_WLAN_HANDOVER_MODE_OFF;
    }

    // Deliberately does NOT call KeepADB.consumeUserDisabled() (unlike setKeepAliveEnabled()):
    // choosing MANUAL/AUTOMATIC here only configures future behavior, it is not itself an
    // explicit "turn WLAN-ADB on now" action. Clearing the user-off flag here would let merely
    // enabling this setting silently undo an earlier explicit user disable before any new USB
    // connect edge even happens, which is exactly what issue #168's safety requirement forbids.
    static void setUsbWlanHandoverMode(Context context, String mode) {
        String sanitized = USB_WLAN_HANDOVER_MODE_MANUAL.equals(mode)
                || USB_WLAN_HANDOVER_MODE_AUTOMATIC.equals(mode)
                ? mode : USB_WLAN_HANDOVER_MODE_OFF;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_USB_WLAN_HANDOVER_MODE, sanitized).apply();
    }

    static boolean isNotificationHidden(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_HIDE_NOTIFICATION, false);
    }

    static void setNotificationHidden(Context context, boolean hidden) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_HIDE_NOTIFICATION, hidden).apply();
    }

    /** #224: keeps the display on only while MainActivity is in the foreground. Default OFF. */
    static boolean isKeepDisplayOnEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_KEEP_DISPLAY_ON, false);
    }

    static void setKeepDisplayOnEnabled(Context context, boolean enabled) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_KEEP_DISPLAY_ON, enabled).apply();
    }

    static boolean isKeepAliveEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_KEEP_ALIVE, false);
    }

    static void setKeepAliveEnabled(Context context, boolean enabled) {
        KeepADBDiagnostics.event(context, "keep_alive_setting", "app", enabled ? "enabled" : "disabled", "user_setting");
        KeepADB.consumeUserDisabled();
        if (enabled) {
            KeepADB.recordExplicitIntent(context, true);
        }
        if (context == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_KEEP_ALIVE, enabled).apply();
    }

    static boolean isRegisterWebhookEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_WEBHOOK_ENABLED, false);
    }

    static void setRegisterWebhookEnabled(Context context, boolean enabled) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_WEBHOOK_ENABLED, enabled).apply();
    }

    static String getRegisterWebhookUrl(Context context) {
        if (context == null) return null;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return sanitizeWebhookUrl(prefs.getString(KEY_REGISTER_URL, null));
    }

    static void setRegisterWebhookUrl(Context context, String url) {
        if (context == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String sanitized = sanitizeWebhookUrl(url);
        if (sanitized == null || sanitized.trim().isEmpty()) {
            prefs.edit().remove(KEY_REGISTER_URL).apply();
        } else {
            prefs.edit().putString(KEY_REGISTER_URL, sanitized.trim()).apply();
        }
    }

    static long getWebhookLastReportedAt(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getLong(KEY_WEBHOOK_LAST_REPORTED, 0L);
    }

    static void setWebhookLastReportedAtNow(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putLong(KEY_WEBHOOK_LAST_REPORTED, System.currentTimeMillis()).apply();
    }

    static String getWebhookLastReportedEndpoint(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_WEBHOOK_LAST_ENDPOINT, null);
    }

    static void setWebhookLastReportedEndpoint(Context context, String endpoint) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (endpoint == null) {
            prefs.edit().remove(KEY_WEBHOOK_LAST_ENDPOINT).apply();
        } else {
            prefs.edit().putString(KEY_WEBHOOK_LAST_ENDPOINT, endpoint).apply();
        }
    }

    /**
     * #350: sanitised on read, not only on write. Installations that stored this value before
     * {@link #setRegisterWebhookUrl(Context, String)} started stripping userinfo still hold a raw
     * URL here, and this value is not just displayed — the register client uses it as the DELETE /
     * {@code active:false} target when the webhook URL changes. Sanitising at the read boundary
     * means such a legacy value can neither reach the network with its credentials attached nor
     * reach a log line, without needing a one-shot migration that a downgrade could undo.
     */
    static String getWebhookLastReportedUrl(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return sanitizeWebhookUrl(prefs.getString(KEY_WEBHOOK_LAST_URL, null));
    }

    static void setWebhookLastReportedUrl(Context context, String url) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String sanitized = sanitizeWebhookUrl(url);
        if (sanitized == null) {
            prefs.edit().remove(KEY_WEBHOOK_LAST_URL).apply();
        } else {
            prefs.edit().putString(KEY_WEBHOOK_LAST_URL, sanitized).apply();
        }
    }

    /**
     * Returns the last WLAN-ADB webhook result. Missing status is derived from the legacy fields
     * so existing installations keep their previous success/deregistration meaning.
     */
    static String getWebhookLastReportStatus(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String stored = prefs.getString(KEY_WEBHOOK_LAST_STATUS, null);
        if (isWebhookReportStatus(stored)) {
            return stored;
        }
        String endpoint = prefs.getString(KEY_WEBHOOK_LAST_ENDPOINT, null);
        if (endpoint != null && !endpoint.trim().isEmpty()) {
            return WEBHOOK_STATUS_SUCCESS;
        }
        return prefs.getLong(KEY_WEBHOOK_LAST_REPORTED, 0L) > 0L
                ? WEBHOOK_STATUS_DEREGISTERED : WEBHOOK_STATUS_NEVER;
    }

    static void setWebhookLastReportStatus(Context context, String status) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (isWebhookReportStatus(status)) {
            prefs.edit().putString(KEY_WEBHOOK_LAST_STATUS, status).apply();
        } else {
            prefs.edit().remove(KEY_WEBHOOK_LAST_STATUS).apply();
        }
    }

    private static boolean isWebhookReportStatus(String status) {
        return WEBHOOK_STATUS_SUCCESS.equals(status)
                || WEBHOOK_STATUS_DEREGISTERED.equals(status)
                || WEBHOOK_STATUS_FAILED.equals(status)
                || WEBHOOK_STATUS_NEVER.equals(status);
    }

    /**
     * #317: writes the whole WLAN-ADB report snapshot through a single {@link SharedPreferences.Editor}.
     *
     * <p>Timestamp, URL, endpoint and status used to be written through four independent
     * {@code apply()} calls, so a crash or LMK kill between two of them could persist a URL
     * without its endpoint (or a success status without the endpoint it refers to). One editor
     * with several {@code put*()} calls is written to the preferences file as one unit.
     *
     * <p>A {@code null} {@code status} leaves the stored status untouched; any other value follows
     * the same validity rule as {@link #setWebhookLastReportStatus(Context, String)}.
     */
    static void setWebhookReportSnapshot(Context context, String url, String endpoint, String status,
            boolean touchTimestamp) {
        if (context == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        String sanitizedUrl = sanitizeWebhookUrl(url);
        if (sanitizedUrl == null) {
            editor.remove(KEY_WEBHOOK_LAST_URL);
        } else {
            editor.putString(KEY_WEBHOOK_LAST_URL, sanitizedUrl);
        }
        if (endpoint == null) {
            editor.remove(KEY_WEBHOOK_LAST_ENDPOINT);
        } else {
            editor.putString(KEY_WEBHOOK_LAST_ENDPOINT, endpoint);
        }
        if (status != null) {
            if (isWebhookReportStatus(status)) {
                editor.putString(KEY_WEBHOOK_LAST_STATUS, status);
            } else {
                editor.remove(KEY_WEBHOOK_LAST_STATUS);
            }
        }
        if (touchTimestamp) {
            editor.putLong(KEY_WEBHOOK_LAST_REPORTED, System.currentTimeMillis());
        }
        editor.apply();
    }

    // ---- #317: retryable cleanups for registrations left behind on a previous webhook URL. ----

    /**
     * Upper bound for either pending-cleanup set. Each retry costs one HTTP round trip on the
     * register executor, so the backlog is deliberately small; a full set evicts its OLDEST entry
     * (FIFO, see #368) rather than growing without limit or rejecting the newest one.
     */
    static final int MAX_PENDING_CLEANUPS = 4;

    /**
     * Separator joining entries in the ordered FIFO shadow list (#368). {@code SharedPreferences}
     * string sets have no defined iteration order, so the eviction order can't be derived from the
     * legacy {@code StringSet} keys alone; this ASCII Group Separator control character is joined
     * between entries in a plain {@code String} preference instead, preserving insertion order.
     * Chosen because it cannot appear in a URL.
     */
    private static final String ORDER_SEPARATOR = "\u001D";

    static java.util.Set<String> getPendingWebhookCleanupUrls(Context context) {
        return getPendingCleanups(context, KEY_WEBHOOK_PENDING_CLEANUP);
    }

    static void addPendingWebhookCleanupUrl(Context context, String url) {
        if (url == null || url.trim().isEmpty()) return;
        addPendingCleanup(context, KEY_WEBHOOK_PENDING_CLEANUP, url);
    }

    static void removePendingWebhookCleanupUrl(Context context, String url) {
        removePendingCleanup(context, KEY_WEBHOOK_PENDING_CLEANUP, url);
    }

    /** The ordered shadow key for a legacy pending-cleanup {@code StringSet} key. */
    private static String orderKeyFor(String key) {
        return key + "_order";
    }

    /**
     * Returns the pending cleanups in FIFO order (oldest first, see #368).
     *
     * <p>Always a fresh, mutable copy. Preferred source is the ordered shadow key written by
     * {@link #persistPendingCleanups}. When that key is missing (e.g. data written by an app
     * version predating #368) but the legacy {@code StringSet} key holds entries, the order is
     * reconstructed best-effort from the set's iteration order -- {@code SharedPreferences}
     * string sets carry no defined ordering, so this is only a starting point, not a guarantee
     * that it matches the original insertion order. It is immediately persisted in the new
     * ordered format so that later evictions are FIFO-correct going forward.
     */
    private static java.util.LinkedHashSet<String> getPendingCleanups(Context context, String key) {
        if (context == null) return new java.util.LinkedHashSet<>();
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String orderKey = orderKeyFor(key);
        String joined = prefs.getString(orderKey, null);
        if (joined != null) {
            java.util.LinkedHashSet<String> ordered = new java.util.LinkedHashSet<>();
            if (!joined.isEmpty()) {
                for (String entry : joined.split(ORDER_SEPARATOR, -1)) {
                    if (!entry.isEmpty()) ordered.add(entry);
                }
            }
            return ordered;
        }
        java.util.Set<String> legacy = prefs.getStringSet(key, null);
        java.util.LinkedHashSet<String> migrated = legacy == null
                ? new java.util.LinkedHashSet<>() : new java.util.LinkedHashSet<>(legacy);
        if (!migrated.isEmpty()) {
            persistPendingCleanups(context, key, migrated);
        }
        return migrated;
    }

    /** Persists both the legacy {@code StringSet} key (compat) and the new ordered shadow key. */
    private static void persistPendingCleanups(Context context, String key, java.util.LinkedHashSet<String> pending) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String orderKey = orderKeyFor(key);
        if (pending.isEmpty()) {
            prefs.edit().remove(key).remove(orderKey).apply();
            return;
        }
        prefs.edit()
                .putStringSet(key, pending)
                .putString(orderKey, String.join(ORDER_SEPARATOR, pending))
                .apply();
    }

    private static void addPendingCleanup(Context context, String key, String entry) {
        if (context == null) return;
        java.util.LinkedHashSet<String> pending = getPendingCleanups(context, key);
        if (pending.contains(entry)) return;
        if (pending.size() >= MAX_PENDING_CLEANUPS) {
            // #368: evict the OLDEST entry (head of insertion order), not the newest, so the
            // newest -- most likely to still be a live orphan registration -- is kept.
            java.util.Iterator<String> oldest = pending.iterator();
            oldest.next();
            oldest.remove();
        }
        pending.add(entry);
        persistPendingCleanups(context, key, pending);
    }

    private static void removePendingCleanup(Context context, String key, String entry) {
        if (context == null || entry == null) return;
        java.util.LinkedHashSet<String> pending = getPendingCleanups(context, key);
        if (!pending.remove(entry)) return;
        persistPendingCleanups(context, key, pending);
    }

    static boolean isValidWebhookUrl(String url) {
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

    static String sanitizeWebhookUrl(String rawUrl) {
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

    /**
     * #350: UI-facing redaction. Delegates to {@link KeepADBUrlRedaction}, the one place that
     * decides how userinfo, host, port, path, query and fragment are treated. This method used to
     * mask IPv4 hosts only, leaving query parameters and IPv6 literals fully readable.
     *
     * <p>Never feed the result into a request — it is display text, not a URL.
     */
    static String maskWebhookUrl(String rawUrl) {
        return KeepADBUrlRedaction.forDisplay(rawUrl);
    }

    /**
     * #483: same display text, with the stricter host rule applied while the privacy mode is on.
     * Reads the toggle itself so all three render sites cannot drift apart.
     *
     * <p>Never feed the result into a request — it is display text, not a URL.
     */
    static String maskWebhookUrlForDisplay(Context context, String rawUrl) {
        return KeepADBUrlRedaction.forDisplay(rawUrl, isPrivacyModeEnabled(context));
    }

    /**
     * #483: masks a {@code host:port} endpoint for display while the privacy mode is on; returns
     * the value unchanged while it is off.
     */
    static String maskEndpointForDisplay(Context context, String endpoint) {
        return isPrivacyModeEnabled(context)
                ? KeepADBAddressMask.maskEndpointForDisplay(endpoint) : endpoint;
    }

    /** #483: masks a bare host for display while the privacy mode is on. */
    static String maskHostForDisplay(Context context, String host) {
        return isPrivacyModeEnabled(context) ? KeepADBAddressMask.maskHost(host) : host;
    }

    /** Marks the moment the foreground service was known alive; used to log restart gaps. */
    static long getServiceLastHeartbeat(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getLong(KEY_SERVICE_LAST_HEARTBEAT, 0L);
    }

    static void setServiceLastHeartbeatNow(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putLong(KEY_SERVICE_LAST_HEARTBEAT, System.currentTimeMillis()).apply();
    }

    static String getAppLanguage(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_APP_LANGUAGE, "");
    }

    static void setAppLanguage(Context context, String languageTag) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (languageTag == null || languageTag.trim().isEmpty()) {
            prefs.edit().remove(KEY_APP_LANGUAGE).apply();
        } else {
            prefs.edit().putString(KEY_APP_LANGUAGE, languageTag.trim()).apply();
        }
    }

    /** #225: advice banner visibility. Default ON (true). */
    static boolean isAdviceBannerVisible(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_ADVICE_BANNER_VISIBLE, true);
    }

    static void setAdviceBannerVisible(Context context, boolean visible) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_ADVICE_BANNER_VISIBLE, visible).apply();
    }

    /** #482/#483: whether currently-displayed network addresses (e.g. the WLAN-ADB endpoint,
     * access point BSSIDs) should be masked in the UI. Off by default -- existing behavior is
     * unchanged until the user opts in from the main screen's header toggle. This flag only ever
     * controls rendering; it never touches {@code adb_wifi_enabled} or any other persisted
     * original value. */
    static boolean isPrivacyModeEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_PRIVACY_MODE_ENABLED, false);
    }

    static void setPrivacyModeEnabled(Context context, boolean enabled) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_PRIVACY_MODE_ENABLED, enabled).apply();
    }
}
