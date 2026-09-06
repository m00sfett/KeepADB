package de.hohnepeople.keepadb;

import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

/**
 * Boundary around the three NsdManager calls {@link KeepADBEndpoint} uses for mDNS discovery
 * (#249), so its generation-token/resolve-queue logic can be driven deterministically in a test
 * by a fake that fires synthetic discovery/resolve callbacks on demand, instead of needing a
 * real mDNS responder on the network.
 */
interface KeepADBNsdProbe {
    void discoverServices(String serviceType, NsdManager.DiscoveryListener listener);

    void resolveService(NsdServiceInfo serviceInfo, NsdManager.ResolveListener listener);

    void stopServiceDiscovery(NsdManager.DiscoveryListener listener);
}
