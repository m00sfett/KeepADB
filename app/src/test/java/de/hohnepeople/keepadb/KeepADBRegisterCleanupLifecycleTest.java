package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Behaviour tests for the register-client lifecycle hardening of issue #317:
 * retryable cleanup of a superseded webhook URL (WLAN and USB), symmetric USB failure
 * reporting, and a WLAN snapshot that reaches the preferences file as a single editor
 * transaction. The preferences fake counts {@code apply()} calls, which is what makes the
 * atomicity claim observable rather than a code-shape assertion.
 */
public class KeepADBRegisterCleanupLifecycleTest {

    private static final String OLD_URL = "http://old.example/register";
    private static final String NEW_URL = "http://new.example/register";

    private KeepADBFakeHttpTransport transport;
    private FakeContext context;

    @Before
    public void setUp() {
        KeepADBRegisterClient.resetForTesting();
        transport = new KeepADBFakeHttpTransport();
        KeepADBRegisterClient.setHttpTransport(transport);
        context = new FakeContext();
    }

    @After
    public void tearDown() {
        KeepADBRegisterClient.resetHttpTransport();
        KeepADBRegisterClient.resetForTesting();
    }

    // ---- Criterion 1: a failed cleanup of the old URL stays retryable. ----

    @Test
    public void wlanCleanupFailureOnUrlChangeIsRememberedAndRetriedLater() throws Exception {
        configureWebhook(NEW_URL);
        KeepADBRegisterClient.setWlanStateForTesting(OLD_URL, "192.168.1.50:41234");
        transport.setDeleteSuccess(false);

        KeepADBRegisterClient.updateEndpointAsync(context, "192.168.1.51", 41235);
        waitUntil(() -> "192.168.1.51:41235".equals(
                KeepADBPreferences.getWebhookLastReportedEndpoint(context)), 3000);

        // The DELETE failed, but the old registration is not forgotten.
        assertTrue(KeepADBPreferences.getPendingWebhookCleanupUrls(context).contains(OLD_URL));
        assertEquals(NEW_URL, KeepADBPreferences.getWebhookLastReportedUrl(context));

        // Next register activity retries it; on success the retry entry disappears.
        transport.setDeleteSuccess(true);
        transport.clearRequests();
        KeepADBRegisterClient.updateEndpointAsync(context, "192.168.1.52", 41236);
        waitUntil(() -> KeepADBPreferences.getPendingWebhookCleanupUrls(context).isEmpty(), 3000);

        KeepADBFakeHttpTransport.Request first = transport.recordedRequests.get(0);
        assertEquals("DELETE", first.method);
        assertEquals(OLD_URL, first.url);
    }

    @Test
    public void wlanCleanupSuccessOnUrlChangeLeavesNothingPending() throws Exception {
        configureWebhook(NEW_URL);
        KeepADBRegisterClient.setWlanStateForTesting(OLD_URL, "192.168.1.50:41234");

        KeepADBRegisterClient.updateEndpointAsync(context, "192.168.1.51", 41235);
        waitUntil(() -> "192.168.1.51:41235".equals(
                KeepADBPreferences.getWebhookLastReportedEndpoint(context)), 3000);

        assertTrue(KeepADBPreferences.getPendingWebhookCleanupUrls(context).isEmpty());
        assertEquals("DELETE", transport.recordedRequests.get(0).method);
        assertEquals(OLD_URL, transport.recordedRequests.get(0).url);
    }

    @Test
    public void legacyPendingCleanupUrlsAreSanitizedBeforeRetryAndRemovedByOriginalEntry()
            throws Exception {
        String legacyWlanUrl = "http://admin:secret@legacy.example/register?token=abc";
        String legacyUsbUrl = "https://usbadmin:secret@usb-legacy.example/register#fragment";
        String payload = KeepADBRegisterClient.buildUsbPayload(
                "device", 1, "Office", "host", "hostname", "tailnet", false);
        KeepADBPreferences.addPendingWebhookCleanupUrl(context, legacyWlanUrl);
        KeepADBPreferences.addPendingUsbWebhookCleanup(context, legacyUsbUrl, payload);

        KeepADBRegisterClient.updateUsbEndpointAsyncInternal(context, true,
                "http://new.example/register", "device", 1, "Office", "host", "hostname", "tailnet");
        waitUntil(() -> KeepADBPreferences.getPendingWebhookCleanupUrls(context).isEmpty()
                && KeepADBPreferences.getPendingUsbWebhookCleanups(context).isEmpty(), 3000);

        KeepADBFakeHttpTransport.Request wlanCleanup = transport.recordedRequests.get(0);
        assertEquals("DELETE", wlanCleanup.method);
        assertEquals("http://legacy.example/register?token=abc", wlanCleanup.url);
        KeepADBFakeHttpTransport.Request usbCleanup = transport.recordedRequests.get(1);
        assertEquals("POST", usbCleanup.method);
        assertEquals("https://usb-legacy.example/register", usbCleanup.url);
        assertFalse(wlanCleanup.url.contains("secret"));
        assertFalse(usbCleanup.url.contains("secret"));
    }

