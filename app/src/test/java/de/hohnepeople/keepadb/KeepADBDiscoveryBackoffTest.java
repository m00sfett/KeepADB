package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Numeric behaviour of the bounded discovery retry backoff (#315).
 *
 * <p>{@link KeepADBTileDiscoveryContractTest} only asserts that {@code scheduleRetryLocked()}
 * consults this function and that the exhausted branch precedes the single {@code postDelayed()}
 * call -- both are source-level contracts. The delay function itself is a pure static, so its
 * actual values are asserted here instead of grepped: a source check for {@code delay *= 2} and
 * a {@code Math.min(...)} would still pass if the cap were computed from the wrong constant or
 * the loop off by one, which is exactly the regression that would silently restore an
 * effectively unthrottled scan frequency.
 */
public class KeepADBDiscoveryBackoffTest {

    @Test
    public void backoffDoublesFromTwoSecondsUntilItSaturates() {
        assertEquals(2_000L, KeepADBEndpointCoordinator.retryDelayMsForAttemptForTesting(0));
        assertEquals(4_000L, KeepADBEndpointCoordinator.retryDelayMsForAttemptForTesting(1));
        assertEquals(8_000L, KeepADBEndpointCoordinator.retryDelayMsForAttemptForTesting(2));
        assertEquals(16_000L, KeepADBEndpointCoordinator.retryDelayMsForAttemptForTesting(3));
        assertEquals(30_000L, KeepADBEndpointCoordinator.retryDelayMsForAttemptForTesting(4));
    }

    @Test
    public void backoffStaysCappedForEveryFurtherAttempt() {
        for (int attempt : new int[] { 5, 6, 32, 1_000, Integer.MAX_VALUE }) {
            assertEquals("attempt " + attempt + " must saturate at the cap",
                    30_000L, KeepADBEndpointCoordinator.retryDelayMsForAttemptForTesting(attempt));
        }
    }

    @Test
    public void backoffIsMonotonicAndNeverNegativeOrOverflowing() {
        long previous = KeepADBEndpointCoordinator.retryDelayMsForAttemptForTesting(0);
        for (int attempt = 1; attempt <= 128; attempt++) {
            long delay = KeepADBEndpointCoordinator.retryDelayMsForAttemptForTesting(attempt);
            assertTrue("attempt " + attempt + " must not shrink", delay >= previous);
            assertTrue("attempt " + attempt + " must stay within the cap", delay <= 30_000L);
            previous = delay;
        }
    }

    @Test
    public void negativeAttemptsFallBackToTheInitialDelay() {
        assertEquals(2_000L, KeepADBEndpointCoordinator.retryDelayMsForAttemptForTesting(-1));
        assertEquals(2_000L, KeepADBEndpointCoordinator.retryDelayMsForAttemptForTesting(Integer.MIN_VALUE));
    }

    /**
     * The whole retry budget must stay finite and short enough that an unreachable network does
     * not keep a discovery circuit (and with it the MulticastLock) alive indefinitely.
     */
    @Test
    public void theFullRetryBudgetIsBoundedInWallClockTime() {
        long total = 0;
        for (int attempt = 0; attempt < 5; attempt++) {
            total += KeepADBEndpointCoordinator.retryDelayMsForAttemptForTesting(attempt);
        }
        assertEquals(60_000L, total);
    }
}
