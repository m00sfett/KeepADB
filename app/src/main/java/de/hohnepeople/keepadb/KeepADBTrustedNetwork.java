package de.hohnepeople.keepadb;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * The unified network trust model used by every automatic re-enable call site (#760).
 *
 * <h2>Inputs</h2>
 * <ul>
 *   <li><b>Trusted access points</b> (BSSID entries, {@link Entry}). Trust is per access point.</li>
 *   <li><b>Blocks</b> per BSSID and per Wi-Fi name (SSID, "never"), see {@link
 *       KeepADBNetworkBlocklist}.</li>
 *   <li><b>Comfort switch</b> ({@link #isTrustByNameEnabled}): additionally trust any access point
 *       broadcasting the name of a trusted access point. The names are <em>derived</em> from the
 *       trusted entries ({@link Entry#ssid()}); there is no second name list (#758, F5). Access
 *       points that are blocked do not contribute their name.</li>
 *   <li><b>Policy</b> ({@link #getMode}): {@link #MODE_ALLOWLIST} (trusted access points only, the
 *       default of a new installation) or {@link #MODE_ALL_WIFI}, which is the former default kept
 *       for existing installations as the <em>legacy "all networks" setting</em>: every network
 *       counts as trusted unless it is blocked.</li>
 *   <li><b>Legacy name grants</b> ({@link #isSsidMatchingEnabled} plus {@link #getSsidEntries}):
 *       the pre-#760 name allowlist. Existing installations keep exactly the behavior it gave
 *       them until they decide otherwise; nothing new writes to it from the model.</li>
 * </ul>
 *
 * <h2>Precedence (single implementation: {@link #evaluate})</h2>
 * <ol>
 *   <li>A block on the BSSID, then a block on the SSID: never, in every policy, no prompt. A block
 *       beats trust of the same BSSID; the trust entry stays stored and applies again once the block
 *       is lifted explicitly. Only the force mode (#763) overrides a block; it is an overlay above
 *       this order, applied once in {@link #evaluateCurrent} and stored in {@link
 *       KeepADBForceMode}, so activating or ending it never touches anything this class stores.</li>
 *   <li>Legacy "all networks" policy: trusted, including an unreadable identity (as before).</li>
 *   <li>Trusted access point (BSSID, ignoring case).</li>
 *   <li>Trusted name: derived (comfort switch) or legacy name grant, exact and case-sensitive.</li>
 *   <li>Otherwise a readable identity is unknown (the user is asked), an unreadable one pauses
 *       (fail-closed).</li>
 * </ol>
 * Manual controls never consult this model. Adding trust never lifts a block.
 *
 * <h2>The force mode overlay (#763)</h2>
 * While {@link KeepADBForceMode#isActive} is true, {@link #evaluateCurrent} answers {@link
 * Decision#FORCE_MODE} without reading the Wi-Fi identity: every automatic re-enable goes ahead
 * whatever the network is, blocked or unreadable included (they still need Keep-Alive and a Wi-Fi
 * transport, which the call sites check themselves). The overlay is deliberately <em>not</em> part
 * of {@link #evaluate}: that method also answers "is this prompt's network blocked?" for the trust
 * actions, and a force mode must never let trust be added to a blocked network or a stale prompt be
 * answered as if the block were gone. The read is pure (no lock, no write), so it is safe where the
 * callers hold {@code KeepADB}'s monitor and expires exactly at the deadline without any timer.
 *
 * <h2>Persistence and migration</h2>
 * The model reads the pre-#760 keys in place and never rewrites them: the migration of an existing
 * installation is "interpret what is stored" ({@link #getProtectionLevel} names the result), so it
 * is idempotent by construction and an older app version keeps reading the same data. The only
 * stateful step is the one-time persisted default of the policy ({@link #ensureModeInitialized}):
 * a stored mode is kept verbatim, an installation without one gets {@link #MODE_ALLOWLIST}. New
 * keys ({@link KeepADBNetworkBlocklist}, {@link #KEY_TRUST_BY_NAME}) are additive. See
 * docs/trusted-networks.md for the user-facing rule and the permission behavior.
 *
 * <h2>The Wi-Fi name stored with an entry (#796)</h2>
 * An entry also remembers the Wi-Fi name it was trusted under ({@link Entry#savedSsid}, key {@code
 * trusted_network_<id>_ssid}), written together with the entry when a usable name was known. It is
 * <em>display data for the network list only</em>: {@link Entry#listSsid()} lets the list say
 * "blocked" for an entry whose label is just the BSSID while its remembered name is blocked. It is
 * neither the trust label nor an input of any decision: {@link Entry#ssid()} (the derived comfort
 * switch) keeps reading the label alone, and no method of {@link #evaluate} touches the stored name.
 * An entry stored before this change has no such key and reads as "no name"; nothing completes it
 * afterwards, and an older app version ignores the key.
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
    /** #760: the derived comfort switch ("also trust by Wi-Fi name"), default off. */
    static final String KEY_TRUST_BY_NAME = "trust_by_name";

    static final String MODE_ALL_WIFI = "all_wifi";
    static final String MODE_ALLOWLIST = "allowlist";

    /** Why the UI reports automatic re-enable as blocked; a blocked network reads as untrusted. */
    enum BlockReason { NONE, UNTRUSTED_NETWORK, IDENTITY_UNAVAILABLE }

    /**
     * The outcome of the unified trust evaluation for one network identity (#760). Only the
     * three {@code allowsAutomaticEnable} values let an automatic re-enable go ahead; a blocked
     * network is the one case that must additionally never raise a prompt.
     */
    enum Decision {
        TRUSTED_ACCESS_POINT(true),
        /** Allowed through a trusted Wi-Fi name (derived comfort switch or legacy name grant). */
        TRUSTED_NAME(true),
        /** Allowed only because the legacy "all networks" policy is active. */
        LEGACY_ALL_WIFI(true),
        /** Allowed because the force mode is on (#763): it overrides blocks and trust. */
        FORCE_MODE(true),
        BLOCKED_ACCESS_POINT(false),
        BLOCKED_NAME(false),
        /** Readable identity that is neither trusted nor blocked: the user is asked. */
        UNKNOWN_NETWORK(false),
        /** The identity cannot be read: pause rather than guess. */
        IDENTITY_UNAVAILABLE(false);

        final boolean allowsAutomaticEnable;

        Decision(boolean allowsAutomaticEnable) {
            this.allowsAutomaticEnable = allowsAutomaticEnable;
        }

        boolean isBlocked() {
            return this == BLOCKED_ACCESS_POINT || this == BLOCKED_NAME;
        }
    }

    /**
     * How the stored policy reads in the vocabulary of the protection presets (#758). The mapping
     * from the pre-#760 data is the migration: it never changes what is stored. The two legacy
     * levels are settings no preset reproduces exactly; showing them as such (instead of mapping
     * them onto a preset) is what keeps an existing installation from being silently tightened or
     * loosened until its user decides.
     */
    enum ProtectionLevel {
        /** Trusted access points only. */
        MAXIMUM_SECURITY,
        /** Trusted access points plus access points carrying a trusted access point's name. */
        BALANCED,
        /** The former default "in all Wi-Fi networks": everything not blocked is trusted. */
        LEGACY_ALL_WIFI,
        /** Trusted access points plus the pre-#760 name allowlist, which stays in force as stored. */
        LEGACY_NAME_LIST
    }

    /** Upper bound of an access point's own name (#714); longer input is cut, not rejected. */
    static final int MAX_CUSTOM_NAME_LENGTH = 40;

    /**
     * One allowlisted access point. {@code id} is the stable entry number shown as {@code #id}
     * (#714): it is handed out once, never reused and never changes when other entries come or go.
     * {@code customName} is the optional name the user gave this entry, or null; it is display
     * only and is never read by any trust decision. A trust decision keys on {@code bssid} and, for
     * the derived comfort switch, on the Wi-Fi name in {@link #ssid()}.
     */
    static final class Entry {
        final int id;
        final String label;
        final String bssid;
        final String customName;
        /**
         * #796: the Wi-Fi name known when the entry was trusted, or null (nothing stored: an entry
         * from before the field, or an access point whose name was not readable). Display only, see
         * {@link #listSsid()}.
         */
        final String savedSsid;

        Entry(int id, String label, String bssid) {
            this(id, label, bssid, null);
        }

        Entry(int id, String label, String bssid, String customName) {
            this(id, label, bssid, customName, null);
        }

        Entry(int id, String label, String bssid, String customName, String savedSsid) {
            this.id = id;
            this.label = label;
            this.bssid = bssid;
            this.customName = customName;
            this.savedSsid = savedSsid;
        }

        /**
         * The Wi-Fi name this access point was trusted under, or null when only its address was
         * known. Every writer stores the label as the identity's readable SSID or, when there was
         * none, as the BSSID itself (see {@link #addBssid}), so a label that is not the BSSID is
         * the name. This is what the derived comfort switch trusts; the custom name is display
         * only and never counts.
         */
        String ssid() {
            return ssidFromLabel(label, bssid);
        }

        /**
         * #796: the Wi-Fi name the network list files and evaluates this entry under: the name of
         * the label when it has one, else the name stored with the entry, else null. For the list
         * only -- unlike {@link #ssid()} it feeds no trust decision, in particular not the derived
         * comfort switch, so a stored name never makes another access point trusted.
         */
        String listSsid() {
            String fromLabel = ssid();
            return fromLabel != null ? fromLabel : savedSsid;
        }
    }

    /**
     * The Wi-Fi name carried by a trust label, or null when the label is absent or is just the BSSID
     * fallback. Shared by {@link Entry#ssid()} and the callers that hold only a BSSID and a label
     * (notification actions), so both read a label the same way.
     */
    static String ssidFromLabel(String label, String bssid) {
        if (label == null || label.isEmpty() || label.equalsIgnoreCase(bssid)) return null;
        return label;
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

    /**
     * The stored policy. Only the exact value {@link #MODE_ALL_WIFI} reads as the legacy open
     * policy -- an explicit, persisted choice. Everything else, including a missing or damaged
     * value, is {@link #MODE_ALLOWLIST}: a policy nobody chose must never stand in for the wide one
     * (#760, secure default).
     */
    static String getMode(Context context) {
        ensureModeInitialized(context);
        String mode = prefs(context).getString(KEY_MODE, MODE_ALLOWLIST);
        return MODE_ALL_WIFI.equals(mode) ? MODE_ALL_WIFI : MODE_ALLOWLIST;
    }

    /**
     * One-time, idempotent persistence of the policy default (#492, reworked by #760). Runs before
     * every mode read and writes at most once per installation.
     *
     * <p>A stored mode is an explicit decision and is kept verbatim: every installation that
     * evaluated the rule since the #492 default flip (1.8.9) holds one, so an existing
     * "all networks" installation stays on the legacy open policy and an allowlist installation on
     * the allowlist. Only an installation that has no stored mode yet receives the default, which
     * is now {@link #MODE_ALLOWLIST} (trusted access points only). That covers new installations
     * and an installation that predates the flip and never evaluated the rule since -- which, under
     * the pre-#492 reading, ran an allowlist anyway.
     *
     * <p>The default is a *persisted* decision rather than a computed fallback so a later change of
     * the default can never move an installation underneath its user. Idempotence is keyed on
     * {@link #KEY_MODE_INITIALIZED} rather than on {@link #KEY_MODE}'s presence, so a later explicit
     * switch can never be re-migrated.
     */
    private static void ensureModeInitialized(Context context) {
        SharedPreferences preferences = prefs(context);
        if (preferences.getBoolean(KEY_MODE_INITIALIZED, false)) return;
        if (preferences.contains(KEY_MODE)) {
            // An explicit decision already exists; record it as initialized and keep it.
            preferences.edit().putBoolean(KEY_MODE_INITIALIZED, true).apply();
            return;
        }
        preferences.edit()
                .putString(KEY_MODE, MODE_ALLOWLIST)
                .putBoolean(KEY_MODE_INITIALIZED, true)
                .apply();
    }

    /**
     * The comfort switch of the unified model (#760): additionally trust access points that carry
     * the name of a trusted access point. Default off; the names are derived from the trusted
     * entries ({@link Entry#ssid()}) and are never stored separately. Without effect in the legacy
     * "all networks" policy, where every unblocked network is trusted anyway.
     */
    static boolean isTrustByNameEnabled(Context context) {
        return prefs(context).getBoolean(KEY_TRUST_BY_NAME, false);
    }

    static void setTrustByNameEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_TRUST_BY_NAME, enabled).apply();
        KeepADBWarningState.observe(context);
    }

    /** Whether the pre-#760 name allowlist is in force: its opt-in is on and it holds entries. */
    static boolean hasActiveLegacyNameGrants(Context context) {
        return isSsidMatchingEnabled(context) && !getSsidEntries(context).isEmpty();
    }

    /** The stored policy in the vocabulary of the protection presets; see {@link ProtectionLevel}. */
    static ProtectionLevel getProtectionLevel(Context context) {
        if (!isAllowlistMode(context)) return ProtectionLevel.LEGACY_ALL_WIFI;
        // #762: the legacy name list is asked before the comfort switch. With both name rules on,
        // the effective grant is the wider one (derived names plus the stored list), which no
        // preset reproduces; reading it as BALANCED would show a narrower grant than is in force.
        if (hasActiveLegacyNameGrants(context)) return ProtectionLevel.LEGACY_NAME_LIST;
        if (isTrustByNameEnabled(context)) return ProtectionLevel.BALANCED;
        return ProtectionLevel.MAXIMUM_SECURITY;
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
        KeepADBWarningState.observe(context);
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
        KeepADBWarningState.observe(context);
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
        return addBssid(context, bssid, label, null);
    }

    /**
     * Like {@link #addBssid(Context, String, String)}, and remembers the Wi-Fi name the access
     * point is trusted under (#796): {@code ssid} when it is a usable name, else the name the label
     * carries, else none. The label itself is stored exactly as given, so a caller that knows the
     * name only from elsewhere (a name stored with an earlier block) leaves the label a plain
     * BSSID and the derived name rule unchanged. An already-listed BSSID is returned unchanged,
     * including its (missing) stored name.
     */
    static Entry addBssid(Context context, String bssid, String label, String ssid) {
        String cleanBssid = clean(bssid);
        if (cleanBssid.isEmpty()) return null;
        for (Entry entry : getEntries(context)) {
            if (entry.bssid.equalsIgnoreCase(cleanBssid)) return entry;
        }
        SharedPreferences preferences = prefs(context);
        int id = preferences.getInt(KEY_NEXT_ID, 1);
        String cleanLabel = clean(label);
        if (cleanLabel.isEmpty()) cleanLabel = cleanBssid;
        String savedSsid = KeepADBNetworkBlocklist.isUsableSsid(ssid) ? ssid
                : ssidFromLabel(cleanLabel, cleanBssid);
        if (!KeepADBNetworkBlocklist.isUsableSsid(savedSsid)) savedSsid = null;
        Entry entry = new Entry(id, cleanLabel, cleanBssid, null, savedSsid);
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
                .remove(PREFIX + id + "_bssid")
                .remove(PREFIX + id + "_name")
                .remove(PREFIX + id + "_ssid");
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
     * Gives the entry {@code id} its own display name, or resets it to the default display when
     * {@code name} is null or blank (#714). Returns false when no such entry exists, so a stale
     * dialog can never resurrect a name for an entry that was removed meanwhile. Display only:
     * the BSSID, the stored label and every trust decision stay exactly as they were.
     */
    static boolean setCustomName(Context context, int id, String name) {
        boolean exists = false;
        for (Entry entry : getEntries(context)) {
            if (entry.id == id) {
                exists = true;
                break;
            }
        }
        if (!exists) return false;
        String cleanName = normalizeCustomName(name);
        SharedPreferences.Editor editor = prefs(context).edit();
        if (cleanName.isEmpty()) {
            editor.remove(PREFIX + id + "_name");
        } else {
            editor.putString(PREFIX + id + "_name", cleanName);
        }
        editor.apply();
        return true;
    }

    /**
     * Trims, turns control characters such as line breaks into spaces (a name is one line) and cuts
     * at {@link #MAX_CUSTOM_NAME_LENGTH} without splitting a surrogate pair. Returns "" for no
     * usable name.
     */
    static String normalizeCustomName(String name) {
        if (name == null) return "";
        StringBuilder cleaned = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            cleaned.append(Character.isISOControl(c) ? ' ' : c);
        }
        String trimmed = cleaned.toString().trim();
        if (trimmed.length() <= MAX_CUSTOM_NAME_LENGTH) return trimmed;
        int end = MAX_CUSTOM_NAME_LENGTH;
        if (Character.isHighSurrogate(trimmed.charAt(end - 1))) end--;
        return trimmed.substring(0, end).trim();
    }

    /**
     * Whether an automatic re-enable may go ahead on the current network: the single question every
     * automatic call site asks (service observer and heartbeat, recovery pulse, USB handover,
     * Keep-Alive switch). The caller must independently confirm that Wi-Fi is connected. Manual
     * controls do not use this gate. See the class javadoc for the precedence.
     */
    static boolean isCurrentNetworkTrusted(Context context) {
        return evaluateCurrent(context).allowsAutomaticEnable;
    }

    /** Why automatic re-enable is currently blocked, for Settings UI messaging. */
    static BlockReason getBlockReason(Context context) {
        Decision decision = evaluateCurrent(context);
        if (decision.allowsAutomaticEnable) return BlockReason.NONE;
        // Not allowed: distinguish "readable but not trusted (or blocked)" from "identity unknown".
        return decision == Decision.IDENTITY_UNAVAILABLE
                ? BlockReason.IDENTITY_UNAVAILABLE : BlockReason.UNTRUSTED_NETWORK;
    }

    /**
     * The evaluation for the network the device is connected to right now. A running force mode
     * (#763) overrides everything below and does not read the identity. In the legacy "all
     * networks" policy without any block nothing can change the outcome, so the Wi-Fi identity is
     * not even read -- exactly as before the unified model (#760).
     */
    static Decision evaluateCurrent(Context context) {
        if (KeepADBForceMode.isActive(context)) {
            return Decision.FORCE_MODE;
        }
        if (!isAllowlistMode(context) && KeepADBNetworkBlocklist.isEmpty(context)) {
            return Decision.LEGACY_ALL_WIFI;
        }
        return evaluate(context, KeepADBNetworkIdentity.current(context));
    }

    /**
     * The unified trust evaluation (#760) for an identity already read by the caller; the one place
     * where the precedence is implemented. A block ends the evaluation first, so no policy, no
     * trusted entry and no name rule can allow a blocked network. Only then does the legacy
     * "all networks" policy apply, and it never allows a blocked one. The force mode is not
     * consulted here, see the class javadoc.
     */
    static Decision evaluate(Context context, KeepADBNetworkIdentity identity) {
        Decision decision = evaluateLists(context, identity);
        if (decision.isBlocked() || decision.allowsAutomaticEnable) return decision;
        return isAllowlistMode(context) ? decision : Decision.LEGACY_ALL_WIFI;
    }

    /**
     * Everything except the policy default: blocks, then trusted access point, then trusted name,
     * else unknown or unreadable. Unknown or masked BSSID values never match a trust entry and a
     * name cannot rescue them; a block, in contrast, also applies on a readable SSID alone, because
     * "never" must hold whenever there is evidence of the network.
     */
    private static Decision evaluateLists(Context context, KeepADBNetworkIdentity identity) {
        boolean known = identity != null && identity.isKnown();
        String ssid = identity == null ? null : identity.displaySsid();
        if (known && KeepADBNetworkBlocklist.isBssidBlocked(context, identity.bssid)) {
            return Decision.BLOCKED_ACCESS_POINT;
        }
        if (KeepADBNetworkBlocklist.isSsidBlocked(context, ssid)) {
            return Decision.BLOCKED_NAME;
        }
        if (!known) return Decision.IDENTITY_UNAVAILABLE;
        if (matchesAllowlist(context, identity.bssid)) return Decision.TRUSTED_ACCESS_POINT;
        if (matchesTrustedName(context, ssid)) return Decision.TRUSTED_NAME;
        return Decision.UNKNOWN_NETWORK;
    }

    /**
     * Whether {@code ssid} is trusted by name right now: the derived comfort switch or the legacy
     * name allowlist, each only while its own switch is on. For the Settings card, which explains
     * the decision and must not reimplement it.
     */
    static boolean isNameTrusted(Context context, String ssid) {
        return matchesTrustedName(context, ssid);
    }

    private static boolean matchesAllowlist(Context context, String bssid) {
        for (Entry entry : getEntries(context)) {
            if (entry.bssid.equalsIgnoreCase(bssid)) return true;
        }
        return false;
    }

    /**
     * The name rules, both default off and both only ever for a known identity (the caller has
     * already established that): the derived comfort switch and the pre-#760 name allowlist.
     * Matching is exact and case-sensitive; a name cannot rescue a masked BSSID.
     */
    private static boolean matchesTrustedName(Context context, String ssid) {
        if (ssid == null || ssid.isEmpty()) return false;
        return matchesDerivedName(context, ssid) || matchesLegacyNameGrant(context, ssid);
    }

    /**
     * #760/F5: the name of a trusted access point, but only of one that is not blocked itself -- a
     * block on the access point overrides its trust together with everything derived from it.
     */
    private static boolean matchesDerivedName(Context context, String ssid) {
        if (!isTrustByNameEnabled(context)) return false;
        for (Entry entry : getEntries(context)) {
            if (ssid.equals(entry.ssid())
                    && !KeepADBNetworkBlocklist.isBssidBlocked(context, entry.bssid)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesLegacyNameGrant(Context context, String ssid) {
        if (!isSsidMatchingEnabled(context)) return false;
        for (SsidEntry entry : getSsidEntries(context)) {
            if (entry.ssid.equals(ssid)) return true;
        }
        return false;
    }

    /**
     * Test-only seam: exercises the trust lists (blocks, trusted access points, names) against an
     * explicit identity, independent of the policy default, since a plain JVM unit test can't make
     * {@link KeepADBNetworkIdentity#current} return anything but an unknown identity (no real
     * WifiManager). Production call sites always go through {@link #isCurrentNetworkTrusted} or
     * {@link #getBlockReason}, never this method directly.
     */
    static boolean isTrustedForTesting(Context context, KeepADBNetworkIdentity identity) {
        return evaluateLists(context, identity).allowsAutomaticEnable;
    }

    private static Entry read(Context context, int id) {
        SharedPreferences preferences = prefs(context);
        String bssid = preferences.getString(PREFIX + id + "_bssid", null);
        if (bssid == null) return null;
        String label = preferences.getString(PREFIX + id + "_label", bssid);
        String customName = preferences.getString(PREFIX + id + "_name", null);
        if (customName != null && customName.isEmpty()) customName = null;
        String savedSsid = preferences.getString(PREFIX + id + "_ssid", null);
        if (!KeepADBNetworkBlocklist.isUsableSsid(savedSsid)) savedSsid = null;
        return new Entry(id, label, bssid, customName, savedSsid);
    }

    private static void write(SharedPreferences preferences, Entry entry) {
        SharedPreferences.Editor editor = preferences.edit()
                .putString(PREFIX + entry.id + "_label", entry.label)
                .putString(PREFIX + entry.id + "_bssid", entry.bssid);
        if (entry.savedSsid != null) editor.putString(PREFIX + entry.id + "_ssid", entry.savedSsid);
        editor.apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
