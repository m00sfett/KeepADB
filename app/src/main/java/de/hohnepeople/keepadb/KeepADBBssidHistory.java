package de.hohnepeople.keepadb;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

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
 */
final class KeepADBBssidHistory {
    private static final String PREFS_NAME = "keepadb_prefs";
    private static final String KEY_NEXT_ID = "bssid_history_next_id";
    private static final String KEY_SSID_IDS = "bssid_history_ssid_ids";
    private static final String PREFIX = "bssid_history_";

    /** Oldest BSSID is evicted once a single SSID's history would exceed this size. */
    static final int MAX_BSSIDS_PER_SSID = 8;

    private KeepADBBssidHistory() {}

    /**
     * Records that {@code bssid} was observed broadcasting {@code ssid}. A no-op if either is
     * null/blank -- by design, no entry is ever stored without a known SSID -- or if the BSSID
     * is already known for that SSID. Evicts the oldest BSSID once the per-SSID history would
     * otherwise exceed {@link #MAX_BSSIDS_PER_SSID}.
     */
    static void recordObservation(Context context, String ssid, String bssid) {
        String cleanSsid = clean(ssid);
        String cleanBssid = clean(bssid);
        if (cleanSsid.isEmpty() || cleanBssid.isEmpty()) return;

        SharedPreferences preferences = prefs(context);
        int id = findOrCreateSsidId(preferences, cleanSsid);
        List<String> bssids = readBssids(preferences, id);
        for (String existing : bssids) {
            if (existing.equalsIgnoreCase(cleanBssid)) return;
        }
        bssids.add(cleanBssid);
        while (bssids.size() > MAX_BSSIDS_PER_SSID) {
            bssids.remove(0);
        }
        writeBssids(preferences, id, bssids);
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

    private static int findOrCreateSsidId(SharedPreferences preferences, String ssid) {
        Integer existing = findSsidId(preferences, ssid);
        if (existing != null) return existing;
        int id = preferences.getInt(KEY_NEXT_ID, 1);
        String ids = preferences.getString(KEY_SSID_IDS, "");
        preferences.edit()
                .putString(KEY_SSID_IDS, ids.isEmpty() ? String.valueOf(id) : ids + "," + id)
                .putString(PREFIX + id + "_ssid", ssid)
                .putInt(KEY_NEXT_ID, id + 1)
                .apply();
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

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
