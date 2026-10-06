package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * #766: the logic behind the "Trust this network?" decision, without a screen. The load-bearing
 * invariant, pinned from both sides in every order: a block beats trust, trust never lifts a
 * block, and a block never deletes a stored trust (it applies again once the block is lifted).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBNetworkDecisionTest {

    private static final String SSID = "Cafe-WLAN";
    private static final String BSSID = "aa:bb:cc:dd:ee:01";
    private static final String OTHER_BSSID = "aa:bb:cc:dd:ee:02";

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = RuntimeEnvironment.getApplication();
    private KeepADBFakeSettingsGateway gateway;

    @Before
    public void setUp() {
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.POST_NOTIFICATIONS,
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
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

    // --- resolve --------------------------------------------------------------------------------

    @Test
    public void aRecordedUnansweredAccessPointIsPendingWithItsRecordedName() {
        record(SSID, BSSID);

        KeepADBNetworkDecision.Resolution resolution = KeepADBNetworkDecision.resolve(context, BSSID);

        assertEquals(KeepADBNetworkDecision.Status.PENDING, resolution.status);
        assertEquals(BSSID, resolution.pending.bssid);
        assertEquals(SSID, resolution.pending.ssid);
        assertTrue(resolution.pending.canBlockName());
    }

    @Test
    public void anAccessPointWithoutAReadableNameIsPendingButHasNoNameToBlock() {
        record(null, BSSID);

        KeepADBNetworkDecision.Resolution resolution = KeepADBNetworkDecision.resolve(context, BSSID);

        assertEquals(KeepADBNetworkDecision.Status.PENDING, resolution.status);
        assertNull(resolution.pending.ssid);
        assertFalse(resolution.pending.canBlockName());
    }

    @Test
    public void everyWayOfAnsweringMakesTheQuestionAlreadyDecidedAndControlsStayPending() {
        record(SSID, BSSID);
        record(SSID, OTHER_BSSID);
        assertPending(BSSID);
        assertPending(OTHER_BSSID);

        KeepADBNetworkBlocklist.blockBssid(context, BSSID);
        assertEquals(KeepADBNetworkDecision.Status.ALREADY_DECIDED, resolve(BSSID));
        assertPending(OTHER_BSSID);

        KeepADBNetworkBlocklist.unblockBssid(context, BSSID);
        assertPending(BSSID);
        KeepADBNetworkBlocklist.blockSsid(context, SSID);
        assertEquals("A block on the name decides every access point of that name",
                KeepADBNetworkDecision.Status.ALREADY_DECIDED, resolve(BSSID));
        assertEquals(KeepADBNetworkDecision.Status.ALREADY_DECIDED, resolve(OTHER_BSSID));

        KeepADBNetworkBlocklist.unblockSsid(context, SSID);
        KeepADBTrustedNetwork.addBssid(context, BSSID, SSID);
        assertEquals("A trusted access point is decided", KeepADBNetworkDecision.Status.ALREADY_DECIDED,
                resolve(BSSID));
        assertPending(OTHER_BSSID);
    }

    @Test
    public void aNameTrustedByTheComfortSwitchIsDecidedOnlyWhileTheSwitchIsOn() {
        KeepADBTrustedNetwork.addBssid(context, BSSID, SSID);
        record(SSID, OTHER_BSSID);

        assertPending(OTHER_BSSID);
        KeepADBTrustedNetwork.setTrustByNameEnabled(context, true);
        assertEquals(KeepADBNetworkDecision.Status.ALREADY_DECIDED, resolve(OTHER_BSSID));
        KeepADBTrustedNetwork.setTrustByNameEnabled(context, false);
        assertPending(OTHER_BSSID);
    }

    @Test
    public void theFormerAllWifiSettingDoesNotMakeTheQuestionDecided() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        record(SSID, BSSID);

        assertPending(BSSID);
    }

    @Test
    public void anUnrecordedOrPlaceholderBssidIsExpiredNotPending() {
        record(SSID, BSSID);

        for (String bssid : new String[] {OTHER_BSSID, null, "", "  ",
                KeepADBNetworkIdentity.REDACTED_BSSID, KeepADBNetworkIdentity.UNSET_BSSID}) {
            KeepADBNetworkDecision.Resolution resolution = KeepADBNetworkDecision.resolve(context, bssid);
            assertEquals("'" + bssid + "'", KeepADBNetworkDecision.Status.EXPIRED, resolution.status);
            assertNull(resolution.pending);
        }
        // Without a record, a block or a trusted entry still reads as answered, not as expired.
        KeepADBNetworkBlocklist.blockBssid(context, OTHER_BSSID);
        assertEquals(KeepADBNetworkDecision.Status.ALREADY_DECIDED, resolve(OTHER_BSSID));
    }

    // --- precedence, both sides, every order ----------------------------------------------------

    @Test
    public void trustThenBlockLeavesTheAccessPointBlockedButKeepsTheStoredTrust() {
        KeepADBNetworkDecision.Pending pending = pending(SSID, BSSID);

        assertEquals(KeepADBNetworkDecision.Outcome.TRUSTED,
                KeepADBNetworkDecision.trust(context, pending).outcome);
        assertEquals(KeepADBTrustedNetwork.Decision.TRUSTED_ACCESS_POINT, decisionFor(SSID, BSSID));

        assertEquals(KeepADBNetworkDecision.Outcome.BLOCKED_ACCESS_POINT,
                KeepADBNetworkDecision.blockAccessPoint(context, BSSID));
        assertEquals("A block beats the trust of the same access point",
                KeepADBTrustedNetwork.Decision.BLOCKED_ACCESS_POINT, decisionFor(SSID, BSSID));
        assertEquals("... but does not delete it", 1, KeepADBTrustedNetwork.getEntries(context).size());

        KeepADBNetworkBlocklist.unblockBssid(context, BSSID);
        assertEquals("Lifting the block makes the stored trust apply again",
                KeepADBTrustedNetwork.Decision.TRUSTED_ACCESS_POINT, decisionFor(SSID, BSSID));
    }

    @Test
    public void blockThenTrustIsRefusedStoresNothingAndReportsTheBlock() {
        KeepADBNetworkDecision.Pending pending = pending(SSID, BSSID);
        KeepADBNetworkDecision.blockAccessPoint(context, BSSID);

        KeepADBNetworkDecision.TrustResult result = KeepADBNetworkDecision.trust(context, pending);

        assertEquals(KeepADBNetworkDecision.Outcome.TRUST_REFUSED_BLOCKED, result.outcome);
        assertFalse(result.enabled);
        assertTrue(KeepADBTrustedNetwork.getEntries(context).isEmpty());
        assertTrue(KeepADBNetworkBlocklist.isBssidBlocked(context, BSSID));
        assertTrue("Nothing was switched on", gateway.writes.isEmpty());
    }

    @Test
    public void aBlockedNameRefusesTrustForEveryAccessPointOfThatName() {
        KeepADBNetworkDecision.blockName(context, SSID);

        for (String bssid : new String[] {BSSID, OTHER_BSSID}) {
            assertEquals(KeepADBNetworkDecision.Outcome.TRUST_REFUSED_BLOCKED,
                    KeepADBNetworkDecision.trust(context, pending(SSID, bssid)).outcome);
        }
        assertTrue(KeepADBTrustedNetwork.getEntries(context).isEmpty());

        // Control: another name is trusted as usual.
        assertEquals(KeepADBNetworkDecision.Outcome.TRUSTED,
                KeepADBNetworkDecision.trust(context, pending("Other", "aa:bb:cc:dd:ee:77")).outcome);
    }

    @Test
    public void blockNameBlocksTheNameWithoutBlockingTheAddressAndTheOtherWayRound() {
        assertEquals(KeepADBNetworkDecision.Outcome.BLOCKED_NAME,
                KeepADBNetworkDecision.blockName(context, SSID));
        assertTrue(KeepADBNetworkBlocklist.isSsidBlocked(context, SSID));
        assertFalse(KeepADBNetworkBlocklist.isBssidBlocked(context, BSSID));

        assertEquals(KeepADBNetworkDecision.Outcome.BLOCKED_ACCESS_POINT,
                KeepADBNetworkDecision.blockAccessPoint(context, OTHER_BSSID));
        assertTrue(KeepADBNetworkBlocklist.isBssidBlocked(context, OTHER_BSSID));
        assertEquals("Only the name block of the first answer exists", 1,
                KeepADBNetworkBlocklist.getBlockedSsids(context).size());
        assertEquals(1, KeepADBNetworkBlocklist.getBlockedBssids(context).size());
    }

    @Test
    public void unusableTargetsCannotBeBlockedAndBlockNothing() {
        for (String bssid : new String[] {null, "", "  ",
                KeepADBNetworkIdentity.REDACTED_BSSID, KeepADBNetworkIdentity.UNSET_BSSID}) {
            assertEquals("'" + bssid + "'", KeepADBNetworkDecision.Outcome.BLOCK_FAILED,
                    KeepADBNetworkDecision.blockAccessPoint(context, bssid));
        }
        for (String ssid : new String[] {null, "", android.net.wifi.WifiManager.UNKNOWN_SSID}) {
            assertEquals("'" + ssid + "'", KeepADBNetworkDecision.Outcome.BLOCK_FAILED,
                    KeepADBNetworkDecision.blockName(context, ssid));
        }
        assertTrue("A placeholder block would match every unreadable network",
                KeepADBNetworkBlocklist.isEmpty(context));
    }

    @Test
    public void blockingTwiceStaysBlockedAndReportsTheBlock() {
        assertEquals(KeepADBNetworkDecision.Outcome.BLOCKED_ACCESS_POINT,
                KeepADBNetworkDecision.blockAccessPoint(context, BSSID));
        assertEquals(KeepADBNetworkDecision.Outcome.BLOCKED_ACCESS_POINT,
                KeepADBNetworkDecision.blockAccessPoint(context, BSSID.toUpperCase(java.util.Locale.ROOT)));
        assertEquals(1, KeepADBNetworkBlocklist.getBlockedBssids(context).size());
    }

    @Test
    public void blockingDoesNotSwitchWirelessDebuggingEitherWay() {
        KeepADBNetworkDecision.blockAccessPoint(context, BSSID);
        KeepADBNetworkDecision.blockName(context, SSID);

        assertTrue("A block only ever takes automatic actions away", gateway.writes.isEmpty());
    }

    @Test
    public void trustingStoresTheRecordedNameAndRemovesTheRecordOnlyForThatAccessPoint() {
        record(SSID, BSSID);
        record(SSID, OTHER_BSSID);

        KeepADBNetworkDecision.TrustResult result =
                KeepADBNetworkDecision.trust(context, pending(SSID, BSSID));

        assertEquals(KeepADBNetworkDecision.Outcome.TRUSTED, result.outcome);
        assertEquals(SSID, KeepADBTrustedNetwork.getEntries(context).get(0).label);
        assertEquals(1, KeepADBBlockedNetworkHistory.getEntries(context).size());
        assertEquals(OTHER_BSSID, KeepADBBlockedNetworkHistory.getEntries(context).get(0).bssid);
        assertNotNull(KeepADBNetworkDecision.resolve(context, OTHER_BSSID).pending);
    }

    // --- helpers --------------------------------------------------------------------------------

    private void record(String ssid, String bssid) {
        KeepADBBlockedNetworkHistory.record(context, new KeepADBNetworkIdentity(
                ssid == null ? null : "\"" + ssid + "\"", bssid), 1L);
    }

    private KeepADBNetworkDecision.Pending pending(String ssid, String bssid) {
        return new KeepADBNetworkDecision.Pending(bssid, ssid);
    }

    private KeepADBNetworkDecision.Status resolve(String bssid) {
        return KeepADBNetworkDecision.resolve(context, bssid).status;
    }

    private void assertPending(String bssid) {
        assertEquals(bssid, KeepADBNetworkDecision.Status.PENDING, resolve(bssid));
    }

    private KeepADBTrustedNetwork.Decision decisionFor(String ssid, String bssid) {
        return KeepADBTrustedNetwork.evaluate(context,
                new KeepADBNetworkIdentity("\"" + ssid + "\"", bssid));
    }

    private android.content.SharedPreferences prefs() {
        return context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE);
    }
}
