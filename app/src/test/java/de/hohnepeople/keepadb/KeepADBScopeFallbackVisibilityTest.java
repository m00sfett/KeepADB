package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

import org.junit.Test;

/**
 * Regression coverage for #403 (R21 follow-up on #364): the scope-id comparison in {@link
 * WifiAddressPolicy#matchesActiveWifiAddress} silently falls back to the scope-blind, pre-#364
 * comparison whenever either side's scope id could not be resolved to a concrete interface
 * (scope id {@code 0}) -- and until this issue, nothing observed that this happened at all.
 */
public class KeepADBScopeFallbackVisibilityTest {

    /**
     * The exact "ungestempelt" case named in #403: our own side's link-local address never got
     * stamped with a real interface index (e.g. {@code stampLinkLocalScope} could not resolve the
     * network's interface), so it carries scope {@code 0}, while the candidate arrives with a
     * concrete, nonzero scope from a *different* real interface. Byte-identical, but genuinely a
     * different device on a different interface (the #364 attacker model) -- and without a
     * resolved own-side scope, {@link WifiAddressPolicy#matchesActiveWifiAddress} cannot reject it.
     * This must still be accepted (fail-open is the documented, deliberate behaviour), but the
     * fallback sink must fire exactly once to make that acceptance observable.
     */
    @Test
    public void unstampedOwnSideFallsBackAndReportsExactlyOnce() throws Exception {
        byte[] linkLocalBytes = InetAddress.getByName("fe80::1").getAddress();
        // Own side: never stamped (scope 0), simulating a failed stampLinkLocalScope resolution.
        List<InetAddress> activeWifiAddresses = Collections.singletonList(
                InetAddress.getByName("fe80::1"));
        // Candidate: a byte-identical address resolved on some other real interface (index 9).
        InetAddress candidateOnAnotherInterface =
                Inet6Address.getByAddress(null, linkLocalBytes, 9);

        List<int[]> reportedFallbacks = new ArrayList<>();
        boolean matched = WifiAddressPolicy.matchesActiveWifiAddress(candidateOnAnotherInterface,
                activeWifiAddresses,
                (candidateScope, activeScope) -> reportedFallbacks.add(
                        new int[] { candidateScope, activeScope }));

        assertTrue("must still fail open per the documented, deliberate #403 decision", matched);
        assertEquals("the fallback must be reported exactly once, not silently",
                1, reportedFallbacks.size());
        assertEquals(9, reportedFallbacks.get(0)[0]);
        assertEquals(0, reportedFallbacks.get(0)[1]);
    }

    /** Same scenario, but the unresolved scope is on the candidate side instead of our own. */
    @Test
    public void unstampedCandidateSideFallsBackAndReportsExactlyOnce() throws Exception {
        byte[] linkLocalBytes = InetAddress.getByName("fe80::1").getAddress();
        List<InetAddress> activeWifiAddresses = Collections.singletonList(
                Inet6Address.getByAddress(null, linkLocalBytes, 5));
        InetAddress scopelessCandidate = InetAddress.getByName("fe80::1");

        List<int[]> reportedFallbacks = new ArrayList<>();
        boolean matched = WifiAddressPolicy.matchesActiveWifiAddress(scopelessCandidate,
                activeWifiAddresses,
                (candidateScope, activeScope) -> reportedFallbacks.add(
                        new int[] { candidateScope, activeScope }));

        assertTrue(matched);
        assertEquals(1, reportedFallbacks.size());
        assertEquals(0, reportedFallbacks.get(0)[0]);
        assertEquals(5, reportedFallbacks.get(0)[1]);
    }

    /**
     * When both sides resolved to a concrete, nonzero scope (the #364 hardened case), no fallback
     * event fires at all -- the sink must stay silent for a genuinely scope-verified match, not
     * just for a rejection.
     */
    @Test
    public void resolvedScopesOnBothSidesNeverReportAFallback() throws Exception {
        byte[] linkLocalBytes = InetAddress.getByName("fe80::1").getAddress();
        List<InetAddress> activeWifiAddresses = Collections.singletonList(
                Inet6Address.getByAddress(null, linkLocalBytes, 7));
        InetAddress candidateSameInterface = Inet6Address.getByAddress(null, linkLocalBytes, 7);

        List<int[]> reportedFallbacks = new ArrayList<>();
        boolean matched = WifiAddressPolicy.matchesActiveWifiAddress(candidateSameInterface,
                activeWifiAddresses,
                (candidateScope, activeScope) -> reportedFallbacks.add(
                        new int[] { candidateScope, activeScope }));

        assertTrue(matched);
        assertTrue("a fully scope-verified match must not be reported as a fallback",
                reportedFallbacks.isEmpty());
    }

