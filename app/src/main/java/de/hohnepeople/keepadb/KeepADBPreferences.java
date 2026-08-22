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

    private KeepADBPreferences() {}

    static boolean isKeepAliveEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_KEEP_ALIVE, false);
    }

    static void setKeepAliveEnabled(Context context, boolean enabled) {
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
