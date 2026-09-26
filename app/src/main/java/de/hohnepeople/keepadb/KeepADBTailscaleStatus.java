package de.hohnepeople.keepadb;

import android.content.Context;
import android.content.pm.PackageManager;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/**
 * Purely local, read-only Tailscale presence/activity signal for the optional status shown in
 * the network/endpoint view (#537). Tailscale is never started, stopped, authenticated or
 * configured here, and nothing in this class calls any Tailscale-owned API (local or remote) --
 * both are explicitly out of scope for #537/#581. The whole signal is derived from two
 * platform-level reads every app can already make without a runtime permission prompt:
 *
 * <ol>
 *   <li>whether the Tailscale app ({@link #TAILSCALE_PACKAGE}) is installed at all
 *       ({@link PackageManager#getPackageInfo}, gated by the {@code <queries>} declaration in
 *       {@code AndroidManifest.xml} required for package visibility on API 30+); and</li>
 *   <li>whether any currently up network interface looks like Tailscale's own tunnel -- see
 *       {@link #looksLikeTailscale(InterfaceSnapshot)} below.</li>
 * </ol>
 *
 * <p><b>#581 fix -- detection rule and rationale.</b> The original implementation only matched
 * an interface literally named {@code tailscale0}, which is the interface name Tailscale's
 * Linux/desktop client uses. On Android, the Tailscale app runs as a {@link
 * android.net.VpnService}, whose TUN device is named {@code tun0} (or {@code tun1}, ...) by the
 * platform, never {@code tailscale0} -- so real detection always reported {@link Status#INACTIVE}
 * on Android even while Tailscale was verifiably active, contradicting {@link
 * KeepADBVpnTransport}'s independent CGNAT-based endpoint discovery, which does find the tunnel.
 *
 * <p>The fix combines a coarse <em>name</em> filter with an <em>address</em> filter, requiring
 * both to match, rather than relying on either alone:
 *
 * <ul>
 *   <li><b>Name filter</b> ({@link #hasTunnelName}): the interface name must start with {@code
 *       tun} (Android's VpnService TUN naming) or {@code tailscale} (Linux/desktop naming, kept
 *       for compatibility and for the case a rooted/custom Android build names it that way).
 *       This step exists specifically to rule out non-tunnel interfaces that can carry a
 *       Tailscale-shaped address for unrelated reasons -- most importantly a mobile carrier's own
 *       CGNAT deployment on the cellular data interface (commonly {@code rmnet_data0} or similar,
 *       observed in the wild with addresses like {@code 100.72.x.x}, inside the very same
 *       100.64.0.0/10 block Tailscale uses). Matching on address alone, with no name filter,
 *       would misreport that carrier CGNAT as Tailscale being active. A name-only filter without
 *       an address check has the opposite problem: {@code tun0} is Android's generic VpnService
 *       device name, so it says nothing about Tailscale specifically. Hence both checks are
 *       required together.</li>
 *   <li><b>Address filter</b>: within a name-matched interface, at least one address must be
 *       either a Tailscale CGNAT IPv4 address ({@link #isCgnatAddress}, 100.64.0.0/10) or a
 *       Tailscale ULA IPv6 address ({@link #isTailscaleUlaAddress}, fd7a:115c:a1e0::/48).
 *       Checking the ULA in addition to the CGNAT address costs nothing (both are read from the
 *       same {@link NetworkInterface} pass) and adds a second, independently Tailscale-specific
 *       signal: fd7a:115c:a1e0::/48 is Tailscale's own registered ULA prefix, so a match there is
 *       essentially conclusive on its own. It is deliberately an "or", not an additional
 *       requirement -- a tunnel that has only received its IPv4 CGNAT address yet (or is running
 *       IPv4-only) must not be reported as inactive just because it lacks a ULA.</li>
 * </ul>
 *
 * <p>This rule still folds "installed but never configured" and "configured but currently
 * disconnected/unreachable" into the single {@link Status#INACTIVE} result: Android has no
 * permission-free way to tell those two apart without depending on Tailscale's own local API
 * (explicitly out of scope), so claiming a finer distinction here would be guessing, not
 * detecting. Whenever a platform call itself fails in a way that makes even this coarse read
 * unreliable, {@link Status#UNKNOWN} is returned instead of defaulting to a guess.
 *
 * <p>The interface enumeration itself ({@link #interfacesReal()}) is a thin adapter around the
 * plain JDK {@link NetworkInterface} API that only collects facts (name, up state, raw address
 * bytes) into {@link InterfaceSnapshot}; the actual decision -- {@link #looksLikeTailscale} and
 * {@link #isTailscaleActive} -- is a pure function over that description with no Android or JDK
 * networking dependency, so it can be exercised directly by ordinary JVM tests with fabricated
 * interfaces (including a realistic {@code tun0}), independent of the {@link InterfacesProvider}
 * test seam.
 *
 * <p>This class is display-only: nothing here is consulted by {@link KeepADB}, {@link
 * KeepADBEndpoint}, {@link KeepADBService} or any Keep-Alive/auto-enable decision path, and an
 * active Tailscale interface is never treated as an ADB endpoint by itself -- that judgement
 * stays entirely with the existing endpoint/transport discovery ({@link KeepADBEndpoint}, {@link
 * KeepADBNetworkIdentity}).
 */
