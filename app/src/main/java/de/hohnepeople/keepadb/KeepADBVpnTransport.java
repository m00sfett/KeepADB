package de.hohnepeople.keepadb;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.net.Inet4Address;
import java.net.InetAddress;

/**
 * Minimal, self-contained Tailscale/VPN transport verification for #538.
 *
 * <p>#537 (parallel work on Tailscale status detection) had no Tailscale-specific detection
 * class visible on a shared branch/PR when this was written (checked at the start of this
 * issue's work: only a local, unpushed worktree existed, and its diff touched notification-panel
 * preferences, not network detection). This class is therefore independent, deliberately narrow,
 * and does not depend on or wait for that work; reconciling the two is left to the later
 * cross-branch review the parent orchestration already plans for.
 *
 * <p>Detection is two-staged, matching the #538 acceptance criterion that an active VPN
 * interface alone must never be presented as an ADB endpoint:
 * <ol>
 *   <li>{@link #findTailscaleIpv4Address(Context)} identifies a VPN-transport network whose
 *       IPv4 address falls inside Tailscale's documented CGNAT allocation range
 *       (100.64.0.0/10) -- the range every device on a tailnet gets its address from. A
 *       different (non-Tailscale) VPN simply has no address in that range and is correctly
 *       not reported here.</li>
 *   <li>{@link #verifyAdbReachable(String, int)} is a real socket-connect probe against that
 *       address on the already-known WLAN/LAN ADB port (a TCP server such as adbd listens on
 *       the same port across every one of the device's own interfaces, so the WLAN-discovered
 *       port is the correct one to probe on the Tailscale interface too), reusing
 *       {@link KeepADBEndpoint#isPortReachable}. Without an already-known port (no verified
 *       WLAN/LAN endpoint) reachability cannot be established here at all -- exactly the "VPN
 *       active, ADB not reachable there" acceptance criterion.</li>
 * </ol>
 */
final class KeepADBVpnTransport {
    private static final int VERIFY_TIMEOUT_MS = 400;

    /** Test-only seam for {@link #verifyAdbReachable}, mirroring the {@code ReachabilityProbe}
     * pattern {@link KeepADBNotification} already uses: a real socket connect against an
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

    /** First IPv4 address of an active VPN-transport network that falls inside Tailscale's
     * 100.64.0.0/10 CGNAT range, or {@code null} if no such network is currently active. */
    static String findTailscaleIpv4Address(Context context) {
        if (context == null) return null;
        try {
            ConnectivityManager cm = connectivityManager(context);
            if (cm == null) return null;
            Network[] networks = cm.getAllNetworks();
            if (networks == null) return null;
            for (Network network : networks) {
                NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
                if (capabilities == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    continue;
                }
                LinkProperties linkProperties = cm.getLinkProperties(network);
                if (linkProperties == null) continue;
                for (LinkAddress linkAddress : linkProperties.getLinkAddresses()) {
                    InetAddress address = linkAddress.getAddress();
                    if (isTailscaleCgnatAddress(address)) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Whether any active VPN-transport network exists at all, regardless of address range --
     * used to surface a "VPN active" status distinct from an actual ADB endpoint. */
    static boolean hasActiveVpnTransport(Context context) {
        if (context == null) return false;
        try {
            ConnectivityManager cm = connectivityManager(context);
            if (cm == null) return false;
            Network[] networks = cm.getAllNetworks();
            if (networks == null) return false;
            for (Network network : networks) {
                NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
                if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static ConnectivityManager connectivityManager(Context context) {
        return (ConnectivityManager) context.getApplicationContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    /** Tailscale's documented CGNAT allocation range for tailnet device addresses:
     * 100.64.0.0/10, i.e. first octet 100 and the second octet's upper six bits fixed
     * (64-127 inclusive). */
    static boolean isTailscaleCgnatAddress(InetAddress address) {
        if (!(address instanceof Inet4Address)) return false;
        byte[] bytes = address.getAddress();
        if (bytes.length != 4) return false;
        int first = bytes[0] & 0xFF;
        int second = bytes[1] & 0xFF;
        return first == 100 && second >= 64 && second <= 127;
    }

    /** Real socket-connect ADB reachability probe against {@code host:port}, blocking -- callers
     * must not run this on the main thread (see {@link KeepADBTransportOverview#currentAsync}). */
    static boolean verifyAdbReachable(String host, int port) {
        if (host == null || port <= 0) return false;
        return reachabilityProbe.isReachable(host, port, VERIFY_TIMEOUT_MS);
    }
}
