package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * #594: behavior tests at the new {@link KeepADBEndpointCoordinator} boundary -- the component
 * that owns cached endpoint, discovery owner, generation/token, retry and cancellation after the
 * split from {@link KeepADBNotification}.
 *
 * <p>Discovery results are delivered by invoking the exact {@link KeepADBEndpoint.Listener} the
 * coordinator handed to its (fake-probe backed) {@link KeepADBEndpoint}, read back via reflection:
 * a real mDNS resolve needs a real socket and an own Wi-Fi address, which Robolectric cannot give
 * deterministically (see {@link KeepADBFakeNsdProbe}). Everything between that callback and the
 * observable outcome is production code.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBEndpointCoordinatorTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = org.robolectric.RuntimeEnvironment.getApplication();
    private KeepADBFakeNsdProbe nsdProbe;

    @Before
    public void setUp() {
        shadowOf((Application) context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(true));
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
    }

    @After
    public void tearDown() {
        KeepADBEndpointCoordinator.resetForTesting();
        KeepADBNetwork.resetForTesting();
        KeepADB.resetForTesting();
    }

    /**
     * Snapshot consistency: a reader must never observe host, port and verification time of two
     * different endpoints. The test holds the coordinator monitor while it is half-way through
     * replacing endpoint A by B (exactly what every production mutation site does under that
     * monitor); a reader taking a snapshot meanwhile must wait and then see B completely.
     */
    @Test
    public void snapshotNeverMixesHostPortAndTimeOfTwoEndpoints() throws Exception {
        setStatic("currentHost", "192.0.2.1");
        setStatic("currentPort", 1111);
        setStatic("currentEndpointVerifiedAtMs", 111L);

        AtomicReference<KeepADBEndpointCoordinator.Snapshot> seen = new AtomicReference<>();
        Thread reader = new Thread(() -> seen.set(KeepADBEndpointCoordinator.snapshot()));
        synchronized (KeepADBEndpointCoordinator.class) {
            setStatic("currentHost", "192.0.2.2");
            reader.start();
            reader.join(300);
            assertTrue("a snapshot must not be taken while an endpoint mutation holds the monitor",
                    reader.isAlive());
            setStatic("currentPort", 2222);
            setStatic("currentEndpointVerifiedAtMs", 222L);
        }
        reader.join(5000);

        KeepADBEndpointCoordinator.Snapshot snapshot = seen.get();
        assertNotNull(snapshot);
        assertEquals("192.0.2.2", snapshot.host);
        assertEquals(2222, snapshot.port);
        assertEquals(222L, snapshot.verifiedAtMs);
    }

    /** A snapshot is a value: later coordinator changes never leak into an already taken one. */
    @Test
    public void snapshotIsImmutableAcrossLaterInvalidation() throws Exception {
        setStatic("currentHost", "192.0.2.1");
        setStatic("currentPort", 40000);
        setStatic("currentEndpointVerifiedAtMs", 123L);

        KeepADBEndpointCoordinator.Snapshot before = KeepADBEndpointCoordinator.snapshot();
        KeepADBEndpointCoordinator.invalidateEndpoint(context);

        assertTrue(before.hasEndpoint());
        assertEquals("192.0.2.1", before.host);
        assertEquals(40000, before.port);
        assertEquals(123L, before.verifiedAtMs);
        assertFalse(KeepADBEndpointCoordinator.snapshot().hasEndpoint());
        assertEquals(0L, KeepADBEndpointCoordinator.snapshot().verifiedAtMs);
    }

    /**
     * Stale worker (token guard in isolation): a verification worker that started for endpoint A
     * and then reports it unreachable must not tear down A once A has been freshly re-published by
     * discovery in the meantime -- even though owner (global again) and host/port (still A) both
     * match again when the worker wakes up. Only the verification token tells the two apart.
     */
    @Test
    public void staleVerificationResultCannotInvalidateAFreshlyRepublishedEndpoint() throws Exception {
        installFakeEndpoint();
        KeepADBEndpointCoordinator.refresh(context);
        KeepADBEndpoint.Listener discovery = capturedListener();
        deliver(discovery, "192.0.2.1", 40000);
        assertEquals(1, nsdProbe.discoverServicesCallCount);

        CountDownLatch probeStarted = new CountDownLatch(1);
        CountDownLatch releaseProbe = new CountDownLatch(1);
        KeepADBEndpointCoordinator.setReachabilityProbeForTesting((host, port, timeoutMs) -> {
            probeStarted.countDown();
            await(releaseProbe);
            return false;
        });
        KeepADBEndpointCoordinator.verifyEndpointHealth(context);
        assertTrue(probeStarted.await(5, TimeUnit.SECONDS));

        // Discovery re-delivers the same endpoint (fresh confirmation), then a global refresh
        // re-claims ownership while the old worker is still in flight (coalesced, no new token).
        deliver(discovery, "192.0.2.1", 40000);
        KeepADBEndpointCoordinator.refresh(context);

        releaseProbe.countDown();
        awaitVerificationIdle();

        KeepADBEndpointCoordinator.Snapshot snapshot = KeepADBEndpointCoordinator.snapshot();
        assertEquals("a superseded worker must not invalidate the freshly re-published endpoint",
                "192.0.2.1", snapshot.host);
        assertEquals(40000, snapshot.port);
        assertEquals("a superseded worker must not start a rediscovery either",
                1, nsdProbe.discoverServicesCallCount);
    }

    /**
     * Positive control for the test above: without an intervening re-publication the very same
     * unreachable result must still invalidate A and start a rediscovery -- otherwise a
     * coordinator that ignored every worker result would pass the stale-worker test for the
     * wrong reason.
     */
    @Test
    public void currentVerificationResultStillInvalidatesAnUnreachableEndpoint() throws Exception {
        installFakeEndpoint();
        KeepADBEndpointCoordinator.refresh(context);
        deliver(capturedListener(), "192.0.2.1", 40000);
        KeepADBEndpointCoordinator.setReachabilityProbeForTesting((host, port, timeoutMs) -> false);

        KeepADBEndpointCoordinator.verifyEndpointHealth(context);
        awaitVerificationIdle();

        assertFalse(KeepADBEndpointCoordinator.snapshot().hasEndpoint());
        assertEquals("the stale endpoint must trigger exactly one rediscovery",
                2, nsdProbe.discoverServicesCallCount);
    }

    /** Owner cancellation: a Tile cancels its own discovery and a late Tile result is dropped. */
    @Test
    public void tileCancellationStopsItsOwnDiscoveryAndDropsItsLateResult() throws Exception {
        Object tile = new Object();
        installFakeEndpoint();
        KeepADBEndpointCoordinator.refreshForTile(context, tile);
        KeepADBEndpoint.Listener tileDiscovery = capturedListener();
        assertTrue(KeepADBEndpointCoordinator.hasActiveDiscoveryAttemptForTesting());

        KeepADBEndpointCoordinator.cancelTileDiscovery(tile);
        assertFalse("the Tile's own discovery must be stopped",
                KeepADBEndpointCoordinator.hasActiveDiscoveryAttemptForTesting());

        deliver(tileDiscovery, "192.0.2.1", 40000);
        assertNull("a result arriving after cancellation must not be published",
                KeepADBEndpointCoordinator.snapshot().host);
    }

    /** Owner cancellation must never reach a discovery retained by the global owner. */
    @Test
    public void tileCancellationNeverAbortsAGlobalDiscovery() throws Exception {
        Object tile = new Object();
        installFakeEndpoint();
        KeepADBEndpointCoordinator.refresh(context);

        KeepADBEndpointCoordinator.cancelTileDiscovery(tile);
        assertTrue("a Tile that never owned the discovery must not cancel it",
                KeepADBEndpointCoordinator.hasActiveDiscoveryAttemptForTesting());

        // A Tile joining the running global discovery must not adopt it either.
        KeepADBEndpointCoordinator.refreshForTile(context, tile);
        KeepADBEndpointCoordinator.cancelTileDiscovery(tile);
        assertTrue("a Tile must never adopt (and then cancel) the global discovery",
                KeepADBEndpointCoordinator.hasActiveDiscoveryAttemptForTesting());

        // The Tile refresh restarted discovery under the retained global owner; its result
        // (the current request) must still be published.
        deliver(capturedListener(), "192.0.2.1", 40000);
        KeepADBEndpointCoordinator.Snapshot snapshot = KeepADBEndpointCoordinator.snapshot();
        assertEquals("the global discovery result must still be published", "192.0.2.1", snapshot.host);
        assertEquals(40000, snapshot.port);
        assertTrue(snapshot.verifiedAtMs > 0);
    }

    /** Listener events and snapshot describe the same endpoint for every consumer. */
    @Test
    public void listenerEventsAndSnapshotAgree() throws Exception {
        installFakeEndpoint();
        AtomicReference<String> lastEvent = new AtomicReference<>();
        KeepADBEndpointCoordinator.setEndpointListener(new KeepADBEndpointCoordinator.EndpointListener() {
            @Override
            public void onEndpoint(String host, int port) {
                lastEvent.set(host + ":" + port);
            }

            @Override
            public void onUnavailable() {
                lastEvent.set("unavailable");
            }
        });
        assertEquals("unavailable", lastEvent.get());

        KeepADBEndpointCoordinator.refresh(context);
        deliver(capturedListener(), "192.0.2.7", 41234);
        KeepADBEndpointCoordinator.Snapshot snapshot = KeepADBEndpointCoordinator.snapshot();
        assertEquals(snapshot.host + ":" + snapshot.port, lastEvent.get());

        KeepADBEndpointCoordinator.invalidateEndpoint(context);
        assertEquals("unavailable", lastEvent.get());
        assertFalse(KeepADBEndpointCoordinator.snapshot().hasEndpoint());
    }

    private void installFakeEndpoint() {
        nsdProbe = new KeepADBFakeNsdProbe();
        KeepADBEndpointCoordinator.setEndpointForTesting(
                new KeepADBEndpoint(context, nsdProbe, new KeepADBFakeScheduler()));
    }

    /**
     * Delivers a discovery result the way {@link KeepADBEndpoint} does: it stops its own session
     * first and then calls the listener (outside its lock).
     */
    private static void deliver(KeepADBEndpoint.Listener listener, String host, int port) throws Exception {
        Field endpointField = KeepADBEndpointCoordinator.class.getDeclaredField("endpoint");
        endpointField.setAccessible(true);
        KeepADBEndpoint endpoint = (KeepADBEndpoint) endpointField.get(null);
        if (endpoint != null) endpoint.stop();
        listener.onEndpoint(host, port);
    }

    private static KeepADBEndpoint.Listener capturedListener() throws Exception {
        Field endpointField = KeepADBEndpointCoordinator.class.getDeclaredField("endpoint");
        endpointField.setAccessible(true);
        Object endpoint = endpointField.get(null);
        assertNotNull("precondition: discovery must have been started", endpoint);
        Field listenerField = KeepADBEndpoint.class.getDeclaredField("currentListener");
        listenerField.setAccessible(true);
        KeepADBEndpoint.Listener listener = (KeepADBEndpoint.Listener) listenerField.get(endpoint);
        assertNotNull("precondition: the coordinator must have attached its listener", listener);
        return listener;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitVerificationIdle() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (KeepADBEndpointCoordinator.isVerificationInFlightForTesting()) {
            if (System.currentTimeMillis() > deadline) {
                fail("verification worker did not finish within the timeout");
            }
            Thread.sleep(10);
        }
    }

    private static void setStatic(String fieldName, Object value) throws Exception {
        Field field = KeepADBEndpointCoordinator.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, value);
    }
}
