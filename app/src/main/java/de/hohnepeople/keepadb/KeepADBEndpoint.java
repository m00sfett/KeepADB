package de.hohnepeople.keepadb;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.Enumeration;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

/** Discovers the active secure wireless-debugging endpoint advertised by adbd. */
final class KeepADBEndpoint {
    static final String SERVICE_TYPE = "_adb-tls-connect._tcp.";
    private static final long RESOLVE_TIMEOUT_MS = 1500;
    private static final int PROBE_START_PORT = 30000;
    private static final int PROBE_END_PORT = 50000;
    private static final int PROBE_THREADS = 16;

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
        if (nsdManager == null) {
            this.currentListener = null;
            if (listener != null) {
                listener.onUnavailable();
            }
            return;
        }

        if (discovering) {
            return;
        }
        discovering = true;

        if (multicastLock != null && !multicastLock.isHeld()) {
            try {
                multicastLock.acquire();
            } catch (RuntimeException ignored) {
            }
        }

        final long generation = discoveryGeneration;

        // 1. Lokaler Fast-Probe Port-Scan (30000-50000) zur verzögerungsfreien Erkennung
        startFastProbe(generation);

        // 2. mDNS-Discovery als robuster Standard-/Fallback-Pfad
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String serviceType) {
                // Discovery is asynchronous; the first reachable service result supplies the endpoint.
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                if (!isCurrent(generation) || !sameServiceType(serviceInfo.getServiceType())) {
                    return;
                }
                synchronized (KeepADBEndpoint.this) {
                    if (!isCurrent(generation)) return;
                    resolveQueue.offer(serviceInfo);
                    processNextResolveLocked(generation);
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                // Ignore service lost so running fast-probe or valid endpoints are not disrupted.
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                // No action; stop() is also used before replacing a discovery request.
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                // If mDNS discovery start fails, fast-probe continues unaffected.
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                // The listener is no longer used after stop() has been requested.
            }
        };

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        } catch (RuntimeException ignored) {
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
        if (resolving || resolveQueue.isEmpty() || !isCurrent(generation)) {
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
                if (!isCurrent(generation) || !resolving) return;
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
                        if (!isCurrent(generation)) return;
                        processNextResolveLocked(generation);
                    }
                }

                @Override
                public void onServiceResolved(NsdServiceInfo resolved) {
                    synchronized (KeepADBEndpoint.this) {
                        cancelResolveWatchdogLocked();
                        if (!isCurrent(generation)) {
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
                            boolean reachable = false;
                            try (Socket socket = new Socket()) {
                                socket.connect(new InetSocketAddress(addr, port), 600);
                                reachable = true;
                            } catch (Exception ignored) {
                            }
                            Listener targetListener = null;
                            synchronized (KeepADBEndpoint.this) {
                                resolving = false;
                                if (!isCurrent(generation)) return;
                                if (reachable) {
                                    resolveQueue.clear();
                                    targetListener = currentListener;
                                    stop();
                                } else {
                                    processNextResolveLocked(generation);
                                }
                            }
                            if (targetListener != null) {
                                targetListener.onEndpoint(host, port);
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
                // Discovery already stopped by the framework.
            }
        }
    }

    private synchronized boolean isCurrent(long generation) {
        return discoveryGeneration == generation;
    }

    private void startFastProbe(long generation) {
        coordinatorThread = new Thread(() -> {
            final AtomicBoolean found = new AtomicBoolean(false);
            final int maxAttempts = 15;

            for (int attempt = 0; attempt < maxAttempts; attempt++) {
                if (!isCurrent(generation) || found.get() || Thread.currentThread().isInterrupted()) return;

                final String wifiIp = getWifiIpAddress(appContext);
                if (wifiIp == null) {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException e) {
                        return;
                    }
                    continue;
                }

                final InetAddress probeAddr;
                try {
                    probeAddr = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
                } catch (Exception e) {
                    continue;
                }

                final int totalPorts = PROBE_END_PORT - PROBE_START_PORT + 1;
                final int chunkSize = (totalPorts + PROBE_THREADS - 1) / PROBE_THREADS;
                final Thread[] workers = new Thread[PROBE_THREADS];

                for (int i = 0; i < PROBE_THREADS; i++) {
                    final int start = PROBE_START_PORT + i * chunkSize;
                    final int end = Math.min(start + chunkSize - 1, PROBE_END_PORT);
                    workers[i] = new Thread(() -> {
                        for (int port = start; port <= end; port++) {
                            if (found.get() || !isCurrent(generation) || Thread.currentThread().isInterrupted()) {
                                break;
                            }
                            boolean open = false;
                            try (Socket s = new Socket()) {
                                s.connect(new InetSocketAddress(probeAddr, port), 25);
                                open = true;
                            } catch (Exception ignored) {
                            }
                            if (open && found.compareAndSet(false, true)) {
                                Listener targetListener = null;
                                synchronized (KeepADBEndpoint.this) {
                                    if (isCurrent(generation)) {
                                        resolveQueue.clear();
                                        targetListener = currentListener;
                                        stop();
                                    }
                                }
                                if (targetListener != null) {
                                    targetListener.onEndpoint(wifiIp, port);
                                }
                                break;
                            }
                        }
                    }, "KeepADBFastProbe-" + i);
                    workers[i].start();
                }

                for (Thread worker : workers) {
                    try {
                        worker.join();
                    } catch (InterruptedException e) {
                        for (Thread w : workers) {
                            if (w != null) w.interrupt();
                        }
                        return;
                    }
                }

                if (found.get() || !isCurrent(generation) || Thread.currentThread().isInterrupted()) {
                    return;
                }

                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    return;
                }
            }

            if (!found.get() && isCurrent(generation)) {
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

    static String getWifiIpAddress(Context context) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        if (cm != null) {
            for (Network network : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
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
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en != null && en.hasMoreElements();) {
                NetworkInterface intf = en.nextElement();
                if (intf.isLoopback() || !intf.isUp()) continue;
                String name = intf.getName();
                if (name != null && (name.startsWith("wlan") || name.startsWith("ap"))) {
                    for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements();) {
                        InetAddress inetAddress = enumIpAddr.nextElement();
                        if (inetAddress instanceof Inet4Address && !inetAddress.isLoopbackAddress() && !inetAddress.isLinkLocalAddress()) {
                            return inetAddress.getHostAddress();
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
