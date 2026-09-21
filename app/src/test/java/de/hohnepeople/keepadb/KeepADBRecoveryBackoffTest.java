package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

/**
 * Framework-free tests for #496's automatic-enable backoff, mirroring {@link
 * KeepADBToggleStateTest}'s style: every transition of the policy ("exactly one controlled
 * automatic attempt per unchanged-state cycle, then wait for an explicit trigger or the fallback
 * interval") is exercised here with plain JUnit and no Android side effects at all. The
 * Android-facing wiring (call-site gating, applyNow() bookkeeping, the reset triggers) is covered
 * separately by {@link KeepADBRecoveryBackoffSchedulingTest} and
 * {@link KeepADBServiceLifecycleRobolectricTest}.
 */
public class KeepADBRecoveryBackoffTest {

    private KeepADBRecoveryBackoff backoff;

    @Before
    public void setUp() {
        backoff = new KeepADBRecoveryBackoff();
    }

    @Test
    public void freshInstanceIsNeverBlocked() {
        assertFalse(backoff.isBlocked(0));
        assertFalse(backoff.isBlocked(Long.MAX_VALUE));
        assertEquals(0, backoff.attemptsInCycleForTesting());
    }

    @Test
    public void theFirstAttemptBlocksImmediatelyForTheFirstRetryDelay() {
        backoff.recordAttempt(100_000);

        assertEquals(1, backoff.attemptsInCycleForTesting());
        assertTrue("a single mismatch must already block further attempts",
                backoff.isBlocked(100_000));
        assertTrue(backoff.isBlocked(
                100_000 + KeepADBRecoveryBackoff.FIRST_RETRY_DELAY_MS - 1));
    }

    @Test
    public void theBlockExpiresExactlyAtTheFirstRetryDelayBoundary() {
        backoff.recordAttempt(100_000);

        assertFalse("the first retry delay must open a fresh attempt once it has fully elapsed",
                backoff.isBlocked(100_000 + KeepADBRecoveryBackoff.FIRST_RETRY_DELAY_MS));
    }

    /**
     * #536 acceptance criterion: "den nächsten Versuch nach ungefähr 2 Minuten einplanen; weitere
     * Versuche höchstens im Abstand von 5 Minuten." The first retry after an unconfirmed attempt
     * is the short (~2 minute) delay; every attempt after that re-anchors at the longer, capped
     * 5-minute interval instead.
     */
    @Test
    public void aSecondAttemptBlocksForTheCappedRetryIntervalNotTheFirstDelay() {
        backoff.recordAttempt(100_000);
        long secondAttemptAt = 100_000 + KeepADBRecoveryBackoff.FIRST_RETRY_DELAY_MS;

        backoff.recordAttempt(secondAttemptAt);

        assertEquals(secondAttemptAt + KeepADBRecoveryBackoff.RETRY_INTERVAL_MS,
                backoff.blockedUntilElapsedMsForTesting());
        assertTrue(backoff.isBlocked(
                secondAttemptAt + KeepADBRecoveryBackoff.RETRY_INTERVAL_MS - 1));
        assertFalse(backoff.isBlocked(
                secondAttemptAt + KeepADBRecoveryBackoff.RETRY_INTERVAL_MS));
    }

    /** The cadence caps at {@link KeepADBRecoveryBackoff#RETRY_INTERVAL_MS}; it never grows past it. */
    @Test
    public void aThirdConsecutiveAttemptStaysAtTheSameCappedRetryInterval() {
        backoff.recordAttempt(100_000);
        long secondAttemptAt = 100_000 + KeepADBRecoveryBackoff.FIRST_RETRY_DELAY_MS;
        backoff.recordAttempt(secondAttemptAt);
        long thirdAttemptAt = secondAttemptAt + KeepADBRecoveryBackoff.RETRY_INTERVAL_MS;

        backoff.recordAttempt(thirdAttemptAt);

        assertEquals("the interval must not keep growing past the 5-minute cap",
                thirdAttemptAt + KeepADBRecoveryBackoff.RETRY_INTERVAL_MS,
                backoff.blockedUntilElapsedMsForTesting());
    }

    @Test
    public void confirmSuccessEndsTheCycle() {
        backoff.recordAttempt(100_000);
        assertTrue(backoff.isBlocked(100_000));

        backoff.confirmSuccess();

        assertFalse("a successful readback must end the cycle immediately",
                backoff.isBlocked(100_000));
        assertEquals(0, backoff.attemptsInCycleForTesting());
    }

    @Test
    public void resetReopensTheCycleAheadOfTheFallbackTimer() {
        backoff.recordAttempt(100_000);
        assertTrue(backoff.isBlocked(100_001));

        // An explicit trigger (network change, manual action, restart, observed success)
        // reopens the cycle immediately -- it must not have to wait for the fallback timer.
        backoff.reset();

        assertFalse(backoff.isBlocked(100_001));
        assertEquals(0, backoff.attemptsInCycleForTesting());
        assertEquals(0, backoff.blockedUntilElapsedMsForTesting());
    }

