package de.hohnepeople.keepadb;

import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

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
}
