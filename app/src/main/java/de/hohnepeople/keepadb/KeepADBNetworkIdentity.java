package de.hohnepeople.keepadb;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

/**
 * Identifies the currently connected Wi-Fi network for the trusted-network allowlist (#245).
 *
 * <p>BSSID is the match key (identifies one physical access point; SSID is a user-chosen,
 * freely reused string, so two unrelated networks can share the same name). SSID is kept only
 * as a human-readable label. Reading either field requires ACCESS_FINE_LOCATION on Android; if
 * that permission is missing (or location is off), the platform returns the placeholder values
 * {@link WifiInfo#UNKNOWN_SSID} and {@link #REDACTED_BSSID} instead of throwing, so {@link
 * #isKnown()} must be checked before treating the identity as a real, matchable value -- using
 * the placeholder as-is would make every unrecognized network compare equal (fail-open).
 */
final class KeepADBNetworkIdentity {
    static final String REDACTED_BSSID = "02:00:00:00:00:00";

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
        return bssid != null && !bssid.isEmpty() && !REDACTED_BSSID.equals(bssid);
    }

    /** Human-readable SSID with the surrounding quotes WifiInfo#getSSID() adds, if present. */
    String displaySsid() {
        if (ssid == null) return null;
        if (ssid.length() >= 2 && ssid.startsWith("\"") && ssid.endsWith("\"")) {
            return ssid.substring(1, ssid.length() - 1);
        }
        return ssid;
    }
}
