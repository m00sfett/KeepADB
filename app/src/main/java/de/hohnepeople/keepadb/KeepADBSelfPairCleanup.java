package de.hohnepeople.keepadb;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

/**
 * Who may switch Wifi-ADB off after the app granted itself the system permission (#962), and
 * the one place that does it.
 *
 * <p>The user switches Wireless debugging on only to pair. Once the permission is there and
 * Keep-Alive is off, it is switched off again. That act needs an owner, because it can become
 * due late: {@code adbd} may run the grant after the connection broke, and the process may die
 * between the grant and the cleanup.
 *
 * <p>The owner is a <em>claim</em>, a small record that survives the process. Its rules:
 * <ul>
 *   <li>It is written only once pairing has succeeded, right before the grant command can be
 *       sent, and synchronously. An attempt that never paired cannot have granted anything and
 *       never holds a claim.</li>
 *   <li>It belongs to one run of the service. A run settles only its own claim; the next process
 *       start may settle whatever claim it finds.</li>
 *   <li>It is valid for a short time within the same boot, measured on the monotonic clock.
 *       Wireless debugging does not survive a reboot, so there is nothing to clean up after
 *       one.</li>
 *   <li>Anything the user does afterwards about Wifi-ADB or Keep-Alive in KeepADB supersedes it:
 *       the claim is dropped and nothing is switched off behind their back.</li>
 *   <li>It is released only when the cleanup is confirmed, superseded, expired or not needed
 *       (Keep-Alive on). A refused write keeps it, so the next entry tries again.</li>
 * </ul>
 *
 * <p>What this cannot do: clean up at the moment a process is killed. That waits for the next
 * start, and only inside the window. A change the user makes in Android's own settings is
 * invisible here; the short window is what bounds that case.
 *
 * <p>Nothing secret is stored: a random run id and two clock values.
 */
final class KeepADBSelfPairCleanup {
    /** What {@link #settle} found and did. */
    enum Outcome {
        /** The permission is not granted (yet); a valid claim stays for a late grant. */
        NOT_GRANTED,
        /** Granted; Wifi-ADB was left as it is (Keep-Alive, or not this caller's to switch). */
        LEFT_ON,
        /** Granted; Wifi-ADB was switched off and read back as off. */
        SWITCHED_OFF,
        /** Granted; switching off failed or could not be confirmed. The claim stays. */
        STILL_ON,
        /** The check itself failed; nothing is known and nothing was changed. */
        UNKNOWN
    }

    /** Long enough to come back to the app after pairing, short enough not to surprise later. */
    static final long WINDOW_MS = 3 * 60 * 1000L;

    private static final String TAG = "KeepADBSelfPair";
    private static final String PREFS_NAME = "keepadb_selfpair";
    private static final String KEY_RUN = "claim_run";
    private static final String KEY_ELAPSED = "claim_elapsed";
    private static final String KEY_BOOT = "claim_boot";
    private static final Object LOCK = new Object();

    private KeepADBSelfPairCleanup() {}

    /**
     * Records that {@code runId} is about to send the grant command. Blocks until the record is
     * on disk; call it off the main thread.
     */
    static void claim(Context context, String runId) {
        synchronized (LOCK) {
            try {
                prefs(context).edit()
                        .putString(KEY_RUN, runId)
                        .putLong(KEY_ELAPSED, SystemClock.elapsedRealtime())
                        .putInt(KEY_BOOT, bootCount(context))
                        .commit();
            } catch (RuntimeException e) {
                Log.w(TAG, "cannot record the cleanup claim", e);
            }
        }
    }

    /** The user decided something about Wifi-ADB or Keep-Alive: any claim is void. */
    static void supersede(Context context) {
        if (context == null) return;
        synchronized (LOCK) {
            try {
                SharedPreferences prefs = prefs(context);
                if (prefs.contains(KEY_RUN)) {
                    prefs.edit().clear().apply();
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "cannot drop the cleanup claim", e);
            }
        }
    }

    /** At process start: finish what an earlier process may have left undone. Never throws. */
    static void reconcileAtStart(Context context) {
        settle(context, null);
    }

    /**
     * Checks the permission and, if this caller holds the claim, switches Wifi-ADB off.
     * {@code runId} names the calling run; {@code null} is the process start, which may act on
     * any valid claim. Never throws.
     */
    static Outcome settle(Context context, String runId) {
        synchronized (LOCK) {
            try {
                Context app = context.getApplicationContext();
                boolean granted = app.checkSelfPermission(
                        Manifest.permission.WRITE_SECURE_SETTINGS)
                        == PackageManager.PERMISSION_GRANTED;
                SharedPreferences prefs = prefs(app);
                String owner = prefs.getString(KEY_RUN, null);
                if (owner != null && !isValid(app, prefs)) {
                    prefs.edit().clear().apply();
                    owner = null;
                }
                if (!granted) {
                    return Outcome.NOT_GRANTED;
                }
                if (owner == null || (runId != null && !runId.equals(owner))) {
                    // No claim, or another run's: the permission is there, the switch is not ours.
                    return Outcome.LEFT_ON;
                }
                if (KeepADBPreferences.isKeepAliveEnabled(app)) {
                    prefs.edit().clear().apply();
                    return Outcome.LEFT_ON;
                }
                KeepADB.ToggleResult result =
                        KeepADB.setEnabled(app, false, KeepADB.SOURCE_SELF_PAIRING);
                Boolean enabled = KeepADB.isEnabledOrNull(app, KeepADB.SOURCE_SELF_PAIRING);
                if (result != null && result.isSuccess() && Boolean.FALSE.equals(enabled)) {
                    prefs.edit().clear().apply();
                    return Outcome.SWITCHED_OFF;
                }
                return Outcome.STILL_ON;
            } catch (RuntimeException e) {
                Log.w(TAG, "self-pairing cleanup failed", e);
                return Outcome.UNKNOWN;
            }
        }
    }

    /** True while a claim is recorded, valid or not. */
    static boolean hasClaim(Context context) {
        synchronized (LOCK) {
            return prefs(context).contains(KEY_RUN);
        }
    }

    private static boolean isValid(Context context, SharedPreferences prefs) {
        long age = SystemClock.elapsedRealtime() - prefs.getLong(KEY_ELAPSED, Long.MIN_VALUE / 2);
        return prefs.getInt(KEY_BOOT, -1) == bootCount(context) && age >= 0 && age < WINDOW_MS;
    }

    /** Distinguishes boots where the monotonic clock alone could not. -1 if unreadable. */
    private static int bootCount(Context context) {
        try {
            return Settings.Global.getInt(context.getContentResolver(),
                    Settings.Global.BOOT_COUNT, -1);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
