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
}
