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
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.InetAddress;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowConnectivityManager;
import org.robolectric.shadows.ShadowNetwork;
import org.robolectric.shadows.ShadowNetworkCapabilities;
import org.robolectric.shadows.ShadowNetworkInfo;
import org.robolectric.shadows.ShadowWifiInfo;

/**
 * Regression coverage for #365: the synchronous best-effort Wi-Fi address fallback used while
 * {@link KeepADBNetwork}'s own callback view is not yet authoritative (see #390) used to be
 * IPv4-only ({@link WifiInfo} has no IPv6 accessor), so a candidate on an IPv6-only Wi-Fi network
 * was wrongly rejected during that same startup/registration-failure race window. {@link
 * KeepADBNetwork#synchronousWifiIpv6Address()} closes that gap via {@link ConnectivityManager}'s
 * synchronous {@code getActiveNetwork()}/{@code getNetworkCapabilities()}/{@code
 * getLinkProperties()}, only when no synchronous IPv4 address is available.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBIpv6SynchronousFallbackTest {

    private static final String WIFI_IPV4 = "192.168.7.42";
    private static final String WIFI_IPV6 = "2001:db8::42";

    // KeepADBNetwork.get(context) memoizes its singleton across calls and ignores the context
    // on every call after the first. Some other test elsewhere in the suite may create it
    // against a different Robolectric Application without ever resetting it (only KeepADBNetwork
    // itself, not KeepADBService/KeepADBEndpoint callers, are obliged to via #352's contract), so
    // resetting here too -- not only in tearDown() -- makes this test's outcome independent of
    // execution order instead of depending on every other test's cleanup discipline.
    @Before
    public void setUp() {
        KeepADBNetwork.resetForTesting();
    }

    @After
    public void tearDown() {
        KeepADBNetwork.resetForTesting();
    }

    /**
     * AC1: a pure IPv6 network (no synchronous IPv4 snapshot) must still have its address
     * accepted during the race window, via the new synchronous IPv6 lookup.
     */
    @Test
    public void pureIpv6NetworkIsAcceptedDuringTheRaceWindow() throws Exception {
        KeepADBNetwork network = KeepADBNetwork.get(context());
        makeWifiTheActiveNetworkWithAddress(WIFI_IPV6);

        assertTrue("An IPv6-only Wi-Fi network's address must be accepted via the synchronous "
                        + "IPv6 fallback while the callback view is not yet authoritative",
                network.isActiveWifiAddress(InetAddress.getByName(WIFI_IPV6)));
    }

    /**
     * AC2: on a dual-stack network the synchronous IPv4 snapshot alone still decides the
     * candidate set, exactly as before #365 -- the new IPv6 lookup only runs when no IPv4
     * candidate exists, so it must not additionally accept the IPv6 address here.
     */
    @Test
    public void mixedIpv4AndIpv6NetworkKeepsPreferringIpv4Unchanged() throws Exception {
        KeepADBNetwork network = KeepADBNetwork.get(context());
        setSynchronousWifiConnectionInfo(WIFI_IPV4);
        makeWifiTheActiveNetworkWithAddress(WIFI_IPV6);

        assertTrue("The synchronous IPv4 address must still be accepted, unchanged",
                network.isActiveWifiAddress(InetAddress.getByName(WIFI_IPV4)));
        assertFalse("With an IPv4 candidate present the synchronous IPv6 lookup must not run, "
                        + "matching pre-#365 behaviour",
                network.isActiveWifiAddress(InetAddress.getByName(WIFI_IPV6)));
    }

    /** AC3: a pure IPv4 network's behaviour is unaffected by the new IPv6 lookup. */
    @Test
    public void pureIpv4NetworkIsUnaffected() throws Exception {
        KeepADBNetwork network = KeepADBNetwork.get(context());
        setSynchronousWifiConnectionInfo(WIFI_IPV4);

        assertTrue("A pure IPv4 network's synchronous address must still be accepted",
                network.isActiveWifiAddress(InetAddress.getByName(WIFI_IPV4)));
        assertFalse("No IPv6 candidate exists on a pure IPv4 network",
                network.isActiveWifiAddress(InetAddress.getByName(WIFI_IPV6)));
    }

    /**
     * The synchronous IPv6 lookup only ever widens the candidate set through {@code
     * ConnectivityManager#getActiveNetwork()}: when Wi-Fi is connected but not the device's
     * current default route (e.g. VPN/cellular is active), it must stay silent rather than throw
     * or wrongly accept anything.
     */
    @Test
    public void ipv6LookupStaysSilentWhenWifiIsNotTheActiveDefaultNetwork() throws Exception {
        KeepADBNetwork network = KeepADBNetwork.get(context());
        // No synchronous IPv4 snapshot and no active-network wiring at all: ConnectivityManager's
        // getActiveNetwork() returns whatever Robolectric's default fixture provides (mobile),
        // which carries no TRANSPORT_WIFI capability.
        assertFalse("Without Wi-Fi being the active default network, no IPv6 candidate exists",
                network.isActiveWifiAddress(InetAddress.getByName(WIFI_IPV6)));
    }

    private static void makeWifiTheActiveNetworkWithAddress(String ipv6) throws Exception {
        ConnectivityManager connectivityManager =
                (ConnectivityManager) context().getSystemService(Context.CONNECTIVITY_SERVICE);
        ShadowConnectivityManager shadowConnectivityManager = shadowOf(connectivityManager);

        // ShadowConnectivityManager#getActiveNetwork() resolves via netIdToNetwork keyed by the
        // active NetworkInfo's *type*, not the Network's own net id, so addNetwork() below only
        // wires things up correctly when this network's net id equals TYPE_WIFI.
        Network wifiNetwork = ShadowNetwork.newInstance(ConnectivityManager.TYPE_WIFI);
        NetworkInfo wifiNetworkInfo = ShadowNetworkInfo.newInstance(
                NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true);

        NetworkCapabilities capabilities = ShadowNetworkCapabilities.newInstance();
        shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_WIFI);

        shadowConnectivityManager.addNetwork(wifiNetwork, wifiNetworkInfo);
        shadowConnectivityManager.setActiveNetworkInfo(wifiNetworkInfo);
        shadowConnectivityManager.setNetworkCapabilities(wifiNetwork, capabilities);
        shadowConnectivityManager.setLinkProperties(wifiNetwork, linkPropertiesWithIpv6(ipv6));
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
     * See {@code KeepADBNetworkRobustnessBehaviorTest}/{@code KeepADBActiveWifiAddressStalenessTest}:
     * the compile-time Android stub jar exposes neither {@link LinkAddress}'s {@code String}
     * constructor nor {@code LinkProperties.addLinkAddress}, and Robolectric ships no shadow seam
     * for either, so both are driven reflectively against the real framework classes loaded at
     * test run time.
     */
    private static LinkProperties linkPropertiesWithIpv6(String ipv6) throws Exception {
        Constructor<LinkAddress> constructor = LinkAddress.class.getConstructor(String.class);
        LinkAddress address = constructor.newInstance(ipv6 + "/64");
        LinkProperties properties = new LinkProperties();
        Method addLinkAddress = LinkProperties.class.getMethod("addLinkAddress", LinkAddress.class);
        addLinkAddress.invoke(properties, address);
        return properties;
    }
}
