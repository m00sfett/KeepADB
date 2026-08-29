package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Contract tests for the USB-ADB register path added alongside the existing WLAN-ADB one.
 * Mirrors {@link KeepADBRegisterClientTest}'s fake-server style; the point of these tests is to
 * prove the USB state machine (dedup, inactive marking) never touches the WLAN state fields.
 */
public class KeepADBUsbRegisterClientTest {

    private ServerSocket testServer;
    private int testServerPort;
    private Thread serverThread;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final List<String> recordedRequests = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger responseCode = new AtomicInteger(200);

    @Before
    public void setUp() throws Exception {
        KeepADBRegisterClient.resetForTesting();
        testServer = new ServerSocket(0);
        testServerPort = testServer.getLocalPort();
        running.set(true);
        recordedRequests.clear();
        responseCode.set(200);

        serverThread = new Thread(() -> {
            while (running.get() && !testServer.isClosed()) {
                try (Socket socket = testServer.accept()) {
                    socket.setSoTimeout(2000);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    String line = reader.readLine();
                    if (line != null) {
                        recordedRequests.add(line);
                    }
                    int contentLength = 0;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                        if (line.toLowerCase().startsWith("content-length:")) {
                            contentLength = Integer.parseInt(line.substring(15).trim());
                        }
                    }
                    if (contentLength > 0) {
                        char[] bodyChars = new char[contentLength];
                        int read = reader.read(bodyChars, 0, contentLength);
                        if (read > 0) {
                            recordedRequests.add(new String(bodyChars, 0, read));
                        }
                    }
                    OutputStream out = socket.getOutputStream();
                    int code = responseCode.get();
                    String statusText = (code == 200) ? "OK" : "Error";
                    String response = "HTTP/1.1 " + code + " " + statusText + "\r\nContent-Length: 0\r\n\r\n";
                    out.write(response.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (Exception ignored) {
                }
            }
        });
        serverThread.start();
    }

    @After
    public void tearDown() {
        running.set(false);
        if (testServer != null) {
            try {
                testServer.close();
            } catch (Exception ignored) {
            }
        }
        if (serverThread != null) {
            serverThread.interrupt();
        }
        KeepADBRegisterClient.resetForTesting();
    }

    private String url() {
        return "http://127.0.0.1:" + testServerPort + "/register";
    }

    /** Polls until the condition holds or fails after the timeout; requests round-trip over real sockets. */
    private void waitUntil(java.util.function.BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(20);
        }
        assertTrue("condition not met within " + timeoutMs + "ms", condition.getAsBoolean());
    }

    @Test
    public void testUsbPayloadShapeOnConnectOrProfileSwitch() throws Exception {
        KeepADBRegisterClient.updateUsbEndpointAsyncInternal(true, url(), "abc123",
                7, "Desk", "192.168.1.20", "desk-host", "desk.tailnet.ts.net");

        waitUntil(() -> recordedRequests.size() >= 2, 2000);

        assertTrue(recordedRequests.get(0).startsWith("POST"));
        String body = recordedRequests.get(1);
        assertEquals("{\"method\":\"usb-adb\",\"deviceId\":\"abc123\",\"profileId\":7,"
                + "\"profileName\":\"Desk\",\"ipAddress\":\"192.168.1.20\",\"hostname\":\"desk-host\","
                + "\"tailnetHostname\":\"desk.tailnet.ts.net\",\"active\":true}", body);

        waitUntil(() -> url().equals(KeepADBRegisterClient.getLastRegisteredUsbUrlForTesting()), 2000);
    }

