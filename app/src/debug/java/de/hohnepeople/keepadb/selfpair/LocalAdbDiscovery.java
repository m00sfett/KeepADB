package de.hohnepeople.keepadb.selfpair;

import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.function.IntConsumer;

/**
 * Finds one of this device's own ADB mDNS services (#962) and reports its port. Services that
 * other devices on the network announce are ignored: only a service whose resolved address
 * belongs to a local interface counts, and the caller then connects to loopback.
 */
final class LocalAdbDiscovery implements NsdManager.DiscoveryListener {
    static final String PAIRING = "_adb-tls-pairing._tcp";
    static final String CONNECT = "_adb-tls-connect._tcp";
    private static final String TAG = "KeepADBSelfPair";

    private final NsdManager nsd;
    private final String type;
    private final IntConsumer onPort;
    private final ArrayDeque<NsdServiceInfo> pending = new ArrayDeque<>();
    private boolean resolving;
    private boolean stopped;

    LocalAdbDiscovery(NsdManager nsd, String type, IntConsumer onPort) {
        this.nsd = nsd;
        this.type = type;
        this.onPort = onPort;
    }

    void start() {
        nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, this);
    }

    synchronized void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        try {
            nsd.stopServiceDiscovery(this);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "discovery was not running: " + type);
        }
    }

    @Override
    public synchronized void onServiceFound(NsdServiceInfo info) {
        pending.add(info);
        resolveNext();
    }

    /** NsdManager resolves one service at a time on older releases, so queue them. */
    private void resolveNext() {
        if (resolving || stopped || pending.isEmpty()) {
            return;
        }
        resolving = true;
        nsd.resolveService(pending.poll(), new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                Log.w(TAG, "resolve failed for " + type + ": " + errorCode);
                done();
            }

            @Override
            public void onServiceResolved(NsdServiceInfo info) {
                boolean local = isLocalAddress(info.getHost());
                Log.i(TAG, "resolved " + type + " port " + info.getPort() + ", local=" + local);
                if (local) {
                    onPort.accept(info.getPort());
                }
                done();
            }

            private void done() {
                synchronized (LocalAdbDiscovery.this) {
                    resolving = false;
                    resolveNext();
                }
            }
        });
    }

    static boolean isLocalAddress(InetAddress address) {
        if (address == null) {
            return false;
        }
        if (address.isLoopbackAddress()) {
            return true;
        }
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress own : Collections.list(nic.getInetAddresses())) {
                    if (own.equals(address)) {
                        return true;
                    }
                }
            }
        } catch (SocketException e) {
            Log.w(TAG, "cannot list interfaces", e);
        }
        return false;
    }

    @Override
    public void onServiceLost(NsdServiceInfo info) {
    }

    @Override
    public void onDiscoveryStarted(String serviceType) {
        Log.i(TAG, "discovery started: " + serviceType);
    }

    @Override
    public void onDiscoveryStopped(String serviceType) {
    }

    @Override
    public void onStartDiscoveryFailed(String serviceType, int errorCode) {
        Log.w(TAG, "discovery failed to start: " + serviceType + " " + errorCode);
    }

    @Override
    public void onStopDiscoveryFailed(String serviceType, int errorCode) {
    }
}
