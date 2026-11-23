package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

/**
 * Pins the bytes of the webhook register events and what the sender really transmits. The
 * expected strings are literals on purpose: a change of field order, name, value or event id
 * changes what the receiving server sees and must be a deliberate decision of this contract, not a
 * side effect of refactoring the transport code around it (#956 removed a transport type here).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBRegisterWireFormatContractTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private static final String URL = "http://127.0.0.1:50829/register/s20";

    private static final String WLAN_ACTIVE = "{\"contract_version\":2,\"method\":\"wlan-adb\","
            + "\"active\":true,\"endpoint\":\"192.168.1.50:41234\",\"source\":\"keepadb-app\","
            + "\"observed_at\":\"2023-11-14T22:13:20Z\","
            + "\"event_id\":\"keepadb-v2-wlan-adb-fd1dbf686603c790\"}";
    private static final String VPN_ACTIVE = "{\"contract_version\":2,\"method\":\"vpn-adb\","
            + "\"active\":true,\"endpoint\":\"100.101.2.3:41234\",\"source\":\"keepadb-app\","
            + "\"observed_at\":\"2023-11-14T22:13:21Z\","
            + "\"event_id\":\"keepadb-v2-vpn-adb-b8c4fc0ad5ab6c9b\"}";
    private static final String WLAN_INACTIVE = "{\"contract_version\":2,\"method\":\"wlan-adb\","
            + "\"active\":false,\"source\":\"keepadb-app\","
            + "\"observed_at\":\"2023-11-14T22:13:20Z\","
            + "\"event_id\":\"keepadb-v2-wlan-adb-30006c0e3c5dec88\"}";
    private static final String VPN_INACTIVE = "{\"contract_version\":2,\"method\":\"vpn-adb\","
            + "\"active\":false,\"source\":\"keepadb-app\","
            + "\"observed_at\":\"2023-11-14T22:13:21Z\","
            + "\"event_id\":\"keepadb-v2-vpn-adb-0dca6ad0fe861025\"}";

    private final Context context = RuntimeEnvironment.getApplication();
    private final List<String> sentPayloads = Collections.synchronizedList(new ArrayList<>());

    @Before
    public void setUp() {
        clearPreferences();
        KeepADBRegisterClient.resetForTesting();
        KeepADB.resetForTesting();
        sentPayloads.clear();
        KeepADBRegisterClient.setHttpTransport(new RegisterTransport.HttpTransport() {
            @Override
            public TransportResult postJson(String targetUrl, String payload, String logLabel) {
                sentPayloads.add(payload);
                return TransportResult.http(200);
            }

            @Override
            public TransportResult delete(String targetUrl) {
                return TransportResult.http(200);
            }
        });
    }

    @After
    public void tearDown() throws Exception {
        clearPreferences();
        KeepADBRegisterClient.resetForTesting();
        KeepADBVpnTestSupport.resetVerifiedVpn();
        KeepADBRegisterPayload.setServerSupportedMethodsForTesting(null);
        KeepADB.resetForTesting();
    }

    @Test
    public void theActiveEventsAreByteForByteTheDocumentedContract() {
        List<KeepADBRegisterPayload.Event> events = KeepADBRegisterPayload.buildEvents(Arrays.asList(
                new KeepADBRegisterPayload.VerifiedTransport(
                        KeepADBRegisterPayload.Type.WLAN_LAN, "192.168.1.50", 41234,
                        1_700_000_000_000L),
                new KeepADBRegisterPayload.VerifiedTransport(
                        KeepADBRegisterPayload.Type.VPN, "100.101.2.3", 41234,
                        1_700_000_001_000L)));

        assertEquals(2, events.size());
        assertEquals(WLAN_ACTIVE, events.get(0).json);
        assertEquals("wlan-adb", events.get(0).method);
        assertEquals("keepadb-v2-wlan-adb-fd1dbf686603c790", events.get(0).eventId);
        assertEquals(VPN_ACTIVE, events.get(1).json);
        assertEquals("vpn-adb", events.get(1).method);
        assertEquals("keepadb-v2-vpn-adb-b8c4fc0ad5ab6c9b", events.get(1).eventId);
    }

    @Test
    public void theInactiveEventsAreByteForByteTheDocumentedContract() {
        assertEquals(WLAN_INACTIVE, KeepADBRegisterPayload.inactiveEvent(
                KeepADBRegisterPayload.Type.WLAN_LAN, 1_700_000_000_000L).json);
        assertEquals(VPN_INACTIVE, KeepADBRegisterPayload.inactiveEvent(
                KeepADBRegisterPayload.Type.VPN, 1_700_000_001_000L).json);
    }

    @Test
    public void theSchemaVersionTheSourceAndTheSendAllowlistStayAsShipped() {
        assertEquals(2, KeepADBRegisterPayload.CONTRACT_VERSION);
        assertEquals("keepadb-app", KeepADBRegisterPayload.SOURCE);
        assertEquals(Collections.singleton("wlan-adb"),
                KeepADBRegisterPayload.SERVER_SUPPORTED_METHODS);
        List<String> methods = new ArrayList<>();
        for (KeepADBRegisterPayload.Type type : KeepADBRegisterPayload.Type.values()) {
            methods.add(type.method);
        }
        assertEquals("the methods a payload can name", Arrays.asList("wlan-adb", "vpn-adb"), methods);
    }

    /**
     * The transmitted request, not only the builder: with a verified VPN next to the WLAN
     * endpoint exactly one POST leaves the app, the WLAN one, with the documented field order.
     * The VPN candidate is built and held back before it is sent.
     */
    @Test
    public void aVerifiedVpnNextToTheWlanEndpointSendsExactlyTheWlanEventOnTheWire()
            throws Exception {
        KeepADBPreferences.setRegisterWebhookUrl(context, URL);
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        KeepADBVpnTestSupport.deliverVerifiedVpn(context, "192.168.1.50", 41234, "100.101.2.3");

        KeepADBRegisterClient.updateEndpointAsync(context, "192.168.1.50", 41234);
        waitUntil(() -> "192.168.1.50:41234".equals(
                WebhookReportPrefs.getWebhookLastReportedEndpoint(context)), 5_000L);
        Thread.sleep(100L);

        assertEquals("exactly one request leaves the app", 1, sentPayloads.size());
        String wire = sentPayloads.get(0).replaceAll(
                "\"observed_at\":\"[^\"]+\"", "\"observed_at\":\"2023-11-14T22:13:20Z\"");
        assertEquals(WLAN_ACTIVE, wire);
        assertTrue(sentPayloads.get(0), sentPayloads.get(0).matches(
                "\\{\"contract_version\":2,\"method\":\"wlan-adb\",\"active\":true,"
                        + "\"endpoint\":\"192\\.168\\.1\\.50:41234\",\"source\":\"keepadb-app\","
                        + "\"observed_at\":\"\\d{4}-\\d\\d-\\d\\dT[\\d:.]+Z\","
                        + "\"event_id\":\"keepadb-v2-wlan-adb-fd1dbf686603c790\"\\}"));
    }

    /** Counter-check: the VPN event is real, it only waits behind the allowlist. */
    @Test
    public void withAWidenedAllowlistTheVpnEventFollowsWithItsDocumentedBytes() throws Exception {
        KeepADBRegisterPayload.setServerSupportedMethodsForTesting(
                new java.util.HashSet<>(Arrays.asList("wlan-adb", "vpn-adb")));
        KeepADBPreferences.setRegisterWebhookUrl(context, URL);
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        KeepADBVpnTestSupport.deliverVerifiedVpn(context, "192.168.1.50", 41234, "100.101.2.3");

        KeepADBRegisterClient.updateEndpointAsync(context, "192.168.1.50", 41234);
        waitUntil(() -> sentPayloads.size() >= 2, 5_000L);

        String vpn = sentPayloads.get(1).replaceAll(
                "\"observed_at\":\"[^\"]+\"", "\"observed_at\":\"2023-11-14T22:13:21Z\"");
        assertEquals(VPN_ACTIVE, vpn);
    }

    private void clearPreferences() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
    }

    private static void waitUntil(Callable<Boolean> condition, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper();
            if (Boolean.TRUE.equals(condition.call())) return;
            Thread.sleep(10L);
        }
        throw new AssertionError("Condition not met within " + timeoutMs + "ms");
    }
}
