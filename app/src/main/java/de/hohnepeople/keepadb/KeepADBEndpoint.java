package de.hohnepeople.keepadb;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Discovers the active secure wireless-debugging endpoint advertised by adbd.
 * mDNS (NsdManager) is the primary, continuously running discovery path, since Android's
 * per-socket framework overhead (~4.6ms measured, regardless of thread count) makes scanning
 * the full local port range a multi-second operation, not the sub-second check it once was.
 * A tightly time-boxed local port probe still runs alongside it as a best-effort shortcut for
 * the common case where adbd's listener is already open.
 */
final class KeepADBEndpoint {
    private static final String TAG = "KeepADBEndpoint";
    static final String SERVICE_TYPE = "_adb-tls-connect._tcp.";
    private static final long RESOLVE_TIMEOUT_MS = 1500;
    static final int PROBE_START_PORT = 30000;
    static final int PROBE_END_PORT = 50000;
    // Measured live on-device: opening a plain SocketChannel and initiating a non-blocking
    // connect() costs ~4.6ms of Android framework overhead PER SOCKET, regardless of whether
    // the connect ever resolves and regardless of how many worker threads run concurrently
    // (2501 ports alone took ~11.6s to just *open*, before any waiting). Scanning the full
    // 20001-port range can therefore never be a "few hundred ms" operation on this device, so
    // mDNS (below) is the primary discovery path; this quick probe is now a best-effort,
    // tightly time-boxed opportunistic check only, not a loop.
    private static final long SCAN_BATCH_TIMEOUT_MS = 300;
    private static final int SCAN_WORKERS = 8;
    private static final java.util.concurrent.ExecutorService SCAN_EXECUTOR =
            java.util.concurrent.Executors.newFixedThreadPool(SCAN_WORKERS, r -> {
                Thread t = new Thread(r, "KeepADBScanWorker");
                t.setDaemon(true);
                return t;
            });
    private static final long RECOVERY_PULSE_DELAY_MS = 5000;
    private static final long RECOVERY_PULSE_OFF_MS = 800;
    // Must stay comfortably above RECOVERY_PULSE_DELAY_MS + RECOVERY_PULSE_OFF_MS (5800ms):
    // the recovery pulse's re-enable only happens at that point, and mDNS still needs to pick
    // up the freshly re-advertised listener afterwards (typically 1-2s per README). Cutting
    // this too close would make the overall timeout fire before the pulse's own fix had a
    // realistic chance to work. Giving up here isn't otherwise fatal: KeepADBNotification's
    // onUnavailable() retries with backoff, so a too-short timeout just costs an extra cycle
    // in the slow-path case, not the endpoint.
    private static final long OVERALL_TIMEOUT_MS = 8_000;

    interface Listener {
        void onEndpoint(String host, int port);
        void onUnavailable();
    }

    private final Context appContext;
    private final NsdManager nsdManager;
    private final WifiManager.MulticastLock multicastLock;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private NsdManager.DiscoveryListener discoveryListener;
    private final Queue<NsdServiceInfo> resolveQueue = new ArrayDeque<>();
    private boolean resolving;
    private Runnable resolveWatchdogRunnable;
    private long discoveryGeneration;
    private Listener currentListener;
    private boolean discovering;
    private Thread coordinatorThread;
    private final AtomicBoolean endpointDelivered = new AtomicBoolean(false);
    private Runnable recoveryPulseRunnable;
    private Runnable overallTimeoutRunnable;

    KeepADBEndpoint(Context context) {
        appContext = context.getApplicationContext();
        nsdManager = (NsdManager) appContext.getSystemService(Context.NSD_SERVICE);
        WifiManager wifiManager = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
        if (wifiManager != null) {
            multicastLock = wifiManager.createMulticastLock("de.hohnepeople.keepadb.KeepADBEndpoint");
            multicastLock.setReferenceCounted(false);
        } else {
            multicastLock = null;
        }
    }

