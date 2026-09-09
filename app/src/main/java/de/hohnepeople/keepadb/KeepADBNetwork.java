package de.hohnepeople.keepadb;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
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
 * <p>Since #314 the default network's link properties are tracked but deliberately not consulted
 * by any endpoint decision: a wireless-debugging endpoint is only ever valid on an address bound
 * to an eligible Wi-Fi network of this device (see {@link #isActiveWifiAddress(InetAddress)}),
 * never on the current default route when that route is cellular, VPN, USB tethering or ethernet.
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
    // #303: test-only override for isWifiConnected(), see KeepADBWifiProbe's javadoc for why it
    // lives here instead of on KeepADBService/KeepADBNotification. null in production, where
    // isWifiConnected() always falls through to the real transport-capability check below.
    private static volatile KeepADBWifiProbe wifiConnectivityOverride;

    private final Context appContext;
    private final ConnectivityManager connectivityManager;
    private final ConnectivityManager.NetworkCallback wifiCallback;
    private final ConnectivityManager.NetworkCallback defaultCallback;
    private final Map<Network, NetworkCapabilities> wifiCapabilities = new ConcurrentHashMap<>();
    private final Map<Network, LinkProperties> wifiLinkProperties = new ConcurrentHashMap<>();
    private final Map<Network, LinkProperties> defaultLinkProperties = new ConcurrentHashMap<>();

    private KeepADBNetwork(Context context) {
        appContext = context.getApplicationContext();
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
                // Best-effort: isWifiConnected()/getWifiIpv4Address()/isActiveWifiAddress()
                // simply see fewer tracked networks if registration fails -- which for
                // isActiveWifiAddress() means rejecting candidates, never accepting one.
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
        wifiConnectivityOverride = null;
    }

    /**
     * Test-only seam (#303): replaces {@link #isWifiConnected()}'s real transport-capability
     * check with {@code override}, or restores it when passed {@code null}. See {@link
     * KeepADBWifiProbe}'s javadoc for why this lives here rather than as a seam on {@code
     * KeepADBService}/{@code KeepADBNotification}.
     */
    static void setWifiConnectivityOverrideForTesting(KeepADBWifiProbe override) {
        wifiConnectivityOverride = override;
    }

    /** Wi-Fi transport, excluding VPN-over-Wi-Fi -- unchanged from the prior getAllNetworks() predicate. */
    static boolean isEligibleWifiTransport(NetworkCapabilities capabilities) {
        return capabilities != null
                && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
    }

    boolean isWifiConnected() {
        KeepADBWifiProbe override = wifiConnectivityOverride;
        if (override != null) {
            return override.isWifiConnected();
        }
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
     * Whether {@code address} is one of the addresses bound to an <em>eligible Wi-Fi network of
     * this device right now</em> (#314) -- the only addresses a genuine wireless-debugging
     * endpoint of ours can be reached on via {@code adb connect <host>:<port>}.
     *
     * <p>This deliberately replaces the former {@code isKnownLocalAddress()}, which also
     * accepted addresses of the current default route (cellular, VPN, USB tethering, ethernet)
     * and, together with {@link KeepADBEndpoint}'s blanket loopback/link-local shortcut, let any
     * unrelated local TCP listener pass as an endpoint candidate. Two properties matter here:
     *
     * <ul>
     *   <li><b>Bound, not merely "reachable" or "in our subnet":</b> the candidate must equal an
     *       address the Wi-Fi interface itself holds. A neighbour's address on the same subnet,
     *       another device's IPv6 link-local, or a service on our own cellular/VPN interface is
     *       rejected.</li>
     *   <li><b>Fail-closed:</b> with no eligible Wi-Fi network tracked and no synchronous Wi-Fi
     *       IP available (e.g. Wi-Fi off), the candidate set is empty and every candidate is
     *       rejected rather than optimistically accepted.</li>
     * </ul>
     *
     * <p>The synchronous {@link WifiManager} address is kept as an additional candidate because
     * {@code onCapabilitiesChanged}/{@code onLinkPropertiesChanged} populate the maps
     * asynchronously: a query made immediately after the first-ever {@link #get} call in a
     * process (before either callback has fired) would otherwise wrongly treat our own Wi-Fi
     * address as foreign.
     *
     * <p>Note what this does <em>not</em> establish: that whatever listens on the port is really
     * adbd rather than some other service the device itself exposes on its Wi-Fi address. That
     * remains open follow-up work (adbd authenticity, R13 on #314) and is out of scope here.
     */
    boolean isActiveWifiAddress(InetAddress address) {
        return matchesActiveWifiAddress(address, activeWifiAddresses());
    }

    /** Addresses currently bound to an eligible (non-VPN) Wi-Fi network of this device. */
    private List<InetAddress> activeWifiAddresses() {
        List<InetAddress> addresses = new ArrayList<>();
        for (Map.Entry<Network, NetworkCapabilities> entry : wifiCapabilities.entrySet()) {
            if (!isEligibleWifiTransport(entry.getValue())) continue;
            LinkProperties linkProperties = wifiLinkProperties.get(entry.getKey());
            if (linkProperties == null) continue;
            for (LinkAddress linkAddress : linkProperties.getLinkAddresses()) {
                InetAddress address = linkAddress.getAddress();
                if (address != null) {
                    addresses.add(address);
                }
            }
        }
        InetAddress synchronousAddress = synchronousWifiAddress();
        if (synchronousAddress != null) {
            addresses.add(synchronousAddress);
        }
        return addresses;
    }

    /**
     * Pure decision behind {@link #isActiveWifiAddress(InetAddress)}, separated so it is
     * testable without a real {@link ConnectivityManager}. Loopback, wildcard and multicast
     * addresses are rejected outright: they are never a usable {@code adb connect} target from
     * another host, so accepting one could only ever register a local service of some other
     * kind.
     */
    static boolean matchesActiveWifiAddress(InetAddress candidate, List<InetAddress> activeWifiAddresses) {
        if (candidate == null || activeWifiAddresses == null) return false;
        if (candidate.isLoopbackAddress() || candidate.isAnyLocalAddress()
                || candidate.isMulticastAddress()) {
            return false;
        }
        for (InetAddress address : activeWifiAddresses) {
            if (candidate.equals(address)) return true;
        }
        return false;
    }

    private InetAddress synchronousWifiAddress() {
        try {
            WifiManager wifiManager = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
            if (wifiManager == null) return null;
            WifiInfo info = wifiManager.getConnectionInfo();
            if (info == null) return null;
            int ip = info.getIpAddress();
            if (ip == 0) return null;
            byte[] bytes = {(byte) ip, (byte) (ip >> 8), (byte) (ip >> 16), (byte) (ip >> 24)};
            return InetAddress.getByAddress(bytes);
        } catch (Exception ignored) {
            return null;
        }
    }
}
