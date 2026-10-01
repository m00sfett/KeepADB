package de.hohnepeople.keepadb;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Arrays;
import java.util.List;

/**
 * Stateless address and scope decisions behind {@link KeepADBNetwork#isActiveWifiAddress}, the
 * security boundary of the active Wi-Fi endpoint binding (#314, #364, #403, #410). Extracted from
 * {@link KeepADBNetwork} in #699 without any change of behavior.
 *
 * <p>This class decides, it never observes. It holds no field, no cache, no lock and no listener,
 * performs no I/O and has no Android dependency: every input arrives as an argument and every
 * outcome is a return value or a call on the {@link ScopeFallbackSink} the caller passed in.
 * Everything that observes the device stays with the single owner {@link KeepADBNetwork}:
 * <ul>
 *   <li>the network callbacks and their tracked maps (which addresses are bound to an eligible
 *       Wi-Fi network right now),</li>
 *   <li>whether that tracking is authoritative yet, i.e. the synchronous fallback permission of
 *       #352/#390 -- this policy never widens the candidate list it is given,</li>
 *   <li>the {@link NetworkInterface} enumeration and lookup behind {@link
 *       KeepADBNetwork#resolveScopeInterface} and the {@link ScopeCandidate} snapshots built from
 *       it ({@code KeepADBNetwork#scopeCandidateOf}).</li>
 * </ul>
 * {@link NetworkInterface} appears here only as an opaque value that {@link ScopeCandidate} carries
 * through to the caller; no method of it is ever invoked in this class.
 *
 * <p>Fail-closed by construction: with {@code null} input or an empty candidate list nothing is
 * accepted, and a candidate is only ever accepted because it equals an address of that list.
 */
final class WifiAddressPolicy {
    private WifiAddressPolicy() {}

    /**
     * Pure decision behind {@link KeepADBNetwork#isActiveWifiAddress(InetAddress)}, separated so it
     * is testable without a real {@code ConnectivityManager}. Loopback, wildcard and
     * multicast addresses are rejected outright: they are never a usable {@code adb connect}
     * target from another host, so accepting one could only ever register a local service of some
     * other kind.
     *
     * <p>The core comparison is {@link InetAddress#equals}, which is <em>scope-id blind</em> for
     * IPv6: {@code Inet6Address.equals()} compares the 16 address bytes only, so {@code
     * fe80::1%wlan0}, {@code fe80::1%rmnet0} and a scopeless {@code fe80::1} all compare equal.
     * On top of that, for a link-local ({@code fe80::/10}) match, #364 additionally compares
     * {@link Inet6Address#getScopeId()} whenever <em>both</em> sides resolved to a nonzero
     * numeric scope, and rejects a byte-identical candidate whose scope disagrees -- a real
     * device on a different interface (cellular, USB tethering, a VPN endpoint under attacker
     * control) numerically colliding with our own Wi-Fi link-local address, formerly
     * indistinguishable from the real thing. This still fails open rather than closed whenever
     * either side's scope could not be resolved to a concrete interface (scope id {@code 0}):
     * {@code NsdManager} does not always hand back a resolved link-local address with a scope,
     * and the {@code LinkAddress}es of the tracked Wi-Fi network only carry one when {@code
     * KeepADBNetwork#stampLinkLocalScope} (hardened in #403 via {@link
     * KeepADBNetwork#resolveScopeInterface}) could resolve the network's real interface -- either
     * gap must keep accepting our own advertised link-local endpoint, since adbd has been
     * observed advertising IPv6-only and a scope-strict comparison would otherwise reject it.
     * Proving that whatever listens behind a matched address really is adbd remains separate,
     * open follow-up work (R13 on #314).
     *
     * <p>#403: this scope-blind fallback is a <em>permanent, deliberate</em> acceptance for the
     * candidate side, not a temporary gap -- {@code NsdManager} is not documented to always
     * resolve a scope for a link-local address, so a candidate-side scope of {@code 0} is
     * expected steady-state behaviour, not an error condition to eventually close. The own-side
     * (tracked Wi-Fi network) half of the gap is the one this issue narrows, via {@link
     * KeepADBNetwork#resolveScopeInterface}'s byte-match fallback. Whenever either side still
     * falls back to the scope-blind comparison, {@code scopeFallbackSink} (if given) is notified
     * so the event is observable instead of silent; see {@link KeepADBNetwork#isActiveWifiAddress}
     * for the production wiring that turns this into a {@link KeepADBDiagnostics} event.
     */
    static boolean matchesActiveWifiAddress(InetAddress candidate, List<InetAddress> activeWifiAddresses) {
        return matchesActiveWifiAddress(candidate, activeWifiAddresses, null);
    }

