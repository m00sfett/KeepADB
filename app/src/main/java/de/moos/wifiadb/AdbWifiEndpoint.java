package de.moos.wifiadb;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

/** Discovers the active secure wireless-debugging endpoint advertised by adbd. */
final class AdbWifiEndpoint {
    static final String SERVICE_TYPE = "_adb-tls-connect._tcp.";

    interface Listener {
        void onEndpoint(String host, int port);
        void onUnavailable();
    }

    private final NsdManager nsdManager;
    private NsdManager.DiscoveryListener discoveryListener;
    private boolean resolving;

    AdbWifiEndpoint(Context context) {
        nsdManager = (NsdManager) context.getApplicationContext()
                .getSystemService(Context.NSD_SERVICE);
    }

    void discover(Listener listener) {
        if (discoveryListener != null) return;
        stop();
        if (nsdManager == null) {
            listener.onUnavailable();
            return;
        }

        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String serviceType) {
                // Discovery is asynchronous; the first service result supplies the endpoint.
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                if (!sameServiceType(serviceInfo.getServiceType()) || resolving) {
                    return;
                }
                resolving = true;
                nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                    @Override
                    public void onResolveFailed(NsdServiceInfo ignored, int errorCode) {
                        resolving = false;
                        listener.onUnavailable();
                    }

                    @Override
                    public void onServiceResolved(NsdServiceInfo resolved) {
                        resolving = false;
                        if (resolved.getHost() == null || resolved.getPort() <= 0) {
                            listener.onUnavailable();
                            return;
                        }
                        String host = resolved.getHost().getHostAddress();
                        if (host == null || host.isEmpty()) {
                            listener.onUnavailable();
                            return;
                        }
                        listener.onEndpoint(host, resolved.getPort());
                    }
                });
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                listener.onUnavailable();
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                // No action; stop() is also used before replacing a discovery request.
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                stop();
                listener.onUnavailable();
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                // The listener is no longer used after stop() has been requested.
            }
        };

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
    }

    void stop() {
        if (nsdManager != null && discoveryListener != null) {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener);
            } catch (IllegalArgumentException ignored) {
                // Discovery already stopped by the framework.
            }
        }
        discoveryListener = null;
        resolving = false;
    }

    private static boolean sameServiceType(String serviceType) {
        return SERVICE_TYPE.equalsIgnoreCase(serviceType)
                || SERVICE_TYPE.substring(0, SERVICE_TYPE.length() - 1)
                .equalsIgnoreCase(serviceType);
    }
}
