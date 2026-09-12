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
