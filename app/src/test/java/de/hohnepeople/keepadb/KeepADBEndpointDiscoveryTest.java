package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import org.junit.Test;

/**
 * Deterministic tests for {@link KeepADBEndpoint}'s discovery lifecycle (#249), using the
 * {@link KeepADBFakeNsdProbe}/{@link KeepADBFakeScheduler} seams so no real mDNS responder or
 * real timing is needed. All tests use {@code allowRecoveryPulse=false} to keep them
 * independent of {@link KeepADB}'s static toggle state, which is covered separately in {@link
 * KeepADBToggleSchedulingTest} and {@link KeepADBRecoveryPulseInterruptionTest}.
 *
 * <p>Two of the issue's listed scenarios -- an mDNS endpoint that cannot be reached, and
 * endpoint invalidation during verification -- are NOT covered here. Both require a resolved
 * {@link NsdServiceInfo} whose {@code getHost()}/{@code getPort()}/{@code getServiceType()}
 * return real data, but {@code NsdServiceInfo} is a {@code final} platform class whose setters
 * are no-ops under this project's Robolectric-free, JVM-only unit test setup (AGP's mockable
 * android.jar with {@code returnDefaultValues=true} strips every method body uniformly, and a
 * final class can't be subclassed to work around it). Exercising those two paths would need
 * either Robolectric or an instrumented/emulator test, both outside this project's current
 * testing approach.
 */
public class KeepADBEndpointDiscoveryTest {

    @Test
    public void overlappingDiscoveryRequestsAttachToTheActiveProbeInsteadOfRestarting() {
        KeepADBFakeNsdProbe nsdProbe = new KeepADBFakeNsdProbe();
        KeepADBFakeScheduler scheduler = new KeepADBFakeScheduler();
        KeepADBEndpoint endpoint = new KeepADBEndpoint(new FakeContext(), nsdProbe, scheduler);
        RecordingListener first = new RecordingListener();
        RecordingListener second = new RecordingListener();

        endpoint.discover(first, false);
        assertEquals(1, nsdProbe.discoverServicesCallCount);

        // A second caller joins while discovery is already active in progress.
        endpoint.discover(second, false);
        assertEquals("an overlapping request must attach to the active probe, not restart it",
                1, nsdProbe.discoverServicesCallCount);

        // Only the latest attached listener must be notified once discovery gives up.
        scheduler.advanceBy(8_000); // OVERALL_TIMEOUT_MS
        assertFalse("the superseded first listener must not be notified", first.unavailable);
        assertTrue("the latest listener must be notified", second.unavailable);

        endpoint.stop();
    }

    @Test
    public void stopTearsDownCleanlyAndIgnoresStaleCallbacksAfterward() {
        KeepADBFakeNsdProbe nsdProbe = new KeepADBFakeNsdProbe();
        KeepADBFakeScheduler scheduler = new KeepADBFakeScheduler();
        KeepADBEndpoint endpoint = new KeepADBEndpoint(new FakeContext(), nsdProbe, scheduler);
        RecordingListener listener = new RecordingListener();

        endpoint.discover(listener, false);
        NsdManager.DiscoveryListener staleDiscoveryListener = nsdProbe.discoveryListener;
        assertNotNull(staleDiscoveryListener);

        // Simulates e.g. Wi-Fi loss: something external (KeepADBNotification.invalidateEndpoint)
        // calls stop() while discovery is still in progress.
        endpoint.stop();
        assertEquals(1, nsdProbe.stopServiceDiscoveryCallCount);
        assertFalse("stop() must cancel the pending overall-timeout/watchdog callbacks",
                scheduler.hasAnyPending());

        // A stale mDNS callback arriving after stop() (generation now mismatched) must be a
        // safe no-op, not attempt to resolve a service from an abandoned discovery session.
        staleDiscoveryListener.onServiceFound(new NsdServiceInfo());
        assertNull("a stale onServiceFound after stop() must never trigger a resolve",
                nsdProbe.lastResolveRequest);
    }

    @Test
    public void overallTimeoutGivesUpAndNotifiesListener() {
        KeepADBFakeNsdProbe nsdProbe = new KeepADBFakeNsdProbe();
        KeepADBFakeScheduler scheduler = new KeepADBFakeScheduler();
        KeepADBEndpoint endpoint = new KeepADBEndpoint(new FakeContext(), nsdProbe, scheduler);
        RecordingListener listener = new RecordingListener();

        endpoint.discover(listener, false);
        assertFalse(listener.unavailable);

        scheduler.advanceBy(8_000); // OVERALL_TIMEOUT_MS
        assertTrue("the listener must be notified once the overall timeout elapses unresolved",
                listener.unavailable);
        assertEquals(1, nsdProbe.stopServiceDiscoveryCallCount);
    }

    private static final class RecordingListener implements KeepADBEndpoint.Listener {
        boolean unavailable;
        String host;
        int port;

        @Override
        public void onEndpoint(String host, int port) {
            this.host = host;
            this.port = port;
        }

        @Override
        public void onUnavailable() {
            unavailable = true;
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
