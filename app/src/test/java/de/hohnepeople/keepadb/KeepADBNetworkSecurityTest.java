package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowWifiInfo;

/**
 * #860: the opt-in security check of trusted access points. A baseline is recorded when the user
 * trusts the access point they are on; with the check on, a lower or unreadable tier blocks
 * automatic enable until it is trusted again. Normal and adversarial transitions both ways.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBNetworkSecurityTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private static final String HOME = "aa:bb:cc:00:00:01";
    private static final String MESH = "aa:bb:cc:00:00:02";
    // WifiInfo.SECURITY_TYPE_* values.
    private static final int OPEN = 0;
    private static final int PSK = 2;
    private static final int EAP = 3;
    private static final int SAE = 4;
    private static final int OWE = 6;
    private static final int UNKNOWN = -1;

    private final Context context = ApplicationProvider.getApplicationContext();

    @Before
    public void setUp() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
    }

    @After
    public void tearDown() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test
    public void downgradeIsATierChangeOnly() {
        assertEquals(KeepADBNetworkSecurity.tierOfType(PSK), KeepADBNetworkSecurity.tierOfType(SAE));
        assertEquals(KeepADBNetworkSecurity.TIER_OPEN, KeepADBNetworkSecurity.tierOfType(OWE));
        assertEquals(KeepADBNetworkSecurity.TIER_OPEN, KeepADBNetworkSecurity.tierOfType(OPEN));
        assertEquals(KeepADBNetworkSecurity.TIER_ENTERPRISE, KeepADBNetworkSecurity.tierOfType(EAP));
        assertEquals(KeepADBNetworkSecurity.TIER_UNKNOWN, KeepADBNetworkSecurity.tierOfType(UNKNOWN));
    }

    @Test
    public void personalAccessPointFallingToOpen_blocksAutomaticEnable() {
        trustWhileConnected(HOME, PSK);
        KeepADBTrustedNetwork.setSecurityCheckEnabled(context, true);

        connectTo(HOME, OPEN);

        assertEquals(KeepADBTrustedNetwork.Decision.SECURITY_MISMATCH,
                KeepADBTrustedNetwork.evaluateCurrent(context));
        assertFalse(KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
        assertEquals(KeepADBTrustedNetwork.BlockReason.UNTRUSTED_NETWORK,
                KeepADBTrustedNetwork.getBlockReason(context));
    }

    @Test
    public void transitionModePskAfterSae_andAnUpgrade_stayTrusted() {
        trustWhileConnected(HOME, SAE);
        KeepADBTrustedNetwork.setSecurityCheckEnabled(context, true);

        connectTo(HOME, PSK);
        assertTrue("PSK on a WPA2/WPA3 transition AP", KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
        connectTo(HOME, EAP);
        assertTrue("a higher tier", KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
    }

    @Test
    public void unreadableTierWithABaseline_failsClosed() {
        trustWhileConnected(HOME, PSK);
        KeepADBTrustedNetwork.setSecurityCheckEnabled(context, true);

        connectTo(HOME, UNKNOWN);

        assertEquals(KeepADBTrustedNetwork.Decision.SECURITY_MISMATCH,
                KeepADBTrustedNetwork.evaluateCurrent(context));
    }

    @Test
    public void optionOff_keepsTheAddressOnlyRule() {
        trustWhileConnected(HOME, PSK);

        connectTo(HOME, OPEN);

        assertEquals(KeepADBTrustedNetwork.Decision.TRUSTED_ACCESS_POINT,
                KeepADBTrustedNetwork.evaluateCurrent(context));
    }

    @Test
    public void entryWithoutBaseline_keepsTheAddressOnlyRule() {
        KeepADBTrustedNetwork.addBssid(context, HOME, "Home");
        KeepADBTrustedNetwork.setSecurityCheckEnabled(context, true);

        connectTo(HOME, OPEN);

        assertTrue(KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
    }

    @Test
    public void trustingAnotherAccessPoint_recordsNoBaselineForIt() {
        connectTo(HOME, PSK);
        KeepADBReceiver.allowBssidOnly(context, MESH, "Home");
        KeepADBTrustedNetwork.setSecurityCheckEnabled(context, true);

        connectTo(MESH, OPEN);

        assertTrue("a mesh sibling trusted from elsewhere has no baseline",
                KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
    }

    @Test
    public void trustingAgain_acceptsTheNewTier() {
        trustWhileConnected(HOME, PSK);
        KeepADBTrustedNetwork.setSecurityCheckEnabled(context, true);
        connectTo(HOME, OPEN);
        assertTrue(KeepADBTrustedNetwork.isSecurityMismatchForCurrent(context, HOME));

        KeepADBReceiver.allowBssidOnly(context, HOME, "Home");

        assertTrue(KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
        assertFalse(KeepADBTrustedNetwork.isSecurityMismatchForCurrent(context, HOME));
    }

    @Test
    public void aTrustedNameDoesNotRescueAFailedCheck() {
        trustWhileConnected(HOME, PSK);
        KeepADBTrustedNetwork.setTrustByNameEnabled(context, true);
        KeepADBTrustedNetwork.setSecurityCheckEnabled(context, true);

        connectTo(HOME, OPEN);

        assertEquals(KeepADBTrustedNetwork.Decision.SECURITY_MISMATCH,
                KeepADBTrustedNetwork.evaluateCurrent(context));
    }

    @Test
    public void aBlockStillWins() {
        trustWhileConnected(HOME, PSK);
        KeepADBTrustedNetwork.setSecurityCheckEnabled(context, true);
        KeepADBNetworkBlocklist.blockBssid(context, HOME);

        connectTo(HOME, OPEN);

        assertEquals(KeepADBTrustedNetwork.Decision.BLOCKED_ACCESS_POINT,
                KeepADBTrustedNetwork.evaluateCurrent(context));
    }

    @Test
    public void storedRowsAreNotChecked_andRemovingTheEntryDropsTheBaseline() {
        trustWhileConnected(HOME, PSK);
        KeepADBTrustedNetwork.setSecurityCheckEnabled(context, true);
        connectTo(HOME, OPEN);
        assertEquals(KeepADBNetworkList.Status.TRUSTED,
                KeepADBNetworkList.statusOf(context, "Home", HOME));

        int id = KeepADBTrustedNetwork.getEntries(context).get(0).id;
        assertEquals(Integer.valueOf(KeepADBNetworkSecurity.TIER_PERSONAL),
                KeepADBTrustedNetwork.getSecurityBaseline(context, id));
        KeepADBTrustedNetwork.remove(context, id);
        assertNull(KeepADBTrustedNetwork.getSecurityBaseline(context, id));
    }

    /** The decision dialog asks again about a trusted access point that failed the check. */
    @Test
    public void aFailedCheckIsUndecidedInTheDecisionDialog() {
        trustWhileConnected(HOME, PSK);
        KeepADBTrustedNetwork.setSecurityCheckEnabled(context, true);
        assertEquals(KeepADBNetworkDecision.Status.ALREADY_DECIDED,
                KeepADBNetworkDecision.resolve(context, HOME).status);

        connectTo(HOME, OPEN);
        KeepADBBlockedNetworkHistory.record(context, KeepADBNetworkIdentity.current(context),
                System.currentTimeMillis());

        assertEquals(KeepADBNetworkDecision.Status.PENDING,
                KeepADBNetworkDecision.resolve(context, HOME).status);
    }

    /** Codex review: a trusted name does not make a failed check "decided" in the dialog. */
    @Test
    public void aTrustedNameDoesNotSettleAFailedCheckInTheDecisionDialog() {
        trustWhileConnected(HOME, PSK);
        KeepADBTrustedNetwork.setTrustByNameEnabled(context, true);
        KeepADBTrustedNetwork.setSecurityCheckEnabled(context, true);
        connectTo(HOME, OPEN);
        KeepADBBlockedNetworkHistory.record(context, KeepADBNetworkIdentity.current(context),
                System.currentTimeMillis());

        assertEquals(KeepADBNetworkDecision.Status.PENDING,
                KeepADBNetworkDecision.resolve(context, HOME).status);
    }

    /** Codex review: the Settings card agrees with the policy and offers to trust again. */
    @Test
    public void theNetworkCardShowsAFailedCheckAsNotAllowed() {
        trustWhileConnected(HOME, PSK);
        KeepADBTrustedNetwork.setSecurityCheckEnabled(context, true);
        assertEquals(KeepADBNetworkCardState.Connection.ALLOWED_AP, cardState().connection);

        connectTo(HOME, OPEN);

        KeepADBNetworkCardState.Snapshot snapshot = cardState();
        assertEquals(KeepADBNetworkCardState.Connection.NOT_ALLOWED, snapshot.connection);
        assertEquals(KeepADBNetworkCardState.Action.ALLOW_ACCESS_POINT, snapshot.action);
    }

    private KeepADBNetworkCardState.Snapshot cardState() {
        return KeepADBNetworkCardState.derive(KeepADBNetworkCardState.read(context,
                KeepADBNetworkIdentity.current(context), true, true, true, true));
    }

    /** Codex review: the legacy "all networks" policy does not bypass an opted-in check. */
    @Test
    public void legacyAllNetworksPolicy_stillHonorsTheCheck() {
        trustWhileConnected(HOME, PSK);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        KeepADBTrustedNetwork.setSecurityCheckEnabled(context, true);

        connectTo(HOME, OPEN);
        assertEquals(KeepADBTrustedNetwork.Decision.SECURITY_MISMATCH,
                KeepADBTrustedNetwork.evaluateCurrent(context));

        connectTo(MESH, OPEN);
        assertEquals("other networks keep the legacy policy",
                KeepADBTrustedNetwork.Decision.LEGACY_ALL_WIFI,
                KeepADBTrustedNetwork.evaluateCurrent(context));

        KeepADBTrustedNetwork.setSecurityCheckEnabled(context, false);
        connectTo(HOME, OPEN);
        assertEquals(KeepADBTrustedNetwork.Decision.LEGACY_ALL_WIFI,
                KeepADBTrustedNetwork.evaluateCurrent(context));
    }

    private void trustWhileConnected(String bssid, int securityType) {
        connectTo(bssid, securityType);
        KeepADBReceiver.allowBssidOnly(context, bssid, "Home");
    }

    private void connectTo(String bssid, int securityType) {
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = ShadowWifiInfo.newInstance();
        shadowOf(info).setSSID("Home");
        shadowOf(info).setBSSID(bssid);
        try {
            // Hidden framework setter; Robolectric 4.13 has no shadow for the security type.
            WifiInfo.class.getMethod("setCurrentSecurityType", int.class).invoke(info, securityType);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        shadowOf(wifiManager).setConnectionInfo(info);
    }
}
