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
 * <p>A single {@link ConnectivityManager.NetworkCallback} is registered against a request
 * with capabilities cleared (matches every network, not just internet-validated ones, since
 * Wireless Debugging over a purely local Wi-Fi network never carries validated internet
 * capability). Per the platform contract, {@code onCapabilitiesChanged}/{@code
 * onLinkPropertiesChanged} fire at least once for every network the request matches --
 * including ones that already existed at registration time -- so no separate {@code
 * onAvailable} bookkeeping is required; {@code onLost} removes the entry.
 */
final class KeepADBNetwork {
    private static volatile KeepADBNetwork instance;

    private final ConnectivityManager connectivityManager;
    private final ConnectivityManager.NetworkCallback callback;
    private final Map<Network, NetworkCapabilities> capabilitiesByNetwork = new ConcurrentHashMap<>();
    private final Map<Network, LinkProperties> linkPropertiesByNetwork = new ConcurrentHashMap<>();

    private KeepADBNetwork(Context context) {
        Context appContext = context.getApplicationContext();
        connectivityManager = appContext.getSystemService(ConnectivityManager.class);
        callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                capabilitiesByNetwork.put(network, capabilities);
            }

            @Override
            public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
                linkPropertiesByNetwork.put(network, linkProperties);
            }

            @Override
            public void onLost(Network network) {
                capabilitiesByNetwork.remove(network);
                linkPropertiesByNetwork.remove(network);
            }
        };
        if (connectivityManager != null) {
            try {
                NetworkRequest request = new NetworkRequest.Builder().clearCapabilities().build();
                connectivityManager.registerNetworkCallback(request, callback);
            } catch (RuntimeException ignored) {
                // Best-effort: isWifiConnected()/getWifiIpv4Address()/isKnownLocalAddress()
                // simply see no tracked networks if registration fails.
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
                instance.connectivityManager.unregisterNetworkCallback(instance.callback);
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
        for (NetworkCapabilities capabilities : capabilitiesByNetwork.values()) {
            if (isEligibleWifiTransport(capabilities)) {
                return true;
            }
        }
        return false;
    }

    /** First non-loopback, non-link-local IPv4 address bound to an eligible Wi-Fi network, if any. */
    String getWifiIpv4Address() {
        for (Map.Entry<Network, NetworkCapabilities> entry : capabilitiesByNetwork.entrySet()) {
            if (!isEligibleWifiTransport(entry.getValue())) continue;
            LinkProperties linkProperties = linkPropertiesByNetwork.get(entry.getKey());
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

    /** Whether {@code address} is bound to any currently tracked network, of any transport. */
    boolean isKnownLocalAddress(InetAddress address) {
        if (address == null) return false;
        for (LinkProperties linkProperties : linkPropertiesByNetwork.values()) {
            for (LinkAddress linkAddress : linkProperties.getLinkAddresses()) {
                if (address.equals(linkAddress.getAddress())) {
                    return true;
                }
            }
        }
        return false;
    }
}
