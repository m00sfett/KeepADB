package de.hohnepeople.keepadb;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

/**
 * The Wifi-ADB timer (#1000): Wireless Debugging on for a chosen time, then off.
 *
 * <h2>What it is</h2>
 * A temporary Keep-Alive overlay, the counterpart of the force mode: while it runs, Keep-Alive
 * behaves as switched on ({@link KeepADBPreferences#isKeepAliveEffective}), whatever the stored
 * Keep-Alive setting says, and the setting itself is never written. The network rules stay
 * untouched: on an untrusted network the timer holds nothing and keeps counting; force mode, which
 * may run at the same time, decides <em>where</em> Wifi-ADB is kept alive, the timer <em>how
 * long</em>.
 *
 * <h2>Who starts it, who ends it</h2>
 * <ul>
 *   <li><b>Start:</b> {@link #start}, from the app and the launcher shortcut. It stores the state,
 *       then switches Wifi-ADB on through the manual {@link KeepADB#SOURCE_TIMER}. A new start
 *       replaces a running timer (new duration from now).</li>
 *   <li><b>Extend:</b> {@link #extend}, one hour per call, the remaining time capped at
 *       {@link #MAX_REMAINING_MS}. Only from the notification, behind an unlocked device.</li>
 *   <li><b>End by the user:</b> every manual on/off of Wifi-ADB through another source ends it
 *       ({@link #onManualToggle}). A manual off is the user's "off"; a manual on without a timer
 *       means "on without a limit".</li>
 *   <li><b>End by time:</b> the deadline itself. {@link #isActive} is a pure read that is false
 *       from the first instant the deadline has passed, and {@link #isExpiryPending} is true from
 *       then until {@link #finishIfExpired} performed the transition: the Keep-Alive gate reads
 *       both, so a late alarm can never let Keep-Alive re-enable after the end. The transition
 *       turns Wifi-ADB off like a manual off (the last explicit intent becomes "off", so a
 *       Keep-Alive that is on in Settings waits for the next deliberate "on") and posts a
 *       one-time notice. Drivers: the service heartbeat, an inexact alarm, the boot / package
 *       replaced / clock set broadcasts ({@link #restore}) and the screens.</li>
 * </ul>
 *
 * <h2>Time rules</h2>
 * Exactly those of the force mode ({@link KeepADBDeadline}): the smaller of wall clock and
 * monotonic clock, the budget rebound to every new boot by the first driver that runs, a clock set
 * backward written down, an unreadable boot count or a wall clock before the base after a restart
 * end it (fail closed). A restart neither ends the timer nor credits time: after the boot the
 * effective Keep-Alive restores Wifi-ADB for the time that is left. If the time ran out while the
 * device was off, or after the boot but before KeepADB ran ({@code BOOT_COMPLETED} arrives after
 * the first unlock), the boot path finishes it before Keep-Alive looks at it: Wifi-ADB stays off.
 *
 * <h2>Persistence, rollback</h2>
 * Additive keys in {@code keepadb_prefs} ({@link #KEY_STATE}, {@link #KEY_NOTICE_PENDING}, {@link
 * #KEY_NOTICE_REASON}), written with {@code commit()}. An older app version ignores them: a
 * downgrade drops the limit and leaves Wifi-ADB as Keep-Alive and the last intent say. Excluded
 * from backup and device transfer like the rest of the file; the default duration is a separate,
 * exported preference ({@link KeepADBPreferences#getTimerDefaultDuration}).
 *
 * <h2>Locks</h2>
 * {@link #LOCK} serializes the state writes only. {@link #isActive}, {@link #status} and {@link
 * #isExpiryPending} take no lock and have no side effect, so they are safe under the {@code KeepADB}
 * monitor; every transition refreshes surfaces or writes Wifi-ADB and must not be called from there.
 */