    static boolean matchesActiveWifiAddress(InetAddress candidate, List<InetAddress> activeWifiAddresses,
            ScopeFallbackSink scopeFallbackSink) {
        if (candidate == null || activeWifiAddresses == null) return false;
        if (candidate.isLoopbackAddress() || candidate.isAnyLocalAddress()
                || candidate.isMulticastAddress()) {
            return false;
        }
        for (InetAddress address : activeWifiAddresses) {
            if (!candidate.equals(address)) continue;
            if (candidate instanceof Inet6Address && address instanceof Inet6Address
                    && candidate.isLinkLocalAddress()) {
                int candidateScope = ((Inet6Address) candidate).getScopeId();
                int activeScope = ((Inet6Address) address).getScopeId();
                if (candidateScope != 0 && activeScope != 0 && candidateScope != activeScope) {
                    continue; // Byte-identical, but bound to two different real interfaces (#364).
                }
                if ((candidateScope == 0 || activeScope == 0) && scopeFallbackSink != null) {
                    scopeFallbackSink.onScopeFallback(candidateScope, activeScope);
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Callback for {@link #matchesActiveWifiAddress(InetAddress, List, ScopeFallbackSink)}: fired
     * whenever a link-local match was accepted via the scope-blind fallback described there
     * (#403), i.e. at least one side's scope id could not be resolved to a concrete interface.
     */
    interface ScopeFallbackSink {
        void onScopeFallback(int candidateScope, int activeScope);
    }

    /**
     * Core of the byte-match scan (#410), separated from real {@link NetworkInterface} enumeration
     * so the ambiguous case -- two distinct eligible interfaces both claiming {@code addressBytes}
     * -- can be exercised in a unit test without needing two such real interfaces to exist on the
     * machine running the test. Loopback and down interfaces are skipped outright; among the
     * remaining eligible ones, a single match wins, but a second distinct match downgrades the
     * result to {@code null} ("nicht auflösbar") instead of keeping the first one found.
     *
     * <p>The candidates are snapshots built by the caller ({@code
     * KeepADBNetwork#scopeCandidateOf}); this method only compares bytes and never touches a live
     * {@link NetworkInterface}.
     */
    static NetworkInterface resolveScopeInterfaceByByteMatch(List<ScopeCandidate> candidates,
            byte[] addressBytes) {
        NetworkInterface match = null;
        for (ScopeCandidate candidate : candidates) {
            if (!candidate.eligible || !candidate.hasAddress(addressBytes)) continue;
            if (match != null) return null; // Ambiguous (#410): more than one interface qualifies.
            match = candidate.networkInterface;
        }
        return match;
    }

    /**
     * Snapshot of one {@link NetworkInterface}'s eligibility and address bytes for {@link
     * #resolveScopeInterfaceByByteMatch}, package-visible so #410's regression test can construct
     * synthetic candidates (e.g. two claiming the identical address) directly, without depending
     * on the test machine's real network topology. Immutable plain data: reading the live
     * interface to fill it is the I/O adapter {@code KeepADBNetwork#scopeCandidateOf}, which
     * deliberately stays in {@link KeepADBNetwork}.
     */
    static final class ScopeCandidate {
        final NetworkInterface networkInterface;
        final boolean eligible;
        private final List<byte[]> addresses;

        ScopeCandidate(NetworkInterface networkInterface, boolean eligible, List<byte[]> addresses) {
            this.networkInterface = networkInterface;
            this.eligible = eligible;
            this.addresses = addresses;
        }

        boolean hasAddress(byte[] addressBytes) {
            for (byte[] candidateAddress : addresses) {
                if (Arrays.equals(candidateAddress, addressBytes)) return true;
            }
            return false;
        }
    }
}
