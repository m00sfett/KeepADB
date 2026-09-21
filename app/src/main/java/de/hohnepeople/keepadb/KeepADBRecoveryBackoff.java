package de.hohnepeople.keepadb;

/**
 * Bounds automatic Keep-Alive re-enable attempts after a write that {@code Settings.Global}
 * accepted but whose readback never actually flipped to "on" (#496): on a network whose Android
 * "always allow Wireless Debugging on this network" pairing dialog was never confirmed, every
 * retry is accepted by the system call and still leaves {@code adb_wifi_enabled} at 0. Retrying
 * at the plain {@link KeepADBToggleState#TOGGLE_COOLDOWN_MS} debounce cadence turned that into
 * roughly one write every 1.5s -- about 160 in a minute, observed during F-Droid review on a
 * Redmi Note 8T -- because the write itself (accepted, then reverted by the OS) re-triggers the
 * very {@code ContentObserver} that schedules the next automatic attempt.
 *
 * <p>Policy, per the repo owner's explicit decision on #496: exactly one controlled automatic
 * enable attempt per retry window, so the OS pairing dialog is triggered but never spammed. A
 * window ends -- and one fresh attempt is allowed again -- the moment any of the recognized
 * triggers fires: an observed successful readback (the permission was granted), a Wi-Fi network
 * change, a manual user action, an app/service restart, or, lacking any of those, the fallback
 * retry timer below. #536 replaced that timer's single flat interval with the two-stage cadence
 * the repo owner specified: the first unconfirmed attempt reopens after {@link
 * #FIRST_RETRY_DELAY_MS} (roughly 2 minutes), and every attempt after that is capped at {@link
 * #RETRY_INTERVAL_MS} (5 minutes) apart -- it never grows further, so a stubborn dialog does not
 * make recovery wait longer and longer for it. Critically, #536 also closed the gap the flat
 * interval alone always had: a stored {@code blockedUntilElapsedMs} is a fact this class can
 * report on demand, but nothing about it makes anyone ask again once it is in the past -- {@link
 * KeepADBService}'s heartbeat ticker is what actually re-invokes {@code recheckAndEnable()} once
 * the window elapses, turning the timestamp into a real retry instead of a value that only ever
 * gets consulted by the next unrelated event. The call sites in {@link KeepADBService} own *when*
 * to consult this class (mirroring how the trusted-network policy stays at the call site per
 * #245); this class only tracks the resulting attempt/window bookkeeping and never touches
 * {@code Settings.Global}, a {@code Context}, or any other Android type.
 *
 * <p>Framework-free and self-synchronized for the same reason {@link KeepADBToggleState} is:
 * every transition is exercisable with plain JUnit, with no Android side effects at all.
 */
final class KeepADBRecoveryBackoff {

    /** One controlled attempt per retry window -- the repo owner's explicit decision on #496. */
    static final int MAX_ATTEMPTS_PER_CYCLE = 1;

    /**
     * Spacing before the very first retry after an unconfirmed automatic attempt (#536's
     * "ungefähr 2 Minuten" acceptance criterion). Deliberately long enough that it can never be
     * mistaken for the 1500ms {@code TOGGLE_COOLDOWN_MS} loop this class replaces, while still
     * short enough that a user who confirmed the pairing dialog without KeepADB noticing doesn't
     * wait long for automatic recovery to resume on its own.
     */
    static final long FIRST_RETRY_DELAY_MS = 2 * 60 * 1000L; // ~2 minutes

    /**
     * Spacing for every retry after the first one, and the cap the cadence never exceeds (#536's
     * "höchstens im Abstand von 5 Minuten" acceptance criterion). A cycle that keeps mismatching
     * therefore retries at 2, 7, 12, 17, ... minutes after the first attempt -- bounded, never
     * growing further, so a stubborn dialog costs at most one controlled attempt every 5 minutes
     * instead of escalating.
     */
    static final long RETRY_INTERVAL_MS = 5 * 60 * 1000L; // 5 minutes

