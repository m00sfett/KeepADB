package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.content.ContextWrapper;

import org.junit.Test;

/**
 * Regression test for #359: an exception thrown between {@code multicastLock.acquire()} and the
 * overall-timeout post in {@code discover()} must not leave the lock held forever.
 *
 * <p>Injects the failure at the last unguarded call inside that window -- {@code
 * scheduler.postDelayed()} for the recovery-pulse runnable, reached only when {@code
 * allowRecoveryPulse=true} and only immediately before the overall-timeout post that would
 * otherwise arm cleanup -- via the existing {@link KeepADBScheduler} test seam (#249). A real
 * {@code Handler.postDelayed()} can throw {@code IllegalStateException} if its looper thread has
 * died, so this isn't a contrived failure point.
 *
 * <p>The effect is observed on a {@link KeepADBMulticastLock} fake (#359), not a real {@code
 * WifiManager.MulticastLock}: the latter's acquire()/release()/isHeld() bodies are stripped
 * no-ops under this project's Robolectric-free, JVM-only unit test setup (AGP's mockable
 * android.jar), the same limitation documented in {@link KeepADBEndpointDiscoveryTest}'s javadoc.
 */
public class KeepADBEndpointMulticastLockLeakTest {

    @Test
    public void lockIsReleasedWhenDiscoverThrowsBeforeTimeoutIsArmed() {
        KeepADBFakeNsdProbe nsdProbe = new KeepADBFakeNsdProbe();
        FailOnFirstPostDelayedScheduler scheduler = new FailOnFirstPostDelayedScheduler();
        FakeMulticastLock lock = new FakeMulticastLock();
        KeepADBEndpoint endpoint = new KeepADBEndpoint(new FakeContext(), nsdProbe, scheduler, lock);

        RuntimeException thrown = null;
        try {
            // allowRecoveryPulse=true so scheduler.postDelayed() is reached (for the recovery
            // pulse runnable) before the overall-timeout post -- exactly the window #359 covers.
            endpoint.discover(new NoopListener(), true);
        } catch (RuntimeException e) {
            thrown = e;
        }

        assertNotNull("the injected failure must propagate, not be silently swallowed", thrown);
        assertEquals("simulated failure between acquire() and timeout post", thrown.getMessage());
        assertEquals("acquire() must have been called exactly once", 1, lock.acquireCount);
        assertEquals("release() must have been called to clean up after the failure",
                1, lock.releaseCount);
        assertFalse("the lock must not be left held after the failure", lock.isHeld());
    }

    private static final class FailOnFirstPostDelayedScheduler extends KeepADBFakeScheduler {
        private boolean failed;

        @Override
        public void postDelayed(Runnable runnable, long delayMs) {
            if (!failed) {
                failed = true;
                throw new RuntimeException("simulated failure between acquire() and timeout post");
            }
            super.postDelayed(runnable, delayMs);
        }
    }

    private static final class FakeMulticastLock implements KeepADBMulticastLock {
        int acquireCount;
        int releaseCount;
        private boolean held;

        @Override
        public void acquire() {
            acquireCount++;
            held = true;
        }

        @Override
        public void release() {
            releaseCount++;
            held = false;
        }

        @Override
        public boolean isHeld() {
            return held;
        }
    }

    private static final class NoopListener implements KeepADBEndpoint.Listener {
        @Override
        public void onEndpoint(String host, int port) {
        }

        @Override
        public void onUnavailable() {
        }
    }

    private static final class FakeContext extends ContextWrapper {
        FakeContext() {
            super(null);
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }
    }
}
