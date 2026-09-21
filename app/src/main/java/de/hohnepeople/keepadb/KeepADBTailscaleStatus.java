package de.hohnepeople.keepadb;

import android.content.Context;
import android.content.pm.PackageManager;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

/**
 * Purely local, read-only Tailscale presence/activity signal for the optional status shown in
 * the network/endpoint view (#537). Tailscale is never started, stopped, authenticated or
 * configured here, and nothing in this class calls any Tailscale-owned API (local or remote) --
 * both are explicitly out of scope for #537. The whole signal is derived from two platform-level
 * reads every app can already make without a runtime permission prompt:
 *
 * <ol>
 *   <li>whether the Tailscale app ({@link #TAILSCALE_PACKAGE}) is installed at all
 *       ({@link PackageManager#getPackageInfo}, gated by the {@code <queries>} declaration in
 *       {@code AndroidManifest.xml} required for package visibility on API 30+); and</li>
 *   <li>whether a network interface named {@link #INTERFACE_NAME} is currently up and carries an
 *       IPv4 address inside Tailscale's CGNAT allocation (100.64.0.0/10), via the plain JDK
 *       {@link NetworkInterface} API -- the same API {@link KeepADBNetwork} already uses for
 *       scope resolution, just pointed at a different interface name.</li>
 * </ol>
 *
 * Neither read proves Tailscale is actually reachable end-to-end, and this class makes no such
 * claim -- it only distinguishes "the interface looks up with a Tailscale-shaped address" from
 * "it doesn't". #537 deliberately folds "installed but never configured" and "configured but
 * currently disconnected/unreachable" into the single {@link Status#INACTIVE} result: Android has
 * no permission-free way to tell those two apart without depending on Tailscale's own local API
 * (explicitly out of scope), so claiming a finer distinction here would be guessing, not
 * detecting -- the acceptance criterion is "don't guess", not "report everything Tailscale itself
 * could report". Whenever either platform call itself fails in a way that makes even this coarse
 * read unreliable, {@link Status#UNKNOWN} is returned instead of defaulting to a guess.
 *
 * <p>This class is display-only: nothing here is consulted by {@link KeepADB}, {@link
 * KeepADBEndpoint}, {@link KeepADBService} or any Keep-Alive/auto-enable decision path, and an
 * active Tailscale interface is never treated as an ADB endpoint by itself -- that judgement
 * stays entirely with the existing endpoint/transport discovery ({@link KeepADBEndpoint}, {@link
 * KeepADBNetworkIdentity}).
 */
final class KeepADBTailscaleStatus {

    static final String TAILSCALE_PACKAGE = "com.tailscale.ipn";
    static final String INTERFACE_NAME = "tailscale0";

    /** The four user-facing states #537's acceptance criteria enumerate. */
    enum Status {
        /** Tailscale app not installed: the UI shows nothing, not a neutral/empty status card. */
        NOT_INSTALLED,
        /** Interface up with a Tailscale-shaped (CGNAT) address: clearly active. */
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

    /** Test-only seam: substitutes the real interface enumeration. */
    interface InterfaceActiveCheck {
        /** @return {@code true}/{@code false} when known, or {@code null} when enumeration itself
         * failed. */
        Boolean isActive(String interfaceName);
    }

    private static PackageInstalledCheck packageInstalledCheck =
            KeepADBTailscaleStatus::isPackageInstalledReal;
    private static InterfaceActiveCheck interfaceActiveCheck =
            KeepADBTailscaleStatus::isInterfaceActiveReal;

    private KeepADBTailscaleStatus() {}

    static synchronized void setPackageInstalledCheckForTesting(PackageInstalledCheck check) {
        packageInstalledCheck = check != null ? check : KeepADBTailscaleStatus::isPackageInstalledReal;
    }

    static synchronized void setInterfaceActiveCheckForTesting(InterfaceActiveCheck check) {
        interfaceActiveCheck = check != null ? check : KeepADBTailscaleStatus::isInterfaceActiveReal;
    }

    /** Never throws; any unexpected failure surfaces as {@link Status#UNKNOWN}. */
    static Status detect(Context context) {
        PackageInstalledCheck packageCheck;
        InterfaceActiveCheck interfaceCheck;
        synchronized (KeepADBTailscaleStatus.class) {
            packageCheck = packageInstalledCheck;
            interfaceCheck = interfaceActiveCheck;
        }
        Boolean installed;
        try {
            installed = packageCheck.isInstalled(context, TAILSCALE_PACKAGE);
        } catch (Exception e) {
            installed = null;
        }
        if (installed == null) return Status.UNKNOWN;
        if (!installed) return Status.NOT_INSTALLED;

        Boolean active;
        try {
            active = interfaceCheck.isActive(INTERFACE_NAME);
        } catch (Exception e) {
            active = null;
        }
        if (active == null) return Status.UNKNOWN;
        return active ? Status.ACTIVE : Status.INACTIVE;
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

    private static Boolean isInterfaceActiveReal(String interfaceName) {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return false;
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface == null || !interfaceName.equals(iface.getName())) continue;
                if (!isUpSafe(iface)) continue;
                if (hasCgnatAddress(iface)) return true;
            }
            return false;
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

    private static boolean hasCgnatAddress(NetworkInterface iface) {
        Enumeration<InetAddress> addresses = iface.getInetAddresses();
        while (addresses.hasMoreElements()) {
            InetAddress address = addresses.nextElement();
            if (address instanceof Inet4Address && isCgnatAddress(address.getAddress())) {
                return true;
            }
        }
        return false;
    }

    /** Tailscale's CGNAT allocation, 100.64.0.0/10: first byte 100, second byte 64-127. */
    static boolean isCgnatAddress(byte[] ipv4) {
        if (ipv4 == null || ipv4.length != 4) return false;
        int first = ipv4[0] & 0xFF;
        int second = ipv4[1] & 0xFF;
        return first == 100 && second >= 64 && second <= 127;
    }
}
