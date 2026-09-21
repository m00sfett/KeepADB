package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * #539: wire-contract tests for the multi-transport register payload. These assert the properties
 * the register server and its existing GET consumers depend on, not merely that a string was
 * produced.
 */
public class KeepADBRegisterPayloadTest {

    private static KeepADBRegisterPayload.VerifiedTransport wlan() {
        return new KeepADBRegisterPayload.VerifiedTransport(
                KeepADBRegisterPayload.Type.WLAN_LAN, "192.168.1.50", 41234, 1_700_000_000_000L);
    }

    private static KeepADBRegisterPayload.VerifiedTransport tailscale() {
        return new KeepADBRegisterPayload.VerifiedTransport(
                KeepADBRegisterPayload.Type.TAILSCALE_VPN, "100.111.111.30", 41234,
                1_700_000_001_000L);
    }

    private static KeepADBRegisterPayload.VerifiedTransport usb() {
        return new KeepADBRegisterPayload.VerifiedTransport(
                KeepADBRegisterPayload.Type.USB, null, 0, 1_700_000_002_000L);
    }

    @Test
    public void everyVerifiedTransportBecomesItsOwnEventWithItsOwnMethod() {
        List<KeepADBRegisterPayload.Event> events =
                KeepADBRegisterPayload.buildEvents(Arrays.asList(wlan(), tailscale(), usb()));

        assertEquals(3, events.size());
        assertEquals("wlan-adb", events.get(0).method);
        assertEquals("tailscale-adb", events.get(1).method);
        assertEquals("usb-adb", events.get(2).method);

        // Separate slots: no event carries a peer transport's endpoint, and none of them asks the
        // server to clear anything. That is what keeps WLAN/Tailscale/USB from erasing each other.
        for (KeepADBRegisterPayload.Event event : events) {
            assertTrue(event.json.contains("\"active\":true"));
            assertFalse(event.json.contains("\"action\""));
        }
        assertTrue(events.get(0).json.contains("\"endpoint\":\"192.168.1.50:41234\""));
        assertTrue(events.get(1).json.contains("\"endpoint\":\"100.111.111.30:41234\""));
        assertFalse(events.get(1).json.contains("192.168.1.50"));
        // USB has no network endpoint of its own; it must not invent one.
        assertFalse(events.get(2).json.contains("\"endpoint\""));
    }

    @Test
    public void tailscaleAndUsbAreHeldBackUntilTheServerAcceptsThem() {
        List<KeepADBRegisterPayload.Event> events =
                KeepADBRegisterPayload.buildEvents(Arrays.asList(wlan(), tailscale(), usb()));

        assertTrue(events.get(0).serverSupported);
        assertFalse(events.get(1).serverSupported);
        assertFalse(events.get(2).serverSupported);
        assertEquals(Collections.singleton("wlan-adb"),
                KeepADBRegisterPayload.SERVER_SUPPORTED_METHODS);
    }

    @Test
    public void onlyTheVerifiedTransportIsReported() {
        List<KeepADBRegisterPayload.Event> events =
                KeepADBRegisterPayload.buildEvents(Collections.singletonList(tailscale()));

        assertEquals(1, events.size());
        assertEquals("tailscale-adb", events.get(0).method);
        // No placeholder or clearing event is fabricated for the transports that are not verified.
        assertFalse(events.get(0).json.contains("wlan-adb"));
    }

    @Test
    public void nothingVerifiedProducesNoEventAtAll() {
        assertTrue(KeepADBRegisterPayload.buildEvents(
                new ArrayList<KeepADBRegisterPayload.VerifiedTransport>()).isEmpty());
        assertTrue(KeepADBRegisterPayload.buildEvents(null).isEmpty());
    }

