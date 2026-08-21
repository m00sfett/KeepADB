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
 * Uses ultra-fast parallel local NIO port probing (30000-50000) combined with background mDNS.
 */
final class KeepADBEndpoint {
    private static final String TAG = "KeepADBEndpoint";
    static final String SERVICE_TYPE = "_adb-tls-connect._tcp.";
    private static final long RESOLVE_TIMEOUT_MS = 1500;
    static final int PROBE_START_PORT = 30000;
    static final int PROBE_END_PORT = 50000;
    private static final int SCAN_WORKERS = 8;
    private static final java.util.concurrent.ExecutorService SCAN_EXECUTOR = java.util.concurrent.Executors.newFixedThreadPool(SCAN_WORKERS, r -> {
        Thread t = new Thread(r, "KeepADBScanWorker");
        t.setDaemon(true);
        return t;
    });

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

        // 1. Ultra-fast parallel NIO Port-Scan (30000-50000)
        startFastProbe(generation);

        // 2. Parallel mDNS-Discovery als robuster Fallback-Pfad
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

    private void startFastProbe(long generation) {
        coordinatorThread = new Thread(() -> {
            Log.d(TAG, "startFastProbe gen=" + generation + " started");
            final int maxTotalSeconds = 45;
            final long startTime = System.currentTimeMillis();

            while (isCurrent(generation) && !endpointDelivered.get() && !Thread.currentThread().isInterrupted()) {
                if (System.currentTimeMillis() - startTime > maxTotalSeconds * 1000L) {
                    Log.w(TAG, "startFastProbe gen=" + generation + " timed out");
                    break;
                }

                final String wifiIp = getWifiIpAddress(appContext);
                final List<Integer> openPorts = scanLocalOpenPortsParallel(PROBE_START_PORT, PROBE_END_PORT, SCAN_WORKERS, generation);
                Log.d(TAG, "FastProbe gen=" + generation + " iteration: wifiIp=" + wifiIp + ", openPorts=" + openPorts);

                if (!openPorts.isEmpty()) {
                    String targetHost = wifiIp;
                    if (targetHost == null) {
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException e) {
                            return;
                        }
                        targetHost = getWifiIpAddress(appContext);
                    }

                    if (targetHost != null) {
                        final byte[] loopbackBytes = new byte[]{127, 0, 0, 1};
                        try {
                            final InetAddress loopbackAddr = InetAddress.getByAddress(loopbackBytes);
                            for (int candidatePort : openPorts) {
                                boolean reachable = isPortReachable(loopbackAddr, candidatePort, 50);
                                Log.d(TAG, "Testing candidate " + candidatePort + " on loopback: " + reachable);
                                if (reachable) {
                                    if (endpointDelivered.compareAndSet(false, true)) {
                                        Log.i(TAG, "FastProbe verified live ADB endpoint: " + targetHost + ":" + candidatePort);
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
                                        return;
                                    }
                                }
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "Error in candidate verification", e);
                        }
                    }
                }

                try {
                    Thread.sleep(150);
                } catch (InterruptedException e) {
                    return;
                }
            }

            if (!endpointDelivered.get() && isCurrent(generation)) {
                Listener targetListener = null;
                synchronized (KeepADBEndpoint.this) {
                    if (isCurrent(generation)) {
                        targetListener = currentListener;
                        stop();
                    }
                }
                if (targetListener != null) {
                    targetListener.onUnavailable();
                }
            }
        }, "KeepADBFastProbeCoordinator");
        coordinatorThread.start();
    }

    private List<Integer> scanLocalOpenPortsParallel(int startPort, int endPort, int numWorkers, long generation) {
        final int totalPorts = endPort - startPort + 1;
        final int chunkSize = (totalPorts + numWorkers - 1) / numWorkers;
        final List<java.util.concurrent.Future<List<Integer>>> futures = new ArrayList<>(numWorkers);

        for (int i = 0; i < numWorkers; i++) {
            final int chunkStart = startPort + i * chunkSize;
            final int chunkEnd = Math.min(chunkStart + chunkSize - 1, endPort);
            futures.add(SCAN_EXECUTOR.submit(() -> scanLocalOpenPorts(chunkStart, chunkEnd, generation)));
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

    private List<Integer> scanLocalOpenPorts(int startPort, int endPort, long generation) {
        List<Integer> openPorts = new ArrayList<>();
        final byte[] loopbackBytes = new byte[]{127, 0, 0, 1};
        final InetAddress loopback;
        try {
            loopback = InetAddress.getByAddress(loopbackBytes);
        } catch (Exception e) {
            return openPorts;
        }

        for (int port = startPort; port <= endPort; port++) {
            if (!isCurrent(generation) || endpointDelivered.get() || Thread.currentThread().isInterrupted()) {
                return openPorts;
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(loopback, port));
                openPorts.add(port);
            } catch (Exception ignored) {
            }
        }
        return openPorts;
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
