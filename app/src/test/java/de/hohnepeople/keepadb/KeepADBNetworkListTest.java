package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import de.hohnepeople.keepadb.KeepADBNetworkList.Group;
import de.hohnepeople.keepadb.KeepADBNetworkList.Reason;
import de.hohnepeople.keepadb.KeepADBNetworkList.Row;
import de.hohnepeople.keepadb.KeepADBNetworkList.Snapshot;
import de.hohnepeople.keepadb.KeepADBNetworkList.Status;
import de.hohnepeople.keepadb.KeepADBNetworkListActions.Outcome;

/**
 * #762: the status the network list shows and what its answers change, against the real policy
 * ({@link KeepADBTrustedNetwork#evaluate}) and the real stores. Every invariant is asserted on both
 * sides: a blocked name turns a trusted row into "blocked" and the same row without the block is
 * "trusted"; "stop trusting" and "lift the block" of an access point end in "unknown" while
 * lifting a name block gives the stored trust back.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBNetworkListTest {

    private static final String HOME = "Heimnetz";
    private static final String HOME_KITCHEN = "aa:bb:cc:11:22:33";
    private static final String HOME_HALL = "aa:bb:cc:11:22:44";
    private static final String CAFE = "Cafe Sonne";
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
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADB.resetForTesting();
        gateway = new KeepADBFakeSettingsGateway(false);
        KeepADB.setGatewayForTesting(gateway);
    }

    @After
    public void tearDown() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADB.resetForTesting();
    }

    // --- Blocked before trusted -------------------------------------------------------------

    @Test
    public void aTrustedAccessPointWhoseNameIsBlockedIsBlockedWithTheReasonNeverTrusted() {
        KeepADBTrustedNetwork.addBssid(context, HOME_KITCHEN, HOME);

        Row trusted = onlyRow(build(null));
        assertEquals("Control: without a name block the access point is trusted",
                Status.TRUSTED, trusted.status);
        assertEquals(Reason.NONE, trusted.reason);

        KeepADBNetworkBlocklist.blockSsid(context, HOME);
        Snapshot snapshot = build(null);
        Row blocked = onlyRow(snapshot);
        assertEquals(Status.BLOCKED, blocked.status);
        assertEquals(Reason.NAME, blocked.reason);
        assertNotNull("The trust stays stored behind the block", blocked.entry);
        assertEquals(0, snapshot.trusted);
        assertEquals(1, snapshot.blocked);
        assertTrue(snapshot.groups.get(0).nameBlocked);
    }

    @Test
    public void aTrustedAccessPointWhoseAddressIsBlockedIsBlockedWithTheAddressAsReason() {
        KeepADBTrustedNetwork.addBssid(context, HOME_KITCHEN, HOME);
        KeepADBNetworkBlocklist.blockBssid(context, HOME_KITCHEN);

        Row row = onlyRow(build(null));
        assertEquals(Status.BLOCKED, row.status);
        assertEquals(Reason.ACCESS_POINT, row.reason);

        // The sibling access point of the same name is not affected by the address block.
        KeepADBTrustedNetwork.addBssid(context, HOME_HALL, HOME);
        Group home = build(null).groups.get(0);
        assertEquals(2, home.rows.size());
        assertEquals("Trusted rows come before blocked ones", HOME_HALL, home.rows.get(0).bssid);
        assertEquals(Status.TRUSTED, home.rows.get(0).status);
        assertEquals(Status.BLOCKED, home.rows.get(1).status);
    }

    @Test
    public void theCurrentNetworkIsBlockedNotTrustedWhenItsNameIsBlockedAndTrustedWithoutTheBlock() {
        KeepADBTrustedNetwork.addBssid(context, HOME_KITCHEN, HOME);
        KeepADBNetworkIdentity here = new KeepADBNetworkIdentity("\"" + HOME + "\"", HOME_KITCHEN);

        KeepADBNetworkList.Current trusted = build(here).current;
        assertEquals(Status.TRUSTED, trusted.status);
        assertFalse(trusted.legacyOnly);
        assertNotNull("The trusted row is its saved row", trusted.row);
        assertTrue(trusted.row.current);

        KeepADBNetworkBlocklist.blockSsid(context, HOME);
        KeepADBNetworkList.Current blocked = build(here).current;
        assertEquals(Status.BLOCKED, blocked.status);
        assertEquals(Reason.NAME, blocked.reason);
    }

    /** The other side of the name block: the address is blocked while the name is trusted. */
    @Test
    public void aBlockedAccessPointStaysBlockedWithTheAddressAsReasonWhileItsNameIsTrusted() {
        KeepADBTrustedNetwork.addBssid(context, HOME_KITCHEN, HOME);
        KeepADBTrustedNetwork.setTrustByNameEnabled(context, true);
        KeepADBBssidHistory.recordObservation(context, HOME, HOME_HALL, 5200);
        assertEquals("Control: the name trusts the sibling access point",
                Status.TRUSTED_BY_NAME, KeepADBNetworkList.statusOf(context, HOME, HOME_HALL));

        KeepADBNetworkBlocklist.blockBssid(context, HOME_HALL);

        assertEquals(Status.BLOCKED, KeepADBNetworkList.statusOf(context, HOME, HOME_HALL));
        Group home = build(null).groups.get(0);
        assertEquals(2, home.rows.size());
        assertEquals(HOME_KITCHEN, home.rows.get(0).bssid);
        assertEquals(Status.TRUSTED, home.rows.get(0).status);
        Row blocked = home.rows.get(1);
        assertEquals(HOME_HALL, blocked.bssid);
        assertEquals("Not 'trusted by name': the address block wins", Status.BLOCKED, blocked.status);
        assertEquals(Reason.ACCESS_POINT, blocked.reason);
        assertFalse("The name itself is not blocked", home.nameBlocked);
    }

    @Test
    public void aBlockedAccessPointStaysBlockedWhileTheLegacyNameListTrustsItsName() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBTrustedNetwork.addSsid(context, HOME);
        KeepADBTrustedNetwork.setSsidMatchingEnabled(context, true);
        KeepADBBssidHistory.recordObservation(context, HOME, HOME_HALL, 5200);
        assertEquals("Control: the name list trusts the access point",
                Status.TRUSTED_BY_NAME, KeepADBNetworkList.statusOf(context, HOME, HOME_HALL));

        KeepADBNetworkBlocklist.blockBssid(context, HOME_HALL);

        Row blocked = onlyRow(build(null));
        assertEquals(Status.BLOCKED, blocked.status);
        assertEquals(Reason.ACCESS_POINT, blocked.reason);
    }

    @Test
    public void theCurrentNetworkIsBlockedByItsAddressWhileItsNameIsTrusted() {
        KeepADBTrustedNetwork.addBssid(context, HOME_KITCHEN, HOME);
        KeepADBTrustedNetwork.setTrustByNameEnabled(context, true);
        KeepADBNetworkIdentity here = new KeepADBNetworkIdentity("\"" + HOME + "\"", HOME_HALL);
        assertEquals("Control: trusted by its name",
                Status.TRUSTED_BY_NAME, build(here).current.status);

        KeepADBNetworkBlocklist.blockBssid(context, HOME_HALL);

        KeepADBNetworkList.Current current = build(here).current;
        assertEquals(Status.BLOCKED, current.status);
        assertEquals(Reason.ACCESS_POINT, current.reason);
    }

    @Test
    public void aBlockedNameBehindAMaskedAddressIsBlockedNotUnreadable() {
        KeepADBNetworkIdentity masked = new KeepADBNetworkIdentity("\"" + CAFE + "\"",
                KeepADBNetworkIdentity.REDACTED_BSSID);
        assertEquals("Control: masked address without a block is unreadable",
                Status.UNREADABLE, build(masked).current.status);

        KeepADBNetworkBlocklist.blockSsid(context, CAFE);
        KeepADBNetworkList.Current current = build(masked).current;
        assertEquals(Status.BLOCKED, current.status);
        assertEquals(Reason.NAME, current.reason);
        assertNull(current.bssid);
    }

    // --- The way back: unknown, or the stored trust ------------------------------------------

    @Test
    public void stopTrustingAndLiftingAnAccessPointBlockBothEndInUnknownNeverInTrusted() {
        KeepADBTrustedNetwork.addBssid(context, HOME_KITCHEN, HOME);
        assertEquals(Outcome.STOPPED_TRUSTING, KeepADBNetworkListActions.stopTrusting(context, HOME_KITCHEN));
        assertEquals(Status.UNKNOWN, KeepADBNetworkList.statusOf(context, HOME, HOME_KITCHEN));
        assertTrue("An unknown access point is no saved row", build(null).isEmpty());

        // Block a trusted access point, then lift: the trust must not come back by itself.
        KeepADBTrustedNetwork.addBssid(context, HOME_HALL, HOME);
        assertEquals(Outcome.BLOCKED_ACCESS_POINT, KeepADBNetworkListActions.blockAccessPoint(context, HOME_HALL));
        Row blocked = onlyRow(build(null));
        assertEquals(Status.BLOCKED, blocked.status);
        assertEquals(Reason.ACCESS_POINT, blocked.reason);
        assertNull("Blocking drops the trust stored under the address", blocked.entry);

        assertEquals(Outcome.ACCESS_POINT_BLOCK_LIFTED,
                KeepADBNetworkListActions.liftAccessPointBlock(context, HOME_HALL));
        assertEquals(Status.UNKNOWN, KeepADBNetworkList.statusOf(context, HOME, HOME_HALL));
        assertTrue(KeepADBTrustedNetwork.getEntries(context).isEmpty());
        assertTrue(KeepADBNetworkBlocklist.isEmpty(context));
        assertTrue(build(null).isEmpty());
    }

    @Test
    public void liftingABlockThatSitsOnAStoredTrustStillEndsInUnknown() {
        // Data that did not come through the list (migration, older versions): trusted and blocked.
        KeepADBTrustedNetwork.addBssid(context, HOME_KITCHEN, HOME);
        KeepADBNetworkBlocklist.blockBssid(context, HOME_KITCHEN);

        KeepADBNetworkListActions.liftAccessPointBlock(context, HOME_KITCHEN);

        assertEquals(Status.UNKNOWN, KeepADBNetworkList.statusOf(context, HOME, HOME_KITCHEN));
        assertTrue(KeepADBTrustedNetwork.getEntries(context).isEmpty());
    }

    @Test
    public void liftingANameBlockGivesTheStoredTrustBackAndLeavesUnknownOnesUnknown() {
        KeepADBTrustedNetwork.addBssid(context, HOME_KITCHEN, HOME);
        KeepADBNetworkBlocklist.blockSsid(context, HOME);
        assertEquals(Status.BLOCKED, onlyRow(build(null)).status);

        assertEquals(Outcome.NAME_BLOCK_LIFTED, KeepADBNetworkListActions.liftNameBlock(context, HOME));

        Row row = onlyRow(build(null));
        assertEquals("Trusted again as soon as the name block is lifted", Status.TRUSTED, row.status);
        assertEquals("An access point nobody trusted is still unknown",
                Status.UNKNOWN, KeepADBNetworkList.statusOf(context, HOME, HOME_HALL));
        assertFalse(KeepADBNetworkBlocklist.isSsidBlocked(context, HOME));
    }

    @Test
    public void trustingABlockedAccessPointLiftsOnlyThatBlockAndIsRefusedWhileTheNameIsBlocked() {
        KeepADBNetworkBlocklist.blockBssid(context, CAFE_AP);
        KeepADBNetworkBlocklist.blockBssid(context, OTHER_AP);

        assertEquals(Outcome.TRUSTED, KeepADBNetworkListActions.trustBlockedAccessPoint(context, CAFE_AP, CAFE));
        assertFalse(KeepADBNetworkBlocklist.isBssidBlocked(context, CAFE_AP));
        assertTrue("Only the access point asked for", KeepADBNetworkBlocklist.isBssidBlocked(context, OTHER_AP));
        assertEquals(Status.TRUSTED, KeepADBNetworkList.statusOf(context, CAFE, CAFE_AP));

        // The same with a name block: refused, nothing lifted, nothing stored.
        KeepADBNetworkBlocklist.blockBssid(context, HOME_KITCHEN);
        KeepADBNetworkBlocklist.blockSsid(context, HOME);
        assertEquals(Outcome.TRUST_REFUSED_NAME_BLOCKED,
                KeepADBNetworkListActions.trustBlockedAccessPoint(context, HOME_KITCHEN, HOME));
        assertTrue(KeepADBNetworkBlocklist.isBssidBlocked(context, HOME_KITCHEN));
        assertTrue(KeepADBNetworkBlocklist.isSsidBlocked(context, HOME));
        assertEquals(1, KeepADBTrustedNetwork.getEntries(context).size());
    }

    @Test
    public void blockingTheNameBlocksEveryAccessPointOfItAndNoAnswerSwitchesDebuggingOnOrOff() {
        KeepADBTrustedNetwork.addBssid(context, HOME_KITCHEN, HOME);
        KeepADBTrustedNetwork.addBssid(context, HOME_HALL, HOME);

        assertEquals(Outcome.BLOCKED_NAME, KeepADBNetworkListActions.blockName(context, HOME));

        for (Row row : build(null).groups.get(0).rows) {
            assertEquals(row.bssid, Status.BLOCKED, row.status);
            assertEquals(Reason.NAME, row.reason);
        }
        assertEquals("Not usable: nothing to block", Outcome.FAILED, KeepADBNetworkListActions.blockName(context, ""));
        assertTrue("No answer of the list writes the setting: " + gateway.writes, gateway.writes.isEmpty());
    }

    // --- Grouping, order and counts ---------------------------------------------------------

    @Test
    public void groupsAreByNameWithTrustedFirstAndTheNameBlockWithoutAccessPointsIsAGroupOfItsOwn() {
        KeepADBTrustedNetwork.addBssid(context, HOME_HALL, HOME);
        KeepADBTrustedNetwork.addBssid(context, HOME_KITCHEN, HOME);
        KeepADBTrustedNetwork.setCustomName(context,
                KeepADBTrustedNetwork.getEntries(context).get(1).id, "Kueche");
        KeepADBNetworkBlocklist.blockBssid(context, CAFE_AP);
        KeepADBNetworkBlocklist.blockSsid(context, "Gast");
        KeepADBNetworkBlocklist.blockSsid(context, CAFE);
        KeepADBBssidHistory.recordObservation(context, CAFE, CAFE_AP);

        Snapshot snapshot = build(null);

        List<String> names = new ArrayList<>();
        for (Group group : snapshot.groups) names.add(group.ssid);
        assertEquals("Trusted group first, then the blocked ones alphabetically",
                List.of(HOME, CAFE, "Gast"), names);
        assertEquals(2, snapshot.groups.get(0).rows.size());
        assertEquals("The access point with an own name sorts by it", HOME_KITCHEN,
                snapshot.groups.get(0).rows.get(0).bssid);
        assertEquals("The name of a blocked address comes from the observation",
                CAFE, snapshot.groups.get(1).rows.get(0).ssid);
        assertEquals(Reason.ACCESS_POINT, snapshot.groups.get(1).rows.get(0).reason);
        assertTrue(snapshot.groups.get(2).rows.isEmpty());
        assertTrue(snapshot.groups.get(2).nameBlocked);
        assertEquals(2, snapshot.trusted);
        assertEquals("The blocked address plus the blocked name without an access point",
                2, snapshot.blocked);
    }

    @Test
    public void anAddressWithoutAKnownNameLandsInTheLastGroupWithNoName() {
        KeepADBNetworkBlocklist.blockBssid(context, OTHER_AP);
        KeepADBTrustedNetwork.addBssid(context, HOME_KITCHEN, HOME);

        List<Group> groups = build(null).groups;

        assertEquals(2, groups.size());
        assertEquals(HOME, groups.get(0).ssid);
        assertNull(groups.get(1).ssid);
        assertEquals(OTHER_AP, groups.get(1).rows.get(0).bssid);
    }

    // --- The current network ----------------------------------------------------------------

    @Test
    public void theCurrentNetworkIsUnknownTrustedByNameOrTrustedOnlyByTheLegacyPolicy() {
        KeepADBNetworkIdentity here = new KeepADBNetworkIdentity("\"" + HOME + "\"", HOME_HALL);

        assertEquals(Status.UNKNOWN, build(here).current.status);

        KeepADBTrustedNetwork.addBssid(context, HOME_KITCHEN, HOME);
        assertEquals("Without the comfort switch the name trusts nothing",
                Status.UNKNOWN, build(here).current.status);
        KeepADBTrustedNetwork.setTrustByNameEnabled(context, true);
        assertEquals(Status.TRUSTED_BY_NAME, build(here).current.status);

        KeepADBTrustedNetwork.setTrustByNameEnabled(context, false);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        KeepADBNetworkList.Current legacy = build(here).current;
        assertEquals(Status.TRUSTED, legacy.status);
        assertTrue("Trusted only because of the previous setting", legacy.legacyOnly);
        assertNull("No saved row behind it", legacy.row);
    }

    @Test
    public void anUnreadableNetworkAndNoNetworkAreToldApart() {
        KeepADBNetworkIdentity unreadable = new KeepADBNetworkIdentity(null, null);
        assertEquals(Status.UNREADABLE, KeepADBNetworkList.build(context, unreadable, true).current.status);
        assertEquals(Status.NO_WIFI, KeepADBNetworkList.build(context, unreadable, false).current.status);
        assertNotEquals(Status.UNREADABLE, KeepADBNetworkList.build(context, null, false).current.status);
    }

    // --- Helpers ----------------------------------------------------------------------------

    private Snapshot build(KeepADBNetworkIdentity identity) {
        return KeepADBNetworkList.build(context, identity, identity != null);
    }

    private static Row onlyRow(Snapshot snapshot) {
        assertEquals(1, snapshot.groups.size());
        assertEquals(1, snapshot.groups.get(0).rows.size());
        return snapshot.groups.get(0).rows.get(0);
    }
}
