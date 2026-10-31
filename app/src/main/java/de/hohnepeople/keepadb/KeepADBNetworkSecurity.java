package de.hohnepeople.keepadb;

import android.net.wifi.WifiInfo;
import android.os.Build;

/**
 * #860: the security tier of the current Wi-Fi association, for the opt-in check of trusted access
 * points. Android 12 and later report the negotiated type ({@link WifiInfo#getCurrentSecurityType});
 * Android 11 has no reliable source, so there the check does not apply. Matching BSSID and tier is
 * defense in depth, not authentication: a lookalike access point with the same security mode
 * passes. See docs/trusted-networks.md.
 */
final class KeepADBNetworkSecurity {
    static final int TIER_UNKNOWN = 0;
    static final int TIER_OPEN = 1;
    static final int TIER_PERSONAL = 2;
    static final int TIER_ENTERPRISE = 3;

    private KeepADBNetworkSecurity() {}

    static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    /** The tier of the association, or null where Android cannot report it (before Android 12). */
    static Integer tierOf(WifiInfo info) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || info == null) return null;
        try {
            return tierOfType(info.getCurrentSecurityType());
        } catch (RuntimeException e) {
            return TIER_UNKNOWN;
        }
    }

    /**
     * Only a tier change counts as a downgrade: WPA2-PSK and WPA3-SAE are the same tier, so PSK on a
     * WPA2/WPA3 transition access point is not one. Values are WifiInfo's SECURITY_TYPE_* constants,
     * written as literals because some are newer than Android 12.
     */
    static int tierOfType(int type) {
        switch (type) {
            case 0: // OPEN
            case 1: // WEP
            case 6: // OWE
                return TIER_OPEN;
            case 2: // PSK
            case 4: // SAE
            case 7: // WAPI_PSK
            case 13: // DPP
                return TIER_PERSONAL;
            case 3: // EAP
            case 5: // EAP_WPA3_ENTERPRISE_192_BIT
            case 8: // WAPI_CERT
            case 9: // EAP_WPA3_ENTERPRISE
            case 10: // OSEN
            case 11: // PASSPOINT_R1_R2
            case 12: // PASSPOINT_R3
                return TIER_ENTERPRISE;
            default:
                return TIER_UNKNOWN;
        }
    }
}
