package de.hohnepeople.keepadb;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * Persists the Wi-Fi trust rule used by automatic re-enable call sites.
 *
 * <p>New installations default to all Wi-Fi networks. Allowlist mode is an explicit choice and
 * fails closed when the current BSSID cannot be read or is not listed. Optional SSID matching is
 * a separate default-off widening rule; manual controls do not consult this policy.
 *
 * <p>Upgrade initialization preserves an older implicit allowlist when entries already exist.
 * See docs/trusted-networks.md for the current product rule and permission behavior.
 */
final class KeepADBTrustedNetwork {
    private static final String PREFS_NAME = "keepadb_prefs";
    private static final String KEY_MODE = "trusted_network_mode";
    private static final String KEY_NEXT_ID = "trusted_network_next_id";
    private static final String KEY_IDS = "trusted_network_ids";
    private static final String PREFIX = "trusted_network_";
    private static final String KEY_MODE_INITIALIZED = "trusted_network_mode_initialized";
    private static final String KEY_SSID_MATCHING = "trusted_network_ssid_matching";
    private static final String KEY_SSID_NEXT_ID = "trusted_ssid_next_id";
    private static final String KEY_SSID_IDS = "trusted_ssid_ids";
    private static final String SSID_PREFIX = "trusted_ssid_";

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

    /** One entry of the optional SSID allowlist (#492). */
    static final class SsidEntry {
        final int id;
        final String ssid;

        SsidEntry(int id, String ssid) {
            this.id = id;
            this.ssid = ssid;
        }
    }

    private KeepADBTrustedNetwork() {}

    static String getMode(Context context) {
        ensureModeInitialized(context);
        String mode = prefs(context).getString(KEY_MODE, MODE_ALL_WIFI);
        return MODE_ALLOWLIST.equals(mode) ? MODE_ALLOWLIST : MODE_ALL_WIFI;
    }

    /**
     * One-time, idempotent migration of the #492 default flip (see class javadoc). Runs before
     * every mode read, writes at most once per installation, and is deliberately a *persisted*
     * decision rather than a computed fallback: the "does this installation hold allowlist
     * entries" proxy is only valid at the moment of the upgrade. Once the user starts removing
     * entries -- or adds one while in {@link #MODE_ALL_WIFI}, which the per-access-point trust
     * buttons and the notification allow action permit in either mode -- recomputing it would
     * flip the policy underneath them.
     *
     * <p>Idempotence is keyed on {@link #KEY_MODE_INITIALIZED} rather than on {@link #KEY_MODE}'s
     * presence, so a later explicit switch to {@link #MODE_ALL_WIFI} can never be re-migrated
     * back into {@link #MODE_ALLOWLIST} by a subsequent entry being added.
     */
    private static void ensureModeInitialized(Context context) {
        SharedPreferences preferences = prefs(context);
        if (preferences.getBoolean(KEY_MODE_INITIALIZED, false)) return;
        if (preferences.contains(KEY_MODE)) {
            // An explicit user decision already exists; record it as initialized and keep it.
            preferences.edit().putBoolean(KEY_MODE_INITIALIZED, true).apply();
            return;
        }
        // No mode was ever written. Under the pre-#492 build this installation therefore ran in
        // MODE_ALLOWLIST. Preserve that for anyone who acted on it (has entries) and only apply
        // the new opt-in default to installations that never did.
        boolean hadImplicitAllowlist = !getEntries(context).isEmpty();
        preferences.edit()
                .putString(KEY_MODE, hadImplicitAllowlist ? MODE_ALLOWLIST : MODE_ALL_WIFI)
                .putBoolean(KEY_MODE_INITIALIZED, true)
                .apply();
    }

    /**
     * Whether the SSID allowlist below may additionally authorize a network (#492). A separate
     * opt-in, default off, on top of {@link #MODE_ALLOWLIST} -- it only ever *widens* what
     * allowlist mode accepts and is meaningless without it.
     */
    static boolean isSsidMatchingEnabled(Context context) {
        return prefs(context).getBoolean(KEY_SSID_MATCHING, false);
    }