    @Test
    public void testIdempotentNoOpOnRepeatedIdenticalCalls() throws Exception {
        KeepADBRegisterClient.updateUsbEndpointAsyncInternal(true, url(), "abc123",
                7, "Desk", "192.168.1.20", "desk-host", "desk.tailnet.ts.net");
        waitUntil(() -> recordedRequests.size() >= 2, 2000);
        int countAfterFirst = recordedRequests.size();

        // Same profile, same connected state -> must be a synchronous no-op, no extra request.
        KeepADBRegisterClient.updateUsbEndpointAsyncInternal(true, url(), "abc123",
                7, "Desk", "192.168.1.20", "desk-host", "desk.tailnet.ts.net");
        Thread.sleep(300);

        assertEquals(countAfterFirst, recordedRequests.size());
    }

    @Test
    public void testProfileChangeAfterRegistrationSendsNewUpdate() throws Exception {
        KeepADBRegisterClient.updateUsbEndpointAsyncInternal(true, url(), "abc123",
                7, "Desk", "192.168.1.20", "desk-host", "desk.tailnet.ts.net");
        waitUntil(() -> recordedRequests.size() >= 2, 2000);
        int countAfterFirst = recordedRequests.size();

        // Switching to a different profile must not be deduped against the previous one.
        KeepADBRegisterClient.updateUsbEndpointAsyncInternal(true, url(), "abc123",
                9, "Living Room", "192.168.1.30", "lr-host", "lr.tailnet.ts.net");
        waitUntil(() -> recordedRequests.size() >= countAfterFirst + 2, 2000);

        String body = recordedRequests.get(recordedRequests.size() - 1);
        assertTrue(body.contains("\"profileId\":9"));
        assertTrue(body.contains("\"profileName\":\"Living Room\""));
    }

    @Test
    public void testInactiveMarkingOnDisconnectDoesNotTouchWlanState() throws Exception {
        KeepADBRegisterClient.setWlanStateForTesting("http://sentinel.example/register", "10.0.0.5:5555");

        KeepADBRegisterClient.updateUsbEndpointAsyncInternal(true, url(), "abc123",
                7, "Desk", "192.168.1.20", "desk-host", "desk.tailnet.ts.net");
        waitUntil(() -> url().equals(KeepADBRegisterClient.getLastRegisteredUsbUrlForTesting()), 2000);

        KeepADBRegisterClient.markUsbInactiveAsyncInternal(true, url(), "abc123");
        waitUntil(() -> KeepADBRegisterClient.getLastRegisteredUsbUrlForTesting() == null, 2000);

        // The disconnect POST carries active:false for the last known profile.
        String lastBody = recordedRequests.get(recordedRequests.size() - 1);
        assertTrue(lastBody.contains("\"active\":false"));
        assertTrue(lastBody.contains("\"profileId\":7"));

        // WLAN state must be completely untouched by the USB lifecycle.
        assertEquals("http://sentinel.example/register", KeepADBRegisterClient.getLastRegisteredUrlForTesting());
        assertEquals("10.0.0.5:5555", KeepADBRegisterClient.getLastRegisteredEndpointForTesting());
    }

    @Test
    public void testMarkInactiveIsNoOpWhenNothingWasRegistered() throws Exception {
        KeepADBRegisterClient.markUsbInactiveAsyncInternal(true, url(), "abc123");
        Thread.sleep(300);
        assertTrue(recordedRequests.isEmpty());
        assertNull(KeepADBRegisterClient.getLastRegisteredUsbUrlForTesting());
    }

    @Test
    public void testWebhookDisabledMeansNoHttpCallAtAll() throws Exception {
        KeepADBRegisterClient.updateUsbEndpointAsyncInternal(false, url(), "abc123",
                7, "Desk", "192.168.1.20", "desk-host", "desk.tailnet.ts.net");
        Thread.sleep(300);
        assertTrue(recordedRequests.isEmpty());
        assertNull(KeepADBRegisterClient.getLastRegisteredUsbUrlForTesting());
    }

