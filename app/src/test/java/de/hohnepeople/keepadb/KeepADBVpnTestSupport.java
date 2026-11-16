package de.hohnepeople.keepadb;

import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.robolectric.shadows.ShadowNetwork;
import org.robolectric.shadows.ShadowNetworkCapabilities;

/**
 * #676: {@link KeepADBVpnTransport} reads VPN networks from {@link KeepADBNetwork}'s callback
 * tracking, so tests announce a VPN network the way the framework does: by delivering it to the
 * tracker's VPN callback. Merely adding it to the shadow {@code ConnectivityManager} is invisible
 * to callback tracking.
 */
final class KeepADBVpnTestSupport {
    private KeepADBVpnTestSupport() {}

    static Network deliverVpnNetwork(Context context, int netId, LinkProperties linkProperties) {
        Network network = ShadowNetwork.newInstance(netId);
        NetworkCapabilities capabilities = ShadowNetworkCapabilities.newInstance();
        shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_VPN);
        deliver(context, network, capabilities, linkProperties);
        return network;
    }

    static void deliver(Context context, Network network, NetworkCapabilities capabilities,
            LinkProperties linkProperties) {
        android.net.ConnectivityManager.NetworkCallback callback =
                KeepADBNetwork.get(context).vpnCallbackForTesting();
        callback.onCapabilitiesChanged(network, capabilities);
        callback.onLinkPropertiesChanged(network, linkProperties);
    }

    static void lose(Context context, Network network) {
        KeepADBNetwork.get(context).vpnCallbackForTesting().onLost(network);
    }

    /**
     * A VPN with a reachable ADB port next to a verified WLAN endpoint, the way the transport
     * overview reports it: the WLAN-discovered port is reused as the VPN-side probe target, so
     * the coordinator needs its own endpoint first. Undo with {@link #resetVerifiedVpn}.
     */
    static void deliverVerifiedVpn(Context context, String wlanHost, int wlanPort, String vpnIp)
            throws Exception {
        seedWlanEndpoint(wlanHost, wlanPort, 1_700_000_000_000L);
        deliverVpnNetwork(context, 9001, linkPropertiesWithIpv4(vpnIp));
        KeepADBVpnTransport.setReachabilityProbeForTesting((host, port, timeoutMs) -> true);
    }

    static void resetVerifiedVpn() throws Exception {
        KeepADBVpnTransport.setReachabilityProbeForTesting(null);
        KeepADBEndpointCoordinator.resetForTesting();
        seedWlanEndpoint(null, 0, 0L);
    }

    static void seedWlanEndpoint(String host, int port, long verifiedAtMs) throws Exception {
        setCoordinatorStatic("currentHost", host);
        setCoordinatorStatic("currentPort", port);
        setCoordinatorStatic("currentEndpointVerifiedAtMs", verifiedAtMs);
    }

    private static void setCoordinatorStatic(String fieldName, Object value) throws Exception {
        Field field = KeepADBEndpointCoordinator.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, value);
    }

    /** The compile-time Android stub exposes neither {@link android.net.LinkAddress}'s
     * {@code String} constructor nor {@code LinkProperties.addLinkAddress}; both are driven
     * reflectively against the real framework classes loaded at test run time. */
    static LinkProperties linkPropertiesWithIpv4(String ipv4) throws Exception {
        Constructor<android.net.LinkAddress> constructor =
                android.net.LinkAddress.class.getConstructor(String.class);
        android.net.LinkAddress address = constructor.newInstance(ipv4 + "/32");
        LinkProperties properties = new LinkProperties();
        Method addLinkAddress = LinkProperties.class.getMethod(
                "addLinkAddress", android.net.LinkAddress.class);
        addLinkAddress.invoke(properties, address);
        return properties;
    }
}