final class KeepADBTimer {
    private static final String PREFS_NAME = "keepadb_prefs";
    /** {@code 1;<budget ms>;<base wall ms>;<base elapsed ms>;<base boot count>}, one value. */
    static final String KEY_STATE = "timer_state";
    /** The timer ended by time and its notice has not been delivered yet. */
    static final String KEY_NOTICE_PENDING = "timer_expired_notice_pending";
    /** Why: {@link #REASON_SAFETY}, {@link #REASON_WHILE_OFF}, or absent for an ordinary expiry. */
    static final String KEY_NOTICE_REASON = "timer_expired_reason";
    /**
     * Codex review #1000: the timer ended (by time or by a manual off) and Wifi-ADB has not been
     * seen off since. Written in the same commit that ends the timer, together with the "off"
     * intent, so a process death before the off write cannot leave Wifi-ADB on with an "on" intent.
     * While set, Keep-Alive is suppressed ({@link KeepADBPreferences#isKeepAliveEffective}) and
     * every driver retries the off ({@link #settleShutdown}) until a readback shows it off.
     */
    static final String KEY_SHUTDOWN_PENDING = "timer_shutdown_pending";
    /** Retry of a pending off when no other driver runs (the service stops after an end). */
    static final long SHUTDOWN_RETRY_MS = 60_000L;
    static final String REASON_SAFETY = "safety";
    static final String REASON_WHILE_OFF = "while_off";
    private static final String STATE_VERSION = "1";

    static final long HOUR_MS = 60L * 60L * 1000L;
    /** One tap on "+1 h". */
    static final long EXTENSION_MS = HOUR_MS;
    /** No start or extension leaves more than this; a longer need is Keep-Alive without a timer. */
    static final long MAX_REMAINING_MS = 24L * HOUR_MS;

    /** Request codes of the timer's PendingIntents: one block after the force mode's 20..23. */
    static final int REQUEST_CODE_EXPIRY_ALARM = 24;
    static final int REQUEST_CODE_OFF_NOW = 25;
    static final int REQUEST_CODE_EXTEND = 26;
    static final int REQUEST_CODE_EXPIRED_CONTENT = 27;

    /** The selectable durations. */
    enum Duration {
        MINUTES_15("15m", 15L * 60L * 1000L),
        MINUTES_30("30m", 30L * 60L * 1000L),
        HOUR_1("1h", HOUR_MS),
        HOURS_2("2h", 2L * HOUR_MS),
        HOURS_4("4h", 4L * HOUR_MS),
        HOURS_8("8h", 8L * HOUR_MS);

        static final Duration DEFAULT = HOUR_1;

        final String token;
        final long millis;

        Duration(String token, long millis) {
            this.token = token;
            this.millis = millis;
        }

        /** The duration for a stored token, or {@link #DEFAULT} for anything unknown. */
        static Duration fromToken(String token) {
            for (Duration duration : values()) {
                if (duration.token.equals(token)) return duration;
            }
            return DEFAULT;
        }
    }

    /** What the start, extension or a rebind stored: the budget and its base. */
    static final class State {
        final KeepADBDeadline.Base base;

        State(KeepADBDeadline.Base base) {
            this.base = base;
        }
    }

    /** A pure snapshot of a running timer. */
    static final class Status {
        /** Milliseconds left, always positive. */
        final long remainingMs;
        /** Wall clock instant of the effective end. */
        final long endsAtWallMs;

        Status(long remainingMs, long endsAtWallMs) {
            this.remainingMs = remainingMs;
            this.endsAtWallMs = endsAtWallMs;
        }

        /** Whether "+1 h" can still add anything. */
        boolean canExtend() {
            return remainingMs < MAX_REMAINING_MS;
        }
    }

    private static final Object LOCK = new Object();
    private static final Object NOTICE_LOCK = new Object();
    private static volatile KeepADBDeadline.Clock clock = KeepADBDeadline.SYSTEM_CLOCK;
    private static volatile Runnable stateListener;
    private static volatile NoticeSink noticeSink;

    /** Posts the one-time expiry notice; set by the notification code, a seam for tests. */
    interface NoticeSink {
        /** @return true if the notice was posted. */
        boolean postExpired(Context app, String reason);
    }

    private KeepADBTimer() {}

    // ---- Pure reads -------------------------------------------------------------------------------

    /** Whether the timer runs right now. Pure; false from the first instant its time is up. */
    static boolean isActive(Context context) {
        return status(context) != null;
    }