    @Test
    public void testNoConfiguredUrlMeansNoHttpCallAtAll() throws Exception {
        KeepADBRegisterClient.updateUsbEndpointAsyncInternal(true, null, "abc123",
                7, "Desk", "192.168.1.20", "desk-host", "desk.tailnet.ts.net");
        KeepADBRegisterClient.updateUsbEndpointAsyncInternal(true, "   ", "abc123",
                7, "Desk", "192.168.1.20", "desk-host", "desk.tailnet.ts.net");
        Thread.sleep(300);
        assertTrue(recordedRequests.isEmpty());
        assertNull(KeepADBRegisterClient.getLastRegisteredUsbUrlForTesting());
    }

    @Test
    public void testWlanPayloadShapeUnchanged() {
        // Regression: the existing WLAN-ADB payload must stay byte-for-byte unchanged by this change.
        String url = url();
        boolean success = KeepADBRegisterClient.postEndpoint(url, "192.168.1.50:41234");
        assertTrue(success);
        assertFalse(recordedRequests.isEmpty());
        assertTrue(recordedRequests.get(0).startsWith("POST"));
        assertEquals("{\"method\":\"wlan-adb\",\"endpoint\":\"192.168.1.50:41234\"}", recordedRequests.get(1));
    }

    @Test
    public void testOptionalProfileFieldsOmitEmptyStringsSensibly() throws Exception {
        KeepADBRegisterClient.updateUsbEndpointAsyncInternal(true, url(), "abc123",
                3, "Only Name", "", "", "");
        waitUntil(() -> recordedRequests.size() >= 2, 2000);
        String body = recordedRequests.get(1);
        assertEquals("{\"method\":\"usb-adb\",\"deviceId\":\"abc123\",\"profileId\":3,"
                + "\"profileName\":\"Only Name\",\"ipAddress\":\"\",\"hostname\":\"\","
                + "\"tailnetHostname\":\"\",\"active\":true}", body);
    }

    @Test
    public void testProfileFieldsWithControlCharactersProduceValidJson() throws Exception {
        // A pasted newline/tab in a user-entered profile field must not break the JSON payload.
        KeepADBRegisterClient.updateUsbEndpointAsyncInternal(true, url(), "abc123",
                9, "Desk\nRoom", "192.168.1.20", "desk\thost", "desk.tailnet.ts.net");
        waitUntil(() -> recordedRequests.size() >= 2, 2000);
        String body = recordedRequests.get(1);
        assertEquals("{\"method\":\"usb-adb\",\"deviceId\":\"abc123\",\"profileId\":9,"
                + "\"profileName\":\"Desk\\nRoom\",\"ipAddress\":\"192.168.1.20\",\"hostname\":\"desk\\thost\","
                + "\"tailnetHostname\":\"desk.tailnet.ts.net\",\"active\":true}", body);
    }

