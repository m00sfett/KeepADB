package de.hohnepeople.keepadb;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

/**
 * Regression contract for issue #297: {@code KeepADBNotification.verifyCachedEndpointAsync()} is
 * reached both by the throttled roam trigger ({@code onCapabilitiesChanged}) and by the
 * unthrottled 60s heartbeat ({@code KeepADBService.heartbeatNow()}). Persisting an
 * {@code endpoint_verified/reachable} diagnostics event on every single confirmation floods the
 * bounded {@code KeepADBDiagnostics.MAX_EVENTS} (128) ring buffer with routine confirmations and
 * pushes out rarer events.
 *
 * <p>The fix gates that persisted event behind {@code KeepADBNotification.shouldLogReachable()}:
 * it returns {@code true} only the first time a confirmed-reachable state is observed, and
 * {@code false} for repeated confirmations, until the state is reset by
 * {@code resetReachableConfirmed()} (called wherever the cached endpoint changes, is invalidated
 * as stale, or is torn down). This test drives that pure state machine directly -- without
 * spinning up the background verification thread or a real socket, which the existing
 * {@link KeepADBNotificationRobolectricTest} javadoc already documents as out of scope for
 * Robolectric here.
 */
public class KeepADBReachableEventThrottleTest {

    @After
    public void resetSharedState() {
        KeepADBNotification.resetReachableConfirmed();
    }

    @Test
    public void repeatedReachableConfirmationsWithoutAStateChangeAreNotLoggedRepeatedly() {
        assertTrue("the first confirmation after a (re)set must be logged",
                KeepADBNotification.shouldLogReachable());

        // Simulate many more routine heartbeat/roam-trigger confirmations of the same, still
        // reachable endpoint: none of them may report a fresh state change.
        for (int i = 0; i < 50; i++) {
            assertFalse("a routine reachable confirmation with no state change must not be "
                            + "logged again (event #" + i + ")",
                    KeepADBNotification.shouldLogReachable());
        }
    }

    @Test
    public void aStateChangeAfterAResetIsLoggedAgain() {
        assertTrue(KeepADBNotification.shouldLogReachable());
        assertFalse(KeepADBNotification.shouldLogReachable());

        // A real state change (stale_invalidated, a fresh discovery after a roam, or a teardown)
        // resets the flag in production code (see resetReachableConfirmed() call sites in
        // KeepADBNotification); the next confirmation must be visible again.
        KeepADBNotification.resetReachableConfirmed();

        assertTrue("a confirmation after a genuine state change must be logged",
                KeepADBNotification.shouldLogReachable());
        assertFalse("but the next routine repeat must again be suppressed",
                KeepADBNotification.shouldLogReachable());
    }
}