    synchronized void discover(Listener listener) {
        this.currentListener = listener;
        if (discovering && !endpointDelivered.get()) {
            Log.d(TAG, "discover called while already discovering (gen=" + discoveryGeneration + "); attached listener to active probe");
            return;
        }
        if (discovering) {
            stop();
        }
        if (nsdManager == null) {
            this.currentListener = null;
            if (listener != null) {
                listener.onUnavailable();
            }
            return;
        }

        discovering = true;
        endpointDelivered.set(false);

        if (multicastLock != null && !multicastLock.isHeld()) {
            try {
                multicastLock.acquire();
            } catch (RuntimeException ignored) {
            }
        }

        final long generation = discoveryGeneration;

        // 1. Best-effort, tightly time-boxed local port probe (see SCAN_BATCH_TIMEOUT_MS) --
        // covers the common case where adbd's listener is already up, without blocking mDNS.
        startQuickProbe(generation);

        // 2. mDNS discovery -- the primary, continuously running discovery path.
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String serviceType) {
                Log.d(TAG, "mDNS discovery started");
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                if (!isCurrent(generation) || endpointDelivered.get() || !sameServiceType(serviceInfo.getServiceType())) {
                    return;
                }
                synchronized (KeepADBEndpoint.this) {
                    if (!isCurrent(generation) || endpointDelivered.get()) return;
                    resolveQueue.offer(serviceInfo);
                    processNextResolveLocked(generation);
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.w(TAG, "mDNS start discovery failed with code: " + errorCode);
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
            }
        };

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed to start mDNS service discovery", e);
            discoveryListener = null;
        }

        // 3. #114 safety net: if adb_wifi_enabled is on but nothing was found after a while,
        // adbd may have accepted the toggle mid-teardown of a previous session without ever
        // binding a listener. Pulse it once to force a clean restart, then give mDNS a fresh
        // chance to pick up the new advertisement before giving up entirely.
        recoveryPulseRunnable = () -> maybeSendRecoveryPulse(generation);
        mainHandler.postDelayed(recoveryPulseRunnable, RECOVERY_PULSE_DELAY_MS);
        overallTimeoutRunnable = () -> giveUpIfStillUnresolved(generation);
        mainHandler.postDelayed(overallTimeoutRunnable, OVERALL_TIMEOUT_MS);
    }

    // Static, not per-instance: toggling adb_wifi_enabled fires KeepADBService's/MainActivity's
    // ContentObserver, which tears down and recreates the KeepADBEndpoint instance (see
    // KeepADBNotification.stop()/startDiscoveryDirectLocked()). An instance-scoped "already
    // pulsed" flag would reset with every such recreation, causing our own pulse to retrigger
    // itself every ~6.5s in an endless loop that never gave mDNS a real chance to resolve
    // anything -- found live: the recovery pulse fired repeatedly for 40+ seconds straight.
    private static volatile long lastRecoveryPulseAtMs = 0;
    private static final long RECOVERY_PULSE_COOLDOWN_MS = 20_000;

    private void maybeSendRecoveryPulse(long generation) {
        synchronized (this) {
            if (!isCurrent(generation) || endpointDelivered.get()) return;
            if (!KeepADB.isEnabled(appContext) || KeepADB.isUserDisabled()) return;
        }
        long now = System.currentTimeMillis();
        synchronized (KeepADBEndpoint.class) {
            if (now - lastRecoveryPulseAtMs < RECOVERY_PULSE_COOLDOWN_MS) return;
            lastRecoveryPulseAtMs = now;
        }
        Log.w(TAG, "gen=" + generation + " found no adbd listener after " + RECOVERY_PULSE_DELAY_MS
                + "ms while enabled; pulsing adb_wifi_enabled to recover");
        KeepADB.performRecoveryPulse(appContext);
    }

    private void giveUpIfStillUnresolved(long generation) {
        Listener targetListener;
        synchronized (this) {
            if (!isCurrent(generation) || endpointDelivered.get()) return;
            Log.w(TAG, "gen=" + generation + " timed out after " + OVERALL_TIMEOUT_MS + "ms without an endpoint");
            targetListener = currentListener;
            stop();
        }
        if (targetListener != null) {
            targetListener.onUnavailable();
        }
    }

    private void startQuickProbe(long generation) {
        coordinatorThread = new Thread(() -> {
            List<Integer> openPorts = scanLocalOpenPortsBatch(PROBE_START_PORT, PROBE_END_PORT, generation);
            Log.d(TAG, "QuickProbe gen=" + generation + ": openPorts=" + openPorts);
            if (openPorts.isEmpty() || !isCurrent(generation) || endpointDelivered.get()) {
                return;
            }
            String targetHost = getWifiIpAddress(appContext);
            if (targetHost == null || !endpointDelivered.compareAndSet(false, true)) {
                return;
            }
            // scanLocalOpenPortsBatch already confirmed this port via a real finishConnect(),
            // so no separate re-verification is needed here.
            int candidatePort = openPorts.get(0);
            Log.i(TAG, "QuickProbe verified live ADB endpoint: " + targetHost + ":" + candidatePort);
            Listener targetListener;
            synchronized (KeepADBEndpoint.this) {
                if (isCurrent(generation)) {
                    resolveQueue.clear();
                    targetListener = currentListener;
                    stop();
                } else {
                    targetListener = null;
                }
            }
            if (targetListener != null) {
                targetListener.onEndpoint(targetHost, candidatePort);
            }
        }, "KeepADBQuickProbe");
        coordinatorThread.start();
    }

    private void cancelResolveWatchdogLocked() {
        if (resolveWatchdogRunnable != null) {
            mainHandler.removeCallbacks(resolveWatchdogRunnable);
            resolveWatchdogRunnable = null;
        }
    }

    private void processNextResolveLocked(long generation) {
        if (resolving || resolveQueue.isEmpty() || !isCurrent(generation) || endpointDelivered.get()) {
            return;
        }
        final NsdServiceInfo nextService = resolveQueue.poll();
        if (nextService == null) {
            return;
        }
        resolving = true;
        cancelResolveWatchdogLocked();
        resolveWatchdogRunnable = () -> {
            synchronized (KeepADBEndpoint.this) {
                if (!isCurrent(generation) || !resolving || endpointDelivered.get()) return;
                resolving = false;
                processNextResolveLocked(generation);
            }
        };
        mainHandler.postDelayed(resolveWatchdogRunnable, RESOLVE_TIMEOUT_MS);

        try {
            nsdManager.resolveService(nextService, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo ignored, int errorCode) {
                    synchronized (KeepADBEndpoint.this) {
                        cancelResolveWatchdogLocked();
                        resolving = false;
                        if (!isCurrent(generation) || endpointDelivered.get()) return;
                        processNextResolveLocked(generation);
                    }
                }

                @Override
                public void onServiceResolved(NsdServiceInfo resolved) {
                    synchronized (KeepADBEndpoint.this) {
                        cancelResolveWatchdogLocked();
                        if (!isCurrent(generation) || endpointDelivered.get()) {
                            resolving = false;
                            return;
                        }
                        if (resolved.getHost() == null || resolved.getPort() <= 0) {
                            resolving = false;
                            processNextResolveLocked(generation);
                            return;
                        }
                        final String host = resolved.getHost().getHostAddress();
                        if (host == null || host.isEmpty()) {
                            resolving = false;
                            processNextResolveLocked(generation);
                            return;
                        }
                        final int port = resolved.getPort();
                        final InetAddress addr = resolved.getHost();
                        new Thread(() -> {
                            boolean reachable = isPortReachable(addr, port, 400);
                            if (reachable && endpointDelivered.compareAndSet(false, true)) {
                                Listener targetListener;
                                synchronized (KeepADBEndpoint.this) {
                                    resolveQueue.clear();
                                    targetListener = currentListener;
                                    stop();
                                }
                                if (targetListener != null) {
                                    targetListener.onEndpoint(host, port);
                                }
                            } else {
                                synchronized (KeepADBEndpoint.this) {
                                    resolving = false;
                                    if (isCurrent(generation) && !endpointDelivered.get()) {
                                        processNextResolveLocked(generation);
                                    }
                                }
                            }
                        }, "KeepADBEndpointCheck").start();
                    }
                }
            });
        } catch (RuntimeException ignored) {
            cancelResolveWatchdogLocked();
            resolving = false;
            processNextResolveLocked(generation);
        }
    }

    synchronized void stop() {
        discoveryGeneration++;
        discovering = false;
        currentListener = null;
        cancelResolveWatchdogLocked();
        resolveQueue.clear();
        resolving = false;
        if (coordinatorThread != null) {
            coordinatorThread.interrupt();
            coordinatorThread = null;
        }
        if (recoveryPulseRunnable != null) {
            mainHandler.removeCallbacks(recoveryPulseRunnable);
            recoveryPulseRunnable = null;
        }
        if (overallTimeoutRunnable != null) {
            mainHandler.removeCallbacks(overallTimeoutRunnable);
            overallTimeoutRunnable = null;
        }
        NsdManager.DiscoveryListener listener = discoveryListener;
        discoveryListener = null;
        if (multicastLock != null && multicastLock.isHeld()) {
            try {
                multicastLock.release();
            } catch (RuntimeException ignored) {
            }
        }
        if (nsdManager != null && listener != null) {
            try {
                nsdManager.stopServiceDiscovery(listener);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private synchronized boolean isCurrent(long generation) {
        return discoveryGeneration == generation;
    }

    /**
     * Non-blocking batch scan of [startPort, endPort] on loopback, bounded by
     * {@link #SCAN_BATCH_TIMEOUT_MS} total regardless of how many ports don't answer -- unlike a
     * per-port blocking connect() (with or without a timeout), a port that never responds cannot
     * delay any other port's result, since every socket is polled concurrently via one Selector
     * per worker. The range is split across {@link #SCAN_WORKERS} threads purely to parallelize
     * the per-socket creation overhead; every worker shares the same absolute deadline.
     */
    private static final byte[] LOOPBACK_V4 = {127, 0, 0, 1};
    private static final byte[] LOOPBACK_V6 =
            {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};

    /**
     * adbd's wireless-debugging TLS listener has been observed bound IPv6-only on this device
     * (dual-stack is the common but not guaranteed case), so an IPv4-only loopback scan can
     * silently find nothing even while the listener is up. Try IPv4 first (the common case,
     * cheaper to rule out quickly) and only fall back to IPv6 if that comes up empty.
     */
    private List<Integer> scanLocalOpenPortsBatch(int startPort, int endPort, long generation) {
        List<Integer> found = scanLocalOpenPortsBatch(startPort, endPort, generation, LOOPBACK_V4);
        if (!found.isEmpty() || !isCurrent(generation) || endpointDelivered.get()) {
            return found;
        }
        return scanLocalOpenPortsBatch(startPort, endPort, generation, LOOPBACK_V6);
    }

    private List<Integer> scanLocalOpenPortsBatch(int startPort, int endPort, long generation, byte[] loopbackBytes) {
        final InetAddress loopback;
        try {
            loopback = InetAddress.getByAddress(loopbackBytes);
        } catch (Exception e) {
            return new ArrayList<>();
        }
        final int totalPorts = endPort - startPort + 1;
        final int chunkSize = (totalPorts + SCAN_WORKERS - 1) / SCAN_WORKERS;
        final long deadline = System.currentTimeMillis() + SCAN_BATCH_TIMEOUT_MS;
        final List<java.util.concurrent.Future<List<Integer>>> futures = new ArrayList<>(SCAN_WORKERS);

        for (int i = 0; i < SCAN_WORKERS; i++) {
            final int chunkStart = startPort + i * chunkSize;
            final int chunkEnd = Math.min(chunkStart + chunkSize - 1, endPort);
            if (chunkStart > endPort) break;
            futures.add(SCAN_EXECUTOR.submit(() -> scanChunkNonBlocking(chunkStart, chunkEnd, generation, deadline, loopback)));
        }

        final List<Integer> allOpenPorts = new ArrayList<>();
        for (java.util.concurrent.Future<List<Integer>> f : futures) {
            try {
                allOpenPorts.addAll(f.get());
            } catch (Exception ignored) {
            }
        }
        return allOpenPorts;
    }

    private List<Integer> scanChunkNonBlocking(int startPort, int endPort, long generation, long deadline, InetAddress loopback) {
        List<Integer> openPorts = new ArrayList<>();
        Selector selector;
        try {
            selector = Selector.open();
        } catch (Exception e) {
            return openPorts;
        }

        List<SocketChannel> pending = new ArrayList<>();
        try {
            for (int port = startPort; port <= endPort; port++) {
                // Opening a SocketChannel and initiating connect() has real per-call overhead
                // (see SCAN_BATCH_TIMEOUT_MS doc); without this check the open loop alone could
                // run well past the deadline before the wait phase below ever gets a chance to
                // enforce it, on a large enough port range.
                if (System.currentTimeMillis() >= deadline || !isCurrent(generation)
                        || endpointDelivered.get() || Thread.currentThread().isInterrupted()) {
                    return openPorts;
                }
                SocketChannel channel = null;
                try {
                    channel = SocketChannel.open();
                    channel.configureBlocking(false);
                    if (channel.connect(new InetSocketAddress(loopback, port))) {
                        openPorts.add(port); // connected synchronously (rare, but possible)
                        channel.close();
                    } else {
                        channel.register(selector, SelectionKey.OP_CONNECT, port);
                        pending.add(channel);
                    }
                } catch (Exception ignored) {
                    closeQuietly(channel);
                }
            }

            while (!pending.isEmpty()) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0 || !isCurrent(generation) || endpointDelivered.get()
                        || Thread.currentThread().isInterrupted()) {
                    break;
                }
                int ready;
                try {
                    ready = selector.select(remaining);
                } catch (Exception e) {
                    break;
                }
                if (ready == 0) {
                    continue; // re-check the deadline/generation above
                }
                for (SelectionKey key : selector.selectedKeys()) {
                    SocketChannel channel = (SocketChannel) key.channel();
                    Integer port = (Integer) key.attachment();
                    key.cancel();
                    pending.remove(channel);
                    try {
                        if (channel.finishConnect()) {
                            openPorts.add(port);
                        }
                    } catch (Exception ignored) {
                        // connection refused/reset -- port is closed
                    } finally {
                        closeQuietly(channel);
                    }
                }
                selector.selectedKeys().clear();
            }
        } finally {
            for (SocketChannel channel : pending) {
                closeQuietly(channel);
            }
            try {
                selector.close();
            } catch (Exception ignored) {
            }
        }
        return openPorts;
    }

    private static void closeQuietly(SocketChannel channel) {
        if (channel == null) return;
        try {
            channel.close();
        } catch (Exception ignored) {
        }
    }

    private static boolean isPortReachable(InetAddress addr, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(addr, port), timeoutMs);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Package-visible so a previously reported endpoint can be re-verified before reuse. */
    static boolean isPortReachable(String host, int port, int timeoutMs) {
        try {
            return isPortReachable(InetAddress.getByName(host), port, timeoutMs);
        } catch (Exception ignored) {
            return false;
        }
    }

    static String getWifiIpAddress(Context context) {
        if (context == null) return null;
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                WifiInfo info = wm.getConnectionInfo();
                if (info != null) {
                    int ip = info.getIpAddress();
                    if (ip != 0) {
                        return String.format(Locale.US, "%d.%d.%d.%d",
                                (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
                    }
                }
            }
        } catch (Exception ignored) {
        }

        try {
            ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
            if (cm != null) {
                for (Network network : cm.getAllNetworks()) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        LinkProperties lp = cm.getLinkProperties(network);
                        if (lp != null) {
                            for (LinkAddress la : lp.getLinkAddresses()) {
                                InetAddress addr = la.getAddress();
                                if (addr instanceof Inet4Address && !addr.isLoopbackAddress() && !addr.isLinkLocalAddress()) {
                                    return addr.getHostAddress();
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean sameServiceType(String serviceType) {
        if (serviceType == null) return false;
        String normalized = serviceType.trim();
        while (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return "_adb-tls-connect._tcp".equalsIgnoreCase(normalized);
    }
}
