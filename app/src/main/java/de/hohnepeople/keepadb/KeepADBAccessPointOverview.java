package de.hohnepeople.keepadb;

import android.content.Context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Read-only presentation model for the "Wi-Fi &amp; Access Points" card on {@link MainActivity}
 * (#461). Combines three existing, independent data sources -- the live connection identity
 * ({@link KeepADBNetworkIdentity}), the observation history ({@link KeepADBBssidHistory}) and
 * the trust allowlist ({@link KeepADBTrustedNetwork}) -- into a single ordered list of display
 * items. It is purely presentational: nothing here is consulted by any automatic re-enable call
 * site, matching the "observation only" contract the two source classes already document, and it
 * never writes to any of them (adding/removing trust from the resulting UI still goes through
 * {@link KeepADBTrustedNetwork} directly, the one and only entry point for that).
 *
 * <p>{@link KeepADB} (the toggle facade) must never reference the allowlist (#245 contract,
 * enforced by {@code KeepADBTrustedNetworkContractTest#keepAdbFacadeNeverReferencesTheAllowlist}).
 * This class -- like {@code SettingsActivity} already does -- reads {@link KeepADBTrustedNetwork}
 * directly instead, so the contract is unaffected.
 *
 * <p>#468: {@link #buildItems} returns the complete, unlimited list -- it never truncates.
 * History alone can already hold up to {@code KeepADBBssidHistory.MAX_SSIDS *
 * KeepADBBssidHistory.MAX_BSSIDS_PER_SSID} entries, so keeping the on-screen card a fixed,
 * phone-friendly size by default -- while still letting the full list be expanded into view --
 * is {@code MainActivity}'s presentation concern, not this pure data-preparation step's.
 */
final class KeepADBAccessPointOverview {

    /** One access point to show: either the one currently connected to, or a recently observed
     * one from {@link KeepADBBssidHistory}. */
    static final class ApItem {
        final String bssid;
        /** Human-readable SSID, or null when it could not be determined. */
        final String ssid;
        final boolean current;
        final boolean trusted;
        /** 1-based position among the items sharing this item's SSID, in display order, or 0
         * when this SSID has only this one known access point (nothing to distinguish). */
        final int meshPosition;
        /** Total number of distinct access points known for this item's SSID. */
        final int meshCount;

        ApItem(String bssid, String ssid, boolean current, boolean trusted,
               int meshPosition, int meshCount) {
            this.bssid = bssid;
            this.ssid = ssid;
            this.current = current;
            this.trusted = trusted;
            this.meshPosition = meshPosition;
            this.meshCount = meshCount;
        }

        /** Whether several distinct access points share this item's SSID (acceptance criterion
         * 4 of #461: a mesh/repeater setup, several BSSIDs broadcasting one SSID). */
        boolean isMeshMember() {
            return meshCount > 1;
        }

        /** SSID if known, otherwise the BSSID -- what the UI shows as the primary label. */
        String label() {
            return (ssid == null || ssid.isEmpty()) ? bssid : ssid;
        }
    }

    private KeepADBAccessPointOverview() {}

    /** Builds the display list from the real device state. Never throws; an unreadable identity
     * or empty history simply yields a shorter (possibly empty) list. */
    static List<ApItem> buildItems(Context context) {
        KeepADBNetworkIdentity current = KeepADBNetworkIdentity.current(context);
        List<KeepADBBssidHistory.Observation> history = KeepADBBssidHistory.getRecentObservations(context);
        List<KeepADBTrustedNetwork.Entry> trustedEntries = KeepADBTrustedNetwork.getEntries(context);
        return buildItems(current, history, trustedEntries);
    }

    /**
     * Pure variant of {@link #buildItems(Context)}: independent of any live Android API beyond
     * the plain data already read out of it, so the ordering, mesh-grouping and trust-matching
     * logic is unit-testable without Robolectric or a real {@code WifiManager}.
     */
    static List<ApItem> buildItems(KeepADBNetworkIdentity current,
                                    List<KeepADBBssidHistory.Observation> history,
                                    List<KeepADBTrustedNetwork.Entry> trustedEntries) {
        // De-duplicated by BSSID, current connection first (if known), then history newest
        // observation first -- a history entry that duplicates the current BSSID is skipped via
        // putIfAbsent rather than overwriting the just-inserted current entry.
        LinkedHashMap<String, String> ssidByBssid = new LinkedHashMap<>();
        if (current != null && current.isKnown()) {
            ssidByBssid.put(normalize(current.bssid), current.displaySsid());
        }
        if (history != null) {
            for (KeepADBBssidHistory.Observation observation : history) {
                if (observation == null || observation.bssid == null || observation.bssid.isEmpty()) continue;
                ssidByBssid.putIfAbsent(normalize(observation.bssid), observation.ssid);
            }
        }

        // Group by SSID across the whole set so mesh labels are correct regardless of where in
        // the (now unlimited) list a same-SSID sibling ends up.
        Map<String, List<String>> bssidsBySsid = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : ssidByBssid.entrySet()) {
            String ssid = entry.getValue();
            if (ssid == null || ssid.isEmpty()) continue;
            bssidsBySsid.computeIfAbsent(ssid, unused -> new ArrayList<>()).add(entry.getKey());
        }

        List<String> trustedBssids = new ArrayList<>();
        if (trustedEntries != null) {
            for (KeepADBTrustedNetwork.Entry entry : trustedEntries) {
                if (entry != null && entry.bssid != null) trustedBssids.add(normalize(entry.bssid));
            }
        }

        String currentBssid = (current != null && current.isKnown()) ? normalize(current.bssid) : null;

        List<ApItem> items = new ArrayList<>();
        for (Map.Entry<String, String> entry : ssidByBssid.entrySet()) {
            String bssid = entry.getKey();
            String ssid = entry.getValue();
            boolean trusted = trustedBssids.contains(bssid);
            boolean isCurrent = bssid.equals(currentBssid);
            int meshPosition = 0;
            int meshCount = 1;
            if (ssid != null && !ssid.isEmpty()) {
                List<String> siblings = bssidsBySsid.get(ssid);
                meshCount = siblings.size();
                if (meshCount > 1) {
                    meshPosition = siblings.indexOf(bssid) + 1;
                }
            }
            items.add(new ApItem(bssid, ssid, isCurrent, trusted, meshPosition, meshCount));
        }
        return items;
    }

    private static String normalize(String bssid) {
        return bssid.toUpperCase(Locale.ROOT);
    }
}