    @Test
    public void testStateRestoredFromPreferencesAcrossSimulatedProcessDeath() throws Exception {
        FakeContext context = new FakeContext();
        KeepADBRegisterClient.updateUsbEndpointAsyncInternal(context, true, url(), "dev1",
                5, "Office", "192.168.1.10", "host1", "tail1");

        waitUntil(() -> recordedRequests.size() >= 2, 2000);
        waitUntil(() -> url().equals(KeepADBRegisterClient.getLastRegisteredUsbUrlForTesting()), 2000);

        // Verify state is stored in KeepADBPreferences
        assertEquals(url(), KeepADBPreferences.getUsbWebhookLastReportedUrl(context));
        assertEquals(Integer.valueOf(5), KeepADBPreferences.getUsbWebhookLastProfileId(context));
        assertEquals("Office", KeepADBPreferences.getUsbWebhookLastProfileName(context));
        assertEquals("192.168.1.10", KeepADBPreferences.getUsbWebhookLastIpAddress(context));
        assertEquals("host1", KeepADBPreferences.getUsbWebhookLastHostname(context));
        assertEquals("tail1", KeepADBPreferences.getUsbWebhookLastTailnetHostname(context));
        assertTrue(KeepADBPreferences.getUsbWebhookLastReportedAt(context) > 0);

        // Simulate process death: clear in-memory static state in KeepADBRegisterClient
        KeepADBRegisterClient.resetForTesting();
        assertNull(KeepADBRegisterClient.getLastRegisteredUsbUrlForTesting());
        assertFalse(KeepADBRegisterClient.isUsbStateInitializedForTesting());

        // Restore state from SharedPreferences
        KeepADBRegisterClient.ensureUsbStateInitializedLocked(context);
        assertTrue(KeepADBRegisterClient.isUsbStateInitializedForTesting());
        assertEquals(url(), KeepADBRegisterClient.getLastRegisteredUsbUrlForTesting());
        assertEquals(Integer.valueOf(5), KeepADBRegisterClient.getLastRegisteredUsbProfileIdForTesting());
        assertEquals("Office", KeepADBRegisterClient.getLastRegisteredUsbProfileNameForTesting());
        assertEquals("192.168.1.10", KeepADBRegisterClient.getLastRegisteredUsbIpAddressForTesting());
        assertEquals("host1", KeepADBRegisterClient.getLastRegisteredUsbHostnameForTesting());
        assertEquals("tail1", KeepADBRegisterClient.getLastRegisteredUsbTailnetHostnameForTesting());
        assertTrue(KeepADBRegisterClient.getLastRegisteredUsbPayloadForTesting().contains("\"profileName\":\"Office\""));
    }

    @Test
    public void testProcessRestartWithCableStillConnectedIsIdempotent() throws Exception {
        FakeContext context = new FakeContext();
        KeepADBRegisterClient.updateUsbEndpointAsyncInternal(context, true, url(), "dev1",
                5, "Office", "192.168.1.10", "host1", "tail1");
        waitUntil(() -> recordedRequests.size() >= 2, 2000);
        int initialCount = recordedRequests.size();

        // Simulate process death (memory wiped, SharedPreferences preserved)
        KeepADBRegisterClient.resetForTesting();
        assertNull(KeepADBRegisterClient.getLastRegisteredUsbUrlForTesting());

        // App/service restart triggers update with same profile while still connected
        KeepADBRegisterClient.updateUsbEndpointAsyncInternal(context, true, url(), "dev1",
                5, "Office", "192.168.1.10", "host1", "tail1");
        Thread.sleep(300);

        // Idempotency: 0 extra HTTP calls made
        assertEquals(initialCount, recordedRequests.size());
        assertEquals(url(), KeepADBRegisterClient.getLastRegisteredUsbUrlForTesting());
    }

    @Test
    public void testProcessRestartWithCableDisconnectedSendsInactiveCleanup() throws Exception {
        FakeContext context = new FakeContext();
        KeepADBRegisterClient.updateUsbEndpointAsyncInternal(context, true, url(), "dev1",
                5, "Office", "192.168.1.10", "host1", "tail1");
        waitUntil(() -> recordedRequests.size() >= 2, 2000);
        int countBeforeRestart = recordedRequests.size();

        // Simulate process death (memory wiped, SharedPreferences preserved)
        KeepADBRegisterClient.resetForTesting();
        assertNull(KeepADBRegisterClient.getLastRegisteredUsbUrlForTesting());

        // Cable disconnected during restart: markUsbInactiveAsync is called
        KeepADBRegisterClient.markUsbInactiveAsyncInternal(context, true, url(), "dev1");
        waitUntil(() -> recordedRequests.size() >= countBeforeRestart + 2, 2000);

        String inactiveBody = recordedRequests.get(recordedRequests.size() - 1);
        assertTrue(inactiveBody.contains("\"active\":false"));
        assertTrue(inactiveBody.contains("\"profileId\":5"));
        assertTrue(inactiveBody.contains("\"profileName\":\"Office\""));

        // Both in-memory and SharedPreferences states are cleared
        waitUntil(() -> KeepADBRegisterClient.getLastRegisteredUsbUrlForTesting() == null, 2000);
        assertNull(KeepADBPreferences.getUsbWebhookLastReportedUrl(context));
        assertNull(KeepADBPreferences.getUsbWebhookLastReportedPayload(context));
        assertNull(KeepADBPreferences.getUsbWebhookLastProfileId(context));
        assertEquals(0L, KeepADBPreferences.getUsbWebhookLastReportedAt(context));
    }

