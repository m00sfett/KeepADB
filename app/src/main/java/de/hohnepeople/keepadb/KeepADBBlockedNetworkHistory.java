package de.hohnepeople.keepadb;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * Local, size-bounded log of Wi-Fi access points on which automatic Keep-Alive re-enable was
 * recently blocked because they are not on the trusted allowlist (#446).
 *
 * <p>This exists purely for transparency: the Settings screen lists what was recently detected
 * and blocked, and offers to allow it after the fact. Like {@link KeepADBBssidHistory} it never
 * gates trust itself -- {@link KeepADBTrustedNetwork} stays the only authority on what is
 * trusted, and nothing here is consulted by any automatic re-enable call site. Entries live in
 * the same {@code keepadb_prefs} SharedPreferences as the rest of KeepADB's local, non-backed-up
 * settings and are deliberately never read by {@link KeepADBRegisterClient} or any other
 * webhook/register-sync path (WLAN observation data, out of scope for that sync).
 *
 * <p>Only access points with a genuinely readable BSSID are recorded. A redacted or unset BSSID
 * ({@link KeepADBNetworkIdentity#isKnown()} false) is not an actionable entry: it cannot be added
 * to the allowlist without storing a placeholder that every later unidentifiable network would
 * compare equal to, i.e. fail open. That case is surfaced by the existing
 * {@code settings_trusted_network_status_identity_unavailable} status line instead.
 */
final class KeepADBBlockedNetworkHistory {
    private static final String PREFS_NAME = "keepadb_prefs";
    private static final String KEY_ENTRIES = "blocked_network_entries";
    private static final String PREFIX = "blocked_network_";

    /** Oldest entry is evicted once the log would exceed this size (FIFO, newest last). */
    static final int MAX_ENTRIES = 10;

    /** One recently detected, blocked access point. */
    static final class Entry {
        final String bssid;
        /** Human-readable SSID, or null when it could not be read at the time of the block. */
        final String ssid;
        /** {@code System.currentTimeMillis()} of the most recent block for this BSSID. */
        final long lastSeenAt;

        Entry(String bssid, String ssid, long lastSeenAt) {
            this.bssid = bssid;
            this.ssid = ssid;
            this.lastSeenAt = lastSeenAt;
        }

        /** SSID if one is known, otherwise the BSSID -- what the UI and notification show. */
        String label() {
            return (ssid == null || ssid.isEmpty()) ? bssid : ssid;
        }
    }

    private KeepADBBlockedNetworkHistory() {}

    /**
     * Records that automatic re-enable was blocked on {@code identity}. A no-op for an identity
     * whose BSSID is not genuinely readable (see the class javadoc). Re-recording a BSSID that is
     * already listed updates its SSID and timestamp and moves it to the most-recent position
     * rather than adding a duplicate, so a device sitting on one untrusted AP can never fill the
     * log with copies of it.
     */
    static void record(Context context, KeepADBNetworkIdentity identity, long nowMillis) {
        if (identity == null || !identity.isKnown()) return;
        String bssid = clean(identity.bssid);
        if (bssid.isEmpty()) return;
        String ssid = clean(identity.displaySsid());

        SharedPreferences preferences = prefs(context);
        List<Entry> entries = getEntries(context);
        entries.removeIf(entry -> entry.bssid.equalsIgnoreCase(bssid));
        entries.add(new Entry(bssid, ssid, nowMillis));
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(0);
        }
        writeAll(preferences, entries);
    }

    /** Recently blocked access points, oldest first. */
    static List<Entry> getEntries(Context context) {
        SharedPreferences preferences = prefs(context);
        List<Entry> entries = new ArrayList<>();
        for (String key : indexOf(preferences)) {
            String bssid = preferences.getString(PREFIX + key + "_bssid", null);
            if (bssid == null || bssid.isEmpty()) continue;
            String ssid = preferences.getString(PREFIX + key + "_ssid", "");
            long lastSeenAt = preferences.getLong(PREFIX + key + "_seen", 0L);
            entries.add(new Entry(bssid, ssid, lastSeenAt));
        }
        return entries;
    }

    /** Removes {@code bssid} from the log, e.g. once the user allowed it. */
    static boolean remove(Context context, String bssid) {
        String clean = clean(bssid);
        if (clean.isEmpty()) return false;
        List<Entry> entries = getEntries(context);
        if (!entries.removeIf(entry -> entry.bssid.equalsIgnoreCase(clean))) return false;
        writeAll(prefs(context), entries);
        return true;
    }

    static void clear(Context context) {
        writeAll(prefs(context), new ArrayList<>());
    }

    /**
     * Rewrites the whole log. The stored slot index is positional (0..n-1) and every slot is
     * rewritten on each change, so a removal can never leave a stale slot behind that a later
     * write would resurrect; slots beyond the new size are removed explicitly.
     */
    private static void writeAll(SharedPreferences preferences, List<Entry> entries) {
        SharedPreferences.Editor editor = preferences.edit();
        for (String staleKey : indexOf(preferences)) {
            editor.remove(PREFIX + staleKey + "_bssid")
                    .remove(PREFIX + staleKey + "_ssid")
                    .remove(PREFIX + staleKey + "_seen");
        }
        StringBuilder index = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (index.length() > 0) index.append(',');
            index.append(i);
            editor.putString(PREFIX + i + "_bssid", entry.bssid)
                    .putString(PREFIX + i + "_ssid", entry.ssid == null ? "" : entry.ssid)
                    .putLong(PREFIX + i + "_seen", entry.lastSeenAt);
        }
        if (index.length() == 0) {
            editor.remove(KEY_ENTRIES);
        } else {
            editor.putString(KEY_ENTRIES, index.toString());
        }
        editor.apply();
    }

    private static List<String> indexOf(SharedPreferences preferences) {
        String stored = preferences.getString(KEY_ENTRIES, "");
        List<String> result = new ArrayList<>();
        for (String value : stored.split(",")) {
            if (!value.isEmpty()) result.add(value);
        }
        return result;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
