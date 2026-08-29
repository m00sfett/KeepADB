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
    private static final String KEY_APP_LANGUAGE = "app_language";
    private static final String KEY_SERVICE_LAST_HEARTBEAT = "service_last_heartbeat";
    private static final String KEY_HIDE_NOTIFICATION = "hide_notification_enabled";
    private static final String KEY_USB_WLAN_HANDOVER_MODE = "usb_wlan_handover_mode";
    private static final String KEY_USB_WEBHOOK_LAST_REPORTED = "usb_webhook_last_reported";
    private static final String KEY_USB_WEBHOOK_LAST_URL = "usb_webhook_last_url";
    private static final String KEY_USB_WEBHOOK_LAST_PAYLOAD = "usb_webhook_last_payload";
    private static final String KEY_USB_WEBHOOK_LAST_PROFILE_ID = "usb_webhook_last_profile_id";
    private static final String KEY_USB_WEBHOOK_LAST_PROFILE_NAME = "usb_webhook_last_profile_name";
    private static final String KEY_USB_WEBHOOK_LAST_IP = "usb_webhook_last_ip";
    private static final String KEY_USB_WEBHOOK_LAST_HOSTNAME = "usb_webhook_last_hostname";
    private static final String KEY_USB_WEBHOOK_LAST_TAILNET_HOSTNAME = "usb_webhook_last_tailnet_hostname";

    // #168: optional USB-ADB -> WLAN-ADB handover offered from the USB notification.
    static final String USB_WLAN_HANDOVER_MODE_OFF = "off";
    static final String USB_WLAN_HANDOVER_MODE_MANUAL = "manual";
    static final String USB_WLAN_HANDOVER_MODE_AUTOMATIC = "automatic";

    private KeepADBPreferences() {}

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

    static boolean isKeepAliveEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_KEEP_ALIVE, false);
    }

    static void setKeepAliveEnabled(Context context, boolean enabled) {
        KeepADBDiagnostics.event(context, "keep_alive_setting", "app", enabled ? "enabled" : "disabled", "user_setting");
        KeepADB.consumeUserDisabled();
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
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_REGISTER_URL, null);
    }

    static void setRegisterWebhookUrl(Context context, String url) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (url == null || url.trim().isEmpty()) {
            prefs.edit().remove(KEY_REGISTER_URL).apply();
        } else {
            prefs.edit().putString(KEY_REGISTER_URL, url.trim()).apply();
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

    static String getWebhookLastReportedUrl(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_WEBHOOK_LAST_URL, null);
    }

    static void setWebhookLastReportedUrl(Context context, String url) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (url == null) {
            prefs.edit().remove(KEY_WEBHOOK_LAST_URL).apply();
        } else {
            prefs.edit().putString(KEY_WEBHOOK_LAST_URL, url).apply();
        }
    }

    static long getUsbWebhookLastReportedAt(Context context) {
        if (context == null) return 0L;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getLong(KEY_USB_WEBHOOK_LAST_REPORTED, 0L);
    }

    static String getUsbWebhookLastReportedUrl(Context context) {
        if (context == null) return null;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_USB_WEBHOOK_LAST_URL, null);
    }

    static void setUsbWebhookLastReportedUrl(Context context, String url) {
        if (context == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (url == null) {
            prefs.edit().remove(KEY_USB_WEBHOOK_LAST_URL).apply();
        } else {
            prefs.edit().putString(KEY_USB_WEBHOOK_LAST_URL, url).apply();
        }
    }

    static String getUsbWebhookLastReportedPayload(Context context) {
        if (context == null) return null;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_USB_WEBHOOK_LAST_PAYLOAD, null);
    }

    static void setUsbWebhookLastReportedPayload(Context context, String payload) {
        if (context == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (payload == null) {
            prefs.edit().remove(KEY_USB_WEBHOOK_LAST_PAYLOAD).apply();
        } else {
            prefs.edit().putString(KEY_USB_WEBHOOK_LAST_PAYLOAD, payload).apply();
        }
    }

    static Integer getUsbWebhookLastProfileId(Context context) {
        if (context == null) return null;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (!prefs.contains(KEY_USB_WEBHOOK_LAST_PROFILE_ID)) return null;
        return prefs.getInt(KEY_USB_WEBHOOK_LAST_PROFILE_ID, 0);
    }

    static String getUsbWebhookLastProfileName(Context context) {
        if (context == null) return null;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_USB_WEBHOOK_LAST_PROFILE_NAME, null);
    }

    static String getUsbWebhookLastIpAddress(Context context) {
        if (context == null) return null;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_USB_WEBHOOK_LAST_IP, null);
    }

    static String getUsbWebhookLastHostname(Context context) {
        if (context == null) return null;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_USB_WEBHOOK_LAST_HOSTNAME, null);
    }

    static String getUsbWebhookLastTailnetHostname(Context context) {
        if (context == null) return null;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_USB_WEBHOOK_LAST_TAILNET_HOSTNAME, null);
    }

    static void setUsbWebhookLastReportedState(Context context, String url, String payload,
            Integer profileId, String profileName, String ipAddress, String hostname, String tailnetHostname) {
        if (context == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        if (url == null) {
            editor.remove(KEY_USB_WEBHOOK_LAST_URL);
            editor.remove(KEY_USB_WEBHOOK_LAST_REPORTED);
        } else {
            editor.putString(KEY_USB_WEBHOOK_LAST_URL, url);
            editor.putLong(KEY_USB_WEBHOOK_LAST_REPORTED, System.currentTimeMillis());
        }
        if (payload == null) {
            editor.remove(KEY_USB_WEBHOOK_LAST_PAYLOAD);
        } else {
            editor.putString(KEY_USB_WEBHOOK_LAST_PAYLOAD, payload);
        }
        if (profileId == null) {
            editor.remove(KEY_USB_WEBHOOK_LAST_PROFILE_ID);
        } else {
            editor.putInt(KEY_USB_WEBHOOK_LAST_PROFILE_ID, profileId);
        }
        if (profileName == null) {
            editor.remove(KEY_USB_WEBHOOK_LAST_PROFILE_NAME);
        } else {
            editor.putString(KEY_USB_WEBHOOK_LAST_PROFILE_NAME, profileName);
        }
        if (ipAddress == null) {
            editor.remove(KEY_USB_WEBHOOK_LAST_IP);
        } else {
            editor.putString(KEY_USB_WEBHOOK_LAST_IP, ipAddress);
        }
        if (hostname == null) {
            editor.remove(KEY_USB_WEBHOOK_LAST_HOSTNAME);
        } else {
            editor.putString(KEY_USB_WEBHOOK_LAST_HOSTNAME, hostname);
        }
        if (tailnetHostname == null) {
            editor.remove(KEY_USB_WEBHOOK_LAST_TAILNET_HOSTNAME);
        } else {
            editor.putString(KEY_USB_WEBHOOK_LAST_TAILNET_HOSTNAME, tailnetHostname);
        }
        editor.apply();
    }

    static void clearUsbWebhookReportedState(Context context) {
        setUsbWebhookLastReportedState(context, null, null, null, null, null, null, null);
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
}