    /**
     * How long an accepted automatic enable must survive before it counts as a real success
     * (#500). The synchronous readback taken immediately after {@code Settings.Global.putInt}
     * cannot tell the two cases apart: on a network whose pairing dialog was never confirmed the
     * system accepts the value, serves it back as 1 on the very next read, and only then reverts
     * it to 0 asynchronously. Treating that first read as success reset the whole backoff before
     * the revert ever arrived, which is why the device test on #500 saw 186 attempts in 72
     * seconds and not a single {@code state_mismatch}. Chosen well above the OS revert latency
     * (observed as part of the same ~1.5s cycle) and well below anything a user would notice.
     */
    static final long SUCCESS_CONFIRMATION_MS = 3000L;

    private int attemptsInCycle;
    private long blockedUntilElapsedMs;
    private boolean awaitingConfirmation;

    /** True while an automatic enable attempt is currently suppressed. */
    synchronized boolean isBlocked(long nowElapsedMs) {
        return nowElapsedMs < blockedUntilElapsedMs;
    }

    /**
     * Records that an automatic enable attempt was just made and its write was accepted. The
     * attempt counts -- and blocks further automatic attempts once {@link #MAX_ATTEMPTS_PER_CYCLE}
     * is reached -- <em>regardless</em> of what the immediate readback said, because that readback
     * is not evidence either way (see {@link #SUCCESS_CONFIRMATION_MS}). The window stays blocked
     * until {@link #confirmSuccess()} proves the value actually stuck, an explicit trigger calls
     * {@link #reset()}, or its retry delay elapses -- {@link #FIRST_RETRY_DELAY_MS} for the first
     * attempt in the cycle, {@link #RETRY_INTERVAL_MS} (the cap) for every attempt after that.
     */
    synchronized void recordAttempt(long nowElapsedMs) {
        attemptsInCycle++;
        awaitingConfirmation = true;
        if (attemptsInCycle >= MAX_ATTEMPTS_PER_CYCLE) {
            long delay = attemptsInCycle <= 1 ? FIRST_RETRY_DELAY_MS : RETRY_INTERVAL_MS;
            blockedUntilElapsedMs = nowElapsedMs + delay;
        }
    }

    /** True between {@link #recordAttempt} and its delayed stability verdict. */
    synchronized boolean isAwaitingConfirmation() {
        return awaitingConfirmation;
    }

    /**
     * The attempt recorded by {@link #recordAttempt} was still in effect after
     * {@link #SUCCESS_CONFIRMATION_MS}: it really took hold, so the cycle is over.
     */
    synchronized void confirmSuccess() {
        reset();
    }

    /**
     * The attempt recorded by {@link #recordAttempt} did not survive the confirmation window --
     * the system reverted it. The block stays in place; only the "awaiting" marker is cleared so
     * later observations are no longer attributed to that attempt.
     */
    synchronized void recordUnconfirmed() {
        awaitingConfirmation = false;
    }

    /**
     * An "on" readback was observed from outside our own in-flight attempt (typically the
     * ContentObserver: the user confirmed Android's pairing dialog, or enabled the switch
     * elsewhere). Reopens the cycle -- but never while an attempt of ours is still awaiting its
     * verdict, because the transient "on" our own accepted-then-reverted write produces would
     * otherwise reset the very backoff it is supposed to engage (#500).
     */
    synchronized void noteObservedEnabled() {
        if (!awaitingConfirmation) {
            reset();
        }
    }

    /**
     * Explicit trigger (network change, manual user action, app/service restart, or an
     * externally observed successful readback): reopens the cycle immediately, ahead of the
     * fallback timer.
     */
    synchronized void reset() {
        attemptsInCycle = 0;
        blockedUntilElapsedMs = 0;
        awaitingConfirmation = false;
    }

    synchronized int attemptsInCycleForTesting() {
        return attemptsInCycle;
    }

    synchronized long blockedUntilElapsedMsForTesting() {
        return blockedUntilElapsedMs;
    }
}
