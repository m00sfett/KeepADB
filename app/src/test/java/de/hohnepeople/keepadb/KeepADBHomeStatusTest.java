package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import de.hohnepeople.keepadb.KeepADBHomeStatus.Action;
import de.hohnepeople.keepadb.KeepADBHomeStatus.Model;
import de.hohnepeople.keepadb.KeepADBHomeStatus.Network;
import de.hohnepeople.keepadb.MainActivity.KeepAliveWaitingDetail;
import org.junit.Test;

/**
 * #957: every row of the table of the issue, as the pure mapping from the resolved app state to
 * the state line, the reason line, the long explanation behind it and the action button.
 */
public class KeepADBHomeStatusTest {

    private static Model of(KeepADB.State state) {
        return KeepADBHomeStatus.of(state, false, null, Network.NONE);
    }

    private static void assertModel(Model model, int state, int reason, int explanation, Action action) {
        assertEquals("state", state, model.stateRes);
        assertEquals("reason", reason, model.reasonRes);
        assertEquals("explanation", explanation, model.explanationRes);
        assertEquals("action", action, model.action);
    }

    @Test
    public void permissionMissingIsSetupMissingWithInstructions() {
        assertModel(of(KeepADB.State.PERMISSION_MISSING), R.string.home_state_setup,
                R.string.home_reason_permission, 0, Action.INSTRUCTIONS);
    }

    @Test
    public void aMissingPermissionWinsOverAPendingToggle() {
        assertModel(KeepADBHomeStatus.of(KeepADB.State.PERMISSION_MISSING, true, null, Network.NONE),
                R.string.home_state_setup, R.string.home_reason_permission, 0, Action.INSTRUCTIONS);
    }

    @Test
    public void aPendingToggleIsSwitchingWithoutReasonOrAction() {
        for (KeepADB.State state : new KeepADB.State[] {KeepADB.State.OFF,
                KeepADB.State.OFF_KEEP_ALIVE_WAITING, KeepADB.State.ENABLED_CONNECTED,
                KeepADB.State.ENABLED_DISCONNECTED}) {
            assertModel(KeepADBHomeStatus.of(state, true, KeepAliveWaitingDetail.BLOCKED_UNTRUSTED_NETWORK,
                            Network.UNDECIDED),
                    R.string.status_pending, 0, 0, Action.NONE);
        }
    }

    @Test
    public void offIsOffWithoutReason() {
        assertModel(of(KeepADB.State.OFF), R.string.home_state_off, 0, 0, Action.NONE);
    }

    @Test
    public void keepAliveWaitingForWifiHasAReasonAndNoAction() {
        assertModel(KeepADBHomeStatus.of(KeepADB.State.OFF_KEEP_ALIVE_WAITING, false,
                        KeepAliveWaitingDetail.WIFI_DISCONNECTED, Network.NONE),
                R.string.home_state_off, R.string.home_reason_waiting,
                R.string.status_off_keep_alive_waiting, Action.NONE);
    }

    @Test
    public void keepAliveWaitingWithoutADetailFallsBackToWaiting() {
        assertModel(KeepADBHomeStatus.of(KeepADB.State.OFF_KEEP_ALIVE_WAITING, false, null, Network.NONE),
                R.string.home_state_off, R.string.home_reason_waiting,
                R.string.status_off_keep_alive_waiting, Action.NONE);
    }

    @Test
    public void pausedOnAnUndecidedWifiLeadsToTheDecision() {
        assertModel(KeepADBHomeStatus.of(KeepADB.State.OFF_KEEP_ALIVE_WAITING, false,
                        KeepAliveWaitingDetail.BLOCKED_UNTRUSTED_NETWORK, Network.UNDECIDED),
                R.string.home_state_off, R.string.home_reason_untrusted,
                R.string.status_off_keep_alive_blocked_untrusted, Action.DECIDE);
    }

