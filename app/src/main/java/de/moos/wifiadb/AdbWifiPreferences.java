package de.moos.wifiadb;

import android.content.Context;
import android.content.SharedPreferences;

/** Manages persistent preferences such as keep-alive auto-reconnect. */
final class AdbWifiPreferences {
    private static final String PREFS_NAME = "wifi_adb_prefs";
    private static final String KEY_KEEP_ALIVE = "keep_alive_enabled";

    private AdbWifiPreferences() {}

    static boolean isKeepAliveEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_KEEP_ALIVE, false);
    }

    static void setKeepAliveEnabled(Context context, boolean enabled) {
        AdbWifi.consumeUserDisabled();
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_KEEP_ALIVE, enabled).apply();
    }
}
