package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

import org.junit.Test;

/**
 * #699: cases of the stateless {@link WifiAddressPolicy} that no other test pinned when the
 * decision still lived in {@code KeepADBNetwork}. Found by running the moved code against targeted
 * mutations in a disposable copy: a multicast candidate that is tracked anyway, an active list
 * where one entry disagrees on the scope and a later one agrees, and a byte-match candidate whose
 * eligibility is right but whose address is not. {@link KeepADBEndpointAddressBindingTest} and
 * {@link KeepADBScopeFallbackVisibilityTest} hold the rest of the policy's cases.
 */
public class WifiAddressPolicyTest {

    private static List<int[]> collect(List<InetAddress> active, InetAddress candidate,
            boolean[] matched) {
        List<int[]> reported = new ArrayList<>();
        matched[0] = WifiAddressPolicy.matchesActiveWifiAddress(candidate, active,
                (candidateScope, activeScope) -> reported.add(new int[] {candidateScope, activeScope}));
        return reported;
    }

    private static InetAddress linkLocal(String address, int scope) throws Exception {
        return Inet6Address.getByAddress(null, InetAddress.getByName(address).getAddress(), scope);
    }

    /** Multicast is never an {@code adb connect} target, even if it somehow is in the active list. */
    @Test
    public void multicastIsRejectedEvenIfItIsInTheActiveList() throws Exception {
        for (String multicast : new String[] {"224.0.0.1", "239.255.255.250", "ff02::1", "ff05::c"}) {
            InetAddress address = InetAddress.getByName(multicast);
            boolean[] matched = new boolean[1];
            List<int[]> reported = collect(Collections.singletonList(address), address, matched);

            assertFalse("multicast must be rejected: " + multicast, matched[0]);
            assertTrue(reported.isEmpty());
        }
    }

    /** The IPv6 wildcard is rejected like the IPv4 one, even if it is in the active list. */
    @Test
    public void theIpv6WildcardIsRejectedEvenIfItIsInTheActiveList() throws Exception {
        InetAddress wildcard = InetAddress.getByName("::");
        assertFalse(WifiAddressPolicy.matchesActiveWifiAddress(wildcard,
                Collections.singletonList(wildcard)));
    }

    /**
     * Two tracked networks can hold the same MAC-derived link-local address on different
     * interfaces. An entry whose resolved scope disagrees with the candidate must be skipped, not
     * end the decision: a later entry that agrees still makes it our own endpoint, in either order.
     */
    @Test
    public void aDisagreeingEntryNeverHidesAnAgreeingEntry() throws Exception {
        InetAddress candidate = linkLocal("fe80::1", 9);

        boolean[] matched = new boolean[1];
        List<int[]> reported = collect(Arrays.asList(linkLocal("fe80::1", 7), linkLocal("fe80::1", 9)),
                candidate, matched);
        assertTrue("the agreeing entry after a disagreeing one must still match", matched[0]);
        assertTrue("a scope-verified match is not a fallback", reported.isEmpty());

        reported = collect(Arrays.asList(linkLocal("fe80::1", 9), linkLocal("fe80::1", 7)), candidate,
                matched);
        assertTrue(matched[0]);
        assertTrue(reported.isEmpty());

        reported = collect(Arrays.asList(linkLocal("fe80::1", 7), linkLocal("fe80::1", 5)), candidate,
                matched);
        assertFalse("every entry disagrees on the scope", matched[0]);
        assertTrue(reported.isEmpty());
    }

    /** A disagreeing entry does not hide an entry with an unresolved scope either: that one reports. */
    @Test
    public void aDisagreeingEntryNeverHidesAnUnresolvedEntry() throws Exception {
        boolean[] matched = new boolean[1];
        List<int[]> reported = collect(
                Arrays.asList(linkLocal("fe80::1", 7), InetAddress.getByName("fe80::1")),
                linkLocal("fe80::1", 9), matched);

        assertTrue(matched[0]);
        assertEquals("exactly one fallback report", 1, reported.size());
        assertEquals(9, reported.get(0)[0]);
        assertEquals(0, reported.get(0)[1]);
    }

