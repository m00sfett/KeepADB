package de.hohnepeople.keepadb;

import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

/** Production {@link KeepADBNsdProbe} backed by a real {@link NsdManager}. */
final class KeepADBAndroidNsdProbe implements KeepADBNsdProbe {
    private final NsdManager nsdManager;

    KeepADBAndroidNsdProbe(NsdManager nsdManager) {
        this.nsdManager = nsdManager;
    }

    @Override
    public void discoverServices(String serviceType, NsdManager.DiscoveryListener listener) {
        nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener);
    }

    @Override
    public void resolveService(NsdServiceInfo serviceInfo, NsdManager.ResolveListener listener) {
        nsdManager.resolveService(serviceInfo, listener);
    }

    @Override
    public void stopServiceDiscovery(NsdManager.DiscoveryListener listener) {
        nsdManager.stopServiceDiscovery(listener);
    }
}
