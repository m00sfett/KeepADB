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

import de.hohnepeople.keepadb.KeepADBTrustedNetwork.Decision;

/**
 * #760: the precedence of the unified trust model -- block, then legacy open policy, then trusted
 * access point, then trusted name, else unknown or unreadable -- asserted through the production
 * entry points ({@link KeepADBTrustedNetwork#isCurrentNetworkTrusted}, {@link
 * KeepADBTrustedNetwork#getBlockReason}, {@link KeepADBTrustedNetwork#evaluateCurrent}) against the
 * real {@code WifiManager} shadow, i.e. the very path every automatic re-enable takes. Whether those
 * call sites actually ask is covered in {@link KeepADBBlockedNetworkCallPathTest}.
 *
 * <p>Every invariant here is two-sided: the block side (a block denies whatever trust says) always
 * next to the side that a too-wide or too-eager block would break (other networks, unreadable
 * networks, trust that must survive, trust that must not be created or lifted by accident).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBTrustPrecedenceTest {

    private static final String HOME = "Home";
    private static final String HOME_BSSID = "aa:bb:cc:dd:ee:01";
    private static final String HOME_SIBLING_BSSID = "aa:bb:cc:dd:ee:02";
    private static final String OFFICE = "Office";
    private static final String OFFICE_BSSID = "11:22:33:44:55:01";
    private static final String CAFE = "Cafe";
    private static final String CAFE_BSSID = "cc:cc:cc:cc:cc:01";

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

    // --- Block wins, in every policy ---------------------------------------------------------

    /**
     * The core invariant over every combination of policy, trust configuration and connected
     * network: whatever the independent oracle says without a block, any block on the connected
     * access point or on its name denies, and lifting the blocks returns the oracle's answer
     * exactly. The matrix must contain allowed and denied cases or it proves nothing.
     */
    @Test
    public void aBlockDeniesWhateverTheTrustSaysAndLiftingItRestoresTheSameAnswer() {
        int allowedWithoutBlock = 0;
        int deniedWithoutBlock = 0;
        for (boolean legacyOpen : new boolean[] {false, true}) {
            for (boolean apListed : new boolean[] {false, true}) {
                for (boolean comfort : new boolean[] {false, true}) {
                    for (boolean legacyNames : new boolean[] {false, true}) {
                        for (boolean onSibling : new boolean[] {false, true}) {
                            prefs().edit().clear().commit();
                            Scenario scenario = new Scenario(legacyOpen, apListed, comfort,
                                    legacyNames, onSibling);
                            scenario.apply();
                            String label = scenario.toString();

                            boolean expected = scenario.expectedWithoutBlock();
                            assertEquals("No block: " + label, expected,
                                    KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
                            if (expected) allowedWithoutBlock++; else deniedWithoutBlock++;

                            String blockedBssid = scenario.connectedBssid();
                            KeepADBNetworkBlocklist.blockBssid(context, blockedBssid);
                            assertFalse("BSSID block must deny: " + label,
                                    KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
                            assertTrue(KeepADBTrustedNetwork.evaluateCurrent(context).isBlocked());
                            KeepADBNetworkBlocklist.unblockBssid(context, blockedBssid);
                            assertEquals("Unblock restores: " + label, expected,
                                    KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));

                            KeepADBNetworkBlocklist.blockSsid(context, HOME);
                            assertFalse("Name block must deny: " + label,
                                    KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
                            assertEquals(Decision.BLOCKED_NAME,
                                    KeepADBTrustedNetwork.evaluateCurrent(context));
                            KeepADBNetworkBlocklist.unblockSsid(context, HOME);
                            assertEquals("Unblock restores: " + label, expected,
                                    KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));

                            KeepADBNetworkBlocklist.blockBssid(context, blockedBssid);
                            KeepADBNetworkBlocklist.blockSsid(context, HOME);
                            assertFalse("Both blocks must deny: " + label,
                                    KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
                            // Lifting only one of two keeps the other in force.
                            KeepADBNetworkBlocklist.unblockBssid(context, blockedBssid);
                            assertFalse("The name block alone still denies: " + label,
                                    KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
                            KeepADBNetworkBlocklist.unblockSsid(context, HOME);
                            assertEquals(expected, KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
                        }
                    }
                }
            }
        }
        assertTrue("The matrix must contain allowed cases", allowedWithoutBlock > 0);
        assertTrue("The matrix must contain denied cases", deniedWithoutBlock > 0);
    }

    @Test
    public void aBlockOnTheAccessPointBeatsItsOwnTrustAndTheTrustSurvivesTheBlock() {
        KeepADBTrustedNetwork.Entry entry = KeepADBTrustedNetwork.addBssid(context, HOME_BSSID, HOME);
        connectTo(HOME, HOME_BSSID);
        assertTrue(KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
        assertEquals(Decision.TRUSTED_ACCESS_POINT, KeepADBTrustedNetwork.evaluateCurrent(context));

        KeepADBNetworkBlocklist.blockBssid(context, HOME_BSSID);

        assertFalse(KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
        assertEquals(Decision.BLOCKED_ACCESS_POINT, KeepADBTrustedNetwork.evaluateCurrent(context));
        assertEquals("A blocked network reads as not trusted, not as unreadable",
                KeepADBTrustedNetwork.BlockReason.UNTRUSTED_NETWORK,
                KeepADBTrustedNetwork.getBlockReason(context));
        assertEquals("The block must not destroy the trust it overrules", 1,
                KeepADBTrustedNetwork.getEntries(context).size());
        assertEquals(entry.id, KeepADBTrustedNetwork.getEntries(context).get(0).id);

        assertTrue(KeepADBNetworkBlocklist.unblockBssid(context, HOME_BSSID));
        assertTrue("Explicit unblock brings the stored trust back",
                KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
        assertEquals(KeepADBTrustedNetwork.BlockReason.NONE,
                KeepADBTrustedNetwork.getBlockReason(context));
    }

    @Test
    public void aBlockOnTheNameBeatsTrustOfEveryAccessPointCarryingItButNoOtherName() {
        KeepADBTrustedNetwork.addBssid(context, HOME_BSSID, HOME);
        KeepADBTrustedNetwork.addBssid(context, HOME_SIBLING_BSSID, HOME);
        KeepADBTrustedNetwork.addBssid(context, OFFICE_BSSID, OFFICE);

        KeepADBNetworkBlocklist.blockSsid(context, HOME);

        // Denied side.
        connectTo(HOME, HOME_BSSID);
        assertFalse(KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
        assertEquals(Decision.BLOCKED_NAME, KeepADBTrustedNetwork.evaluateCurrent(context));
        connectTo(HOME, HOME_SIBLING_BSSID);
        assertFalse(KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
        // A name block holds for an access point nobody ever listed, too.
        connectTo(HOME, "ee:ee:ee:ee:ee:ee");
        assertEquals(Decision.BLOCKED_NAME, KeepADBTrustedNetwork.evaluateCurrent(context));
        // The other side: a trusted access point of another name is untouched.
        connectTo(OFFICE, OFFICE_BSSID);
        assertTrue("A name block must not reach other names", KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
        assertEquals(Decision.TRUSTED_ACCESS_POINT, KeepADBTrustedNetwork.evaluateCurrent(context));
        // ... and so is a near-miss spelling of the blocked name, which is a different network.
        KeepADBTrustedNetwork.addBssid(context, "ab:ab:ab:ab:ab:01", "home");
        connectTo("home", "ab:ab:ab:ab:ab:01");
        assertTrue("Names are exact; 'home' is not 'Home'", KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));

        KeepADBNetworkBlocklist.unblockSsid(context, HOME);
        connectTo(HOME, HOME_BSSID);
        assertTrue("The stored trust applies again after the explicit unblock",
                KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
    }

    @Test
    public void aBlockOnOneAccessPointLeavesItsNeighbourAndOtherNetworksTrusted() {
        KeepADBTrustedNetwork.addBssid(context, HOME_BSSID, HOME);
        KeepADBTrustedNetwork.addBssid(context, HOME_SIBLING_BSSID, HOME);
        KeepADBTrustedNetwork.addBssid(context, OFFICE_BSSID, OFFICE);
        KeepADBNetworkBlocklist.blockBssid(context, HOME_BSSID);

        connectTo(HOME, HOME_BSSID);
        assertFalse(KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
        // The same name on the sibling access point is a different, still trusted entry.
        connectTo(HOME, HOME_SIBLING_BSSID);
        assertTrue("An access point block is not a name block", KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
        connectTo(OFFICE, OFFICE_BSSID);
        assertTrue(KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
    }

    // --- Trust never lifts a block ---------------------------------------------------------------

    @Test
    public void addingTrustInAnyWayNeverLiftsABlock() {
        connectTo(HOME, HOME_BSSID);
        KeepADBNetworkBlocklist.blockBssid(context, HOME_BSSID);
        assertFalse(KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));

        // Every route that can add trust, one after the other, each followed by the decision.
        KeepADBTrustedNetwork.addBssid(context, HOME_BSSID, HOME);
        assertFalse("addBssid must not lift an access point block",
                KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
        KeepADBTrustedNetwork.setTrustByNameEnabled(context, true);
        KeepADBTrustedNetwork.setSsidMatchingEnabled(context, true);
        KeepADBTrustedNetwork.addSsid(context, HOME);
        assertFalse("A name rule must not lift an access point block",
                KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        assertFalse("The legacy open policy must not lift a block",
                KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
        assertTrue("The block itself must still be there",
                KeepADBNetworkBlocklist.isBssidBlocked(context, HOME_BSSID));

        // Only the explicit unblock lifts it.
        KeepADBNetworkBlocklist.unblockBssid(context, HOME_BSSID);
        assertTrue(KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
    }

    // --- Names derived from trusted access points (F5) ------------------------------------------

    @Test
    public void theComfortSwitchTrustsSiblingsOfATrustedAccessPointOnlyWhileItIsOn() {
        KeepADBTrustedNetwork.addBssid(context, HOME_BSSID, HOME);
        connectTo(HOME, HOME_SIBLING_BSSID);

        assertFalse("Off by default: an unlisted access point is only unknown",
                KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
        assertEquals(Decision.UNKNOWN_NETWORK, KeepADBTrustedNetwork.evaluateCurrent(context));

        KeepADBTrustedNetwork.setTrustByNameEnabled(context, true);
        assertTrue(KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
        assertEquals(Decision.TRUSTED_NAME, KeepADBTrustedNetwork.evaluateCurrent(context));
        assertEquals(KeepADBTrustedNetwork.ProtectionLevel.BALANCED,
                KeepADBTrustedNetwork.getProtectionLevel(context));

        KeepADBTrustedNetwork.setTrustByNameEnabled(context, false);
        assertFalse("Turning the switch off revokes what it allowed",
                KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
    }

    @Test
    public void derivedNamesAreExactAndComeOnlyFromTrustedAccessPoints() {
        KeepADBTrustedNetwork.setTrustByNameEnabled(context, true);
        // Nothing is trusted yet, so no name is: the switch widens trust, it never creates it.
        connectTo(HOME, HOME_SIBLING_BSSID);
        assertFalse(KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));

        KeepADBTrustedNetwork.addBssid(context, HOME_BSSID, HOME);
        for (String near : new String[] {"home", "HOME", "Home ", " Home", "Hom", "Home2", "Homes"}) {
            connectTo(near, HOME_SIBLING_BSSID);
            assertFalse("Must not trust the different name '" + near + "'",
                    KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
        }
        connectTo(HOME, HOME_SIBLING_BSSID);
        assertTrue(KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));

        // Removing the only trusted access point of that name removes the derived name with it:
        // there is no second list that would keep it alive.
        KeepADBTrustedNetwork.remove(context, KeepADBTrustedNetwork.getEntries(context).get(0).id);
        assertFalse(KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
    }

    @Test
    public void anEntryTrustedWithoutAReadableNameContributesNoNameAndTheOwnNameNeverCounts() {
        KeepADBTrustedNetwork.setTrustByNameEnabled(context, true);
        // Trusted while the SSID was unreadable: the label is the BSSID itself.
        KeepADBTrustedNetwork.Entry bare =
                KeepADBTrustedNetwork.addBssid(context, HOME_BSSID, HOME_BSSID);
        assertNull(bare.ssid());
        connectTo(HOME_BSSID, HOME_SIBLING_BSSID);
        assertFalse("A BSSID label is not a Wi-Fi name",
                KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
        connectTo(HOME, HOME_SIBLING_BSSID);
        assertFalse(KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));

        // The own name is display only (#714) and must never become a trusted network name.
        KeepADBTrustedNetwork.Entry named =
                KeepADBTrustedNetwork.addBssid(context, OFFICE_BSSID, OFFICE);
        KeepADBTrustedNetwork.setCustomName(context, named.id, "Garage");
        connectTo("Garage", "dd:dd:dd:dd:dd:01");
        assertFalse(KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
        connectTo(OFFICE, "dd:dd:dd:dd:dd:02");
        assertTrue("The real name still counts", KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
        assertEquals(OFFICE, KeepADBTrustedNetwork.getEntries(context).get(1).ssid());
    }

    @Test
    public void aBlockedAccessPointDoesNotLendItsNameToItsSiblings() {
        KeepADBTrustedNetwork.setTrustByNameEnabled(context, true);
        KeepADBTrustedNetwork.addBssid(context, HOME_BSSID, HOME);
        connectTo(HOME, HOME_SIBLING_BSSID);
        assertTrue("Precondition: the sibling is trusted through the name",
                KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));

        // The user blocks the access point they trusted: its trust, and what derives from it, ends.
        KeepADBNetworkBlocklist.blockBssid(context, HOME_BSSID);
        assertFalse(KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
        assertEquals(Decision.UNKNOWN_NETWORK, KeepADBTrustedNetwork.evaluateCurrent(context));

        // The other side: a second, unblocked trusted access point of the same name still lends it.
        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:03", HOME);
        assertTrue(KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
        assertEquals(Decision.TRUSTED_NAME, KeepADBTrustedNetwork.evaluateCurrent(context));

        // And lifting the block gives the name back to its owner.
        KeepADBNetworkBlocklist.unblockBssid(context, HOME_BSSID);
        connectTo(HOME, HOME_BSSID);
        assertEquals(Decision.TRUSTED_ACCESS_POINT, KeepADBTrustedNetwork.evaluateCurrent(context));
    }

    // --- The legacy settings stay in force exactly as stored -------------------------------------

    @Test
    public void theLegacyNameAllowlistStaysInForceWithoutAnyTrustedAccessPoint() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBTrustedNetwork.setSsidMatchingEnabled(context, true);
        KeepADBTrustedNetwork.addSsid(context, "Mesh");

        connectTo("Mesh", "dd:dd:dd:dd:dd:03");
        assertTrue("Granted by name before #760, granted by name after",
                KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
        assertEquals(Decision.TRUSTED_NAME, KeepADBTrustedNetwork.evaluateCurrent(context));
        assertEquals(KeepADBTrustedNetwork.ProtectionLevel.LEGACY_NAME_LIST,
                KeepADBTrustedNetwork.getProtectionLevel(context));
        // Not widened: the names of trusted access points are not trusted merely because the legacy
        // list is on -- that is what the derived switch is for, and it is off.
        KeepADBTrustedNetwork.addBssid(context, HOME_BSSID, HOME);
        connectTo(HOME, HOME_SIBLING_BSSID);
        assertFalse(KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));

        // A block still wins over it.
        KeepADBNetworkBlocklist.blockSsid(context, "Mesh");
        connectTo("Mesh", "dd:dd:dd:dd:dd:03");
        assertFalse(KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
    }

    // --- Legacy "all networks" policy ----------------------------------------------------------

    @Test
    public void theLegacyOpenPolicyTrustsEverythingNotBlockedAndNothingElseChanges() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        assertEquals(KeepADBTrustedNetwork.ProtectionLevel.LEGACY_ALL_WIFI,
                KeepADBTrustedNetwork.getProtectionLevel(context));

        connectTo(CAFE, CAFE_BSSID);
        assertTrue("Unknown network: trusted, as before", KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
        assertEquals(Decision.LEGACY_ALL_WIFI, KeepADBTrustedNetwork.evaluateCurrent(context));
        connectTo(CAFE, KeepADBNetworkIdentity.REDACTED_BSSID);
        assertTrue("Unreadable identity: trusted, as before", KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
        assertEquals(KeepADBTrustedNetwork.BlockReason.NONE, KeepADBTrustedNetwork.getBlockReason(context));

        // Blocks elsewhere change nothing for the networks they do not name ...
        KeepADBNetworkBlocklist.blockBssid(context, HOME_BSSID);
        KeepADBNetworkBlocklist.blockSsid(context, OFFICE);
        connectTo(CAFE, CAFE_BSSID);
        assertTrue("A block must not turn the open policy off for everything",
                KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
        connectTo(HOME, HOME_SIBLING_BSSID);
        assertTrue(KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
        // ... and deny the ones they do.
        connectTo(HOME, HOME_BSSID);
        assertFalse(KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
        connectTo(OFFICE, OFFICE_BSSID);
        assertFalse(KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
        assertEquals(KeepADBTrustedNetwork.BlockReason.UNTRUSTED_NETWORK,
                KeepADBTrustedNetwork.getBlockReason(context));
    }

    /**
     * A known property of the legacy open policy, pinned so that changing it is a decision: it never
     * fails closed, so a network whose identity cannot be read cannot be recognised as blocked and
     * stays trusted, exactly as it was before blocks existed. (Under the allowlist the same network
     * pauses -- see {@link #anUnreadableNetworkPausesUnderTheAllowlistWhateverIsBlockedElsewhere}.)
     */
    @Test
    public void underTheLegacyOpenPolicyAnUnreadableNetworkCannotBeRecognisedAsBlocked() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        KeepADBNetworkBlocklist.blockBssid(context, HOME_BSSID);
        connectTo(HOME, KeepADBNetworkIdentity.REDACTED_BSSID);
        assertTrue(KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
    }

    // --- Unreadable, unknown and the secure default -------------------------------------------------

    @Test
    public void aNewInstallationTrustsNothingUntilTheUserDecides() {
        connectTo(CAFE, CAFE_BSSID);
        assertEquals(Decision.UNKNOWN_NETWORK, KeepADBTrustedNetwork.evaluateCurrent(context));
        assertFalse(KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
        assertEquals(KeepADBTrustedNetwork.BlockReason.UNTRUSTED_NETWORK,
                KeepADBTrustedNetwork.getBlockReason(context));
        // The first trusted access point is the one thing that makes it go.
        KeepADBTrustedNetwork.addBssid(context, CAFE_BSSID, CAFE);
        assertTrue(KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
        connectTo(CAFE, "cc:cc:cc:cc:cc:02");
        assertFalse("Trust is per access point, not per name, by default",
                KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
    }

    @Test
    public void anUnreadableNetworkPausesUnderTheAllowlistWhateverIsBlockedElsewhere() {
        KeepADBTrustedNetwork.addBssid(context, HOME_BSSID, HOME);
        KeepADBTrustedNetwork.setTrustByNameEnabled(context, true);
        KeepADBNetworkBlocklist.blockBssid(context, OFFICE_BSSID);
        KeepADBNetworkBlocklist.blockSsid(context, CAFE);

        for (String bssid : new String[] {KeepADBNetworkIdentity.REDACTED_BSSID,
                KeepADBNetworkIdentity.UNSET_BSSID, null, ""}) {
            connectTo(HOME, bssid);
            assertFalse("Unreadable must pause: " + bssid,
                    KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
            assertEquals(Decision.IDENTITY_UNAVAILABLE, KeepADBTrustedNetwork.evaluateCurrent(context));
            assertEquals(KeepADBTrustedNetwork.BlockReason.IDENTITY_UNAVAILABLE,
                    KeepADBTrustedNetwork.getBlockReason(context));
        }
    }

    @Test
    public void aBlockOnTheNameAlsoHoldsWhenOnlyTheNameCouldBeRead() {
        KeepADBNetworkBlocklist.blockSsid(context, CAFE);
        connectTo(CAFE, KeepADBNetworkIdentity.REDACTED_BSSID);

        // "Never" must hold whenever there is evidence of the network: this is blocked, not merely
        // unreadable, which matters because only a blocked network is exempt from every prompt.
        assertEquals(Decision.BLOCKED_NAME, KeepADBTrustedNetwork.evaluateCurrent(context));
        assertFalse(KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));

        // A readable name that is not blocked, with the same masked address, stays plainly unreadable.
        connectTo(HOME, KeepADBNetworkIdentity.REDACTED_BSSID);
        assertEquals(Decision.IDENTITY_UNAVAILABLE, KeepADBTrustedNetwork.evaluateCurrent(context));
    }

    @Test
    public void theBlockedAccessPointIsMatchedOnItsAddressNotOnWhateverTheNameReads() {
        KeepADBNetworkBlocklist.blockBssid(context, CAFE_BSSID);
        KeepADBTrustedNetwork.addBssid(context, HOME_BSSID, HOME);

        // Same address, any name (a renamed access point stays blocked) ...
        for (String ssid : new String[] {CAFE, HOME, "Renamed", ""}) {
            connectTo(ssid, CAFE_BSSID.toUpperCase(java.util.Locale.ROOT));
            assertEquals("Blocked by address under the name '" + ssid + "'",
                    Decision.BLOCKED_ACCESS_POINT, KeepADBTrustedNetwork.evaluateCurrent(context));
        }
        // ... and the trusted neighbour with the same name as the blocked one is not collateral.
        KeepADBTrustedNetwork.addBssid(context, "cc:cc:cc:cc:cc:09", CAFE);
        connectTo(CAFE, "cc:cc:cc:cc:cc:09");
        assertTrue(KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
    }

    @Test
    public void everyDecisionKindIsReachableAndOnlyThreeAllowAnAutomaticEnable() {
        KeepADBTrustedNetwork.addBssid(context, HOME_BSSID, HOME);
        KeepADBTrustedNetwork.setTrustByNameEnabled(context, true);
        KeepADBNetworkBlocklist.blockBssid(context, OFFICE_BSSID);
        KeepADBNetworkBlocklist.blockSsid(context, CAFE);

        assertDecision(Decision.TRUSTED_ACCESS_POINT, true, HOME, HOME_BSSID);
        assertDecision(Decision.TRUSTED_NAME, true, HOME, HOME_SIBLING_BSSID);
        assertDecision(Decision.BLOCKED_ACCESS_POINT, false, OFFICE, OFFICE_BSSID);
        assertDecision(Decision.BLOCKED_NAME, false, CAFE, CAFE_BSSID);
        assertDecision(Decision.UNKNOWN_NETWORK, false, "Elsewhere", "ff:ff:ff:ff:ff:01");
        assertDecision(Decision.IDENTITY_UNAVAILABLE, false, HOME, KeepADBNetworkIdentity.REDACTED_BSSID);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        assertDecision(Decision.LEGACY_ALL_WIFI, true, "Elsewhere", "ff:ff:ff:ff:ff:01");
        int allowing = 0;
        for (Decision decision : Decision.values()) {
            if (decision.allowsAutomaticEnable) allowing++;
        }
        assertEquals("Only the three trusting outcomes may allow an automatic enable", 3, allowing);
        for (Decision decision : Decision.values()) {
            assertTrue("A blocked outcome never allows",
                    !decision.isBlocked() || !decision.allowsAutomaticEnable);
        }
    }

    // --- Pure evaluation seam ------------------------------------------------------------------

    @Test
    public void evaluateAgreesWithEvaluateCurrentAndTheTestSeamIgnoresThePolicy() {
        KeepADBTrustedNetwork.addBssid(context, HOME_BSSID, HOME);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        KeepADBNetworkIdentity unknown = new KeepADBNetworkIdentity("\"Elsewhere\"", "ff:ff:ff:ff:ff:01");
        connectTo("Elsewhere", "ff:ff:ff:ff:ff:01");

        assertEquals(KeepADBTrustedNetwork.evaluateCurrent(context),
                KeepADBTrustedNetwork.evaluate(context, unknown));
        assertFalse("The list seam answers the lists' question, independent of the policy default",
                KeepADBTrustedNetwork.isTrustedForTesting(context, unknown));
        KeepADBNetworkIdentity home = new KeepADBNetworkIdentity("\"Home\"", HOME_BSSID);
        assertTrue(KeepADBTrustedNetwork.isTrustedForTesting(context, home));
        KeepADBNetworkBlocklist.blockBssid(context, HOME_BSSID);
        assertFalse("The seam applies blocks too", KeepADBTrustedNetwork.isTrustedForTesting(context, home));
    }

    private void assertDecision(Decision expected, boolean allowed, String ssid, String bssid) {
        connectTo(ssid, bssid);
        assertEquals(expected, KeepADBTrustedNetwork.evaluateCurrent(context));
        assertEquals(allowed, KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
    }

    /** One cell of the matrix, with its own oracle that does not share code with the model. */
    private final class Scenario {
        final boolean legacyOpen;
        final boolean apListed;
        final boolean comfort;
        final boolean legacyNames;
        final boolean onSibling;

        Scenario(boolean legacyOpen, boolean apListed, boolean comfort, boolean legacyNames,
                 boolean onSibling) {
            this.legacyOpen = legacyOpen;
            this.apListed = apListed;
            this.comfort = comfort;
            this.legacyNames = legacyNames;
            this.onSibling = onSibling;
        }

        String connectedBssid() {
            return onSibling ? HOME_SIBLING_BSSID : HOME_BSSID;
        }

        void apply() {
            KeepADBTrustedNetwork.setMode(context, legacyOpen
                    ? KeepADBTrustedNetwork.MODE_ALL_WIFI : KeepADBTrustedNetwork.MODE_ALLOWLIST);
            if (apListed) KeepADBTrustedNetwork.addBssid(context, HOME_BSSID, HOME);
            KeepADBTrustedNetwork.setTrustByNameEnabled(context, comfort);
            if (legacyNames) {
                KeepADBTrustedNetwork.setSsidMatchingEnabled(context, true);
                KeepADBTrustedNetwork.addSsid(context, HOME);
            }
            connectTo(HOME, connectedBssid());
        }

        /** What the rules say without any block, stated directly rather than via the model. */
        boolean expectedWithoutBlock() {
            if (legacyOpen) return true;
            boolean viaAccessPoint = apListed && !onSibling;
            boolean viaDerivedName = comfort && apListed;
            boolean viaLegacyName = legacyNames;
            return viaAccessPoint || viaDerivedName || viaLegacyName;
        }

        @Override
        public String toString() {
            return "legacyOpen=" + legacyOpen + " apListed=" + apListed + " comfort=" + comfort
                    + " legacyNames=" + legacyNames + " onSibling=" + onSibling;
        }
    }

    private void connectTo(String ssid, String bssid) {
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = ShadowWifiInfo.newInstance();
        // The shadow reports the name the way Android does (in quotes); the model strips them.
        shadowOf(info).setSSID(ssid);
        shadowOf(info).setBSSID(bssid);
        shadowOf(wifiManager).setConnectionInfo(info);
    }

    private android.content.SharedPreferences prefs() {
        return context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE);
    }
}
