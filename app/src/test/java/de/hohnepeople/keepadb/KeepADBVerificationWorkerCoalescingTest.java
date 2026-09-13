package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Regression coverage for issue #351: {@code KeepADBNotification.verifyCachedEndpointAsync()}
 * used to start a brand-new raw {@link Thread} plus blocking socket check on every single trigger,
 * even while an earlier check for the very same cached endpoint was still in flight. The #315
 * verification token already made a superseded worker's *result* harmless, but did nothing to
 * bound how many concurrent socket checks could pile up from repeated heartbeat/roam triggers.
 *
 * <p>These tests drive {@link KeepADBNotification#verifyEndpointHealth(Context)} against the new
 * {@link KeepADBNotification.ReachabilityProbe} test seam (#351), which lets a fake reachability
 * check block on a {@link CountDownLatch} instead of opening a real, potentially hanging socket
 * (see the scope note in {@link KeepADBNotificationRobolectricTest#wifiNetworkCallbackIsRegisteredAgainstARealConnectivityManager()}
 * on why the real socket path stays out of scope for Robolectric).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBVerificationWorkerCoalescingTest {

    private final Context context = org.robolectric.RuntimeEnvironment.getApplication();

    @org.junit.Before
    public void grantNotificationPermission() {
        shadowOf((Application) context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
        // A verification worker bails out via stop() (clearing the cached endpoint) unless
        // KeepADB.isEnabled() is true, which the real Settings.Global-backed gateway defaults to
        // false for in Robolectric -- fake it enabled so the worker reaches its reachable/stale
        // branches instead.
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(true));
    }

    @After
    public void resetSharedState() throws Exception {
        KeepADBNotification.resetForTesting();
        KeepADB.resetForTesting();
        setStatic("currentHost", null);
        setStatic("currentPort", 0);
    }

    @Test
    public void repeatedTriggersWhileASlowCheckIsInFlightDoNotStartAnotherWorker() throws Exception {
        setStatic("currentHost", "192.0.2.1");
        setStatic("currentPort", 40000);

        CountDownLatch releaseProbe = new CountDownLatch(1);
        CountDownLatch probeStarted = new CountDownLatch(1);
        AtomicInteger probeInvocations = new AtomicInteger();
        KeepADBNotification.setReachabilityProbeForTesting((host, port, timeoutMs) -> {
            probeInvocations.incrementAndGet();
            probeStarted.countDown();
            try {
                releaseProbe.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return true;
        });

        // First trigger (e.g. the 60s heartbeat) starts the worker; it blocks on releaseProbe.
        KeepADBNotification.verifyEndpointHealth(context);
        assertTrue("the first trigger's worker must have actually started its reachability check",
                probeStarted.await(5, TimeUnit.SECONDS));

        // Further triggers arriving while that worker is still in flight (e.g. a roam callback
        // firing moments later) must be coalesced into it, not spawn their own worker.
        for (int i = 0; i < 5; i++) {
            KeepADBNotification.verifyEndpointHealth(context);
        }

        assertEquals("repeated triggers for the same in-flight check must not start additional "
                        + "worker threads", 1, KeepADBNotification.getVerificationWorkerStartCountForTesting());
        assertEquals("the coalesced probe must only ever have been invoked once", 1, probeInvocations.get());

        releaseProbe.countDown();
        awaitVerificationIdle();

        // Once the in-flight worker has finished, a fresh trigger must start a new one again --
        // coalescing must not permanently wedge verification shut.
        KeepADBNotification.setReachabilityProbeForTesting((host, port, timeoutMs) -> true);
        KeepADBNotification.verifyEndpointHealth(context);
        awaitVerificationIdle();
        assertEquals("a normal, sequential verification after the in-flight worker completed must "
                        + "start its own worker", 2, KeepADBNotification.getVerificationWorkerStartCountForTesting());
    }

    @Test
    public void aSupersededInFlightCheckDoesNotMutateStateAfterANewerInvalidation() throws Exception {
        setStatic("currentHost", "192.0.2.1");
        setStatic("currentPort", 40000);

        CountDownLatch releaseProbe = new CountDownLatch(1);
        CountDownLatch probeStarted = new CountDownLatch(1);
        // The in-flight worker will report the *old* endpoint as no longer reachable, which would
        // normally invalidate the cache and kick off rediscovery -- but by the time it wakes up,
        // a newer event (invalidateEndpoint(), simulating e.g. Wi-Fi loss) has already superseded
        // it. It must not be allowed to clobber that newer state.
        KeepADBNotification.setReachabilityProbeForTesting((host, port, timeoutMs) -> {
            probeStarted.countDown();
            try {
                releaseProbe.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return false;
        });

        KeepADBNotification.verifyEndpointHealth(context);
        assertTrue(probeStarted.await(5, TimeUnit.SECONDS));

        // A newer event supersedes the in-flight worker before it wakes up.
        KeepADBNotification.invalidateEndpoint(context);
        assertNull(KeepADBNotification.getCurrentHost());

        releaseProbe.countDown();
        awaitVerificationIdle();

        assertNull("a superseded worker must not resurrect a cached endpoint that a newer event "
                + "already cleared", KeepADBNotification.getCurrentHost());
        assertFalse("a superseded worker must release its in-flight flag once it returns",
                KeepADBNotification.isVerificationInFlightForTesting());
    }

    /**
     * Review repair on #351: the in-flight flag is raised before the worker exists and cleared
     * only inside the worker body, so a worker that could never be started (the platform refusing
     * another thread -- the very failure #359 guards its own coordinator thread against) used to
     * leave the flag raised forever, silently disabling endpoint verification for the rest of the
     * process. A refused start must release the flag and leave the next trigger able to try again.
     */
    @Test
    public void aWorkerThatCannotBeStartedDoesNotWedgeVerificationShut() throws Exception {
        setStatic("currentHost", "192.0.2.1");
        setStatic("currentPort", 40000);
        AtomicInteger probeInvocations = new AtomicInteger();
        KeepADBNotification.setReachabilityProbeForTesting((host, port, timeoutMs) -> {
            probeInvocations.incrementAndGet();
            return true;
        });
        KeepADBNotification.setWorkerStarterForTesting(worker -> {
            throw new OutOfMemoryError("simulated thread limit exhaustion");
        });

        KeepADBNotification.verifyEndpointHealth(context);

        assertFalse("a worker that never ran must not leave the in-flight flag raised",
                KeepADBNotification.isVerificationInFlightForTesting());
        assertEquals("the refused worker never ran its reachability check", 0, probeInvocations.get());

        // The next trigger must be able to start a worker again instead of being coalesced into
        // a worker that does not exist.
        KeepADBNotification.setWorkerStarterForTesting(null);
        KeepADBNotification.verifyEndpointHealth(context);
        awaitVerificationIdle();
        assertEquals("verification must recover on the next trigger", 1, probeInvocations.get());
    }

    private static void awaitVerificationIdle() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (KeepADBNotification.isVerificationInFlightForTesting()) {
            if (System.currentTimeMillis() > deadline) {
                fail("verification worker did not finish within the timeout");
            }
            Thread.sleep(10);
        }
    }

    private static void setStatic(String fieldName, Object value) throws Exception {
        Field field = KeepADBNotification.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, value);
    }
}
