package de.hohnepeople.keepadb;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide view of currently connected networks, replacing the deprecated
 * {@code ConnectivityManager.getAllNetworks()} enumeration (deprecated since API 31) used
 * throughout {@link KeepADBService} and {@link KeepADBEndpoint} (#250).
 *
 * <p>Two {@link ConnectivityManager.NetworkCallback}s each keep their own pair of backing maps:
 * one scoped to {@code TRANSPORT_WIFI} (matching the existing Wi-Fi-scoped request already used
 * elsewhere in the codebase), and one from {@link ConnectivityManager#registerDefaultNetworkCallback}
 * (tracking whatever the current default route is -- cellular, VPN, ethernet, etc.). The two are
 * deliberately kept separate rather than sharing one map: a default-network callback's {@code
 * onLost} fires whenever that network stops being the *default* route, not only when it actually
 * disconnects (e.g. turning on a VPN fires {@code onLost} for Wi-Fi from the default callback's
 * perspective, even though Wi-Fi is still connected and the Wi-Fi-scoped callback never saw its
 * own {@code onLost}). Sharing one map would let that default-route change wrongly evict a
 * still-connected Wi-Fi network's cached data.
 *
 * <p>This combination, rather than a single capabilities-cleared "match everything" request, is
 * also deliberate for a second reason: {@code NetworkRequest.Builder#clearCapabilities()} was
 * added in API 31, but this app's {@code minSdk} is 30, so using it unconditionally would throw
 * {@code NoSuchMethodError} on real API 30 devices. Per the platform contract, {@code
 * onCapabilitiesChanged}/{@code onLinkPropertiesChanged} fire at least once for every network a
 * request matches -- including ones that already existed at registration time -- so no separate
 * {@code onAvailable} bookkeeping is required.
 */
final class KeepADBNetwork {
    private static volatile KeepADBNetwork instance;

    private final ConnectivityManager connectivityManager;
    private final ConnectivityManager.NetworkCallback wifiCallback;
    private final ConnectivityManager.NetworkCallback defaultCallback;
    private final Map<Network, NetworkCapabilities> wifiCapabilities = new ConcurrentHashMap<>();
    private final Map<Network, LinkProperties> wifiLinkProperties = new ConcurrentHashMap<>();
    private final Map<Network, LinkProperties> defaultLinkProperties = new ConcurrentHashMap<>();

    private KeepADBNetwork(Context context) {
        Context appContext = context.getApplicationContext();
        connectivityManager = appContext.getSystemService(ConnectivityManager.class);
        wifiCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                wifiCapabilities.put(network, capabilities);
            }

            @Override
            public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
                wifiLinkProperties.put(network, linkProperties);
            }

            @Override
            public void onLost(Network network) {
                wifiCapabilities.remove(network);
                wifiLinkProperties.remove(network);
            }
        };
        defaultCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
                defaultLinkProperties.put(network, linkProperties);
            }

            @Override
            public void onLost(Network network) {
                defaultLinkProperties.remove(network);
            }
        };
        if (connectivityManager != null) {
            try {
                NetworkRequest wifiRequest = new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .build();
                connectivityManager.registerNetworkCallback(wifiRequest, wifiCallback);
            } catch (RuntimeException ignored) {
                // Best-effort: isWifiConnected()/getWifiIpv4Address()/isKnownLocalAddress()
                // simply see fewer tracked networks if registration fails.
            }
            try {
                connectivityManager.registerDefaultNetworkCallback(defaultCallback);
            } catch (RuntimeException ignored) {
            }
        }
    }

    static synchronized KeepADBNetwork get(Context context) {
        if (instance == null) {
            instance = new KeepADBNetwork(context);
        }
        return instance;
    }

    static synchronized void resetForTesting() {
        if (instance != null && instance.connectivityManager != null) {
            try {
                instance.connectivityManager.unregisterNetworkCallback(instance.wifiCallback);
            } catch (RuntimeException ignored) {
            }
            try {
                instance.connectivityManager.unregisterNetworkCallback(instance.defaultCallback);
            } catch (RuntimeException ignored) {
            }
        }
        instance = null;
    }

    /** Wi-Fi transport, excluding VPN-over-Wi-Fi -- unchanged from the prior getAllNetworks() predicate. */
    static boolean isEligibleWifiTransport(NetworkCapabilities capabilities) {
        return capabilities != null
                && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
    }

    boolean isWifiConnected() {
        for (NetworkCapabilities capabilities : wifiCapabilities.values()) {
            if (isEligibleWifiTransport(capabilities)) {
                return true;
            }
        }
        return false;
    }

    /** First non-loopback, non-link-local IPv4 address bound to an eligible Wi-Fi network, if any. */
    String getWifiIpv4Address() {
        for (Map.Entry<Network, NetworkCapabilities> entry : wifiCapabilities.entrySet()) {
            if (!isEligibleWifiTransport(entry.getValue())) continue;
            LinkProperties linkProperties = wifiLinkProperties.get(entry.getKey());
            if (linkProperties == null) continue;
            for (LinkAddress linkAddress : linkProperties.getLinkAddresses()) {
                InetAddress address = linkAddress.getAddress();
                if (address instanceof Inet4Address && !address.isLoopbackAddress() && !address.isLinkLocalAddress()) {
                    return address.getHostAddress();
                }
            }
        }
        return null;
    }

    /**
     * Whether {@code address} is bound to any currently tracked network -- every Wi-Fi network
     * plus whatever the current default route is (see the class javadoc). This narrows the
     * pre-#250 behavior of checking literally every network Android knows about, in exchange
     * for not requiring API 31; adb-over-Wi-Fi endpoints are expected to resolve to a Wi-Fi
     * network address in practice.
     */
    boolean isKnownLocalAddress(InetAddress address) {
        if (address == null) return false;
        for (LinkProperties linkProperties : wifiLinkProperties.values()) {
            if (containsAddress(linkProperties, address)) return true;
        }
        for (LinkProperties linkProperties : defaultLinkProperties.values()) {
            if (containsAddress(linkProperties, address)) return true;
        }
        return false;
    }

    private static boolean containsAddress(LinkProperties linkProperties, InetAddress address) {
        for (LinkAddress linkAddress : linkProperties.getLinkAddresses()) {
            if (address.equals(linkAddress.getAddress())) {
                return true;
            }
        }
        return false;
    }
}
