package de.hohnepeople.keepadb;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * Persisted trusted-network allowlist policy for automatic Keep-Alive re-enable (#245).
 *
 * <p>{@link #MODE_ALL_WIFI} is the default and preserves KeepADB's original behavior (any
 * connected Wi-Fi network may trigger auto re-enable) so upgrading users see no change unless
 * they deliberately opt into {@link #MODE_ALLOWLIST}. Once in allowlist mode, an unlisted or
 * unrecognizable network is never trusted (fail closed) -- this policy only ever gates
 * *automatic* re-enable call sites; manual toggling is never affected, by design of where
 * callers apply {@link #isCurrentNetworkTrusted(Context)}, not by anything in this class.
 */
final class KeepADBTrustedNetwork {
    private static final String PREFS_NAME = "keepadb_prefs";
    private static final String KEY_MODE = "trusted_network_mode";
    private static final String KEY_NEXT_ID = "trusted_network_next_id";
    private static final String KEY_IDS = "trusted_network_ids";
    private static final String PREFIX = "trusted_network_";

    static final String MODE_ALL_WIFI = "all_wifi";
    static final String MODE_ALLOWLIST = "allowlist";

    enum BlockReason { NONE, UNTRUSTED_NETWORK, IDENTITY_UNAVAILABLE }

    static final class Entry {
        final int id;
        final String label;
        final String bssid;

        Entry(int id, String label, String bssid) {
            this.id = id;
            this.label = label;
            this.bssid = bssid;
        }
    }

    private KeepADBTrustedNetwork() {}

    static String getMode(Context context) {
        return prefs(context).getString(KEY_MODE, MODE_ALL_WIFI);
    }

    static void setMode(Context context, String mode) {
        prefs(context).edit().putString(KEY_MODE, mode).apply();
    }

    static boolean isAllowlistMode(Context context) {
        return MODE_ALLOWLIST.equals(getMode(context));
    }

    static List<Entry> getEntries(Context context) {
        String ids = prefs(context).getString(KEY_IDS, "");
        List<Entry> entries = new ArrayList<>();
        for (String value : ids.split(",")) {
            if (value.isEmpty()) continue;
            try {
                Entry entry = read(context, Integer.parseInt(value));
                if (entry != null) entries.add(entry);
            } catch (NumberFormatException ignored) {
                // Ignore a malformed local entry and keep the remaining ones usable.
            }
        }
        return entries;
    }

    /** Adds the currently connected Wi-Fi network. Returns null if its identity isn't known
     * (missing location permission, no Wi-Fi connection) or it is already in the list. */
    static Entry addCurrentNetwork(Context context, String label) {
        KeepADBNetworkIdentity identity = KeepADBNetworkIdentity.current(context);
        if (!identity.isKnown()) return null;
        for (Entry entry : getEntries(context)) {
            if (entry.bssid.equalsIgnoreCase(identity.bssid)) return entry;
        }
        SharedPreferences preferences = prefs(context);
        int id = preferences.getInt(KEY_NEXT_ID, 1);
        String cleanLabel = clean(label);
        if (cleanLabel.isEmpty()) {
            String ssid = identity.displaySsid();
            cleanLabel = (ssid == null || ssid.isEmpty()) ? identity.bssid : ssid;
        }
        Entry entry = new Entry(id, cleanLabel, identity.bssid);
        write(preferences, entry);
        String ids = preferences.getString(KEY_IDS, "");
        preferences.edit()
                .putString(KEY_IDS, ids.isEmpty() ? String.valueOf(id) : ids + "," + id)
                .putInt(KEY_NEXT_ID, id + 1)
                .apply();
        return entry;
    }

    static boolean remove(Context context, int id) {
        SharedPreferences preferences = prefs(context);
        List<Entry> entries = getEntries(context);
        boolean removed = entries.removeIf(entry -> entry.id == id);
        if (!removed) return false;
        SharedPreferences.Editor editor = preferences.edit()
                .remove(PREFIX + id + "_label")
                .remove(PREFIX + id + "_bssid");
        if (entries.isEmpty()) {
            editor.remove(KEY_IDS);
        } else {
            StringBuilder ids = new StringBuilder();
            for (Entry entry : entries) {
                if (ids.length() > 0) ids.append(',');
                ids.append(entry.id);
            }
            editor.putString(KEY_IDS, ids.toString());
        }
        editor.apply();
        return true;
    }

    /**
     * The policy gate for automatic re-enable call sites. In {@link #MODE_ALL_WIFI} (default)
     * every network is trusted, matching pre-#245 behavior. In {@link #MODE_ALLOWLIST}, the
     * current network's identity must be known and match a listed BSSID -- an unavailable
     * identity or an unlisted network is never trusted.
     */
    static boolean isCurrentNetworkTrusted(Context context) {
        if (!isAllowlistMode(context)) return true;
        return isTrusted(context, KeepADBNetworkIdentity.current(context));
    }

    /** Why automatic re-enable is currently blocked, for Settings UI messaging. */
    static BlockReason getBlockReason(Context context) {
        if (!isAllowlistMode(context)) return BlockReason.NONE;
        KeepADBNetworkIdentity identity = KeepADBNetworkIdentity.current(context);
        if (!identity.isKnown()) return BlockReason.IDENTITY_UNAVAILABLE;
        return isTrusted(context, identity) ? BlockReason.NONE : BlockReason.UNTRUSTED_NETWORK;
    }

    /** Shared by {@link #isCurrentNetworkTrusted} and {@link #getBlockReason} so callers that
     * already resolved a {@link KeepADBNetworkIdentity} don't trigger a second synchronous
     * WifiManager lookup just to re-derive the same identity. */
    private static boolean isTrusted(Context context, KeepADBNetworkIdentity identity) {
        if (!identity.isKnown()) return false;
        for (Entry entry : getEntries(context)) {
            if (entry.bssid.equalsIgnoreCase(identity.bssid)) return true;
        }
        return false;
    }

    private static Entry read(Context context, int id) {
        SharedPreferences preferences = prefs(context);
        String bssid = preferences.getString(PREFIX + id + "_bssid", null);
        if (bssid == null) return null;
        String label = preferences.getString(PREFIX + id + "_label", bssid);
        return new Entry(id, label, bssid);
    }

    private static void write(SharedPreferences preferences, Entry entry) {
        preferences.edit()
                .putString(PREFIX + entry.id + "_label", entry.label)
                .putString(PREFIX + entry.id + "_bssid", entry.bssid)
                .apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
