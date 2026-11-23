package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

/**
 * #1049: the components split out of {@link KeepADBRegisterClient}, tested directly instead of
 * through the client's test hooks: the endpoint-loss DELETE gate, the POST retry series and the
 * typed transport result.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class RegisterComponentsTest {

    // ---- UnavailableDeleteBackoff ----

    @Test
    public void theFirstDeleteIsImmediateAndEachFailureWaitsItsStep() {
        UnavailableDeleteBackoff gate = new UnavailableDeleteBackoff();
        gate.setNowForTesting(1_000L);
        assertFalse("the first attempt is never gated", gate.isWaiting());

        gate.recordFailure();
        assertEquals(1, gate.attempts());
        assertEquals(6_000L, gate.nextAttemptAt());
        assertTrue(gate.isWaiting());
        gate.setNowForTesting(6_000L);
        assertFalse("due exactly at the step", gate.isWaiting());

        gate.reset();
        assertEquals(0, gate.attempts());
        assertEquals(0L, gate.nextAttemptAt());
    }

    @Test
    public void theBackoffFollowsTheApprovedStaffingAndThenItsCeiling() {
        long[] expected = {0L, 5_000L, 10_000L, 15_000L, 30_000L, 60_000L, 180_000L, 300_000L,
                300_000L};
        for (int failures = 0; failures < expected.length; failures++) {
            assertEquals("after " + failures + " failures", expected[failures],
                    UnavailableDeleteBackoff.backoffMs(failures));
        }
    }

    @Test
    public void theNextAttemptSaturatesInsteadOfOverflowing() {
        UnavailableDeleteBackoff gate = new UnavailableDeleteBackoff();
        gate.setNowForTesting(Long.MAX_VALUE - 1L);
        gate.recordFailure();
        assertEquals(Long.MAX_VALUE, gate.nextAttemptAt());
    }

    @Test
    public void coalescingOnlyHoldsForTheCurrentOperationAndOnlyItsOwnFinishReleasesIt() {
        UnavailableDeleteBackoff gate = new UnavailableDeleteBackoff();
        assertFalse("nothing in flight", gate.coalesces(0L));

        gate.dispatched(7L);
        assertTrue(gate.coalesces(7L));
        assertFalse("a newer operation is never swallowed", gate.coalesces(8L));

        gate.dispatched(9L);
        gate.finished(7L);
        assertTrue("an older DELETE must not release the newer one's gate", gate.coalesces(9L));
        gate.finished(9L);
        assertFalse(gate.coalesces(9L));
    }

    // ---- RegisterRetrySeries ----

    private static Handler handler() {
        return new Handler(Looper.getMainLooper());
    }

    @Test
    public void aSeriesIsJoinedOnlyForTheSamePairWhileItIsBusyOrGivenUp() {
        RegisterRetrySeries series = new RegisterRetrySeries();
        Handler handler = handler();
        series.start(handler, "http://a/r", "1.2.3.4:5");

        assertTrue("running", series.joins("http://a/r", "1.2.3.4:5", true));
        assertFalse("idle and not given up", series.joins("http://a/r", "1.2.3.4:5", false));
        assertFalse("another endpoint", series.joins("http://a/r", "1.2.3.4:6", true));
        assertFalse("another URL", series.joins("http://b/r", "1.2.3.4:5", true));

        series.giveUp();
        assertTrue("given up", series.joins("http://a/r", "1.2.3.4:5", false));
    }

    @Test
    public void retriesRunUpToTheBudgetThenTheSeriesIsGivenUp() {
        RegisterRetrySeries series = new RegisterRetrySeries();
        Handler handler = handler();
        series.setRetryBudgetForTesting(2);
        series.start(handler, "http://a/r", "1.2.3.4:5");
        AtomicInteger runs = new AtomicInteger();

        for (int i = 1; i <= 2; i++) {
            Runnable retry = runs::incrementAndGet;
            assertTrue(series.scheduleRetry(handler, retry, 0L));
            assertSame(retry, series.pendingRetry());
            assertEquals(i, series.retryNumber());
            ShadowLooper.idleMainLooper(WebhookReportPrefs.webhookRetryDelayMs(i),
                    java.util.concurrent.TimeUnit.MILLISECONDS);
            assertTrue(series.takeRetry(retry));
        }
        assertEquals(2, runs.get());

        assertFalse("the budget is spent", series.scheduleRetry(handler, runs::incrementAndGet, 42L));
        assertTrue(series.isExhausted());
        assertNull(series.pendingRetry());
    }

    @Test
    public void cancellingDropsTheScheduledRetrySoItNeverRuns() {
        RegisterRetrySeries series = new RegisterRetrySeries();
        Handler handler = handler();
        series.start(handler, "http://a/r", "1.2.3.4:5");
        AtomicInteger runs = new AtomicInteger();
        Runnable retry = runs::incrementAndGet;
        series.scheduleRetry(handler, retry, 0L);

        series.cancel(handler);
        ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.HOURS);

        assertEquals(0, runs.get());
        assertFalse("a cancelled retry cannot be claimed", series.takeRetry(retry));
        assertEquals(0, series.retryNumber());
        assertFalse(series.isExhausted());
    }

    @Test
    public void aReattemptIsOneRequestAndWaitsItsIntervalAfterTheLastGiveUp() {
        RegisterRetrySeries series = new RegisterRetrySeries();
        Handler handler = handler();
        long interval = RegisterRetrySeries.EPISODE_REATTEMPT_INTERVAL_MS;
        assertTrue("no series at all counts as given up (#884)", series.reattemptDue(interval));

        series.start(handler, "http://a/r", "1.2.3.4:5");
        assertFalse("a live series is not re-attempted", series.reattemptDue(interval));

        series.markReattempt(interval);
        assertFalse("a re-attempt never retries",
                series.scheduleRetry(handler, () -> { }, interval + 1L));
        assertTrue(series.isExhausted());
        assertFalse(series.reattemptDue(2L * interval));
        assertTrue(series.reattemptDue(2L * interval + 1L));

        series.replaceResync(handler, () -> { }, 1_000L);
        assertFalse("a waiting resync comes first", series.reattemptDue(10L * interval));
    }

    @Test
    public void aReplacedResyncCannotClaimTheSlot() {
        RegisterRetrySeries series = new RegisterRetrySeries();
        Handler handler = handler();
        Runnable first = () -> { };
        Runnable second = () -> { };
        series.replaceResync(handler, first, 1_000L);
        series.replaceResync(handler, second, 1_000L);

        assertFalse(series.takeResync(first));
        assertTrue(series.takeResync(second));
        assertFalse(series.hasPendingResync());
    }

    // ---- TransportResult ----

    @Test
    public void onlyA2xxAnswerIsSuccessAndOnlyAnAnswerProvesNothingWasRegistered() {
        assertTrue(TransportResult.http(200).ok);
        assertTrue(TransportResult.http(299).ok);
        assertFalse(TransportResult.http(199).ok);
        assertFalse(TransportResult.http(300).ok);
        assertFalse(TransportResult.http(503).ok);
        assertEquals("http=503", TransportResult.http(503).outcome);
        assertTrue(TransportResult.http(503).isHttpAnswer());

        TransportResult error = TransportResult.error(new java.net.SocketTimeoutException("10.0.0.1"));
        assertFalse(error.ok);
        assertFalse(error.isHttpAnswer());
        assertEquals("the class only, never the message", "error=SocketTimeoutException",
                error.outcome);
    }
}
