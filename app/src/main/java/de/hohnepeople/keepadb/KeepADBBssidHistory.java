package de.hohnepeople.keepadb;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Local, size-bounded observation history of which BSSIDs have been seen under which SSID
 * (#266, mesh-hybrid follow-up to the BSSID-only trusted-network allowlist in {@link
 * KeepADBTrustedNetwork}).
 *
 * <p>This is purely an observation log for the "add other access points of this mesh network
 * too?" convenience prompt in {@code SettingsActivity} -- it never gates trust itself. {@link
 * KeepADBTrustedNetwork#isTrusted} stays BSSID-only; nothing here is consulted by any automatic
 * re-enable call site. Entries live in the same {@code keepadb_prefs} SharedPreferences as the
 * rest of KeepADB's local, non-backed-up settings, so they disappear on uninstall like
 * everything else here, and are deliberately never read by {@link KeepADBRegisterClient} or any
 * other webhook/register-sync code path (this is WLAN observation data, out of scope for that
 * sync).
 *
 * <p>#721: next to each SSID's BSSIDs the history keeps the last Wi-Fi band seen per BSSID, as an
 * additional field ({@code bssid_history_<id>_bands}, {@code BSSID=band} pairs; absent in older
 * data, which then simply has no stored band). Only the latest band is kept -- a new observation
 * overwrites it, there is no history of bands and no timestamp -- and it lives and dies with the
 * BSSID it belongs to: eviction of a BSSID or an SSID takes its band along, so the existing
 * bounds ({@link #MAX_BSSIDS_PER_SSID}, {@link #MAX_SSIDS}) cover it. Writes are the caller's
 * business (the Network card records only while "Observe access points" is on) and {@link
 * #clearBands} deletes every stored band when that option is turned off. The band is display
 * only and never read by the trust decision.
 */
final class KeepADBBssidHistory {
    private static final String PREFS_NAME = "keepadb_prefs";
    private static final String KEY_NEXT_ID = "bssid_history_next_id";
    private static final String KEY_SSID_IDS = "bssid_history_ssid_ids";
    private static final String PREFIX = "bssid_history_";
    private static final String BANDS_SUFFIX = "_bands";

    /** Oldest BSSID is evicted once a single SSID's history would exceed this size. */
    static final int MAX_BSSIDS_PER_SSID = 8;

    /**
     * Least-recently-observed SSID (and its whole BSSID history) is evicted once the total
     * number of distinct SSIDs would exceed this size (#268 -- unbounded growth over a device's
     * lifetime otherwise).
     */
    static final int MAX_SSIDS = 50;

    private KeepADBBssidHistory() {}

    /** One observed (SSID, BSSID) pairing, for display purposes only (#461). */
    static final class Observation {
        final String ssid;
        final String bssid;

        Observation(String ssid, String bssid) {
            this.ssid = ssid;
            this.bssid = bssid;
        }
    }

    /**
     * Records that {@code bssid} was observed broadcasting {@code ssid}, without a band. See
     * {@link #recordObservation(Context, String, String, int)}.
     */
    static void recordObservation(Context context, String ssid, String bssid) {
        recordObservation(context, ssid, bssid, KeepADBAccessPointBand.UNKNOWN);
    }

    /**
     * Records that {@code bssid} was observed broadcasting {@code ssid}. A no-op if either is
     * null/blank -- by design, no entry is ever stored without a known SSID -- or if the BSSID
     * is already known for that SSID. Evicts the oldest BSSID once the per-SSID history would
     * otherwise exceed {@link #MAX_BSSIDS_PER_SSID}.
     *
     * <p>#721: a known {@code band} (a {@link KeepADBAccessPointBand} value) is stored as the
     * last band seen for the BSSID and overwrites an earlier one, also when the BSSID itself was
     * already known. {@link KeepADBAccessPointBand#UNKNOWN} stores nothing and keeps whatever
     * band was stored before: not having measured a band is no observation of a change.
     */
    static void recordObservation(Context context, String ssid, String bssid, int band) {
        String cleanSsid = clean(ssid);
        String cleanBssid = clean(bssid);
        if (cleanSsid.isEmpty() || cleanBssid.isEmpty()) return;

        SharedPreferences preferences = prefs(context);
        int id = findOrCreateSsidId(preferences, cleanSsid);
        List<String> bssids = readBssids(preferences, id);
        boolean known = false;
        for (String existing : bssids) {
            if (existing.equalsIgnoreCase(cleanBssid)) {
                known = true;
                break;
            }
        }
        if (!known) {
            bssids.add(cleanBssid);
            while (bssids.size() > MAX_BSSIDS_PER_SSID) {
                bssids.remove(0);
            }
            writeBssids(preferences, id, bssids);
        }
        updateBands(preferences, id, bssids, cleanBssid, band);
    }

    /**
     * The last band seen per upper-case BSSID, across every SSID (#721); empty when none is
     * stored. Only known bands are returned. A BSSID filed under several SSIDs reports the band
     * of the most recently observed SSID group.
     */
    static Map<String, Integer> getStoredBands(Context context) {
        SharedPreferences preferences = prefs(context);
        Map<String, Integer> result = new HashMap<>();
        for (String value : idsOf(preferences)) {
            try {
                result.putAll(readBands(preferences, Integer.parseInt(value)));
            } catch (NumberFormatException ignored) {
                // Ignore a malformed local id, like every other reader of the id list.
            }
        }
        return result;
    }

    /**
     * Deletes every stored band (#721), for all SSIDs, including any left behind by an SSID
     * that is no longer listed. The BSSIDs and SSIDs themselves stay: the history is not touched.
     * Called when "Observe access points" is turned off, so that turning it off leaves no band
     * on the device.
     */
    static void clearBands(Context context) {
        SharedPreferences preferences = prefs(context);
        SharedPreferences.Editor editor = null;
        for (String key : preferences.getAll().keySet()) {
            if (isBandsKey(key)) {
                if (editor == null) editor = preferences.edit();
                editor.remove(key);
            }
        }
        if (editor != null) editor.apply();
    }

    /**
     * Deletes the whole observation history (#731): every SSID, its BSSIDs and its stored bands.
     * Only called after an explicit "Yes" in the dialog shown when "Observe access points" is
     * turned off; nothing else clears the history.
     */
    static void clearHistory(Context context) {
        SharedPreferences preferences = prefs(context);
        SharedPreferences.Editor editor = null;
        for (String key : preferences.getAll().keySet()) {
            if (key != null && key.startsWith(PREFIX)) {
                if (editor == null) editor = preferences.edit();
                editor.remove(key);
            }
        }
        if (editor != null) editor.apply();
    }

    /** BSSIDs observed under {@code ssid} so far, oldest first. Empty if unknown/unseen. */
    static List<String> getKnownBssids(Context context, String ssid) {
        String cleanSsid = clean(ssid);
        if (cleanSsid.isEmpty()) return new ArrayList<>();
        SharedPreferences preferences = prefs(context);
        Integer id = findSsidId(preferences, cleanSsid);
        if (id == null) return new ArrayList<>();
        return readBssids(preferences, id);
    }

    /**
     * BSSIDs known (observed) for {@code ssid} that aren't already (case-insensitively) present
     * in {@code excludeBssids} -- used to offer only the genuinely new mesh access points when
     * adding a network to the trusted allowlist.
     */
    static List<String> getAdditionalBssids(Context context, String ssid, List<String> excludeBssids) {
        List<String> result = new ArrayList<>();
        for (String candidate : getKnownBssids(context, ssid)) {
            if (!containsIgnoreCase(excludeBssids, candidate)) {
                result.add(candidate);
            }
        }
        return result;
    }

    /**
     * All recorded observations across every known SSID, for the "recently connected access
     * points" display on the main screen (#461) -- a read-only view that never mutates the
     * stored history. Ordered most-recently-observed first: SSID groups in the reverse of
     * {@link #idsOf}'s oldest-first order, and within a group, BSSIDs in the reverse of {@link
     * #readBssids}'s oldest-first order (both lists are appended-to on every new observation, so
     * reversing each yields newest-first without changing how the history itself is stored).
     */
    static List<Observation> getRecentObservations(Context context) {
        SharedPreferences preferences = prefs(context);
        List<String> ids = idsOf(preferences);
        List<Observation> result = new ArrayList<>();
        for (int i = ids.size() - 1; i >= 0; i--) {
            int id;
            try {
                id = Integer.parseInt(ids.get(i));
            } catch (NumberFormatException ignored) {
                continue;
            }
            String ssid = preferences.getString(PREFIX + id + "_ssid", null);
            if (ssid == null) continue;
            List<String> bssids = readBssids(preferences, id);
            for (int j = bssids.size() - 1; j >= 0; j--) {
                result.add(new Observation(ssid, bssids.get(j)));
            }
        }
        return result;
    }

    private static boolean containsIgnoreCase(List<String> values, String candidate) {
        for (String value : values) {
            if (candidate.equalsIgnoreCase(value)) return true;
        }
        return false;
    }

    private static Integer findSsidId(SharedPreferences preferences, String ssid) {
        for (String value : idsOf(preferences)) {
            try {
                int id = Integer.parseInt(value);
                if (ssid.equals(preferences.getString(PREFIX + id + "_ssid", null))) return id;
            } catch (NumberFormatException ignored) {
                // Ignore a malformed local id and keep looking.
            }
        }
        return null;
    }

    /**
     * Returns the local id for {@code ssid}, creating it if unseen. Touches the SSID's
     * least-recently-observed position to most-recently-observed (moves it to the end of {@link
     * #KEY_SSID_IDS}) either way, then evicts the least-recently-observed SSID -- and its whole
     * BSSID history -- once the total distinct-SSID count would exceed {@link #MAX_SSIDS}.
     */
    private static int findOrCreateSsidId(SharedPreferences preferences, String ssid) {
        List<String> ids = idsOf(preferences);
        Integer existing = findSsidId(preferences, ssid);
        SharedPreferences.Editor editor = preferences.edit();
        int id;
        if (existing != null) {
            id = existing;
            ids.remove(String.valueOf(id));
        } else {
            id = preferences.getInt(KEY_NEXT_ID, 1);
            editor.putString(PREFIX + id + "_ssid", ssid).putInt(KEY_NEXT_ID, id + 1);
        }
        ids.add(String.valueOf(id));
        while (ids.size() > MAX_SSIDS) {
            int evictedId = Integer.parseInt(ids.remove(0));
            editor.remove(PREFIX + evictedId + "_ssid").remove(PREFIX + evictedId + "_bssids")
                    .remove(PREFIX + evictedId + BANDS_SUFFIX);
        }
        editor.putString(KEY_SSID_IDS, String.join(",", ids)).apply();
        return id;
    }

    private static List<String> idsOf(SharedPreferences preferences) {
        String ids = preferences.getString(KEY_SSID_IDS, "");
        List<String> result = new ArrayList<>();
        for (String value : ids.split(",")) {
            if (!value.isEmpty()) result.add(value);
        }
        return result;
    }

    private static List<String> readBssids(SharedPreferences preferences, int id) {
        String stored = preferences.getString(PREFIX + id + "_bssids", "");
        List<String> result = new ArrayList<>();
        for (String value : stored.split(",")) {
            if (!value.isEmpty()) result.add(value);
        }
        return result;
    }

    private static void writeBssids(SharedPreferences preferences, int id, List<String> bssids) {
        preferences.edit().putString(PREFIX + id + "_bssids", String.join(",", bssids)).apply();
    }

    /**
     * Stores {@code band} for {@code bssid} (if known) and drops the bands of BSSIDs that are no
     * longer in {@code bssids}, so an evicted BSSID takes its band along. Writes only on change.
     */
    private static void updateBands(SharedPreferences preferences, int id, List<String> bssids,
                                    String bssid, int band) {
        Map<String, Integer> stored = readBands(preferences, id);
        Map<String, Integer> updated = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : stored.entrySet()) {
            if (containsIgnoreCase(bssids, entry.getKey())) updated.put(entry.getKey(), entry.getValue());
        }
        if (KeepADBAccessPointBand.isKnown(band)) {
            updated.put(bssid.toUpperCase(Locale.ROOT), band);
        }
        if (updated.equals(stored)) return;
        if (updated.isEmpty()) {
            preferences.edit().remove(PREFIX + id + BANDS_SUFFIX).apply();
            return;
        }
        List<String> pairs = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : updated.entrySet()) {
            pairs.add(entry.getKey() + "=" + entry.getValue());
        }
        preferences.edit().putString(PREFIX + id + BANDS_SUFFIX, String.join(",", pairs)).apply();
    }

    /** The stored bands of one SSID by upper-case BSSID; malformed or unknown values are skipped. */
    private static Map<String, Integer> readBands(SharedPreferences preferences, int id) {
        String stored = preferences.getString(PREFIX + id + BANDS_SUFFIX, "");
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String pair : stored.split(",")) {
            int separator = pair.lastIndexOf('=');
            if (separator <= 0) continue;
            try {
                int band = Integer.parseInt(pair.substring(separator + 1));
                if (KeepADBAccessPointBand.isKnown(band)) {
                    result.put(pair.substring(0, separator).toUpperCase(Locale.ROOT), band);
                }
            } catch (NumberFormatException ignored) {
                // A malformed value is ignored like a missing one: no band, nothing shown.
            }
        }
        return result;
    }

    private static boolean isBandsKey(String key) {
        if (key == null || !key.startsWith(PREFIX) || !key.endsWith(BANDS_SUFFIX)) return false;
        String middle = key.substring(PREFIX.length(), key.length() - BANDS_SUFFIX.length());
        return !middle.isEmpty() && middle.chars().allMatch(Character::isDigit);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