    /** The running timer, or null. Pure. */
    static Status status(Context context) {
        if (context == null) return null;
        State state = readState(context);
        if (state == null) return null;
        KeepADBDeadline.Clock now = clock;
        long wall = now.wallMs();
        long remaining = KeepADBDeadline.remainingMs(state.base, wall, now.elapsedMs(),
                now.bootCount(context));
        if (remaining <= 0) return null;
        return new Status(remaining, wall + remaining);
    }

    /**
     * Whether a timer is stored whose time is up but whose end has not been performed yet. Pure.
     * While true nothing may re-enable Wifi-ADB automatically, not even a Keep-Alive that is on in
     * Settings: the end is a "manual off" that has not been written yet.
     */
    static boolean isExpiryPending(Context context) {
        if (context == null) return false;
        return readState(context) != null && status(context) == null;
    }

    /** Whether an end's switch-off is still outstanding ({@link #KEY_SHUTDOWN_PENDING}). Pure. */
    static boolean isShutdownPending(Context context) {
        return context != null && prefs(context).getBoolean(KEY_SHUTDOWN_PENDING, false);
    }

    // ---- Transitions --------------------------------------------------------------------------------

    /**
     * Starts the timer for {@code duration}, replacing a running one, and switches Wifi-ADB on.
     * The state is stored first, so the manual switch-on below finds the timer and does not end
     * it, and Keep-Alive is effective from the first instant. If the switch-on fails for lack of
     * the permission, the state is removed again: no timer without the means to end it.
     *
     * @return the result of the switch-on, or null when the state could not be stored.
     */
    static KeepADB.ToggleResult start(Context context, Duration duration, String origin) {
        if (context == null || duration == null) return null;
        Context app = context.getApplicationContext();
        KeepADBDeadline.Clock now = clock;
        int boot = now.bootCount(app);
        if (boot < 0) {
            // Nothing to bind the budget to: it would end at once.
            KeepADBDiagnostics.event(app, "timer", origin, "refused", "boot_count_unreadable");
            return null;
        }
        State state = new State(new KeepADBDeadline.Base(duration.millis, now.wallMs(),
                now.elapsedMs(), boot));
        if (!store(app, state, true)) {
            KeepADBDiagnostics.event(app, "timer", origin, "failed", "state_not_stored");
            return null;
        }
        KeepADBTimerNotice.cancelExpired(app);
        KeepADBDiagnostics.event(app, "timer", origin, "started", "duration=" + duration.token);
        KeepADB.ToggleResult result = KeepADB.setEnabled(app, true, KeepADB.SOURCE_TIMER);
        if (result.isPermissionFailure()) {
            clear(app);
            KeepADBDiagnostics.event(app, "timer", origin, "cancelled", "permission_missing");
            refreshSurfaces(app, false);
            return result;
        }
        scheduleExpiryAlarm(app);
        refreshSurfaces(app, true);
        return result;
    }

    /**
     * Adds {@link #EXTENSION_MS} to the remaining time, capped at {@link #MAX_REMAINING_MS}. The
     * result becomes a new base now, so the budget can only ever be what the user saw plus one
     * hour.
     *
     * @return true if a running timer was extended.
     */
    static boolean extend(Context context, String origin) {
        if (context == null) return false;
        Context app = context.getApplicationContext();
        boolean extended = false;
        long newBudget = 0L;
        synchronized (LOCK) {
            State state = readState(app);
            if (state != null) {
                KeepADBDeadline.Clock now = clock;
                long wall = now.wallMs();
                long elapsed = now.elapsedMs();
                int boot = now.bootCount(app);
                long remaining = KeepADBDeadline.remainingMs(state.base, wall, elapsed, boot);
                if (remaining > 0 && remaining < MAX_REMAINING_MS) {
                    newBudget = Math.min(remaining + EXTENSION_MS, MAX_REMAINING_MS);
                    extended = store(app, new State(new KeepADBDeadline.Base(newBudget, wall,
                            elapsed, boot)), false);
                }
            }
        }
        if (!extended) {
            // An expired timer is finished instead of extended: time decides, not the tap.
            finishIfExpired(app);
            return false;
        }
        KeepADBDiagnostics.event(app, "timer", origin, "extended",
                "remainingMin=" + (newBudget / 60_000L));
        scheduleExpiryAlarm(app);
        refreshSurfaces(app, false);
        return true;
    }

