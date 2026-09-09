package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBRegisterClientTest {

    private ServerSocket testServer;
    private int testServerPort;
    private Thread serverThread;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final List<String> recordedRequests = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger responseCode = new AtomicInteger(200);

    @Before
    public void setUp() throws Exception {
        clearPreferences();
        KeepADBRegisterClient.resetHttpTransport();
        KeepADBRegisterClient.resetForTesting();
        KeepADB.resetForTesting();
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
        clearPreferences();
        KeepADBRegisterClient.resetHttpTransport();
        KeepADBRegisterClient.resetForTesting();
        KeepADB.resetForTesting();
    }

    private void clearPreferences() {
        Context context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test
    public void testPostEndpointSuccess() {
        String url = "http://127.0.0.1:" + testServerPort + "/register";
        boolean success = KeepADBRegisterClient.postEndpoint(url, "192.168.1.50:41234");
        assertTrue(success);
        assertFalse(recordedRequests.isEmpty());
        assertTrue(recordedRequests.get(0).startsWith("POST"));
        assertTrue(recordedRequests.toString().contains("192.168.1.50:41234"));
    }

    @Test
    public void testDeleteEndpointSuccess() {
        String url = "http://127.0.0.1:" + testServerPort + "/register";
        boolean success = KeepADBRegisterClient.deleteEndpoint(url);
        assertTrue(success);
        assertFalse(recordedRequests.isEmpty());
        assertTrue(recordedRequests.get(0).startsWith("DELETE"));
    }

    @Test
    public void testPostEndpointFailureOn500() {
        responseCode.set(500);
        String url = "http://127.0.0.1:" + testServerPort + "/register";
        boolean success = KeepADBRegisterClient.postEndpoint(url, "192.168.1.50:41234");
        assertFalse(success);
    }

    @Test
    public void testWebhookUrlValidation() {
        assertTrue(KeepADBPreferences.isValidWebhookUrl("http://192.168.1.1:8080/hook"));
        assertTrue(KeepADBPreferences.isValidWebhookUrl("https://example.com/api/register"));
        assertFalse(KeepADBPreferences.isValidWebhookUrl("ftp://example.com"));
        assertFalse(KeepADBPreferences.isValidWebhookUrl("invalid-url"));
        assertFalse(KeepADBPreferences.isValidWebhookUrl(null));
        assertFalse(KeepADBPreferences.isValidWebhookUrl(""));
    }

    @Test
    public void testSanitizeUrl() {
        assertEquals("https://example.com:8443",
                KeepADBRegisterClient.sanitizeUrl("https://user:password@example.com:8443/api/register?token=secret#fragment"));
        // #350: logs now apply the same host rule as the UI, so an IPv4 literal is masked here too.
        assertEquals("http://192.168.*.**:8080",
                KeepADBRegisterClient.sanitizeUrl("http://192.168.1.10:8080/hook?secret=12345"));
        // #350: an IPv6 literal used to reach the log intact -- java.net.URI even threw on an
        // un-encoded zone id, so the whole raw string was replaced by a useless placeholder.
        assertEquals("https://[***]:8443",
                KeepADBRegisterClient.sanitizeUrl("https://user:pw@[fe80::1%eth0]:8443/hook?token=secret"));
        assertEquals("null", KeepADBRegisterClient.sanitizeUrl(null));
        assertEquals("[redacted-url]", KeepADBRegisterClient.sanitizeUrl("not-a-url"));
    }

    @Test
    public void testNetworkSecurityConfigPermitsCleartextTraffic() throws Exception {
        java.nio.file.Path directory = java.nio.file.Paths.get("").toAbsolutePath();
        while (directory != null && !java.nio.file.Files.exists(directory.resolve("settings.gradle"))) {
            directory = directory.getParent();
        }
        if (directory == null) {
            throw new IllegalStateException("Could not locate project root");
        }
        String xml = new String(java.nio.file.Files.readAllBytes(directory.resolve("app/src/main/res/xml/network_security_config.xml")), StandardCharsets.UTF_8);
        assertTrue(xml.contains("<base-config cleartextTrafficPermitted=\"true\">"));
    }

    @Test
    public void testFakeSettingsGatewayDefaultSuccess() {
        Context context = ApplicationProvider.getApplicationContext();
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(false);
        assertFalse(gateway.isEnabled(context));

        boolean ok = gateway.write(context, true);
        assertTrue(ok);
        assertTrue(gateway.isEnabled(context));
        assertEquals(1, gateway.writes.size());
        assertTrue(gateway.writes.get(0));
    }

    @Test
    public void testFakeSettingsGatewayWriteFailureInjection() {
        Context context = ApplicationProvider.getApplicationContext();
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(false);
        gateway.setWriteSuccess(false);

        boolean ok = gateway.write(context, true);
        assertFalse(ok);
        assertFalse(gateway.isEnabled(context));
        assertEquals(1, gateway.writes.size());
        assertTrue(gateway.writes.get(0));

        // State remains false on subsequent read
        assertFalse(gateway.isEnabled(context));

        // Test with initially enabled = true attempting to write false
        KeepADBFakeSettingsGateway gatewayEnabled = new KeepADBFakeSettingsGateway(true);
        gatewayEnabled.setWriteSuccess(false);

        boolean okDisable = gatewayEnabled.write(context, false);
        assertFalse(okDisable);
        assertTrue(gatewayEnabled.isEnabled(context));
        assertEquals(1, gatewayEnabled.writes.size());
        assertFalse(gatewayEnabled.writes.get(0));
    }

    @Test
    public void testKeepADBApplyDesiredStateWithFailingGateway() {
        Context context = ApplicationProvider.getApplicationContext();
        org.robolectric.Shadows.shadowOf((android.app.Application) context)
                .grantPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS);

        KeepADBFakeScheduler scheduler = new KeepADBFakeScheduler();
        scheduler.setClockMs(100_000);
        KeepADB.setSchedulerForTesting(scheduler);

        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(false);
        gateway.setWriteSuccess(false);
        KeepADB.setGatewayForTesting(gateway);

        // Apply toggle ON: gateway rejects the write. #309: this used to return true -- the
        // rejected write was reported to the caller as a successful toggle; it now fails.
        boolean applied = KeepADB.setEnabled(context, true, "test");
        assertFalse(applied);
        assertFalse(KeepADB.isEnabled(context));
        assertEquals(1, gateway.writes.size());
        assertTrue(gateway.writes.get(0));
    }

    @Test
    public void testFakeHttpTransportConfigurationAndRecording() {
        KeepADBFakeHttpTransport transport = new KeepADBFakeHttpTransport();
        assertTrue(transport.postJson("http://test.url/post", "{\"key\":\"value\"}", "label"));
        assertTrue(transport.delete("http://test.url/delete"));

        assertEquals(2, transport.getRequestCount());
        assertEquals("POST", transport.recordedRequests.get(0).method);
        assertEquals("http://test.url/post", transport.recordedRequests.get(0).url);
        assertEquals("{\"key\":\"value\"}", transport.recordedRequests.get(0).payload);
        assertEquals("label", transport.recordedRequests.get(0).logLabel);

        assertEquals("DELETE", transport.recordedRequests.get(1).method);
        assertEquals("http://test.url/delete", transport.recordedRequests.get(1).url);
        assertEquals(transport.recordedRequests.get(1), transport.getLastRequest());

        // Test failure injection for POST
        transport.setPostSuccess(false);
        assertFalse(transport.postJson("http://test.url/post2", "{}", "label2"));

        // Test response codes
        transport.setPostResponseCode(500);
        assertEquals(500, transport.getPostResponseCode());
        assertFalse(transport.postJson("http://test.url/post3", "{}", "label3"));

        transport.setPostResponseCode(200);
        assertTrue(transport.postJson("http://test.url/post4", "{}", "label4"));

        // Test failure injection for DELETE
        transport.setDeleteResponseCode(404);
        assertFalse(transport.delete("http://test.url/delete2"));

        // Test callbacks
        AtomicBoolean failureCallbackRan = new AtomicBoolean(false);
        transport.setFailureCallback(() -> failureCallbackRan.set(true));
        transport.setPostSuccess(false);
        transport.postJson("http://test.url/fail", "{}", "fail");
        assertTrue(failureCallbackRan.get());

        List<KeepADBFakeHttpTransport.Request> callbackRequests = new ArrayList<>();
        transport.setRequestCallback(callbackRequests::add);
        transport.setPostSuccess(true);
        transport.postJson("http://test.url/cb", "{\"a\":1}", "cb");
        assertEquals(1, callbackRequests.size());
        assertEquals("http://test.url/cb", callbackRequests.get(0).url);

        // Test simulated latency
        long start = System.currentTimeMillis();
        transport.setSimulatedLatencyMs(30);
        transport.delete("http://test.url/latency");
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed >= 25);

        transport.clearRequests();
        assertEquals(0, transport.getRequestCount());
        assertNull(transport.getLastRequest());
    }

    @Test
    public void testPostEndpointFailureWithFakeTransport() {
        KeepADBFakeHttpTransport transport = new KeepADBFakeHttpTransport();
        transport.setPostSuccess(false);
        KeepADBRegisterClient.setHttpTransport(transport);

        boolean success = KeepADBRegisterClient.postEndpoint("http://test.url/register", "192.168.1.50:41234");
        assertFalse(success);
        assertEquals(1, transport.getRequestCount());
        assertEquals("POST", transport.getLastRequest().method);
    }

    @Test
    public void testDeleteEndpointFailureWithFakeTransport() {
        KeepADBFakeHttpTransport transport = new KeepADBFakeHttpTransport();
        transport.setDeleteSuccess(false);
        KeepADBRegisterClient.setHttpTransport(transport);

        boolean success = KeepADBRegisterClient.deleteEndpoint("http://test.url/register");
        assertFalse(success);
        assertEquals(1, transport.getRequestCount());
        assertEquals("DELETE", transport.getLastRequest().method);
    }

    @Test
    public void testUpdateEndpointAsyncFailureUpdatesStatusAndNotifiesListener() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        KeepADBPreferences.setRegisterWebhookUrl(context, "http://fake.url/register");
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);

        KeepADBFakeHttpTransport transport = new KeepADBFakeHttpTransport();
        transport.setPostSuccess(false);
        KeepADBRegisterClient.setHttpTransport(transport);

        AtomicBoolean listenerNotified = new AtomicBoolean(false);
        KeepADBRegisterClient.setRegisterStateListener(() -> listenerNotified.set(true));

        KeepADBRegisterClient.updateEndpointAsync(context, "192.168.1.50", 41234);

        waitUntil(() -> KeepADBPreferences.WEBHOOK_STATUS_FAILED.equals(
                KeepADBPreferences.getWebhookLastReportStatus(context)), 3000);

        waitUntil(() -> {
            ShadowLooper.idleMainLooper();
            return listenerNotified.get();
        }, 3000);

        assertEquals(KeepADBPreferences.WEBHOOK_STATUS_FAILED,
                KeepADBPreferences.getWebhookLastReportStatus(context));
        assertTrue(listenerNotified.get());
        assertEquals(1, transport.getRequestCount());
    }

    @Test
    public void testMarkUnavailableAsyncFailureUpdatesStatusAndNotifiesListener() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        KeepADBPreferences.setRegisterWebhookUrl(context, "http://fake.url/register");
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        KeepADBPreferences.setWebhookLastReportedUrl(context, "http://fake.url/register");
        KeepADBPreferences.setWebhookLastReportedEndpoint(context, "192.168.1.50:41234");
        KeepADBPreferences.setWebhookLastReportStatus(context, KeepADBPreferences.WEBHOOK_STATUS_SUCCESS);

        KeepADBFakeHttpTransport transport = new KeepADBFakeHttpTransport();
        transport.setDeleteSuccess(false);
        KeepADBRegisterClient.setHttpTransport(transport);

        AtomicBoolean listenerNotified = new AtomicBoolean(false);
        KeepADBRegisterClient.setRegisterStateListener(() -> listenerNotified.set(true));

        KeepADBRegisterClient.markUnavailableAsync(context);

        waitUntil(() -> KeepADBPreferences.WEBHOOK_STATUS_FAILED.equals(
                KeepADBPreferences.getWebhookLastReportStatus(context)), 3000);

        waitUntil(() -> {
            ShadowLooper.idleMainLooper();
            return listenerNotified.get();
        }, 3000);

        assertEquals(KeepADBPreferences.WEBHOOK_STATUS_FAILED,
                KeepADBPreferences.getWebhookLastReportStatus(context));
        assertTrue(listenerNotified.get());
        assertEquals(1, transport.getRequestCount());
    }

    @Test
    public void testUsbEndpointFailureDoesNotUpdateReportedState() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        KeepADBFakeHttpTransport transport = new KeepADBFakeHttpTransport();
        transport.setPostSuccess(false);
        KeepADBRegisterClient.setHttpTransport(transport);

        KeepADBRegisterClient.updateUsbEndpointAsyncInternal(context, true, "http://fake.url/register",
                "device123", 1, "Desk", "192.168.1.20", "host", "tailhost");

        waitUntil(() -> transport.getRequestCount() >= 1, 3000);
        Thread.sleep(100);

        assertNull(KeepADBRegisterClient.getLastRegisteredUsbUrlForTesting());
        assertNull(KeepADBPreferences.getUsbWebhookLastReportedUrl(context));
    }

    @Test
    public void testUsbEndpointFailureUpdatesStatusAndNotifiesListener() throws Exception {
        // #317: the USB path used to abort silently on a failed POST -- no persisted status and
        // no listener callback -- while the WLAN path recorded both.
        Context context = ApplicationProvider.getApplicationContext();
        KeepADBFakeHttpTransport transport = new KeepADBFakeHttpTransport();
        transport.setPostSuccess(false);
        KeepADBRegisterClient.setHttpTransport(transport);

        AtomicBoolean listenerNotified = new AtomicBoolean(false);
        KeepADBRegisterClient.setRegisterStateListener(() -> listenerNotified.set(true));

        KeepADBRegisterClient.updateUsbEndpointAsyncInternal(context, true, "http://fake.url/register",
                "device123", 1, "Desk", "192.168.1.20", "host", "tailhost");

        waitUntil(() -> KeepADBPreferences.WEBHOOK_STATUS_FAILED.equals(
                KeepADBPreferences.getUsbWebhookLastReportStatus(context)), 3000);
        waitUntil(() -> {
            ShadowLooper.idleMainLooper();
            return listenerNotified.get();
        }, 3000);

        assertTrue(listenerNotified.get());
        assertNull(KeepADBPreferences.getUsbWebhookLastReportedUrl(context));
        assertEquals(1, transport.getRequestCount());
    }

    @Test
    public void testUsbDeactivationFailureUpdatesStatusAndNotifiesListener() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        KeepADBFakeHttpTransport transport = new KeepADBFakeHttpTransport();
        KeepADBRegisterClient.setHttpTransport(transport);

        KeepADBRegisterClient.updateUsbEndpointAsyncInternal(context, true, "http://fake.url/register",
                "device123", 1, "Desk", "192.168.1.20", "host", "tailhost");
        waitUntil(() -> "http://fake.url/register".equals(
                KeepADBRegisterClient.getLastRegisteredUsbUrlForTesting()), 3000);

        transport.setPostSuccess(false);
        AtomicBoolean listenerNotified = new AtomicBoolean(false);
        KeepADBRegisterClient.setRegisterStateListener(() -> listenerNotified.set(true));

        KeepADBRegisterClient.markUsbInactiveAsyncInternal(context, true, "http://fake.url/register",
                "device123");

        waitUntil(() -> KeepADBPreferences.WEBHOOK_STATUS_FAILED.equals(
                KeepADBPreferences.getUsbWebhookLastReportStatus(context)), 3000);
        waitUntil(() -> {
            ShadowLooper.idleMainLooper();
            return listenerNotified.get();
        }, 3000);

        assertTrue(listenerNotified.get());
        // The registration is still known, so the cleanup remains possible.
        assertEquals("http://fake.url/register", KeepADBRegisterClient.getLastRegisteredUsbUrlForTesting());
    }

    @Test
    public void testInFlightUpdateSupersededByNewerUpdate() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        KeepADBPreferences.setRegisterWebhookUrl(context, "http://fake.url/register");
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);

        KeepADBFakeHttpTransport transport = new KeepADBFakeHttpTransport();
        KeepADBRegisterClient.setHttpTransport(transport);

        CountDownLatch firstRequestStarted = new CountDownLatch(1);
        CountDownLatch canFinishFirstRequest = new CountDownLatch(1);

        transport.setRequestCallback(req -> {
            if (req.payload != null && req.payload.contains("41234")) {
                firstRequestStarted.countDown();
                try {
                    canFinishFirstRequest.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
            }
        });

        // Trigger in-flight POST with port 41234
        KeepADBRegisterClient.updateEndpointAsync(context, "192.168.1.50", 41234);
        assertTrue(firstRequestStarted.await(3, TimeUnit.SECONDS));

        // While first POST is in-flight, trigger newer update with port 41235
        KeepADBRegisterClient.updateEndpointAsync(context, "192.168.1.50", 41235);

        // Allow first in-flight POST to finish
        canFinishFirstRequest.countDown();

        // Wait until second update completes
        waitUntil(() -> "192.168.1.50:41235".equals(
                KeepADBPreferences.getWebhookLastReportedEndpoint(context)), 3000);

        ShadowLooper.idleMainLooper();

        // Verify the final registered endpoint is the newer one (41235), not the stale one (41234)
        assertEquals("192.168.1.50:41235", KeepADBRegisterClient.getLastRegisteredEndpointForTesting());
        assertEquals("192.168.1.50:41235", KeepADBPreferences.getWebhookLastReportedEndpoint(context));
    }

    @Test
    public void testInFlightUsbRegistrationSupersededByNewerProfile() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        KeepADBFakeHttpTransport transport = new KeepADBFakeHttpTransport();
        KeepADBRegisterClient.setHttpTransport(transport);

        CountDownLatch firstRequestStarted = new CountDownLatch(1);
        CountDownLatch canFinishFirstRequest = new CountDownLatch(1);

        transport.setRequestCallback(req -> {
            if (req.payload != null && req.payload.contains("Desk")) {
                firstRequestStarted.countDown();
                try {
                    canFinishFirstRequest.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
            }
        });

        // Start Desk registration (in-flight)
        KeepADBRegisterClient.updateUsbEndpointAsyncInternal(context, true, "http://fake.url/register",
                "device123", 1, "Desk", "192.168.1.20", "host", "tailhost");
        assertTrue(firstRequestStarted.await(3, TimeUnit.SECONDS));

        // Supersede with Laptop registration
        KeepADBRegisterClient.updateUsbEndpointAsyncInternal(context, true, "http://fake.url/register",
                "device123", 2, "Laptop", "192.168.1.30", "lap-host", "lap-tailhost");

        // Allow Desk to finish
        canFinishFirstRequest.countDown();

        // Wait until Laptop state is recorded
        waitUntil(() -> Integer.valueOf(2).equals(KeepADBRegisterClient.getLastRegisteredUsbProfileIdForTesting()), 3000);
        waitUntil(() -> "Laptop".equals(KeepADBPreferences.getUsbWebhookLastProfileName(context)), 3000);

        assertEquals("Laptop", KeepADBRegisterClient.getLastRegisteredUsbProfileNameForTesting());
        assertEquals("Laptop", KeepADBPreferences.getUsbWebhookLastProfileName(context));
    }

    @Test
    public void testInFlightUpdateSupersededByDisconnect() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        String targetUrl = "http://fake.url/register/" + testServerPort;
        KeepADBPreferences.setRegisterWebhookUrl(context, targetUrl);
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);

        KeepADBFakeHttpTransport transport = new KeepADBFakeHttpTransport();
        KeepADBRegisterClient.setHttpTransport(transport);

        CountDownLatch firstRequestStarted = new CountDownLatch(1);
        CountDownLatch canFinishFirstRequest = new CountDownLatch(1);
        CountDownLatch deleteRequestStarted = new CountDownLatch(1);
        CountDownLatch deregistrationNotified = new CountDownLatch(1);

        KeepADBRegisterClient.setRegisterStateListener(() -> {
            if (KeepADBPreferences.WEBHOOK_STATUS_DEREGISTERED.equals(
                    KeepADBPreferences.getWebhookLastReportStatus(context))) {
                deregistrationNotified.countDown();
            }
        });

        transport.setRequestCallback(req -> {
            if ("POST".equals(req.method) && req.payload != null && req.payload.contains("41234")) {
                firstRequestStarted.countDown();
                try {
                    canFinishFirstRequest.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
            } else if ("DELETE".equals(req.method)
                    && targetUrl.equals(req.url)) {
                deleteRequestStarted.countDown();
            }
        });

        try {
            // Trigger in-flight POST with port 41234
            KeepADBRegisterClient.updateEndpointAsync(context, "192.168.1.50", 41234);
            assertTrue(firstRequestStarted.await(3, TimeUnit.SECONDS));

            // While first POST is still in-flight, disconnect/turn off wireless debugging
            KeepADBRegisterClient.markUnavailableAsync(context);

            // Allow first in-flight POST to finish
            canFinishFirstRequest.countDown();

            // Synchronize on the operation itself, then on its terminal state. A request is
            // recorded before its transport call returns, so request count alone is not a
            // completion signal.
            assertTrue(deleteRequestStarted.await(3, TimeUnit.SECONDS));
            awaitMainLooperSignal(deregistrationNotified, 3000);

            // Verify the in-flight update did not revive the endpoint after disconnect
            assertNull(KeepADBRegisterClient.getLastRegisteredEndpointForTesting());
            assertNull(KeepADBPreferences.getWebhookLastReportedEndpoint(context));
            assertNull(KeepADBRegisterClient.getLastRegisteredUrlForTesting());
            assertNull(KeepADBPreferences.getWebhookLastReportedUrl(context));
            List<KeepADBFakeHttpTransport.Request> requests;
            synchronized (transport.recordedRequests) {
                requests = new ArrayList<>(transport.recordedRequests);
            }
            assertEquals(2, requests.size());
            KeepADBFakeHttpTransport.Request updateRequest = null;
            KeepADBFakeHttpTransport.Request disconnectRequest = null;
            for (KeepADBFakeHttpTransport.Request request : requests) {
                if ("POST".equals(request.method) && request.payload != null
                        && request.payload.contains("41234")) {
                    updateRequest = request;
                } else if ("DELETE".equals(request.method)
                        && targetUrl.equals(request.url)) {
                    disconnectRequest = request;
                }
            }
            assertNotNull(updateRequest);
            assertEquals(targetUrl, updateRequest.url);
            assertEquals("{\"method\":\"wlan-adb\",\"endpoint\":\"192.168.1.50:41234\"}",
                    updateRequest.payload);
            assertNotNull(disconnectRequest);
            assertEquals(targetUrl, disconnectRequest.url);
            assertNull(disconnectRequest.payload);
            assertEquals(KeepADBPreferences.WEBHOOK_STATUS_DEREGISTERED,
                    KeepADBPreferences.getWebhookLastReportStatus(context));
            assertFalse(KeepADBRegisterClient.isWlanUpdateInFlightForTesting());
        } finally {
            // Do not leave the blocked worker or a test listener behind when an assertion fails.
            canFinishFirstRequest.countDown();
            KeepADBRegisterClient.clearRegisterStateListener();
        }
    }

    @Test
    public void testInFlightUsbRegistrationSupersededByDisconnect() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        KeepADBFakeHttpTransport transport = new KeepADBFakeHttpTransport();
        KeepADBRegisterClient.setHttpTransport(transport);

        CountDownLatch firstRequestStarted = new CountDownLatch(1);
        CountDownLatch canFinishFirstRequest = new CountDownLatch(1);

        transport.setRequestCallback(req -> {
            if ("POST".equals(req.method) && req.payload != null && req.payload.contains("Desk")) {
                firstRequestStarted.countDown();
                try {
                    canFinishFirstRequest.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
            }
        });

        // Start Desk registration (in-flight)
        KeepADBRegisterClient.updateUsbEndpointAsyncInternal(context, true, "http://fake.url/register",
                "device123", 1, "Desk", "192.168.1.20", "host", "tailhost");
        assertTrue(firstRequestStarted.await(3, TimeUnit.SECONDS));

        // While Desk is in-flight, USB disconnects (markUsbInactiveAsync)
        KeepADBRegisterClient.markUsbInactiveAsyncInternal(context, true, "http://fake.url/register", "device123");

        // Allow Desk to finish
        canFinishFirstRequest.countDown();

        // Wait until inactive request is processed
        waitUntil(() -> transport.getRequestCount() >= 2, 3000);
        ShadowLooper.idleMainLooper();

        // Verify Desk was superseded and not registered
        assertNull(KeepADBRegisterClient.getLastRegisteredUsbProfileNameForTesting());
        assertNull(KeepADBPreferences.getUsbWebhookLastProfileName(context));
        assertNull(KeepADBRegisterClient.getLastRegisteredUsbPayloadForTesting());
        assertNull(KeepADBPreferences.getUsbWebhookLastReportedPayload(context));

        // Verify inactive payload was sent with profile metadata
        assertEquals(2, transport.getRequestCount());
        assertEquals("POST", transport.recordedRequests.get(0).method);
        assertTrue(transport.recordedRequests.get(0).payload.contains("\"active\":true"));
        KeepADBFakeHttpTransport.Request inactiveReq = transport.recordedRequests.get(1);
        assertEquals("POST", inactiveReq.method);
        assertEquals("http://fake.url/register", inactiveReq.url);
        assertTrue(inactiveReq.payload.contains("\"active\":false"));
        assertTrue(inactiveReq.payload.contains("\"profileId\":1"));
        assertTrue(inactiveReq.payload.contains("\"profileName\":\"Desk\""));
        assertTrue(inactiveReq.payload.contains("\"ipAddress\":\"192.168.1.20\""));

        // Verify USB state is cleared
        assertNull(KeepADBRegisterClient.getLastRegisteredUsbUrlForTesting());
        assertNull(KeepADBRegisterClient.getLastRegisteredUsbProfileIdForTesting());
        assertNull(KeepADBPreferences.getUsbWebhookLastReportedUrl(context));
        assertFalse(KeepADBRegisterClient.isUsbUpdateInFlightForTesting());
    }

    @Test
    public void testInFlightInitialUsbUpdateSupersededViaMarkUsbInactiveAsync() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        KeepADBPreferences.setRegisterWebhookUrl(context, "http://fake.url/register");
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);

        KeepADBFakeHttpTransport transport = new KeepADBFakeHttpTransport();
        KeepADBRegisterClient.setHttpTransport(transport);

        CountDownLatch firstRequestStarted = new CountDownLatch(1);
        CountDownLatch canFinishFirstRequest = new CountDownLatch(1);

        transport.setRequestCallback(req -> {
            if ("POST".equals(req.method) && req.payload != null && req.payload.contains("Workstation")) {
                firstRequestStarted.countDown();
                try {
                    canFinishFirstRequest.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
            }
        });

        KeepADBUsbProfile.Profile profile = new KeepADBUsbProfile.Profile(
                7, "Workstation", "10.0.0.99", "work-pc", "work.tailnet.ts.net");

        // Start initial USB registration (in-flight)
        KeepADBRegisterClient.updateUsbEndpointAsync(context, profile);
        assertTrue(firstRequestStarted.await(3, TimeUnit.SECONDS));

        // While initial POST is in-flight, USB disconnects via public API
        KeepADBRegisterClient.markUsbInactiveAsync(context);

        // Allow initial POST to finish
        canFinishFirstRequest.countDown();

        // Wait until inactive request is processed
        waitUntil(() -> transport.getRequestCount() >= 2, 3000);
        ShadowLooper.idleMainLooper();

        // Assert 1: In-flight POST completion does not persist active state
        assertNull(KeepADBRegisterClient.getLastRegisteredUsbProfileNameForTesting());
        assertNull(KeepADBPreferences.getUsbWebhookLastProfileName(context));
        assertNull(KeepADBRegisterClient.getLastRegisteredUsbPayloadForTesting());
        assertNull(KeepADBPreferences.getUsbWebhookLastReportedPayload(context));

        // Assert 2: Inactive USB payload (active: false) is sent to the server with profile metadata
        assertEquals(2, transport.getRequestCount());
        KeepADBFakeHttpTransport.Request inactiveReq = transport.recordedRequests.get(1);
        assertEquals("POST", inactiveReq.method);
        assertTrue(inactiveReq.payload.contains("\"active\":false"));
        assertTrue(inactiveReq.payload.contains("\"profileId\":7"));
        assertTrue(inactiveReq.payload.contains("\"profileName\":\"Workstation\""));
        assertTrue(inactiveReq.payload.contains("\"ipAddress\":\"10.0.0.99\""));

        // Assert 3: USB state is cleared
        assertNull(KeepADBRegisterClient.getLastRegisteredUsbUrlForTesting());
        assertNull(KeepADBRegisterClient.getLastRegisteredUsbProfileIdForTesting());
        assertNull(KeepADBPreferences.getUsbWebhookLastReportedUrl(context));
        assertFalse(KeepADBRegisterClient.isUsbUpdateInFlightForTesting());
    }

    @Test
    public void testMarkUnavailableAsyncWhenNothingRegisteredOrInFlightIsNoOp() {
        Context context = ApplicationProvider.getApplicationContext();
        KeepADBPreferences.setRegisterWebhookUrl(context, "http://fake.url/register");
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);

        KeepADBFakeHttpTransport transport = new KeepADBFakeHttpTransport();
        KeepADBRegisterClient.setHttpTransport(transport);

        KeepADBRegisterClient.markUnavailableAsync(context);

        assertEquals(0, transport.getRequestCount());
        assertNull(KeepADBRegisterClient.getLastRegisteredEndpointForTesting());
    }

    @Test
    public void testDefaultHttpTransportPostFailureOnUnreachableHost() {
        String url = "http://127.0.0.1:1/register";
        boolean success = KeepADBRegisterClient.postEndpoint(url, "192.168.1.50:41234");
        assertFalse(success);
    }

    @Test
    public void testDefaultHttpTransportDeleteFailureOn500() {
        responseCode.set(500);
        String url = "http://127.0.0.1:" + testServerPort + "/register";
        boolean success = KeepADBRegisterClient.deleteEndpoint(url);
        assertFalse(success);
    }

    private static void waitUntil(Callable<Boolean> condition, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(condition.call())) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Condition timed out after " + timeoutMs + " ms");
    }

    private static void awaitMainLooperSignal(CountDownLatch signal, long timeoutMs) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (true) {
            ShadowLooper.idleMainLooper();
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new AssertionError("Signal timed out after " + timeoutMs + " ms");
            }
            if (signal.await(Math.min(remainingNanos, TimeUnit.MILLISECONDS.toNanos(20)),
                    TimeUnit.NANOSECONDS)) {
                return;
            }
        }
    }
}
