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
 * Regression coverage for issue #352 (follow-up from cumulative review R20): {@link
 * KeepADBNetwork} and {@link KeepADBService#isWifiConnected(Context)} must stay deterministic
 * and safe when the Wi-Fi {@code NetworkCallback} registration fails, when the synchronous
 * {@code WifiInfo} snapshot is stale relative to the live callback state, and when more than one
 * eligible Wi-Fi network is tracked at once.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBNetworkRobustnessBehaviorTest {

    private final Context context = RuntimeEnvironment.getApplication();

    @After
    public void tearDown() {
        KeepADBNetwork.resetForTesting();
    }

    /**
     * AC1 (callback registration failure): with no live callback at all, KeepADBNetwork's own
     * isWifiConnected() can only ever say "false" -- but that "false" means "unknown", not
     * "disconnected". KeepADBService's fallback to the synchronous WifiInfo snapshot must still
     * be reachable in that case.
     */
    @Test
    public void wifiInfoFallbackIsUsedWhenTheCallbackFailedToRegister() throws Exception {
        KeepADBNetwork.get(context); // force construction so the override below has an instance to apply to
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> false);
        KeepADBNetwork.setWifiCallbackRegisteredOverrideForTesting(false);
        setSynchronousWifiConnectionInfo("192.168.1.42");

        assertTrue("With no working callback, the synchronous WifiInfo snapshot is the only "
                        + "remaining signal and must be trusted",
                KeepADBService.isWifiConnected(context));
    }

    /**
     * AC1 (stale WifiInfo): once the callback is live and authoritatively reports "no eligible
     * Wi-Fi network", a stale WifiInfo snapshot that still reports a (just-dropped) address must
     * not override that authoritative negative answer.
     */
    @Test
    public void staleWifiInfoDoesNotOverrideAnAuthoritativeCallbackRegisteredNegative() throws Exception {
        KeepADBNetwork.get(context);
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> false);
        KeepADBNetwork.setWifiCallbackRegisteredOverrideForTesting(true);
        setSynchronousWifiConnectionInfo("192.168.1.42");

        assertFalse("A live, registered callback's negative answer must win over a stale "
                        + "synchronous WifiInfo snapshot",
                KeepADBService.isWifiConnected(context));
    }

    /** The real (non-overridden) production default: a successful construction reports itself as registered. */
    @Test
    public void realConstructionReportsTheCallbackAsRegistered() {
        KeepADBNetwork network = KeepADBNetwork.get(context);

        assertTrue("registerNetworkCallback() succeeds under Robolectric's shadow "
                        + "ConnectivityManager, so isWifiCallbackRegistered() must be true",
                network.isWifiCallbackRegistered());
    }

    /**
     * AC2/AC4 (second WLAN): losing one of two simultaneously tracked eligible Wi-Fi networks
     * must not evict the still-active one from KeepADBNetwork's own tracker.
     */
    @Test
    public void onLostForOneOfTwoWifiNetworksKeepsTheOtherTracked() throws Exception {
        KeepADBNetwork network = KeepADBNetwork.get(context);
        ConnectivityManager connectivityManager = context.getSystemService(ConnectivityManager.class);
        ShadowConnectivityManager shadowConnectivityManager = shadowOf(connectivityManager);

        Network networkA = ShadowNetwork.newInstance(4001);
        Network networkB = ShadowNetwork.newInstance(4002);
        NetworkCapabilities wifiCaps = eligibleWifiCapabilities();
        LinkProperties propsA = linkPropertiesWithIpv4("192.168.5.10");
        LinkProperties propsB = linkPropertiesWithIpv4("192.168.5.20");

        deliverToAllCallbacks(shadowConnectivityManager,
                callback -> callback.onCapabilitiesChanged(networkA, wifiCaps));
        deliverToAllCallbacks(shadowConnectivityManager,
                callback -> callback.onLinkPropertiesChanged(networkA, propsA));
        deliverToAllCallbacks(shadowConnectivityManager,
                callback -> callback.onCapabilitiesChanged(networkB, wifiCaps));
        deliverToAllCallbacks(shadowConnectivityManager,
                callback -> callback.onLinkPropertiesChanged(networkB, propsB));

        assertTrue("Both eligible Wi-Fi networks tracked -> isWifiConnected() must be true",
                network.isWifiConnected());

        deliverToAllCallbacks(shadowConnectivityManager, callback -> callback.onLost(networkB));

        assertTrue("Losing network B must not invalidate the still-tracked network A",
                network.isWifiConnected());
        assertEquals("Network A's address must still be resolvable after B is lost",
                "192.168.5.10", network.getWifiIpv4Address());
    }

    /** AC2/AC4 (complete loss): losing the last tracked eligible Wi-Fi network does invalidate the tracker. */
    @Test
    public void onLostForTheLastWifiNetworkInvalidatesTracking() throws Exception {
        KeepADBNetwork network = KeepADBNetwork.get(context);
        ConnectivityManager connectivityManager = context.getSystemService(ConnectivityManager.class);
        ShadowConnectivityManager shadowConnectivityManager = shadowOf(connectivityManager);

        Network onlyNetwork = ShadowNetwork.newInstance(4003);
        NetworkCapabilities wifiCaps = eligibleWifiCapabilities();
        LinkProperties props = linkPropertiesWithIpv4("192.168.5.30");

        deliverToAllCallbacks(shadowConnectivityManager,
                callback -> callback.onCapabilitiesChanged(onlyNetwork, wifiCaps));
        deliverToAllCallbacks(shadowConnectivityManager,
                callback -> callback.onLinkPropertiesChanged(onlyNetwork, props));
        assertTrue(network.isWifiConnected());

        deliverToAllCallbacks(shadowConnectivityManager, callback -> callback.onLost(onlyNetwork));

        assertFalse("Losing the only tracked network must invalidate isWifiConnected()",
                network.isWifiConnected());
        assertEquals(null, network.getWifiIpv4Address());
    }

    /**
     * AC3/AC4 (multiple simultaneous WLANs, deterministic evaluation): with two eligible Wi-Fi
     * networks tracked at once, getWifiIpv4Address() must resolve to the same candidate every
     * time and regardless of the order the two networks were reported in -- not depend on
     * ConcurrentHashMap's unspecified iteration order.
     */
    @Test
    public void getWifiIpv4AddressIsDeterministicRegardlessOfDeliveryOrder() throws Exception {
        Network lowerHandleNetwork = ShadowNetwork.newInstance(5001);
        Network higherHandleNetwork = ShadowNetwork.newInstance(5002);
        NetworkCapabilities wifiCaps = eligibleWifiCapabilities();
        LinkProperties propsLower = linkPropertiesWithIpv4("10.0.0.11");
        LinkProperties propsHigher = linkPropertiesWithIpv4("10.0.0.22");

        // Deliver the higher-handle network first, then the lower-handle one, to prove the
        // result does not depend on arrival order.
        KeepADBNetwork network = KeepADBNetwork.get(context);
        ConnectivityManager connectivityManager = context.getSystemService(ConnectivityManager.class);
        ShadowConnectivityManager shadowConnectivityManager = shadowOf(connectivityManager);

        deliverToAllCallbacks(shadowConnectivityManager,
                callback -> callback.onCapabilitiesChanged(higherHandleNetwork, wifiCaps));
        deliverToAllCallbacks(shadowConnectivityManager,
                callback -> callback.onLinkPropertiesChanged(higherHandleNetwork, propsHigher));
        deliverToAllCallbacks(shadowConnectivityManager,
                callback -> callback.onCapabilitiesChanged(lowerHandleNetwork, wifiCaps));
        deliverToAllCallbacks(shadowConnectivityManager,
                callback -> callback.onLinkPropertiesChanged(lowerHandleNetwork, propsLower));

        String first = network.getWifiIpv4Address();
        String second = network.getWifiIpv4Address();

        assertEquals("Repeated calls against the same tracked state must agree", first, second);
        assertEquals("The lower Network#getNetworkHandle() candidate must be picked deterministically",
                "10.0.0.11", first);
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

    private static NetworkCapabilities eligibleWifiCapabilities() {
        NetworkCapabilities capabilities = ShadowNetworkCapabilities.newInstance();
        shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
        return capabilities;
    }

    /**
     * The compile-time Android stub jar this module's unit tests build against only exposes
     * {@link LinkAddress}'s no-arg constructor and no {@code LinkProperties.addLinkAddress}
     * overload at all (unlike the real framework classes Robolectric substitutes at runtime),
     * and Robolectric ships no {@code ShadowLinkAddress}/{@code ShadowLinkProperties} seam for
     * either -- so both are driven reflectively instead, resolving against the real framework
     * classes actually loaded at test run time.
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

    private static void deliverToAllCallbacks(ShadowConnectivityManager shadowConnectivityManager,
            CallbackAction action) {
        for (ConnectivityManager.NetworkCallback callback : shadowConnectivityManager.getNetworkCallbacks()) {
            action.deliver(callback);
        }
    }
}