    @Test
    public void usbUrlChangeDeactivatesThePreviousRegistration() throws Exception {
        KeepADBRegisterClient.setUsbStateForTesting(OLD_URL,
                KeepADBRegisterClient.buildUsbPayload("dev1", 5, "Office", "10.0.0.5", "h1", "t1", true),
                5, "Office", "10.0.0.5", "h1", "t1");

        KeepADBRegisterClient.updateUsbEndpointAsyncInternal(context, true, NEW_URL, "dev1",
                5, "Office", "10.0.0.5", "h1", "t1");
        waitUntil(() -> NEW_URL.equals(KeepADBRegisterClient.getLastRegisteredUsbUrlForTesting()), 3000);

        // Before #317 the old URL was never told; it kept this device as active forever.
        KeepADBFakeHttpTransport.Request deactivation = transport.recordedRequests.get(0);
        assertEquals("POST", deactivation.method);
        assertEquals(OLD_URL, deactivation.url);
        assertTrue(deactivation.payload.contains("\"active\":false"));
        assertTrue(deactivation.payload.contains("\"profileName\":\"Office\""));

        KeepADBFakeHttpTransport.Request registration = transport.recordedRequests.get(1);
        assertEquals(NEW_URL, registration.url);
        assertTrue(registration.payload.contains("\"active\":true"));
        assertTrue(KeepADBPreferences.getPendingUsbWebhookCleanups(context).isEmpty());
    }

    @Test
    public void usbCleanupFailureOnUrlChangeIsRememberedAndRetriedLater() throws Exception {
        KeepADBRegisterClient.setUsbStateForTesting(OLD_URL,
                KeepADBRegisterClient.buildUsbPayload("dev1", 5, "Office", "10.0.0.5", "h1", "t1", true),
                5, "Office", "10.0.0.5", "h1", "t1");
        transport.setPostSuccess(false);

        KeepADBRegisterClient.updateUsbEndpointAsyncInternal(context, true, NEW_URL, "dev1",
                5, "Office", "10.0.0.5", "h1", "t1");
        // Nothing succeeded, so the old URL is still the registered one and the migration as a
        // whole is retried; no pending entry is written while nothing has been forgotten yet.
        waitUntil(() -> transport.getRequestCount() >= 2, 3000);
        Thread.sleep(100);
        assertTrue(KeepADBPreferences.getPendingUsbWebhookCleanups(context).isEmpty());
        assertEquals(OLD_URL, KeepADBRegisterClient.getLastRegisteredUsbUrlForTesting());

        // Now only the new URL answers: the migration completes, the undelivered deactivation
        // of the old URL is kept for a retry instead of being dropped.
        transport.clearRequests();
        transport.setPostSuccess(true);
        transport.setFailingUrl(OLD_URL);
        KeepADBRegisterClient.updateUsbEndpointAsyncInternal(context, true, NEW_URL, "dev1",
                5, "Office", "10.0.0.5", "h1", "t1");
        waitUntil(() -> NEW_URL.equals(KeepADBRegisterClient.getLastRegisteredUsbUrlForTesting()), 3000);

        Set<String> pending = KeepADBPreferences.getPendingUsbWebhookCleanups(context);
        assertEquals(1, pending.size());
        String entry = pending.iterator().next();
        assertEquals(OLD_URL, KeepADBPreferences.pendingCleanupUrl(entry));
        assertTrue(KeepADBPreferences.pendingCleanupPayload(entry).contains("\"active\":false"));

        // The next USB register activity retries it and clears the entry once it lands.
        transport.setFailingUrl(null);
        transport.clearRequests();
        KeepADBRegisterClient.updateUsbEndpointAsyncInternal(context, true, NEW_URL, "dev1",
                6, "Laptop", "10.0.0.6", "h2", "t2");
        waitUntil(() -> KeepADBPreferences.getPendingUsbWebhookCleanups(context).isEmpty(), 3000);
        assertEquals(OLD_URL, transport.recordedRequests.get(0).url);
        assertTrue(transport.recordedRequests.get(0).payload.contains("\"active\":false"));
    }

    // ---- Invariant: a pending cleanup never targets the currently registered URL. ----

    @Test
    public void wlanPendingCleanupForTheUrlJustRegisteredIsDropped() throws Exception {
        // A cleanup can survive its own flush: an already-absent record answers a DELETE with 404,
        // which counts as a failure, while the POST that follows in the same transaction succeeds.
        // The entry would then queue a DELETE for the URL that is now live -- and a later flush
        // from a USB transaction does not re-post, so the registration would vanish silently.
        KeepADBPreferences.addPendingWebhookCleanupUrl(context, NEW_URL);
        transport.setDeleteSuccess(false);
        configureWebhook(NEW_URL);

        KeepADBRegisterClient.updateEndpointAsync(context, "192.168.1.51", 41235);
        waitUntil(() -> NEW_URL.equals(KeepADBPreferences.getWebhookLastReportedUrl(context)), 3000);
        Thread.sleep(100);

        assertTrue("a cleanup must not point at the live registration",
                KeepADBPreferences.getPendingWebhookCleanupUrls(context).isEmpty());
    }

