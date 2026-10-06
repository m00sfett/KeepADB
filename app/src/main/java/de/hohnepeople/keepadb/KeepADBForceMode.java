package de.hohnepeople.keepadb;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.format.DateFormat;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * The force mode (#763): for a time the user picks, every automatic re-enable of Wireless
 * Debugging ignores blocks and trust and only needs Wi-Fi and Keep-Alive. It is an overlay above
 * the trust model, not a mode of it: nothing the model stores (trusted access points, blocks, the
 * comfort switch, the legacy policy) is read or written by activating or ending it, so "back to
 * the previous protection level" is simply the overlay going away. See {@link
 * KeepADBTrustedNetwork#evaluateCurrent} for the one place the overlay is applied.
 *
 * <h2>Who starts it, who ends it</h2>
 * <ul>
 *   <li><b>Start:</b> only {@link #activate}, and the only caller is {@link KeepADBForceDialog}
 *       (asserted by a static test). No intent extra, no notification action, no receiver and no
 *       migration can start or extend it; changing the duration is a new activation through the
 *       same dialog with its warnings. There is no silent extension.</li>
 *   <li><b>End by the user:</b> {@link #endNow}, from the home card, the settings row or the
 *       notification action. It needs no confirmation: the safe direction.</li>
 *   <li><b>End by time:</b> the deadline itself. {@link #isActive} is a pure read that is false
 *       from the first instant the deadline has passed, so the trust gate (which consults it on
 *       every automatic path, also under the {@code KeepADB} lock right before a write) never
 *       depends on a timer having fired. {@link #finishIfExpired} then performs the visible
 *       transition once: it clears the stored state, refreshes the surfaces and delivers the
 *       one-time expiry notice. It is driven by the 60 second service heartbeat, an inexact
 *       {@link AlarmManager} alarm (also while the app has no process), the boot / package
 *       replaced / clock set broadcasts ({@link #restore}) and the screens when they open.</li>
 * </ul>
 *
 * <h2>Time rules</h2>
 * The state is one string in {@code keepadb_prefs} ({@link #KEY_STATE}), written with {@code
 * commit()} so a process kill right after the dialog cannot lose it: span, wall clock at start,
 * monotonic clock at start and the boot count at start. The remaining time is the <em>smaller</em>
 * of two measures, so every clock anomaly can only end the mode early, never extend it:
 * <ul>
 *   <li>wall clock: {@code start + span - now} (epoch based, so time zones and daylight saving
 *       time are irrelevant). A clock set <em>forward</em> ends it early. It is also the only
 *       measure across a reboot.</li>
 *   <li>monotonic clock ({@link SystemClock#elapsedRealtime}, includes deep sleep), used only
 *       while the boot count is unchanged: a clock set <em>backward</em> cannot extend it within
 *       a boot.</li>
 * </ul>
 * After a reboot (boot count changed, or unreadable) a wall clock earlier than the start cannot be
 * measured and ends the mode (fail closed). Known residual: a reboot followed by a manual clock
 * change backward that stays after the start extends the mode by the size of the jump; that needs
 * an unlocked device and does not happen through NTP, time zone or daylight saving changes. An
 * unlimited span has no deadline and survives restarts and app updates until it is ended.
 *
 * <h2>Persistence, rollback</h2>
 * Two additive keys ({@link #KEY_STATE}, {@link #KEY_NOTICE_PENDING}). An older app version ignores
 * them, so a downgrade ends the mode (the narrower state), loses no data and widens nothing. Like
 * the rest of {@code keepadb_prefs} they are excluded from backup and device transfer.
 *
 * <h2>Locks</h2>
 * {@link #LOCK} guards only the preference read-modify-write and is never held while calling into
 * other classes; {@link #NOTICE_LOCK} serializes the delivery of the expiry notice. {@link
 * #isActive} and {@link #status} take no lock and have no side effect, so they are safe under
 * the coordinator and {@code KeepADB} monitors; {@link #finishIfExpired}, {@link #endNow} and
 * {@link #activate} refresh surfaces and must not be called from there.
 */
final class KeepADBForceMode {
    private static final String PREFS_NAME = "keepadb_prefs";
    /** {@code 1;<span token>;<wall ms>;<elapsed ms>;<boot count>}, one value so reads are atomic. */
    static final String KEY_STATE = "force_state";
    /** The expiry happened and its notice has not been delivered (or not been confirmed) yet. */
    static final String KEY_NOTICE_PENDING = "force_expired_notice_pending";
    private static final String STATE_VERSION = "1";

    /** Request codes of the PendingIntents of the force mode (N6): one block, no overlap. */
    static final int REQUEST_CODE_END = 20;
    static final int REQUEST_CODE_EXPIRY_ALARM = 21;
    static final int REQUEST_CODE_EXPIRED_CONTENT = 22;
    static final int REQUEST_CODE_EXPIRED_TURN_OFF = 23;

    /** The selectable time limits. A time limit is mandatory; "unlimited" needs an acknowledgment. */
    enum Span {
        HOUR_1("1h", 60L * 60L * 1000L, 1),
        HOURS_24("24h", 24L * 60L * 60L * 1000L, 1),
        DAYS_7("7d", 7L * 24L * 60L * 60L * 1000L, 2),
        DAYS_30("30d", 30L * 24L * 60L * 60L * 1000L, 2),
        UNLIMITED("unlimited", 0L, 3);

        /** The preselected, shortest and safest span. */
        static final Span DEFAULT = HOUR_1;

        final String token;
        final long millis;
        /**
         * Which warnings the dialog shows: 1 the general warning, 2 additionally the several-days
         * warning, 3 additionally the no-end-time warning (and the acknowledgment checkbox).
         */
        final int warningStage;

        Span(String token, long millis, int warningStage) {
            this.token = token;
            this.millis = millis;
            this.warningStage = warningStage;
        }

        boolean isUnlimited() {
            return this == UNLIMITED;
        }

        static Span fromToken(String token) {
            for (Span span : values()) {
                if (span.token.equals(token)) return span;
            }
            return null;
        }
    }

    /** The three clocks the rules need. Test seam; production reads the real ones. */
    interface Clock {
        long wallMs();

        long elapsedMs();

        /** The boot counter of the device, or a negative number if unreadable. */
        int bootCount(Context context);
    }

    private static final Clock SYSTEM_CLOCK = new Clock() {
        @Override
        public long wallMs() {
            return System.currentTimeMillis();
        }

        @Override
        public long elapsedMs() {
            return SystemClock.elapsedRealtime();
        }

        @Override
        public int bootCount(Context context) {
            try {
                return Settings.Global.getInt(context.getContentResolver(),
                        Settings.Global.BOOT_COUNT, -1);
            } catch (RuntimeException unreadable) {
                return -1;
            }
        }
    };

    /** What is stored while the mode is on. */
    static final class State {
        final Span span;
        final long startedWallMs;
        final long startedElapsedMs;
        final int bootCount;

        State(Span span, long startedWallMs, long startedElapsedMs, int bootCount) {
            this.span = span;
            this.startedWallMs = startedWallMs;
            this.startedElapsedMs = startedElapsedMs;
            this.bootCount = bootCount;
        }
    }

    /** A pure snapshot of an active force mode. */
    static final class Status {
        final Span span;
        /** Milliseconds left, {@link Long#MAX_VALUE} without a deadline. Always positive. */
        final long remainingMs;
        /** Wall clock instant of the effective end (now plus the remaining time), 0 without one. */
        final long endsAtWallMs;

        Status(Span span, long remainingMs, long endsAtWallMs) {
            this.span = span;
            this.remainingMs = remainingMs;
            this.endsAtWallMs = endsAtWallMs;
        }

        boolean isUnlimited() {
            return span.isUnlimited();
        }
    }

    private static final Object LOCK = new Object();
    private static final Object NOTICE_LOCK = new Object();
    private static volatile Clock clock = SYSTEM_CLOCK;
    private static volatile Runnable stateListener;

    private KeepADBForceMode() {}

    // ---- Pure reads -------------------------------------------------------------------------------

    /**
     * Whether the force mode is on right now. Pure: no lock, no write, no notification; false from
     * the first instant the deadline has passed, whether or not {@link #finishIfExpired} has run.
     */
    static boolean isActive(Context context) {
        return status(context) != null;
    }

    /** The active mode, or null. Same purity as {@link #isActive}. */
    static Status status(Context context) {
        if (context == null) return null;
        State state = readState(context);
        if (state == null) return null;
        if (state.span.isUnlimited()) return new Status(state.span, Long.MAX_VALUE, 0L);
        Clock now = clock;
        long wall = now.wallMs();
        long remaining = remainingMs(state, wall, now.elapsedMs(), now.bootCount(context));
        if (remaining <= 0) return null;
        return new Status(state.span, remaining, wall + remaining);
    }

    /**
     * The remaining time of a limited state, the smaller of the wall clock and the monotonic
     * measure (see the class javadoc); zero or less means expired.
     */
    static long remainingMs(State state, long wallNow, long elapsedNow, int bootNow) {
        if (state.span.isUnlimited()) return Long.MAX_VALUE;
        long total = state.span.millis;
        long remaining = state.startedWallMs + total - wallNow;
        boolean sameBoot = state.bootCount >= 0 && bootNow == state.bootCount
                && elapsedNow >= state.startedElapsedMs;
        if (sameBoot) {
            return Math.min(remaining, state.startedElapsedMs + total - elapsedNow);
        }
        // Another boot, or the boot cannot be told: only the wall clock is left, and a wall clock
        // before the start (reset RTC, clock set back) cannot be measured -- fail closed.
        if (wallNow < state.startedWallMs) return 0L;
        return remaining;
    }

    /** The end as text for the surfaces: a time of day within today, otherwise weekday, date and time. */
    static String formatEnd(Context context, Status status) {
        Locale locale = context.getResources().getConfiguration().getLocales().get(0);
        Calendar end = Calendar.getInstance();
        end.setTimeInMillis(status.endsAtWallMs);
        Calendar now = Calendar.getInstance();
        now.setTimeInMillis(clock.wallMs());
        boolean sameDay = end.get(Calendar.YEAR) == now.get(Calendar.YEAR)
                && end.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR);
        String pattern = DateFormat.getBestDateTimePattern(locale, sameDay ? "jm" : "EEEdMMMjm");
        return new SimpleDateFormat(pattern, locale).format(new Date(status.endsAtWallMs));
    }

    // ---- Transitions --------------------------------------------------------------------------------

    /**
     * Turns the force mode on for {@code span}, replacing a running one (a new full start, never
     * an extension). Only {@link KeepADBForceDialog} calls this, after the warnings. An unlimited
     * span is refused without {@code unlimitedAcknowledged}; a state that could not be stored is
     * refused too, so a failed write can never leave the user believing it is on.
     *
     * <p>Keep-Alive is switched on with it (F7) when it is off; when it already is on, nothing
     * about it is touched, so an earlier manual "off" of Wireless Debugging itself stays respected.
     *
     * @return true if the force mode is on afterwards.
     */
    static boolean activate(Context context, Span span, boolean unlimitedAcknowledged) {
        if (context == null || span == null) return false;
        Context app = context.getApplicationContext();
        if (span.isUnlimited() && !unlimitedAcknowledged) {
            KeepADBDiagnostics.event(app, "force_mode", "app", "refused", "unlimited_not_acknowledged");
            return false;
        }
        Clock now = clock;
        State state = new State(span, now.wallMs(), now.elapsedMs(), now.bootCount(app));
        boolean stored;
        synchronized (LOCK) {
            stored = prefs(app).edit()
                    .putString(KEY_STATE, encode(state))
                    .remove(KEY_NOTICE_PENDING)
                    .commit();
        }
        if (!stored) {
            KeepADBDiagnostics.event(app, "force_mode", "app", "failed", "state_not_stored");
            return false;
        }
        if (!KeepADBPreferences.isKeepAliveEnabled(app)) {
            KeepADBPreferences.setKeepAliveEnabled(app, true);
        }
        scheduleExpiryAlarm(app);
        KeepADBDiagnostics.event(app, "force_mode", "app", "activated", "span=" + span.token);
        refreshSurfaces(app, true);
        return true;
    }

    /**
     * Ends the force mode at the user's request. A mode whose time has in fact run out is reported
     * as an expiry instead (and this returns false), so the user is not told "ended by you" about
     * something time ended. Needs no confirmation.
     *
     * @return true if a running force mode was ended by this call.
     */
    static boolean endNow(Context context) {
        if (context == null) return false;
        Context app = context.getApplicationContext();
        if (finishIfExpired(app)) return false;
        boolean ended = false;
        synchronized (LOCK) {
            if (readState(app) != null) {
                ended = prefs(app).edit().remove(KEY_STATE).remove(KEY_NOTICE_PENDING).commit();
            }
        }
        if (!ended) return false;
        cancelExpiryAlarm(app);
        KeepADBForceNotice.cancelExpired(app);
        KeepADBDiagnostics.event(app, "force_mode", "user", "ended", "by_user");
        refreshSurfaces(app, false);
        return true;
    }

    /**
     * Performs the expiry transition if the deadline has passed: clears the stored state, refreshes
     * the surfaces and delivers the one-time notice. Also delivers a notice that an earlier call
     * recorded but did not get to post (process death in between). Safe to call from any of the
     * drivers, any number of times: the state is cleared in one atomic write, so exactly one caller
     * performs the transition, and the notice is delivered once.
     *
     * @return true if this call performed the expiry transition.
     */
    static boolean finishIfExpired(Context context) {
        if (context == null) return false;
        Context app = context.getApplicationContext();
        boolean transitioned = false;
        synchronized (LOCK) {
            State state = readState(app);
            if (state != null && !state.span.isUnlimited()) {
                Clock now = clock;
                long remaining = remainingMs(state, now.wallMs(), now.elapsedMs(), now.bootCount(app));
                if (remaining <= 0) {
                    transitioned = prefs(app).edit()
                            .remove(KEY_STATE)
                            .putBoolean(KEY_NOTICE_PENDING, true)
                            .commit();
                }
            }
        }
        if (transitioned) {
            cancelExpiryAlarm(app);
            KeepADBDiagnostics.event(app, "force_mode", "timer", "expired", "deadline_passed");
            refreshSurfaces(app, false);
        }
        deliverPendingNotice(app);
        return transitioned;
    }

    /**
     * What the system restarts and clock changes need: finish an expiry that happened while nothing
     * ran, and re-arm the alarm (alarms do not survive a reboot or an app update, and a clock set
     * moves the effective deadline).
     */
    static void restore(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        finishIfExpired(app);
        scheduleExpiryAlarm(app);
    }

    private static void deliverPendingNotice(Context app) {
        synchronized (NOTICE_LOCK) {
            if (!prefs(app).getBoolean(KEY_NOTICE_PENDING, false)) return;
            // Posted before the flag is cleared: a crash in between repeats the notice (same id,
            // so it replaces itself) rather than losing it.
            boolean posted = KeepADBForceNotice.postExpired(app);
            prefs(app).edit().remove(KEY_NOTICE_PENDING).commit();
            KeepADBDiagnostics.event(app, "force_mode", "timer", "notice",
                    posted ? "posted" : "not_posted");
        }
    }

    // ---- Alarm ----------------------------------------------------------------------------------------------

    /**
     * Arms the inexact expiry alarm for the effective deadline, or cancels it when there is no
     * limited mode. Inexact on purpose: it only has to deliver the notice, the gate itself is exact
     * without it, and an exact alarm would need a permission KeepADB does not hold.
     */
    private static void scheduleExpiryAlarm(Context app) {
        AlarmManager alarms = app.getSystemService(AlarmManager.class);
        if (alarms == null) return;
        State state = readState(app);
        if (state == null || state.span.isUnlimited()) {
            alarms.cancel(expiryAlarmIntent(app));
            return;
        }
        Clock now = clock;
        long elapsed = now.elapsedMs();
        long remaining = remainingMs(state, now.wallMs(), elapsed, now.bootCount(app));
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                elapsed + Math.max(remaining, 0L), expiryAlarmIntent(app));
    }

    private static void cancelExpiryAlarm(Context app) {
        AlarmManager alarms = app.getSystemService(AlarmManager.class);
        if (alarms != null) alarms.cancel(expiryAlarmIntent(app));
    }

    private static PendingIntent expiryAlarmIntent(Context app) {
        Intent intent = new Intent(app, KeepADBReceiver.class)
                .setAction(KeepADBReceiver.ACTION_FORCE_EXPIRE);
        return PendingIntent.getBroadcast(app, REQUEST_CODE_EXPIRY_ALARM, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    // ---- Surfaces ---------------------------------------------------------------------------------------------

    /** The open screen re-renders when the mode starts or ends; one listener, like the register's. */
    static void setStateListener(Runnable listener) {
        stateListener = listener;
    }

    static void clearStateListener() {
        stateListener = null;
    }

    private static void refreshSurfaces(Context app, boolean restartService) {
        // Starting the service is only needed when Keep-Alive was just switched on; an expiry or an
        // end leaves it exactly as it is (and must not start a foreground service from a broadcast).
        if (restartService) KeepADBService.sync(app);
        KeepADBEndpointCoordinator.refresh(app);
        KeepADBWidget.refreshAll(app);
        Runnable listener = stateListener;
        if (listener != null) new Handler(Looper.getMainLooper()).post(listener);
    }

    // ---- Storage ---------------------------------------------------------------------------------------------

    private static State readState(Context context) {
        return decode(prefs(context).getString(KEY_STATE, null));
    }

    static String encode(State state) {
        return STATE_VERSION + ";" + state.span.token + ";" + state.startedWallMs + ";"
                + state.startedElapsedMs + ";" + state.bootCount;
    }

    /** Null for anything that is not a complete, plausible state: a damaged value reads as "off". */
    static State decode(String raw) {
        if (raw == null) return null;
        String[] parts = raw.split(";", -1);
        if (parts.length != 5 || !STATE_VERSION.equals(parts[0])) return null;
        Span span = Span.fromToken(parts[1]);
        if (span == null) return null;
        try {
            long wall = Long.parseLong(parts[2]);
            long elapsed = Long.parseLong(parts[3]);
            int boot = Integer.parseInt(parts[4]);
            if (wall <= 0 || elapsed < 0) return null;
            return new State(span, wall, elapsed, boot);
        } catch (NumberFormatException damaged) {
            return null;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ---- Test seams -----------------------------------------------------------------------------------------

    static void setClockForTesting(Clock testClock) {
        clock = testClock == null ? SYSTEM_CLOCK : testClock;
    }

    static boolean hasStateListenerForTesting() {
        return stateListener != null;
    }

    static void resetForTesting() {
        clock = SYSTEM_CLOCK;
        stateListener = null;
    }
}