    @Test
    public void testProcessRestartWithCableDisconnectedAndNothingPreviouslyRegisteredIsNoOp() throws Exception {
        FakeContext context = new FakeContext();
        KeepADBRegisterClient.resetForTesting();

        // Disconnect with no prior registered state -> immediate no-op
        KeepADBRegisterClient.markUsbInactiveAsyncInternal(context, true, url(), "dev1");
        Thread.sleep(300);

        assertTrue(recordedRequests.isEmpty());
        assertNull(KeepADBRegisterClient.getLastRegisteredUsbUrlForTesting());
        assertNull(KeepADBPreferences.getUsbWebhookLastReportedUrl(context));
    }

    @Test
    public void testClearingUsbStateOnInactiveSuccess() throws Exception {
        FakeContext context = new FakeContext();
        KeepADBRegisterClient.updateUsbEndpointAsyncInternal(context, true, url(), "dev1",
                3, "Desk", "192.168.1.20", "desk-host", "desk.tailnet.ts.net");
        waitUntil(() -> url().equals(KeepADBRegisterClient.getLastRegisteredUsbUrlForTesting()), 2000);
        assertEquals("Desk", KeepADBPreferences.getUsbWebhookLastProfileName(context));

        KeepADBRegisterClient.markUsbInactiveAsyncInternal(context, true, url(), "dev1");
        waitUntil(() -> KeepADBRegisterClient.getLastRegisteredUsbUrlForTesting() == null, 2000);

        assertNull(KeepADBRegisterClient.getLastRegisteredUsbPayloadForTesting());
        assertNull(KeepADBRegisterClient.getLastRegisteredUsbProfileIdForTesting());
        assertNull(KeepADBRegisterClient.getLastRegisteredUsbProfileNameForTesting());
        assertNull(KeepADBRegisterClient.getLastRegisteredUsbIpAddressForTesting());
        assertNull(KeepADBRegisterClient.getLastRegisteredUsbHostnameForTesting());
        assertNull(KeepADBRegisterClient.getLastRegisteredUsbTailnetHostnameForTesting());

        assertNull(KeepADBPreferences.getUsbWebhookLastReportedUrl(context));
        assertNull(KeepADBPreferences.getUsbWebhookLastReportedPayload(context));
        assertNull(KeepADBPreferences.getUsbWebhookLastProfileId(context));
        assertNull(KeepADBPreferences.getUsbWebhookLastProfileName(context));
        assertNull(KeepADBPreferences.getUsbWebhookLastIpAddress(context));
        assertNull(KeepADBPreferences.getUsbWebhookLastHostname(context));
        assertNull(KeepADBPreferences.getUsbWebhookLastTailnetHostname(context));
    }