    @Test
    public void usbPendingCleanupForTheUrlJustRegisteredIsDropped() throws Exception {
        KeepADBPreferences.addPendingUsbWebhookCleanup(context, NEW_URL,
                KeepADBRegisterClient.buildUsbPayload("dev1", 5, "Office", "10.0.0.5", "h1", "t1", false));

        // The flushed cleanup fails, the registration POST right behind it succeeds.
        AtomicInteger requests = new AtomicInteger();
        transport.setPostSuccess(false);
        transport.setRequestCallback(req -> {
            if (requests.getAndIncrement() >= 1) {
                transport.setPostSuccess(true);
            }
        });

        KeepADBRegisterClient.updateUsbEndpointAsyncInternal(context, true, NEW_URL, "dev1",
                5, "Office", "10.0.0.5", "h1", "t1");
        waitUntil(() -> NEW_URL.equals(KeepADBRegisterClient.getLastRegisteredUsbUrlForTesting()), 3000);
        Thread.sleep(100);

        assertTrue("a cleanup must not deactivate the live registration",
                KeepADBPreferences.getPendingUsbWebhookCleanups(context).isEmpty());
    }

    @Test
    public void wlanRegistrationDropsEquivalentLegacyPendingCleanup() throws Exception {
        configureWebhook(NEW_URL);
        KeepADBPreferences.addPendingWebhookCleanupUrl(context,
                "http://user:secret@new.example/register");
        transport.setDeleteSuccess(false);

        KeepADBRegisterClient.updateEndpointAsync(context, "192.168.1.51", 41235);
        waitUntil(() -> "192.168.1.51:41235".equals(
                KeepADBPreferences.getWebhookLastReportedEndpoint(context)), 3000);

        assertTrue("a legacy cleanup for the newly registered URL must be obsolete",
                KeepADBPreferences.getPendingWebhookCleanupUrls(context).isEmpty());
    }

    @Test
    public void usbRegistrationDropsEquivalentLegacyWlanPendingCleanup() throws Exception {
        configureWebhook(NEW_URL);
        KeepADBPreferences.addPendingWebhookCleanupUrl(context,
                "http://user:secret@new.example/register");
        transport.setDeleteSuccess(false);

        KeepADBRegisterClient.updateUsbEndpointAsyncInternal(context, true, NEW_URL, "dev1",
                5, "Office", "10.0.0.5", "h1", "t1");
        waitUntil(() -> NEW_URL.equals(KeepADBRegisterClient.getLastRegisteredUsbUrlForTesting()), 3000);

        assertTrue("a legacy WLAN cleanup must not deactivate the new USB registration",
                KeepADBPreferences.getPendingWebhookCleanupUrls(context).isEmpty());
    }

    // ---- #370: the server cleanup endpoint is shared by WLAN and USB. ----

    @Test
    public void wlanCleanupSkipsDeleteWhenUsbRegistrationIsLiveAtSameUrl() throws Exception {
        configureWebhook(NEW_URL);
        KeepADBRegisterClient.setWlanStateForTesting(NEW_URL, "192.168.1.51:41235");
        KeepADBRegisterClient.setUsbStateForTesting(NEW_URL,
                KeepADBRegisterClient.buildUsbPayload("dev1", 5, "Office", "10.0.0.5", "h1", "t1", true),
                5, "Office", "10.0.0.5", "h1", "t1");

        KeepADBRegisterClient.markUnavailableAsync(context);

        waitUntil(() -> KeepADBPreferences.WEBHOOK_STATUS_DEREGISTERED.equals(
                KeepADBPreferences.getWebhookLastReportStatus(context)), 3000);

        assertEquals(0, transport.getRequestCount());
        assertNull(KeepADBRegisterClient.getLastRegisteredUrlForTesting());
        assertEquals(NEW_URL, KeepADBRegisterClient.getLastRegisteredUsbUrlForTesting());
    }

    @Test
    public void wlanCleanupSkipsDeleteWhenUsbUrlSnapshotIsPartial() throws Exception {
        configureWebhook(NEW_URL);
        KeepADBRegisterClient.setWlanStateForTesting(NEW_URL, "192.168.1.51:41235");
        KeepADBRegisterClient.setUsbStateForTesting(NEW_URL, null, null, null, null, null, null);

        KeepADBRegisterClient.markUnavailableAsync(context);

        waitUntil(() -> KeepADBPreferences.WEBHOOK_STATUS_DEREGISTERED.equals(
                KeepADBPreferences.getWebhookLastReportStatus(context)), 3000);

        assertEquals("A partial USB snapshot must still protect the shared server record", 0,
                transport.getRequestCount());
        assertEquals(NEW_URL, KeepADBRegisterClient.getLastRegisteredUsbUrlForTesting());
    }

