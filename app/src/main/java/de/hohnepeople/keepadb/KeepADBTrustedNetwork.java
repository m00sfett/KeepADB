package de.hohnepeople.keepadb;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * Persisted trusted-network allowlist policy for automatic Keep-Alive re-enable (#245).
 *
 * <p>#492 reversed #260's default: {@link #MODE_ALL_WIFI} is now the default for a new or
 * never-initialized installation, and {@link #MODE_ALLOWLIST} is an explicit opt-in. The reason is
 * measured, not aesthetic -- see the Android identity limits documented in {@code
 * docs/trusted-networks-measurement.md}: outside a visible activity the platform masks SSID and
 * BSSID together, so an allowlist-mode installation cannot confirm a trusted network in the
 * background at all and Keep-Alive's automatic re-enable simply stops working there. Shipping that
 * as the silent default made the app's headline convenience feature fail for reasons the user never
 * chose. The restriction is still offered, now as a deliberate comfort-versus-security trade-off
 * the user opts into with the warning in front of them.
 *
 * <p>Existing installations are not widened by that flip. {@link #ensureModeInitialized} persists
 * {@link #MODE_ALLOWLIST} for any installation that never wrote a mode but does hold allowlist
 * entries: under the pre-#492 build such an installation was running in allowlist mode (that was
 * the default), and a user who had explicitly left allowlist mode already has {@link
 * #MODE_ALL_WIFI} written, so the "has entries" proxy is exact for upgrades and vacuous for fresh
 * installs.
 *
 * <p>Once in allowlist mode, an unlisted or unrecognizable network is never trusted (fail closed)
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
    private static final String KEY_MODE_INITIALIZED = "trusted_network_mode_initialized";
    private static final String KEY_SSID_MATCHING = "trusted_network_ssid_matching";
    private static final String KEY_SSID_NEXT_ID = "trusted_ssid_next_id";
    private static final String KEY_SSID_IDS = "trusted_ssid_ids";
    private static final String SSID_PREFIX = "trusted_ssid_";

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
     *
     * <p>#355: is the "BSSID redacted, SSID still readable" case this fallback exists for
     * actually reachable? Source-level analysis of AOSP's {@code WifiServiceImpl.getConnectionInfo()}
     * says no on stock Android: SSID, BSSID and network ID are hidden together behind one single
     * {@code canAccessScanResults(...)} permission check on the same {@code WifiInfo} snapshot --
     * there's no code path that redacts BSSID while leaving SSID intact, so on AOSP-faithful
     * builds {@link #hasMatchingVerifiedTrust} can never actually be reached with a non-null
     * {@link KeepADBNetworkIdentity#displaySsid()}. This wasn't re-verified with a live
     * permission-toggle experiment on hardware (analysis was judged sufficient for this pass);
     * kept as a defensive fallback anyway rather than removed, because an OEM Wi-Fi stack that
     * splits the two checks (or a future AOSP version that does) would silently reintroduce the
     * failure mode #270 was written to prevent, and the fallback is inert -- not merely unlikely
     * to fire -- everywhere it doesn't apply. See the issue for the full reasoning.
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
        // Same reasoning as setMode(): the policy that produced the cached verified trust just
        // changed, so nothing verified under the old one may carry over.
        forgetVerifiedTrust();
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
     * Adds the currently connected network's SSID. Returns null when the identity is not fully
     * readable -- an {@link KeepADBNetworkIdentity#isKnown()} check is deliberately required on
     * top of a non-null SSID even though only the SSID is stored: per the measurement in {@code
     * docs/trusted-networks-measurement.md} the platform masks SSID and BSSID together, so a
     * readable SSID paired with a masked BSSID is not a state this device produces, and treating
     * it as addable would only open a path to storing a placeholder.
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
        // Same fail-closed reasoning as remove(): a revoked SSID must not keep matching through
        // the connection-scoped cache.
        forgetVerifiedTrust();
        return true;
    }

    static void setMode(Context context, String mode) {
        // #492: an explicit choice is by definition an initialized one -- recording that here as
        // well as in ensureModeInitialized() means the migration can never run after it.
        prefs(context).edit()
                .putString(KEY_MODE, mode)
                .putBoolean(KEY_MODE_INITIALIZED, true)
                .apply();
        // #353: a mode switch is the same security-relevant event as remove() below -- whatever
        // was verified trusted under the old policy must not silently carry over under the new
        // one (e.g. ALLOWLIST -> ALL_WIFI -> ALLOWLIST could otherwise let a masked-BSSID
        // reading of a rogue AP that impersonated a trusted SSID while ALL_WIFI was active reuse
        // stale trust it was never actually verified against). Clearing unconditionally forces a
        // fresh verification after every mode change, fail-closed like every other invalidation
        // site in this class.
        forgetVerifiedTrust();
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
            boolean trusted = matchesAllowlist(context, identity.bssid)
                    || matchesSsidAllowlist(context, identity);
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

    /**
     * The optional SSID match (#492), reached only from the {@code identity.isKnown()} branch of
     * {@link #isTrusted} -- i.e. only when the platform handed us a real, unmasked BSSID for this
     * very reading. That precondition is what keeps this from becoming the SSID-only fallback
     * {@link #lastVerifiedTrustedSsid}'s javadoc rejects: it never rescues a masked reading, so it
     * cannot be satisfied by a rogue access point whose BSSID was never visible.
     *
     * <p>What it does accept is a *readable* access point whose BSSID is not listed but whose SSID
     * is. That is a genuinely weaker security model and the whole point of the separate opt-in: an
     * SSID is a user-chosen string, so any access point that broadcasts the listed name -- a
     * further mesh node, a different radio band of the same router, or an impersonator -- is
     * trusted without its own BSSID ever having been approved. The UI states this at the switch.
     *
     * <p>Matching is exact: equal after the quote-stripping {@link
     * KeepADBNetworkIdentity#displaySsid()} does, with no case folding, trimming, prefix or
     * substring rule, and never against a null/placeholder SSID. A silent normalization here would
     * widen the allowance beyond the name the user actually approved.
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
