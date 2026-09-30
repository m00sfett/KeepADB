package de.hohnepeople.keepadb;

import android.content.Context;

/**
 * #654/#655: how the Network card and its list views show network names and addresses. One place
 * applies the privacy mode (#482), so the card head, the status, every list and the toasts hide
 * the same things and a hidden name can never re-appear on a surface that forgot to ask.
 *
 * <p>While the privacy mode is on, a network name is replaced by a placeholder (with a position
 * number in lists, so rows stay distinguishable for TalkBack without revealing anything) and a
 * BSSID is masked by {@link KeepADBAddressMask#maskBssid}. Display only: nothing stored or
 * compared is ever taken from these strings.
 */
final class KeepADBNetworkDisplay {

    private KeepADBNetworkDisplay() {}

    static boolean hidden(Context context) {
        return KeepADBPreferences.isPrivacyModeEnabled(context);
    }

    /**
     * A network name for display. {@code position} is the 1-based row number used to tell hidden
     * rows apart in a list; pass 0 where only one name is shown.
     */
    static String ssid(Context context, String ssid, int position) {
        if (ssid == null || ssid.isEmpty()) {
            return context.getString(R.string.wifi_aps_ssid_unknown);
        }
        return hidden(context) ? placeholder(context, position) : ssid;
    }

    static String bssid(Context context, String bssid) {
        if (bssid == null) return "";
        return hidden(context) ? KeepADBAddressMask.maskBssid(bssid) : bssid;
    }

    /** The name if one is known, otherwise the BSSID -- each hidden or masked as required. */
    static String label(Context context, String ssid, String bssid, int position) {
        if (ssid == null || ssid.isEmpty()) return bssid(context, bssid);
        return ssid(context, ssid, position);
    }

    /**
     * Any stored label (a network name, or a BSSID copy for unnamed entries), for toasts and
     * dialogs that quote it.
     */
    static String quoted(Context context, String storedLabel) {
        if (storedLabel == null) return "";
        return hidden(context) ? placeholder(context, 0) : storedLabel;
    }

    private static String placeholder(Context context, int position) {
        String hiddenName = context.getString(R.string.network_privacy_name_hidden);
        return position > 0 ? hiddenName + " #" + position : hiddenName;
    }
}