    @Test
    public void wlanCleanupSkipsDeleteWhenUsbUsesEquivalentServerUrl() throws Exception {
        configureWebhook(NEW_URL);
        KeepADBRegisterClient.setWlanStateForTesting(NEW_URL, "192.168.1.51:41235");
        KeepADBRegisterClient.setUsbStateForTesting(NEW_URL + "/?token=rotated", "payload",
                5, "Office", "10.0.0.5", "h1", "t1");

        KeepADBRegisterClient.markUnavailableAsync(context);

        waitUntil(() -> KeepADBPreferences.WEBHOOK_STATUS_DEREGISTERED.equals(
                KeepADBPreferences.getWebhookLastReportStatus(context)), 3000);

        assertEquals("Query and trailing-slash variants share the server record", 0,
                transport.getRequestCount());
        assertEquals(NEW_URL + "/?token=rotated",
                KeepADBRegisterClient.getLastRegisteredUsbUrlForTesting());
    }

    @Test
    public void usbCleanupSkipsInactivePostWhenWlanRegistrationIsLiveAtSameUrl() throws Exception {
        configureWebhook(NEW_URL);
        KeepADBRegisterClient.setWlanStateForTesting(NEW_URL, "192.168.1.51:41235");
        KeepADBRegisterClient.setUsbStateForTesting(NEW_URL,
                KeepADBRegisterClient.buildUsbPayload("dev1", 5, "Office", "10.0.0.5", "h1", "t1", true),
                5, "Office", "10.0.0.5", "h1", "t1");

        KeepADBRegisterClient.markUsbInactiveAsyncInternal(context, true, NEW_URL, "dev1");

        waitUntil(() -> KeepADBRegisterClient.getLastRegisteredUsbUrlForTesting() == null, 3000);

        assertEquals(0, transport.getRequestCount());
        assertEquals(NEW_URL, KeepADBRegisterClient.getLastRegisteredUrlForTesting());
        assertEquals("192.168.1.51:41235",
                KeepADBRegisterClient.getLastRegisteredEndpointForTesting());
    }

    @Test
    public void usbCleanupSkipsInactivePostWhenWlanUrlSnapshotIsPartial() throws Exception {
        configureWebhook(NEW_URL);
        KeepADBRegisterClient.setWlanStateForTesting(NEW_URL, null);
        String payload = KeepADBRegisterClient.buildUsbPayload(
                "dev1", 5, "Office", "10.0.0.5", "h1", "t1", true);
        KeepADBRegisterClient.setUsbStateForTesting(NEW_URL, payload,
                5, "Office", "10.0.0.5", "h1", "t1");

        KeepADBRegisterClient.markUsbInactiveAsyncInternal(context, true, NEW_URL, "dev1");

        waitUntil(() -> KeepADBRegisterClient.getLastRegisteredUsbUrlForTesting() == null, 3000);

        assertEquals("A partial WLAN snapshot must still protect the shared server record", 0,
                transport.getRequestCount());
        assertEquals(NEW_URL, KeepADBRegisterClient.getLastRegisteredUrlForTesting());
    }

    @Test
    public void usbCleanupLoadsPersistedPartialWlanSnapshotBeforeGuarding() throws Exception {
        configureWebhook(NEW_URL);
        // Simulate process death after the WLAN URL reached disk but before its endpoint did.
        KeepADBPreferences.setWebhookReportSnapshot(context, NEW_URL, null,
                KeepADBPreferences.WEBHOOK_STATUS_SUCCESS, true);
        String payload = KeepADBRegisterClient.buildUsbPayload(
                "dev1", 5, "Office", "test-usb-ip", "h1", "t1", true);
        KeepADBRegisterClient.resetForTesting();
        KeepADBRegisterClient.setHttpTransport(transport);
        KeepADBRegisterClient.setUsbStateForTesting(NEW_URL, payload,
                5, "Office", "test-usb-ip", "h1", "t1");

        KeepADBRegisterClient.markUsbInactiveAsyncInternal(context, true, NEW_URL, "dev1");

        waitUntil(() -> KeepADBRegisterClient.getLastRegisteredUsbUrlForTesting() == null, 3000);

        assertEquals("A persisted partial WLAN snapshot must protect the shared server record", 0,
                transport.getRequestCount());
        assertEquals(NEW_URL, KeepADBRegisterClient.getLastRegisteredUrlForTesting());
    }

    @Test
    public void wlanPendingCleanupIsDroppedWhenUsbRegistrationIsLiveAtSameUrl() throws Exception {
        configureWebhook(NEW_URL);
        KeepADBPreferences.addPendingWebhookCleanupUrl(context, NEW_URL);
        KeepADBRegisterClient.setUsbStateForTesting(NEW_URL,
                KeepADBRegisterClient.buildUsbPayload("dev1", 5, "Office", "10.0.0.5", "h1", "t1", true),
                5, "Office", "10.0.0.5", "h1", "t1");

        KeepADBRegisterClient.unregisterAndDisableAsync(context);

        waitUntil(() -> KeepADBPreferences.WEBHOOK_STATUS_DEREGISTERED.equals(
                KeepADBPreferences.getWebhookLastReportStatus(context)), 3000);

        assertEquals(0, transport.getRequestCount());
        assertTrue("The peer registration already replaced the shared cleanup target",
                KeepADBPreferences.getPendingWebhookCleanupUrls(context).isEmpty());
        assertEquals(NEW_URL, KeepADBRegisterClient.getLastRegisteredUsbUrlForTesting());
    }

