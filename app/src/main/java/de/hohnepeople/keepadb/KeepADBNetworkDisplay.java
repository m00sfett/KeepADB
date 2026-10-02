package de.hohnepeople.keepadb;

import android.content.Context;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
        String displayBssid = bssid.toUpperCase(Locale.ROOT);
        return hidden(context) ? KeepADBAddressMask.maskBssid(displayBssid) : displayBssid;
    }

    /**
     * The BSSID with its band directly behind it in brackets, e.g. {@code AA:BB:... (5 GHz)}
     * (#714). {@code band} is a {@link KeepADBAccessPointBand} value; with no known band (#721)
     * only the BSSID is returned -- no brackets, no placeholder text. The band is no secret, so
     * only the address part follows the privacy mode.
     */
    static String bssidWithBand(Context context, String bssid, int band) {
        int label = KeepADBAccessPointBand.labelRes(band);
        if (label == 0) return bssid(context, bssid);
        return context.getString(R.string.network_bssid_with_band, bssid(context, bssid),
                context.getString(label));
    }

    /**
     * The name a user gave an access point (#714), for display. While the privacy mode is on it is
     * replaced by the same placeholder a network name gets, because a name chosen by the user
     * ("Bedroom", "Office") says as much about the place as the network name does.
     */
    static String customName(Context context, String customName) {
        return hidden(context) ? placeholder(context, 0) : customName;
    }

    /**
     * #722: a row title with the access point's own number behind it in brackets, e.g.
     * {@code Office (2)} or {@code Name hidden #1 (2)}. The only "#" in a row is the one of the
     * hidden name; the access point number never leads the row.
     */
    static String withApNumber(String title, int apNumber) {
        return title + " (" + apNumber + ")";
    }

    /**
     * #729: the network names that occur at least twice in the given list (null and empty names
     * are ignored). Only these get an access point number behind them in that list.
     */
    static Set<String> repeatedNames(List<String> names) {
        Set<String> seen = new HashSet<>();
        Set<String> repeated = new HashSet<>();
        for (String name : names) {
            if (name == null || name.isEmpty()) continue;
            if (!seen.add(name)) repeated.add(name);
        }
        return repeated;
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
        return quoted(context, storedLabel, null);
    }

    /** Quotes an SSID or BSSID copy as a network label, formatting a BSSID only for display. */
    static String quoted(Context context, String storedLabel, String bssid) {
        if (storedLabel == null) return "";
        if (hidden(context)) return placeholder(context, 0);
        if (bssid != null && storedLabel.equalsIgnoreCase(bssid)) {
            return KeepADBNetworkDisplay.bssid(context, storedLabel);
        }
        return storedLabel;
    }

    private static String placeholder(Context context, int number) {
        String hiddenName = context.getString(R.string.network_privacy_name_hidden);
        return number > 0 ? hiddenName + " #" + number : hiddenName;
    }
}
