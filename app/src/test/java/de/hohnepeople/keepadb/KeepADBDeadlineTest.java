package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * #1000: the time rules shared by the force mode and the Wifi-ADB timer. Each rule from both sides:
 * the anomaly that must end or shorten the deadline, and the ordinary case that must not.
 */
public class KeepADBDeadlineTest {
    private static final long HOUR = 60L * 60L * 1000L;
    private static final long WALL = 1_800_000_000_000L;
    private static final long ELAPSED = 10_000L;
    private static final int BOOT = 7;

    private final KeepADBDeadline.Base base = new KeepADBDeadline.Base(HOUR, WALL, ELAPSED, BOOT);

    @Test
    public void sameBootCountsDownWithBothClocks() {
        assertEquals(HOUR - 1_000L,
                KeepADBDeadline.remainingMs(base, WALL + 1_000L, ELAPSED + 1_000L, BOOT));
        assertTrue(KeepADBDeadline.remainingMs(base, WALL + HOUR, ELAPSED + HOUR, BOOT) <= 0);
    }

    @Test
    public void wallClockSetBackCannotExtendWithinTheBoot() {
        // Wall clock one hour behind, monotonic clock says 50 minutes passed: 10 minutes left.
        long remaining = KeepADBDeadline.remainingMs(base, WALL - HOUR, ELAPSED + 50L * 60_000L, BOOT);
        assertEquals(10L * 60_000L, remaining);
        assertTrue(KeepADBDeadline.isWallClockSetBack(base, WALL - HOUR, ELAPSED + 50L * 60_000L));
        assertEquals(KeepADBDeadline.REBIND_CLOCK_SET_BACK,
                KeepADBDeadline.rebindReason(base, WALL - HOUR, ELAPSED + 50L * 60_000L, BOOT));
    }

    @Test
    public void wallClockSetForwardEndsEarly() {
        assertTrue(KeepADBDeadline.remainingMs(base, WALL + 2 * HOUR, ELAPSED + 1_000L, BOOT) <= 0);
        assertFalse(KeepADBDeadline.isWallClockSetBack(base, WALL + 2 * HOUR, ELAPSED + 1_000L));
    }

    @Test
    public void smallDriftWithinToleranceIsNotWrittenDown() {
        long drift = KeepADBDeadline.CLOCK_SET_BACK_TOLERANCE_MS;
        assertFalse(KeepADBDeadline.isWallClockSetBack(base, WALL + 60_000L - drift, ELAPSED + 60_000L));
        assertNull(KeepADBDeadline.rebindReason(base, WALL + 60_000L - drift, ELAPSED + 60_000L, BOOT));
    }

    @Test
    public void newBootMeasuresWithTheWallClockOnlyAndAsksForRebinding() {
        // After a restart the monotonic clock is small again; 20 minutes of wall time passed.
        long remaining = KeepADBDeadline.remainingMs(base, WALL + 20L * 60_000L, 5_000L, BOOT + 1);
        assertEquals(40L * 60_000L, remaining);
        assertEquals(KeepADBDeadline.REBIND_NEW_BOOT,
                KeepADBDeadline.rebindReason(base, WALL + 20L * 60_000L, 5_000L, BOOT + 1));
    }

    @Test
    public void newBootWithWallClockBeforeTheBaseFailsClosed() {
        assertTrue(KeepADBDeadline.remainingMs(base, WALL - 1L, 5_000L, BOOT + 1) <= 0);
        assertTrue(KeepADBDeadline.endedForSafety(base, WALL - 1L, 5_000L, BOOT + 1));
    }

    @Test
    public void unreadableBootCountFailsClosed() {
        assertEquals(0L, KeepADBDeadline.remainingMs(base, WALL + 1L, ELAPSED + 1L, -1));
        assertTrue(KeepADBDeadline.endedForSafety(base, WALL + 1L, ELAPSED + 1L, -1));
    }

    @Test
    public void ordinaryExpiryIsNotASafetyEnd() {
        assertFalse(KeepADBDeadline.endedForSafety(base, WALL + HOUR, ELAPSED + HOUR, BOOT));
        assertFalse(KeepADBDeadline.endedForSafety(base, WALL + 2 * HOUR, 5_000L, BOOT + 1));
    }

    @Test
    public void monotonicClockBelowTheBaseIsAnotherBootEvenWithTheSameCount() {
        assertFalse(KeepADBDeadline.isSameBoot(base, ELAPSED - 1L, BOOT));
        assertTrue(KeepADBDeadline.isSameBoot(base, ELAPSED, BOOT));
    }
}
