package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the extracted, framework-free toggle/recovery state machine (#248).
 *
 * <p>Deliberately imports nothing from {@code android.*}: no Context, no SharedPreferences stub,
 * no ContentResolver, not even the gateway/scheduler fakes. That is the point of the extraction --
 * every user-intent, generation-token and debounce transition is decided here and can be asserted
 * without a single Android side effect. The Android-facing glue around this core (settings write,
 * scheduling, persistence, surface refresh) stays covered by
 * {@link KeepADBToggleSchedulingTest} and {@link KeepADBRecoveryPulseInterruptionTest}.
 */
public class KeepADBToggleStateTest {

    private KeepADBToggleState state;

    @Before
    public void setUp() {
        state = new KeepADBToggleState();
    }

    @Test
    public void freshStateDefaultsToAnOnIntentThatWasNeverUserDisabled() {
        assertFalse(state.isUserDisabled());
        assertFalse(state.wasLastExplicitIntentOff());
    }

    @Test
    public void firstToggleOutsideTheCooldownWindowAppliesImmediately() {
        KeepADBToggleState.ToggleDecision decision = state.requestToggle(true, 100_000, true);

        assertTrue(decision.isImmediate());
        assertEquals(0, decision.delayMs);
        assertTrue(decision.token > 0);
    }

    @Test
    public void aSecondToggleInsideTheCooldownWindowIsDelayedByTheRemainder() {
        KeepADBToggleState.ToggleDecision first = state.requestToggle(true, 100_000, true);
        assertTrue(first.isImmediate());
        state.recordApplied(true, 100_000);

        KeepADBToggleState.ToggleDecision second = state.requestToggle(false, 100_500, true);

        assertFalse(second.isImmediate());
        assertEquals(KeepADBToggleState.TOGGLE_COOLDOWN_MS - 500, second.delayMs);
    }

    @Test
    public void theCooldownExpiresExactlyAtTheBoundary() {
        state.recordApplied(true, 100_000);

        assertTrue(state.requestToggle(false, 100_000 + KeepADBToggleState.TOGGLE_COOLDOWN_MS, true)
                .isImmediate());
    }

    /** #310: an undebounced (manual) request skips the cooldown even at the same instant. */
    @Test
    public void anUndebouncedRequestIsNeverDelayed() {
        state.recordApplied(true, 100_000);

        KeepADBToggleState.ToggleDecision manual = state.requestToggle(false, 100_000, false);

        assertTrue(manual.isImmediate());
        assertEquals(0, manual.delayMs);
        // Counter-probe: the identical request as a debounced one *is* delayed, so the line
        // above is about the flag, not about an already-expired cooldown.
        assertFalse(state.requestToggle(false, 100_000, true).isImmediate());
    }

    @Test
    public void manualReenableGetsOnlyTheShortTeardownGapAfterManualDisable() {
        state.recordApplied(false, 100_000);

        KeepADBToggleState.ToggleDecision decision = state.requestToggle(true, 100_000, false);

        assertFalse(decision.isImmediate());
        assertEquals(100, decision.delayMs);
        assertTrue(state.requestToggle(true, 100_000 + KeepADBToggleState.MANUAL_REENABLE_GAP_MS,
                false).isImmediate());
    }

    /** #310: dropping the delay must not drop the superseding protection along with it. */
    @Test
    public void anUndebouncedRequestStillSupersedesThePreviousToken() {
        long pending = state.requestToggle(true, 100_000, true).token;

        long manual = state.requestToggle(false, 100_000, false).token;

        assertFalse(state.isCurrentIntent(pending));
        assertTrue(state.isCurrentIntent(manual));
    }

    @Test
    public void aDecisionCarriesTheNetworkGenerationItWasPlannedUnder() {
        KeepADBToggleState.ToggleDecision planned = state.requestToggle(true, 100_000, true);
        assertTrue(state.isCurrentNetworkGeneration(planned.networkGeneration));

        assertEquals(1, state.noteNetworkChanged());

        assertFalse("an intent planned for the previous network must be stale",
                state.isCurrentNetworkGeneration(planned.networkGeneration));
        assertTrue(state.isCurrentNetworkGeneration(
                state.requestToggle(true, 100_000, true).networkGeneration));
        assertEquals(2, state.noteNetworkChanged());
    }

    @Test
    public void everyNewIntentSupersedesTheTokenOfThePreviousOne() {
        long stale = state.requestToggle(false, 0, true).token;
        assertTrue(state.isCurrentIntent(stale));

        long newer = state.requestToggle(true, 0, true).token;

        assertFalse("the delayed write of a superseded intent must be dropped",
                state.isCurrentIntent(stale));
        assertTrue(state.isCurrentIntent(newer));
    }

    @Test
    public void aRecoveryPulseTokenAlsoSupersedesAndIsItselfSupersedable() {
        long toggle = state.requestToggle(true, 0, true).token;
        long pulse = state.beginPulse();

        assertFalse(state.isCurrentIntent(toggle));
        assertTrue(state.isCurrentIntent(pulse));

        state.requestToggle(false, 0, true);
        assertFalse("a manual toggle during the pulse pause must cancel the restore stage",
                state.isCurrentIntent(pulse));
    }

    @Test
    public void aRecoveryPulseIsASameStateBounceAndNeverRewritesTheExplicitIntent() {
        state.requestToggle(true, 0, true);
        state.recordApplied(true, 0);

        state.beginPulse();
        state.recordAppliedTime(5_000);

        assertFalse("the pulse must not turn the last explicit intent into 'off'",
                state.wasLastExplicitIntentOff());
        assertFalse(state.isUserDisabled());
        // recordAppliedTime advanced the debounce clock, so a toggle 5s later is not throttled.
        assertTrue(state.requestToggle(false, 10_000, true).isImmediate());
    }

    /**
     * The #168 regression: {@code userDisabled} is a one-shot token with exactly one intended
     * consumer, while {@code lastDesiredOn} must survive an unrelated consumer's read.
     */
    @Test
    public void consumingUserDisabledDoesNotStarveTheNonConsumedLastIntentFlag() {
        state.requestToggle(false, 0, true);

        assertTrue(state.isUserDisabled());
        assertTrue(state.consumeUserDisabled());
        assertFalse("the one-shot token is spent after exactly one read", state.isUserDisabled());
        assertFalse(state.consumeUserDisabled());

        assertTrue("the explicit off intent must survive the unrelated consumer",
                state.wasLastExplicitIntentOff());
    }

    @Test
    public void recordingAnAppliedWriteUpdatesBothIntentFlags() {
        state.recordApplied(false, 42);
        assertTrue(state.isUserDisabled());
        assertTrue(state.wasLastExplicitIntentOff());

        state.recordApplied(true, 84);
        assertFalse(state.isUserDisabled());
        assertFalse(state.wasLastExplicitIntentOff());
    }

    @Test
    public void forcingTheLastIntentLetsAPersistedPreferenceWinOverInMemoryState() {
        assertFalse(state.wasLastExplicitIntentOff());

        state.forceLastDesiredOn(false);

        assertTrue(state.wasLastExplicitIntentOff());
        assertFalse("forcing the intent must not resurrect the consumed one-shot token",
                state.isUserDisabled());
    }

    @Test
    public void resetRestoresEveryFieldToItsProcessStartDefault() {
        state.requestToggle(false, 0, true);
        long token = state.beginPulse();
        state.recordAppliedTime(9_999);
        state.noteNetworkChanged();

        state.reset();

        assertFalse(state.isUserDisabled());
        assertFalse(state.wasLastExplicitIntentOff());
        assertFalse(state.isCurrentIntent(token));
        assertTrue("the network generation must be cleared too",
                state.isCurrentNetworkGeneration(0));
        // Without the clock reset, a toggle 500ms after the recorded write would still be
        // throttled; with it, the 9_999ms since the (zeroed) last write is well past the cooldown.
        assertTrue("the debounce clock must be cleared too",
                state.requestToggle(true, 10_499, true).isImmediate());
    }
}
