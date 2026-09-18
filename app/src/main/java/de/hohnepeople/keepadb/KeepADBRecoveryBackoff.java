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
 * enable attempt per "unchanged state" cycle, so the OS pairing dialog is triggered but never
 * spammed. A cycle ends -- and one fresh attempt is allowed again -- the moment any of the
 * recognized triggers fires: an observed successful readback (the permission was granted), a
 * Wi-Fi network change, a manual user action, an app/service restart, or, lacking any of those, a
 * fallback timer long enough that it can never be mistaken for the 1500ms loop it replaces. The
 * call sites in {@link KeepADBService} own *when* to consult this class (mirroring how the
 * trusted-network policy stays at the call site per #245); this class only tracks the resulting
 * attempt/cycle bookkeeping and never touches {@code Settings.Global}, a {@code Context}, or any
 * other Android type.
 *
 * <p>Framework-free and self-synchronized for the same reason {@link KeepADBToggleState} is:
 * every transition is exercisable with plain JUnit, with no Android side effects at all.
 */
final class KeepADBRecoveryBackoff {

    /** One controlled attempt per cycle -- the repo owner's explicit decision on #496. */
    static final int MAX_ATTEMPTS_PER_CYCLE = 1;

    /**
     * Fallback retry spacing once a cycle is exhausted and no other trigger (network change,
     * manual action, restart, observed success) has reopened it yet. Deliberately long enough
     * that it can never be mistaken for the 1500ms {@code TOGGLE_COOLDOWN_MS} loop this class
     * replaces, while still short enough that a user who confirmed the pairing dialog without
     * KeepADB noticing doesn't wait indefinitely for automatic recovery to resume. Not specified
     * by the issue -- a pragmatic choice, easy to retune later without touching the policy above.
     */
    static final long FALLBACK_RETRY_INTERVAL_MS = 15 * 60 * 1000L; // 15 minutes

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
     * is not evidence either way (see {@link #SUCCESS_CONFIRMATION_MS}). The cycle stays blocked
     * until {@link #confirmSuccess()} proves the value actually stuck, an explicit trigger calls
     * {@link #reset()}, or {@link #FALLBACK_RETRY_INTERVAL_MS} has elapsed.
     */
    synchronized void recordAttempt(long nowElapsedMs) {
        attemptsInCycle++;
        awaitingConfirmation = true;
        if (attemptsInCycle >= MAX_ATTEMPTS_PER_CYCLE) {
            blockedUntilElapsedMs = nowElapsedMs + FALLBACK_RETRY_INTERVAL_MS;
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
