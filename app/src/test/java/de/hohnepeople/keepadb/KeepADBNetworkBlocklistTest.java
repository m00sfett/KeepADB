package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.wifi.WifiManager;

import androidx.test.core.app.ApplicationProvider;

import java.util.Arrays;
import java.util.Collections;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * #760: the block store. It must match exactly what a block is meant to cover -- not less (a block
 * that misses its network is no block) and not more (a block that swallows other networks, or every
 * unreadable one, silently disables Keep-Alive elsewhere). Both halves are asserted side by side.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBNetworkBlocklistTest {

    private static final String BSSID = "aa:bb:cc:dd:ee:01";
    private static final String OTHER_BSSID = "aa:bb:cc:dd:ee:02";

    private final Context context = ApplicationProvider.getApplicationContext();

    @Before
    public void setUp() {
        prefs().edit().clear().commit();
    }

    @After
    public void tearDown() {
        prefs().edit().clear().commit();
    }

    @Test
    public void aFreshInstallationHasNoBlocks() {
        assertTrue(KeepADBNetworkBlocklist.isEmpty(context));
        assertTrue(KeepADBNetworkBlocklist.getBlockedBssids(context).isEmpty());
        assertTrue(KeepADBNetworkBlocklist.getBlockedSsids(context).isEmpty());
        assertFalse(KeepADBNetworkBlocklist.isBssidBlocked(context, BSSID));
        assertFalse(KeepADBNetworkBlocklist.isSsidBlocked(context, "Cafe"));
    }

    @Test
    public void aBssidBlockMatchesThatAccessPointIgnoringCaseAndNoOtherOne() {
        assertTrue(KeepADBNetworkBlocklist.blockBssid(context, "AA:BB:CC:DD:EE:01"));

        // The blocked side: every spelling of the address.
        assertTrue(KeepADBNetworkBlocklist.isBssidBlocked(context, BSSID));
        assertTrue(KeepADBNetworkBlocklist.isBssidBlocked(context, "AA:BB:CC:DD:EE:01"));
        assertTrue(KeepADBNetworkBlocklist.isBssidBlocked(context, " " + BSSID + " "));
        // The other side: a block on one access point leaves its neighbours alone, whatever their
        // name -- including an access point that differs only in the last digit.
        assertFalse(KeepADBNetworkBlocklist.isBssidBlocked(context, OTHER_BSSID));
        assertFalse(KeepADBNetworkBlocklist.isBssidBlocked(context, "aa:bb:cc:dd:ee:10"));
        assertFalse("An access point block is not a name block",
                KeepADBNetworkBlocklist.isSsidBlocked(context, BSSID));
        assertFalse(KeepADBNetworkBlocklist.isEmpty(context));
        assertEquals(Collections.singletonList(BSSID),
                KeepADBNetworkBlocklist.getBlockedBssids(context));
    }

    @Test
    public void anSsidBlockMatchesTheExactNameOnlyAndIsNotAnAccessPointBlock() {
        assertTrue(KeepADBNetworkBlocklist.blockSsid(context, "Cafe Sonne"));

        assertTrue(KeepADBNetworkBlocklist.isSsidBlocked(context, "Cafe Sonne"));
        // Exact, like the trust side and like Android's saved networks: no case folding, no
        // trimming, no prefix or substring rule. Each of these is a different network.
        for (String near : new String[] {"cafe sonne", "CAFE SONNE", "Cafe Sonne ", " Cafe Sonne",
                "Cafe", "Cafe Sonne 2", "Cafe  Sonne"}) {
            assertFalse("Must not block the different name '" + near + "'",
                    KeepADBNetworkBlocklist.isSsidBlocked(context, near));
        }
        assertFalse("A name block is not an access point block",
                KeepADBNetworkBlocklist.isBssidBlocked(context, "Cafe Sonne"));
        assertEquals(Collections.singletonList("Cafe Sonne"),
                KeepADBNetworkBlocklist.getBlockedSsids(context));
    }

    @Test
    public void placeholderAndBlankValuesCanNeverBeBlocked() {
        // A block on a placeholder would match every unreadable network, silently.
        for (String bssid : new String[] {null, "", "  ", KeepADBNetworkIdentity.REDACTED_BSSID,
                KeepADBNetworkIdentity.UNSET_BSSID, " 02:00:00:00:00:00 "}) {
            assertFalse("Must refuse BSSID: " + bssid, KeepADBNetworkBlocklist.blockBssid(context, bssid));
        }
        for (String ssid : new String[] {null, "", WifiManager.UNKNOWN_SSID}) {
            assertFalse("Must refuse SSID: " + ssid, KeepADBNetworkBlocklist.blockSsid(context, ssid));
        }
        assertTrue(KeepADBNetworkBlocklist.isEmpty(context));

        // And the matching side never reports a placeholder as blocked either, even when a
        // different, genuine block exists.
        KeepADBNetworkBlocklist.blockBssid(context, BSSID);
        KeepADBNetworkBlocklist.blockSsid(context, "Cafe");
        assertFalse(KeepADBNetworkBlocklist.isBssidBlocked(context, KeepADBNetworkIdentity.REDACTED_BSSID));
        assertFalse(KeepADBNetworkBlocklist.isBssidBlocked(context, KeepADBNetworkIdentity.UNSET_BSSID));
        assertFalse(KeepADBNetworkBlocklist.isBssidBlocked(context, null));
        assertFalse(KeepADBNetworkBlocklist.isSsidBlocked(context, null));
        assertFalse(KeepADBNetworkBlocklist.isSsidBlocked(context, WifiManager.UNKNOWN_SSID));
        assertFalse(KeepADBNetworkBlocklist.isBlocked(context, null, null));
    }

    @Test
    public void blockingIsIdempotentAndUnblockingRemovesExactlyThatBlock() {
        assertTrue(KeepADBNetworkBlocklist.blockBssid(context, BSSID));
        assertFalse("A second identical block is not a change",
                KeepADBNetworkBlocklist.blockBssid(context, BSSID.toUpperCase(java.util.Locale.ROOT)));
        assertTrue(KeepADBNetworkBlocklist.blockBssid(context, OTHER_BSSID));
        assertTrue(KeepADBNetworkBlocklist.blockSsid(context, "Cafe"));
        assertFalse(KeepADBNetworkBlocklist.blockSsid(context, "Cafe"));
        assertEquals(Arrays.asList(BSSID, OTHER_BSSID),
                KeepADBNetworkBlocklist.getBlockedBssids(context));

        assertTrue(KeepADBNetworkBlocklist.unblockBssid(context, "AA:BB:CC:DD:EE:01"));
        assertFalse("Unblocking something not blocked is not a change",
                KeepADBNetworkBlocklist.unblockBssid(context, BSSID));
        assertFalse(KeepADBNetworkBlocklist.isBssidBlocked(context, BSSID));
        assertTrue("The neighbouring block must survive", KeepADBNetworkBlocklist.isBssidBlocked(context, OTHER_BSSID));
        assertTrue("An access point unblock must not lift a name block",
                KeepADBNetworkBlocklist.isSsidBlocked(context, "Cafe"));

        assertFalse("A near-miss name is not the blocked name",
                KeepADBNetworkBlocklist.unblockSsid(context, "cafe"));
        assertTrue(KeepADBNetworkBlocklist.isSsidBlocked(context, "Cafe"));
        assertTrue(KeepADBNetworkBlocklist.unblockSsid(context, "Cafe"));
        assertFalse(KeepADBNetworkBlocklist.isSsidBlocked(context, "Cafe"));
        assertTrue(KeepADBNetworkBlocklist.unblockBssid(context, OTHER_BSSID));
        assertTrue("Everything lifted: the store is empty again and its keys are gone",
                KeepADBNetworkBlocklist.isEmpty(context));
        assertFalse(prefs().contains(KeepADBNetworkBlocklist.KEY_BSSIDS));
        assertFalse(prefs().contains(KeepADBNetworkBlocklist.KEY_SSIDS));
    }

    @Test
    public void isBlockedAsksBothHalves() {
        KeepADBNetworkBlocklist.blockBssid(context, BSSID);
        KeepADBNetworkBlocklist.blockSsid(context, "Cafe");

        assertTrue(KeepADBNetworkBlocklist.isBlocked(context, BSSID, "Elsewhere"));
        assertTrue(KeepADBNetworkBlocklist.isBlocked(context, OTHER_BSSID, "Cafe"));
        assertTrue(KeepADBNetworkBlocklist.isBlocked(context, BSSID, "Cafe"));
        assertTrue(KeepADBNetworkBlocklist.isBlocked(context, null, "Cafe"));
        assertTrue(KeepADBNetworkBlocklist.isBlocked(context, BSSID, null));
        assertFalse(KeepADBNetworkBlocklist.isBlocked(context, OTHER_BSSID, "Elsewhere"));
    }

    @Test
    public void theStoreIsAdditiveAndKeepsEveryOtherKeyUntouched() {
        prefs().edit()
                .putString("trusted_network_mode", "all_wifi")
                .putString("trusted_network_ids", "1")
                .putString("trusted_network_1_bssid", BSSID)
                .putString("trusted_network_1_label", "Home")
                .apply();
        java.util.Map<String, Object> before = new java.util.HashMap<>(prefs().getAll());

        KeepADBNetworkBlocklist.blockBssid(context, OTHER_BSSID);
        KeepADBNetworkBlocklist.blockSsid(context, "Cafe");
        KeepADBNetworkBlocklist.unblockBssid(context, OTHER_BSSID);
        KeepADBNetworkBlocklist.unblockSsid(context, "Cafe");

        assertEquals("Blocking and unblocking must leave every older key exactly as it was",
                before, new java.util.HashMap<>(prefs().getAll()));
    }

    private android.content.SharedPreferences prefs() {
        return context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE);
    }
}
