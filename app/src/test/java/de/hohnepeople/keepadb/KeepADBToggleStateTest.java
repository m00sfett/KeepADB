package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

/**
 * Plain-JUnit tests for KeepADB's platform-independent toggle/recovery state core (issue #248).
 * Deliberately imports nothing from android.* or Robolectric -- every assertion here exercises
 * pure state transitions with no Android framework side effects.
 */
public class KeepADBToggleStateTest {

    private KeepADBToggleState state;

    @Before
    public void setUp() {
        state = new KeepADBToggleState();
    }

    @Test
    public void defaultsToNotUserDisabledAndLastIntentOn() {
        assertFalse(state.isUserDisabled());
        assertFalse(state.wasLastExplicitIntentOff());
    }

    @Test
    public void requestToggleAppliesImmediatelyWhenCooldownElapsed() {
        KeepADBToggleState.ToggleDecision decision = state.requestToggle(true, 10_000);
        assertTrue(decision.isImmediate());
        assertEquals(0, decision.delayMs);
    }

    @Test
    public void requestToggleDebouncesWithinCooldownWindow() {
        state.requestToggle(true, 0);
        state.recordApplied(true, 0);

        KeepADBToggleState.ToggleDecision decision = state.requestToggle(false, 500);
        assertFalse(decision.isImmediate());
        assertEquals(KeepADBToggleState.TOGGLE_COOLDOWN_MS - 500, decision.delayMs);
    }

    @Test
    public void requestToggleSetsUserDisabledAndLastIntentFlags() {
        state.requestToggle(false, 0);
        assertTrue(state.isUserDisabled());
        assertTrue(state.wasLastExplicitIntentOff());

        state.requestToggle(true, 10_000);
        assertFalse(state.isUserDisabled());
        assertFalse(state.wasLastExplicitIntentOff());
    }

    @Test
    public void eachRequestSupersedesThePreviousIntentToken() {
        KeepADBToggleState.ToggleDecision first = state.requestToggle(true, 0);
        KeepADBToggleState.ToggleDecision second = state.requestToggle(false, 0);

        assertFalse(state.isCurrentIntent(first.token));
        assertTrue(state.isCurrentIntent(second.token));
    }

    @Test
    public void consumeUserDisabledIsOneShot() {
        state.requestToggle(false, 0);
        assertTrue(state.consumeUserDisabled());
        assertFalse(state.isUserDisabled());
        assertFalse(state.consumeUserDisabled());
    }

    @Test
    public void wasLastExplicitIntentOffIsNotConsumedByConsumeUserDisabled() {
        state.requestToggle(false, 0);
        state.consumeUserDisabled();
        // #168: unlike userDisabled, this flag must survive an unrelated consumeUserDisabled() read.
        assertTrue(state.wasLastExplicitIntentOff());
    }

    @Test
    public void forceLastDesiredOnOverridesInMemoryFlagOnly() {
        state.requestToggle(true, 0);
        state.forceLastDesiredOn(false);
        assertTrue(state.wasLastExplicitIntentOff());
        assertFalse(state.isUserDisabled());
    }

    @Test
    public void beginPulseIssuesANewTokenWithoutTouchingIntentFlags() {
        state.requestToggle(true, 0);
        long pulseToken = state.beginPulse();

        assertTrue(state.isCurrentIntent(pulseToken));
        assertFalse(state.isUserDisabled());
        assertFalse(state.wasLastExplicitIntentOff());
    }

    @Test
    public void recordAppliedTimeDoesNotChangeIntentFlags() {
        state.requestToggle(false, 0);
        state.consumeUserDisabled();
        state.recordAppliedTime(5_000);

        assertFalse(state.isUserDisabled());
        assertTrue(state.wasLastExplicitIntentOff());
    }

    @Test
    public void resetRestoresDefaults() {
        state.requestToggle(false, 100);
        state.reset();

        assertFalse(state.isUserDisabled());
        assertFalse(state.wasLastExplicitIntentOff());
        // lastAppliedChangeMs is reset to 0, so a "now" comfortably past the cooldown window
        // (measured from that reset origin) must apply immediately again.
        KeepADBToggleState.ToggleDecision decision = state.requestToggle(true, 10_000);
        assertTrue(decision.isImmediate());
    }
}
