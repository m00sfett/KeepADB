package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import androidx.test.core.app.ApplicationProvider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowWifiInfo;

import de.hohnepeople.keepadb.KeepADBTrustedNetwork.BlockReason;
import de.hohnepeople.keepadb.KeepADBTrustedNetwork.ProtectionLevel;

/**
 * #760: the migration is "interpret what is stored, rewrite nothing". This pins what that promises.
 *
 * <ul>
 *   <li><b>Behavior kept.</b> For every stored pre-#760 state, the production decision equals the
 *       decision the 1.9.28 rules gave -- an oracle written here from the old rules, not from the
 *       new code.</li>
 *   <li><b>Nothing lost, idempotent.</b> Reading, evaluating and "migrating" any legacy state
 *       leaves every legacy key exactly as it was; the only one-time write is the initialized flag
 *       of an installation that never had one, and a second run changes nothing.</li>
 *   <li><b>Downgrade-readable.</b> What the new code writes is read back unchanged by the 1.9.28
 *       reader; the keys it adds are ignored by it. The one thing a downgrade loses is the block
 *       lists, which an older version does not know -- asserted, so it stays a documented fact.</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBTrustMigrationTest {

    private static final String HOME = "Home";
    private static final String HOME_BSSID = "aa:bb:cc:dd:ee:01";

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = ApplicationProvider.getApplicationContext();

    @Before
    public void setUp() {
        prefs().edit().clear().commit();
    }

    @After
    public void tearDown() {
        prefs().edit().clear().commit();
    }

    // --- Behavior is kept ---------------------------------------------------------------------------

    /** One stored legacy state, written the way 1.9.28 wrote it. */
    private static final class Legacy {
        final String mode;
        final boolean ssidMatching;
        final boolean bssidEntry;
        final boolean ssidEntry;

        Legacy(String mode, boolean ssidMatching, boolean bssidEntry, boolean ssidEntry) {
            this.mode = mode;
            this.ssidMatching = ssidMatching;
            this.bssidEntry = bssidEntry;
            this.ssidEntry = ssidEntry;
        }

        void seed(SharedPreferences preferences, boolean initializedFlag) {
            SharedPreferences.Editor editor = preferences.edit()
                    .putString("trusted_network_mode", mode)
                    .putBoolean("trusted_network_ssid_matching", ssidMatching);
            if (initializedFlag) editor.putBoolean("trusted_network_mode_initialized", true);
            if (bssidEntry) {
                editor.putString("trusted_network_ids", "1")
                        .putString("trusted_network_1_label", HOME)
                        .putString("trusted_network_1_bssid", HOME_BSSID)
                        .putInt("trusted_network_next_id", 2);
            }
            if (ssidEntry) {
                editor.putString("trusted_ssid_ids", "1")
                        .putString("trusted_ssid_1_ssid", "Mesh")
                        .putInt("trusted_ssid_next_id", 2);
            }
            editor.commit();
        }

        /** The 1.9.28 decision, restated from its rules rather than from the new code. */
        boolean oldTrusted(String ssid, String bssid) {
            if (!"allowlist".equals(mode)) return true;
            boolean known = bssid != null && !bssid.isEmpty()
                    && !bssid.equalsIgnoreCase("02:00:00:00:00:00")
                    && !bssid.equalsIgnoreCase("00:00:00:00:00:00");
            if (!known) return false;
            if (bssidEntry && bssid.equalsIgnoreCase(HOME_BSSID)) return true;
            return ssidMatching && ssidEntry && "Mesh".equals(ssid);
        }

        BlockReason oldReason(String ssid, String bssid) {
            if (!"allowlist".equals(mode)) return BlockReason.NONE;
            if (oldTrusted(ssid, bssid)) return BlockReason.NONE;
            boolean known = bssid != null && !bssid.isEmpty()
                    && !bssid.equalsIgnoreCase("02:00:00:00:00:00")
                    && !bssid.equalsIgnoreCase("00:00:00:00:00:00");
            return known ? BlockReason.UNTRUSTED_NETWORK : BlockReason.IDENTITY_UNAVAILABLE;
        }

        ProtectionLevel expectedLevel() {
            if (!"allowlist".equals(mode)) return ProtectionLevel.LEGACY_ALL_WIFI;
            return ssidMatching && ssidEntry
                    ? ProtectionLevel.LEGACY_NAME_LIST : ProtectionLevel.MAXIMUM_SECURITY;
        }

        @Override
        public String toString() {
            return "mode=" + mode + " ssidMatching=" + ssidMatching + " bssidEntry=" + bssidEntry
                    + " ssidEntry=" + ssidEntry;
        }
    }

    private static final String[][] NETWORKS = {
            {"Home", "AA:BB:CC:DD:EE:01"},               // the listed access point
            {"Home", "aa:bb:cc:dd:ee:99"},               // same name, other address
            {"Mesh", "11:22:33:44:55:66"},               // the legacy-listed name
            {"Mesh", "02:00:00:00:00:00"},               // masked
            {"Cafe", "cc:cc:cc:cc:cc:01"},               // unknown
            {"Cafe", "00:00:00:00:00:00"},               // unset placeholder
            {"", null},                                  // nothing readable
    };

    @Test
    public void everyStoredLegacyStateDecidesExactlyAsItDidBeforeTheUnifiedModel() {
        int trusted = 0;
        int denied = 0;
        int states = 0;
        for (String mode : new String[] {"all_wifi", "allowlist"}) {
            for (boolean ssidMatching : new boolean[] {false, true}) {
                for (boolean bssidEntry : new boolean[] {false, true}) {
                    for (boolean ssidEntry : new boolean[] {false, true}) {
                        Legacy legacy = new Legacy(mode, ssidMatching, bssidEntry, ssidEntry);
                        prefs().edit().clear().commit();
                        legacy.seed(prefs(), true);
                        states++;
                        assertEquals(legacy.toString(), legacy.expectedLevel(),
                                KeepADBTrustedNetwork.getProtectionLevel(context));
                        for (String[] network : NETWORKS) {
                            connectTo(network[0], network[1]);
                            boolean expected = legacy.oldTrusted(network[0], network[1]);
                            String label = legacy + " on " + network[0] + "/" + network[1];
                            assertEquals(label, expected,
                                    KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
                            assertEquals(label, legacy.oldReason(network[0], network[1]),
                                    KeepADBTrustedNetwork.getBlockReason(context));
                            if (expected) trusted++; else denied++;
                        }
                    }
                }
            }
        }
        assertEquals(16, states);
        assertTrue("The matrix must contain trusted cases", trusted > 0);
        assertTrue("The matrix must contain denied cases", denied > 0);
    }

    @Test
    public void theLegacyOpenPolicyDoesNotEvenReadTheWifiIdentityWithoutBlocks() {
        // Exactly as before: in the former default nothing about the network can change the answer,
        // so the Wi-Fi identity is not read at all. A context that fails on any system service
        // proves it; a regression that reads the identity first would throw or answer otherwise.
        prefs().edit().putString("trusted_network_mode", "all_wifi")
                .putBoolean("trusted_network_mode_initialized", true).commit();
        Context failing = new android.content.ContextWrapper(context) {
            @Override
            public Object getSystemService(String name) {
                throw new AssertionError("The identity must not be read: " + name);
            }

            @Override
            public Context getApplicationContext() {
                return this;
            }
        };

        assertTrue(KeepADBTrustedNetwork.isCurrentNetworkTrusted(failing));
        assertEquals(BlockReason.NONE, KeepADBTrustedNetwork.getBlockReason(failing));
        assertEquals(KeepADBTrustedNetwork.Decision.LEGACY_ALL_WIFI,
                KeepADBTrustedNetwork.evaluateCurrent(failing));
    }

    // --- Every era of the stored mode ---------------------------------------------------------------

    @Test
    public void theStoredModeOfEveryEraIsKeptAndANewInstallationGetsTheSecureDefault() {
        // 1.8.9 .. 1.9.28 default that was persisted on the first read: "all networks".
        prefs().edit().clear().putString("trusted_network_mode", "all_wifi")
                .putBoolean("trusted_network_mode_initialized", true).commit();
        assertEquals(KeepADBTrustedNetwork.MODE_ALL_WIFI, KeepADBTrustedNetwork.getMode(context));
        assertEquals(ProtectionLevel.LEGACY_ALL_WIFI, KeepADBTrustedNetwork.getProtectionLevel(context));

        // An explicit allowlist decision.
        prefs().edit().clear().putString("trusted_network_mode", "allowlist")
                .putBoolean("trusted_network_mode_initialized", true).commit();
        assertEquals(KeepADBTrustedNetwork.MODE_ALLOWLIST, KeepADBTrustedNetwork.getMode(context));

        // A mode key without the flag (written, never read since): the decision is kept and only
        // the flag is added.
        prefs().edit().clear().putString("trusted_network_mode", "all_wifi").commit();
        assertEquals(KeepADBTrustedNetwork.MODE_ALL_WIFI, KeepADBTrustedNetwork.getMode(context));
        assertTrue(prefs().getBoolean("trusted_network_mode_initialized", false));

        // Pre-#492: entries but no mode key at all ran as an allowlist and still do.
        prefs().edit().clear()
                .putString("trusted_network_ids", "1")
                .putString("trusted_network_1_label", HOME)
                .putString("trusted_network_1_bssid", HOME_BSSID)
                .putInt("trusted_network_next_id", 2).commit();
        assertEquals(KeepADBTrustedNetwork.MODE_ALLOWLIST, KeepADBTrustedNetwork.getMode(context));
        assertEquals(1, KeepADBTrustedNetwork.getEntries(context).size());

        // A new installation: nothing stored, secure default, persisted once.
        prefs().edit().clear().commit();
        assertEquals(KeepADBTrustedNetwork.MODE_ALLOWLIST, KeepADBTrustedNetwork.getMode(context));
        assertEquals("allowlist", prefs().getString("trusted_network_mode", null));
        assertTrue(prefs().getBoolean("trusted_network_mode_initialized", false));
        assertEquals(ProtectionLevel.MAXIMUM_SECURITY, KeepADBTrustedNetwork.getProtectionLevel(context));
    }

    @Test
    public void theProtectionLevelMapsEveryStoredCombinationWithoutPickingAPresetForALegacyOne() {
        // The two legacy levels are the ones no preset reproduces exactly; they must stay visible as
        // such. A silent mapping onto a preset would tighten or loosen the installation.
        Map<String, ProtectionLevel> expected = new HashMap<>();
        expected.put("all_wifi|off|none", ProtectionLevel.LEGACY_ALL_WIFI);
        expected.put("all_wifi|on|listed", ProtectionLevel.LEGACY_ALL_WIFI);
        expected.put("allowlist|off|none", ProtectionLevel.MAXIMUM_SECURITY);
        expected.put("allowlist|off|listed", ProtectionLevel.MAXIMUM_SECURITY);   // list inert
        expected.put("allowlist|on|none", ProtectionLevel.MAXIMUM_SECURITY);      // grants nothing
        expected.put("allowlist|on|listed", ProtectionLevel.LEGACY_NAME_LIST);
        for (Map.Entry<String, ProtectionLevel> row : expected.entrySet()) {
            String[] parts = row.getKey().split("\\|");
            new Legacy(parts[0], "on".equals(parts[1]), false, "listed".equals(parts[2]))
                    .seed(prefs(), true);
            assertEquals(row.getKey(), row.getValue(), KeepADBTrustedNetwork.getProtectionLevel(context));
            prefs().edit().clear().commit();
        }
        // The new derived switch is the only way to the balanced level, and it never raises the
        // legacy ones: with the allowlist on it is BALANCED, in the legacy open policy it is inert.
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBTrustedNetwork.setTrustByNameEnabled(context, true);
        assertEquals(ProtectionLevel.BALANCED, KeepADBTrustedNetwork.getProtectionLevel(context));
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        assertEquals(ProtectionLevel.LEGACY_ALL_WIFI, KeepADBTrustedNetwork.getProtectionLevel(context));
    }

    /**
     * #762: both name rules on is the widest grant (derived names plus the stored list); it must
     * read as the legacy name list, not as the narrower balanced preset. Each rule alone keeps its
     * own level, so the order of the two checks cannot be swapped back unnoticed.
     */
    @Test
    public void bothNameRulesOnReadAsTheWiderLegacyNameListNotAsBalanced() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBTrustedNetwork.setSsidMatchingEnabled(context, true);
        KeepADBTrustedNetwork.addSsid(context, "Mesh");
        KeepADBTrustedNetwork.setTrustByNameEnabled(context, true);
        assertEquals(ProtectionLevel.LEGACY_NAME_LIST, KeepADBTrustedNetwork.getProtectionLevel(context));

        // The other sides: the switch alone is balanced, the list alone is the legacy list.
        KeepADBTrustedNetwork.setSsidMatchingEnabled(context, false);
        assertEquals(ProtectionLevel.BALANCED, KeepADBTrustedNetwork.getProtectionLevel(context));
        KeepADBTrustedNetwork.setSsidMatchingEnabled(context, true);
        KeepADBTrustedNetwork.setTrustByNameEnabled(context, false);
        assertEquals(ProtectionLevel.LEGACY_NAME_LIST, KeepADBTrustedNetwork.getProtectionLevel(context));
    }

    // --- Nothing lost, idempotent -----------------------------------------------------------------------

    @Test
    public void readingAnInitializedLegacyInstallationWritesNothingAtAll() {
        Legacy legacy = new Legacy("allowlist", true, true, true);
        legacy.seed(prefs(), true);
        KeepADBBlockedNetworkHistory.record(context, new KeepADBNetworkIdentity("Cafe", "cc:cc:cc:cc:cc:01"), 5L);
        seedObservationHistory();
        Map<String, ?> before = new HashMap<>(prefs().getAll());
        assertFalse("Precondition: the legacy data is there", before.isEmpty());

        exerciseEveryRead();

        assertEquals("No legacy key may change, move or disappear, and nothing may be added",
                before, prefs().getAll());
    }

    @Test
    public void theMigrationOfAnUninitializedInstallationAddsOnlyTheFlagAndIsIdempotent() {
        Legacy legacy = new Legacy("all_wifi", true, true, true);
        legacy.seed(prefs(), false);
        KeepADBBlockedNetworkHistory.record(context, new KeepADBNetworkIdentity("Cafe", "cc:cc:cc:cc:cc:01"), 5L);
        seedObservationHistory();
        Map<String, Object> before = new HashMap<>(prefs().getAll());

        exerciseEveryRead();
        Map<String, Object> afterFirst = new HashMap<>(prefs().getAll());

        Map<String, Object> expected = new HashMap<>(before);
        expected.put("trusted_network_mode_initialized", true);
        assertEquals("The only one-time write is the initialized flag", expected, afterFirst);

        exerciseEveryRead();
        assertEquals("A second run must change nothing", afterFirst, prefs().getAll());
    }

    @Test
    public void historyAndObservationStoresStayUntouchedAndAreNotMigratedIntoTheModel() {
        KeepADBBlockedNetworkHistory.record(context, new KeepADBNetworkIdentity("Cafe", "cc:cc:cc:cc:cc:01"), 5L);
        seedObservationHistory();

        exerciseEveryRead();

        assertEquals(1, KeepADBBlockedNetworkHistory.getEntries(context).size());
        assertEquals("11:22:33:44:55:66", prefs().getString("bssid_history_1_bssids", null));
        // They never had trust meaning and still have none: neither the recorded nor the observed
        // access point is trusted or blocked because of them.
        assertTrue(KeepADBTrustedNetwork.getEntries(context).isEmpty());
        assertTrue(KeepADBNetworkBlocklist.isEmpty(context));
        connectTo("Cafe", "cc:cc:cc:cc:cc:01");
        assertFalse(KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
        assertEquals(KeepADBTrustedNetwork.Decision.UNKNOWN_NETWORK,
                KeepADBTrustedNetwork.evaluateCurrent(context));
    }

    /** The observation history as the former writer stored it (#788: nothing writes it any more). */
    private void seedObservationHistory() {
        prefs().edit().putString("bssid_history_ssid_ids", "1").putInt("bssid_history_next_id", 2)
                .putString("bssid_history_1_ssid", "Mesh")
                .putString("bssid_history_1_bssids", "11:22:33:44:55:66").commit();
    }

    // --- Downgrade stays readable ---------------------------------------------------------------------------

    @Test
    public void whatTheNewCodeWritesIsReadBackUnchangedByThePreviousVersionsReader() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBTrustedNetwork.addBssid(context, HOME_BSSID, HOME);
        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:02", "aa:bb:cc:dd:ee:02");
        KeepADBTrustedNetwork.setCustomName(context, KeepADBTrustedNetwork.getEntries(context).get(0).id, "Kitchen");
        KeepADBTrustedNetwork.setSsidMatchingEnabled(context, true);
        KeepADBTrustedNetwork.addSsid(context, "Mesh");
        // The keys this version adds.
        KeepADBTrustedNetwork.setTrustByNameEnabled(context, true);
        KeepADBNetworkBlocklist.blockBssid(context, "cc:cc:cc:cc:cc:01");
        KeepADBNetworkBlocklist.blockSsid(context, "Cafe");

        PreviousReader previous = new PreviousReader(prefs());

        assertEquals(KeepADBTrustedNetwork.getMode(context), previous.mode());
        List<String> previousEntries = previous.entries();
        List<String> currentEntries = new ArrayList<>();
        for (KeepADBTrustedNetwork.Entry entry : KeepADBTrustedNetwork.getEntries(context)) {
            currentEntries.add(entry.id + "|" + entry.label + "|" + entry.bssid);
        }
        assertEquals(currentEntries, previousEntries);
        assertEquals(2, previousEntries.size());
        assertEquals(java.util.Collections.singletonList("Mesh"), previous.ssids());
        assertTrue(previous.ssidMatching());
    }

    @Test
    public void aDowngradeKeepsEveryNonBlockedDecisionAndLosesOnlyTheBlocks() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBTrustedNetwork.addBssid(context, HOME_BSSID, HOME);
        KeepADBTrustedNetwork.setSsidMatchingEnabled(context, true);
        KeepADBTrustedNetwork.addSsid(context, "Mesh");
        KeepADBNetworkBlocklist.blockBssid(context, "cc:cc:cc:cc:cc:01");
        KeepADBNetworkBlocklist.blockSsid(context, "Cafe");
        PreviousReader previous = new PreviousReader(prefs());

        for (String[] network : NETWORKS) {
            connectTo(network[0], network[1]);
            assertEquals("Not blocked, so identical to what the previous version decides: "
                            + network[0] + "/" + network[1],
                    previous.trusted(network[0], network[1]),
                    KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
        }
        // The documented caveat of a rollback: the previous version does not know blocks. These
        // are the networks it would (wrongly) allow again, and the current version denies.
        KeepADBNetworkBlocklist.blockBssid(context, HOME_BSSID);
        connectTo(HOME, HOME_BSSID);
        assertTrue(previous.trusted(HOME, HOME_BSSID));
        assertFalse(KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
    }

    // --- Helpers ----------------------------------------------------------------------------------------------------

    /** Every read a surface or a call site does, over every network, so any hidden write shows. */
    private void exerciseEveryRead() {
        for (String[] network : NETWORKS) {
            connectTo(network[0], network[1]);
            KeepADBTrustedNetwork.getMode(context);
            KeepADBTrustedNetwork.isAllowlistMode(context);
            KeepADBTrustedNetwork.getEntries(context);
            KeepADBTrustedNetwork.getSsidEntries(context);
            KeepADBTrustedNetwork.isSsidMatchingEnabled(context);
            KeepADBTrustedNetwork.isTrustByNameEnabled(context);
            KeepADBTrustedNetwork.hasActiveLegacyNameGrants(context);
            KeepADBTrustedNetwork.getProtectionLevel(context);
            KeepADBTrustedNetwork.isCurrentNetworkTrusted(context);
            KeepADBTrustedNetwork.getBlockReason(context);
            KeepADBTrustedNetwork.evaluateCurrent(context);
            KeepADBTrustedNetwork.isNameTrusted(context, network[0]);
            KeepADBNetworkBlocklist.isEmpty(context);
            KeepADBNetworkBlocklist.isBlocked(context, network[1], network[0]);
        }
    }

    /**
     * The 1.9.28 reading code, restated: id lists in comma-separated keys and one set of fields per
     * id, the mode where only the exact value "allowlist" restricts. It reads raw keys only, so any
     * key the new version adds is ignored by construction.
     */
    private static final class PreviousReader {
        private final SharedPreferences preferences;

        PreviousReader(SharedPreferences preferences) {
            this.preferences = preferences;
        }

        String mode() {
            return "allowlist".equals(preferences.getString("trusted_network_mode", "all_wifi"))
                    ? "allowlist" : "all_wifi";
        }

        boolean ssidMatching() {
            return preferences.getBoolean("trusted_network_ssid_matching", false);
        }

        List<String> entries() {
            List<String> result = new ArrayList<>();
            for (String id : preferences.getString("trusted_network_ids", "").split(",")) {
                if (id.isEmpty()) continue;
                String bssid = preferences.getString("trusted_network_" + id + "_bssid", null);
                if (bssid == null) continue;
                String label = preferences.getString("trusted_network_" + id + "_label", bssid);
                result.add(id + "|" + label + "|" + bssid);
            }
            return result;
        }

        List<String> ssids() {
            List<String> result = new ArrayList<>();
            for (String id : preferences.getString("trusted_ssid_ids", "").split(",")) {
                if (id.isEmpty()) continue;
                String ssid = preferences.getString("trusted_ssid_" + id + "_ssid", null);
                if (ssid != null) result.add(ssid);
            }
            return result;
        }

        boolean trusted(String ssid, String bssid) {
            if (!"allowlist".equals(mode())) return true;
            boolean known = bssid != null && !bssid.isEmpty()
                    && !bssid.equalsIgnoreCase("02:00:00:00:00:00")
                    && !bssid.equalsIgnoreCase("00:00:00:00:00:00");
            if (!known) return false;
            for (String entry : entries()) {
                if (entry.split("\\|")[2].equalsIgnoreCase(bssid)) return true;
            }
            return ssidMatching() && ssid != null && ssids().contains(ssid);
        }
    }

    private void connectTo(String ssid, String bssid) {
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = ShadowWifiInfo.newInstance();
        shadowOf(info).setSSID(ssid);
        shadowOf(info).setBSSID(bssid);
        shadowOf(wifiManager).setConnectionInfo(info);
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE);
    }
}
