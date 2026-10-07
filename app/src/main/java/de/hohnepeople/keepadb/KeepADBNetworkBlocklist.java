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
 * KeepADBTrustedNetwork#evaluate}, which every automatic re-enable call site already consults;
 * the force mode is the overlay in {@link KeepADBTrustedNetwork#evaluateCurrent} and never changes
 * what is stored here.
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
 *
 * <p><strong>Names on a blocked access point (#796, #802).</strong> A blocked access point can
 * additionally carry the Wi-Fi name it was known under when it was blocked and an own display name
 * the user gave it ({@link BlockedAccessPoint}). Both are stored next to the address under
 * {@code BSSID_SSID_PREFIX} and {@code BSSID_NAME_PREFIX} (lower-case address as the key
 * suffix), written in the same edit as the block itself and removed together with it. They are
 * additive and optional: a block without them (every block stored before this change) reads as
 * "no name", is never rewritten or completed afterwards, and an older app version ignores the keys.
 * Neither name is ever consulted by {@link #isBssidBlocked}, {@link #isSsidBlocked} or {@link
 * #isBlocked}: they describe a block for the network list and decide nothing.
 */
final class KeepADBNetworkBlocklist {
    private static final String PREFS_NAME = "keepadb_prefs";
    static final String KEY_BSSIDS = "blocked_bssids";
    static final String KEY_SSIDS = "blocked_ssids";
    /**
     * #796: key prefix of the Wi-Fi name a blocked access point was known under. Like the per-entry
     * keys of the trusted list, a record family (one key per blocked address), not a setting.
     */
    private static final String BSSID_SSID_PREFIX = "blocked_bssid_ssid_";
    /** #802: key prefix of the own display name the user gave a blocked access point. */
    private static final String BSSID_NAME_PREFIX = "blocked_bssid_name_";

    /** Guards the read-modify-write of both sets; callers come from UI and service threads. */
    private static final Object LOCK = new Object();

    /**
     * One blocked access point with what is stored about it. {@code ssid} and {@code customName} are
     * null for a block that was stored without them. Display data only; see the class javadoc.
     */
    static final class BlockedAccessPoint {
        /** Lower-case address, the same form {@link #getBlockedBssids} returns. */
        final String bssid;
        /** The Wi-Fi name it was known under when it was blocked, or null. */
        final String ssid;
        /** The own name the user gave it, or null. */
        final String customName;

        BlockedAccessPoint(String bssid, String ssid, String customName) {
            this.bssid = bssid;
            this.ssid = ssid;
            this.customName = customName;
        }
    }

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
        return blockBssid(context, bssid, null, null);
    }

    /**
     * Blocks one access point and stores the names it is known under (#796, #802) in the same edit
     * as the block. {@code ssid} counts only when it is a usable Wi-Fi name; {@code customName} is
     * normalized like the own name of a trusted access point and counts only when something is left.
     * Returns true if it was newly blocked. Returns false, and changes nothing at all (the names of
     * an existing block included), if it was already blocked or {@code bssid} is not a real,
     * matchable address.
     */
    static boolean blockBssid(Context context, String bssid, String ssid, String customName) {
        String key = normalizeBssid(bssid);
        if (key == null) return false;
        synchronized (LOCK) {
            SharedPreferences preferences = prefs(context);
            Set<String> values = new HashSet<>(
                    preferences.getStringSet(KEY_BSSIDS, Collections.emptySet()));
            if (!values.add(key)) return false;
            SharedPreferences.Editor editor = preferences.edit().putStringSet(KEY_BSSIDS, values);
            // Written or cleared either way, so a leftover of an earlier block of this address (an
            // older app version may have lifted it without knowing the name keys) never resurfaces.
            putOrRemove(editor, BSSID_SSID_PREFIX + key, isUsableSsid(ssid) ? ssid : null);
            String name = KeepADBTrustedNetwork.normalizeCustomName(customName);
            putOrRemove(editor, BSSID_NAME_PREFIX + key, name.isEmpty() ? null : name);
            editor.apply();
            return true;
        }
    }

    /** Lifts the block on one access point and drops its names. Returns true if a block was removed. */
    static boolean unblockBssid(Context context, String bssid) {
        String key = normalizeBssid(bssid);
        if (key == null) return false;
        return remove(context, KEY_BSSIDS, key,
                BSSID_SSID_PREFIX + key, BSSID_NAME_PREFIX + key);
    }

    /**
     * The block on {@code bssid} with the names stored for it, or null when that access point is
     * not blocked. Names stored under an address that is no longer in the block set are ignored.
     */
    static BlockedAccessPoint getBlockedAccessPoint(Context context, String bssid) {
        String key = normalizeBssid(bssid);
        if (key == null) return null;
        SharedPreferences preferences = prefs(context);
        if (!preferences.getStringSet(KEY_BSSIDS, Collections.emptySet()).contains(key)) return null;
        return readBlocked(preferences, key);
    }

    /** Every blocked access point with its names, in the stable order of {@link #getBlockedBssids}. */
    static List<BlockedAccessPoint> getBlockedAccessPoints(Context context) {
        SharedPreferences preferences = prefs(context);
        List<BlockedAccessPoint> result = new ArrayList<>();
        for (String key : sorted(preferences.getStringSet(KEY_BSSIDS, Collections.emptySet()))) {
            result.add(readBlocked(preferences, key));
        }
        return result;
    }

    /**
     * #802: gives a blocked access point its own display name, or resets it when {@code name} is
     * null or blank. Returns false when the access point is not blocked (so a stale dialog can never
     * resurrect a name for a block that was lifted meanwhile). Display only: the block itself, the
     * Wi-Fi name and every decision stay as they are.
     */
    static boolean setBlockedCustomName(Context context, String bssid, String name) {
        String key = normalizeBssid(bssid);
        if (key == null) return false;
        synchronized (LOCK) {
            SharedPreferences preferences = prefs(context);
            if (!preferences.getStringSet(KEY_BSSIDS, Collections.emptySet()).contains(key)) {
                return false;
            }
            String clean = KeepADBTrustedNetwork.normalizeCustomName(name);
            SharedPreferences.Editor editor = preferences.edit();
            putOrRemove(editor, BSSID_NAME_PREFIX + key, clean.isEmpty() ? null : clean);
            editor.apply();
            return true;
        }
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

    /** Removes {@code value} from the set under {@code key}, plus the {@code alsoRemove} keys. */
    private static boolean remove(Context context, String key, String value, String... alsoRemove) {
        synchronized (LOCK) {
            SharedPreferences preferences = prefs(context);
            Set<String> values = new HashSet<>(preferences.getStringSet(key, Collections.emptySet()));
            if (!values.remove(value)) return false;
            SharedPreferences.Editor editor = preferences.edit();
            for (String extra : alsoRemove) editor.remove(extra);
            if (values.isEmpty()) {
                editor.remove(key);
            } else {
                editor.putStringSet(key, values);
            }
            editor.apply();
            return true;
        }
    }

    private static BlockedAccessPoint readBlocked(SharedPreferences preferences, String key) {
        String ssid = preferences.getString(BSSID_SSID_PREFIX + key, null);
        String name = preferences.getString(BSSID_NAME_PREFIX + key, null);
        return new BlockedAccessPoint(key, isUsableSsid(ssid) ? ssid : null,
                name == null || name.isEmpty() ? null : name);
    }

    private static void putOrRemove(SharedPreferences.Editor editor, String key, String value) {
        if (value == null) {
            editor.remove(key);
        } else {
            editor.putString(key, value);
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
