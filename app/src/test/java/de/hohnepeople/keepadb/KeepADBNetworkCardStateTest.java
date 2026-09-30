package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

import de.hohnepeople.keepadb.KeepADBNetworkCardState.Action;
import de.hohnepeople.keepadb.KeepADBNetworkCardState.Background;
import de.hohnepeople.keepadb.KeepADBNetworkCardState.Cause;
import de.hohnepeople.keepadb.KeepADBNetworkCardState.Connection;
import de.hohnepeople.keepadb.KeepADBNetworkCardState.Detection;
import de.hohnepeople.keepadb.KeepADBNetworkCardState.Mode;
import de.hohnepeople.keepadb.KeepADBNetworkCardState.NameMatching;
import de.hohnepeople.keepadb.KeepADBNetworkCardState.Snapshot;

/**
 * #654/#655: the card's state derivation -- mode, effect of the Wi-Fi-name switch, connection,
 * cause, action and the two background facts. Pure JVM: no Android types are involved.
 *
 * <p>Every test names both sides of the rule it guards. The "fail closed" and "names only count
 * while matching is active" rules are two-sided invariants -- a state that is allowed when it
 * must not be is as wrong as one that is blocked when it must not be -- so both directions are
 * asserted explicitly rather than only the harmless one.
 */
public class KeepADBNetworkCardStateTest {

    /** Starts from a readable, connected, permitted, not-yet-listed allowlist situation. */
    private static final class Scenario {
        boolean allowlist = true;
        boolean ssidMatching = false;
        boolean wifiConnected = true;
        boolean identityKnown = true;
        boolean bssidListed = false;
        boolean ssidListed = false;
        boolean fine = true;
        boolean locationOn = true;
        boolean background = false;

        Scenario allWifi() { allowlist = false; return this; }
        Scenario names(boolean on) { ssidMatching = on; return this; }
        Scenario noWifi() { wifiConnected = false; identityKnown = false; return this; }
        Scenario unreadable() { identityKnown = false; return this; }
        Scenario bssidListed() { bssidListed = true; return this; }
        Scenario ssidListed() { ssidListed = true; return this; }
        Scenario noPermission() { fine = false; return this; }
        Scenario locationOff() { locationOn = false; return this; }
        Scenario backgroundGranted() { background = true; return this; }

        Snapshot derive() {
            return KeepADBNetworkCardState.derive(new KeepADBNetworkCardState.Inputs(
                    allowlist, ssidMatching, wifiConnected, identityKnown, bssidListed,
                    ssidListed, fine, locationOn, background));
        }
    }

    // --- mode and effect of the name switch -------------------------------------------------

    @Test
    public void modeFollowsTheTwoStoredSettings() {
        assertEquals(Mode.ALL_WIFI, KeepADBNetworkCardState.mode(false, false));
        assertEquals("Saved names do not change the active mode while all networks are allowed",
                Mode.ALL_WIFI, KeepADBNetworkCardState.mode(false, true));
        assertEquals(Mode.ALLOWED_APS, KeepADBNetworkCardState.mode(true, false));
        assertEquals("The mode names the Wi-Fi names exactly while they are active",
                Mode.ALLOWED_APS_AND_NAMES, KeepADBNetworkCardState.mode(true, true));
    }

    @Test
    public void nameSwitchIsOffActiveOrWithoutEffect() {
        assertEquals(NameMatching.OFF, KeepADBNetworkCardState.nameMatching(true, false));
        assertEquals("Off stays off, also while all networks are allowed",
                NameMatching.OFF, KeepADBNetworkCardState.nameMatching(false, false));
        assertEquals(NameMatching.ACTIVE, KeepADBNetworkCardState.nameMatching(true, true));
        assertEquals("A saved switch is shown as without effect, never as active",
                NameMatching.NO_EFFECT, KeepADBNetworkCardState.nameMatching(false, true));
    }

    // --- the connection and the trust rule it explains --------------------------------------