    @Test
    public void usbPendingCleanupIsDroppedWhenWlanRegistrationIsLiveAtSameUrl() throws Exception {
        configureWebhook(NEW_URL);
        String inactivePayload = KeepADBRegisterClient.buildUsbPayload(
                "dev1", 5, "Office", "10.0.0.5", "h1", "t1", false);
        KeepADBPreferences.addPendingUsbWebhookCleanup(context, NEW_URL, inactivePayload);
        KeepADBRegisterClient.setWlanStateForTesting(NEW_URL, "192.168.1.51:41235");

        KeepADBRegisterClient.unregisterAndDisableAsync(context);

        waitUntil(() -> KeepADBPreferences.WEBHOOK_STATUS_DEREGISTERED.equals(
                KeepADBPreferences.getWebhookLastReportStatus(context)), 3000);

        assertEquals(1, transport.getRequestCount());
        assertEquals("DELETE", transport.recordedRequests.get(0).method);
        assertTrue("The peer registration already replaced the shared cleanup target",
                KeepADBPreferences.getPendingUsbWebhookCleanups(context).isEmpty());
    }

    @Test
    public void wlanCleanupStillDeletesWhenUsbHasNoLiveRegistration() throws Exception {
        configureWebhook(NEW_URL);
        KeepADBRegisterClient.setWlanStateForTesting(NEW_URL, "192.168.1.51:41235");

        KeepADBRegisterClient.markUnavailableAsync(context);

        waitUntil(() -> KeepADBPreferences.WEBHOOK_STATUS_DEREGISTERED.equals(
                KeepADBPreferences.getWebhookLastReportStatus(context)), 3000);

        assertEquals(1, transport.getRequestCount());
        assertEquals("DELETE", transport.recordedRequests.get(0).method);
        assertNull(KeepADBRegisterClient.getLastRegisteredUsbUrlForTesting());
    }

    @Test
    public void usbCleanupStillPostsInactiveWhenWlanHasNoLiveRegistration() throws Exception {
        configureWebhook(NEW_URL);
        KeepADBRegisterClient.setUsbStateForTesting(NEW_URL,
                KeepADBRegisterClient.buildUsbPayload("dev1", 5, "Office", "10.0.0.5", "h1", "t1", true),
                5, "Office", "10.0.0.5", "h1", "t1");

        KeepADBRegisterClient.markUsbInactiveAsyncInternal(context, true, NEW_URL, "dev1");

        waitUntil(() -> KeepADBRegisterClient.getLastRegisteredUsbUrlForTesting() == null, 3000);

        assertEquals(1, transport.getRequestCount());
        assertEquals("POST", transport.recordedRequests.get(0).method);
        assertTrue(transport.recordedRequests.get(0).payload.contains("\"active\":false"));
        assertNull(KeepADBRegisterClient.getLastRegisteredUrlForTesting());
    }

    // ---- Criterion 2: USB failures are recorded and surfaced, like WLAN failures. ----

    @Test
    public void usbRegistrationFailurePersistsFailedStatus() throws Exception {
        assertEquals(KeepADBPreferences.WEBHOOK_STATUS_NEVER,
                KeepADBPreferences.getUsbWebhookLastReportStatus(context));
        transport.setPostSuccess(false);

        KeepADBRegisterClient.updateUsbEndpointAsyncInternal(context, true, NEW_URL, "dev1",
                5, "Office", "10.0.0.5", "h1", "t1");

        waitUntil(() -> KeepADBPreferences.WEBHOOK_STATUS_FAILED.equals(
                KeepADBPreferences.getUsbWebhookLastReportStatus(context)), 3000);
        // The failure must not fabricate a registration.
        assertNull(KeepADBPreferences.getUsbWebhookLastReportedUrl(context));
        assertNull(KeepADBRegisterClient.getLastRegisteredUsbUrlForTesting());
    }

    @Test
    public void usbRegistrationSuccessClearsAnEarlierFailedStatus() throws Exception {
        transport.setPostSuccess(false);
        KeepADBRegisterClient.updateUsbEndpointAsyncInternal(context, true, NEW_URL, "dev1",
                5, "Office", "10.0.0.5", "h1", "t1");
        waitUntil(() -> KeepADBPreferences.WEBHOOK_STATUS_FAILED.equals(
                KeepADBPreferences.getUsbWebhookLastReportStatus(context)), 3000);

        transport.setPostSuccess(true);
        KeepADBRegisterClient.updateUsbEndpointAsyncInternal(context, true, NEW_URL, "dev1",
                5, "Office", "10.0.0.5", "h1", "t1");

        waitUntil(() -> KeepADBPreferences.WEBHOOK_STATUS_SUCCESS.equals(
                KeepADBPreferences.getUsbWebhookLastReportStatus(context)), 3000);
    }

