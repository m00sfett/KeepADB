package de.hohnepeople.keepadb;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

/**
 * Reads the currently connected Wi-Fi identity used by trusted-network checks.
 *
 * <p>The BSSID is the match key; the SSID is a display label and an optional, weaker match. Android
 * may return masked placeholder values unless precise location permission, enabled location
 * services, and an eligible foreground-service context are present. Treat placeholders as unknown
 * so an unreadable identity cannot satisfy the allowlist. See docs/trusted-networks.md.
 */
final class KeepADBNetworkIdentity {
    static final String REDACTED_BSSID = "02:00:00:00:00:00";
    /**
     * What WifiInfo reports for "no associated access point" on the devices/OEM builds that
     * return a non-null placeholder instead of null. Treated exactly like {@link
     * #REDACTED_BSSID}: storing it as an allowlist entry (by tapping "add current network"
     * while not associated) would otherwise make every later disconnected state compare equal
     * to a listed entry, i.e. fail open.
     */
    static final String UNSET_BSSID = "00:00:00:00:00:00";

    final String ssid;
    final String bssid;

    /** Package-visible so tests can construct known/unknown identities without a real WifiInfo. */
    KeepADBNetworkIdentity(String ssid, String bssid) {
        this.ssid = ssid;
        this.bssid = bssid;
    }

    static KeepADBNetworkIdentity from(WifiInfo info) {
        if (info == null) return new KeepADBNetworkIdentity(null, null);
        return new KeepADBNetworkIdentity(info.getSSID(), info.getBSSID());
    }

    static KeepADBNetworkIdentity current(Context context) {
        if (context == null) return new KeepADBNetworkIdentity(null, null);
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                return from(wm.getConnectionInfo());
            }
        } catch (Exception ignored) {
        }
        return new KeepADBNetworkIdentity(null, null);
    }

    boolean isKnown() {
        return bssid != null && !bssid.isEmpty()
                && !REDACTED_BSSID.equalsIgnoreCase(bssid)
                && !UNSET_BSSID.equalsIgnoreCase(bssid);
    }

    /**
     * Returns a readable SSID without WifiInfo's surrounding quotes, or null for an unknown value.
     * The allowlist keeps checking BSSID separately and never stores an Android placeholder as a
     * name. See docs/trusted-networks.md.
     */
    String displaySsid() {
        if (ssid == null || WifiManager.UNKNOWN_SSID.equals(ssid)) return null;
        if (ssid.length() >= 2 && ssid.startsWith("\"") && ssid.endsWith("\"")) {
            return ssid.substring(1, ssid.length() - 1);
        }
        return ssid;
    }
}
