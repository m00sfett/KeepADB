package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

import de.hohnepeople.keepadb.KeepADBNetworkCardState.Action;
import de.hohnepeople.keepadb.KeepADBNetworkCardState.Background;
import de.hohnepeople.keepadb.KeepADBNetworkCardState.Cause;
import de.hohnepeople.keepadb.KeepADBNetworkCardState.Connection;
import de.hohnepeople.keepadb.KeepADBNetworkCardState.Detection;
import de.hohnepeople.keepadb.KeepADBNetworkCardState.Mode;
import de.hohnepeople.keepadb.KeepADBNetworkCardState.NameMatching;

/**
 * #654/#655: the mapping from card states to texts and colours. Two states must never share a
 * text -- that would make the card say the same thing for different situations -- and each colour
 * rule must keep the colour a pure reinforcement of a text that is always there.
 */
public class KeepADBNetworkCardTextTest {

    private static void assertAllDistinct(String what, int expected, Set<Integer> ids) {
        assertEquals(what + " must map every state to its own text", expected, ids.size());
        assertTrue(what + " must map to real resources", !ids.contains(0));
    }

    @Test
    public void everyStateOfEachKindHasItsOwnText() {
        Set<Integer> modes = new HashSet<>();
        for (Mode mode : Mode.values()) modes.add(KeepADBNetworkCardText.modeOption(mode));
        assertAllDistinct("Mode", Mode.values().length, modes);

        Set<Integer> connections = new HashSet<>();
        for (Connection connection : Connection.values()) {
            connections.add(KeepADBNetworkCardText.connectionLabel(connection));
        }
        assertAllDistinct("Connection", Connection.values().length, connections);

        Set<Integer> causes = new HashSet<>();
        for (Cause cause : Cause.values()) causes.add(KeepADBNetworkCardText.cause(cause));
        assertAllDistinct("Cause", Cause.values().length, causes);

        Set<Integer> backgrounds = new HashSet<>();
        for (Background background : Background.values()) {
            backgrounds.add(KeepADBNetworkCardText.background(background));
        }
        assertAllDistinct("Background", Background.values().length, backgrounds);

        Set<Integer> detections = new HashSet<>();
        for (Detection detection : Detection.values()) {
            detections.add(KeepADBNetworkCardText.detection(detection));
        }
        assertAllDistinct("Detection", Detection.values().length, detections);
    }

    @Test
    public void everyActionHasALabelExceptNone() {
        Set<Integer> labels = new HashSet<>();
        for (Action action : Action.values()) {
            int label = KeepADBNetworkCardText.action(action);
            if (action == Action.NONE) {
                assertEquals("No action, no button", 0, label);
            } else {
                assertNotEquals(action.name(), 0, label);
                labels.add(label);
            }
        }
        assertEquals(Action.values().length - 1, labels.size());
    }

    @Test
    public void allowedIsGreenBlockedIsAmberAndNothingElseIsLoud() {
        for (Mode mode : Mode.values()) {
            assertEquals(R.color.status_ok_green,
                    KeepADBNetworkCardText.connectionColor(Connection.ALLOWED_AP, mode));
            assertEquals(R.color.status_ok_green,
                    KeepADBNetworkCardText.connectionColor(Connection.ALLOWED_NAME, mode));
            assertEquals(R.color.night_muted,
                    KeepADBNetworkCardText.connectionColor(Connection.NO_WIFI, mode));
        }
        // Amber only where Keep-Alive is really held back, neutral where it is not.
        assertEquals(R.color.text_yellow,
                KeepADBNetworkCardText.connectionColor(Connection.NOT_ALLOWED, Mode.ALLOWED_APS));
        assertEquals(R.color.text_yellow,
                KeepADBNetworkCardText.connectionColor(Connection.UNREADABLE,
                        Mode.ALLOWED_APS_AND_NAMES));
        assertEquals(R.color.night_muted,
                KeepADBNetworkCardText.connectionColor(Connection.NOT_ALLOWED, Mode.ALL_WIFI));
        assertEquals(R.color.night_muted,
                KeepADBNetworkCardText.connectionColor(Connection.UNREADABLE, Mode.ALL_WIFI));
    }

    @Test
    public void backgroundColoursKeepTheThreeStateScheme() {
        assertEquals(R.color.status_ok_green,
                KeepADBNetworkCardText.backgroundColor(Background.ALLOWED));
        assertEquals(R.color.text_yellow,
                KeepADBNetworkCardText.backgroundColor(Background.RESTRICTED));
        assertEquals(R.color.night_muted,
                KeepADBNetworkCardText.backgroundColor(Background.NOT_REQUIRED));
    }

    @Test
    public void theMaskedIdentityCauseKeepsTheTextThatNamesAllowAllTheTime() {
        assertEquals("The hidden-identity cause must keep the wording that names the permanent fix (#643)",
                R.string.settings_trusted_network_status_identity_unavailable,
                KeepADBNetworkCardText.cause(Cause.IDENTITY_HIDDEN));
    }
}