    @Test
    public void aNetworkTransportWithoutAConfirmedEndpointIsNotPublished() {
        List<KeepADBRegisterPayload.VerifiedTransport> broken = Arrays.asList(
                new KeepADBRegisterPayload.VerifiedTransport(
                        KeepADBRegisterPayload.Type.WLAN_LAN, null, 41234, 1L),
                new KeepADBRegisterPayload.VerifiedTransport(
                        KeepADBRegisterPayload.Type.WLAN_LAN, "192.168.1.50", 0, 1L),
                new KeepADBRegisterPayload.VerifiedTransport(
                        KeepADBRegisterPayload.Type.TAILSCALE_VPN, "   ", 41234, 1L));

        assertTrue(KeepADBRegisterPayload.buildEvents(broken).isEmpty());
    }

    @Test
    public void repeatingAnUnchangedStateRepeatsTheEventIdAndIsThereforeIdempotent() {
        String first = KeepADBRegisterPayload.buildEvents(
                Collections.singletonList(wlan())).get(0).eventId;
        // Same state observed later: the id must not depend on the moment of reporting, otherwise
        // every repeat would be a new event on the server.
        String later = KeepADBRegisterPayload.wlanEvent("192.168.1.50:41234",
                1_700_000_999_000L).eventId;

        assertEquals(first, later);
    }

    @Test
    public void aChangedStateYieldsADifferentEventIdSoItIsNotSwallowedAsADuplicate() {
        String base = KeepADBRegisterPayload.eventIdFor("wlan-adb", "192.168.1.50:41234", true);

        // Both sides of the invariant: an unchanged state must repeat its id (above), and each of
        // the state fields must be able to change it -- otherwise a real change would be dropped
        // by the server's duplicate detection.
        assertNotEquals(base,
                KeepADBRegisterPayload.eventIdFor("wlan-adb", "192.168.1.51:41234", true));
        assertNotEquals(base,
                KeepADBRegisterPayload.eventIdFor("wlan-adb", "192.168.1.50:41235", true));
        assertNotEquals(base,
                KeepADBRegisterPayload.eventIdFor("wlan-adb", "192.168.1.50:41234", false));
        assertNotEquals(base,
                KeepADBRegisterPayload.eventIdFor("tailscale-adb", "192.168.1.50:41234", true));
    }

    @Test
    public void everyEventCarriesTheFieldsContractV2Requires() {
        KeepADBRegisterPayload.Event event =
                KeepADBRegisterPayload.wlanEvent("192.168.1.50:41234", 1_700_000_000_000L);

        // The server rejects a contract-v2 event that lacks observed_at or event_id.
        assertTrue(event.json.contains("\"contract_version\":2"));
        assertTrue(event.json.contains("\"observed_at\":\"2023-11-14T22:13:20Z\""));
        assertTrue(event.json.contains("\"event_id\":\"" + event.eventId + "\""));
        assertTrue(event.json.contains("\"source\":\"keepadb-app\""));
        // Backward compatibility: method and endpoint stay exactly where pre-v2 consumers and the
        // register's legacy projection expect them.
        assertTrue(event.json.contains("\"method\":\"wlan-adb\""));
        assertTrue(event.json.contains("\"endpoint\":\"192.168.1.50:41234\""));
    }

    @Test
    public void deactivationNamesItsOwnSlotAndCarriesNoEndpoint() {
        KeepADBRegisterPayload.Event event = KeepADBRegisterPayload.inactiveEvent(
                KeepADBRegisterPayload.Type.WLAN_LAN, 1_700_000_000_000L);

        // The server refuses a deactivation whose transport slot would have to be inherited, so an
        // explicit method is what keeps this from clearing a peer transport.
        assertTrue(event.json.contains("\"method\":\"wlan-adb\""));
        assertTrue(event.json.contains("\"active\":false"));
        assertFalse(event.json.contains("\"endpoint\""));
    }

    @Test
    public void eventIdIsBoundedAndFreeOfRawEndpointData() {
        String id = KeepADBRegisterPayload.eventIdFor("wlan-adb", "192.168.1.50:41234", true);

        assertTrue(id.startsWith("keepadb-v2-wlan-adb-"));
        assertFalse(id.contains("192.168.1.50"));
        assertTrue(id.length() <= 48);
    }
}
