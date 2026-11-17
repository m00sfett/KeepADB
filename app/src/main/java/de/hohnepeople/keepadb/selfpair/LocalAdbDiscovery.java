package de.hohnepeople.keepadb.selfpair;

import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
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
 *
 * <p>The change callback runs outside the lock, so one that was already under way when
 * {@link #stop} returns can still arrive; it carries no data and must tolerate that.
 */
final class LocalAdbDiscovery implements NsdManager.DiscoveryListener {
    static final String PAIRING = "_adb-tls-pairing._tcp";
    static final String CONNECT = "_adb-tls-connect._tcp";
    private static final String TAG = "KeepADBSelfPair";

    interface Bridge {
        void discoverServices(String serviceType, NsdManager.DiscoveryListener listener);
        void stopServiceDiscovery(NsdManager.DiscoveryListener listener);
        void resolveService(NsdServiceInfo serviceInfo, NsdManager.ResolveListener listener);
    }

    private final Bridge nsd;
    private final String type;
    private final Runnable onChange;
    /** Bounds for what a flood of announcements can make this object hold. */
    private static final int MAX_PENDING = 32;
    private static final int MAX_SERVICES = 16;
    private static final int MAX_ALREADY_ACTIVE_RETRIES = 4;
    static final long RETRY_DELAY_MS = 250;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<NsdServiceInfo> pending = new ArrayDeque<>();
    /** The announcement each queued entry belongs to, in the same order as {@link #pending}. */
    private final ArrayDeque<Integer> pendingTokens = new ArrayDeque<>();
    private final ArrayDeque<Integer> pendingRetries = new ArrayDeque<>();
    /** Names currently announced; a resolution that finishes after the loss is dropped. */
    private final Map<String, Integer> announced = new HashMap<>();
    private int generation;
    /** Service name to port, in the order the services were resolved. */
    private final Map<String, Integer> ports = new LinkedHashMap<>();
    private boolean resolving;
    private boolean stopped;

    LocalAdbDiscovery(NsdManager nsd, String type, Runnable onChange) {
        this(nsd == null ? null : new Bridge() {
            @Override
            public void discoverServices(String serviceType, NsdManager.DiscoveryListener listener) {
                nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener);
            }

            @Override
            public void stopServiceDiscovery(NsdManager.DiscoveryListener listener) {
                nsd.stopServiceDiscovery(listener);
            }

            @Override
            public void resolveService(
                    NsdServiceInfo serviceInfo, NsdManager.ResolveListener listener) {
                nsd.resolveService(serviceInfo, listener);
            }
        }, type, onChange);
    }

    LocalAdbDiscovery(Bridge nsd, String type, Runnable onChange) {
        this.nsd = nsd;
        this.type = type;
        this.onChange = onChange;
    }

    void start() {
        try {
            if (nsd != null) {
                nsd.discoverServices(type, this);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot start discovery for " + type + ": " + e);
        }
    }

    synchronized void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        handler.removeCallbacksAndMessages(null);
        try {
            if (nsd != null) {
                nsd.stopServiceDiscovery(this);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "discovery was not running or failed to stop: " + type);
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
        pendingTokens.add(generation);
        pendingRetries.add(0);
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
        // The announcement this entry was queued for; a later one of the same name differs.
        final Integer token = pendingTokens.poll();
        final int attempt = pendingRetries.isEmpty() ? 0 : pendingRetries.poll();
        if (!token.equals(announced.get(name))) {
            // Lost (and possibly announced again) while it waited: not ours to resolve.
            resolving = false;
            resolveNext();
            return;
        }
        nsd.resolveService(next, new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                Log.w(TAG, "resolve failed for " + type + ": " + errorCode);
                if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE
                        && attempt < MAX_ALREADY_ACTIVE_RETRIES && requeue(next, token, attempt + 1)) {
                    scheduleDeferredRetry();
                    return;
                }
                release();
                done(false);
            }

            /** Re-queues when NsdManager reports FAILURE_ALREADY_ACTIVE (Android < 13 single-resolve limit). */
            private boolean requeue(NsdServiceInfo service, Integer entryToken, int nextAttempt) {
                synchronized (LocalAdbDiscovery.this) {
                    if (stopped || pending.size() >= MAX_PENDING
                            || entryToken == null || !entryToken.equals(announced.get(name))) {
                        return false;
                    }
                    pending.add(service);
                    pendingTokens.add(entryToken);
                    pendingRetries.add(nextAttempt);
                    return true;
                }
            }

            private void scheduleDeferredRetry() {
                handler.postDelayed(() -> {
                    synchronized (LocalAdbDiscovery.this) {
                        resolving = false;
                        if (!stopped) {
                            resolveNext();
                        }
                    }
                }, RETRY_DELAY_MS);
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