    /** Only the first accepting entry reports, never several. */
    @Test
    public void aMatchReportsAtMostOnceEvenWithSeveralUnresolvedEntries() throws Exception {
        boolean[] matched = new boolean[1];
        List<int[]> reported = collect(
                Arrays.asList(InetAddress.getByName("fe80::1"), InetAddress.getByName("fe80::1")),
                InetAddress.getByName("fe80::1"), matched);

        assertTrue(matched[0]);
        assertEquals(1, reported.size());
    }

    /** A decision without any active entry accepts nothing and reports nothing. */
    @Test
    public void anEmptyActiveListAcceptsNothingAndReportsNothing() throws Exception {
        boolean[] matched = new boolean[1];
        List<int[]> reported = collect(new ArrayList<InetAddress>(), InetAddress.getByName("fe80::1"),
                matched);

        assertFalse(matched[0]);
        assertTrue(reported.isEmpty());
    }

    /**
     * An eligible interface that does not hold the address is not a match: the byte comparison,
     * not eligibility alone, decides, also between addresses of the same length.
     */
    @Test
    public void anEligibleInterfaceThatDoesNotHoldTheAddressIsNotAMatch() throws Exception {
        NetworkInterface loopback = anyInterface();
        byte[] held = InetAddress.getByName("fe80::1").getAddress();
        WifiAddressPolicy.ScopeCandidate candidate = new WifiAddressPolicy.ScopeCandidate(
                loopback, true, Collections.singletonList(held));

        assertNull("another IPv6 address of the same length",
                WifiAddressPolicy.resolveScopeInterfaceByByteMatch(
                        Collections.singletonList(candidate),
                        InetAddress.getByName("fe80::2").getAddress()));
        assertNull("an IPv4 address",
                WifiAddressPolicy.resolveScopeInterfaceByByteMatch(
                        Collections.singletonList(candidate),
                        InetAddress.getByName("192.168.7.42").getAddress()));
        assertSame("the held address still resolves", loopback,
                WifiAddressPolicy.resolveScopeInterfaceByByteMatch(
                        Collections.singletonList(candidate), held));
    }

    /** An eligible twin is not made ambiguous by an ineligible interface holding the same address. */
    @Test
    public void anIneligibleTwinDoesNotMakeAnEligibleMatchAmbiguous() throws Exception {
        NetworkInterface loopback = anyInterface();
        byte[] held = InetAddress.getByName("fe80::1").getAddress();
        List<byte[]> addresses = Collections.singletonList(held);
        WifiAddressPolicy.ScopeCandidate down = new WifiAddressPolicy.ScopeCandidate(loopback, false, addresses);
        WifiAddressPolicy.ScopeCandidate live = new WifiAddressPolicy.ScopeCandidate(loopback, true, addresses);

        assertSame(loopback, WifiAddressPolicy.resolveScopeInterfaceByByteMatch(
                Arrays.asList(down, live), held));
        assertSame(loopback, WifiAddressPolicy.resolveScopeInterfaceByByteMatch(
                Arrays.asList(live, down), held));
    }

    /** Nothing to scan resolves to nothing. */
    @Test
    public void noCandidatesResolveToNothing() throws Exception {
        assertNull(WifiAddressPolicy.resolveScopeInterfaceByByteMatch(
                new ArrayList<WifiAddressPolicy.ScopeCandidate>(),
                InetAddress.getByName("fe80::1").getAddress()));
    }

    private static NetworkInterface anyInterface() throws Exception {
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        assumeTrue("test environment must expose a network interface",
                interfaces != null && interfaces.hasMoreElements());
        return interfaces.nextElement();
    }
}