final class KeepADBTailscaleStatus {

    static final String TAILSCALE_PACKAGE = "com.tailscale.ipn";

    /** The four user-facing states #537's acceptance criteria enumerate. */
    enum Status {
        /** Tailscale app not installed: the UI shows nothing, not a neutral/empty status card. */
        NOT_INSTALLED,
        /** A tunnel interface is up with a Tailscale-shaped address: clearly active. */
        ACTIVE,
        /** Installed, but no such interface is currently up -- covers both "never configured" and
         * "configured but disconnected", which Android cannot reliably tell apart here. */
        INACTIVE,
        /** A platform read itself failed; never guessed as active or inactive. */
        UNKNOWN
    }

    /** Test-only seam: substitutes the real installed-package check. */
    interface PackageInstalledCheck {
        /** @return {@code true}/{@code false} when known, or {@code null} when the platform call
         * itself failed unexpectedly (i.e. not a plain "not found"). */
        Boolean isInstalled(Context context, String packageName);
    }

    /** Read-only description of one network interface: exactly the facts {@link
     * #looksLikeTailscale} needs. Decouples the pure decision logic from {@link
     * NetworkInterface} so tests can drive it with fabricated interfaces without a real tunnel
     * on the test machine. */
    static final class InterfaceSnapshot {
        final String name;
        final boolean up;
        final List<byte[]> addresses;

        InterfaceSnapshot(String name, boolean up, byte[]... addresses) {
            this.name = name;
            this.up = up;
            this.addresses = Arrays.asList(addresses);
        }
    }

    /** Test-only seam: substitutes the real interface enumeration. */
    interface InterfacesProvider {
        /** @return the current interface snapshots, or {@code null} when enumeration itself
         * failed. */
        List<InterfaceSnapshot> interfaces();
    }

    private static PackageInstalledCheck packageInstalledCheck =
            KeepADBTailscaleStatus::isPackageInstalledReal;
    private static InterfacesProvider interfacesProvider =
            KeepADBTailscaleStatus::interfacesReal;

    private KeepADBTailscaleStatus() {}

    static synchronized void setPackageInstalledCheckForTesting(PackageInstalledCheck check) {
        packageInstalledCheck = check != null ? check : KeepADBTailscaleStatus::isPackageInstalledReal;
    }

    static synchronized void setInterfacesProviderForTesting(InterfacesProvider provider) {
        interfacesProvider = provider != null ? provider : KeepADBTailscaleStatus::interfacesReal;
    }

    /** Never throws; any unexpected failure surfaces as {@link Status#UNKNOWN}. */
    static Status detect(Context context) {
        PackageInstalledCheck packageCheck;
        InterfacesProvider provider;
        synchronized (KeepADBTailscaleStatus.class) {
            packageCheck = packageInstalledCheck;
            provider = interfacesProvider;
        }
        Boolean installed;
        try {
            installed = packageCheck.isInstalled(context, TAILSCALE_PACKAGE);
        } catch (Exception e) {
            installed = null;
        }
        if (installed == null) return Status.UNKNOWN;
        if (!installed) return Status.NOT_INSTALLED;

        List<InterfaceSnapshot> interfaces;
        try {
            interfaces = provider.interfaces();
        } catch (Exception e) {
            interfaces = null;
        }
        if (interfaces == null) return Status.UNKNOWN;
        return isTailscaleActive(interfaces) ? Status.ACTIVE : Status.INACTIVE;
    }

