package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
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
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

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
 * #699: {@link WifiAddressPolicy} now makes the accept/reject decision, but the only production
 * caller is {@link KeepADBNetwork#isActiveWifiAddress}, which feeds it the addresses its own
 * callbacks track (or, while its tracking is not authoritative, the synchronous snapshot) and
 * hands in the sink that turns an unresolved scope into a {@code scope_fallback} diagnostics event.
 * {@link KeepADBEndpointAddressBindingTest} and {@link KeepADBScopeFallbackVisibilityTest} drive
 * the policy directly; this class drives the same decisions through that real entry point, with a
 * live {@link KeepADBNetwork} singleton fed by a Robolectric-shadowed {@link ConnectivityManager}.
 * It is what turns a policy that is silently bypassed, wired to no sink, or fed from a second
 * source into a failing test.
 *
 * <p>Link-local scopes are real here: the tracked network's link-local address is stamped by
 * {@code KeepADBNetwork#stampLinkLocalScope} with the index of the interface named by {@code
 * LinkProperties#getInterfaceName()}, and the loopback interface of the machine running the test
 * stands in for the Wi-Fi interface, as in {@link KeepADBScopeFallbackVisibilityTest}.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBWifiAddressPolicyWiringTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private static final String OWN_IPV4 = "192.168.7.42";
    private static final String OWN_IPV6 = "2001:db8::42";
    private static final String OWN_LINK_LOCAL = "fe80::1";

    @After
    public void tearDown() {
        KeepADBNetwork.resetForTesting();
    }

    /** Foreign bytes are rejected, our own tracked addresses are accepted -- through the live path. */
    @Test
    public void onlyTheTrackedAddressesAreAcceptedAndForeignBytesAreRejected() throws Exception {
        KeepADBNetwork network = KeepADBNetwork.get(context());
        deliverWifiNetwork(6101, null, OWN_IPV4 + "/24", OWN_IPV6 + "/64");

        assertTrue(network.isActiveWifiAddress(InetAddress.getByName(OWN_IPV4)));
        assertTrue(network.isActiveWifiAddress(InetAddress.getByName(OWN_IPV6)));
        for (String foreign : new String[] {"192.168.7.43", "192.168.7.1", "10.0.0.2", "2001:db8::43",
                "fe80::2"}) {
            assertFalse("a foreign address must be rejected: " + foreign,
                    network.isActiveWifiAddress(InetAddress.getByName(foreign)));
        }
    }

    /**
     * Loopback and wildcard addresses are never a usable {@code adb connect} target. Even when the
     * tracked network itself carries them -- so that the byte comparison alone would match -- they
     * must be rejected, while the tracked own address next to them is still accepted.
     */
    @Test
    public void trackedLoopbackAndWildcardAddressesAreStillRejected() throws Exception {
        KeepADBNetwork network = KeepADBNetwork.get(context());
        deliverWifiNetwork(6102, null, OWN_IPV4 + "/24", "127.0.0.1/8", "0.0.0.0/0", "::1/128", "::/0");

        assertTrue("precondition: the own address next to them is accepted",
                network.isActiveWifiAddress(InetAddress.getByName(OWN_IPV4)));
        for (String rejected : new String[] {"127.0.0.1", "0.0.0.0", "::1", "::"}) {
            assertFalse("must be rejected although tracked: " + rejected,
                    network.isActiveWifiAddress(InetAddress.getByName(rejected)));
        }
    }

    /**
     * A {@code LinkAddress} cannot hold a multicast address, but the synchronous {@code WifiInfo}
     * snapshot behind the startup-race fallback is not validated, so a multicast (or loopback)
     * address can reach the policy through it. It must be rejected there too, while an ordinary
     * snapshot address is accepted in the same window.
     */
    @Test
    public void multicastAndLoopbackFromTheSynchronousSnapshotAreRejected() throws Exception {
        KeepADBNetwork network = KeepADBNetwork.get(context());

        setSynchronousWifiConnectionInfo(OWN_IPV4);
        assertTrue("precondition: an ordinary snapshot address is accepted before the first callback",
                network.isActiveWifiAddress(InetAddress.getByName(OWN_IPV4)));

        for (String address : new String[] {"224.0.0.1", "239.255.255.250", "127.0.0.1"}) {
            setSynchronousWifiConnectionInfo(address);
            assertFalse("must be rejected although the snapshot reports it: " + address,
                    network.isActiveWifiAddress(InetAddress.getByName(address)));
        }
    }

    /** A network the tracker does not know yields no positive decision, whatever the candidate. */
    @Test
    public void anUnknownNetworkNeverYieldsAPositiveDecision() throws Exception {
        KeepADBNetwork network = KeepADBNetwork.get(context());
        String[] candidates = {OWN_IPV4, OWN_IPV6, OWN_LINK_LOCAL, "10.0.0.2", "192.168.7.99"};

        // Nothing delivered yet and no synchronous snapshot: nothing is known.
        for (String candidate : candidates) {
            assertFalse("nothing known: " + candidate,
                    network.isActiveWifiAddress(InetAddress.getByName(candidate)));
        }

        // The tracker is authoritative and the network it knew is gone.
        Network wifi = deliverWifiNetwork(6103, null, OWN_IPV4 + "/24", OWN_LINK_LOCAL + "/64");
        assertTrue("precondition: tracked while it exists",
                network.isActiveWifiAddress(InetAddress.getByName(OWN_IPV4)));
        deliverToAllCallbacks(callback -> callback.onLost(wifi));
        for (String candidate : candidates) {
            assertFalse("network lost: " + candidate,
                    network.isActiveWifiAddress(InetAddress.getByName(candidate)));
        }

        // A different network is tracked: only its own addresses count.
        deliverWifiNetwork(6104, null, "192.168.9.30/24");
        assertTrue(network.isActiveWifiAddress(InetAddress.getByName("192.168.9.30")));
        for (String candidate : candidates) {
            assertFalse("other network: " + candidate,
                    network.isActiveWifiAddress(InetAddress.getByName(candidate)));
        }
    }

    /**
     * Resolved scopes on both sides: a candidate on the interface the tracked address was stamped
     * with is accepted without any fallback event, a byte-identical candidate on another interface
     * is rejected, and a different link-local address is rejected whatever its scope.
     */
    @Test
    public void aKnownDifferentScopeIsRejectedAndTheSameScopeAcceptedWithoutAnEvent() throws Exception {
        NetworkInterface loopback = requireLoopbackInterface();
        int index = loopback.getIndex();
        KeepADBNetwork network = KeepADBNetwork.get(context());
        deliverWifiNetwork(6105, loopback.getName(), OWN_LINK_LOCAL + "/64");
        byte[] bytes = InetAddress.getByName(OWN_LINK_LOCAL).getAddress();

        assertFalse("byte-identical but bound to another real interface",
                network.isActiveWifiAddress(Inet6Address.getByAddress(null, bytes, index + 1)));
        assertTrue("same interface as the tracked address",
                network.isActiveWifiAddress(Inet6Address.getByAddress(null, bytes, index)));
        assertFalse("a different link-local address on the same interface",
                network.isActiveWifiAddress(Inet6Address.getByAddress(null,
                        InetAddress.getByName("fe80::2").getAddress(), index)));
        assertFalse("a different link-local address without scope",
                network.isActiveWifiAddress(InetAddress.getByName("fe80::2")));

        assertEquals("a scope-verified match or a rejection is never reported as a fallback",
                0, scopeFallbackEvents().size());
    }

    /**
     * The tracked side carries a resolved scope, the candidate none: accepted by the deliberate
     * scope-blind fallback, and reported exactly once through the sink the live tracker hands to
     * the policy -- not silently, not twice.
     */
    @Test
    public void anUnscopedCandidateIsAcceptedAndReportedOnceThroughTheLiveSink() throws Exception {
        NetworkInterface loopback = requireLoopbackInterface();
        KeepADBNetwork network = KeepADBNetwork.get(context());
        deliverWifiNetwork(6106, loopback.getName(), OWN_LINK_LOCAL + "/64");

        assertTrue(network.isActiveWifiAddress(InetAddress.getByName(OWN_LINK_LOCAL)));

        List<String> events = scopeFallbackEvents();
        assertEquals("exactly one fallback event per accepted call: " + events, 1, events.size());
        assertTrue(events.get(0), events.get(0).contains("source=network"));
        assertTrue(events.get(0), events.get(0).contains("outcome=unresolved"));
        assertTrue(events.get(0),
                events.get(0).contains("detail=candidateScope=0 activeScope=" + loopback.getIndex()));
    }

    /**
     * The tracked side could not be stamped (no interface name, no interface holds the address),
     * so its scope is 0: a candidate with a concrete scope is accepted -- the documented fail-open
     * -- and the event names both scopes. This is the case #403 exists for.
     */
    @Test
    public void anUnstampedTrackedAddressAcceptsAScopedCandidateAndReportsOnceThroughTheLiveSink()
            throws Exception {
        KeepADBNetwork network = KeepADBNetwork.get(context());
        deliverWifiNetwork(6107, null, OWN_LINK_LOCAL + "/64");
        byte[] bytes = InetAddress.getByName(OWN_LINK_LOCAL).getAddress();

        assertTrue(network.isActiveWifiAddress(Inet6Address.getByAddress(null, bytes, 9)));

        List<String> events = scopeFallbackEvents();
        assertEquals("exactly one fallback event per accepted call: " + events, 1, events.size());
        assertTrue(events.get(0), events.get(0).contains("detail=candidateScope=9 activeScope=0"));

        // Both sides unresolved: still accepted and reported, with both scopes 0.
        assertTrue(network.isActiveWifiAddress(InetAddress.getByName(OWN_LINK_LOCAL)));
        events = scopeFallbackEvents();
        assertEquals(2, events.size());
        assertTrue(events.get(1), events.get(1).contains("detail=candidateScope=0 activeScope=0"));
    }

    /** Neither an IPv4 nor a global IPv6 match, nor a rejection, ever reports a scope fallback. */
    @Test
    public void onlyAScopeBlindLinkLocalMatchIsReported() throws Exception {
        KeepADBNetwork network = KeepADBNetwork.get(context());
        deliverWifiNetwork(6108, null, OWN_IPV4 + "/24", OWN_IPV6 + "/64", OWN_LINK_LOCAL + "/64");

        assertTrue(network.isActiveWifiAddress(InetAddress.getByName(OWN_IPV4)));
        assertTrue(network.isActiveWifiAddress(InetAddress.getByName(OWN_IPV6)));
        assertFalse(network.isActiveWifiAddress(InetAddress.getByName("192.168.7.43")));
        assertFalse(network.isActiveWifiAddress(InetAddress.getByName("fe80::2")));

        assertEquals(0, scopeFallbackEvents().size());
    }

    /**
     * Deciding registers nothing: the one tracker keeps the three callbacks it registered at
     * construction (Wi-Fi, default route, VPN), stays the same singleton, and a decision leaves
     * no other listener behind.
     */
    @Test
    public void decidingAddsNoCallbackAndKeepsTheSingleTracker() throws Exception {
        KeepADBNetwork network = KeepADBNetwork.get(context());
        int callbacksOfTheTracker = registeredCallbacks();
        assertEquals("the tracker registers its Wi-Fi, default-route and VPN callbacks, nothing else",
                3, callbacksOfTheTracker);

        deliverWifiNetwork(6109, null, OWN_IPV4 + "/24", OWN_LINK_LOCAL + "/64");
        for (int i = 0; i < 5; i++) {
            network.isActiveWifiAddress(InetAddress.getByName(OWN_IPV4));
            network.isActiveWifiAddress(InetAddress.getByName(OWN_LINK_LOCAL));
            network.isActiveWifiAddress(InetAddress.getByName("10.0.0.2"));
        }

        assertEquals(callbacksOfTheTracker, registeredCallbacks());
        assertSame(network, KeepADBNetwork.get(context()));
    }

    private static NetworkInterface requireLoopbackInterface() throws Exception {
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces != null && interfaces.hasMoreElements()) {
            NetworkInterface candidate = interfaces.nextElement();
            if (candidate.isLoopback()) return candidate;
        }
        assumeTrue("test environment must expose a loopback interface", false);
        return null;
    }

    private static List<String> scopeFallbackEvents() {
        List<String> events = new ArrayList<>();
        for (String line : KeepADBDiagnostics.export(context()).split("\n")) {
            if (line.contains("event=scope_fallback")) events.add(line);
        }
        return events;
    }

    private static Network deliverWifiNetwork(int handle, String interfaceName, String... linkAddresses)
            throws Exception {
        Network network = ShadowNetwork.newInstance(handle);
        NetworkCapabilities capabilities = ShadowNetworkCapabilities.newInstance();
        shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
        LinkProperties linkProperties = linkProperties(interfaceName, linkAddresses);
        deliverToAllCallbacks(callback -> callback.onCapabilitiesChanged(network, capabilities));
        deliverToAllCallbacks(callback -> callback.onLinkPropertiesChanged(network, linkProperties));
        return network;
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
     * See {@link KeepADBActiveWifiAddressStalenessTest}: the compile-time Android stub jar exposes
     * neither {@link LinkAddress}'s {@code String} constructor, {@code
     * LinkProperties.addLinkAddress} nor {@code LinkProperties.setInterfaceName}, and Robolectric
     * ships no shadow seam for them, so all three are driven reflectively against the real
     * framework classes loaded at test run time.
     */
    private static LinkProperties linkProperties(String interfaceName, String... linkAddresses)
            throws Exception {
        Constructor<LinkAddress> constructor = LinkAddress.class.getConstructor(String.class);
        Method addLinkAddress = LinkProperties.class.getMethod("addLinkAddress", LinkAddress.class);
        LinkProperties properties = new LinkProperties();
        for (String linkAddress : linkAddresses) {
            addLinkAddress.invoke(properties, constructor.newInstance(linkAddress));
        }
        if (interfaceName != null) {
            LinkProperties.class.getMethod("setInterfaceName", String.class)
                    .invoke(properties, interfaceName);
        }
        return properties;
    }

    private interface CallbackAction {
        void deliver(ConnectivityManager.NetworkCallback callback);
    }

    private static void deliverToAllCallbacks(CallbackAction action) {
        for (ConnectivityManager.NetworkCallback callback : shadowConnectivityManager().getNetworkCallbacks()) {
            action.deliver(callback);
        }
    }

    private static int registeredCallbacks() {
        return shadowConnectivityManager().getNetworkCallbacks().size();
    }

    private static ShadowConnectivityManager shadowConnectivityManager() {
        return shadowOf(context().getSystemService(ConnectivityManager.class));
    }
}
