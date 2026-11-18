package de.hohnepeople.keepadb;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

/**
 * #997: whether the current Wi-Fi is open or sits behind a captive portal. While the rule is on
 * ({@link KeepADBPreferences#isOpenNetworkShutdownEnabled}), such a network allows no automatic
 * enable -- trusted access points included -- and the Keep-Alive service turns Wifi-ADB off there
 * ({@link KeepADBUntrustedShutdown}). A running force mode overrides it, as it overrides every
 * other network rule ({@link KeepADBTrustedNetwork#evaluateCurrent}).
 *
 * <p>"Open" is {@link KeepADBNetworkSecurity#TIER_OPEN}: no password (open, WEP, Enhanced Open).
 * Android 12 and later report the security type; Android 11 does not, so there only the captive
 * portal part applies. An unreadable or unknown type is not treated as open: the rule acts only
 * on positive evidence and leaves everything else to the trusted-network rules. A captive portal
 * is what Android itself reports for the Wi-Fi ({@link
 * NetworkCapabilities#NET_CAPABILITY_CAPTIVE_PORTAL}); once the user has signed in, Android drops
 * the capability and only the security type counts.
 */
final class KeepADBOpenNetwork {
    enum Reason { NONE, OPEN, CAPTIVE_PORTAL }

    /** Test seam for the two platform reads; null uses the platform. */
    interface Probe {
        Integer securityTier(Context context);

        boolean captivePortal(Context context);
    }

    private static volatile Probe probeForTesting;

    private KeepADBOpenNetwork() {}

    static void setProbeForTesting(Probe probe) {
        probeForTesting = probe;
    }

    /** The reason the current Wi-Fi falls under the rule, or NONE while the rule is off. */
    static Reason current(Context context) {
        if (context == null || !KeepADBPreferences.isOpenNetworkShutdownEnabled(context)) {
            return Reason.NONE;
        }
        Probe probe = probeForTesting;
        boolean captive = probe != null ? probe.captivePortal(context) : isCaptivePortal(context);
        Integer tier = probe != null ? probe.securityTier(context)
                : KeepADBNetworkIdentity.current(context).securityTier;
        return of(tier, captive);
    }

    /** The pure rule: a captive portal first, then a readable open tier. */
    static Reason of(Integer securityTier, boolean captivePortal) {
        if (captivePortal) return Reason.CAPTIVE_PORTAL;
        if (securityTier != null && securityTier == KeepADBNetworkSecurity.TIER_OPEN) {
            return Reason.OPEN;
        }
        return Reason.NONE;
    }

    /** Whether any connected Wi-Fi network currently reports a captive portal. */
    private static boolean isCaptivePortal(Context context) {
        try {
            ConnectivityManager cm = context.getApplicationContext()
                    .getSystemService(ConnectivityManager.class);
            if (cm == null) return false;
            for (Network network : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) {
                    return true;
                }
            }
        } catch (RuntimeException ignored) {
            // No answer is no evidence: the rule does not apply.
        }
        return false;
    }
}
