package de.hohnepeople.keepadb;

import android.content.Context;

/**
 * #1050: short-lived markers that only make sense within one boot, split out of {@link
 * KeepADBPreferences}: the restart "on" still owed after a process death (#1027) and the
 * remembered Wifi-ADB port (#1030), each stored with the boot counter it belongs to. Same
 * preferences file and keys as before.
 */
final class KeepADBRuntimeMarkers {
    /** #1027: elapsed realtime of a Home restart whose "on" is still owed; absent when none. */
    private static final String KEY_RESTART_ON_PENDING_AT = "restart_on_pending_at";
    /** #1027: the boot counter at {@link #KEY_RESTART_ON_PENDING_AT}; written in the same commit. */
    private static final String KEY_RESTART_ON_PENDING_BOOT = "restart_on_pending_boot";
    /** #1030: the last Wifi-ADB port adbd announced or answered on; absent when none. */
    private static final String KEY_LAST_ADB_PORT = "last_adb_port";
    /** #1030: the boot counter at {@link #KEY_LAST_ADB_PORT}; a port never outlives its boot. */
    private static final String KEY_LAST_ADB_PORT_BOOT = "last_adb_port_boot";

    private KeepADBRuntimeMarkers() {}

    /** #1027: elapsed realtime of a pending restart "on", or -1 when none is owed. */
    static long getRestartOnPendingAt(Context context) {
        if (context == null) return -1L;
        return KeepADBPreferences.prefs(context)
                .getLong(KEY_RESTART_ON_PENDING_AT, -1L);
    }

    /** #1027: the boot counter stored with the pending restart "on", or -1. */
    static int getRestartOnPendingBoot(Context context) {
        if (context == null) return -1;
        return KeepADBPreferences.prefs(context)
                .getInt(KEY_RESTART_ON_PENDING_BOOT, -1);
    }

    /**
     * #1027: time and boot counter in one synchronous commit, so a process death right after the
     * "off" still finds both, and a later boot can never adopt the record.
     */
    static void setRestartOnPending(Context context, long atElapsedMs, int bootCount) {
        if (context == null) return;
        KeepADBPreferences.prefs(context).edit()
                .putLong(KEY_RESTART_ON_PENDING_AT, atElapsedMs)
                .putInt(KEY_RESTART_ON_PENDING_BOOT, bootCount).commit();
    }

    static void clearRestartOnPending(Context context) {
        if (context == null) return;
        KeepADBPreferences.prefs(context).edit()
                .remove(KEY_RESTART_ON_PENDING_AT).remove(KEY_RESTART_ON_PENDING_BOOT).commit();
    }

    /** #1030: the remembered Wifi-ADB port, or -1. */
    static int getLastAdbPort(Context context) {
        if (context == null) return -1;
        return KeepADBPreferences.prefs(context)
                .getInt(KEY_LAST_ADB_PORT, -1);
    }

    /** #1030: the boot counter stored with the remembered port, or -1. */
    static int getLastAdbPortBoot(Context context) {
        if (context == null) return -1;
        return KeepADBPreferences.prefs(context)
                .getInt(KEY_LAST_ADB_PORT_BOOT, -1);
    }

    /** #1030: port and boot counter in one edit, so neither is ever read without the other. */
    static void setLastAdbPort(Context context, int port, int bootCount) {
        if (context == null) return;
        KeepADBPreferences.prefs(context).edit()
                .putInt(KEY_LAST_ADB_PORT, port)
                .putInt(KEY_LAST_ADB_PORT_BOOT, bootCount).apply();
    }

    static void clearLastAdbPort(Context context) {
        if (context == null) return;
        KeepADBPreferences.prefs(context).edit()
                .remove(KEY_LAST_ADB_PORT).remove(KEY_LAST_ADB_PORT_BOOT).apply();
    }
}
