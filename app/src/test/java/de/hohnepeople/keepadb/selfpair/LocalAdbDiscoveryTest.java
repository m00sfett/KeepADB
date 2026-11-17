package de.hohnepeople.keepadb.selfpair;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Looper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests {@link LocalAdbDiscovery}'s serial resolve queue and deferred retry handling for
 * {@link NsdManager#FAILURE_ALREADY_ACTIVE} on Android &lt; 13.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LocalAdbDiscoveryTest {

    private static final class FakeBridge implements LocalAdbDiscovery.Bridge {
        final List<NsdServiceInfo> resolvedRequests = new ArrayList<>();
        final ArrayDeque<NsdManager.ResolveListener> listeners = new ArrayDeque<>();
        int stopCalls;
        RuntimeException throwOnNextResolve;

        @Override
        public void discoverServices(String serviceType, NsdManager.DiscoveryListener listener) {
        }

        @Override
        public void stopServiceDiscovery(NsdManager.DiscoveryListener listener) {
            stopCalls++;
        }

        @Override
        public void resolveService(
                NsdServiceInfo serviceInfo, NsdManager.ResolveListener listener) {
            if (throwOnNextResolve != null) {
                RuntimeException error = throwOnNextResolve;
                throwOnNextResolve = null;
                throw error;
            }
            resolvedRequests.add(serviceInfo);
            listeners.add(listener);
        }
    }

    private static NsdServiceInfo service(String name) {
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(name);
        info.setServiceType(LocalAdbDiscovery.PAIRING);
        return info;
    }

    private static NsdServiceInfo resolvedService(String name, int port) {
        NsdServiceInfo info = service(name);
        info.setHost(InetAddress.getLoopbackAddress());
        info.setPort(port);
        return info;
    }

    @Test
    public void failureAlreadyActiveRetriesAfterDelayAndDeliversPort() {
        FakeBridge bridge = new FakeBridge();
        AtomicInteger changes = new AtomicInteger();
        LocalAdbDiscovery discovery =
                new LocalAdbDiscovery(bridge, LocalAdbDiscovery.PAIRING, changes::incrementAndGet);
        discovery.start();

        discovery.onServiceFound(service("adb-pair-1"));
        assertEquals(1, bridge.resolvedRequests.size());

        bridge.listeners.poll().onResolveFailed(
                bridge.resolvedRequests.get(0), NsdManager.FAILURE_ALREADY_ACTIVE);
        assertEquals(1, bridge.resolvedRequests.size());
        assertEquals(0, changes.get());

        Shadows.shadowOf(Looper.getMainLooper())
                .idleFor(Duration.ofMillis(LocalAdbDiscovery.RETRY_DELAY_MS));
        assertEquals(2, bridge.resolvedRequests.size());

        bridge.listeners.poll().onServiceResolved(resolvedService("adb-pair-1", 37123));
        assertEquals(1, changes.get());
        assertEquals(List.of(37123), discovery.candidatePorts());
    }

    @Test
    public void failureAlreadyActiveStopsAfterMaxRetriesAndAdvancesQueue() {
        FakeBridge bridge = new FakeBridge();
        AtomicInteger changes = new AtomicInteger();
        LocalAdbDiscovery discovery =
                new LocalAdbDiscovery(bridge, LocalAdbDiscovery.PAIRING, changes::incrementAndGet);
        discovery.start();

        discovery.onServiceFound(service("adb-first"));

        for (int attempt = 0; attempt < 5; attempt++) {
            assertEquals("attempt " + attempt, attempt + 1, bridge.resolvedRequests.size());
            assertEquals("adb-first", bridge.resolvedRequests.get(attempt).getServiceName());
            if (attempt == 4) {
                // Queue a second service before the final failure triggers drop + advance.
                discovery.onServiceFound(service("adb-second"));
            }
            bridge.listeners.poll().onResolveFailed(
                    bridge.resolvedRequests.get(attempt), NsdManager.FAILURE_ALREADY_ACTIVE);
            Shadows.shadowOf(Looper.getMainLooper())
                    .idleFor(Duration.ofMillis(LocalAdbDiscovery.RETRY_DELAY_MS));
        }

        // After exhausting retries for adb-first, the queue moves on to adb-second.
        assertEquals(6, bridge.resolvedRequests.size());
        assertEquals("adb-second", bridge.resolvedRequests.get(5).getServiceName());
        bridge.listeners.poll().onServiceResolved(resolvedService("adb-second", 41234));
        assertEquals(List.of(41234), discovery.candidatePorts());
        assertEquals(1, changes.get());
    }

    @Test
    public void nonAlreadyActiveFailureDoesNotRetry() {
        FakeBridge bridge = new FakeBridge();
        LocalAdbDiscovery discovery =
                new LocalAdbDiscovery(bridge, LocalAdbDiscovery.PAIRING, () -> {});
        discovery.start();

        discovery.onServiceFound(service("adb-bad"));
        assertEquals(1, bridge.resolvedRequests.size());

        bridge.listeners.poll().onResolveFailed(
                bridge.resolvedRequests.get(0), NsdManager.FAILURE_INTERNAL_ERROR);
        Shadows.shadowOf(Looper.getMainLooper())
                .idleFor(Duration.ofMillis(LocalAdbDiscovery.RETRY_DELAY_MS * 2));
        assertEquals(1, bridge.resolvedRequests.size());
        assertTrue(discovery.candidatePorts().isEmpty());
    }

    @Test
    public void stopCancelsPendingAlreadyActiveRetry() {
        FakeBridge bridge = new FakeBridge();
        LocalAdbDiscovery discovery =
                new LocalAdbDiscovery(bridge, LocalAdbDiscovery.PAIRING, () -> {});
        discovery.start();

        discovery.onServiceFound(service("adb-pair-1"));
        assertEquals(1, bridge.resolvedRequests.size());
        bridge.listeners.poll().onResolveFailed(
                bridge.resolvedRequests.get(0), NsdManager.FAILURE_ALREADY_ACTIVE);

        discovery.stop();
        assertEquals(1, bridge.stopCalls);

        Shadows.shadowOf(Looper.getMainLooper())
                .idleFor(Duration.ofMillis(LocalAdbDiscovery.RETRY_DELAY_MS * 2));
        assertEquals(1, bridge.resolvedRequests.size());
    }

    @Test
    public void resolveExceptionUnblocksQueueAndContinuesToNextService() {
        FakeBridge bridge = new FakeBridge();
        bridge.throwOnNextResolve = new IllegalArgumentException("nsd failed synchronously");
        AtomicInteger changes = new AtomicInteger();
        LocalAdbDiscovery discovery =
                new LocalAdbDiscovery(bridge, LocalAdbDiscovery.PAIRING, changes::incrementAndGet);
        discovery.start();

        discovery.onServiceFound(service("adb-throws"));
        discovery.onServiceFound(service("adb-recovers"));

        assertEquals(1, bridge.resolvedRequests.size());
        assertEquals("adb-recovers", bridge.resolvedRequests.get(0).getServiceName());
        bridge.listeners.poll().onServiceResolved(resolvedService("adb-recovers", 39876));
        assertEquals(List.of(39876), discovery.candidatePorts());
        assertEquals(1, changes.get());
    }
}