    @Test
    public void listedBssidIsAllowedAndNotListedIsNot() {
        assertEquals(Connection.ALLOWED_AP, new Scenario().bssidListed().derive().connection);
        assertEquals(Connection.NOT_ALLOWED, new Scenario().derive().connection);
    }

    @Test
    public void aListedNameAllowsOnlyWhileMatchingIsActive() {
        assertEquals("Active matching with a listed name and an unlisted access point",
                Connection.ALLOWED_NAME,
                new Scenario().names(true).ssidListed().derive().connection);
        assertEquals("A listed name is ignored while the switch is off",
                Connection.NOT_ALLOWED,
                new Scenario().ssidListed().derive().connection);
        assertEquals("A saved name without effect in all-networks mode is not 'allowed by name'",
                Connection.NOT_ALLOWED,
                new Scenario().allWifi().names(true).ssidListed().derive().connection);
        assertEquals("An unlisted name does not allow anything",
                Connection.NOT_ALLOWED,
                new Scenario().names(true).derive().connection);
    }

    @Test
    public void theAccessPointIsNamedSeparatelyWhenBothListsMatch() {
        assertEquals("The BSSID entry wins over the name so the access point is named separately",
                Connection.ALLOWED_AP,
                new Scenario().names(true).bssidListed().ssidListed().derive().connection);
    }

    @Test
    public void anUnreadableIdentityIsNeverAllowedWhateverTheListsContain() {
        for (boolean allowlist : new boolean[] {true, false}) {
            for (boolean names : new boolean[] {true, false}) {
                Scenario scenario = new Scenario().names(names).unreadable().bssidListed().ssidListed();
                scenario.allowlist = allowlist;
                Connection connection = scenario.derive().connection;
                assertEquals("allowlist=" + allowlist + " names=" + names,
                        Connection.UNREADABLE, connection);
                assertNotEquals(Connection.ALLOWED_AP, connection);
                assertNotEquals(Connection.ALLOWED_NAME, connection);
            }
        }
    }

    @Test
    public void noWifiIsToldApartFromAnUnreadableNetwork() {
        assertEquals(Connection.NO_WIFI, new Scenario().noWifi().derive().connection);
        assertEquals(Connection.UNREADABLE, new Scenario().unreadable().derive().connection);
    }

    @Test
    public void aKnownIdentityProvesAnAssociationEvenWhenTheTransportFlagIsStale() {
        Scenario scenario = new Scenario().bssidListed();
        scenario.wifiConnected = false;
        assertEquals(Connection.ALLOWED_AP, scenario.derive().connection);
    }

    // --- cause and action --------------------------------------------------------------------

    @Test
    public void noWifiPointsToTheWifiSettings() {
        Snapshot state = new Scenario().noWifi().derive();
        assertEquals(Cause.NO_WIFI, state.cause);
        assertEquals(Action.OPEN_WIFI_SETTINGS, state.action);
    }

    @Test
    public void unreadableInAllowlistModeNamesTheRealReasonAndItsFix() {
        Snapshot permission = new Scenario().unreadable().noPermission().derive();
        assertEquals(Cause.PERMISSION_MISSING, permission.cause);
        assertEquals(Action.GRANT_LOCATION, permission.action);

        Snapshot locationOff = new Scenario().unreadable().locationOff().derive();
        assertEquals(Cause.LOCATION_OFF, locationOff.cause);
        assertEquals(Action.OPEN_LOCATION_SETTINGS, locationOff.action);

        Snapshot hidden = new Scenario().unreadable().derive();
        assertEquals(Cause.IDENTITY_HIDDEN, hidden.cause);
        assertEquals(Action.SET_UP_BACKGROUND, hidden.action);
    }

    @Test
    public void theMissingPermissionOutranksLocationBeingOff() {
        Snapshot state = new Scenario().unreadable().noPermission().locationOff().derive();
        assertEquals(Cause.PERMISSION_MISSING, state.cause);
        assertEquals(Action.GRANT_LOCATION, state.action);
    }