    @Test
    public void aFreshCycleAfterResetCanBlockAgainOnItsOwnAttempt() {
        backoff.recordAttempt(100_000);
        backoff.reset();

        backoff.recordAttempt(200_000);

        assertTrue("the reopened cycle must be able to block again on its own mismatch",
                backoff.isBlocked(200_000));
        assertFalse(backoff.isBlocked(200_000 + KeepADBRecoveryBackoff.FIRST_RETRY_DELAY_MS));
    }

    /**
     * #500's core regression: a momentary "on" observed while our own attempt is still awaiting
     * its verdict is exactly what an accepted-but-reverted write produces, and must not reopen
     * the cycle. Before the fix this reset ran on every such transition, which is why the device
     * test never saw a single blocked attempt.
     */
    @Test
    public void anObservedEnabledWhileAwaitingConfirmationDoesNotReopenTheCycle() {
        backoff.recordAttempt(100_000);
        assertTrue(backoff.isAwaitingConfirmation());

        backoff.noteObservedEnabled();

        assertTrue("the transient 'on' of our own reverted write must not clear the block",
                backoff.isBlocked(100_001));
        assertEquals(1, backoff.attemptsInCycleForTesting());
    }

    @Test
    public void anObservedEnabledOutsideAnAttemptReopensTheCycle() {
        backoff.recordAttempt(100_000);
        backoff.recordUnconfirmed();

        // Later: the user confirms Android's pairing dialog, so the value goes on by itself.
        backoff.noteObservedEnabled();

        assertFalse(backoff.isBlocked(100_001));
        assertEquals(0, backoff.attemptsInCycleForTesting());
    }

    @Test
    public void anUnconfirmedAttemptStaysBlockedUntilTheFirstRetryDelay() {
        backoff.recordAttempt(100_000);

        backoff.recordUnconfirmed();

        assertFalse("the verdict is in; nothing is awaiting confirmation any more",
                backoff.isAwaitingConfirmation());
        assertTrue("a reverted attempt must keep the block", backoff.isBlocked(100_001));
        assertFalse(backoff.isBlocked(
                100_000 + KeepADBRecoveryBackoff.FIRST_RETRY_DELAY_MS));
    }

    @Test
    public void resetClearsAPendingConfirmationToo() {
        backoff.recordAttempt(100_000);

        backoff.reset();

        assertFalse(backoff.isAwaitingConfirmation());
        assertFalse(backoff.isBlocked(100_001));
    }

    @Test
    public void confirmationWindowIsShorterThanTheDebounceLoopButLongerThanOneReadback() {
        assertTrue("the confirmation window must outlast the OS revert that follows an accepted "
                        + "write, i.e. at least a full debounce cycle",
                KeepADBRecoveryBackoff.SUCCESS_CONFIRMATION_MS > KeepADBToggleState.TOGGLE_COOLDOWN_MS);
        assertTrue("and must stay far below even the shorter of the two retry delays it is "
                        + "nested in",
                KeepADBRecoveryBackoff.SUCCESS_CONFIRMATION_MS
                        < KeepADBRecoveryBackoff.FIRST_RETRY_DELAY_MS);
    }

    @Test
    public void maxAttemptsPerCycleIsExactlyOnePerTheIssue496Decision() {
        assertEquals("the repo owner's explicit decision on #496 is exactly one controlled "
                        + "automatic attempt per retry window before backing off again",
                1, KeepADBRecoveryBackoff.MAX_ATTEMPTS_PER_CYCLE);
    }

    /**
     * #536 acceptance criterion: "den nächsten Versuch nach ungefähr 2 Minuten einplanen; weitere
     * Versuche höchstens im Abstand von 5 Minuten."
     */
    @Test
    public void retryDelaysMatchTheIssue536AcceptanceCriterionAndAreFarLongerThanTheToggleCooldown() {
        assertEquals(2 * 60 * 1000L, KeepADBRecoveryBackoff.FIRST_RETRY_DELAY_MS);
        assertEquals(5 * 60 * 1000L, KeepADBRecoveryBackoff.RETRY_INTERVAL_MS);
        assertTrue("the capped interval must be longer than the first retry's delay",
                KeepADBRecoveryBackoff.RETRY_INTERVAL_MS > KeepADBRecoveryBackoff.FIRST_RETRY_DELAY_MS);
        assertTrue("even the shorter first retry delay must never be mistaken for the 1500ms "
                        + "debounce loop it replaces",
                KeepADBRecoveryBackoff.FIRST_RETRY_DELAY_MS > KeepADBToggleState.TOGGLE_COOLDOWN_MS * 50);
    }
}
