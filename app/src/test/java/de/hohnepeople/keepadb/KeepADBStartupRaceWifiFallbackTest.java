package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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
import org.junit.Rule;
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
 * Regression coverage for issue #396: two readers of {@link KeepADBNetwork}'s Wi-Fi state --
 * {@link KeepADBNetwork#getWifiIpv4Address()} and {@link
 * KeepADBService#isWifiConnected(Context)} -- must fall back to a synchronous {@link WifiInfo}
 * snapshot in exactly the same startup-race window {@link KeepADBNetwork#isWifiTrackingAuthoritative()}
 * was introduced for in #390: the Wi-Fi callback is registered but has not fired yet, so the
 * empty tracked maps mean "nothing known yet", not "no Wi-Fi".
 *
 * <p>Both windows are covered in each direction: the pre-first-delivery race (fallback must
 * apply) and the post-delivery authoritative-negative case (fallback must NOT apply, i.e. no
 * stale address is reported once the tracker genuinely knows there is no eligible Wi-Fi network
 * left). Reverting either fix under test turns the corresponding "race window" assertion red
 * while leaving the "authoritative negative" assertion green, and vice versa is not possible --
 * the two cases share the same predicate ({@link KeepADBNetwork#isWifiTrackingAuthoritative()})
 * that #390 already established and tested for {@link KeepADBNetwork#isActiveWifiAddress}.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBStartupRaceWifiFallbackTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private static final String WIFI_IPV4 = "192.168.9.15";

    @After
    public void tearDown() {
        KeepADBNetwork.resetForTesting();
    }

    /**
     * AC1 (getWifiIpv4Address startup race): before the first callback has fired, the tracked
     * maps are empty for reasons unrelated to Wi-Fi being off. getWifiIpv4Address() must still
     * resolve our own synchronous Wi-Fi address instead of reporting null.
     */
    @Test
    public void getWifiIpv4AddressFallsBackToSynchronousSnapshotBeforeFirstCallbackFires() throws Exception {
        KeepADBNetwork network = KeepADBNetwork.get(context());
        setSynchronousWifiConnectionInfo(WIFI_IPV4);

        assertEquals("Before the first callback has fired the tracker knows nothing, so the "
                        + "synchronous Wi-Fi IPv4 address must still be reported",
                WIFI_IPV4, network.getWifiIpv4Address());
    }

    /**
     * AC2 (getWifiIpv4Address authoritative negative): once the callback has fired and reported
     * that no eligible Wi-Fi network is left, a stale synchronous snapshot must not be reported
     * any more -- matching the fail-closed contract #390 established for isActiveWifiAddress().
     */
    @Test
    public void getWifiIpv4AddressStaysNullOnceTheCallbackAuthoritativelyReportsNoWifi() throws Exception {
        KeepADBNetwork network = KeepADBNetwork.get(context());
        Network wifiNetwork = ShadowNetwork.newInstance(7001);

        deliverWifiNetwork(wifiNetwork, WIFI_IPV4);
        assertEquals("Precondition: while the network is tracked the address is resolvable",
                WIFI_IPV4, network.getWifiIpv4Address());

        deliverToAllCallbacks(callback -> callback.onLost(wifiNetwork));
        // WifiManager keeps handing out the just-dropped address for a moment after onLost.
        setSynchronousWifiConnectionInfo(WIFI_IPV4);

        assertNull("A registered callback that has reported the loss is authoritative -- the "
                        + "stale synchronous IPv4 address must not be reported any more",
                network.getWifiIpv4Address());
    }

    /**
     * AC3 (KeepADBService.isWifiConnected startup race): the same race window must not make the
     * service-level reader report "no Wi-Fi" while our own synchronous address is available.
     */
    @Test
    public void serviceIsWifiConnectedFallsBackToSynchronousSnapshotBeforeFirstCallbackFires() throws Exception {
        KeepADBNetwork.get(context());
        setSynchronousWifiConnectionInfo(WIFI_IPV4);

        assertTrue("Before the first callback has fired, isWifiConnected(Context) must fall "
                        + "back to the synchronous Wi-Fi snapshot instead of reporting false",
                KeepADBService.isWifiConnected(context()));
    }

    /**
     * AC4 (KeepADBService.isWifiConnected authoritative negative): once the callback has fired
     * and reported no eligible Wi-Fi network, a stale synchronous snapshot must not flip the
     * answer back to "connected".
     */
    @Test
    public void serviceIsWifiConnectedStaysFalseOnceTheCallbackAuthoritativelyReportsNoWifi() throws Exception {
        KeepADBNetwork network = KeepADBNetwork.get(context());
        Network wifiNetwork = ShadowNetwork.newInstance(7002);

        deliverWifiNetwork(wifiNetwork, WIFI_IPV4);
        assertTrue("Precondition: while the network is tracked, isWifiConnected must be true",
                KeepADBService.isWifiConnected(context()));

        deliverToAllCallbacks(callback -> callback.onLost(wifiNetwork));
        setSynchronousWifiConnectionInfo(WIFI_IPV4);

        assertFalse("A registered callback that has reported the loss is authoritative -- the "
                        + "stale synchronous snapshot must not be accepted any more",
                KeepADBService.isWifiConnected(context()));
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
