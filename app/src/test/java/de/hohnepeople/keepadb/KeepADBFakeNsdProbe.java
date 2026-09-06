package de.hohnepeople.keepadb;

import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

/**
 * Records what {@link KeepADBEndpoint} asks of mDNS discovery, so a test can assert call counts
 * and drive {@code stop()} without a real {@code NsdManager} or a real mDNS responder (#249).
 *
 * <p>This fake deliberately does not attempt to simulate a successful resolve with real host/
 * port data: {@link NsdServiceInfo} is a {@code final} platform class whose setters are no-ops
 * under the project's JVM-only, Robolectric-free unit test setup (see AGP's mockable-android.jar
 * with {@code returnDefaultValues=true}), so a resolved instance's {@code getHost()}/{@code
 * getPort()}/{@code getServiceType()} cannot be made to return real data here. Tests instead
 * cover what's independent of that data: call counts, listener replacement, and cancellation.
 */
final class KeepADBFakeNsdProbe implements KeepADBNsdProbe {
    NsdManager.DiscoveryListener discoveryListener;
    NsdManager.ResolveListener resolveListener;
    NsdServiceInfo lastResolveRequest;
    int discoverServicesCallCount;
    int stopServiceDiscoveryCallCount;

    @Override
    public void discoverServices(String serviceType, NsdManager.DiscoveryListener listener) {
        discoveryListener = listener;
        discoverServicesCallCount++;
    }

    @Override
    public void resolveService(NsdServiceInfo serviceInfo, NsdManager.ResolveListener listener) {
        lastResolveRequest = serviceInfo;
        resolveListener = listener;
    }

    @Override
    public void stopServiceDiscovery(NsdManager.DiscoveryListener listener) {
        stopServiceDiscoveryCallCount++;
    }
}
