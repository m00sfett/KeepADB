package de.hohnepeople.keepadb;

import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;

/**
 * The time rules shared by every time-limited state of KeepADB: the force mode (#763) and the
 * Wifi-ADB timer (#1000). Pure arithmetic over a stored budget and the base it counts from; no
 * storage, no lock, no side effect. The owners keep their own state, persistence and transitions.
 *
 * <p>The remaining time is the <em>smaller</em> of two measures, so every clock anomaly can only
 * end a deadline early, never extend it:
 * <ul>
 *   <li>wall clock: {@code base + budget - now} (epoch based, so time zones and daylight saving
 *       time are irrelevant). A clock set forward ends it early. It is the only measure after a
 *       restart until the budget has been rebound.</li>
 *   <li>monotonic clock ({@link SystemClock#elapsedRealtime}, includes deep sleep), used only while
 *       the boot count is the one of the base: a clock set backward cannot extend it within that
 *       boot.</li>
 * </ul>
 * After a restart only the wall clock can measure, and a wall clock before the base (reset RTC,
 * clock set back) cannot be measured: the deadline counts as passed (fail closed). An unreadable
 * boot count ends it too. The owners rebind the budget to the new boot as soon as a driver runs
 * ({@link #rebindReason}), see {@link KeepADBForceMode} for the full rationale.
 */
final class KeepADBDeadline {
    /**
     * How far the wall clock may fall behind the monotonic clock before the set is written down.
     * Android's network time client by default corrects only a clock that is off by more than about
     * five seconds, so ordinary operation writes nothing; what stays unwritten can credit at most
     * this much.
     */
    static final long CLOCK_SET_BACK_TOLERANCE_MS = 5_000L;

    static final String REBIND_NEW_BOOT = "new_boot";
    static final String REBIND_CLOCK_SET_BACK = "clock_set_back";

    /** The three clocks the rules need. Test seam; production reads the real ones. */
    interface Clock {
        long wallMs();

        long elapsedMs();

        /** The boot counter of the device, or a negative number if unreadable. */
        int bootCount(Context context);
    }

    static final Clock SYSTEM_CLOCK = new Clock() {
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

    /** A budget and the base it counts from (wall clock, monotonic clock, boot count). */
    static final class Base {
        final long budgetMs;
        final long wallMs;
        final long elapsedMs;
        final int bootCount;

        Base(long budgetMs, long wallMs, long elapsedMs, int bootCount) {
            this.budgetMs = budgetMs;
            this.wallMs = wallMs;
            this.elapsedMs = elapsedMs;
            this.bootCount = bootCount;
        }
    }

    private KeepADBDeadline() {}

    /** The remaining time, the smaller of both measures; zero or less means passed. */
    static long remainingMs(Base base, long wallNow, long elapsedNow, int bootNow) {
        if (bootNow < 0) return 0L;
        long remaining = base.wallMs + base.budgetMs - wallNow;
        if (isSameBoot(base, elapsedNow, bootNow)) {
            return Math.min(remaining, base.elapsedMs + base.budgetMs - elapsedNow);
        }
        // Another boot: only the wall clock is left, and a wall clock before the base cannot be
        // measured -- fail closed. Counted from the base it can only shrink the budget.
        if (wallNow < base.wallMs) return 0L;
        return remaining;
    }

    /**
     * Whether a passed deadline passed for safety, not because its time was up: the boot count is
     * unreadable, or the wall clock is before the base after a restart. Only meaningful when
     * {@link #remainingMs} is zero or less.
     */
    static boolean endedForSafety(Base base, long wallNow, long elapsedNow, int bootNow) {
        if (bootNow < 0) return true;
        return !isSameBoot(base, elapsedNow, bootNow) && wallNow < base.wallMs;
    }

    /** Whether the monotonic clock of the base is the one running now. */
    static boolean isSameBoot(Base base, long elapsedNow, int bootNow) {
        return bootNow >= 0 && bootNow == base.bootCount && elapsedNow >= base.elapsedMs;
    }

    /**
     * Whether, in the boot of the base, the wall clock has fallen behind the monotonic one by more
     * than {@link #CLOCK_SET_BACK_TOLERANCE_MS} since the base: it was set backward. Counted from the
     * base, so a series of small steps adds up. A clock set forward is no reason to write: the
     * smaller measure already ends the deadline early.
     */
    static boolean isWallClockSetBack(Base base, long wallNow, long elapsedNow) {
        long realPassed = elapsedNow - base.elapsedMs;
        long wallPassed = wallNow - base.wallMs;
        return realPassed - wallPassed > CLOCK_SET_BACK_TOLERANCE_MS;
    }

    /**
     * Why a running deadline must be rebound now ({@link #REBIND_NEW_BOOT} or {@link
     * #REBIND_CLOCK_SET_BACK}), or null when the stored base is still right. Only meaningful while
     * {@link #remainingMs} is positive, i.e. the boot count is readable.
     */
    static String rebindReason(Base base, long wallNow, long elapsedNow, int bootNow) {
        if (!isSameBoot(base, elapsedNow, bootNow)) return REBIND_NEW_BOOT;
        if (isWallClockSetBack(base, wallNow, elapsedNow)) return REBIND_CLOCK_SET_BACK;
        return null;
    }
}