    static void setSsidMatchingEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_SSID_MATCHING, enabled).apply();
    }

    static List<SsidEntry> getSsidEntries(Context context) {
        String ids = prefs(context).getString(KEY_SSID_IDS, "");
        List<SsidEntry> entries = new ArrayList<>();
        for (String value : ids.split(",")) {
            if (value.isEmpty()) continue;
            try {
                int id = Integer.parseInt(value);
                String ssid = prefs(context).getString(SSID_PREFIX + id + "_ssid", null);
                if (ssid != null) entries.add(new SsidEntry(id, ssid));
            } catch (NumberFormatException ignored) {
                // Ignore a malformed local entry and keep the remaining ones usable.
            }
        }
        return entries;
    }

    /**
     * Adds an SSID to the SSID allowlist. Returns null if {@code ssid} is null/blank; an
     * already-listed SSID is returned unchanged, matching {@link #addBssid}'s dedup behavior.
     * Matching is exact and case-sensitive, so dedup is too: SSIDs are byte strings and two
     * names differing only in case are two different networks.
     */
    static SsidEntry addSsid(Context context, String ssid) {
        String cleanSsid = clean(ssid);
        if (cleanSsid.isEmpty()) return null;
        for (SsidEntry entry : getSsidEntries(context)) {
            if (entry.ssid.equals(cleanSsid)) return entry;
        }
        SharedPreferences preferences = prefs(context);
        int id = preferences.getInt(KEY_SSID_NEXT_ID, 1);
        String ids = preferences.getString(KEY_SSID_IDS, "");
        preferences.edit()
                .putString(SSID_PREFIX + id + "_ssid", cleanSsid)
                .putString(KEY_SSID_IDS, ids.isEmpty() ? String.valueOf(id) : ids + "," + id)
                .putInt(KEY_SSID_NEXT_ID, id + 1)
                .apply();
        return new SsidEntry(id, cleanSsid);
    }

    /**
     * Adds the current readable SSID only when the associated identity has a real BSSID.
     * This prevents storing platform placeholders when identity access is unavailable.
     */
    static SsidEntry addCurrentSsid(Context context) {
        KeepADBNetworkIdentity identity = KeepADBNetworkIdentity.current(context);
        if (!identity.isKnown()) return null;
        return addSsid(context, identity.displaySsid());
    }

    /** The current network's own SSID allowlist entry, or null if unreadable or unlisted. */
    static SsidEntry findSsidEntryForCurrentNetwork(Context context) {
        KeepADBNetworkIdentity identity = KeepADBNetworkIdentity.current(context);
        if (!identity.isKnown()) return null;
        String ssid = identity.displaySsid();
        if (ssid == null || ssid.isEmpty()) return null;
        for (SsidEntry entry : getSsidEntries(context)) {
            if (entry.ssid.equals(ssid)) return entry;
        }
        return null;
    }

    static boolean removeSsid(Context context, int id) {
        SharedPreferences preferences = prefs(context);
        List<SsidEntry> entries = getSsidEntries(context);
        boolean removed = entries.removeIf(entry -> entry.id == id);
        if (!removed) return false;
        SharedPreferences.Editor editor = preferences.edit().remove(SSID_PREFIX + id + "_ssid");
        if (entries.isEmpty()) {
            editor.remove(KEY_SSID_IDS);
        } else {
            StringBuilder ids = new StringBuilder();
            for (SsidEntry entry : entries) {
                if (ids.length() > 0) ids.append(',');
                ids.append(entry.id);
            }
            editor.putString(KEY_SSID_IDS, ids.toString());
        }
        editor.apply();
        return true;
    }

    static void setMode(Context context, String mode) {
        // #492: an explicit choice is by definition an initialized one -- recording that here as
        // well as in ensureModeInitialized() means the migration can never run after it.
        prefs(context).edit()
                .putString(KEY_MODE, mode)
                .putBoolean(KEY_MODE_INITIALIZED, true)
                .apply();
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
        String cleanLabel = clean(label);
        if (cleanLabel.isEmpty()) {
            String ssid = identity.displaySsid();
            cleanLabel = (ssid == null || ssid.isEmpty()) ? identity.bssid : ssid;
        }
        return addBssid(context, identity.bssid, cleanLabel);
    }

    /**
     * Adds an arbitrary BSSID to the allowlist, independent of what network is currently
     * connected (#266: used to add further mesh access points of an SSID that a network's own
     * {@link #addCurrentNetwork} call already made trusted). Returns null only if {@code bssid}
     * is null/blank; an already-listed BSSID is returned unchanged, matching {@link
     * #addCurrentNetwork}'s dedup behavior.
     */
    static Entry addBssid(Context context, String bssid, String label) {
        String cleanBssid = clean(bssid);
        if (cleanBssid.isEmpty()) return null;
        for (Entry entry : getEntries(context)) {
            if (entry.bssid.equalsIgnoreCase(cleanBssid)) return entry;
        }
        SharedPreferences preferences = prefs(context);
        int id = preferences.getInt(KEY_NEXT_ID, 1);
        String cleanLabel = clean(label);
        if (cleanLabel.isEmpty()) cleanLabel = cleanBssid;
        Entry entry = new Entry(id, cleanLabel, cleanBssid);
        write(preferences, entry);
        String ids = preferences.getString(KEY_IDS, "");
        preferences.edit()
                .putString(KEY_IDS, ids.isEmpty() ? String.valueOf(id) : ids + "," + id)
                .putInt(KEY_NEXT_ID, id + 1)
                .apply();
        return entry;
    }

    /** The current network's own allowlist entry, or null if unknown or not yet listed. */
    static Entry findEntryForCurrentNetwork(Context context) {
        KeepADBNetworkIdentity identity = KeepADBNetworkIdentity.current(context);
        if (!identity.isKnown()) return null;
        for (Entry entry : getEntries(context)) {
            if (entry.bssid.equalsIgnoreCase(identity.bssid)) return entry;
        }
        return null;
    }

    /** Removes the currently connected Wi-Fi network's entry, if it has one. Returns the
     * removed entry, or null if the current network's identity is unknown or unlisted. */
    static Entry removeCurrentNetwork(Context context) {
        Entry entry = findEntryForCurrentNetwork(context);
        if (entry == null) return null;
        return remove(context, entry.id) ? entry : null;
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
     * Automatic re-enable is allowed in all-Wi-Fi mode, or in allowlist mode only when the
     * current identity matches a listed BSSID or an enabled exact SSID entry. The caller must
     * independently confirm that Wi-Fi is connected. Manual controls do not use this gate.
     */
    static boolean isCurrentNetworkTrusted(Context context) {
        if (!isAllowlistMode(context)) return true;
        return isTrusted(context, KeepADBNetworkIdentity.current(context));
    }

    /** Why automatic re-enable is currently blocked, for Settings UI messaging. */
    static BlockReason getBlockReason(Context context) {
        if (!isAllowlistMode(context)) return BlockReason.NONE;
        KeepADBNetworkIdentity identity = KeepADBNetworkIdentity.current(context);
        if (isTrusted(context, identity)) return BlockReason.NONE;
        // Not trusted: distinguish "readable but unlisted" from "identity unknown or masked".
        return identity.isKnown() ? BlockReason.UNTRUSTED_NETWORK : BlockReason.IDENTITY_UNAVAILABLE;
    }

    /**
     * Pure trust evaluation for an identity already read by the caller. Unknown or masked BSSID
     * values never match. A known BSSID must be listed, unless the separate optional SSID rule
     * matches the exact readable SSID.
     */
    private static boolean isTrusted(Context context, KeepADBNetworkIdentity identity) {
        if (!identity.isKnown()) return false;
        return matchesAllowlist(context, identity.bssid) || matchesSsidAllowlist(context, identity);
    }

    private static boolean matchesAllowlist(Context context, String bssid) {
        for (Entry entry : getEntries(context)) {
            if (entry.bssid.equalsIgnoreCase(bssid)) return true;
        }
        return false;
    }

    /**
     * Applies the default-off SSID extension only when enabled and the current identity is known.
     * Matching is exact and case-sensitive; a name cannot rescue a masked BSSID.
     */
    private static boolean matchesSsidAllowlist(Context context, KeepADBNetworkIdentity identity) {
        if (!isSsidMatchingEnabled(context)) return false;
        String ssid = identity.displaySsid();
        if (ssid == null || ssid.isEmpty()) return false;
        for (SsidEntry entry : getSsidEntries(context)) {
            if (entry.ssid.equals(ssid)) return true;
        }
        return false;
    }

    /** Test-only seam: exercises the same trust decision as {@link #isCurrentNetworkTrusted}
     * against an explicit identity, since a plain JVM unit test can't make {@link
     * KeepADBNetworkIdentity#current} return anything but an unknown identity (no real
     * WifiManager). Production call sites always go through {@link #isCurrentNetworkTrusted}
     * or {@link #getBlockReason}, never this method directly. */
    static boolean isTrustedForTesting(Context context, KeepADBNetworkIdentity identity) {
        return isTrusted(context, identity);
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
