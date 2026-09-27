package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

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

/**
 * #596 (codequality review CQ-03): {@code KeepADBNetworkContractTest} used to assert that
 * {@code KeepADBEndpoint.java}'s source text contains {@code
 * "KeepADBNetwork.get(context).getWifiIpv4Address()"} and {@code
 * "KeepADBNetwork.get(context).isActiveWifiAddress(addr)"} -- proving the two call sites are
 * spelled out in the source, but not that {@link KeepADBEndpoint#getWifiIpAddress} and {@link
 * KeepADBEndpoint#isOwnWifiAddress} actually delegate to a live {@link KeepADBNetwork} tracker at
 * runtime rather than, say, some independently reimplemented address logic that happens to
 * mention the same class name in a comment.
 *
 * <p>Existing behavior coverage ({@link KeepADBActiveWifiAddressStalenessTest}, {@link
 * KeepADBEndpointAddressBindingTest}, {@link KeepADBNetworkRobustnessBehaviorTest}) drives {@link
 * KeepADBNetwork}'s own decision logic thoroughly, but always by calling {@code
 * KeepADBNetwork.matchesActiveWifiAddress}/{@code isActiveWifiAddress}/{@code getWifiIpv4Address}
 * directly -- none of it goes through {@link KeepADBEndpoint}'s production entry points. This
 * class closes that gap: it delivers a real tracked Wi-Fi network to a live {@link KeepADBNetwork}
 * singleton via a real, Robolectric-shadowed {@link ConnectivityManager} callback (matching {@link
 * KeepADBNetworkRobustnessBehaviorTest}'s pattern), then calls {@link
 * KeepADBEndpoint#getWifiIpAddress}/{@link KeepADBEndpoint#isOwnWifiAddress} themselves and
 * asserts the tracked address is actually reflected back -- proof the delegation is real, not
 * just textually present.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBEndpointNetworkDelegationBehaviorTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = RuntimeEnvironment.getApplication();

    @After
    public void tearDown() {
        KeepADBNetwork.resetForTesting();
    }

    @Test
    public void getWifiIpAddressReflectsTheAddressKeepADBNetworkActuallyTracks() throws Exception {
        // No synchronous WifiInfo connection configured on the shadow WifiManager, so
        // getWifiIpAddress()'s own first (unrelated) synchronous-snapshot branch reports ip=0 and
        // falls through to the KeepADBNetwork delegation this test targets.
        deliverTrackedWifiNetwork("192.168.9.30");

        assertEquals("KeepADBEndpoint.getWifiIpAddress() must return the address the live "
                        + "KeepADBNetwork tracker actually reports, not some independent value",
                "192.168.9.30", KeepADBEndpoint.getWifiIpAddress(context));
    }

    @Test
    public void isOwnWifiAddressAcceptsATrackedAddressAndRejectsAForeignOne() throws Exception {
        deliverTrackedWifiNetwork("192.168.9.30");

        assertTrue("an address KeepADBNetwork actually tracks as our own Wi-Fi address must be "
                        + "accepted through KeepADBEndpoint's own production entry point",
                KeepADBEndpoint.isOwnWifiAddress(context, InetAddress.getByName("192.168.9.30")));
        assertFalse("a foreign address on the same subnet must still be rejected",
                KeepADBEndpoint.isOwnWifiAddress(context, InetAddress.getByName("192.168.9.31")));
    }

    private void deliverTrackedWifiNetwork(String ipv4) throws Exception {
        KeepADBNetwork.get(context); // force construction so the callback below has an instance
        ConnectivityManager connectivityManager = context.getSystemService(ConnectivityManager.class);
        ShadowConnectivityManager shadowConnectivityManager = shadowOf(connectivityManager);
        Network network = ShadowNetwork.newInstance(7001);
        NetworkCapabilities wifiCaps = eligibleWifiCapabilities();
        LinkProperties props = linkPropertiesWithIpv4(ipv4);

        for (ConnectivityManager.NetworkCallback callback : shadowConnectivityManager.getNetworkCallbacks()) {
            callback.onCapabilitiesChanged(network, wifiCaps);
            callback.onLinkPropertiesChanged(network, props);
        }
    }

    private static NetworkCapabilities eligibleWifiCapabilities() {
        NetworkCapabilities capabilities = ShadowNetworkCapabilities.newInstance();
        shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
        return capabilities;
    }

    /**
     * Same reflective construction {@link KeepADBNetworkRobustnessBehaviorTest} uses: the
     * compile-time Android stub jar this module's unit tests build against only exposes {@link
     * LinkAddress}'s no-arg constructor and no {@code LinkProperties.addLinkAddress} overload at
     * all, unlike the real framework classes Robolectric substitutes at runtime.
     */
    private static LinkProperties linkPropertiesWithIpv4(String ipv4) throws Exception {
        Constructor<LinkAddress> constructor = LinkAddress.class.getConstructor(String.class);
        LinkAddress address = constructor.newInstance(ipv4 + "/24");
        LinkProperties properties = new LinkProperties();
        Method addLinkAddress = LinkProperties.class.getMethod("addLinkAddress", LinkAddress.class);
        addLinkAddress.invoke(properties, address);
        return properties;
    }
}
