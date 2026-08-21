package de.moos.wifiadb;

import android.content.Context;
import android.provider.Settings;

/** Liest/schreibt Androids "Wireless debugging"-Schalter (Settings.Global.adb_wifi_enabled). */
final class AdbWifi {
    static final String KEY = "adb_wifi_enabled";

    // Set right after a successful user-initiated disable, consumed once by AdbWifiService's
    // keep-alive observer so it doesn't immediately re-enable a deliberate shutoff.
    private static volatile boolean userDisabled;

    private AdbWifi() {}

    static boolean isEnabled(Context ctx) {
        return Settings.Global.getInt(ctx.getContentResolver(), KEY, 0) == 1;
    }

    /** @return true bei Erfolg, false wenn WRITE_SECURE_SETTINGS fehlt. */
    static boolean setEnabled(Context ctx, boolean on) {
        try {
            Settings.Global.putInt(ctx.getContentResolver(), KEY, on ? 1 : 0);
            userDisabled = !on;
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    /** Consumes and returns whether the last disable was user-initiated (vs. an external drop). */
    static boolean consumeUserDisabled() {
        boolean was = userDisabled;
        userDisabled = false;
        return was;
    }
}
