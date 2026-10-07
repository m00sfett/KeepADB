package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * #796/#802: the names stored with a trusted entry (the Wi-Fi name it was trusted under) and with a
 * blocked access point (the Wi-Fi name and the own name the user gave it). Pinned here, each with
 * its other side next to it:
 *
 * <ul>
 *   <li><strong>Old data:</strong> a state stored before the fields reads as "no name", every read
 *       path leaves the stored state byte for byte as it was (no migration, no completion), and the
 *       fields are additive keys an older version simply ignores;
 *   <li><strong>Round trip:</strong> the writers store the name where it is known, normalise it, and
 *       lifting a block or removing an entry takes the name along (no leftover that could resurface);
 *   <li><strong>No decision reads a name:</strong> the same data with and without the stored names
 *       gives the same decision for every identity, with the derived comfort switch on and off;
 *   <li><strong>Privacy:</strong> the names reach neither the diagnostics export nor the register
 *       payload.
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBStoredNamesTest {

    private static final String HOME = "Heimnetz";
    private static final String CAFE = "Cafe Sonne";
    private static final String KITCHEN = "aa:bb:cc:11:22:33";
    private static final String HALL = "aa:bb:cc:11:22:44";
    private static final String CAFE_AP = "12:34:56:78:9a:bc";
    private static final String OTHER_AP = "ee:ee:ee:ee:ee:01";

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = RuntimeEnvironment.getApplication();
    private KeepADBFakeSettingsGateway gateway;

    @Before
    public void setUp() {
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS,
                android.Manifest.permission.POST_NOTIFICATIONS);
        prefs().edit().clear().commit();
        KeepADB.resetForTesting();
        gateway = new KeepADBFakeSettingsGateway(false);
        KeepADB.setGatewayForTesting(gateway);
    }

    @After
    public void tearDown() {
        prefs().edit().clear().commit();
        KeepADB.resetForTesting();
    }

    // --- Old data: no name, no migration ------------------------------------------------------

    @Test
    public void aStateStoredBeforeTheFieldsReadsAsNoNameAndNoReaderWritesAnything() {
        // Exactly the keys of 1.9.42: a trusted entry with a BSSID-only label, one with a name, a
        // block without any name keys, a name block.
        prefs().edit()
                .putString("trusted_network_mode", "allowlist")
                .putBoolean("trusted_network_mode_initialized", true)
                .putString("trusted_network_ids", "1,2")
                .putInt("trusted_network_next_id", 3)
                .putString("trusted_network_1_bssid", KITCHEN)
                .putString("trusted_network_1_label", KITCHEN)
                .putString("trusted_network_2_bssid", HALL)
                .putString("trusted_network_2_label", HOME)
                .putString("trusted_network_2_name", "Flur")
                .putStringSet("blocked_bssids", new java.util.HashSet<>(Arrays.asList(CAFE_AP)))
                .putStringSet("blocked_ssids", new java.util.HashSet<>(Arrays.asList(CAFE)))
                .commit();
        Map<String, ?> before = new TreeMap<>(prefs().getAll());

        List<KeepADBTrustedNetwork.Entry> entries = KeepADBTrustedNetwork.getEntries(context);
        assertEquals(2, entries.size());
        assertNull("Old entry: no stored name", entries.get(0).savedSsid);
        assertNull("... and nothing to list it under", entries.get(0).listSsid());
        assertNull(entries.get(0).ssid());
        assertNull("Old entry with a label name: no stored name either", entries.get(1).savedSsid);
        assertEquals("... its label name still files it", HOME, entries.get(1).listSsid());
        KeepADBNetworkBlocklist.BlockedAccessPoint block =
                KeepADBNetworkBlocklist.getBlockedAccessPoint(context, CAFE_AP);
        assertNotNull(block);
        assertNull("Old block: no stored Wi-Fi name", block.ssid);
        assertNull("... and no own name", block.customName);
        assertTrue(KeepADBNetworkBlocklist.isBssidBlocked(context, CAFE_AP));

        // Every path that reads: the policy, the list, the decision helper.
        KeepADBTrustedNetwork.evaluate(context, new KeepADBNetworkIdentity(HOME, HALL));
        KeepADBNetworkList.build(context, new KeepADBNetworkIdentity(CAFE, CAFE_AP), true);
        KeepADBNetworkDecision.resolve(context, CAFE_AP);
        KeepADBNetworkBlocklist.getBlockedAccessPoints(context);

        assertEquals("Reading an old state changes nothing and completes no name",
                before, new TreeMap<>(prefs().getAll()));
        assertFalse(hasKeyContaining("trusted_network_1_ssid"));
        assertFalse(hasKeyContaining("blocked_bssid_"));
    }

    @Test
    public void theFieldsAreAdditiveKeysAndTheKeysOfTheOldStateKeepFormatAndMeaning() {
        KeepADBTrustedNetwork.Entry entry = KeepADBTrustedNetwork.addBssid(context, KITCHEN, KITCHEN, HOME);
        KeepADBNetworkBlocklist.blockBssid(context, CAFE_AP, CAFE, "Ecke");

        // What a 1.9.42 reader looks at is stored as it always was ...
        assertEquals(KITCHEN, prefs().getString("trusted_network_" + entry.id + "_bssid", null));
        assertEquals("The label stays the BSSID: it is not where the name goes", KITCHEN,
                prefs().getString("trusted_network_" + entry.id + "_label", null));
        assertEquals(String.valueOf(entry.id), prefs().getString("trusted_network_ids", null));
        assertEquals(java.util.Collections.singleton(CAFE_AP),
                prefs().getStringSet("blocked_bssids", null));
        // ... and everything new sits in keys of its own.
        assertEquals(HOME, prefs().getString("trusted_network_" + entry.id + "_ssid", null));
        assertEquals(CAFE, prefs().getString("blocked_bssid_ssid_" + CAFE_AP, null));
        assertEquals("Ecke", prefs().getString("blocked_bssid_name_" + CAFE_AP, null));

        // The old reader's view is unchanged by the keys: strip them and decide the same.
        KeepADBNetworkIdentity here = new KeepADBNetworkIdentity(HOME, KITCHEN);
        KeepADBTrustedNetwork.Decision with = KeepADBTrustedNetwork.evaluate(context, here);
        prefs().edit()
                .remove("trusted_network_" + entry.id + "_ssid")
                .remove("blocked_bssid_ssid_" + CAFE_AP)
                .remove("blocked_bssid_name_" + CAFE_AP).commit();
        assertEquals(with, KeepADBTrustedNetwork.evaluate(context, here));
        assertTrue("The block is the block without its names", KeepADBNetworkBlocklist.isBssidBlocked(context, CAFE_AP));
        assertNull(KeepADBNetworkBlocklist.getBlockedAccessPoint(context, CAFE_AP).ssid);
    }

    // --- The blocked access point: round trip --------------------------------------------------

    @Test
    public void aBlockStoresTheWifiNameAndTheOwnNameNextToTheAddressAndALiftTakesThemAlong() {
        assertTrue(KeepADBNetworkBlocklist.blockBssid(context, CAFE_AP.toUpperCase(java.util.Locale.ROOT),
                CAFE, "  Ecke\nlinks  "));

        KeepADBNetworkBlocklist.BlockedAccessPoint block =
                KeepADBNetworkBlocklist.getBlockedAccessPoint(context, " " + CAFE_AP + " ");
        assertEquals("Lower-case address, the form every other reader uses", CAFE_AP, block.bssid);
        assertEquals(CAFE, block.ssid);
        assertEquals("Trimmed, line break turned into a space", "Ecke links", block.customName);
        assertEquals(1, KeepADBNetworkBlocklist.getBlockedAccessPoints(context).size());
        assertEquals(KeepADBNetworkBlocklist.getBlockedBssids(context),
                java.util.Collections.singletonList(block.bssid));

        assertTrue(KeepADBNetworkBlocklist.unblockBssid(context, CAFE_AP));
        assertNull(KeepADBNetworkBlocklist.getBlockedAccessPoint(context, CAFE_AP));
        assertTrue("Nothing of the block is left behind", prefs().getAll().isEmpty());

        // The other side: blocked again without names, the old names do not come back.
        assertTrue(KeepADBNetworkBlocklist.blockBssid(context, CAFE_AP));
        assertNull(KeepADBNetworkBlocklist.getBlockedAccessPoint(context, CAFE_AP).ssid);
        assertNull(KeepADBNetworkBlocklist.getBlockedAccessPoint(context, CAFE_AP).customName);
    }

    @Test
    public void namesLeftUnderAnAddressThatIsNotBlockedAreIgnoredAndNeverResurfaceOnANewBlock() {
        // A leftover, for instance from an older app version that lifted the block without knowing
        // the name keys.
        prefs().edit()
                .putString("blocked_bssid_ssid_" + CAFE_AP, "Alt")
                .putString("blocked_bssid_name_" + CAFE_AP, "Alt")
                .commit();
        assertNull("Not blocked: not read", KeepADBNetworkBlocklist.getBlockedAccessPoint(context, CAFE_AP));
        assertTrue(KeepADBNetworkBlocklist.getBlockedAccessPoints(context).isEmpty());
        assertFalse(KeepADBNetworkBlocklist.setBlockedCustomName(context, CAFE_AP, "Neu"));
        assertEquals("A refused rename writes nothing", "Alt",
                prefs().getString("blocked_bssid_name_" + CAFE_AP, null));

        KeepADBNetworkBlocklist.blockBssid(context, CAFE_AP);

        KeepADBNetworkBlocklist.BlockedAccessPoint block =
                KeepADBNetworkBlocklist.getBlockedAccessPoint(context, CAFE_AP);
        assertNull(block.ssid);
        assertNull(block.customName);
        assertFalse(hasKeyContaining("blocked_bssid_"));
    }

    @Test
    public void blockingAnAlreadyBlockedAccessPointChangesNothingNotEvenItsNames() {
        KeepADBNetworkBlocklist.blockBssid(context, CAFE_AP, CAFE, "Ecke");
        Map<String, ?> before = new TreeMap<>(prefs().getAll());

        assertFalse(KeepADBNetworkBlocklist.blockBssid(context, CAFE_AP, "Anders", "Anders"));

        assertEquals(before, new TreeMap<>(prefs().getAll()));
    }

    @Test
    public void onlyAUsableWifiNameAndANonEmptyOwnNameAreStoredAndThePlaceholdersStillBlockNothing() {
        for (String unusable : new String[] {null, "", WifiManager.UNKNOWN_SSID}) {
            KeepADBNetworkBlocklist.blockBssid(context, CAFE_AP, unusable, "  \n ");
            KeepADBNetworkBlocklist.BlockedAccessPoint block =
                    KeepADBNetworkBlocklist.getBlockedAccessPoint(context, CAFE_AP);
            assertNull("'" + unusable + "' is no name", block.ssid);
            assertNull("A blank own name is no name", block.customName);
            assertFalse(hasKeyContaining("blocked_bssid_"));
            KeepADBNetworkBlocklist.unblockBssid(context, CAFE_AP);
        }
        for (String bssid : new String[] {null, "", KeepADBNetworkIdentity.REDACTED_BSSID,
                KeepADBNetworkIdentity.UNSET_BSSID}) {
            assertFalse("'" + bssid + "' cannot be blocked, with or without names",
                    KeepADBNetworkBlocklist.blockBssid(context, bssid, CAFE, "Ecke"));
        }
        assertTrue("A placeholder block would match every unreadable network",
                KeepADBNetworkBlocklist.isEmpty(context));
        assertTrue(prefs().getAll().isEmpty());
    }

    @Test
    public void theOwnNameOfABlockedAccessPointCanBeSetChangedAndResetAndTheBlockStaysAsItIs() {
        KeepADBNetworkBlocklist.blockBssid(context, CAFE_AP, CAFE, null);

        assertTrue(KeepADBNetworkBlocklist.setBlockedCustomName(context, CAFE_AP, "Ecke"));
        assertEquals("Ecke", KeepADBNetworkBlocklist.getBlockedAccessPoint(context, CAFE_AP).customName);
        assertTrue(KeepADBNetworkBlocklist.setBlockedCustomName(context, CAFE_AP.toUpperCase(java.util.Locale.ROOT), "Theke"));
        assertEquals("Theke", KeepADBNetworkBlocklist.getBlockedAccessPoint(context, CAFE_AP).customName);
        assertTrue(KeepADBNetworkBlocklist.setBlockedCustomName(context, CAFE_AP, "   "));
        assertNull("A blank name resets", KeepADBNetworkBlocklist.getBlockedAccessPoint(context, CAFE_AP).customName);
        assertFalse(hasKeyContaining("blocked_bssid_name_"));

        assertEquals("The Wi-Fi name is not touched by a rename", CAFE,
                KeepADBNetworkBlocklist.getBlockedAccessPoint(context, CAFE_AP).ssid);
        assertTrue(KeepADBNetworkBlocklist.isBssidBlocked(context, CAFE_AP));
        assertEquals(1, KeepADBNetworkBlocklist.getBlockedBssids(context).size());
        assertFalse("Another address is not renamed", KeepADBNetworkBlocklist.setBlockedCustomName(context, OTHER_AP, "X"));

        String longName = new String(new char[80]).replace('\0', 'x');
        KeepADBNetworkBlocklist.setBlockedCustomName(context, CAFE_AP, longName);
        assertEquals(KeepADBTrustedNetwork.MAX_CUSTOM_NAME_LENGTH,
                KeepADBNetworkBlocklist.getBlockedAccessPoint(context, CAFE_AP).customName.length());
    }

    // --- The trusted entry: round trip --------------------------------------------------------

    @Test
    public void aTrustStoresTheNameItKnowsAndARemovalTakesItAlong() {
        KeepADBTrustedNetwork.Entry named = KeepADBTrustedNetwork.addBssid(context, KITCHEN, HOME);
        KeepADBTrustedNetwork.Entry bare = KeepADBTrustedNetwork.addBssid(context, HALL, HALL);

        assertEquals("The name of the label is stored as the name as well", HOME,
                KeepADBTrustedNetwork.getEntries(context).get(0).savedSsid);
        assertNull("No name known: none stored", KeepADBTrustedNetwork.getEntries(context).get(1).savedSsid);
        assertFalse(prefs().contains("trusted_network_" + bare.id + "_ssid"));

        assertTrue(KeepADBTrustedNetwork.remove(context, named.id));
        assertFalse("The name leaves with its entry", prefs().contains("trusted_network_" + named.id + "_ssid"));
        assertEquals(1, KeepADBTrustedNetwork.getEntries(context).size());
    }

    @Test
    public void aNameKnownFromElsewhereIsStoredAndTheLabelStaysTheBssid() {
        KeepADBTrustedNetwork.Entry entry =
                KeepADBTrustedNetwork.addBssid(context, KITCHEN, KITCHEN, HOME);

        KeepADBTrustedNetwork.Entry read = KeepADBTrustedNetwork.getEntries(context).get(0);
        assertEquals(entry.id, read.id);
        assertEquals("Label untouched", KITCHEN, read.label);
        assertNull("The label has no name, so the derived name rule has nothing", read.ssid());
        assertEquals(HOME, read.savedSsid);
        assertEquals("The list files it under the stored name", HOME, read.listSsid());

        // An unusable name is no name.
        KeepADBTrustedNetwork.Entry other = KeepADBTrustedNetwork.addBssid(context, HALL, HALL,
                WifiManager.UNKNOWN_SSID);
        assertNull(other.savedSsid);
        assertNull(KeepADBTrustedNetwork.addBssid(context, "", HALL, HOME));

        // The label's own name wins as the list name, whatever else was handed in.
        KeepADBTrustedNetwork.Entry labelled = KeepADBTrustedNetwork.addBssid(context, OTHER_AP, CAFE, HOME);
        assertEquals(CAFE, labelled.listSsid());
        assertEquals("The explicit name is what was stored", HOME, labelled.savedSsid);
    }

    @Test
    public void addingAnAlreadyTrustedAccessPointReturnsItUnchangedWithoutCompletingAName() {
        prefs().edit()
                .putString("trusted_network_ids", "1")
                .putInt("trusted_network_next_id", 2)
                .putString("trusted_network_1_bssid", KITCHEN)
                .putString("trusted_network_1_label", KITCHEN).commit();
        Map<String, ?> before = new TreeMap<>(prefs().getAll());

        KeepADBTrustedNetwork.Entry again = KeepADBTrustedNetwork.addBssid(context,
                KITCHEN.toUpperCase(java.util.Locale.ROOT), HOME, HOME);

        assertEquals(1, again.id);
        assertNull("The old entry is not completed behind its user's back", again.savedSsid);
        assertEquals(before, new TreeMap<>(prefs().getAll()));
    }

    // --- The writers on their real paths ------------------------------------------------------

    @Test
    public void theNoticeBlockActionStoresTheNameOfTheAppsOwnRecordAndClearsTheRecord() {
        record(CAFE, CAFE_AP);

        assertTrue(KeepADBReceiver.handleBlockNetworkAction(context, CAFE_AP));

        assertEquals(CAFE, KeepADBNetworkBlocklist.getBlockedAccessPoint(context, CAFE_AP).ssid);
        assertTrue("The record, which holds the name, is gone as before",
                KeepADBBlockedNetworkHistory.getEntries(context).isEmpty());
    }

    @Test
    public void theDecisionBlockUsesTheCallersNameFirstThenTheRecordThenTheTrustEntry() {
        record("Aus dem Verlauf", CAFE_AP);
        record(null, OTHER_AP);
        KeepADBTrustedNetwork.addBssid(context, KITCHEN, HOME);

        KeepADBNetworkDecision.blockAccessPoint(context, CAFE_AP, CAFE);
        KeepADBNetworkDecision.blockAccessPoint(context, OTHER_AP, null);
        KeepADBNetworkDecision.blockAccessPoint(context, KITCHEN);
        KeepADBNetworkDecision.blockAccessPoint(context, HALL, WifiManager.UNKNOWN_SSID);

        assertEquals("The caller's name wins", CAFE,
                KeepADBNetworkBlocklist.getBlockedAccessPoint(context, CAFE_AP).ssid);
        assertNull("A record without a readable name stores none",
                KeepADBNetworkBlocklist.getBlockedAccessPoint(context, OTHER_AP).ssid);
        assertEquals("The trust entry knows the name", HOME,
                KeepADBNetworkBlocklist.getBlockedAccessPoint(context, KITCHEN).ssid);
        assertNull("Nothing known, nothing stored",
                KeepADBNetworkBlocklist.getBlockedAccessPoint(context, HALL).ssid);
        assertEquals("A block through the decision never stores an own name", 0,
                countKeysContaining("blocked_bssid_name_"));
    }

    @Test
    public void theDecisionBlockOfAnUnusableAddressStoresNoNameAtAll() {
        record(CAFE, CAFE_AP);

        assertEquals(KeepADBNetworkDecision.Outcome.BLOCK_FAILED,
                KeepADBNetworkDecision.blockAccessPoint(context, KeepADBNetworkIdentity.REDACTED_BSSID, CAFE));

        assertFalse(hasKeyContaining("blocked_bssid_"));
        assertTrue(KeepADBNetworkBlocklist.isEmpty(context));
    }

    @Test
    public void theDecisionTrustStoresTheRecordedNameInTheEntry() {
        KeepADBNetworkDecision.TrustResult result = KeepADBNetworkDecision.trust(context,
                new KeepADBNetworkDecision.Pending(CAFE_AP, CAFE));

        assertEquals(KeepADBNetworkDecision.Outcome.TRUSTED, result.outcome);
        KeepADBTrustedNetwork.Entry entry = KeepADBTrustedNetwork.getEntries(context).get(0);
        assertEquals(CAFE, entry.label);
        assertEquals(CAFE, entry.savedSsid);

        // The other side: no readable name, no stored name, and the label is the BSSID as before.
        KeepADBNetworkDecision.trust(context, new KeepADBNetworkDecision.Pending(KITCHEN, null));
        KeepADBTrustedNetwork.Entry bare = KeepADBTrustedNetwork.getEntries(context).get(1);
        assertEquals(KITCHEN, bare.label);
        assertNull(bare.savedSsid);
    }

    @Test
    public void listTrustOfABlockedAccessPointCarriesBothNamesOverAndKeepsTheLabelTheBssid() {
        KeepADBNetworkBlocklist.blockBssid(context, CAFE_AP, CAFE, "Ecke");

        assertEquals(KeepADBNetworkListActions.Outcome.TRUSTED,
                KeepADBNetworkListActions.trustBlockedAccessPoint(context, CAFE_AP, null));

        KeepADBTrustedNetwork.Entry entry = KeepADBTrustedNetwork.getEntries(context).get(0);
        assertEquals("The label is what the caller knew: the BSSID", CAFE_AP, entry.label);
        assertNull("... so the derived name rule has nothing to derive", entry.ssid());
        assertEquals("The name of the block is kept as the stored name", CAFE, entry.savedSsid);
        assertEquals("The own name the user gave survives the lift", "Ecke", entry.customName);
        assertFalse(KeepADBNetworkBlocklist.isBssidBlocked(context, CAFE_AP));
        assertFalse("The block's keys went with the block", hasKeyContaining("blocked_bssid_"));
    }

    /**
     * The refusal of a trust from the list reads the name the caller passes (the label or live
     * name of the row) and nothing stored: the Wi-Fi name stored with a block is display data from
     * the time of the block and can be stale, so an access point that now broadcasts another name
     * is not refused for its old one. Both sides: the passed name that is blocked still refuses.
     */
    @Test
    public void listTrustOfABlockedAccessPointIsRefusedByTheNameTheCallerPassesNeverByAStaleStoredName() {
        KeepADBNetworkBlocklist.blockBssid(context, CAFE_AP, CAFE, "Ecke");
        KeepADBNetworkBlocklist.blockSsid(context, CAFE);
        Map<String, ?> before = new TreeMap<>(prefs().getAll());

        assertEquals("The name the caller passes is blocked: refused, as before",
                KeepADBNetworkListActions.Outcome.TRUST_REFUSED_NAME_BLOCKED,
                KeepADBNetworkListActions.trustBlockedAccessPoint(context, CAFE_AP, CAFE));
        assertEquals("Nothing lifted, nothing stored", before, new TreeMap<>(prefs().getAll()));
        assertTrue(KeepADBTrustedNetwork.getEntries(context).isEmpty());

        // The access point now broadcasts another name; the old one stored with the block is stale
        // and decides nothing.
        assertEquals(KeepADBNetworkListActions.Outcome.TRUSTED,
                KeepADBNetworkListActions.trustBlockedAccessPoint(context, CAFE_AP, HOME));
        KeepADBTrustedNetwork.Entry entry = KeepADBTrustedNetwork.getEntries(context).get(0);
        assertEquals("The live name is the label", HOME, entry.label);
        assertEquals("... and the stored name of the entry", HOME, entry.savedSsid);
        assertFalse(KeepADBNetworkBlocklist.isBssidBlocked(context, CAFE_AP));
        assertTrue("The name block itself is untouched", KeepADBNetworkBlocklist.isSsidBlocked(context, CAFE));
    }

    /** #813: a trust write that stores nothing leaves the block, with both stored names, as it was. */
    @Test
    public void listTrustWhoseWriteFailsKeepsTheBlockWithItsNames() {
        KeepADBNetworkBlocklist.blockBssid(context, CAFE_AP, CAFE, "Ecke");
        Map<String, ?> before = new TreeMap<>(prefs().getAll());

        assertEquals(KeepADBNetworkListActions.Outcome.FAILED,
                KeepADBNetworkListActions.trustBlockedAccessPoint(context, CAFE_AP, null,
                        (c, bssid, label, knownSsid) -> null));

        assertTrue(KeepADBNetworkBlocklist.isBssidBlocked(context, CAFE_AP));
        KeepADBNetworkBlocklist.BlockedAccessPoint block =
                KeepADBNetworkBlocklist.getBlockedAccessPoint(context, CAFE_AP);
        assertEquals(CAFE, block.ssid);
        assertEquals("Ecke", block.customName);
        assertEquals("Nothing else changed", before, new TreeMap<>(prefs().getAll()));
        assertTrue(KeepADBTrustedNetwork.getEntries(context).isEmpty());
    }

    @Test
    public void listTrustOfALegacyBlockWithoutNamesBehavesAsBefore() {
        KeepADBNetworkBlocklist.blockBssid(context, CAFE_AP);

        assertEquals(KeepADBNetworkListActions.Outcome.TRUSTED,
                KeepADBNetworkListActions.trustBlockedAccessPoint(context, CAFE_AP, null));

        KeepADBTrustedNetwork.Entry entry = KeepADBTrustedNetwork.getEntries(context).get(0);
        assertEquals(CAFE_AP, entry.label);
        assertNull(entry.savedSsid);
        assertNull(entry.customName);
    }

    @Test
    public void aNameKnownFromElsewhereIsOnlyStoredWithTheTrustAndTakesNoPartInTheBlockCheck() {
        KeepADBNetworkBlocklist.blockSsid(context, CAFE);

        assertNull("The other side: the name the label carries is blocked and refuses, as before",
                KeepADBReceiver.allowBssidOnly(context, OTHER_AP, CAFE, null));
        assertTrue(KeepADBTrustedNetwork.getEntries(context).isEmpty());

        KeepADBTrustedNetwork.Entry entry = KeepADBReceiver.allowBssidOnly(context, CAFE_AP, CAFE_AP, CAFE);
        assertNotNull("A name known only from elsewhere is display data and refuses nothing", entry);
        assertEquals(CAFE_AP, entry.label);
        assertEquals(CAFE, entry.savedSsid);
    }

    @Test
    public void listBlockOfATrustedAccessPointStoresItsNameAndMovesItsOwnNameToTheBlock() {
        KeepADBTrustedNetwork.Entry entry = KeepADBTrustedNetwork.addBssid(context, KITCHEN, KITCHEN, HOME);
        KeepADBTrustedNetwork.setCustomName(context, entry.id, "Kueche");

        assertEquals(KeepADBNetworkListActions.Outcome.BLOCKED_ACCESS_POINT,
                KeepADBNetworkListActions.blockAccessPoint(context, KITCHEN));

        KeepADBNetworkBlocklist.BlockedAccessPoint block =
                KeepADBNetworkBlocklist.getBlockedAccessPoint(context, KITCHEN);
        assertEquals("The trust's stored name is the block's name", HOME, block.ssid);
        assertEquals("The user's own name moves with the access point", "Kueche", block.customName);
        assertTrue("The trust is dropped as before", KeepADBTrustedNetwork.getEntries(context).isEmpty());
        assertFalse(hasKeyContaining("trusted_network_" + entry.id + "_"));

        // An own name the block already has is not overwritten by the one of a stale trust.
        KeepADBNetworkBlocklist.unblockBssid(context, KITCHEN);
        KeepADBTrustedNetwork.Entry again = KeepADBTrustedNetwork.addBssid(context, HALL, HOME);
        KeepADBTrustedNetwork.setCustomName(context, again.id, "Alt");
        KeepADBNetworkBlocklist.blockBssid(context, HALL, HOME, "Neu");
        KeepADBNetworkListActions.blockAccessPoint(context, HALL);
        assertEquals("Neu", KeepADBNetworkBlocklist.getBlockedAccessPoint(context, HALL).customName);
    }

    // --- No decision reads a name --------------------------------------------------------------

    /**
     * The names are display data. The same trust and block data, with the stored names and without,
     * must decide identically for every identity; the data is chosen so that an access point whose
     * stored name differs from its address would flip if any decision consulted the name: a name
     * stored only in the new fields that is the name of an unknown access point, trusted by name,
     * blocked by name.
     */
    @Test
    public void everyDecisionIsTheSameWithAndWithoutTheStoredNames() {
        KeepADBTrustedNetwork.addBssid(context, KITCHEN, KITCHEN, HOME);   // BSSID label, stored name
        KeepADBTrustedNetwork.addBssid(context, HALL, CAFE, CAFE);          // label name
        KeepADBNetworkBlocklist.blockBssid(context, CAFE_AP, "Gast", "Ecke");
        KeepADBNetworkBlocklist.blockBssid(context, OTHER_AP, HOME, null); // blocked, stored name HOME
        KeepADBTrustedNetwork.addSsid(context, "Fremd");                   // a legacy name grant

        List<String> ssids = Arrays.asList(null, HOME, CAFE, "Gast", "Fremd");
        List<String> bssids = Arrays.asList(KITCHEN, HALL, CAFE_AP, OTHER_AP, "99:99:99:99:99:99",
                KeepADBNetworkIdentity.REDACTED_BSSID, null);
        boolean[] on = {false, true};
        for (String mode : new String[] {KeepADBTrustedNetwork.MODE_ALLOWLIST,
                KeepADBTrustedNetwork.MODE_ALL_WIFI}) {
            for (boolean comfort : on) {
                for (boolean legacyNames : on) {
                    KeepADBTrustedNetwork.setMode(context, mode);
                    KeepADBTrustedNetwork.setTrustByNameEnabled(context, comfort);
                    KeepADBTrustedNetwork.setSsidMatchingEnabled(context, legacyNames);
                    Map<String, String> withNames = decisions(ssids, bssids);

                    // Take every stored name away; the rest of the data stays.
                    SharedPreferences.Editor editor = prefs().edit();
                    Map<String, Object> removed = new TreeMap<>();
                    for (Map.Entry<String, ?> entry : prefs().getAll().entrySet()) {
                        String key = entry.getKey();
                        if (key.startsWith("blocked_bssid_")
                                || (key.startsWith("trusted_network_") && key.endsWith("_ssid"))) {
                            removed.put(key, entry.getValue());
                            editor.remove(key);
                        }
                    }
                    editor.commit();
                    assertFalse("The data holds stored names to take away", removed.isEmpty());
                    Map<String, String> withoutNames = decisions(ssids, bssids);
                    assertEquals("mode=" + mode + " comfort=" + comfort + " legacyNames=" + legacyNames,
                            withNames, withoutNames);

                    // Put them back for the next round.
                    SharedPreferences.Editor restore = prefs().edit();
                    for (Map.Entry<String, Object> entry : removed.entrySet()) {
                        restore.putString(entry.getKey(), (String) entry.getValue());
                    }
                    restore.commit();
                }
            }
        }
        assertEquals("Control: with the comfort switch the BSSID-label entry's stored name trusts no one",
                KeepADBTrustedNetwork.Decision.UNKNOWN_NETWORK, decision(HOME, "99:99:99:99:99:99",
                        KeepADBTrustedNetwork.MODE_ALLOWLIST, true));
        assertEquals("... while a label name does (the rule is alive, so the check above can fail)",
                KeepADBTrustedNetwork.Decision.TRUSTED_NAME, decision(CAFE, "99:99:99:99:99:99",
                        KeepADBTrustedNetwork.MODE_ALLOWLIST, true));
        assertEquals("A stored block name is no name block",
                KeepADBTrustedNetwork.Decision.UNKNOWN_NETWORK, decision("Gast", "99:99:99:99:99:99",
                        KeepADBTrustedNetwork.MODE_ALLOWLIST, false));
    }

    // --- Privacy -------------------------------------------------------------------------------

    @Test
    public void theStoredNamesReachNeitherTheDiagnosticsExportNorTheRegisterSync() throws IOException {
        record(CAFE, CAFE_AP);

        KeepADBReceiver.handleBlockNetworkAction(context, CAFE_AP);
        KeepADBNetworkListActions.trustBlockedAccessPoint(context, CAFE_AP, null);
        KeepADBNetworkListActions.blockAccessPoint(context, CAFE_AP);
        KeepADBNetworkBlocklist.setBlockedCustomName(context, CAFE_AP, "Geheim-Ecke");
        KeepADBNetworkDecision.blockAccessPoint(context, KITCHEN, HOME);

        assertEquals("Control: the names are stored", CAFE,
                KeepADBNetworkBlocklist.getBlockedAccessPoint(context, CAFE_AP).ssid);
        assertEquals("Geheim-Ecke", KeepADBNetworkBlocklist.getBlockedAccessPoint(context, CAFE_AP).customName);
        for (String export : new String[] {KeepADBDiagnostics.export(context),
                KeepADBDiagnostics.exportForIssueReport(context)}) {
            assertFalse("No Wi-Fi name in the diagnostics: " + export, export.contains(CAFE));
            assertFalse(export, export.contains(HOME));
            assertFalse(export, export.contains("Geheim-Ecke"));
        }

        // The register sync is built from the verified transports; it has no access to the lists.
        for (String file : new String[] {"KeepADBRegisterPayload", "KeepADBRegisterClient"}) {
            String source = read("app/src/main/java/de/hohnepeople/keepadb/" + file + ".java");
            assertFalse(file, source.contains("KeepADBNetworkBlocklist"));
            assertFalse(file, source.contains("KeepADBTrustedNetwork"));
        }
    }

    // --- Helpers -------------------------------------------------------------------------------

    private Map<String, String> decisions(List<String> ssids, List<String> bssids) {
        Map<String, String> result = new TreeMap<>();
        for (String ssid : ssids) {
            for (String bssid : bssids) {
                String quoted = ssid == null ? null : "\"" + ssid + "\"";
                result.put(ssid + "|" + bssid,
                        KeepADBTrustedNetwork.evaluate(context, new KeepADBNetworkIdentity(quoted, bssid)).name()
                                + "|" + KeepADBTrustedNetwork.isTrustedForTesting(context,
                                new KeepADBNetworkIdentity(quoted, bssid)));
            }
        }
        return result;
    }

    private KeepADBTrustedNetwork.Decision decision(String ssid, String bssid, String mode, boolean comfort) {
        KeepADBTrustedNetwork.setMode(context, mode);
        KeepADBTrustedNetwork.setTrustByNameEnabled(context, comfort);
        return KeepADBTrustedNetwork.evaluate(context, new KeepADBNetworkIdentity("\"" + ssid + "\"", bssid));
    }

    private void record(String ssid, String bssid) {
        KeepADBBlockedNetworkHistory.record(context, new KeepADBNetworkIdentity(
                ssid == null ? null : "\"" + ssid + "\"", bssid), 1L);
    }

    private boolean hasKeyContaining(String part) {
        return countKeysContaining(part) > 0;
    }

    private int countKeysContaining(String part) {
        int count = 0;
        for (String key : prefs().getAll().keySet()) {
            if (key.contains(part)) count++;
        }
        return count;
    }

    private static String read(String relativePath) throws IOException {
        Path directory = Paths.get("").toAbsolutePath();
        while (directory != null && !Files.exists(directory.resolve("settings.gradle"))) {
            directory = directory.getParent();
        }
        if (directory == null) throw new IllegalStateException("Could not locate project root");
        return new String(Files.readAllBytes(directory.resolve(relativePath)), StandardCharsets.UTF_8);
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE);
    }
}