    /** A rejection (disagreeing resolved scopes) must not spuriously report a fallback either. */
    @Test
    public void aRejectedDisagreeingResolvedScopeNeverReportsAFallback() throws Exception {
        byte[] linkLocalBytes = InetAddress.getByName("fe80::1").getAddress();
        List<InetAddress> activeWifiAddresses = Collections.singletonList(
                Inet6Address.getByAddress(null, linkLocalBytes, 7));
        InetAddress candidateOtherInterface = Inet6Address.getByAddress(null, linkLocalBytes, 9);

        List<int[]> reportedFallbacks = new ArrayList<>();
        boolean matched = WifiAddressPolicy.matchesActiveWifiAddress(candidateOtherInterface,
                activeWifiAddresses,
                (candidateScope, activeScope) -> reportedFallbacks.add(
                        new int[] { candidateScope, activeScope }));

        assertFalse(matched);
        assertTrue(reportedFallbacks.isEmpty());
    }

    /** The 2-arg overload used throughout the rest of the codebase/tests stays unaffected. */
    @Test
    public void twoArgOverloadStillWorksWithoutASink() throws Exception {
        List<InetAddress> activeWifiAddresses = Collections.singletonList(
                InetAddress.getByName("fe80::1"));
        assertTrue(WifiAddressPolicy.matchesActiveWifiAddress(
                InetAddress.getByName("fe80::1"), activeWifiAddresses));
    }

    /**
     * #403 AC2: the own-side interface resolution must not depend solely on {@code
     * NetworkInterface.getByName(interfaceName)} -- when that direct lookup can't find anything
     * (bogus/stale interface name), a byte-match scan across all enumerable interfaces must still
     * find the real one. Exercised via {@link WifiAddressPolicy#resolveScopeInterfaceByByteMatch}
     * directly (#410): the loopback interface used to stand in for "an interface that really
     * holds this address" here, but #410 excludes loopback interfaces from the scan on purpose, so
     * a synthetic, non-loopback-eligible candidate takes its place instead.
     */
    @Test
    public void resolveScopeInterfaceFallsBackToAByteMatchScanWhenTheNameLookupFails()
            throws Exception {
        NetworkInterface loopback = findLoopbackInterface();
        assumeTrue("test environment must expose a loopback interface", loopback != null);
        byte[] addressBytes = InetAddress.getByName("fe80::1").getAddress();
        WifiAddressPolicy.ScopeCandidate eligibleCandidate = new WifiAddressPolicy.ScopeCandidate(
                loopback, true, Collections.singletonList(addressBytes));

        NetworkInterface resolved = WifiAddressPolicy.resolveScopeInterfaceByByteMatch(
                Collections.singletonList(eligibleCandidate), addressBytes);

        assertNotNull("the byte-match fallback must find the interface holding this address",
                resolved);
        assertEquals(loopback.getName(), resolved.getName());
    }

    /**
     * #410: two distinct interfaces claiming the identical (MAC-derived) fe80 address must not
     * resolve to "first wins" -- the real regression named in the issue, where a wlan0/p2p pair
     * sharing one link-local address could stamp the wrong interface's index. The ambiguous case
     * must come back {@code null} (unresolvable), not a guess.
     */
    @Test
    public void resolveScopeInterfaceByByteMatchTreatsTwoMatchingInterfacesAsUnresolvable()
            throws Exception {
        NetworkInterface loopback = findLoopbackInterface();
        assumeTrue("test environment must expose a loopback interface", loopback != null);
        byte[] addressBytes = InetAddress.getByName("fe80::1").getAddress();
        List<byte[]> addresses = Collections.singletonList(addressBytes);
        // Two eligible candidates (e.g. wlan0 and a p2p/tether interface) both reporting the same
        // MAC-derived link-local address -- the exact ambiguity named in #410. The underlying real
        // NetworkInterface object is reused for both synthetic candidates; only the "two eligible
        // candidates hold the same address" shape matters for this decision.
        WifiAddressPolicy.ScopeCandidate wlan =
                new WifiAddressPolicy.ScopeCandidate(loopback, true, addresses);
        WifiAddressPolicy.ScopeCandidate p2p =
                new WifiAddressPolicy.ScopeCandidate(loopback, true, addresses);

        NetworkInterface resolved = WifiAddressPolicy.resolveScopeInterfaceByByteMatch(
                java.util.Arrays.asList(wlan, p2p), addressBytes);

        assertNull("an ambiguous match must be treated as unresolvable, not guessed", resolved);
    }

    /** A non-eligible (down, or loopback) candidate must never win, even if it holds the address. */
    @Test
    public void resolveScopeInterfaceByByteMatchIgnoresIneligibleCandidates() throws Exception {
        NetworkInterface loopback = findLoopbackInterface();
        assumeTrue(loopback != null);
        byte[] addressBytes = InetAddress.getByName("fe80::1").getAddress();
        WifiAddressPolicy.ScopeCandidate ineligible = new WifiAddressPolicy.ScopeCandidate(
                loopback, false, Collections.singletonList(addressBytes));

        NetworkInterface resolved = WifiAddressPolicy.resolveScopeInterfaceByByteMatch(
                Collections.singletonList(ineligible), addressBytes);

        assertNull(resolved);
    }

