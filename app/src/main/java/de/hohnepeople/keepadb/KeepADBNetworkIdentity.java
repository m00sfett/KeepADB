package de.hohnepeople.keepadb;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

/**
 * Identifies the currently connected Wi-Fi network for the trusted-network allowlist (#245).
 *
 * <p>BSSID is the match key (identifies one physical access point; SSID is a user-chosen,
 * freely reused string, so two unrelated networks can share the same name). SSID is kept only
 * as a human-readable label. Reading either field unmasked requires {@link
 * android.Manifest.permission#ACCESS_FINE_LOCATION} on Android, and location services must be
 * enabled. Outside an active UI window, Android also requires an active while-in-use location
 * context, which {@link KeepADBService} provides via its {@code connectedDevice|location}
 * foreground service type (#606, C2). If location permission is missing, location is off, or no
 * while-in-use context exists, the platform returns placeholder values {@link
 * WifiManager#UNKNOWN_SSID} and {@link #REDACTED_BSSID} ("02:00:00:00:00:00") instead of
 * throwing. Therefore {@link #isKnown()} must be checked before treating the identity as a real,
 * matchable value -- using placeholders as-is would make unrecognized or disconnected networks
 * compare equal (fail-open).
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
     * Human-readable SSID with the surrounding quotes WifiInfo#getSSID() adds, if present.
     * Returns null for {@link WifiManager#UNKNOWN_SSID} (BSSID known, SSID unreadable at the
     * moment of the query -- issue #269): treating that placeholder as a real SSID would file
     * BSSID-history observations and mesh-add labels under the literal placeholder string
     * instead of correctly falling back to "no SSID known". This is a defensive measure, not a
     * reaction to an observed platform behavior: every masking measurement so far (see
     * {@code docs/trusted-networks-measurement.md}) found SSID and BSSID masked together or both
     * available, never one without the other. The check stays because nothing guarantees that
     * split state cannot occur on an untested OEM Wi-Fi stack or a future Android version.
     */
    String displaySsid() {
        if (ssid == null || WifiManager.UNKNOWN_SSID.equals(ssid)) return null;
        if (ssid.length() >= 2 && ssid.startsWith("\"") && ssid.endsWith("\"")) {
            return ssid.substring(1, ssid.length() - 1);
        }
        return ssid;
    }
}
