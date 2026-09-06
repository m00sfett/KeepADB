package de.hohnepeople.keepadb;

/**
 * Platform-independent core of KeepADB's toggle/recovery state machine (#248).
 *
 * <p>Owns exactly the mutable state that decides <em>what to do next</em> -- user-intent
 * tracking, the generation token that cancels stale or superseded writes, and the toggle-cooldown
 * debounce timing -- with zero Android imports and zero side effects: no {@code Settings.Global},
 * no {@code Context}, no notification/widget refresh, no {@code SharedPreferences}. It never
 * performs an action itself; callers act on the values it returns. That makes every transition
 * exercisable with plain JUnit and no framework stubs at all (see {@code KeepADBToggleStateTest}),
 * which is the acceptance criterion "state transitions can be tested without requiring Android
 * framework side effects".
 *
 * <p>The Android-facing orchestration wrapped around this core stays in {@link KeepADB}: the
 * {@link KeepADBSettingsGateway} write, {@link KeepADBScheduler} timing, the persisted
 * last-intent preference, diagnostics, and the {@link KeepADBSurfaceRefresher} output effect.
 *
 * <p>Instances are self-synchronized so the core is safe to read from the recovery-pulse thread,
 * but {@link KeepADB} additionally keeps its coarser {@code synchronized (KeepADB.class)}
 * sections: a decision plus the Android effect it authorizes must stay atomic together, which a
 * per-method lock inside this class cannot express. Lock order is therefore always
 * {@code KeepADB.class} then this instance; this class never calls back out, so it cannot invert.
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
    // explicit setEnabled() call. userDisabled is a one-shot token with exactly one intended
    // consumer (KeepADBService's content observer, deciding "stop recovering" vs. "recover").
    // #168 added a second, independent reader of that same field (KeepADBUsbHandover's "did the
    // user just turn this off?" guard) -- two independent consumers of a one-shot token is a
    // bug: whichever reads first "uses it up" for the other. Confirmed on real hardware: a
    // manual off -> content-observer's consumeUserDisabled() (unrelated Keep-Alive decision,
    // resets userDisabled as a side effect) -> a later genuine USB reconnect wrongly saw
    // isUserDisabled()==false and re-enabled WLAN-ADB despite the explicit manual off.
    // lastDesiredOn fixes this by never being consumed -- only ever overwritten by the next
    // requestToggle() call (or forced via forceLastDesiredOn) -- so a read here can't starve
    // any other reader.
    //
    // A recovery pulse (beginPulse()/recordAppliedTime()) deliberately does NOT update this
    // field: it only ever runs when userDisabled is false, i.e. the last explicit intent was
    // already "on", so the pulse is a same-state bounce (on -> brief off -> on) rather than a
    // new intent and can never need to flip this. The content observer's own recovery re-enable
    // (source="content_observer") DOES go through KeepADB.setEnabled(ctx, true, ...) like any
    // other caller and is treated as a legitimate "intent is now on" update: Keep-Alive's whole
    // purpose is to restore the on-state, so an automatic recovery re-enable is as much an
    // intent as a manual tap.
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

        /** True when the caller should write straight away instead of scheduling. */
        boolean isImmediate() {
            return delayMs <= 0;
        }
    }

    /**
     * Records a new explicit on/off intent and computes the debounce delay, if any, against the
     * monotonic clock reading of the last applied write. Always issues a fresh intent token,
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

    /**
     * Records the monotonic clock reading of a write without touching the user-intent flags --
     * used by the recovery pulse, which is a same-state bounce rather than a new intent.
     */
    synchronized void recordAppliedTime(long nowElapsedMs) {
        lastAppliedChangeMs = nowElapsedMs;
    }

    /**
     * Issues a fresh intent token for a recovery pulse, unconditionally. Callers must evaluate
     * the guards ({@link #isUserDisabled()}, {@link #wasLastExplicitIntentOff()}, plus the
     * observed-state check that needs a Context) themselves first -- this class deliberately
     * knows nothing about Settings.Global.
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

    /** Resets every field to its process-start default; used by {@code KeepADB.resetForTesting}. */
    synchronized void reset() {
        userDisabled = false;
        lastDesiredOn = true;
        lastAppliedChangeMs = 0;
        currentIntentToken = 0;
    }
}
