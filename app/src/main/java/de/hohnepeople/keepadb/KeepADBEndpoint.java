package de.hohnepeople.keepadb;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.util.Log;
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
    private static final java.util.concurrent.ExecutorService VERIFY_EXECUTOR =
            java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "KeepADBVerifyWorker");
                t.setDaemon(true);
                return t;
            });
    // #314: how many of the quick probe's open loopback ports are checked against the Wi-Fi
    // address before giving up for this cycle. See selectWifiVerifiedPort().
    static final int QUICK_PROBE_MAX_CANDIDATES = 8;
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
    private final KeepADBNsdProbe nsdProbe;
    private final KeepADBScheduler scheduler;
    private final WifiManager.MulticastLock multicastLock;
    private NsdManager.DiscoveryListener discoveryListener;
    private final Queue<NsdServiceInfo> resolveQueue = new ArrayDeque<>();
    private boolean resolving;
    private Runnable resolveWatchdogRunnable;
    private long discoveryGeneration;
    private long currentResolveAttemptToken;
    private Listener currentListener;
    private boolean discovering;
    private Thread coordinatorThread;
    private final AtomicBoolean endpointDelivered = new AtomicBoolean(false);
    private Runnable recoveryPulseRunnable;
    private boolean recoveryPulseEnabled;
    private Runnable overallTimeoutRunnable;

    KeepADBEndpoint(Context context) {
        this(context, defaultNsdProbe(context), new KeepADBAndroidScheduler());
    }

    /** Package-visible so tests can substitute a fake probe/scheduler (#249). */
    KeepADBEndpoint(Context context, KeepADBNsdProbe nsdProbe, KeepADBScheduler scheduler) {
        appContext = context.getApplicationContext();
        this.nsdProbe = nsdProbe;
        this.scheduler = scheduler;
        WifiManager wifiManager = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
        if (wifiManager != null) {
            multicastLock = wifiManager.createMulticastLock("de.hohnepeople.keepadb.KeepADBEndpoint");
            multicastLock.setReferenceCounted(false);
        } else {
            multicastLock = null;
        }
    }

    private static KeepADBNsdProbe defaultNsdProbe(Context context) {
        NsdManager nsdManager = (NsdManager) context.getApplicationContext().getSystemService(Context.NSD_SERVICE);
        return nsdManager == null ? null : new KeepADBAndroidNsdProbe(nsdManager);
    }

    synchronized void discover(Listener listener, boolean allowRecoveryPulse) {
        if (discovering && !endpointDelivered.get()) {
            if (!allowRecoveryPulse || recoveryPulseEnabled) {
                currentListener = listener;
                Log.d(TAG, "discover called while already discovering (gen=" + discoveryGeneration
                        + "); attached listener to active probe");
                return;
            }
            // A global caller joined a Tile-only read. Restart so the global path retains its
            // recovery pulse and full timeout while the abandoned Tile session is invalidated.
            stop();
        }
        if (discovering) {
            stop();
        }
        currentListener = listener;
        if (nsdProbe == null) {
            currentListener = null;
            if (listener != null) {
                listener.onUnavailable();
            }
            return;
        }

        discovering = true;
        recoveryPulseEnabled = allowRecoveryPulse;
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
            nsdProbe.discoverServices(SERVICE_TYPE, discoveryListener);
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed to start mDNS service discovery", e);
            discoveryListener = null;
        }

        // 3. #114 safety net: if adb_wifi_enabled is on but nothing was found after a while,
        // adbd may have accepted the toggle mid-teardown of a previous session without ever
        // binding a listener. Pulse it once to force a clean restart, then give mDNS a fresh
        // chance to pick up the new advertisement before giving up entirely.
        if (allowRecoveryPulse) {
            recoveryPulseRunnable = () -> maybeSendRecoveryPulse(generation);
            scheduler.postDelayed(recoveryPulseRunnable, RECOVERY_PULSE_DELAY_MS);
        }
        overallTimeoutRunnable = () -> giveUpIfStillUnresolved(generation);
        scheduler.postDelayed(overallTimeoutRunnable, OVERALL_TIMEOUT_MS);
    }

    private static final long RECOVERY_PULSE_COOLDOWN_MS = 20_000;
    // Static, not per-instance: toggling adb_wifi_enabled fires KeepADBService's/MainActivity's
    // ContentObserver, which tears down and recreates the KeepADBEndpoint instance (see
    // KeepADBNotification.stop()/startDiscoveryDirectLocked()). An instance-scoped "already
    // pulsed" flag would reset with every such recreation, causing our own pulse to retrigger
    // itself every ~6.5s in an endless loop that never gave mDNS a real chance to resolve
    // anything -- found live: the recovery pulse fired repeatedly for 40+ seconds straight.
    //
    // #309: a monotonic reading (SystemClock.elapsedRealtime() behind KeepADBScheduler), never
    // System.currentTimeMillis(): a wall-clock jump (NTP correction, timezone/manual clock
    // change) would either park the cooldown arbitrarily far in the future -- suppressing every
    // recovery pulse until real time caught up -- or jump backwards past it and let the pulse
    // fire far more often than the cooldown allows. Seeded a full cooldown in the past so the
    // first pulse is allowed even while elapsedRealtime() is still below the cooldown, i.e.
    // within the first 20s after boot, where a plain 0 would have suppressed it.
    private static volatile long lastRecoveryPulseAtMs = -RECOVERY_PULSE_COOLDOWN_MS;

    // Package-visible so the endpoint-owned recovery guard can be exercised end-to-end without
    // waiting for the discovery watchdog. This is not part of the app's public API.
    void maybeSendRecoveryPulse(long generation) {
        synchronized (this) {
            if (!isCurrent(generation) || endpointDelivered.get()) return;
            if (!KeepADB.isEnabled(appContext) || KeepADB.wasLastExplicitIntentOff(appContext)) return;
        }
        // #296: "trusted network" is a policy decision about an SSID/BSSID allowlist -- it says
        // nothing about whether the device is actually still on a Wi-Fi transport at all right
        // now. Without this, a device that lost Wi-Fi entirely (but still remembers a trusted
        // network from moments ago) would keep pulsing adb_wifi_enabled every
        // RECOVERY_PULSE_COOLDOWN_MS with no Wi-Fi listener for adbd to ever bind on.
        if (!KeepADBService.isWifiConnected(appContext)) {
            Log.i(TAG, "gen=" + generation + " skipping recovery pulse without an active Wi-Fi connection");
            return;
        }
        if (!KeepADBTrustedNetwork.isCurrentNetworkTrusted(appContext)) {
            Log.i(TAG, "gen=" + generation + " skipping recovery pulse on an untrusted Wi-Fi network");
            return;
        }
        long now = scheduler.elapsedRealtimeMs();
        synchronized (KeepADBEndpoint.class) {
            if (now - lastRecoveryPulseAtMs < RECOVERY_PULSE_COOLDOWN_MS) return;
            lastRecoveryPulseAtMs = now;
        }
        Log.w(TAG, "gen=" + generation + " found no adbd listener after " + RECOVERY_PULSE_DELAY_MS
                + "ms while enabled; pulsing adb_wifi_enabled to recover");
        long keepAdbNetworkGeneration = KeepADB.currentNetworkGeneration();
        KeepADB.performRecoveryPulse(appContext,
                context -> isCurrent(generation)
                        && KeepADB.currentNetworkGeneration() == keepAdbNetworkGeneration
                        && KeepADBService.isWifiConnected(context)
                        && KeepADBTrustedNetwork.isCurrentNetworkTrusted(context)
                        && !KeepADB.wasLastExplicitIntentOff(context));
    }

    static synchronized void resetForTesting() {
        lastRecoveryPulseAtMs = -RECOVERY_PULSE_COOLDOWN_MS;
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
            // Fail-closed (#314): without a current Wi-Fi address there is nothing to bind the
            // candidate to, so no endpoint is registered -- never fall back to the loopback
            // address the scan itself used.
            String targetHost = getWifiIpAddress(appContext);
            if (targetHost == null) {
                return;
            }
            final int candidatePort = selectWifiVerifiedPort(targetHost, openPorts,
                    port -> isCurrent(generation) && !endpointDelivered.get()
                            && isPortReachable(targetHost, port, 300));
            if (candidatePort < 0) {
                Log.w(TAG, "QuickProbe gen=" + generation + ": none of " + openPorts
                        + " answered on Wi-Fi host " + targetHost);
                return;
            }
            Listener targetListener = null;
            synchronized (KeepADBEndpoint.this) {
                if (!isCurrent(generation) || !endpointDelivered.compareAndSet(false, true)) {
                    return;
                }
                Log.i(TAG, "QuickProbe verified live ADB endpoint: " + targetHost + ":" + candidatePort);
                resolveQueue.clear();
                targetListener = currentListener;
                stop();
            }
            if (targetListener != null) {
                targetListener.onEndpoint(targetHost, candidatePort);
            }
        }, "KeepADBQuickProbe");
        coordinatorThread.start();
    }

    private void cancelResolveWatchdogLocked() {
        if (resolveWatchdogRunnable != null) {
            scheduler.removeCallbacks(resolveWatchdogRunnable);
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
        final long attemptToken = ++currentResolveAttemptToken;
        resolving = true;
        cancelResolveWatchdogLocked();
        resolveWatchdogRunnable = () -> {
            synchronized (KeepADBEndpoint.this) {
                if (!isCurrent(generation) || !resolving || currentResolveAttemptToken != attemptToken || endpointDelivered.get()) return;
                resolving = false;
                processNextResolveLocked(generation);
            }
        };
        scheduler.postDelayed(resolveWatchdogRunnable, RESOLVE_TIMEOUT_MS);

        try {
            nsdProbe.resolveService(nextService, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo ignored, int errorCode) {
                    synchronized (KeepADBEndpoint.this) {
                        if (!isCurrent(generation) || currentResolveAttemptToken != attemptToken) return;
                        cancelResolveWatchdogLocked();
                        resolving = false;
                        if (endpointDelivered.get()) return;
                        processNextResolveLocked(generation);
                    }
                }

                @Override
                public void onServiceResolved(NsdServiceInfo resolved) {
                    synchronized (KeepADBEndpoint.this) {
                        if (!isCurrent(generation) || currentResolveAttemptToken != attemptToken || endpointDelivered.get()) {
                            return;
                        }
                        cancelResolveWatchdogLocked();
                        if (resolved.getHost() == null || resolved.getPort() <= 0) {
                            resolving = false;
                            processNextResolveLocked(generation);
                            return;
                        }
                        final InetAddress addr = resolved.getHost();
                        if (!isOwnWifiAddress(appContext, addr)) {
                            Log.w(TAG, "Ignoring mDNS ADB service that is not on our active Wi-Fi address: " + addr);
                            resolving = false;
                            processNextResolveLocked(generation);
                            return;
                        }
                        final String host = addr.getHostAddress();
                        if (host == null || host.isEmpty()) {
                            resolving = false;
                            processNextResolveLocked(generation);
                            return;
                        }
                        final int port = resolved.getPort();
                        VERIFY_EXECUTOR.execute(() -> {
                            boolean reachable = isPortReachable(addr, port, 400);
                            Listener targetListener = null;
                            synchronized (KeepADBEndpoint.this) {
                                if (!isCurrent(generation) || currentResolveAttemptToken != attemptToken) {
                                    return;
                                }
                                if (reachable && endpointDelivered.compareAndSet(false, true)) {
                                    resolveQueue.clear();
                                    targetListener = currentListener;
                                    stop();
                                } else {
                                    resolving = false;
                                    if (isCurrent(generation) && !endpointDelivered.get()) {
                                        processNextResolveLocked(generation);
                                    }
                                    return;
                                }
                            }
                            if (targetListener != null) {
                                targetListener.onEndpoint(host, port);
                            }
                        });
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
        currentResolveAttemptToken++;
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
            scheduler.removeCallbacks(recoveryPulseRunnable);
            recoveryPulseRunnable = null;
        }
        recoveryPulseEnabled = false;
        if (overallTimeoutRunnable != null) {
            scheduler.removeCallbacks(overallTimeoutRunnable);
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
        if (nsdProbe != null && listener != null) {
            try {
                nsdProbe.stopServiceDiscovery(listener);
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

    /**
     * Picks the first scanned loopback port that also answers on {@code wifiHost} (#314).
     *
     * <p>The loopback scan finds every local listener, not only adbd's, so its first hit is not
     * automatically the endpoint: an unrelated local service used to shadow the real adbd port
     * for the whole quick-probe shortcut. Walking the candidates instead lets the real listener
     * still be found, while the "must answer on the Wi-Fi address" requirement keeps a purely
     * loopback-bound service from ever being registered.
     *
     * <p>Bounded by {@link #QUICK_PROBE_MAX_CANDIDATES}: each check costs a blocking connect
     * with its own timeout, and the quick probe is only ever a best-effort shortcut alongside
     * mDNS -- it must not turn into a second long-running scan.
     *
     * @return the verified port, or {@code -1} if none qualifies (including a {@code null}
     *         {@code wifiHost}, i.e. no Wi-Fi address to bind to).
     */
    static int selectWifiVerifiedPort(String wifiHost, List<Integer> openPorts,
                                      java.util.function.IntPredicate reachableOnWifiHost) {
        if (wifiHost == null || openPorts == null) return -1;
        int checked = 0;
        for (Integer port : openPorts) {
            if (port == null) continue;
            if (checked++ >= QUICK_PROBE_MAX_CANDIDATES) break;
            if (reachableOnWifiHost.test(port)) return port;
        }
        return -1;
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
            return KeepADBNetwork.get(context).getWifiIpv4Address();
        } catch (Exception ignored) {
        }
        return null;
    }

    static String formatEndpoint(String host, int port) {
        if (host == null) return ":" + port;
        if (host.contains(":") && !host.startsWith("[")) {
            return "[" + host + "]:" + port;
        }
        return host + ":" + port;
    }

    /**
     * Whether {@code addr} may be registered as a wireless-debugging endpoint at all (#314):
     * it must be an address currently bound to an eligible Wi-Fi network of this device.
     *
     * <p>The predecessor {@code isLocalAddress()} returned {@code true} for <em>every</em>
     * loopback and link-local address before it ever looked at our own interfaces, so an
     * unrelated service -- a neighbour's mDNS responder on an IPv6 link-local address, or any
     * local listener at all -- could be registered as "the" endpoint. Registering a wrong
     * endpoint is worse than registering none: the reported host:port is then handed out as the
     * {@code adb connect} target and blocks the paths that would have found the real listener.
     *
     * <p>Fail-closed by construction: a {@code null} context, an unavailable {@link
     * KeepADBNetwork}, or simply no Wi-Fi connection all yield an empty candidate set and thus
     * {@code false} -- no endpoint is registered rather than a guessed one.
     *
     * <p>Out of scope here (R13 on #314, still open): proving that whatever listens behind the
     * port is really adbd. This gate only proves <em>where</em> the candidate lives, not
     * <em>what</em> it is.
     */
    static boolean isOwnWifiAddress(Context context, InetAddress addr) {
        if (addr == null || context == null) return false;
        try {
            return KeepADBNetwork.get(context).isActiveWifiAddress(addr);
        } catch (Exception ignored) {
        }
        return false;
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