    /** Pure decision function: {@code true} iff at least one interface in the snapshot looks
     * like Tailscale's own tunnel. {@code interfaces} must be non-null; the caller (see {@link
     * #detect}) is responsible for turning a failed enumeration into {@link Status#UNKNOWN}
     * before reaching here. */
    static boolean isTailscaleActive(List<InterfaceSnapshot> interfaces) {
        for (InterfaceSnapshot iface : interfaces) {
            if (looksLikeTailscale(iface)) return true;
        }
        return false;
    }

    /** Pure decision function for a single interface: up, tunnel-named, and carrying either a
     * Tailscale CGNAT IPv4 or a Tailscale ULA IPv6 address. See the class doc for why both the
     * name and the address filter are required together. */
    static boolean looksLikeTailscale(InterfaceSnapshot iface) {
        if (iface == null || !iface.up || !hasTunnelName(iface.name)) return false;
        for (byte[] address : iface.addresses) {
            if (isCgnatAddress(address) || isTailscaleUlaAddress(address)) return true;
        }
        return false;
    }

    /** Matches Android's VpnService TUN naming ({@code tun0}, {@code tun1}, ...) and Tailscale's
     * Linux/desktop interface name ({@code tailscale0}). Deliberately excludes everything else
     * (e.g. {@code rmnet*}, {@code wlan*}) so a carrier's own CGNAT deployment can never match by
     * address alone. */
    static boolean hasTunnelName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.startsWith("tun") || lower.startsWith("tailscale");
    }

    private static Boolean isPackageInstalledReal(Context context, String packageName) {
        try {
            context.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (Exception e) {
            // Unexpected PackageManager failure: can't even reliably tell "installed" apart from
            // "not installed" here, so this must not be reported as either.
            return null;
        }
    }

    /** Thin adapter: collects raw facts from {@link NetworkInterface} into {@link
     * InterfaceSnapshot}s. Contains no Tailscale-specific decision logic -- that lives entirely
     * in {@link #looksLikeTailscale}. */
    private static List<InterfaceSnapshot> interfacesReal() {
        List<InterfaceSnapshot> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return result;
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface == null) continue;
                result.add(new InterfaceSnapshot(iface.getName(), isUpSafe(iface), addressBytes(iface)));
            }
            return result;
        } catch (SocketException e) {
            // Enumeration itself failed: don't guess, report unknown.
            return null;
        }
    }

    private static boolean isUpSafe(NetworkInterface iface) {
        try {
            return iface.isUp();
        } catch (SocketException e) {
            return false;
        }
    }

    private static byte[][] addressBytes(NetworkInterface iface) {
        List<byte[]> addresses = new ArrayList<>();
        Enumeration<InetAddress> ifaceAddresses = iface.getInetAddresses();
        while (ifaceAddresses.hasMoreElements()) {
            addresses.add(ifaceAddresses.nextElement().getAddress());
        }
        return addresses.toArray(new byte[0][]);
    }

    /** Tailscale's CGNAT allocation, 100.64.0.0/10: first byte 100, second byte 64-127. */
    static boolean isCgnatAddress(byte[] ipv4) {
        if (ipv4 == null || ipv4.length != 4) return false;
        int first = ipv4[0] & 0xFF;
        int second = ipv4[1] & 0xFF;
        return first == 100 && second >= 64 && second <= 127;
    }

    /** Tailscale's registered ULA allocation, fd7a:115c:a1e0::/48 -- i.e. the address's first 6
     * bytes equal {@code fd:7a:11:5c:a1:e0}. */
    static boolean isTailscaleUlaAddress(byte[] ipv6) {
        if (ipv6 == null || ipv6.length != 16) return false;
        return (ipv6[0] & 0xFF) == 0xfd
                && (ipv6[1] & 0xFF) == 0x7a
                && (ipv6[2] & 0xFF) == 0x11
                && (ipv6[3] & 0xFF) == 0x5c
                && (ipv6[4] & 0xFF) == 0xa1
                && (ipv6[5] & 0xFF) == 0xe0;
    }
}
