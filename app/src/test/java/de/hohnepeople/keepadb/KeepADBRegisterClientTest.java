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
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBRegisterClientTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

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

    /**
     * #552: the actual register-side stale rejection is fixed in {@code phone_register_common.py}
     * (a same-state confirmation is no longer judged solely by cross-clock {@code observed_at}
     * ordering). That fix only helps if the app keeps sending a fresh, ever-advancing
     * {@code observed_at} on every retry of an unchanged endpoint -- if a failed attempt cached
     * and replayed its original timestamp, a genuinely later confirmation could never overtake a
     * host-recorded event with a later clock reading. This locks in the app's half of that
     * contract: a retry of the very same, still-failing endpoint is not a no-op and does not reuse
     * the previous attempt's {@code observed_at}.
     */
    @Test
    public void testFailedRetryOfSameEndpointSendsFreshObservedAtEachTime() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        KeepADBPreferences.setRegisterWebhookUrl(context, "http://fake.url/register");
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);

        KeepADBFakeHttpTransport transport = new KeepADBFakeHttpTransport();
        transport.setPostSuccess(false);
        KeepADBRegisterClient.setHttpTransport(transport);

        KeepADBRegisterClient.updateEndpointAsync(context, "192.168.178.24", 41649);
        waitUntil(() -> transport.getRequestCount() >= 1, 3000);
        KeepADBRegisterClient.awaitIdleForTesting(3000);

        Thread.sleep(5);

        // A failed POST never marks the endpoint as registered, so a second call for the exact
        // same, still-unchanged host:port is not short-circuited by the "already registered"
        // in-memory guard and re-sends its own event.
        KeepADBRegisterClient.updateEndpointAsync(context, "192.168.178.24", 41649);
        waitUntil(() -> transport.getRequestCount() >= 2, 3000);
        KeepADBRegisterClient.awaitIdleForTesting(3000);

        assertEquals(2, transport.getRequestCount());
        String firstObservedAt = extractObservedAt(transport.recordedRequests.get(0).payload);
        String secondObservedAt = extractObservedAt(transport.recordedRequests.get(1).payload);
        assertNotNull(firstObservedAt);
        assertNotNull(secondObservedAt);
        assertTrue("retry must not resend the first attempt's observed_at ("
                        + firstObservedAt + " vs " + secondObservedAt + ")",
                java.time.Instant.parse(secondObservedAt).isAfter(java.time.Instant.parse(firstObservedAt)));
        // Both attempts still describe the exact same endpoint -- only the timestamp advances.
        assertTrue(transport.recordedRequests.get(0).payload.contains("192.168.178.24:41649"));
        assertTrue(transport.recordedRequests.get(1).payload.contains("192.168.178.24:41649"));
    }

    private static String extractObservedAt(String json) {
        if (json == null) return null;
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("\"observed_at\":\"([^\"]+)\"").matcher(json);
        return matcher.find() ? matcher.group(1) : null;
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

    /**
     * #562: the coordinated retry staffing agreed in the issue -- immediate first attempt, then
     * 5s/10s/15s/30s/1min/3min after each consecutive failure, settling into a 5min ceiling. Uses
     * the injectable {@code pendingCleanupNowForTesting} clock (shared with the existing #317
     * pending-cleanup backoff) instead of real sleeps, so the whole progression runs instantly.
     */
    @Test
    public void testMarkUnavailableAsyncRetryFollowsApprovedBackoffStaffing() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        KeepADBPreferences.setRegisterWebhookUrl(context, "http://fake.url/register");
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        KeepADBPreferences.setWebhookLastReportedUrl(context, "http://fake.url/register");
        KeepADBPreferences.setWebhookLastReportedEndpoint(context, "192.168.1.50:41234");

        KeepADBFakeHttpTransport transport = new KeepADBFakeHttpTransport();
        transport.setDeleteSuccess(false);
        KeepADBRegisterClient.setHttpTransport(transport);

        long t0 = 1_000_000_000L;
        KeepADBRegisterClient.setPendingCleanupNowForTesting(t0);

        // First attempt for a newly-unavailable endpoint is immediate, regardless of backoff.
        KeepADBRegisterClient.markUnavailableAsync(context);
        KeepADBRegisterClient.awaitIdleForTesting(3000);
        assertEquals(1, transport.getRequestCount());
        assertEquals(1, KeepADBRegisterClient.getMarkUnavailableRetryAttemptsForTesting());
        long[] expectedBackoffsMs = {5_000L, 10_000L, 15_000L, 30_000L, 60_000L, 180_000L};
        long expectedNextAt = t0 + expectedBackoffsMs[0];
        assertEquals(expectedNextAt, KeepADBRegisterClient.getMarkUnavailableNextAttemptAtForTesting());

        long now = t0;
        int expectedRequestCount = 1;
        for (int i = 0; i < expectedBackoffsMs.length; i++) {
            // Repeated calls inside the current backoff window must not re-issue the DELETE --
            // this is exactly the request storm #562 reports (51 failures, mostly ~60s apart).
            KeepADBRegisterClient.setPendingCleanupNowForTesting(now);
            KeepADBRegisterClient.markUnavailableAsync(context);
            KeepADBRegisterClient.awaitIdleForTesting(3000);
            assertEquals("no retry before backoff #" + i + " elapses",
                    expectedRequestCount, transport.getRequestCount());

            KeepADBRegisterClient.setPendingCleanupNowForTesting(now + expectedBackoffsMs[i] - 1);
            KeepADBRegisterClient.markUnavailableAsync(context);
            KeepADBRegisterClient.awaitIdleForTesting(3000);
            assertEquals("no retry 1ms before backoff #" + i + " elapses",
                    expectedRequestCount, transport.getRequestCount());

            now = now + expectedBackoffsMs[i];
            KeepADBRegisterClient.setPendingCleanupNowForTesting(now);
            KeepADBRegisterClient.markUnavailableAsync(context);
            KeepADBRegisterClient.awaitIdleForTesting(3000);
            expectedRequestCount++;
            assertEquals("retry fires once its backoff elapses",
                    expectedRequestCount, transport.getRequestCount());
            assertEquals(i + 2, KeepADBRegisterClient.getMarkUnavailableRetryAttemptsForTesting());

            long nextBackoffMs = (i + 1 < expectedBackoffsMs.length)
                    ? expectedBackoffsMs[i + 1]
                    : 300_000L; // 5min ceiling once the table is exhausted (#562).
            assertEquals(now + nextBackoffMs,
                    KeepADBRegisterClient.getMarkUnavailableNextAttemptAtForTesting());
        }

        // One more failure beyond the table: settles into the flat 5min ceiling, not a further
        // escalation and not an unbounded/age-based cutoff.
        now = now + 300_000L;
        KeepADBRegisterClient.setPendingCleanupNowForTesting(now);
        KeepADBRegisterClient.markUnavailableAsync(context);
        KeepADBRegisterClient.awaitIdleForTesting(3000);
        expectedRequestCount++;
        assertEquals(expectedRequestCount, transport.getRequestCount());
        assertEquals(now + 300_000L, KeepADBRegisterClient.getMarkUnavailableNextAttemptAtForTesting());
    }

    /**
     * #562: once the register actually confirms the DELETE, the retry counter must not linger --
     * a later, independent unavailability must again get its immediate first attempt rather than
     * inheriting a stale backoff from an unrelated earlier failure sequence.
     */
    @Test
    public void testMarkUnavailableAsyncRetryResetsAfterSuccessfulCleanup() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        KeepADBPreferences.setRegisterWebhookUrl(context, "http://fake.url/register");
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        KeepADBPreferences.setWebhookLastReportedUrl(context, "http://fake.url/register");
        KeepADBPreferences.setWebhookLastReportedEndpoint(context, "192.168.1.50:41234");

        KeepADBFakeHttpTransport transport = new KeepADBFakeHttpTransport();
        transport.setDeleteSuccess(false);
        KeepADBRegisterClient.setHttpTransport(transport);

        long t0 = 2_000_000_000L;
        KeepADBRegisterClient.setPendingCleanupNowForTesting(t0);
        KeepADBRegisterClient.markUnavailableAsync(context);
        KeepADBRegisterClient.awaitIdleForTesting(3000);
        assertEquals(1, transport.getRequestCount());
        assertEquals(1, KeepADBRegisterClient.getMarkUnavailableRetryAttemptsForTesting());

        // The next attempt (once its backoff has elapsed) succeeds.
        transport.setDeleteSuccess(true);
        long t1 = t0 + 5_000L;
        KeepADBRegisterClient.setPendingCleanupNowForTesting(t1);
        KeepADBRegisterClient.markUnavailableAsync(context);
        KeepADBRegisterClient.awaitIdleForTesting(3000);
        assertEquals(2, transport.getRequestCount());
        assertEquals(0, KeepADBRegisterClient.getMarkUnavailableRetryAttemptsForTesting());
        assertEquals(0L, KeepADBRegisterClient.getMarkUnavailableNextAttemptAtForTesting());

        // A brand-new registration/unavailability cycle afterwards is not throttled by the
        // resolved, unrelated earlier sequence.
        KeepADBPreferences.setWebhookLastReportedUrl(context, "http://fake.url/register");
        KeepADBPreferences.setWebhookLastReportedEndpoint(context, "192.168.1.60:9000");
        KeepADBRegisterClient.setWlanStateForTesting("http://fake.url/register", "192.168.1.60:9000");
        transport.setDeleteSuccess(false);
        KeepADBRegisterClient.markUnavailableAsync(context);
        KeepADBRegisterClient.awaitIdleForTesting(3000);
        assertEquals(3, transport.getRequestCount());
        assertEquals(1, KeepADBRegisterClient.getMarkUnavailableRetryAttemptsForTesting());
    }

    /**
     * #562: a confirmed new registration must never be blocked or overridden by a stale
     * "unavailable" retry that is still waiting out its own backoff for the previous resource.
     */
    @Test
    public void testMarkUnavailableAsyncRetryDoesNotBlockNewRegistration() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        KeepADBPreferences.setRegisterWebhookUrl(context, "http://fake.url/register");
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        KeepADBPreferences.setWebhookLastReportedUrl(context, "http://fake.url/register");
        KeepADBPreferences.setWebhookLastReportedEndpoint(context, "192.168.1.50:41234");

        KeepADBFakeHttpTransport transport = new KeepADBFakeHttpTransport();
        transport.setDeleteSuccess(false);
        KeepADBRegisterClient.setHttpTransport(transport);

        long t0 = 3_000_000_000L;
        KeepADBRegisterClient.setPendingCleanupNowForTesting(t0);
        KeepADBRegisterClient.markUnavailableAsync(context);
        KeepADBRegisterClient.awaitIdleForTesting(3000);
        assertEquals(1, transport.getRequestCount());
        assertTrue(KeepADBRegisterClient.getMarkUnavailableNextAttemptAtForTesting() > t0);

        // The device reconnects while still inside the pending-DELETE backoff window: the new
        // registration must go through unaffected by the still-open retry gate.
        KeepADBRegisterClient.updateEndpointAsync(context, "192.168.1.50", 55555);
        KeepADBRegisterClient.awaitIdleForTesting(3000);

        assertEquals("192.168.1.50:55555", KeepADBRegisterClient.getLastRegisteredEndpointForTesting());
        assertEquals(KeepADBPreferences.WEBHOOK_STATUS_SUCCESS,
                KeepADBPreferences.getWebhookLastReportStatus(context));
        // The stale retry state for the old resource must be cleared by the confirmed
        // registration, not left to fire a delayed DELETE against the now-live endpoint.
        assertEquals(0, KeepADBRegisterClient.getMarkUnavailableRetryAttemptsForTesting());
        assertEquals(0L, KeepADBRegisterClient.getMarkUnavailableNextAttemptAtForTesting());
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
            // #539: contract-v2 event. method/endpoint keep their pre-v2 place so the register's
            // legacy projection is unchanged; the remaining fields are additive.
            assertTrue(updateRequest.payload.contains("\"contract_version\":2"));
            assertTrue(updateRequest.payload.contains("\"method\":\"wlan-adb\""));
            assertTrue(updateRequest.payload.contains("\"endpoint\":\"192.168.1.50:41234\""));
            assertTrue(updateRequest.payload.contains("\"event_id\":\""
                    + KeepADBRegisterPayload.eventIdFor("wlan-adb", "192.168.1.50:41234", true)
                    + "\""));
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