    @Test
    public void usbDeactivationFailurePersistsFailedStatusAndKeepsTheRegistration() throws Exception {
        KeepADBRegisterClient.updateUsbEndpointAsyncInternal(context, true, NEW_URL, "dev1",
                5, "Office", "10.0.0.5", "h1", "t1");
        waitUntil(() -> NEW_URL.equals(KeepADBRegisterClient.getLastRegisteredUsbUrlForTesting()), 3000);
        transport.setPostSuccess(false);

        KeepADBRegisterClient.markUsbInactiveAsyncInternal(context, true, NEW_URL, "dev1");

        waitUntil(() -> KeepADBPreferences.WEBHOOK_STATUS_FAILED.equals(
                KeepADBPreferences.getUsbWebhookLastReportStatus(context)), 3000);
        // The registration is still known, so a later disconnect handling can still clean it up.
        assertEquals(NEW_URL, KeepADBRegisterClient.getLastRegisteredUsbUrlForTesting());
    }

    @Test
    public void usbDeactivationSuccessPersistsDeregisteredStatus() throws Exception {
        KeepADBRegisterClient.updateUsbEndpointAsyncInternal(context, true, NEW_URL, "dev1",
                5, "Office", "10.0.0.5", "h1", "t1");
        waitUntil(() -> NEW_URL.equals(KeepADBRegisterClient.getLastRegisteredUsbUrlForTesting()), 3000);

        KeepADBRegisterClient.markUsbInactiveAsyncInternal(context, true, NEW_URL, "dev1");

        waitUntil(() -> KeepADBPreferences.WEBHOOK_STATUS_DEREGISTERED.equals(
                KeepADBPreferences.getUsbWebhookLastReportStatus(context)), 3000);
        assertNull(KeepADBPreferences.getUsbWebhookLastReportedUrl(context));
    }

    // ---- Criterion 3: the WLAN snapshot is written as one editor transaction. ----

    @Test
    public void wlanSuccessSnapshotIsWrittenAsASingleApply() throws Exception {
        configureWebhook(NEW_URL);
        context.preferences.applyCount.set(0);

        KeepADBRegisterClient.updateEndpointAsync(context, "192.168.1.51", 41235);
        waitUntil(() -> "192.168.1.51:41235".equals(
                KeepADBPreferences.getWebhookLastReportedEndpoint(context)), 3000);
        Thread.sleep(100);

        // Timestamp, URL, endpoint and status used to be four independent apply() calls, so a
        // crash between them could persist a URL without its endpoint.
        assertEquals(1, context.preferences.applyCount.get());
        assertEquals(NEW_URL, KeepADBPreferences.getWebhookLastReportedUrl(context));
        assertEquals("192.168.1.51:41235", KeepADBPreferences.getWebhookLastReportedEndpoint(context));
        assertEquals(KeepADBPreferences.WEBHOOK_STATUS_SUCCESS,
                KeepADBPreferences.getWebhookLastReportStatus(context));
        assertTrue(KeepADBPreferences.getWebhookLastReportedAt(context) > 0L);
    }

    @Test
    public void wlanDeregistrationSnapshotIsWrittenAsASingleApply() throws Exception {
        configureWebhook(NEW_URL);
        KeepADBRegisterClient.updateEndpointAsync(context, "192.168.1.51", 41235);
        waitUntil(() -> "192.168.1.51:41235".equals(
                KeepADBPreferences.getWebhookLastReportedEndpoint(context)), 3000);
        Thread.sleep(100);
        context.preferences.applyCount.set(0);

        KeepADBRegisterClient.markUnavailableAsync(context);
        waitUntil(() -> KeepADBPreferences.WEBHOOK_STATUS_DEREGISTERED.equals(
                KeepADBPreferences.getWebhookLastReportStatus(context)), 3000);
        Thread.sleep(100);

        assertEquals(1, context.preferences.applyCount.get());
        assertNull(KeepADBPreferences.getWebhookLastReportedUrl(context));
        assertNull(KeepADBPreferences.getWebhookLastReportedEndpoint(context));
    }

    @Test
    public void pendingCleanupBacklogIsBounded() {
        for (int i = 0; i < KeepADBPreferences.MAX_PENDING_CLEANUPS + 3; i++) {
            KeepADBPreferences.addPendingWebhookCleanupUrl(context, "http://host" + i + "/register");
        }
        assertEquals(KeepADBPreferences.MAX_PENDING_CLEANUPS,
                KeepADBPreferences.getPendingWebhookCleanupUrls(context).size());

        KeepADBPreferences.addPendingWebhookCleanupUrl(context, null);
        KeepADBPreferences.addPendingWebhookCleanupUrl(context, "  ");
        assertEquals(KeepADBPreferences.MAX_PENDING_CLEANUPS,
                KeepADBPreferences.getPendingWebhookCleanupUrls(context).size());

        KeepADBPreferences.removePendingWebhookCleanupUrl(context, "http://host0/register");
        assertFalse(KeepADBPreferences.getPendingWebhookCleanupUrls(context)
                .contains("http://host0/register"));
    }

