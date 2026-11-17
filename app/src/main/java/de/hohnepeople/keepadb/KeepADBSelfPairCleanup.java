package de.hohnepeople.keepadb;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
 *       never holds a claim. If it cannot be written, the grant is not sent.</li>
 *   <li>It belongs to one run of the service, and only the run that is current may record it.
 *       A run settles only its own claim; the next process start may settle whatever claim it
 *       finds.</li>
 *   <li>It is valid for a short time within the same boot, measured on the monotonic clock,
 *       and only if the boot can be identified. Wireless debugging does not survive a reboot,
 *       so there is nothing to clean up after one.</li>
 *   <li>Anything the user does afterwards about Wifi-ADB or Keep-Alive in KeepADB supersedes it:
 *       the claim is dropped and nothing is switched off behind their back.</li>
 *   <li>It is released only when the cleanup is confirmed, superseded, expired or not needed
 *       (Keep-Alive on). A refused write keeps it, so the next entry tries again.</li>
 * </ul>
 *
 * <p>What this cannot do: clean up at the moment a process is killed. That waits for the next
 * start, and only inside the window; after it, a Wifi-ADB that is still on stays on until the
 * user switches it off. A change the user makes in Android's own settings is invisible here; the
 * short window is what bounds that case. Pairing is evidence that a grant may follow, not that
 * this attempt caused the permission: one that arrives from elsewhere inside the window is
 * treated as its own. And a user decision is void in this process at once, but reaches the disk
 * a moment later; a process killed in that moment may find the old claim again.
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

    /** What became of an attempt to record the claim. */
    enum Claim {
        /** Recorded on disk: the run owns the cleanup and may send the grant. */
        HELD,
        /** The user decided about Wifi-ADB or Keep-Alive since the run began: grant, no cleanup. */
        SUPERSEDED,
        /** The run is no longer the current one; its attempt must not go on. */
        OBSOLETE,
        /** The record could not be written; without it a grant could not be cleaned up. */
        FAILED
    }

    /** Long enough to come back to the app after pairing, short enough not to surprise later. */
    static final long WINDOW_MS = 3 * 60 * 1000L;

    private static final String TAG = "KeepADBSelfPair";
    private static final String PREFS_NAME = "keepadb_selfpair";
    private static final String KEY_RUN = "claim_run";
    private static final String KEY_ELAPSED = "claim_elapsed";
    private static final String KEY_BOOT = "claim_boot";
    private static final int UNKNOWN_BOOT = -1;
    private static final Object LOCK = new Object();
    /** The run that may record a claim; guarded by LOCK. */
    private static String authorizedRun;
    /** The run during which the user decided otherwise; guarded by LOCK. */
    private static String supersededRun;
    /**
     * The run that is alive and looks after its own claim; guarded by LOCK. While it is, nobody
     * else settles that claim: the run would otherwise report "left as it is" for a cleanup
     * that someone did behind its back.
     */
    private static String managedRun;

    /** How often an unresolved claim is looked at again after its run has ended. */
    static final long WATCH_INTERVAL_MS = 5_000L;

    /** Runs work off the caller's thread; a seam for tests. */
    interface Deferrer {
        void run(Runnable task);

        void later(long delayMs, Runnable task);
    }

    /** Told when a cleanup happened outside a run, so the assistant can still say so. */
    interface Observer {
        void onBackgroundCleanup(Context context, Outcome outcome);
    }

    private static final Deferrer REAL_DEFERRER = new Deferrer() {
        private final ScheduledExecutorService executor =
                Executors.newSingleThreadScheduledExecutor(task -> {
                    Thread thread = new Thread(task, "selfpair-cleanup");
                    thread.setDaemon(true);
                    return thread;
                });

        @Override
        public void run(Runnable task) {
            executor.execute(task);
        }

        @Override
        public void later(long delayMs, Runnable task) {
            executor.schedule(task, delayMs, TimeUnit.MILLISECONDS);
        }
    };

    private static volatile Deferrer deferrer = REAL_DEFERRER;
    private static volatile Observer observer;
    private static final AtomicBoolean RECONCILE_PENDING = new AtomicBoolean();
    private static final AtomicBoolean WATCHING = new AtomicBoolean();

    static void setObserver(Observer next) {
        observer = next;
    }

    static void setDeferrerForTesting(Deferrer next) {
        deferrer = next == null ? REAL_DEFERRER : next;
        RECONCILE_PENDING.set(false);
        WATCHING.set(false);
        synchronized (LOCK) {
            authorizedRun = null;
            supersededRun = null;
            managedRun = null;
        }
    }

    private KeepADBSelfPairCleanup() {}

    /**
     * A new run begins: only it may record a claim from now on. An attempt of an earlier run
     * that is still finishing its pairing gets {@link Claim#OBSOLETE} and cannot take the
     * cleanup away from this one.
     */
    static void authorize(String runId) {
        synchronized (LOCK) {
            authorizedRun = runId;
            supersededRun = null;
            managedRun = runId;
        }
    }

    /**
     * The run has ended and no longer looks after its claim. If one is still unresolved (paired,
     * no permission seen yet), it is looked at again every few seconds until it is settled,
     * superseded or expired: {@code adbd} may run the grant after the connection broke, and
     * nobody else would notice while the process lives.
     */
    static void release(Context context, String runId) {
        synchronized (LOCK) {
            if (runId != null && runId.equals(managedRun)) {
                managedRun = null;
            }
        }
        watch(context.getApplicationContext());
    }

    /**
     * Records that {@code runId} is about to send the grant command. Blocks until the record is
     * on disk; call it off the main thread.
     */
    static Claim claim(Context context, String runId) {
        synchronized (LOCK) {
            if (runId.equals(supersededRun)) {
                return Claim.SUPERSEDED;
            }
            if (!runId.equals(authorizedRun)) {
                return Claim.OBSOLETE;
            }
            try {
                int boot = bootCount(context);
                boolean stored = boot != UNKNOWN_BOOT && prefs(context).edit()
                        .putString(KEY_RUN, runId)
                        .putLong(KEY_ELAPSED, SystemClock.elapsedRealtime())
                        .putInt(KEY_BOOT, boot)
                        .commit();
                return stored ? Claim.HELD : Claim.FAILED;
            } catch (RuntimeException e) {
                Log.w(TAG, "cannot record the cleanup claim", e);
                return Claim.FAILED;
            }
        }
    }

    /**
     * The user decided something about Wifi-ADB or Keep-Alive: any claim is void, and the run
     * under way may not record one any more.
     *
     * <p>Takes the lock on purpose, although it is called from the main thread: a cleanup that
     * is switching off right now has to finish before the user's own switch is written, or it
     * would undo it. What it can wait for is local (a permission check, preferences, one
     * settings write), never the network. There is no time limit on that wait; making the user's
     * switch part of an asynchronous queue instead would mean rebuilding the app's central
     * toggle path for it, and that was judged the larger risk (maintainer decision, #962). The
     * removal is written asynchronously; within this process the claim is void at once.
     */
    static void supersede(Context context) {
        if (context == null) return;
        synchronized (LOCK) {
            if (authorizedRun != null) {
                supersededRun = authorizedRun;
                authorizedRun = null;
            }
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

    /**
     * At process start, and when the assistant comes to the front: finish what an earlier
     * process or an ended run may have left undone. Returns at once; the work happens on a
     * thread of its own, because it reads preferences and may write a setting.
     */
    static void reconcileAtStart(Context context) {
        final Context app = context.getApplicationContext();
        if (RECONCILE_PENDING.compareAndSet(false, true)) {
            // One at a time: repeated returns to the assistant do not pile up work.
            deferrer.run(() -> {
                RECONCILE_PENDING.set(false);
                background(app);
            });
        }
    }

    /** The reconciliation itself, on the calling thread. Never throws. */
    static Outcome reconcile(Context context) {
        return settle(context, null);
    }

    private static void watch(Context app) {
        if (hasWatchableClaim(app) && WATCHING.compareAndSet(false, true)) {
            deferrer.later(WATCH_INTERVAL_MS, () -> {
                WATCHING.set(false);
                background(app);
            });
        }
    }

    /** One look outside any run: settle what can be settled, say so, and keep watching if needed. */
    private static void background(Context app) {
        Outcome outcome = settle(app, null);
        Observer current = observer;
        if (current != null
                && (outcome == Outcome.SWITCHED_OFF || outcome == Outcome.STILL_ON)) {
            try {
                current.onBackgroundCleanup(app, outcome);
            } catch (RuntimeException e) {
                Log.w(TAG, "cannot report the background cleanup", e);
            }
        }
        watch(app);
    }

    /** A claim that is valid and that no live run looks after. */
    private static boolean hasWatchableClaim(Context app) {
        synchronized (LOCK) {
            try {
                SharedPreferences prefs = prefs(app);
                String owner = prefs.getString(KEY_RUN, null);
                return owner != null && !owner.equals(managedRun) && isValid(app, prefs);
            } catch (RuntimeException e) {
                return false;
            }
        }
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
                if (runId == null && owner.equals(managedRun)) {
                    // A live run looks after this claim and will report what it did with it.
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
        int recorded = prefs.getInt(KEY_BOOT, UNKNOWN_BOOT);
        // An unknown boot proves nothing: after a reboot the monotonic clock starts over and
        // could land inside the window by chance.
        return recorded != UNKNOWN_BOOT && recorded == bootCount(context)
                && age >= 0 && age < WINDOW_MS;
    }

    /** Distinguishes boots where the monotonic clock alone could not. */
    private static int bootCount(Context context) {
        try {
            return Settings.Global.getInt(context.getContentResolver(),
                    Settings.Global.BOOT_COUNT, UNKNOWN_BOOT);
        } catch (RuntimeException e) {
            return UNKNOWN_BOOT;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
