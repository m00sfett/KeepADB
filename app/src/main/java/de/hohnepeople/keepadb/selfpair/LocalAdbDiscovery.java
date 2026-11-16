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
import java.util.HashMap;
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
    /** Bounds for what a flood of announcements can make this object hold. */
    private static final int MAX_PENDING = 32;
    private static final int MAX_SERVICES = 16;

    private final ArrayDeque<NsdServiceInfo> pending = new ArrayDeque<>();
    /** Names currently announced; a resolution that finishes after the loss is dropped. */
    private final Map<String, Integer> announced = new HashMap<>();
    private int generation;
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
        String name = info.getServiceName();
        if (stopped || name == null || pending.size() >= MAX_PENDING
                || announced.size() >= MAX_SERVICES || announced.containsKey(name)) {
            return;
        }
        announced.put(name, ++generation);
        pending.add(info);
        resolveNext();
    }

    @Override
    public void onServiceLost(NsdServiceInfo info) {
        boolean changed;
        synchronized (this) {
            announced.remove(info.getServiceName());
            changed = ports.remove(info.getServiceName()) != null && !stopped;
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
        NsdServiceInfo next = pending.poll();
        final String name = next.getServiceName();
        // The announcement this resolution belongs to; a later one of the same name differs.
        final Integer token = announced.get(name);
        nsd.resolveService(next, new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                Log.w(TAG, "resolve failed for " + type + ": " + errorCode);
                release();
                done(false);
            }

            /** A rejected candidate must not keep occupying one of the bounded slots. */
            private void release() {
                synchronized (LocalAdbDiscovery.this) {
                    if (token != null && token.equals(announced.get(name))) {
                        announced.remove(name);
                    }
                }
            }

            @Override
            public void onServiceResolved(NsdServiceInfo info) {
                boolean local = isLocalAddress(info.getHost());
                boolean changed = false;
                int resolved = info.getPort();
                if (local && resolved > 0 && resolved <= 65535) {
                    synchronized (LocalAdbDiscovery.this) {
                        if (!stopped && token != null && token.equals(announced.get(name))) {
                            Integer port = resolved;
                            changed = !port.equals(ports.remove(name));
                            ports.put(name, port);
                        }
                    }
                } else {
                    release();
                }
                done(changed);
            }

            private void done(boolean changed) {
                boolean notify;
                synchronized (LocalAdbDiscovery.this) {
                    resolving = false;
                    resolveNext();
                    notify = changed && !stopped;
                }
                if (notify) {
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