    @Test
    public void pendingCleanupBacklogOverflowEvictsTheOldestEntryNotTheNewest() {
        // #368: the oldest entry is the most likely to be a long-dead orphan, the newest is the
        // most likely to still be a live registration -- so overflow must drop the oldest one.
        for (int i = 0; i < KeepADBPreferences.MAX_PENDING_CLEANUPS + 3; i++) {
            KeepADBPreferences.addPendingWebhookCleanupUrl(context, "http://host" + i + "/register");
        }

        Set<String> pending = KeepADBPreferences.getPendingWebhookCleanupUrls(context);
        assertEquals(KeepADBPreferences.MAX_PENDING_CLEANUPS, pending.size());
        for (int i = 0; i < 3; i++) {
            assertFalse("oldest entry host" + i + " must have been evicted",
                    pending.contains("http://host" + i + "/register"));
        }
        for (int i = 3; i < KeepADBPreferences.MAX_PENDING_CLEANUPS + 3; i++) {
            assertTrue("newest entry host" + i + " must be kept",
                    pending.contains("http://host" + i + "/register"));
        }
    }

    /**
     * #414: the FIFO rework of #368 introduced an ordered shadow key that is only ever *read*
     * from the code paths above -- every one of them starts from an empty backlog and therefore
     * never exercises the migration branch in {@code KeepADBPreferences.getPendingCleanups} that
     * fires when the order key is missing but the legacy {@code StringSet} key already holds
     * entries (data written by an app version predating #368, or restored from a backup taken
     * before it). This test writes exactly that pre-#368 shape directly into the fake prefs --
     * legacy StringSet populated, order key absent -- and proves both migration-time behaviour
     * (no entry lost while reconstructing order) and that the very next backlog-bound add still
     * evicts FIFO-correctly off the reconstructed order, not off some corrupted or truncated
     * state.
     */
    @Test
    public void legacyStringSetWithoutOrderKeyMigratesAndEvictsFifoWithoutDataLoss() {
        String legacyKey = "register_webhook_pending_cleanup";
        String orderKey = legacyKey + "_order";
        Set<String> legacyEntries = new java.util.HashSet<>(java.util.Arrays.asList(
                "http://legacy0/register", "http://legacy1/register",
                "http://legacy2/register", "http://legacy3/register"));
        assertEquals("test fixture must match MAX_PENDING_CLEANUPS to exercise eviction",
                KeepADBPreferences.MAX_PENDING_CLEANUPS, legacyEntries.size());

        android.content.SharedPreferences prefs =
                context.getSharedPreferences("keepadb_prefs", android.content.Context.MODE_PRIVATE);
        prefs.edit().putStringSet(legacyKey, legacyEntries).apply();
        assertFalse("test precondition: order key must be absent before migration, "
                        + "otherwise this test does not exercise the pre-#368 migration path",
                prefs.contains(orderKey));

        // Reading triggers best-effort order reconstruction from the Set's iteration order and
        // immediately re-persists it in the new ordered format -- no entry may be lost here.
        Set<String> afterMigrationRead = KeepADBPreferences.getPendingWebhookCleanupUrls(context);
        assertEquals(legacyEntries, afterMigrationRead);

        String persistedOrder = prefs.getString(orderKey, null);
        assertTrue("migration must persist the ordered shadow key so future evictions are "
                        + "FIFO-correct", persistedOrder != null && !persistedOrder.isEmpty());
        String[] reconstructedOrder = persistedOrder.split("", -1);
        assertEquals(KeepADBPreferences.MAX_PENDING_CLEANUPS, reconstructedOrder.length);
        String expectedEvicted = reconstructedOrder[0];

        // Backlog is already at MAX_PENDING_CLEANUPS; one more add must evict the reconstructed
        // oldest entry (FIFO, #368), not silently grow past the bound or drop the newest one.
        String newUrl = "http://new-after-migration/register";
        KeepADBPreferences.addPendingWebhookCleanupUrl(context, newUrl);

        Set<String> finalPending = KeepADBPreferences.getPendingWebhookCleanupUrls(context);
        assertEquals(KeepADBPreferences.MAX_PENDING_CLEANUPS, finalPending.size());
        assertTrue("newly added entry must be present", finalPending.contains(newUrl));
        assertFalse("reconstructed oldest entry must have been evicted",
                finalPending.contains(expectedEvicted));
        for (String entry : legacyEntries) {
            if (!entry.equals(expectedEvicted)) {
                assertTrue("non-evicted legacy entry must survive migration and eviction intact: "
                        + entry, finalPending.contains(entry));
            }
        }
    }

    @Test
    public void unreachablePendingCleanupIsBackedOffBetweenFlushes() {
        KeepADBPreferences.addPendingWebhookCleanupUrl(context, OLD_URL);
        transport.setDeleteSuccess(false);

        KeepADBRegisterClient.setPendingCleanupNowForTesting(1_000L);
        KeepADBRegisterClient.flushPendingCleanupsForTesting(context);
        assertEquals(1, transport.getRequestCount());

        // A subsequent register transaction must not synchronously retry the same unreachable
        // host while its persisted backoff window is still open.
        KeepADBRegisterClient.setPendingCleanupNowForTesting(1_001L);
        KeepADBRegisterClient.flushPendingCleanupsForTesting(context);
        assertEquals(1, transport.getRequestCount());

        KeepADBRegisterClient.setPendingCleanupNowForTesting(
                1_000L + KeepADBRegisterClient.PENDING_CLEANUP_INITIAL_BACKOFF_MS);
        KeepADBRegisterClient.flushPendingCleanupsForTesting(context);
        assertEquals(2, transport.getRequestCount());
        assertTrue(KeepADBPreferences.getPendingWebhookCleanupUrls(context).contains(OLD_URL));
    }

