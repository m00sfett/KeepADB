package de.hohnepeople.keepadb;

import android.content.Context;
import android.content.SharedPreferences;

/** Manages persistent preferences such as keep-alive auto-reconnect. */
final class KeepADBPreferences {
    private static final String PREFS_NAME = "keepadb_prefs";
    private static final String KEY_KEEP_ALIVE = "keep_alive_enabled";

    private static final String KEY_REGISTER_URL = "register_webhook_url";

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

    static String getRegisterWebhookUrl(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_REGISTER_URL, null);
    }

    static void setRegisterWebhookUrl(Context context, String url) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_REGISTER_URL, url).apply();
    }
}
