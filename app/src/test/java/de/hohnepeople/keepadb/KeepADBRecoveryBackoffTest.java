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
    public void theFirstMismatchBlocksImmediatelyForTheFallbackInterval() {
        backoff.recordMismatch(100_000);

        assertEquals(1, backoff.attemptsInCycleForTesting());
        assertTrue("a single mismatch must already block further attempts",
                backoff.isBlocked(100_000));
        assertTrue(backoff.isBlocked(
                100_000 + KeepADBRecoveryBackoff.FALLBACK_RETRY_INTERVAL_MS - 1));
    }

    @Test
    public void theBlockExpiresExactlyAtTheFallbackIntervalBoundary() {
        backoff.recordMismatch(100_000);

        assertFalse("the fallback interval must open a fresh attempt once it has fully elapsed",
                backoff.isBlocked(100_000 + KeepADBRecoveryBackoff.FALLBACK_RETRY_INTERVAL_MS));
    }

    @Test
    public void recordSuccessEndsTheCycle() {
        backoff.recordMismatch(100_000);
        assertTrue(backoff.isBlocked(100_000));

        backoff.recordSuccess();

        assertFalse("a successful readback must end the cycle immediately",
                backoff.isBlocked(100_000));
        assertEquals(0, backoff.attemptsInCycleForTesting());
    }

    @Test
    public void resetReopensTheCycleAheadOfTheFallbackTimer() {
        backoff.recordMismatch(100_000);
        assertTrue(backoff.isBlocked(100_001));

        // An explicit trigger (network change, manual action, restart, observed success)
        // reopens the cycle immediately -- it must not have to wait for the fallback timer.
        backoff.reset();

        assertFalse(backoff.isBlocked(100_001));
        assertEquals(0, backoff.attemptsInCycleForTesting());
        assertEquals(0, backoff.blockedUntilElapsedMsForTesting());
    }

    @Test
    public void aFreshCycleAfterResetCanBlockAgainOnItsOwnMismatch() {
        backoff.recordMismatch(100_000);
        backoff.reset();

        backoff.recordMismatch(200_000);

        assertTrue("the reopened cycle must be able to block again on its own mismatch",
                backoff.isBlocked(200_000));
        assertFalse(backoff.isBlocked(200_000 + KeepADBRecoveryBackoff.FALLBACK_RETRY_INTERVAL_MS));
    }

    @Test
    public void aSecondMismatchWhileAlreadyBlockedExtendsTheBlockFromNow() {
        backoff.recordMismatch(100_000);
        assertEquals(100_000 + KeepADBRecoveryBackoff.FALLBACK_RETRY_INTERVAL_MS,
                backoff.blockedUntilElapsedMsForTesting());

        // The fallback timer fired (an automatic retry was attempted again) and mismatched once
        // more: the block must be re-anchored to the new attempt, not left at the stale value.
        long retryAt = 100_000 + KeepADBRecoveryBackoff.FALLBACK_RETRY_INTERVAL_MS;
        backoff.recordMismatch(retryAt);

        assertEquals(retryAt + KeepADBRecoveryBackoff.FALLBACK_RETRY_INTERVAL_MS,
                backoff.blockedUntilElapsedMsForTesting());
    }

    @Test
    public void maxAttemptsPerCycleIsExactlyOnePerTheIssue496Decision() {
        assertEquals("the repo owner's explicit decision on #496 is exactly one controlled "
                        + "automatic attempt per cycle before backing off",
                1, KeepADBRecoveryBackoff.MAX_ATTEMPTS_PER_CYCLE);
    }

    @Test
    public void fallbackIntervalIsFarLongerThanTheToggleCooldownItReplaces() {
        assertTrue("the fallback interval must never be mistaken for the 1500ms debounce loop "
                        + "it replaces",
                KeepADBRecoveryBackoff.FALLBACK_RETRY_INTERVAL_MS
                        > KeepADBToggleState.TOGGLE_COOLDOWN_MS * 100);
    }
}
