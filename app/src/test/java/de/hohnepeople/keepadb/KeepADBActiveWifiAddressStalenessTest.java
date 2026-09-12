package de.hohnepeople.keepadb;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.InetAddress;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowConnectivityManager;
import org.robolectric.shadows.ShadowNetwork;
import org.robolectric.shadows.ShadowNetworkCapabilities;
import org.robolectric.shadows.ShadowWifiInfo;

/**
 * Regression coverage for issue #390 (follow-up finding from #352): {@link
 * KeepADBNetwork#isActiveWifiAddress(java.net.InetAddress)} decides whether a candidate is
 * accepted as a wireless-debugging endpoint. The synchronous {@code WifiInfo} snapshot behind it
 * is known to keep reporting a just-dropped address, so it may only widen the candidate set while
 * the tracker's own callback view is not yet authoritative -- never once the registered callback
 * has actually reported that no eligible Wi-Fi network is left.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBActiveWifiAddressStalenessTest {

    private static final String WIFI_IPV4 = "192.168.7.42";

    @After
    public void tearDown() {
        KeepADBNetwork.resetForTesting();
    }

    /**
     * AC1: callback registered and authoritatively negative (the network was tracked and then
     * lost) -- a stale WifiInfo snapshot still naming the just-dropped address must not get that
     * address accepted as an endpoint candidate.
     */
    @Test
    public void staleWifiInfoIsRejectedOnceTheCallbackAuthoritativelyReportsNoWifi() throws Exception {
        KeepADBNetwork network = KeepADBNetwork.get(context());
        Network wifiNetwork = ShadowNetwork.newInstance(6001);

        deliverWifiNetwork(wifiNetwork, WIFI_IPV4);
        assertTrue("Precondition: while the network is tracked the address is a valid candidate",
                network.isActiveWifiAddress(InetAddress.getByName(WIFI_IPV4)));

        deliverToAllCallbacks(callback -> callback.onLost(wifiNetwork));
        // WifiManager keeps handing out the just-dropped address for a moment after onLost.
        setSynchronousWifiConnectionInfo(WIFI_IPV4);

        assertFalse("A registered callback that has reported the loss is authoritative -- the "
                        + "stale synchronous WifiInfo address must not be accepted any more",
                network.isActiveWifiAddress(InetAddress.getByName(WIFI_IPV4)));
    }

    /**
     * AC2 (startup race): the callback is registered but has not fired yet, so the empty tracked
     * maps mean "nothing known yet", not "no Wi-Fi". The synchronous snapshot must still cover
     * our own address here.
     */
    @Test
    public void synchronousAddressStillCoversTheStartupRaceBeforeTheFirstCallback() throws Exception {
        KeepADBNetwork network = KeepADBNetwork.get(context());
        setSynchronousWifiConnectionInfo(WIFI_IPV4);

        assertTrue("Before the first callback has fired the tracker knows nothing, so our own "
                        + "synchronous Wi-Fi address must still be accepted",
                network.isActiveWifiAddress(InetAddress.getByName(WIFI_IPV4)));
    }

    /**
     * AC2 (registration failure): with no working callback the tracker can never observe
     * anything, so the synchronous fallback stays active indefinitely.
     */
    @Test
    public void synchronousFallbackStaysActiveWhenTheCallbackFailedToRegister() throws Exception {
        KeepADBNetwork network = KeepADBNetwork.get(context());
        KeepADBNetwork.setWifiCallbackRegisteredOverrideForTesting(false);
        // Even a delivered callback cannot make an unregistered tracker authoritative.
        deliverToAllCallbacks(callback -> callback.onLost(ShadowNetwork.newInstance(6002)));
        setSynchronousWifiConnectionInfo(WIFI_IPV4);

        assertTrue("Without a registered callback the synchronous WifiInfo snapshot is the only "
                        + "signal left and must keep widening the candidate set",
                network.isActiveWifiAddress(InetAddress.getByName(WIFI_IPV4)));
    }

    /** The ordinary case: an actively tracked Wi-Fi network's address is accepted unchanged. */
    @Test
    public void trackedWifiAddressIsAcceptedWithoutAnySynchronousSnapshot() throws Exception {
        KeepADBNetwork network = KeepADBNetwork.get(context());

        deliverWifiNetwork(ShadowNetwork.newInstance(6003), WIFI_IPV4);

        assertTrue("A live, callback-tracked Wi-Fi address stays a valid endpoint candidate",
                network.isActiveWifiAddress(InetAddress.getByName(WIFI_IPV4)));
        assertFalse("An address of no tracked network must still be rejected",
                network.isActiveWifiAddress(InetAddress.getByName("192.168.7.99")));
    }

    private static void deliverWifiNetwork(Network network, String ipv4) throws Exception {
        NetworkCapabilities capabilities = ShadowNetworkCapabilities.newInstance();
        shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
        LinkProperties linkProperties = linkPropertiesWithIpv4(ipv4);
        deliverToAllCallbacks(callback -> callback.onCapabilitiesChanged(network, capabilities));
        deliverToAllCallbacks(callback -> callback.onLinkPropertiesChanged(network, linkProperties));
    }

    private static void setSynchronousWifiConnectionInfo(String ipv4) throws Exception {
        WifiManager wifiManager = (WifiManager) context().getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = ShadowWifiInfo.newInstance();
        shadowOf(info).setInetAddress(InetAddress.getByName(ipv4));
        shadowOf(wifiManager).setConnectionInfo(info);
    }

    private static Context context() {
        return RuntimeEnvironment.getApplication();
    }

    /**
     * See {@code KeepADBNetworkRobustnessBehaviorTest}: the compile-time Android stub jar exposes
     * neither {@link LinkAddress}'s {@code String} constructor nor {@code
     * LinkProperties.addLinkAddress}, and Robolectric ships no shadow seam for either, so both
     * are driven reflectively against the real framework classes loaded at test run time.
     */
    private static LinkProperties linkPropertiesWithIpv4(String ipv4) throws Exception {
        Constructor<LinkAddress> constructor = LinkAddress.class.getConstructor(String.class);
        LinkAddress address = constructor.newInstance(ipv4 + "/24");
        LinkProperties properties = new LinkProperties();
        Method addLinkAddress = LinkProperties.class.getMethod("addLinkAddress", LinkAddress.class);
        addLinkAddress.invoke(properties, address);
        return properties;
    }

    private interface CallbackAction {
        void deliver(ConnectivityManager.NetworkCallback callback);
    }

    private static void deliverToAllCallbacks(CallbackAction action) {
        ConnectivityManager connectivityManager = context().getSystemService(ConnectivityManager.class);
        ShadowConnectivityManager shadow = shadowOf(connectivityManager);
        for (ConnectivityManager.NetworkCallback callback : shadow.getNetworkCallbacks()) {
            action.deliver(callback);
        }
    }
}