    @Test
    public void testEnsureStateInitializedLockedAlsoInitializesUsb() {
        FakeContext context = new FakeContext();
        KeepADBPreferences.setWebhookLastReportedUrl(context, "http://wlan.example/hook");
        KeepADBPreferences.setWebhookLastReportedEndpoint(context, "192.168.1.100:5555");
        KeepADBPreferences.setUsbWebhookLastReportedState(context, "http://usb.example/hook", "{\"active\":true}",
                8, "Laptop", "192.168.1.60", "lap-host", "lap.tailnet.ts.net");

        KeepADBRegisterClient.resetForTesting();
        assertNull(KeepADBRegisterClient.getLastRegisteredUrlForTesting());
        assertNull(KeepADBRegisterClient.getLastRegisteredUsbUrlForTesting());

        KeepADBRegisterClient.ensureStateInitializedLocked(context);

        assertEquals("http://wlan.example/hook", KeepADBRegisterClient.getLastRegisteredUrlForTesting());
        assertEquals("192.168.1.100:5555", KeepADBRegisterClient.getLastRegisteredEndpointForTesting());
        assertEquals("http://usb.example/hook", KeepADBRegisterClient.getLastRegisteredUsbUrlForTesting());
        assertEquals(Integer.valueOf(8), KeepADBRegisterClient.getLastRegisteredUsbProfileIdForTesting());
        assertEquals("Laptop", KeepADBRegisterClient.getLastRegisteredUsbProfileNameForTesting());
    }

    private static final class FakeContext extends android.content.ContextWrapper {
        private final android.content.SharedPreferences preferences = new MemoryPreferences();

        FakeContext() {
            super(null);
        }

        @Override
        public android.content.Context getApplicationContext() {
            return this;
        }

        @Override
        public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
            return preferences;
        }
    }

    private static final class MemoryPreferences implements android.content.SharedPreferences {
        private final java.util.Map<String, Object> values = new java.util.HashMap<>();

        @Override
        public java.util.Map<String, ?> getAll() {
            return new java.util.HashMap<>(values);
        }

        @Override
        public String getString(String key, String defValue) {
            Object value = values.get(key);
            return value instanceof String ? (String) value : defValue;
        }

        @SuppressWarnings("unchecked")
        @Override
        public java.util.Set<String> getStringSet(String key, java.util.Set<String> defValues) {
            Object value = values.get(key);
            return value instanceof java.util.Set ? java.util.Set.copyOf((java.util.Set<String>) value) : defValues;
        }

        @Override
        public int getInt(String key, int defValue) {
            Object value = values.get(key);
            return value instanceof Integer ? (Integer) value : defValue;
        }

        @Override
        public long getLong(String key, long defValue) {
            Object value = values.get(key);
            return value instanceof Long ? (Long) value : defValue;
        }

        @Override
        public float getFloat(String key, float defValue) {
            Object value = values.get(key);
            return value instanceof Float ? (Float) value : defValue;
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            Object value = values.get(key);
            return value instanceof Boolean ? (Boolean) value : defValue;
        }

        @Override
        public boolean contains(String key) {
            return values.containsKey(key);
        }

        @Override
        public Editor edit() {
            return new MemoryEditor();
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}

        @Override
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}

        private final class MemoryEditor implements Editor {
            private final java.util.Map<String, Object> updates = new java.util.HashMap<>();
            private final java.util.Set<String> removals = new java.util.HashSet<>();
            private boolean clear;

            @Override
            public Editor putString(String key, String value) {
                updates.put(key, value);
                return this;
            }

            @Override
            public Editor putStringSet(String key, java.util.Set<String> values) {
                updates.put(key, values == null ? null : java.util.Set.copyOf(values));
                return this;
            }

            @Override
            public Editor putInt(String key, int value) {
                updates.put(key, value);
                return this;
            }

            @Override
            public Editor putLong(String key, long value) {
                updates.put(key, value);
                return this;
            }

            @Override
            public Editor putFloat(String key, float value) {
                updates.put(key, value);
                return this;
            }

            @Override
            public Editor putBoolean(String key, boolean value) {
                updates.put(key, value);
                return this;
            }

            @Override
            public Editor remove(String key) {
                removals.add(key);
                return this;
            }

            @Override
            public Editor clear() {
                clear = true;
                return this;
            }

            @Override
            public boolean commit() {
                apply();
                return true;
            }

            @Override
            public void apply() {
                if (clear) values.clear();
                for (String k : removals) values.remove(k);
                values.putAll(updates);
            }
        }
    }
}
