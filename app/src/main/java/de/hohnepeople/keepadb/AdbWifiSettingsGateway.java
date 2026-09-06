package de.hohnepeople.keepadb;

import android.content.Context;
import android.provider.Settings;

/**
 * Isolates all {@link Settings.Global} reads/writes behind one boundary (issue #248), so the
 * surrounding orchestration in {@link KeepADB} -- and its Android-independent decision core,
 * {@link KeepADBToggleState} -- don't need to know Settings.Global exists.
 */
final class AdbWifiSettingsGateway {

    private AdbWifiSettingsGateway() {}

    static boolean isEnabled(Context ctx, String key) {
        return Settings.Global.getInt(ctx.getContentResolver(), key, 0) == 1;
    }

    static boolean writeEnabled(Context ctx, String key, boolean on) {
        return Settings.Global.putInt(ctx.getContentResolver(), key, on ? 1 : 0);
    }
}