    /** The direct name lookup still wins when it actually resolves -- no need for the fallback. */
    @Test
    public void resolveScopeInterfaceUsesTheDirectNameLookupWhenItSucceeds() throws Exception {
        NetworkInterface loopback = findLoopbackInterface();
        assumeTrue(loopback != null);

        NetworkInterface resolved = KeepADBNetwork.resolveScopeInterface(
                loopback.getName(), InetAddress.getByName("203.0.113.1"));

        assertEquals(loopback.getName(), resolved.getName());
    }

    /**
     * #699: reading a live interface ({@code isUp()}, {@code isLoopback()}, {@code
     * getInetAddresses()}) is the I/O adapter that stays in {@link KeepADBNetwork} while the byte
     * comparison moved to {@link WifiAddressPolicy}. The loopback interface really holds {@code
     * 127.0.0.1}, so a scan that let it through would stamp a tracked link-local address with the
     * loopback index (#410 excludes loopback on purpose); with the bogus name the direct lookup
     * cannot resolve, only the scan is left, and it must come back empty.
     */
    @Test
    public void resolveScopeInterfaceNeverStampsWithTheLoopbackInterfaceItScans() throws Exception {
        NetworkInterface loopback = findLoopbackInterface();
        assumeTrue("test environment must expose a loopback interface", loopback != null);
        InetAddress heldByLoopback = firstAddress(loopback);
        assumeTrue("the loopback interface must hold an address", heldByLoopback != null);

        assertNull("a loopback interface is not eligible for the byte-match scan",
                KeepADBNetwork.resolveScopeInterface(
                        "definitely-not-a-real-interface-699", heldByLoopback));
    }

    /**
     * #699: the positive side of the same adapter, on the real topology of the machine running the
     * test -- an up, non-loopback interface that alone holds one of its addresses must be found by
     * that address when the name lookup cannot help. It needs such an interface (any ordinary
     * host, CI runner or container has one) and is skipped, not failed, on a machine without one.
     */
    @Test
    public void resolveScopeInterfaceFindsALiveNonLoopbackInterfaceByOneOfItsAddresses()
            throws Exception {
        NetworkInterface expected = null;
        InetAddress heldOnlyByIt = null;
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        List<NetworkInterface> all = interfaces == null ? new ArrayList<NetworkInterface>()
                : Collections.list(interfaces);
        for (NetworkInterface candidate : all) {
            if (!candidate.isUp() || candidate.isLoopback()) continue;
            for (InetAddress address : Collections.list(candidate.getInetAddresses())) {
                if (holders(all, address) == 1) {
                    expected = candidate;
                    heldOnlyByIt = address;
                    break;
                }
            }
            if (expected != null) break;
        }
        assumeTrue("test environment must have an up, non-loopback interface with its own address",
                expected != null);

        NetworkInterface resolved = KeepADBNetwork.resolveScopeInterface(
                "definitely-not-a-real-interface-699", heldOnlyByIt);

        assertNotNull("the byte-match scan must find the interface holding " + heldOnlyByIt, resolved);
        assertEquals(expected.getName(), resolved.getName());
    }

    /** How many live, non-loopback interfaces hold exactly these address bytes. */
    private static int holders(List<NetworkInterface> all, InetAddress address) throws Exception {
        int holders = 0;
        for (NetworkInterface candidate : all) {
            if (!candidate.isUp() || candidate.isLoopback()) continue;
            for (InetAddress held : Collections.list(candidate.getInetAddresses())) {
                if (java.util.Arrays.equals(held.getAddress(), address.getAddress())) holders++;
            }
        }
        return holders;
    }

    private static NetworkInterface findLoopbackInterface() throws Exception {
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        if (interfaces == null) return null;
        while (interfaces.hasMoreElements()) {
            NetworkInterface candidate = interfaces.nextElement();
            if (candidate.isLoopback()) return candidate;
        }
        return null;
    }

    /** Genuinely unresolvable (bogus name, and no interface holds this address) stays null. */
    @Test
    public void resolveScopeInterfaceReturnsNullWhenNothingMatchesAtAll() throws Exception {
        assertNull(KeepADBNetwork.resolveScopeInterface(
                "definitely-not-a-real-interface-403", InetAddress.getByName("203.0.113.1")));
    }

    private static InetAddress firstAddress(NetworkInterface networkInterface) {
        Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
        InetAddress fallback = null;
        while (addresses.hasMoreElements()) {
            InetAddress address = addresses.nextElement();
            if (address instanceof Inet4Address) return address;
            if (fallback == null) fallback = address;
        }
        return fallback;
    }
}
