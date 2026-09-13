package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Behavior tests for #314: an endpoint candidate is only accepted when its address is actually
 * bound to an eligible Wi-Fi network of this device.
 *
 * <p>The decision itself ({@link KeepADBNetwork#matchesActiveWifiAddress}) is exercised directly
 * against real {@link InetAddress} values, so these are genuine behavior tests and not source
 * greps. What they deliberately cannot cover is the collection step feeding it -- which
 * {@code LinkProperties} of which tracked network contribute addresses -- because
 * {@code LinkProperties}/{@code LinkAddress} are platform classes whose method bodies are
 * stripped by this project's JVM-only mockable {@code android.jar} setup. The invariant proven
 * here is therefore "only an address in the active-Wi-Fi set is accepted", not "the set is
 * assembled from exactly the right interfaces".
 */
public class KeepADBEndpointAddressBindingTest {

    private static final String OWN_WIFI_IP = "192.168.178.50";

    @Before
    @After
    public void resetNetworkSingleton() {
        KeepADBNetwork.resetForTesting();
    }

    @Test
    public void onlyTheExactActiveWifiAddressIsAccepted() throws Exception {
        List<InetAddress> activeWifiAddresses = Collections.singletonList(
                InetAddress.getByName(OWN_WIFI_IP));

        assertTrue("our own Wi-Fi address must be accepted",
                KeepADBNetwork.matchesActiveWifiAddress(
                        InetAddress.getByName(OWN_WIFI_IP), activeWifiAddresses));

        // A neighbour on the same subnet is reachable and "local looking", but is not us.
        assertFalse("a different host on the same subnet must be rejected",
                KeepADBNetwork.matchesActiveWifiAddress(
                        InetAddress.getByName("192.168.178.51"), activeWifiAddresses));
        assertFalse("the subnet's gateway must be rejected",
                KeepADBNetwork.matchesActiveWifiAddress(
                        InetAddress.getByName("192.168.178.1"), activeWifiAddresses));
    }

    @Test
    public void foreignLinkLocalAndLoopbackCandidatesAreRejected() throws Exception {
        List<InetAddress> activeWifiAddresses = Arrays.asList(
                InetAddress.getByName(OWN_WIFI_IP),
                InetAddress.getByName("fe80::1"));

        // The regression this issue is about: any link-local or loopback address used to pass
        // unconditionally, before our own interfaces were ever consulted.
        assertFalse("a foreign IPv6 link-local address must be rejected",
                KeepADBNetwork.matchesActiveWifiAddress(
                        InetAddress.getByName("fe80::2"), activeWifiAddresses));
        assertFalse("IPv4 loopback must be rejected",
                KeepADBNetwork.matchesActiveWifiAddress(
                        InetAddress.getByName("127.0.0.1"), activeWifiAddresses));
        assertFalse("IPv6 loopback must be rejected",
                KeepADBNetwork.matchesActiveWifiAddress(
                        InetAddress.getByName("::1"), activeWifiAddresses));
        assertFalse("a link-local IPv4 (169.254/16) address must be rejected",
                KeepADBNetwork.matchesActiveWifiAddress(
                        InetAddress.getByName("169.254.7.7"), activeWifiAddresses));

        // Our own Wi-Fi interface's link-local address stays valid: adbd has been observed
        // advertising IPv6-only, so rejecting link-local as a class would break discovery.
        assertTrue("our own Wi-Fi link-local address must stay accepted",
                KeepADBNetwork.matchesActiveWifiAddress(
                        InetAddress.getByName("fe80::1"), activeWifiAddresses));
    }

    /**
     * The scope-id direction of the link-local comparison: {@code NsdManager} resolves a
     * link-local address with an interface scope ({@code fe80::1%wlan0}), while the tracked
     * Wi-Fi network's {@code LinkAddress}es generally carry none. Since adbd has been observed
     * advertising IPv6-only, a scope-strict comparison would reject our own endpoint -- so this
     * pins that a scope-id difference alone never causes a rejection. The deliberate cost of
     * that (an identical address on another interface is not distinguished) is documented on
     * {@link KeepADBNetwork#matchesActiveWifiAddress} and is not asserted here.
     */
    @Test
    public void ownLinkLocalIsAcceptedRegardlessOfItsScopeIdRepresentation() throws Exception {
        byte[] linkLocalBytes = InetAddress.getByName("fe80::1").getAddress();
        List<InetAddress> scopelessWifiAddress = Collections.singletonList(
                InetAddress.getByName("fe80::1"));

        assertTrue("a resolved link-local address with an interface scope must still match our"
                        + " scopeless Wi-Fi link address",
                KeepADBNetwork.matchesActiveWifiAddress(
                        Inet6Address.getByAddress(null, linkLocalBytes, 1), scopelessWifiAddress));

        // ...and the same the other way around, if the tracked address is the scoped one.
        assertTrue(KeepADBNetwork.matchesActiveWifiAddress(
                InetAddress.getByName("fe80::1"),
                Collections.singletonList(
                        Inet6Address.getByAddress(null, linkLocalBytes, 1))));

        // A different link-local address stays rejected, scope id or not.
        assertFalse(KeepADBNetwork.matchesActiveWifiAddress(
                Inet6Address.getByAddress(null, InetAddress.getByName("fe80::2").getAddress(), 1),
                scopelessWifiAddress));
    }

    /**
     * #364: once *both* sides resolved to a concrete, nonzero numeric scope, a byte-identical
     * link-local candidate whose scope disagrees is a different real interface, not our own Wi-Fi
     * endpoint, and must be rejected -- the collision this issue is about (e.g. a device on a
     * cellular/USB-tethering/VPN interface numerically colliding with our Wi-Fi link-local
     * address). Matching scopes on both sides still stays accepted.
     */
    @Test
    public void linkLocalWithDisagreeingResolvedScopeIsRejectedButAgreeingScopeIsAccepted()
            throws Exception {
        byte[] linkLocalBytes = InetAddress.getByName("fe80::1").getAddress();
        List<InetAddress> ownWifiScopedAddress = Collections.singletonList(
                Inet6Address.getByAddress(null, linkLocalBytes, 7));

        assertFalse("a candidate resolved on a different real interface must be rejected even "
                        + "though the bytes match our own Wi-Fi link-local address",
                KeepADBNetwork.matchesActiveWifiAddress(
                        Inet6Address.getByAddress(null, linkLocalBytes, 9), ownWifiScopedAddress));

        assertTrue("a candidate resolved on the same real interface as our own Wi-Fi link-local "
                        + "address must still be accepted",
                KeepADBNetwork.matchesActiveWifiAddress(
                        Inet6Address.getByAddress(null, linkLocalBytes, 7), ownWifiScopedAddress));
    }

    @Test
    public void loopbackIsRejectedEvenIfItSomehowEntersTheCandidateSet() throws Exception {
        List<InetAddress> withLoopback = Arrays.asList(
                InetAddress.getByName("127.0.0.1"),
                InetAddress.getByName("0.0.0.0"));

        assertFalse(KeepADBNetwork.matchesActiveWifiAddress(
                InetAddress.getByName("127.0.0.1"), withLoopback));
        assertFalse(KeepADBNetwork.matchesActiveWifiAddress(
                InetAddress.getByName("0.0.0.0"), withLoopback));
    }

    @Test
    public void withoutAnActiveWifiAddressNothingIsAccepted() throws Exception {
        List<InetAddress> noWifi = new ArrayList<>();
        for (String candidate : new String[] { OWN_WIFI_IP, "127.0.0.1", "::1", "fe80::1", "10.0.0.2" }) {
            assertFalse("must fail closed without Wi-Fi: " + candidate,
                    KeepADBNetwork.matchesActiveWifiAddress(InetAddress.getByName(candidate), noWifi));
        }
        assertFalse(KeepADBNetwork.matchesActiveWifiAddress(InetAddress.getByName(OWN_WIFI_IP), null));
        assertFalse(KeepADBNetwork.matchesActiveWifiAddress(null, noWifi));
    }

    /**
     * End-to-end through the production entry point the mDNS resolve path calls, with a context
     * that has neither a {@code ConnectivityManager} nor a {@code WifiManager} -- the "no Wi-Fi
     * at all" case. Nothing may be registered.
     */
    @Test
    public void endpointGateFailsClosedWithoutAnyNetworkInformation() throws Exception {
        FakeContext context = new FakeContext();
        for (String candidate : new String[] { OWN_WIFI_IP, "127.0.0.1", "::1", "fe80::1" }) {
            assertFalse("must fail closed: " + candidate,
                    KeepADBEndpoint.isOwnWifiAddress(context, InetAddress.getByName(candidate)));
        }
        assertFalse(KeepADBEndpoint.isOwnWifiAddress(null, InetAddress.getByName(OWN_WIFI_IP)));
        assertFalse(KeepADBEndpoint.isOwnWifiAddress(context, null));
    }

    @Test
    public void quickProbeNeedsAWifiHostAndSkipsPortsThatOnlyAnswerOnLoopback() {
        List<Integer> openPorts = Arrays.asList(37001, 37002, 37003);
        List<Integer> probed = new ArrayList<>();

        // Without a Wi-Fi address no port may be selected, and none may even be probed.
        assertEquals(-1, KeepADBEndpoint.selectWifiVerifiedPort(null, openPorts, port -> {
            probed.add(port);
            return true;
        }));
        assertTrue("no port may be probed without a Wi-Fi host", probed.isEmpty());

        // A foreign local service on the first open port must not shadow the real listener.
        assertEquals(37003, KeepADBEndpoint.selectWifiVerifiedPort(
                OWN_WIFI_IP, openPorts, port -> port == 37003));
        assertEquals(-1, KeepADBEndpoint.selectWifiVerifiedPort(
                OWN_WIFI_IP, openPorts, port -> false));
        assertEquals(-1, KeepADBEndpoint.selectWifiVerifiedPort(
                OWN_WIFI_IP, Collections.emptyList(), port -> true));
    }

    @Test
    public void quickProbeCandidateChecksAreBounded() {
        List<Integer> manyOpenPorts = new ArrayList<>();
        for (int port = 40000; port < 40100; port++) {
            manyOpenPorts.add(port);
        }
        List<Integer> probed = new ArrayList<>();

        assertEquals(-1, KeepADBEndpoint.selectWifiVerifiedPort(OWN_WIFI_IP, manyOpenPorts, port -> {
            probed.add(port);
            return false;
        }));
        assertEquals("the quick probe must stay a shortcut, not a second scan",
                KeepADBEndpoint.QUICK_PROBE_MAX_CANDIDATES, probed.size());
    }

    /**
     * #366: {@code Thread.interrupt()} cannot abort an in-flight blocking
     * {@code Socket.connect()}, so a {@code stop()} call during a running quick-probe scan can
     * only take effect between candidates -- via the generation/stop check each candidate's
     * predicate already performs before attempting its own (here simulated) blocking connect,
     * exactly like the real predicate in {@code startQuickProbe()} checks {@code isCurrent()}
     * before calling {@code probeAdbTlsPort()}. This proves the scan thread actually stops after
     * the current candidate, rather than only after all {@link KeepADBEndpoint#QUICK_PROBE_MAX_CANDIDATES}
     * candidates have each run their full simulated connect timeout.
     */
    @Test
    public void stopDuringQuickProbeEndsScanAfterCurrentCandidateNotAfterAll() throws Exception {
        final long simulatedConnectMs = 200;
        List<Integer> manyOpenPorts = new ArrayList<>();
        for (int port = 40000; port < 40000 + KeepADBEndpoint.QUICK_PROBE_MAX_CANDIDATES; port++) {
            manyOpenPorts.add(port);
        }
        AtomicBoolean stopped = new AtomicBoolean(false);
        AtomicInteger candidatesStarted = new AtomicInteger(0);
        CountDownLatch firstCandidateStarted = new CountDownLatch(1);
        CountDownLatch scanFinished = new CountDownLatch(1);

        Thread scanThread = new Thread(() -> {
            KeepADBEndpoint.selectWifiVerifiedPort(OWN_WIFI_IP, manyOpenPorts, port -> {
                // Mirrors the production predicate's generation check performed *before* the
                // blocking connect attempt for each candidate.
                if (stopped.get()) return false;
                candidatesStarted.incrementAndGet();
                firstCandidateStarted.countDown();
                try {
                    Thread.sleep(simulatedConnectMs); // simulated blocking connect+read timeout
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                return false;
            });
            scanFinished.countDown();
        }, "test-quick-probe-scan");
        scanThread.start();

        assertTrue("first candidate must have started",
                firstCandidateStarted.await(1, TimeUnit.SECONDS));
        // Simulate a stop() call arriving while the first candidate's connect is still in flight.
        stopped.set(true);

        long fullBudgetMs = KeepADBEndpoint.QUICK_PROBE_MAX_CANDIDATES * simulatedConnectMs;
        assertTrue("stop() must end the scan thread well before the full multi-candidate "
                        + "timeout budget of " + fullBudgetMs + "ms elapses",
                scanFinished.await(fullBudgetMs / 2, TimeUnit.MILLISECONDS));
        assertEquals("only the already in-flight candidate may run after stop()",
                1, candidatesStarted.get());
    }

    private static final class FakeContext extends ContextWrapper {
        FakeContext() {
            super(null);
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }
    }
}
