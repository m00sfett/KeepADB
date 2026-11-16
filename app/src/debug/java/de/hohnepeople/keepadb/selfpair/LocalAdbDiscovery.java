package de.hohnepeople.keepadb.selfpair;

import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds this device's own ADB mDNS services of one type (#962) and keeps their ports. Services
 * that other devices on the network announce are ignored: only a service whose resolved address
 * belongs to a local interface counts, and the caller then connects to loopback.
 *
 * <p>mDNS can still hold records of services that are gone, so the result is a list of
 * candidates, newest first, not a single truth.
 */
final class LocalAdbDiscovery implements NsdManager.DiscoveryListener {
    static final String PAIRING = "_adb-tls-pairing._tcp";
    static final String CONNECT = "_adb-tls-connect._tcp";
    private static final String TAG = "KeepADBSelfPair";

    private final NsdManager nsd;
    private final String type;
    private final Runnable onChange;
    private final ArrayDeque<NsdServiceInfo> pending = new ArrayDeque<>();
    /** Service name to port, in the order the services were resolved. */
    private final Map<String, Integer> ports = new LinkedHashMap<>();
    private boolean resolving;
    private boolean stopped;

    LocalAdbDiscovery(NsdManager nsd, String type, Runnable onChange) {
        this.nsd = nsd;
        this.type = type;
        this.onChange = onChange;
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

    /** Ports of the local services currently known, most recently resolved first. */
    synchronized List<Integer> candidatePorts() {
        List<Integer> out = new ArrayList<>();
        for (Integer port : ports.values()) {
            if (!out.contains(port)) {
                out.add(port);
            }
        }
        Collections.reverse(out);
        return out;
    }

    @Override
    public synchronized void onServiceFound(NsdServiceInfo info) {
        pending.add(info);
        resolveNext();
    }

    @Override
    public void onServiceLost(NsdServiceInfo info) {
        boolean changed;
        synchronized (this) {
            changed = ports.remove(info.getServiceName()) != null;
        }
        if (changed) {
            onChange.run();
        }
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
                done(false);
            }

            @Override
            public void onServiceResolved(NsdServiceInfo info) {
                boolean local = isLocalAddress(info.getHost());
                boolean changed = false;
                if (local) {
                    synchronized (LocalAdbDiscovery.this) {
                        Integer port = info.getPort();
                        changed = !port.equals(ports.remove(info.getServiceName()));
                        ports.put(info.getServiceName(), port);
                    }
                }
                done(changed);
            }

            private void done(boolean changed) {
                synchronized (LocalAdbDiscovery.this) {
                    resolving = false;
                    resolveNext();
                }
                if (changed) {
                    onChange.run();
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
    public void onDiscoveryStarted(String serviceType) {
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