    @Test
    public void unreachablePendingCleanupIsDroppedAfterItsAttemptBudget() {
        KeepADBPreferences.addPendingWebhookCleanupUrl(context, OLD_URL);
        transport.setDeleteSuccess(false);

        KeepADBRegisterClient.setPendingCleanupNowForTesting(1_000L);
        KeepADBRegisterClient.flushPendingCleanupsForTesting(context);
        for (int attempt = 1; attempt < KeepADBRegisterClient.MAX_PENDING_CLEANUP_ATTEMPTS; attempt++) {
            KeepADBRegisterClient.setPendingCleanupNowForTesting(
                    1_000_000L + attempt * KeepADBRegisterClient.PENDING_CLEANUP_MAX_BACKOFF_MS);
            KeepADBRegisterClient.flushPendingCleanupsForTesting(context);
        }

        assertEquals(KeepADBRegisterClient.MAX_PENDING_CLEANUP_ATTEMPTS,
                transport.getRequestCount());
        assertTrue("An unreachable cleanup must not remain in every future transaction",
                KeepADBPreferences.getPendingWebhookCleanupUrls(context).isEmpty());
    }

    @Test
    public void stalePendingCleanupExpiresWithoutAnotherNetworkAttempt() {
        KeepADBPreferences.addPendingWebhookCleanupUrl(context, OLD_URL);
        transport.setDeleteSuccess(false);

        KeepADBRegisterClient.setPendingCleanupNowForTesting(2_000L);
        KeepADBRegisterClient.flushPendingCleanupsForTesting(context);
        KeepADBRegisterClient.setPendingCleanupNowForTesting(
                2_000L + KeepADBRegisterClient.PENDING_CLEANUP_EXPIRY_MS + 1L);
        KeepADBRegisterClient.flushPendingCleanupsForTesting(context);

        assertEquals(1, transport.getRequestCount());
        assertTrue("Expired cleanup must be removed without another DELETE",
                KeepADBPreferences.getPendingWebhookCleanupUrls(context).isEmpty());
    }

    @Test
    public void wlanPendingCleanupWithNewlineIsRemovedFromTheWlanQueueAfterBudget() {
        String malformedLegacyUrl = OLD_URL + "\nlegacy";
        KeepADBPreferences.addPendingWebhookCleanupUrl(context, malformedLegacyUrl);
        transport.setDeleteSuccess(false);

        KeepADBRegisterClient.setPendingCleanupNowForTesting(1_000L);
        KeepADBRegisterClient.flushPendingCleanupsForTesting(context);
        for (int attempt = 1; attempt < KeepADBRegisterClient.MAX_PENDING_CLEANUP_ATTEMPTS; attempt++) {
            KeepADBRegisterClient.setPendingCleanupNowForTesting(
                    1_000_000L + attempt * KeepADBRegisterClient.PENDING_CLEANUP_MAX_BACKOFF_MS);
            KeepADBRegisterClient.flushPendingCleanupsForTesting(context);
        }

        assertTrue("The retry budget must remove malformed WLAN entries from the WLAN queue",
                KeepADBPreferences.getPendingWebhookCleanupUrls(context).isEmpty());
    }

    private void configureWebhook(String url) {
        KeepADBPreferences.setRegisterWebhookUrl(context, url);
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(20);
        }
        assertTrue("condition not met within " + timeoutMs + "ms", condition.getAsBoolean());
    }

    private static final class FakeContext extends android.content.ContextWrapper {
        private final CountingPreferences preferences = new CountingPreferences();

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

    /** In-memory preferences that count how many editor transactions were committed. */
    private static final class CountingPreferences implements android.content.SharedPreferences {
        private final java.util.Map<String, Object> values =
                java.util.Collections.synchronizedMap(new java.util.HashMap<>());
        final AtomicInteger applyCount = new AtomicInteger();

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
            return value instanceof java.util.Set ? (java.util.Set<String>) value : defValues;
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
            return new CountingEditor();
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}

        @Override
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}

        private final class CountingEditor implements Editor {
            private final java.util.Map<String, Object> updates = new java.util.HashMap<>();
            private final java.util.Set<String> removals = new java.util.HashSet<>();
            private boolean clear;

            @Override
            public Editor putString(String key, String value) {
                updates.put(key, value);
                return this;
            }

            @Override
            public Editor putStringSet(String key, java.util.Set<String> value) {
                updates.put(key, value == null ? null : java.util.Set.copyOf(value));
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
                applyCount.incrementAndGet();
                synchronized (values) {
                    if (clear) values.clear();
                    for (String key : removals) values.remove(key);
                    values.putAll(updates);
                }
            }
        }
    }
}
