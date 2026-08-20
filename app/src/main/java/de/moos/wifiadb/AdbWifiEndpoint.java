package de.moos.wifiadb;

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
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

/** Discovers the active secure wireless-debugging endpoint advertised by adbd. */
final class AdbWifiEndpoint {
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

    AdbWifiEndpoint(Context context) {
        appContext = context.getApplicationContext();
        nsdManager = (NsdManager) appContext.getSystemService(Context.NSD_SERVICE);
        WifiManager wifiManager = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
        if (wifiManager != null) {
            multicastLock = wifiManager.createMulticastLock("de.moos.wifiadb.AdbWifiEndpoint");
            multicastLock.setReferenceCounted(false);
        } else {
            multicastLock = null;
        }
    }

    synchronized void discover(Listener listener) {
        if (discoveryListener != null) {
            return;
        }
        if (nsdManager == null) {
            listener.onUnavailable();
            return;
        }

        if (multicastLock != null && !multicastLock.isHeld()) {
            try {
                multicastLock.acquire();
            } catch (RuntimeException ignored) {
            }
        }

        final long generation = discoveryGeneration;

        // 1. Lokaler Fast-Probe Port-Scan (30000-50000) zur verzögerungsfreien Erkennung
        startFastProbe(generation, listener);

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
                synchronized (AdbWifiEndpoint.this) {
                    if (!isCurrent(generation)) return;
                    resolveQueue.offer(serviceInfo);
                    processNextResolveLocked(generation, listener);
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                if (!isCurrent(generation)) return;
                listener.onUnavailable();
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                // No action; stop() is also used before replacing a discovery request.
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                if (!isCurrent(generation)) return;
                stop();
                listener.onUnavailable();
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                // The listener is no longer used after stop() has been requested.
            }
        };

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        } catch (RuntimeException ignored) {
            if (!isCurrent(generation)) return;
            stop();
            listener.onUnavailable();
        }
    }

    private void cancelResolveWatchdogLocked() {
        if (resolveWatchdogRunnable != null) {
            mainHandler.removeCallbacks(resolveWatchdogRunnable);
            resolveWatchdogRunnable = null;
        }
    }

    private void processNextResolveLocked(long generation, Listener listener) {
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
            synchronized (AdbWifiEndpoint.this) {
                if (!isCurrent(generation) || !resolving) return;
                resolving = false;
                processNextResolveLocked(generation, listener);
            }
        };
        mainHandler.postDelayed(resolveWatchdogRunnable, RESOLVE_TIMEOUT_MS);

        try {
            nsdManager.resolveService(nextService, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo ignored, int errorCode) {
                    synchronized (AdbWifiEndpoint.this) {
                        cancelResolveWatchdogLocked();
                        resolving = false;
                        if (!isCurrent(generation)) return;
                        processNextResolveLocked(generation, listener);
                    }
                }

                @Override
                public void onServiceResolved(NsdServiceInfo resolved) {
                    synchronized (AdbWifiEndpoint.this) {
                        cancelResolveWatchdogLocked();
                        if (!isCurrent(generation)) {
                            resolving = false;
                            return;
                        }
                        if (resolved.getHost() == null || resolved.getPort() <= 0) {
                            resolving = false;
                            processNextResolveLocked(generation, listener);
                            return;
                        }
                        final String host = resolved.getHost().getHostAddress();
                        if (host == null || host.isEmpty()) {
                            resolving = false;
                            processNextResolveLocked(generation, listener);
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
                            synchronized (AdbWifiEndpoint.this) {
                                resolving = false;
                                if (!isCurrent(generation)) return;
                                if (reachable) {
                                    resolveQueue.clear();
                                    stop();
                                    listener.onEndpoint(host, port);
                                } else {
                                    processNextResolveLocked(generation, listener);
                                }
                            }
                        }, "AdbWifiEndpointCheck").start();
                    }
                }
            });
        } catch (RuntimeException ignored) {
            cancelResolveWatchdogLocked();
            resolving = false;
            processNextResolveLocked(generation, listener);
        }
    }

    synchronized void stop() {
        discoveryGeneration++;
        cancelResolveWatchdogLocked();
        resolveQueue.clear();
        resolving = false;
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
        return discoveryListener != null && discoveryGeneration == generation;
    }

    private void startFastProbe(long generation, Listener listener) {
        final String wifiIp = getWifiIpAddress(appContext);
        if (wifiIp == null) {
            return;
        }
        new Thread(() -> {
            if (!isCurrent(generation)) return;
            final int totalPorts = PROBE_END_PORT - PROBE_START_PORT + 1;
            final int chunkSize = (totalPorts + PROBE_THREADS - 1) / PROBE_THREADS;
            final AtomicBoolean found = new AtomicBoolean(false);

            for (int i = 0; i < PROBE_THREADS; i++) {
                final int start = PROBE_START_PORT + i * chunkSize;
                final int end = Math.min(start + chunkSize - 1, PROBE_END_PORT);
                new Thread(() -> {
                    for (int port = start; port <= end; port++) {
                        if (found.get() || !isCurrent(generation)) {
                            break;
                        }
                        boolean loopbackOpen = false;
                        try (Socket s = new Socket()) {
                            s.connect(new InetSocketAddress("127.0.0.1", port), 50);
                            loopbackOpen = true;
                        } catch (Exception ignored) {
                        }
                        if (loopbackOpen && !found.get() && isCurrent(generation)) {
                            boolean wifiOpen = false;
                            try (Socket s = new Socket()) {
                                s.connect(new InetSocketAddress(wifiIp, port), 300);
                                wifiOpen = true;
                            } catch (Exception ignored) {
                            }
                            if (wifiOpen && found.compareAndSet(false, true)) {
                                synchronized (AdbWifiEndpoint.this) {
                                    if (!isCurrent(generation)) return;
                                    resolveQueue.clear();
                                    stop();
                                    listener.onEndpoint(wifiIp, port);
                                }
                                break;
                            }
                        }
                    }
                }, "AdbWifiFastProbe-" + i).start();
            }
        }, "AdbWifiFastProbeCoordinator").start();
    }

    static String getWifiIpAddress(Context context) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        if (cm == null) return null;
        Network activeNetwork = cm.getActiveNetwork();
        if (activeNetwork == null) return null;
        NetworkCapabilities caps = cm.getNetworkCapabilities(activeNetwork);
        if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null;
        LinkProperties lp = cm.getLinkProperties(activeNetwork);
        if (lp == null) return null;
        for (LinkAddress la : lp.getLinkAddresses()) {
            InetAddress addr = la.getAddress();
            if (addr instanceof Inet4Address && !addr.isLoopbackAddress() && !addr.isLinkLocalAddress()) {
                return addr.getHostAddress();
            }
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
