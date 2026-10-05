package de.hohnepeople.keepadb;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The "never" half of the unified network trust model (#760): access points (BSSID) and Wi-Fi
 * names (SSID) on which KeepADB must never re-enable Wireless Debugging automatically and never
 * ask about either.
 *
 * <p>This class only stores and matches. The precedence rule -- a block beats every kind of trust,
 * only the force mode (#763) overrides it -- is applied exactly once, in {@link
 * KeepADBTrustedNetwork#evaluate}, which every automatic re-enable call site already consults.
 * Adding trust never touches this store, so a block can only be lifted by an explicit {@code
 * unblock*} call; the trust entries it overruled stay stored and apply again afterwards.
 *
 * <p>Matching mirrors the trust side: a BSSID compares ignoring case, an SSID compares exactly
 * (equal after quote stripping, no case folding or trimming), which is also how Android matches
 * saved networks. Placeholder values (masked or unset BSSID, unknown SSID) can never be stored:
 * a block on a placeholder would silently match every unreadable network.
 *
 * <p>Persisted as two string sets in {@code keepadb_prefs} ({@link #KEY_BSSIDS}, {@link
 * #KEY_SSIDS}). Both keys are additive; older app versions ignore them. Like the rest of
 * {@code keepadb_prefs} they are excluded from backup and device transfer.
 */
final class KeepADBNetworkBlocklist {
    private static final String PREFS_NAME = "keepadb_prefs";
    static final String KEY_BSSIDS = "blocked_bssids";
    static final String KEY_SSIDS = "blocked_ssids";

    /** Guards the read-modify-write of both sets; callers come from UI and service threads. */
    private static final Object LOCK = new Object();

    private KeepADBNetworkBlocklist() {}

    /** Whether any block exists, for callers that can skip reading the Wi-Fi identity without. */
    static boolean isEmpty(Context context) {
        SharedPreferences preferences = prefs(context);
        return preferences.getStringSet(KEY_BSSIDS, Collections.emptySet()).isEmpty()
                && preferences.getStringSet(KEY_SSIDS, Collections.emptySet()).isEmpty();
    }

    /**
     * Blocks one access point. Returns true if it was newly blocked, false if it was already
     * blocked or {@code bssid} is not a real, matchable address (blank or a placeholder).
     */
    static boolean blockBssid(Context context, String bssid) {
        String key = normalizeBssid(bssid);
        if (key == null) return false;
        return add(context, KEY_BSSIDS, key);
    }

    /** Lifts the block on one access point. Returns true if a block was removed. */
    static boolean unblockBssid(Context context, String bssid) {
        String key = normalizeBssid(bssid);
        if (key == null) return false;
        return remove(context, KEY_BSSIDS, key);
    }

    static boolean isBssidBlocked(Context context, String bssid) {
        String key = normalizeBssid(bssid);
        if (key == null) return false;
        return prefs(context).getStringSet(KEY_BSSIDS, Collections.emptySet()).contains(key);
    }

    /** Blocked access points, lower-case, in a stable (sorted) order. */
    static List<String> getBlockedBssids(Context context) {
        return sorted(prefs(context).getStringSet(KEY_BSSIDS, Collections.emptySet()));
    }

    /**
     * Blocks every access point broadcasting {@code ssid}, whatever its BSSID. Returns true if it
     * was newly blocked, false if it was already blocked or is not a usable name (null, empty or
     * Android's unknown-SSID placeholder).
     */
    static boolean blockSsid(Context context, String ssid) {
        if (!isUsableSsid(ssid)) return false;
        return add(context, KEY_SSIDS, ssid);
    }

    /** Lifts the block on a Wi-Fi name. Returns true if a block was removed. */
    static boolean unblockSsid(Context context, String ssid) {
        if (!isUsableSsid(ssid)) return false;
        return remove(context, KEY_SSIDS, ssid);
    }

    static boolean isSsidBlocked(Context context, String ssid) {
        if (!isUsableSsid(ssid)) return false;
        return prefs(context).getStringSet(KEY_SSIDS, Collections.emptySet()).contains(ssid);
    }

    /**
     * Whether the access point {@code bssid} or the Wi-Fi name {@code ssid} is blocked; either
     * may be null or unknown. For the places that hold a prompt's BSSID and label rather than a
     * live identity.
     */
    static boolean isBlocked(Context context, String bssid, String ssid) {
        return isBssidBlocked(context, bssid) || isSsidBlocked(context, ssid);
    }

    /** Blocked Wi-Fi names, exactly as stored, in a stable (sorted) order. */
    static List<String> getBlockedSsids(Context context) {
        return sorted(prefs(context).getStringSet(KEY_SSIDS, Collections.emptySet()));
    }

    /** Lower-case address, or null for blank input and for the masked/unset placeholders. */
    private static String normalizeBssid(String bssid) {
        if (bssid == null) return null;
        String clean = bssid.trim();
        if (clean.isEmpty()
                || KeepADBNetworkIdentity.REDACTED_BSSID.equalsIgnoreCase(clean)
                || KeepADBNetworkIdentity.UNSET_BSSID.equalsIgnoreCase(clean)) {
            return null;
        }
        return clean.toLowerCase(Locale.ROOT);
    }

    /** Whether {@code ssid} can be stored as a block: not null, empty or the unknown placeholder. */
    static boolean isUsableSsid(String ssid) {
        return ssid != null && !ssid.isEmpty() && !WifiManager.UNKNOWN_SSID.equals(ssid);
    }

    private static boolean add(Context context, String key, String value) {
        synchronized (LOCK) {
            SharedPreferences preferences = prefs(context);
            Set<String> values = new HashSet<>(preferences.getStringSet(key, Collections.emptySet()));
            if (!values.add(value)) return false;
            preferences.edit().putStringSet(key, values).apply();
            return true;
        }
    }

    private static boolean remove(Context context, String key, String value) {
        synchronized (LOCK) {
            SharedPreferences preferences = prefs(context);
            Set<String> values = new HashSet<>(preferences.getStringSet(key, Collections.emptySet()));
            if (!values.remove(value)) return false;
            SharedPreferences.Editor editor = preferences.edit();
            if (values.isEmpty()) {
                editor.remove(key);
            } else {
                editor.putStringSet(key, values);
            }
            editor.apply();
            return true;
        }
    }

    private static List<String> sorted(Set<String> values) {
        List<String> result = new ArrayList<>(values);
        Collections.sort(result);
        return result;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
