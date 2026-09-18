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

    private int attemptsInCycle;
    private long blockedUntilElapsedMs;

    /** True while an automatic enable attempt is currently suppressed. */
    synchronized boolean isBlocked(long nowElapsedMs) {
        return nowElapsedMs < blockedUntilElapsedMs;
    }

    /**
     * Records that an automatic enable attempt was just made (the write was accepted) but the
     * readback stayed off. Blocks further automatic attempts once {@link #MAX_ATTEMPTS_PER_CYCLE}
     * is reached, until {@link #FALLBACK_RETRY_INTERVAL_MS} has elapsed or an explicit trigger
     * calls {@link #reset()} first.
     */
    synchronized void recordMismatch(long nowElapsedMs) {
        attemptsInCycle++;
        if (attemptsInCycle >= MAX_ATTEMPTS_PER_CYCLE) {
            blockedUntilElapsedMs = nowElapsedMs + FALLBACK_RETRY_INTERVAL_MS;
        }
    }

    /** Records that an automatic enable attempt actually took effect: the cycle is over. */
    synchronized void recordSuccess() {
        reset();
    }

    /**
     * Explicit trigger (network change, manual user action, app/service restart, or an
     * externally observed successful readback): reopens the cycle immediately, ahead of the
     * fallback timer.
     */
    synchronized void reset() {
        attemptsInCycle = 0;
        blockedUntilElapsedMs = 0;
    }

    synchronized int attemptsInCycleForTesting() {
        return attemptsInCycle;
    }

    synchronized long blockedUntilElapsedMsForTesting() {
        return blockedUntilElapsedMs;
    }
}
