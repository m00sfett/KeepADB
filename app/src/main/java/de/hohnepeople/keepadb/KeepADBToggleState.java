package de.hohnepeople.keepadb;

/**
 * Platform-independent core of KeepADB's toggle/recovery state machine.
 *
 * Owns exactly the mutable state that decides *what to do next* -- user-intent tracking,
 * intent-token generation for cancelling stale/superseded writes, and toggle-cooldown debounce
 * timing -- with zero Android imports and zero side effects (no Settings.Global, no Context, no
 * notification/widget refresh). It never performs an action itself; callers act on the values it
 * returns. This makes the state transitions independently testable with plain JUnit (no
 * Robolectric, no framework mocks) -- see KeepADBToggleStateTest.
 *
 * Extracted from KeepADB.java per issue #248; see that class for the Android-facing orchestration
 * wrapped around this core (Settings.Global writes via AdbWifiSettingsGateway, Handler-based
 * debounce scheduling, diagnostics, and notification/widget refresh as output effects).
 */
final class KeepADBToggleState {

    // #114: a bare rapid off/on write can catch the system's AdbService mid-teardown of the
    // previous session. Debouncing actual writes by this cooldown gives adbd time to finish
    // tearing down before it sees the next transition.
    static final long TOGGLE_COOLDOWN_MS = 1500;
    static final long RECOVERY_PULSE_OFF_MS = 800;

    // Set right after a user-initiated disable, consumed once by KeepADBService's keep-alive
    // observer so it doesn't immediately re-enable a deliberate shutoff.
    private boolean userDisabled;

    // Independent, non-consumed counterpart to userDisabled: the on/off state of the last
    // explicit setEnabled()/requestToggle() call. #168 found that two independent consumers of
    // one one-shot token is a bug (whichever reads first "uses it up" for the other), so this
    // field exists purely to be read repeatedly without ever being consumed -- only ever
    // overwritten by the next requestToggle() (or forced via forceLastDesiredOn()).
    //
    // A recovery pulse (beginPulse()/recordAppliedTime()) deliberately never touches this field:
    // it only ever runs when userDisabled is false, i.e. the last explicit intent was already
    // "on", so the pulse is a same-state bounce (on -> brief off -> on) rather than a new intent.
    private boolean lastDesiredOn = true;
    private long lastAppliedChangeMs;
    private long currentIntentToken;

    /** Result of {@link #requestToggle}: what the caller should do about scheduling the write. */
    static final class ToggleDecision {
        final long token;
        final long delayMs;

        ToggleDecision(long token, long delayMs) {
            this.token = token;
            this.delayMs = delayMs;
        }

        boolean isImmediate() {
            return delayMs <= 0;
        }
    }

    /**
     * Records a new explicit on/off intent and computes the debounce delay, if any, against the
     * elapsed-time clock reading of the last applied write. Always issues a fresh intent token,
     * superseding any not-yet-applied pending toggle or in-flight recovery pulse.
     */
    synchronized ToggleDecision requestToggle(boolean on, long nowElapsedMs) {
        long token = ++currentIntentToken;
        userDisabled = !on;
        lastDesiredOn = on;
        long sinceLastMs = nowElapsedMs - lastAppliedChangeMs;
        long delayMs = sinceLastMs < TOGGLE_COOLDOWN_MS ? TOGGLE_COOLDOWN_MS - sinceLastMs : 0;
        return new ToggleDecision(token, delayMs);
    }

    /** True iff the given intent token is still the newest one issued, i.e. not superseded. */
    synchronized boolean isCurrentIntent(long token) {
        return token == currentIntentToken;
    }

    /** Records that {@code on} was actually applied at {@code nowElapsedMs}. */
    synchronized void recordApplied(boolean on, long nowElapsedMs) {
        userDisabled = !on;
        lastDesiredOn = on;
        lastAppliedChangeMs = nowElapsedMs;
    }

    /** Records the elapsed-time clock reading of a write without changing user-intent flags. */
    synchronized void recordAppliedTime(long nowElapsedMs) {
        lastAppliedChangeMs = nowElapsedMs;
    }

    /**
     * Issues a fresh intent token for a recovery pulse, unconditionally. Callers must check
     * {@link #isUserDisabled()} (and the Android-aware last-intent/observed-state checks that
     * need a Context) themselves first -- this class deliberately knows nothing about Settings.Global.
     */
    synchronized long beginPulse() {
        return ++currentIntentToken;
    }

    synchronized boolean isUserDisabled() {
        return userDisabled;
    }

    /** Non-consumed counterpart to {@link #isUserDisabled()}; see the field comment above. */
    synchronized boolean wasLastExplicitIntentOff() {
        return !lastDesiredOn;
    }

    /** Overrides the in-memory last-intent flag, e.g. when a persisted preference disagrees. */
    synchronized void forceLastDesiredOn(boolean on) {
        lastDesiredOn = on;
    }

    /** Consumes and returns whether the last disable was user-initiated (vs. an external drop). */
    synchronized boolean consumeUserDisabled() {
        boolean was = userDisabled;
        userDisabled = false;
        return was;
    }

    /** Resets all state to defaults; used by tests and by KeepADB.resetForTesting(). */
    synchronized void reset() {
        userDisabled = false;
        lastDesiredOn = true;
        lastAppliedChangeMs = 0;
        currentIntentToken = 0;
    }
}