    /**
     * "No limit" on the home screen: the running timer ends, Wifi-ADB stays as it is, and from now
     * on only the stored Keep-Alive setting decides. Nothing is written to Wifi-ADB.
     *
     * @return true if a running timer was removed.
     */
    static boolean removeLimit(Context context, String origin) {
        if (context == null) return false;
        Context app = context.getApplicationContext();
        if (finishIfExpired(app)) return false;
        if (!clear(app)) return false;
        cancelExpiryAlarm(app);
        KeepADBDiagnostics.event(app, "timer", origin, "ended", "no_limit");
        // Codex review: without the overlay a Keep-Alive that is off in Settings no longer needs
        // the service, and no write follows that would stop it.
        KeepADBService.sync(app);
        refreshSurfaces(app, false);
        return true;
    }

    /**
     * A manual on/off of Wifi-ADB through any source but the timer's own: the running timer ends
     * without a notice (the user decided). Called by {@link KeepADB#setEnabled} before it takes its
     * lock. Nothing is written here; the caller's write is the off or on.
     */
    static void onManualToggle(Context context, boolean on, String source) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        if (on) {
            // A deliberate "on" (the timer's own start included) supersedes an outstanding off.
            clearShutdownPending(app);
        }
        if (KeepADB.SOURCE_TIMER.equals(source) || readState(app) == null) return;
        if (finishIfExpired(app)) return;
        boolean cleared;
        synchronized (LOCK) {
            if (readState(app) == null) return;
            SharedPreferences.Editor editor = prefs(app).edit().remove(KEY_STATE);
            // Codex review: a manual off that then fails must not leave Wifi-ADB on without the
            // timer that would have ended it; the off stays an obligation until it is seen.
            if (!on) editor.putBoolean(KEY_SHUTDOWN_PENDING, true);
            cleared = editor.commit();
        }
        if (!cleared) return;
        scheduleExpiryAlarm(app);
        KeepADBDiagnostics.event(app, "timer", source, "ended",
                on ? "manual_on_without_timer" : "manual_off");
        refreshSurfaces(app, false);
    }

    /**
     * Performs the end if the time is up: clears the state, turns Wifi-ADB off as an explicit "off"
     * intent and delivers the one-time notice. Rebinds a running timer to a new boot or after a
     * clock set backward, like the force mode. Safe from every driver, any number of times: the
     * state is cleared in one atomic write, so exactly one caller performs the end.
     *
     * @return true if this call performed the end.
     */
    static boolean finishIfExpired(Context context) {
        if (context == null) return false;
        Context app = context.getApplicationContext();
        boolean transitioned = false;
        String reason = null;
        String reboundReason = null;
        boolean reboundFailed = false;
        synchronized (LOCK) {
            State state = readState(app);
            if (state != null) {
                KeepADBDeadline.Clock now = clock;
                long wall = now.wallMs();
                long elapsed = now.elapsedMs();
                int boot = now.bootCount(app);
                long remaining = KeepADBDeadline.remainingMs(state.base, wall, elapsed, boot);
                if (remaining <= 0) {
                    if (KeepADBDeadline.endedForSafety(state.base, wall, elapsed, boot)) {
                        reason = REASON_SAFETY;
                    } else if (!KeepADBDeadline.isSameBoot(state.base, elapsed, boot)) {
                        reason = REASON_WHILE_OFF;
                    }
                    // One commit: the end, the notice, the outstanding off and the "off" intent, so
                    // no crash between them can leave Wifi-ADB on with an "on" intent.
                    SharedPreferences.Editor ended = prefs(app).edit()
                            .remove(KEY_STATE)
                            .putBoolean(KEY_NOTICE_PENDING, true)
                            .putBoolean(KEY_SHUTDOWN_PENDING, true)
                            .putBoolean(KeepADBPreferences.KEY_LAST_DESIRED_ON, false);
                    if (reason != null) {
                        ended.putString(KEY_NOTICE_REASON, reason);
                    } else {
                        ended.remove(KEY_NOTICE_REASON);
                    }
                    transitioned = ended.commit();
                } else {
                    reboundReason = KeepADBDeadline.rebindReason(state.base, wall, elapsed, boot);
                    if (reboundReason != null) {
                        reboundFailed = !store(app, new State(new KeepADBDeadline.Base(remaining,
                                wall, elapsed, boot)), false);
                    }
                }
            }
        }
        if (reboundReason != null) {
            KeepADBDiagnostics.event(app, "timer", "timer", reboundFailed ? "failed" : "rebound",
                    reboundFailed ? "rebound_not_stored" : reboundReason);
        }
        if (transitioned) {
            KeepADBDiagnostics.event(app, "timer", "timer", "expired",
                    reason == null ? "deadline_passed" : reason);
            settleShutdown(app);
            refreshSurfaces(app, false);
        } else if (isShutdownPending(app)) {
            settleShutdown(app);
        }
        deliverPendingNotice(app);
        return transitioned;
    }

    /**
     * After a restart, an app update or a clock change: finish an end that happened while nothing
     * ran and re-arm the alarm. The boot path calls this before it looks at Keep-Alive.
     */
    static void restore(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        finishIfExpired(app);
        scheduleExpiryAlarm(app);
    }

    /**
     * Works off an outstanding switch-off ({@link #KEY_SHUTDOWN_PENDING}): done once a readback
     * shows Wifi-ADB off; otherwise the "off" intent is asserted again (a rejected delayed write
     * rolls it back) and the off is written, and a retry alarm stays armed until it is seen.
     * Called by every driver and at process start; never under a monitor.
     */
    static void settleShutdown(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        if (!isShutdownPending(app)) return;
        if (Boolean.FALSE.equals(KeepADB.isEnabledOrNull(app, "timer_shutdown"))) {
            clearShutdownPending(app);
            scheduleExpiryAlarm(app);
            return;
        }
        KeepADB.recordExplicitIntent(app, false);
        KeepADB.ToggleResult result = KeepADB.setEnabled(app, false, KeepADB.SOURCE_TIMER_EXPIRY);
        if (result == KeepADB.ToggleResult.APPLIED
                && Boolean.FALSE.equals(KeepADB.isEnabledOrNull(app, "timer_shutdown"))) {
            clearShutdownPending(app);
        } else {
            KeepADBDiagnostics.event(app, "timer", "timer", "shutdown_pending",
                    "result=" + result);
        }
        scheduleExpiryAlarm(app);
    }

    /**
     * A manual write went through ({@link KeepADB} calls this after applying a manual source, under
     * its lock: only a preference write here). A manual "on", or an "off" the readback shows,
     * settles an outstanding switch-off.
     */
    static void onManualWriteApplied(Context context, boolean on, boolean confirmedOff) {
        if (context == null || !(on || confirmedOff)) return;
        clearShutdownPending(context.getApplicationContext());
    }

    private static void clearShutdownPending(Context app) {
        synchronized (LOCK) {
            if (prefs(app).getBoolean(KEY_SHUTDOWN_PENDING, false)) {
                prefs(app).edit().remove(KEY_SHUTDOWN_PENDING).commit();
            }
        }
    }

    private static void deliverPendingNotice(Context app) {
        synchronized (NOTICE_LOCK) {
            if (!prefs(app).getBoolean(KEY_NOTICE_PENDING, false)) return;
            String reason = prefs(app).getString(KEY_NOTICE_REASON, null);
            NoticeSink sink = noticeSink;
            // Posted before the flag is cleared: a crash in between repeats the notice (same id,
            // so it replaces itself) rather than losing it.
            boolean posted = sink != null && sink.postExpired(app, reason);
            prefs(app).edit().remove(KEY_NOTICE_PENDING).remove(KEY_NOTICE_REASON).commit();
            KeepADBDiagnostics.event(app, "timer", "timer", "notice",
                    posted ? "posted" : "not_posted");
        }
    }

    // ---- Alarm ------------------------------------------------------------------------------------

    /**
     * Arms the inexact expiry alarm for the effective deadline, or cancels it. Inexact on purpose:
     * the Keep-Alive gate reads the deadline itself, so a late alarm only delays the off write and
     * the notice, never allows a re-enable; an exact alarm would need a special permission.
     */
    private static void scheduleExpiryAlarm(Context app) {
        AlarmManager alarms = app.getSystemService(AlarmManager.class);
        if (alarms == null) return;
        State state = readState(app);
        if (state == null) {
            if (isShutdownPending(app)) {
                // No timer left, but its off is outstanding: try again in a minute.
                alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        clock.elapsedMs() + SHUTDOWN_RETRY_MS, expiryAlarmIntent(app));
            } else {
                alarms.cancel(expiryAlarmIntent(app));
            }
            return;
        }
        KeepADBDeadline.Clock now = clock;
        long elapsed = now.elapsedMs();
        long remaining = KeepADBDeadline.remainingMs(state.base, now.wallMs(), elapsed,
                now.bootCount(app));
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                elapsed + Math.max(remaining, 0L), expiryAlarmIntent(app));
    }

    private static void cancelExpiryAlarm(Context app) {
        AlarmManager alarms = app.getSystemService(AlarmManager.class);
        if (alarms != null) alarms.cancel(expiryAlarmIntent(app));
    }

    private static PendingIntent expiryAlarmIntent(Context app) {
        Intent intent = new Intent(app, KeepADBReceiver.class)
                .setAction(KeepADBReceiver.ACTION_TIMER_EXPIRE);
        return PendingIntent.getBroadcast(app, REQUEST_CODE_EXPIRY_ALARM, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    // ---- Surfaces ---------------------------------------------------------------------------------

    static void setStateListener(Runnable listener) {
        stateListener = listener;
    }

    static void clearStateListener() {
        stateListener = null;
    }

    static void setNoticeSink(NoticeSink sink) {
        noticeSink = sink;
    }

    private static void refreshSurfaces(Context app, boolean syncService) {
        // A start makes Keep-Alive effective and needs the service; an end leaves it to the
        // service's own stop logic (and must not start a foreground service from a broadcast).
        if (syncService) KeepADBService.sync(app);
        KeepADBEndpointCoordinator.refresh(app);
        KeepADBWidget.refreshAll(app);
        Runnable listener = stateListener;
        if (listener != null) new Handler(Looper.getMainLooper()).post(listener);
    }

    // ---- Storage ----------------------------------------------------------------------------------

    private static boolean store(Context app, State state, boolean clearNotice) {
        synchronized (LOCK) {
            SharedPreferences.Editor editor = prefs(app).edit().putString(KEY_STATE, encode(state));
            if (clearNotice) editor.remove(KEY_NOTICE_PENDING).remove(KEY_NOTICE_REASON);
            return editor.commit();
        }
    }

    private static boolean clear(Context app) {
        synchronized (LOCK) {
            if (readState(app) == null) return false;
            return prefs(app).edit().remove(KEY_STATE).commit();
        }
    }

    private static State readState(Context context) {
        return decode(prefs(context).getString(KEY_STATE, null));
    }

    static String encode(State state) {
        KeepADBDeadline.Base base = state.base;
        return STATE_VERSION + ";" + base.budgetMs + ";" + base.wallMs + ";" + base.elapsedMs
                + ";" + base.bootCount;
    }

    /** Null for anything that is not a complete, plausible state; a damaged value reads as "off". */
    static State decode(String raw) {
        if (raw == null) return null;
        String[] parts = raw.split(";", -1);
        if (parts.length != 5 || !STATE_VERSION.equals(parts[0])) return null;
        try {
            long budget = Long.parseLong(parts[1]);
            long wall = Long.parseLong(parts[2]);
            long elapsed = Long.parseLong(parts[3]);
            int boot = Integer.parseInt(parts[4]);
            if (wall <= 0 || elapsed < 0) return null;
            // A budget above the cap would be an extension nobody granted: damaged.
            if (budget <= 0 || budget > MAX_REMAINING_MS) return null;
            return new State(new KeepADBDeadline.Base(budget, wall, elapsed, boot));
        } catch (NumberFormatException damaged) {
            return null;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ---- Test seams -------------------------------------------------------------------------------

    static void setClockForTesting(KeepADBDeadline.Clock testClock) {
        clock = testClock == null ? KeepADBDeadline.SYSTEM_CLOCK : testClock;
    }

    static void resetForTesting() {
        clock = KeepADBDeadline.SYSTEM_CLOCK;
        stateListener = null;
        noticeSink = null;
    }
}
