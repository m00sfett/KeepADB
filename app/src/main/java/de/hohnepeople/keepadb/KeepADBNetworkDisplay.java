package de.hohnepeople.keepadb;

import android.content.Context;

import java.util.HashMap;
import java.util.Map;

/**
 * #654/#655: how the Network card and its list views show network names and addresses. One place
 * applies the privacy mode (#482), so the card head, the status, every list and the toasts hide
 * the same things and a hidden name can never re-appear on a surface that forgot to ask.
 *
 * <p>While the privacy mode is on, a network name is replaced by a placeholder and a BSSID is
 * masked by {@link KeepADBAddressMask#maskBssid}. In a list or view the placeholder carries a
 * number per network name (see {@link Numbering}), so rows stay distinguishable for TalkBack
 * without revealing anything and two rows of one name read alike. Display only: nothing stored or
 * compared is ever taken from these strings.
 */
final class KeepADBNetworkDisplay {

    private KeepADBNetworkDisplay() {}

    static boolean hidden(Context context) {
        return KeepADBPreferences.isPrivacyModeEnabled(context);
    }

    /**
     * The numbers of the hidden placeholders of one list or view: the first network name asked for
     * is #1, the next different one #2, and so on; asking again for a name returns its number.
     * One instance serves one rendering pass of one view -- the current access point and the list
     * entries of that view share it, so the same name reads alike everywhere in the view and two
     * different names never share a number. Numbers follow the order the rows are shown in and
     * carry no information about the name itself. Names are compared exactly, like everywhere
     * else in the app (two names that differ only in case are two networks).
     */
    static final class Numbering {
        private final Map<String, Integer> numbers = new HashMap<>();

        int numberOf(String ssid) {
            Integer known = numbers.get(ssid);
            if (known != null) return known;
            int next = numbers.size() + 1;
            numbers.put(ssid, next);
            return next;
        }
    }

    /**
     * A network name for display. {@code numbering} tells hidden names apart in a list or view;
     * pass {@code null} where only one name is shown.
     */
    static String ssid(Context context, String ssid, Numbering numbering) {
        if (ssid == null || ssid.isEmpty()) {
            return context.getString(R.string.wifi_aps_ssid_unknown);
        }
        if (!hidden(context)) return ssid;
        return placeholder(context, numbering == null ? 0 : numbering.numberOf(ssid));
    }

    static String bssid(Context context, String bssid) {
        if (bssid == null) return "";
        return hidden(context) ? KeepADBAddressMask.maskBssid(bssid) : bssid;
    }

    /** The name if one is known, otherwise the BSSID -- each hidden or masked as required. */
    static String label(Context context, String ssid, String bssid, Numbering numbering) {
        if (ssid == null || ssid.isEmpty()) return bssid(context, bssid);
        return ssid(context, ssid, numbering);
    }

    /**
     * Any stored label (a network name, or a BSSID copy for unnamed entries), for toasts and
     * dialogs that quote it.
     */
    static String quoted(Context context, String storedLabel) {
        if (storedLabel == null) return "";
        return hidden(context) ? placeholder(context, 0) : storedLabel;
    }

    private static String placeholder(Context context, int number) {
        String hiddenName = context.getString(R.string.network_privacy_name_hidden);
        return number > 0 ? hiddenName + " #" + number : hiddenName;
    }
}
