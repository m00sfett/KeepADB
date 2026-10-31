package de.hohnepeople.keepadb;

import android.content.Context;
import android.net.LinkAddress;
import android.net.LinkProperties;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Product-neutral VPN transport verification (#538, made product-neutral in #877).
 *
 * <p>KeepADB works with whatever VPN or overlay network the user runs (Tailscale, NetBird,
 * WireGuard, ZeroTier, a corporate VPN, ...). It never detects, names, starts or configures a
 * particular product; an address range does not identify one either -- several overlay networks
 * share 100.64.0.0/10.
 *
 * <p>Since #676 the VPN networks come from {@link KeepADBNetwork}'s callback-based VPN tracking
 * instead of the network enumeration deprecated since API 31.
 *
 * <p>Detection is two-staged, matching the #538 acceptance criterion that an active VPN
 * interface alone must never be presented as an ADB endpoint:
 * <ol>
 *   <li>{@link #findVpnIpv4Addresses(Context)} lists the usable IPv4 addresses of every active
 *       VPN-transport network.</li>
 *   <li>{@link #verifyAdbReachable(String, int)} is a real socket-connect probe against each
 *       address in turn, until one answers, on the already-known WLAN/LAN ADB port (a TCP server such as adbd listens on
 *       the same port across every one of the device's own interfaces, so the WLAN-discovered
 *       port is the correct one to probe on the VPN interface too), reusing
 *       {@link KeepADBEndpoint#isPortReachable}. Without an already-known port (no verified
 *       WLAN/LAN endpoint) reachability cannot be established here at all -- exactly the "VPN
 *       active, ADB not reachable there" acceptance criterion. The probe shows that ADB listens
 *       on the VPN address; whether other peers of that VPN can reach it is up to the VPN.</li>
 * </ol>
 */
final class KeepADBVpnTransport {
    private static final int VERIFY_TIMEOUT_MS = 400;

    /** Test-only seam for {@link #verifyAdbReachable}, mirroring the {@code ReachabilityProbe}
     * pattern {@link KeepADBEndpointCoordinator} already uses: a real socket connect against an
     * artificial 100.64.0.0/10 address cannot be exercised deterministically in a unit test
     * (there is no such local interface to bind), so tests substitute a fake outcome here. */
    interface ReachabilityProbe {
        boolean isReachable(String host, int port, int timeoutMs);
    }

    private static volatile ReachabilityProbe reachabilityProbe = KeepADBEndpoint::isPortReachable;

    static void setReachabilityProbeForTesting(ReachabilityProbe probe) {
        reachabilityProbe = probe != null ? probe : KeepADBEndpoint::isPortReachable;
    }

    private KeepADBVpnTransport() {}

    /** Every usable IPv4 address (see {@link #isUsableVpnAddress}) of the active VPN-transport
     * networks, without duplicates and in tracking order; empty if there is none. Each one is a
     * candidate only -- the caller probes them in turn, since the first may not accept ADB while
     * a later one does. */
    static List<String> findVpnIpv4Addresses(Context context) {
        Set<String> addresses = new LinkedHashSet<>();
        if (context == null) return new ArrayList<>(addresses);
        try {
            for (LinkProperties linkProperties : KeepADBNetwork.get(context).vpnLinkPropertiesSnapshot()) {
                if (linkProperties == null) continue;
                for (LinkAddress linkAddress : linkProperties.getLinkAddresses()) {
                    InetAddress address = linkAddress.getAddress();
                    if (isUsableVpnAddress(address)) {
                        addresses.add(address.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return new ArrayList<>(addresses);
    }

    /** First of {@link #findVpnIpv4Addresses}, or {@code null} if there is none. */
    static String findVpnIpv4Address(Context context) {
        List<String> addresses = findVpnIpv4Addresses(context);
        return addresses.isEmpty() ? null : addresses.get(0);
    }

    /** Whether any active VPN-transport network exists at all, regardless of address range --
     * used to surface a "VPN active" status distinct from an actual ADB endpoint. */
    static boolean hasActiveVpnTransport(Context context) {
        if (context == null) return false;
        try {
            return KeepADBNetwork.get(context).hasVpnNetwork();
        } catch (Exception ignored) {
            return false;
        }
    }

    /** An IPv4 address a peer could address the device by: not wildcard, loopback or
     * link-local. IPv6 is left out to keep the endpoint format to plain {@code host:port}. */
    static boolean isUsableVpnAddress(InetAddress address) {
        return address instanceof Inet4Address
                && !address.isAnyLocalAddress()
                && !address.isLoopbackAddress()
                && !address.isLinkLocalAddress();
    }

    /** Real socket-connect ADB reachability probe against {@code host:port}, blocking -- callers
     * must not run this on the main thread (see {@link KeepADBTransportOverview#currentAsync}). */
    static boolean verifyAdbReachable(String host, int port) {
        if (host == null || port <= 0) return false;
        return reachabilityProbe.isReachable(host, port, VERIFY_TIMEOUT_MS);
    }
}
