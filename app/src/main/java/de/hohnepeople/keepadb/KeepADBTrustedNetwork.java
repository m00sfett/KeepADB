package de.hohnepeople.keepadb;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * Persisted trusted-network allowlist policy for automatic Keep-Alive re-enable (#245).
 *
 * <p>{@link #MODE_ALLOWLIST} is the default (#260): a freshly installed device is protected
 * immediately, and only Wi-Fi networks the user explicitly adds are trusted for automatic
 * re-enable. {@link #MODE_ALL_WIFI} remains available as an opt-out for users who prefer the
 * original, pre-#245 behavior (any connected Wi-Fi network may trigger auto re-enable). This
 * default applies uniformly -- including to upgrading installations that never set a mode --
 * by deliberate decision (#260); there is no migration path that special-cases existing users.
 * Once in allowlist mode, an unlisted or unrecognizable network is never trusted (fail closed)
 * -- this policy only ever gates *automatic* re-enable call sites; manual toggling is never
 * affected, by design of where callers apply {@link #isCurrentNetworkTrusted(Context)}, not by
 * anything in this class.
 */
final class KeepADBTrustedNetwork {
    private static final String PREFS_NAME = "keepadb_prefs";
    private static final String KEY_MODE = "trusted_network_mode";
    private static final String KEY_NEXT_ID = "trusted_network_next_id";
    private static final String KEY_IDS = "trusted_network_ids";
    private static final String PREFIX = "trusted_network_";

    static final String MODE_ALL_WIFI = "all_wifi";
    static final String MODE_ALLOWLIST = "allowlist";

    /**
     * In-process memory of the last real, BSSID-verified trust decision (#270). Android 12+
     * masks {@code WifiInfo#getBSSID()} to {@link KeepADBNetworkIdentity#REDACTED_BSSID} for
     * background apps without background-location access, which would otherwise make {@link
     * #isCurrentNetworkTrusted} fail closed on every background check even on a genuinely
     * trusted, still-connected network -- KeepADB then never re-enables Wi-Fi debugging in the
     * background at all, only once the user brings the app to the foreground.
     *
     * <p>Design choice: "retain the last verified state for the connection" rather than an
     * SSID-only allowlist fallback. A pure SSID fallback (match the allowlist by SSID whenever
     * BSSID is redacted) was considered and rejected: SSID is a user-chosen, freely reused
     * string (see {@link KeepADBNetworkIdentity} class javadoc), so a rogue access point could
     * impersonate a trusted network's SSID and be matched without ever having its BSSID
     * checked. The chosen design still uses SSID, but only as the continuity signal for a
     * connection whose BSSID *was* actually verified against the allowlist moments earlier in
     * this process -- it never searches the allowlist by SSID. Residual risk: if the device
     * silently roams from the real trusted AP to a rogue AP sharing its SSID while BSSID stays
     * masked the whole time (no observed disconnect in between), the fallback would wrongly
     * keep trusting it. This mirrors the exact tradeoff the issue's own "keep last verified
     * state" proposal accepts, and is a strictly smaller exposure window than the rejected
     * SSID-only allowlist match (which would trust *any* first sighting of that SSID, not only
     * a continuation of an already-verified session).
     */
    private static volatile String lastVerifiedTrustedSsid;

    /**
     * Whether something is currently watching for the connection changes that {@link
     * #lastVerifiedTrustedSsid} is only meaningful in the absence of (#354). The cache above does
     * not stand for "this SSID is trusted"; it stands for "the connection verified moments ago is
     * still the same one". Nothing in a masked reading can establish that on its own -- the claim
     * only holds because {@link KeepADBService}'s Wi-Fi {@code NetworkCallback} invalidates the
     * cache on every {@code onAvailable}/{@code onLost}, i.e. on every event that could have
     * substituted a different connection underneath us.
     *
     * <p>Without that callback registered, nobody clears the cache: a process that stays alive
     * with the service stopped (the app was opened once, a {@code KeepADBUsbReceiver} broadcast
     * woke the process, ...) can carry a verified SSID for an unbounded time across arbitrarily
     * many unobserved network changes, and {@link KeepADBEndpoint}'s recovery pulse and {@link
     * KeepADBUsbHandover}'s automatic handover both read the policy on exactly those paths. So
     * the fallback is only offered while an invalidator is demonstrably live, and fails closed
     * otherwise; this is the same fail-closed direction the rest of the class takes.
     *
     * <p>#270 is not lost by this: its actual use case is the background Keep-Alive re-enable,
     * which by construction only runs while {@link KeepADBService} -- and therefore its callback
     * -- is running. A foreground app is not subject to the platform's background BSSID masking
     * in the first place and never reaches the fallback.
     */
    private static volatile boolean verifiedTrustObserverActive;

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
        String mode = prefs(context).getString(KEY_MODE, MODE_ALLOWLIST);
        return MODE_ALL_WIFI.equals(mode) ? MODE_ALL_WIFI : MODE_ALLOWLIST;
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
        // #270 follow-up: removing an entry revokes trust for whatever BSSID it named. The
        // masked-BSSID fallback below only ever compares against an SSID, not a BSSID, so it
        // can't tell whether the just-removed entry was the one that produced the cached SSID
        // -- e.g. the user is still connected to the now-removed network and its masked-BSSID
        // background reading would otherwise keep matching the stale cache and stay trusted
        // after the user explicitly revoked it. Clearing unconditionally on every removal is
        // the safe (fail-closed) choice: it can cost one extra background cycle of the fallback
        // not applying to an unrelated, still-trusted network, but it can never leave a revoked
        // network fail-open.
        forgetVerifiedTrust();
        return true;
    }

    /**
     * The policy gate for automatic re-enable call sites. In {@link #MODE_ALLOWLIST} (default,
     * #260), the current network's identity must be known and match a listed BSSID -- an
     * unavailable identity or an unlisted network is never trusted. In {@link #MODE_ALL_WIFI},
     * every network is trusted, matching pre-#245 behavior.
     *
     * <p>#348: this method alone is never sufficient to permit an automatic re-enable. It
     * answers "is whatever network we're on acceptable", never "is a Wi-Fi transport actually
     * connected right now" -- {@link #MODE_ALL_WIFI} in particular trusts unconditionally without
     * that question ever being asked. Every automatic re-enable call site (currently {@link
     * KeepADBService#isAutoEnableStillPermitted}, {@link KeepADBService}'s content-observer and
     * {@code recheckAndEnable()} paths, {@link KeepADBUsbHandover#isAutoHandoverStillPermitted}/
     * {@link KeepADBUsbHandover#onRawUsbBroadcast}, and {@link
     * KeepADBEndpoint#maybeSendRecoveryPulse}) must independently require {@link
     * KeepADBService#isWifiConnected(Context)} in addition to this method, never this method by
     * itself.
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
        // isTrusted() already tried the masked-BSSID fallback below -- if it still couldn't
        // decide, tell the difference between "we can see it's unlisted" (identity known) and
        // "we can't even tell what network this is" (identity unknown), same as before #270.
        return identity.isKnown() ? BlockReason.UNTRUSTED_NETWORK : BlockReason.IDENTITY_UNAVAILABLE;
    }

    /** Shared by {@link #isCurrentNetworkTrusted} and {@link #getBlockReason} so callers that
     * already resolved a {@link KeepADBNetworkIdentity} don't trigger a second synchronous
     * WifiManager lookup just to re-derive the same identity. */
    private static boolean isTrusted(Context context, KeepADBNetworkIdentity identity) {
        if (identity.isKnown()) {
            boolean trusted = matchesAllowlist(context, identity.bssid);
            if (trusted) {
                rememberVerifiedTrust(identity);
            } else {
                // A genuinely readable network that isn't listed -- whatever we verified
                // earlier no longer describes what we're connected to right now.
                forgetVerifiedTrust();
            }
            return trusted;
        }
        if (!KeepADBNetworkIdentity.REDACTED_BSSID.equals(identity.bssid)) {
            // Unknown or disconnected identity breaks continuity; only exact platform
            // masking may retain a previously verified connection's trust (#313).
            forgetVerifiedTrust();
            return false;
        }
        if (!verifiedTrustObserverActive) {
            // #354: no live invalidator, so "the connection is still the same one" is an
            // unbacked claim -- see the field's javadoc. Drop the cache instead of trusting it.
            forgetVerifiedTrust();
            return false;
        }
        if (hasMatchingVerifiedTrust(identity)) return true;
        // An unreadable or changed SSID also breaks the verified connection's continuity.
        forgetVerifiedTrust();
        return false;
    }

    private static boolean matchesAllowlist(Context context, String bssid) {
        for (Entry entry : getEntries(context)) {
            if (entry.bssid.equalsIgnoreCase(bssid)) return true;
        }
        return false;
    }

    private static void rememberVerifiedTrust(KeepADBNetworkIdentity identity) {
        // Only remember it if we have an SSID to anchor a later fallback match to; otherwise
        // there's nothing safe to compare a subsequent masked reading against.
        lastVerifiedTrustedSsid = identity.displaySsid();
    }

    /** Invalidates connection-scoped trust, including on an observed network loss. */
    static void forgetVerifiedTrust() {
        lastVerifiedTrustedSsid = null;
    }

    /**
     * Declares whether a live {@code NetworkCallback} is currently invalidating this cache on
     * every connection change (#354). Called by {@link KeepADBService} around its Wi-Fi callback
     * registration; see {@link #verifiedTrustObserverActive}.
     *
     * <p>Every transition -- in either direction -- also drops the cached SSID: the identity of
     * who is watching just changed, so no previously cached reading can still claim uninterrupted
     * observation. The three statements are deliberately ordered so a concurrent reader can only
     * ever observe a *stricter* state than the final one, never "active with a stale entry".
     */
    static void setVerifiedTrustObserverActive(boolean active) {
        verifiedTrustObserverActive = false;
        forgetVerifiedTrust();
        verifiedTrustObserverActive = active;
    }

    private static boolean hasMatchingVerifiedTrust(KeepADBNetworkIdentity identity) {
        String cachedSsid = lastVerifiedTrustedSsid;
        if (cachedSsid == null) return false;
        String currentSsid = identity.displaySsid();
        return currentSsid != null && cachedSsid.equals(currentSsid);
    }

    /** Test-only: clears the in-process verified-trust memory <em>and</em> the #354 observer
     * flag, so tests don't leak state into each other (both are intentionally static/process-wide
     * in production). Resetting to the fail-closed state means a test that wants to exercise the
     * masked-BSSID fallback has to state that precondition explicitly. */
    static void resetVerifiedTrustForTesting() {
        lastVerifiedTrustedSsid = null;
        verifiedTrustObserverActive = false;
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