    @Test
    public void aHiddenIdentityOffersNoBackgroundSetupWhenTheGrantIsThereOrNotNeeded() {
        assertEquals("Grant present: nothing left to set up",
                Action.NONE,
                new Scenario().unreadable().backgroundGranted().derive().action);
        assertEquals("All-networks mode never needs the grant",
                Action.NONE,
                new Scenario().allWifi().unreadable().derive().action);
        assertEquals("Allowlist without the grant: set it up",
                Action.SET_UP_BACKGROUND,
                new Scenario().unreadable().derive().action);
    }

    @Test
    public void unreadableWhileAllNetworksAreAllowedPausesNothing() {
        Snapshot permission = new Scenario().allWifi().unreadable().noPermission().derive();
        assertEquals("The text must not claim a paused Keep-Alive", Cause.UNREADABLE_NOT_NEEDED,
                permission.cause);
        assertEquals("The fix is still offered for when the allowlist is used",
                Action.GRANT_LOCATION, permission.action);
        assertEquals(Cause.NOT_ALLOWED, new Scenario().derive().cause);
    }

    @Test
    public void anUnlistedAccessPointOffersToAllowItAndExplainsTheModeEffect() {
        Snapshot blocked = new Scenario().derive();
        assertEquals(Cause.NOT_ALLOWED, blocked.cause);
        assertEquals(Action.ALLOW_ACCESS_POINT, blocked.action);

        Snapshot notRestricted = new Scenario().allWifi().derive();
        assertEquals("Nothing is paused in all-networks mode", Cause.ALL_WIFI, notRestricted.cause);
        assertEquals(Action.ALLOW_ACCESS_POINT, notRestricted.action);
    }

    @Test
    public void anAllowedAccessPointNeedsNoActionButANameOnlyAllowanceCanBeMadeSpecific() {
        Snapshot allowed = new Scenario().bssidListed().derive();
        assertEquals(Cause.ALLOWED, allowed.cause);
        assertEquals(Action.NONE, allowed.action);

        Snapshot byName = new Scenario().names(true).ssidListed().derive();
        assertEquals(Cause.ALLOWED_BY_NAME, byName.cause);
        assertEquals("The access point itself may still be allowed on top of the name",
                Action.ALLOW_ACCESS_POINT, byName.action);

        assertEquals(Cause.ALL_WIFI, new Scenario().allWifi().bssidListed().derive().cause);
    }

    // --- the two background facts stay independent -------------------------------------------

    @Test
    public void backgroundAccessIsJudgedForTheActiveMode() {
        assertEquals(Background.ALLOWED, new Scenario().backgroundGranted().derive().background);
        assertEquals(Background.ALLOWED,
                new Scenario().allWifi().backgroundGranted().derive().background);
        assertEquals(Background.RESTRICTED, new Scenario().derive().background);
        assertEquals(Background.NOT_REQUIRED, new Scenario().allWifi().derive().background);
    }

    @Test
    public void aReadableNetworkNowDoesNotChangeTheBackgroundVerdict() {
        Snapshot readable = new Scenario().bssidListed().derive();
        assertEquals(Detection.READABLE, readable.detection);
        assertEquals("A reading right now proves no reliable background start",
                Background.RESTRICTED, readable.background);
    }

    @Test
    public void aMissingGrantDoesNotClaimACurrentDetectionFailure() {
        Snapshot readableWithoutGrant = new Scenario().derive();
        assertEquals(Background.RESTRICTED, readableWithoutGrant.background);
        assertEquals(Detection.READABLE, readableWithoutGrant.detection);

        Snapshot unreadableWithGrant = new Scenario().unreadable().backgroundGranted().derive();
        assertEquals(Background.ALLOWED, unreadableWithGrant.background);
        assertEquals("A present grant does not hide a current failure",
                Detection.NOT_READABLE, unreadableWithGrant.detection);
    }

    @Test
    public void detectionTellsReadableUnreadableAndNoWifiApart() {
        assertEquals(Detection.READABLE, new Scenario().derive().detection);
        assertEquals(Detection.NOT_READABLE, new Scenario().unreadable().derive().detection);
        assertEquals(Detection.NO_WIFI, new Scenario().noWifi().derive().detection);
    }
}
