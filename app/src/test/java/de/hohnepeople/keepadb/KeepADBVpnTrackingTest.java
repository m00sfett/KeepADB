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
import android.net.NetworkInfo;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowConnectivityManager;
import org.robolectric.shadows.ShadowNetwork;
import org.robolectric.shadows.ShadowNetworkCapabilities;
import org.robolectric.shadows.ShadowNetworkInfo;

/**
 * #676: {@link KeepADBVpnTransport} must see VPN networks through {@link KeepADBNetwork}'s
 * callback tracking (no deprecated network enumeration). These tests drive the tracker's VPN
 * callback directly; a VPN that is only known to the shadow {@code ConnectivityManager} must not
 * be detected once the callback is live.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBVpnTrackingTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = RuntimeEnvironment.getApplication();

    @Test
    public void vpnDeliveredThroughTheCallbackIsDetectedAndLostAgain() throws Exception {
        Network vpn = KeepADBVpnTestSupport.deliverVpnNetwork(context, 9101, linkPropertiesWithIpv4("100.101.2.3"));

        assertTrue(KeepADBVpnTransport.hasActiveVpnTransport(context));
        assertEquals("100.101.2.3", KeepADBVpnTransport.findTailscaleIpv4Address(context));

        KeepADBVpnTestSupport.lose(context, vpn);

        assertFalse(KeepADBVpnTransport.hasActiveVpnTransport(context));
        assertNull(KeepADBVpnTransport.findTailscaleIpv4Address(context));
    }

    @Test
    public void losingOneVpnKeepsTheOtherTracked() throws Exception {
        Network first = KeepADBVpnTestSupport.deliverVpnNetwork(context, 9101, linkPropertiesWithIpv4("10.8.0.5"));
        KeepADBVpnTestSupport.deliverVpnNetwork(context, 9102, linkPropertiesWithIpv4("100.101.2.3"));

        KeepADBVpnTestSupport.lose(context, first);

        assertTrue(KeepADBVpnTransport.hasActiveVpnTransport(context));
        assertEquals("100.101.2.3", KeepADBVpnTransport.findTailscaleIpv4Address(context));
    }

    @Test
    public void nonTailscaleVpnIsActiveButNotATailscaleAddress() throws Exception {
        KeepADBVpnTestSupport.deliverVpnNetwork(context, 9101, linkPropertiesWithIpv4("10.8.0.5"));

        assertTrue(KeepADBVpnTransport.hasActiveVpnTransport(context));
        assertNull(KeepADBVpnTransport.findTailscaleIpv4Address(context));
    }

    @Test
    public void networkWithoutVpnTransportIsIgnored() throws Exception {
        Network wifi = ShadowNetwork.newInstance(9103);
        NetworkCapabilities capabilities = ShadowNetworkCapabilities.newInstance();
        shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_WIFI);

        KeepADBVpnTestSupport.deliver(context, wifi, capabilities, linkPropertiesWithIpv4("100.101.2.3"));

        assertFalse(KeepADBVpnTransport.hasActiveVpnTransport(context));
        assertNull(KeepADBVpnTransport.findTailscaleIpv4Address(context));
    }

    @Test
    public void vpnKnownOnlyToTheShadowConnectivityManagerIsNotDetectedOnceTheCallbackIsLive() throws Exception {
        KeepADBVpnTestSupport.deliverVpnNetwork(context, 9101, linkPropertiesWithIpv4("10.8.0.5"));
        KeepADBVpnTestSupport.lose(context, ShadowNetwork.newInstance(9101));
        addShadowOnlyVpn("100.101.2.3");

        assertFalse("Detection must come from callback tracking, not a synchronous enumeration",
                KeepADBVpnTransport.hasActiveVpnTransport(context));
        assertNull(KeepADBVpnTransport.findTailscaleIpv4Address(context));
    }

    /** Before the first callback delivery the tracker knows nothing; a VPN that is the current
     * default network is still reported through the synchronous default-network fallback. */
    @Test
    public void beforeTheFirstCallbackDeliveryAVpnDefaultNetworkIsStillDetected() throws Exception {
        addShadowOnlyVpn("100.101.2.3");

        assertTrue(KeepADBVpnTransport.hasActiveVpnTransport(context));
        assertEquals("100.101.2.3", KeepADBVpnTransport.findTailscaleIpv4Address(context));
    }

    private void addShadowOnlyVpn(String ipv4) throws Exception {
        ShadowConnectivityManager shadow = shadowOf(context.getSystemService(ConnectivityManager.class));
        // ShadowConnectivityManager#getActiveNetwork() resolves via the active NetworkInfo's
        // *type*, so the net id must equal TYPE_VPN (see KeepADBIpv6SynchronousFallbackTest).
        Network vpn = ShadowNetwork.newInstance(ConnectivityManager.TYPE_VPN);
        NetworkInfo info = ShadowNetworkInfo.newInstance(
                NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_VPN, 0, true, true);
        NetworkCapabilities capabilities = ShadowNetworkCapabilities.newInstance();
        shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_VPN);
        shadow.addNetwork(vpn, info);
        shadow.setNetworkCapabilities(vpn, capabilities);
        shadow.setLinkProperties(vpn, linkPropertiesWithIpv4(ipv4));
        shadow.setActiveNetworkInfo(info);
    }

    private static LinkProperties linkPropertiesWithIpv4(String ipv4) throws Exception {
        Constructor<LinkAddress> constructor = LinkAddress.class.getConstructor(String.class);
        LinkAddress address = constructor.newInstance(ipv4 + "/32");
        LinkProperties properties = new LinkProperties();
        Method addLinkAddress = LinkProperties.class.getMethod("addLinkAddress", LinkAddress.class);
        addLinkAddress.invoke(properties, address);
        return properties;
    }
}
