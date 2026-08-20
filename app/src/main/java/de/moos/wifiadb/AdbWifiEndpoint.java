package de.moos.wifiadb;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.Queue;

/** Discovers the active secure wireless-debugging endpoint advertised by adbd. */
final class AdbWifiEndpoint {
    static final String SERVICE_TYPE = "_adb-tls-connect._tcp.";

    interface Listener {
        void onEndpoint(String host, int port);
        void onUnavailable();
    }

    private final NsdManager nsdManager;
    private final WifiManager.MulticastLock multicastLock;
    private NsdManager.DiscoveryListener discoveryListener;
    private final Queue<NsdServiceInfo> resolveQueue = new ArrayDeque<>();
    private boolean resolving;
    private long discoveryGeneration;

    AdbWifiEndpoint(Context context) {
        Context appContext = context.getApplicationContext();
        nsdManager = (NsdManager) appContext.getSystemService(Context.NSD_SERVICE);
        WifiManager wifiManager = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
        if (wifiManager != null) {
            multicastLock = wifiManager.createMulticastLock("de.moos.wifiadb.AdbWifiEndpoint");
            multicastLock.setReferenceCounted(false);
        } else {
            multicastLock = null;
        }
    }

    void discover(Listener listener) {
        stop();
        if (nsdManager == null) {
            listener.onUnavailable();
            return;
        }

        if (multicastLock != null) {
            try {
                multicastLock.acquire();
            } catch (RuntimeException ignored) {
            }
        }

        final long generation = discoveryGeneration;

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

    private void processNextResolveLocked(long generation, Listener listener) {
        if (resolving || resolveQueue.isEmpty() || !isCurrent(generation)) {
            return;
        }
        final NsdServiceInfo nextService = resolveQueue.poll();
        if (nextService == null) {
            return;
        }
        resolving = true;
        try {
            nsdManager.resolveService(nextService, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo ignored, int errorCode) {
                    synchronized (AdbWifiEndpoint.this) {
                        resolving = false;
                        if (!isCurrent(generation)) return;
                        processNextResolveLocked(generation, listener);
                    }
                }

                @Override
                public void onServiceResolved(NsdServiceInfo resolved) {
                    if (!isCurrent(generation)) {
                        synchronized (AdbWifiEndpoint.this) {
                            resolving = false;
                        }
                        return;
                    }
                    if (resolved.getHost() == null || resolved.getPort() <= 0) {
                        synchronized (AdbWifiEndpoint.this) {
                            resolving = false;
                            if (!isCurrent(generation)) return;
                            processNextResolveLocked(generation, listener);
                        }
                        return;
                    }
                    final String host = resolved.getHost().getHostAddress();
                    if (host == null || host.isEmpty()) {
                        synchronized (AdbWifiEndpoint.this) {
                            resolving = false;
                            if (!isCurrent(generation)) return;
                            processNextResolveLocked(generation, listener);
                        }
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
                                listener.onEndpoint(host, port);
                            } else {
                                processNextResolveLocked(generation, listener);
                            }
                        }
                    }, "AdbWifiEndpointCheck").start();
                }
            });
        } catch (RuntimeException ignored) {
            resolving = false;
            processNextResolveLocked(generation, listener);
        }
    }

    synchronized void stop() {
        discoveryGeneration++;
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