    @Test
    public void pausedOnABlockedWifiLeadsToTheNetworksList() {
        assertModel(KeepADBHomeStatus.of(KeepADB.State.OFF_KEEP_ALIVE_WAITING, false,
                        KeepAliveWaitingDetail.BLOCKED_UNTRUSTED_NETWORK, Network.BLOCKED),
                R.string.home_state_off, R.string.home_reason_blocked,
                R.string.status_off_keep_alive_blocked_by_user, Action.NETWORKS);
    }

    @Test
    public void pausedBecauseTheNetworkIsUnreadableOffersFix() {
        assertModel(KeepADBHomeStatus.of(KeepADB.State.OFF_KEEP_ALIVE_WAITING, false,
                        KeepAliveWaitingDetail.BLOCKED_IDENTITY_UNAVAILABLE, Network.NONE),
                R.string.home_state_off, R.string.home_reason_identity,
                R.string.status_off_keep_alive_blocked_identity_unavailable, Action.FIX);
    }

    @Test
    public void pausedBecauseAndroidsDialogWasNotConfirmedOffersRetry() {
        assertModel(KeepADBHomeStatus.of(KeepADB.State.OFF_KEEP_ALIVE_WAITING, false,
                        KeepAliveWaitingDetail.BLOCKED_RECOVERY_BACKOFF, Network.NONE),
                R.string.home_state_off, R.string.home_reason_backoff,
                R.string.status_off_keep_alive_blocked_recovery_backoff, Action.RETRY);
    }

    @Test
    public void onWithoutWifiNamesTheMissingConnection() {
        assertModel(of(KeepADB.State.ENABLED_DISCONNECTED), R.string.home_state_on,
                R.string.home_reason_no_wifi, 0, Action.NONE);
    }

    @Test
    public void onAndConnectedIsJustOn() {
        assertModel(of(KeepADB.State.ENABLED_CONNECTED), R.string.home_state_on, 0, 0, Action.NONE);
    }

    @Test
    public void onAtAnUndecidedWifiSaysKeepAliveStaysOffAndLeadsToTheDecision() {
        for (KeepADB.State state : new KeepADB.State[] {KeepADB.State.ENABLED_CONNECTED,
                KeepADB.State.ENABLED_DISCONNECTED}) {
            assertModel(KeepADBHomeStatus.of(state, false, null, Network.UNDECIDED),
                    R.string.home_state_on, R.string.home_reason_stays_off_untrusted,
                    R.string.status_on_network_untrusted, Action.DECIDE);
        }
    }

    @Test
    public void onAtABlockedWifiSaysKeepAliveStaysOffAndLeadsToTheList() {
        for (KeepADB.State state : new KeepADB.State[] {KeepADB.State.ENABLED_CONNECTED,
                KeepADB.State.ENABLED_DISCONNECTED}) {
            assertModel(KeepADBHomeStatus.of(state, false, null, Network.BLOCKED),
                    R.string.home_state_on, R.string.home_reason_stays_off_blocked,
                    R.string.status_on_network_blocked, Action.NETWORKS);
        }
    }

    @Test
    public void everyActionButHasALabelAndNoneHasNone() {
        for (Action action : Action.values()) {
            assertEquals(action == Action.NONE, action.labelRes == 0);
        }
    }

    @Test
    public void aReasonWithoutAnExplanationIsPlainText() {
        Model noWifi = of(KeepADB.State.ENABLED_DISCONNECTED);
        assertTrue(noWifi.hasReason());
        assertFalse(noWifi.hasExplanation());
        Model paused = KeepADBHomeStatus.of(KeepADB.State.OFF_KEEP_ALIVE_WAITING, false,
                KeepAliveWaitingDetail.BLOCKED_RECOVERY_BACKOFF, Network.NONE);
        assertTrue(paused.hasReason() && paused.hasExplanation());
        assertFalse(of(KeepADB.State.OFF).hasReason());
    }
}
