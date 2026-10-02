package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Behaviour tests for the register-client lifecycle hardening of issue #317:
 * retryable cleanup of a superseded webhook URL (WLAN), and a WLAN snapshot that reaches the
 * preferences file as a single editor transaction. The preferences fake counts {@code apply()}
 * calls, which is what makes the atomicity claim observable rather than a code-shape assertion.
 *
 * <p>#701 added the persisted retry record ({@link PendingCleanupRetryRepository}) to this
 * picture: its budget, backoff, expiry, restart and legacy-entry behavior with literal numbers
 * (the older tests below use the repository's own constants), the write-ahead of the cleanup to
 * remember and the rule that a cleanup never outlives or overwrites a newer registration.
 *
 * <p>#707 added the retry record's end of life: the record of an entry that a full backlog evicts
 * goes with it, and a sweep on the register executor removes the records of entries that are no
 * longer pending (what an earlier build left behind). Both are driven through the client and its
 * executor here; the sweep's key rules are pinned in {@link PendingCleanupRetryRepositoryTest}.
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
        // Let this test's own register transaction finish before the next test installs its fake
        // transport; otherwise a trailing request lands in the next test's recorded requests.
        KeepADBRegisterClient.awaitIdleForTesting(3000);
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
        configureWebhook(NEW_URL);
        String legacyWlanUrl = "http://admin:secret@legacy.example/register?token=abc";
        KeepADBPreferences.addPendingWebhookCleanupUrl(context, legacyWlanUrl);

        KeepADBRegisterClient.updateEndpointAsync(context, "192.168.1.51", 41235);
        waitUntil(() -> KeepADBPreferences.getPendingWebhookCleanupUrls(context).isEmpty(), 3000);

        KeepADBFakeHttpTransport.Request wlanCleanup = transport.recordedRequests.get(0);
        assertEquals("DELETE", wlanCleanup.method);
        assertEquals("http://legacy.example/register?token=abc", wlanCleanup.url);
        assertFalse(wlanCleanup.url.contains("secret"));
    }

    // ---- Invariant: a pending cleanup never targets the currently registered URL. ----

    @Test
    public void wlanPendingCleanupForTheUrlJustRegisteredIsDropped() throws Exception {
        // A cleanup can survive its own flush: an already-absent record answers a DELETE with 404,
        // which counts as a failure, while the POST that follows in the same transaction succeeds.
        // The entry would then queue a DELETE for the URL that is now live.
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
        assertEquals("the eviction is reported with the entry the reconstructed order dropped (#707)",
                expectedEvicted, KeepADBPreferences.addPendingWebhookCleanupUrl(context, newUrl));

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
                1_000L + PendingCleanupRetryRepository.INITIAL_BACKOFF_MS);
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
        for (int attempt = 1; attempt < PendingCleanupRetryRepository.MAX_ATTEMPTS; attempt++) {
            KeepADBRegisterClient.setPendingCleanupNowForTesting(
                    1_000_000L + attempt * PendingCleanupRetryRepository.MAX_BACKOFF_MS);
            KeepADBRegisterClient.flushPendingCleanupsForTesting(context);
        }

        assertEquals(PendingCleanupRetryRepository.MAX_ATTEMPTS,
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
                2_000L + PendingCleanupRetryRepository.EXPIRY_MS + 1L);
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
        for (int attempt = 1; attempt < PendingCleanupRetryRepository.MAX_ATTEMPTS; attempt++) {
            KeepADBRegisterClient.setPendingCleanupNowForTesting(
                    1_000_000L + attempt * PendingCleanupRetryRepository.MAX_BACKOFF_MS);
            KeepADBRegisterClient.flushPendingCleanupsForTesting(context);
        }

        assertTrue("The retry budget must remove malformed WLAN entries from the WLAN queue",
                KeepADBPreferences.getPendingWebhookCleanupUrls(context).isEmpty());
    }

    // ---- #710: the flush loop must not process snapshot entries that were already cleaned. ----

    @Test
    public void flushSkipsSnapshotEntryAlreadyRemovedByAnotherSpellingOfTheSameResource() {
        String first = "http://a:one@legacy.example/register";
        String second = "http://b:two@legacy.example/register";
        KeepADBPreferences.addPendingWebhookCleanupUrl(context, first);
        KeepADBPreferences.addPendingWebhookCleanupUrl(context, second);
        // The first DELETE succeeds; any further DELETE would fail.
        transport.setRequestCallback(request -> {
            if (transport.getRequestCount() >= 2) transport.setDeleteSuccess(false);
        });

        flushAt(1_000L);

        assertEquals("the second spelling was cleaned with the first, no second DELETE", 1,
                transport.getRequestCount());
        assertTrue(KeepADBPreferences.getPendingWebhookCleanupUrls(context).isEmpty());
        assertTrue("no orphan retry record may remain", retryKeys().isEmpty());
    }

    @Test
    public void flushStillProcessesLaterEntriesOfOtherResources() {
        String sameA = "http://a:one@legacy.example/register";
        String other = "http://other.example/register";
        String sameB = "http://b:two@legacy.example/register";
        KeepADBPreferences.addPendingWebhookCleanupUrl(context, sameA);
        KeepADBPreferences.addPendingWebhookCleanupUrl(context, other);
        KeepADBPreferences.addPendingWebhookCleanupUrl(context, sameB);
        transport.setFailingUrl(other);

        flushAt(1_000L);

        assertEquals("one DELETE for the shared resource plus one for the other resource", 2,
                transport.getRequestCount());
        assertEquals("http://legacy.example/register", transport.recordedRequests.get(0).url);
        assertEquals(other, transport.recordedRequests.get(1).url);
        assertEquals("only the failed, still pending entry remains",
                java.util.Collections.singleton(other),
                new java.util.HashSet<>(KeepADBPreferences.getPendingWebhookCleanupUrls(context)));
        assertEquals("1,31000,86401000", retryRecord(other));
        assertEquals(1, retryKeys().size());
    }

    // ---- #701: the persisted retry record, driven through the client with literal numbers. ----

    private static final String RETRY_KEY_PREFIX = "register_webhook_pending_cleanup_retry_state:";
    private static final long DAY_MS = 86_400_000L;

    @Test
    public void pendingCleanupIsAttemptedThreeTimesWithTheFixedBackoffAndThenDropped() {
        KeepADBPreferences.addPendingWebhookCleanupUrl(context, OLD_URL);
        transport.setDeleteSuccess(false);

        flushAt(1_000L);
        assertEquals(1, transport.getRequestCount());
        assertEquals("1,31000,86401000", retryRecord(OLD_URL));

        flushAt(30_999L);
        assertEquals("the 30s backoff of the first failure is still open", 1,
                transport.getRequestCount());

        flushAt(31_000L);
        assertEquals(2, transport.getRequestCount());
        assertEquals("the second failure doubles the backoff and keeps the expiry",
                "2,91000,86401000", retryRecord(OLD_URL));

        flushAt(90_999L);
        assertEquals("the 60s backoff of the second failure is still open", 2,
                transport.getRequestCount());

        flushAt(91_000L);
        assertEquals("the third attempt uses the budget", 3, transport.getRequestCount());
        assertTrue(KeepADBPreferences.getPendingWebhookCleanupUrls(context).isEmpty());
        assertNull("the dropped entry leaves no retry record behind", retryRecord(OLD_URL));

        flushAt(91_000L + 3_600_000L);
        assertEquals("a dropped entry is never attempted again", 3, transport.getRequestCount());
    }

    @Test
    public void retryRecordAndBudgetSurviveProcessRestarts() {
        KeepADBPreferences.addPendingWebhookCleanupUrl(context, OLD_URL);
        transport.setDeleteSuccess(false);

        flushAt(1_000L);
        restartProcess();
        flushAt(30_999L);
        assertEquals("the backoff is read back from the preferences file after a restart", 1,
                transport.getRequestCount());

        flushAt(31_000L);
        assertEquals(2, transport.getRequestCount());
        restartProcess();
        flushAt(90_999L);
        assertEquals(2, transport.getRequestCount());

        flushAt(91_000L);
        assertEquals("the attempt budget counts across restarts", 3, transport.getRequestCount());
        assertTrue(KeepADBPreferences.getPendingWebhookCleanupUrls(context).isEmpty());
        assertNull(retryRecord(OLD_URL));
    }

    @Test
    public void expiryIsMeasuredFromTheFirstFailureAndDropsTheEntryWithoutAnotherRequest() {
        KeepADBPreferences.addPendingWebhookCleanupUrl(context, OLD_URL);
        transport.setDeleteSuccess(false);

        flushAt(2_000L);
        assertEquals("1,32000,86402000", retryRecord(OLD_URL));

        flushAt(86_401_999L);
        assertEquals("one millisecond before the expiry the entry is still attempted", 2,
                transport.getRequestCount());
        assertEquals("2,86461999,86402000", retryRecord(OLD_URL));

        flushAt(86_402_000L);
        assertEquals("exactly at the expiry the entry is dropped without a request", 2,
                transport.getRequestCount());
        assertTrue(KeepADBPreferences.getPendingWebhookCleanupUrls(context).isEmpty());
        assertNull(retryRecord(OLD_URL));
    }

    /**
     * No clock seam here on purpose: the stored times are absolute wall-clock times. A monotonic
     * clock restarts near zero on every reboot, which would leave a stored 24h expiry unreachable
     * until uptime caught up. A restart in the middle shows the real stored time still gates.
     */
    @Test
    public void persistedRetryTimesAreAbsoluteWallClockTimesThatSurviveARestart() {
        KeepADBPreferences.addPendingWebhookCleanupUrl(context, OLD_URL);
        transport.setDeleteSuccess(false);

        long before = System.currentTimeMillis();
        KeepADBRegisterClient.flushPendingCleanupsForTesting(context);
        long after = System.currentTimeMillis();

        String[] fields = retryRecord(OLD_URL).split(",");
        assertEquals("1", fields[0]);
        long nextAttemptAt = Long.parseLong(fields[1]);
        long expiresAt = Long.parseLong(fields[2]);
        assertTrue("next attempt = wall clock + 30s, was " + nextAttemptAt,
                nextAttemptAt >= before + 30_000L && nextAttemptAt <= after + 30_000L);
        assertTrue("expiry = wall clock + 24h, was " + expiresAt,
                expiresAt >= before + DAY_MS && expiresAt <= after + DAY_MS);

        restartProcess();
        KeepADBRegisterClient.flushPendingCleanupsForTesting(context);
        assertEquals("the stored wall-clock time still gates the retry after a restart", 1,
                transport.getRequestCount());
    }

    @Test
    public void aStoredRecordThatAlreadyUsedItsBudgetIsDroppedWithoutARequest() {
        KeepADBPreferences.addPendingWebhookCleanupUrl(context, OLD_URL);
        // As left behind by a build with a larger budget: three failed attempts, due long ago.
        context.getSharedPreferences("keepadb_prefs", android.content.Context.MODE_PRIVATE).edit()
                .putString(RETRY_KEY_PREFIX + OLD_URL, "3,0," + (5_000L + DAY_MS)).commit();

        flushAt(5_000L);

        assertEquals("a spent budget never buys another request", 0, transport.getRequestCount());
        assertTrue(KeepADBPreferences.getPendingWebhookCleanupUrls(context).isEmpty());
        assertNull(retryRecord(OLD_URL));
    }

    @Test
    public void pendingCleanupFifoKeepsFourEntriesOnItsDocumentedKeys() {
        for (int i = 0; i < 6; i++) {
            KeepADBPreferences.addPendingWebhookCleanupUrl(context, "http://host" + i + "/register");
        }

        assertEquals(4, KeepADBPreferences.getPendingWebhookCleanupUrls(context).size());
        android.content.SharedPreferences prefs =
                context.getSharedPreferences("keepadb_prefs", android.content.Context.MODE_PRIVATE);
        assertEquals(new java.util.HashSet<>(java.util.Arrays.asList("http://host2/register",
                "http://host3/register", "http://host4/register", "http://host5/register")),
                prefs.getStringSet("register_webhook_pending_cleanup", null));
        assertEquals("the oldest two were evicted, the rest keep their insertion order",
                "http://host2/register\u001Dhttp://host3/register\u001D"
                        + "http://host4/register\u001Dhttp://host5/register",
                prefs.getString("register_webhook_pending_cleanup_order", null));
    }

    @Test
    public void legacyCredentialEntryKeepsItsRecordAcrossRestartsAndIsDroppedWithIt() {
        String legacy = "http://admin:secret@legacy.example/register?token=abc";
        KeepADBPreferences.addPendingWebhookCleanupUrl(context, legacy);
        transport.setDeleteSuccess(false);

        flushAt(1_000L);
        assertEquals("the record belongs to the stored raw entry", "1,31000,86401000",
                retryRecord(legacy));

        restartProcess();
        flushAt(31_000L);
        restartProcess();
        flushAt(91_000L);

        assertEquals(3, transport.getRequestCount());
        for (KeepADBFakeHttpTransport.Request request : transport.recordedRequests) {
            assertEquals("DELETE", request.method);
            assertEquals("http://legacy.example/register?token=abc", request.url);
            assertFalse(request.url.contains("secret") || request.url.contains("admin"));
        }
        assertTrue("the raw entry is dropped with the exhausted budget",
                KeepADBPreferences.getPendingWebhookCleanupUrls(context).isEmpty());
        assertTrue("and its retry record with it: " + retryKeys(), retryKeys().isEmpty());
    }

    @Test
    public void aSuccessfulRetryRemovesTheRawEntryAndItsRetryRecord() {
        String legacy = "http://admin:secret@legacy.example/register?token=abc";
        KeepADBPreferences.addPendingWebhookCleanupUrl(context, legacy);
        transport.setDeleteSuccess(false);
        flushAt(1_000L);
        assertEquals(1, retryKeys().size());

        transport.setDeleteSuccess(true);
        flushAt(31_000L);

        assertEquals(2, transport.getRequestCount());
        assertTrue(KeepADBPreferences.getPendingWebhookCleanupUrls(context).isEmpty());
        assertTrue("a delivered cleanup leaves no retry record: " + retryKeys(),
                retryKeys().isEmpty());
    }

    // ---- #701: a cleanup never outlives or overwrites a newer registration. ----

    @Test
    public void aNewRegistrationDropsEveryObsoleteSpellingOfItsResourceAndOnlyThose()
            throws Exception {
        configureWebhook(NEW_URL);
        String sameResource = "HTTP://New.Example:80/register/";
        String[] otherResources = {"http://new.example/register2", "http://new.example:8080/register",
                "http://other.example/register"};
        KeepADBPreferences.addPendingWebhookCleanupUrl(context, sameResource);
        for (String other : otherResources) {
            KeepADBPreferences.addPendingWebhookCleanupUrl(context, other);
        }
        transport.setDeleteSuccess(false);

        KeepADBRegisterClient.updateEndpointAsync(context, "192.168.1.51", 41235);
        waitUntil(() -> NEW_URL.equals(KeepADBPreferences.getWebhookLastReportedUrl(context)), 3000);
        Thread.sleep(100);

        Set<String> pending = KeepADBPreferences.getPendingWebhookCleanupUrls(context);
        assertFalse("the spelling of the resource just registered is obsolete: " + pending,
                pending.contains(sameResource));
        assertEquals("another path, port or host is another resource and stays queued: " + pending,
                new java.util.HashSet<>(java.util.Arrays.asList(otherResources)), pending);
        assertFalse("the obsolete entry leaves no retry record: " + retryKeys(),
                retryKeys().contains(RETRY_KEY_PREFIX + sameResource));
        assertEquals("each entry that stays queued keeps exactly its own record", 3,
                retryKeys().size());
    }

    @Test
    public void aFlushNeverDeletesAResourceThatIsLiveAndDropsItsRetryRecord() {
        String sameResource = "http://new.example:80/register/";
        KeepADBPreferences.addPendingWebhookCleanupUrl(context, sameResource);
        transport.setDeleteSuccess(false);
        flushAt(1_000L);
        assertEquals("while it is not live the cleanup is attempted", 1, transport.getRequestCount());
        assertEquals(1, retryKeys().size());

        KeepADBRegisterClient.setWlanStateForTesting(NEW_URL, "192.168.1.51:41235");
        transport.setDeleteSuccess(true);
        flushAt(31_000L);

        assertEquals("a live registration is never deleted by an older cleanup", 1,
                transport.getRequestCount());
        assertTrue(KeepADBPreferences.getPendingWebhookCleanupUrls(context).isEmpty());
        assertTrue("and the obsolete record goes with it: " + retryKeys(), retryKeys().isEmpty());
        assertEquals(NEW_URL, KeepADBRegisterClient.getLastRegisteredUrlForTesting());
    }

    @Test
    public void aSuccessfulDeleteOfAResourceDropsTheObsoleteCleanupsOfThatResource()
            throws Exception {
        configureWebhook(NEW_URL);
        String sameResource = "HTTP://New.Example:80/register/";
        KeepADBPreferences.addPendingWebhookCleanupUrl(context, sameResource);
        // Only the queued spelling stays unreachable; the DELETE of the webhook URL itself works.
        transport.setFailingUrl("http://New.Example:80/register/");

        KeepADBRegisterClient.unregisterAndDisableAsync(context);
        waitUntil(() -> KeepADBPreferences.WEBHOOK_STATUS_DEREGISTERED.equals(
                KeepADBPreferences.getWebhookLastReportStatus(context)), 3000);
        Thread.sleep(100);

        assertEquals("the queued spelling is attempted first and fails, then the URL is deleted",
                2, transport.getRequestCount());
        assertEquals(NEW_URL, transport.getLastRequest().url);
        assertTrue("the resource is gone, so its queued cleanup is obsolete: "
                        + KeepADBPreferences.getPendingWebhookCleanupUrls(context),
                KeepADBPreferences.getPendingWebhookCleanupUrls(context).isEmpty());
        assertTrue("and so is its retry record: " + retryKeys(), retryKeys().isEmpty());
    }

    /**
     * Write-ahead: when the report snapshot moves on to the new URL, the cleanup of the old one is
     * already in the preferences file, so a crash between the two writes cannot forget it. The
     * fake sees each editor transaction just before it is applied.
     */
    @Test
    public void cleanupToRememberIsPersistedBeforeTheReportSnapshotMovesOn() throws Exception {
        configureWebhook(NEW_URL);
        KeepADBRegisterClient.setWlanStateForTesting(OLD_URL, "192.168.1.50:41234");
        transport.setDeleteSuccess(false);
        AtomicReference<Boolean> rememberedBeforeSnapshot = new AtomicReference<>();
        context.preferences.beforeApply = (changedKeys, before) -> {
            if (changedKeys.contains("register_webhook_last_url")) {
                Object stored = before.get("register_webhook_pending_cleanup");
                rememberedBeforeSnapshot.set(
                        stored instanceof Set && ((Set<?>) stored).contains(OLD_URL));
            }
        };

        KeepADBRegisterClient.updateEndpointAsync(context, "192.168.1.51", 41235);
        waitUntil(() -> rememberedBeforeSnapshot.get() != null, 3000);

        assertTrue("the old URL must already be queued when the snapshot is written",
                rememberedBeforeSnapshot.get());
    }

    // ---- #707: the end of life of a retry record. ----

    private static final String[] FULL_FIFO = {"http://e0.example/register",
            "http://e1.example/register", "http://e2.example/register", "http://e3.example/register"};
    /** Two failed attempts, not due before 91 s: a flush at 1 s neither attempts nor drops it. */
    private static final String SPENT_TWICE = "2,91000,86401000";

    @Test
    public void addingToAFullBacklogReportsTheEntryItEvictedAndOtherwiseNothing() {
        assertEquals(KeepADBPreferences.MAX_PENDING_CLEANUPS, FULL_FIFO.length);
        for (String entry : FULL_FIFO) {
            assertNull("a free slot evicts nothing", KeepADBPreferences.addPendingWebhookCleanupUrl(context, entry));
        }
        assertNull("an entry that is queued already changes nothing",
                KeepADBPreferences.addPendingWebhookCleanupUrl(context, FULL_FIFO[2]));
        assertNull(KeepADBPreferences.addPendingWebhookCleanupUrl(context, null));
        assertNull(KeepADBPreferences.addPendingWebhookCleanupUrl(context, "  "));
        assertEquals(Arrays.asList(FULL_FIFO),
                new ArrayList<>(KeepADBPreferences.getPendingWebhookCleanupUrls(context)));

        assertEquals("the OLDEST entry makes room, never the new one", FULL_FIFO[0],
                KeepADBPreferences.addPendingWebhookCleanupUrl(context, "http://e4.example/register"));
        assertEquals(FULL_FIFO[1],
                KeepADBPreferences.addPendingWebhookCleanupUrl(context, "http://e5.example/register"));
        assertEquals(Arrays.asList(FULL_FIFO[2], FULL_FIFO[3], "http://e4.example/register",
                "http://e5.example/register"),
                new ArrayList<>(KeepADBPreferences.getPendingWebhookCleanupUrls(context)));
    }

    @Test
    public void anEvictedEntryLosesItsRetryRecordAndOnlyThatOne() throws Exception {
        configureWebhook(NEW_URL);
        fillTheBacklogWithSpentRecords();

        runTheEvictingTransaction();

        assertEquals("the oldest entry made room for the old URL",
                Arrays.asList(FULL_FIFO[1], FULL_FIFO[2], FULL_FIFO[3], OLD_URL),
                new ArrayList<>(KeepADBPreferences.getPendingWebhookCleanupUrls(context)));
        assertNull("the evicted entry is gone for good, its record must not outlive it",
                retryRecord(FULL_FIFO[0]));
        for (int i = 1; i < FULL_FIFO.length; i++) {
            assertEquals("an entry that stays queued keeps its record: " + FULL_FIFO[i], SPENT_TWICE,
                    retryRecord(FULL_FIFO[i]));
        }
        assertNull("the entry that was just queued has not failed yet", retryRecord(OLD_URL));
        assertEquals("exactly the three records of the entries that stay: " + retryKeys(), 3,
                retryKeys().size());
        assertEquals("evicting is local bookkeeping: the old URL's DELETE and the new POST only", 2,
                transport.getRequestCount());
    }

    @Test
    public void anEvictedLegacyEntryWithCredentialsLosesItsRecordUnderItsRawKey() throws Exception {
        configureWebhook(NEW_URL);
        String legacy = "http://admin:secret@legacy.example/register?token=abc";
        KeepADBPreferences.addPendingWebhookCleanupUrl(context, legacy);
        plantRecord(legacy, SPENT_TWICE);
        for (int i = 1; i < FULL_FIFO.length; i++) {
            KeepADBPreferences.addPendingWebhookCleanupUrl(context, FULL_FIFO[i]);
            plantRecord(FULL_FIFO[i], SPENT_TWICE);
        }

        runTheEvictingTransaction();

        assertFalse(KeepADBPreferences.getPendingWebhookCleanupUrls(context).contains(legacy));
        assertNull("the record belongs to the raw stored entry, not to its canonical resource",
                retryRecord(legacy));
        assertNull(retryRecord("http://legacy.example/register?token=abc"));
        assertEquals(3, retryKeys().size());
    }

    /**
     * Acceptance of #707 on the record alone: no flush runs between the eviction and the entry being
     * queued again, so only the removal at eviction can make the record fresh.
     */
    @Test
    public void anEntryQueuedAgainRightAfterItsEvictionStartsWithAFreshRecord() throws Exception {
        configureWebhook(NEW_URL);
        fillTheBacklogWithSpentRecords();
        runTheEvictingTransaction();
        KeepADBPreferences.removePendingWebhookCleanupUrl(context, OLD_URL);

        assertNull(KeepADBPreferences.addPendingWebhookCleanupUrl(context, FULL_FIFO[0]));

        PendingCleanupRetryRepository.RetryState state =
                PendingCleanupRetryRepository.read(context, FULL_FIFO[0], 5_000L);
        assertEquals("no attempt inherited from the entry's earlier life", 0, state.attempts);
        assertEquals("due immediately", 5_000L, state.nextAttemptAt);
        assertEquals("and a new 24h expiry", 5_000L + DAY_MS, state.expiresAt);
    }

    /**
     * The same acceptance through the client only: the entry is queued again by a later transaction,
     * which flushes first, and is then attempted like any new cleanup. This path is covered twice --
     * by the removal at eviction and by the sweep at that flush -- so it only fails when both are
     * missing; each of them is pinned on its own by the tests around it.
     */
    @Test
    public void anEntryQueuedAgainByALaterTransactionIsAttemptedWithAFreshRecord() throws Exception {
        configureWebhook(NEW_URL);
        fillTheBacklogWithSpentRecords();
        runTheEvictingTransaction();
        assertEquals(2, transport.getRequestCount());

        // The evicted URL was registered once more and is replaced again, unreachable again.
        KeepADBRegisterClient.setWlanStateForTesting(FULL_FIFO[0], "192.168.1.52:41236");
        configureWebhook("http://newer.example/register");
        KeepADBRegisterClient.updateEndpointAsync(context, "192.168.1.52", 41237);
        waitUntil(() -> "http://newer.example/register".equals(
                KeepADBPreferences.getWebhookLastReportedUrl(context)), 3000);
        KeepADBRegisterClient.awaitIdleForTesting(3000);

        assertTrue("queued again", KeepADBPreferences.getPendingWebhookCleanupUrls(context)
                .contains(FULL_FIFO[0]));
        assertNull("without the record of its earlier life", retryRecord(FULL_FIFO[0]));

        transport.clearRequests();
        flushAt(2_000L);
        assertEquals("a fresh record is due at once, an inherited one would wait until 91 s", 1,
                countDeletes(FULL_FIFO[0]));
        assertEquals("one failed attempt, expiring 24h after this flush, not after the old one",
                "1,32000,86402000", retryRecord(FULL_FIFO[0]));
    }

    /**
     * The other side of the two tests above (#711, review of #707, O2): queueing a cleanup that is
     * pending already evicts nothing, so the commit block of the client must leave its retry record
     * alone. The backlog is not full and the old URL is queued again by the commit block.
     *
     * <p>The flush in front of every transaction drops a pending entry whose URL is the live
     * registration, so a transaction does not meet this state by itself. The test creates it the
     * only way the single register executor allows: during the flush (a DELETE of another entry),
     * the old URL becomes the live registration and is queued with a spent record, which is the
     * state a transaction would see if the preferences were changed between its flush and its
     * commit.
     */
    @Test
    public void aCleanupQueuedAgainWithoutAnEvictionKeepsItsSpentRetryRecord() throws Exception {
        configureWebhook(NEW_URL);
        KeepADBPreferences.addPendingWebhookCleanupUrl(context, FULL_FIFO[0]);
        plantRecord(FULL_FIFO[0], SPENT_TWICE);
        KeepADBPreferences.addPendingWebhookCleanupUrl(context, FULL_FIFO[1]);
        KeepADBRegisterClient.setPendingCleanupNowForTesting(1_000L);
        transport.setDeleteSuccess(false);
        AtomicInteger stateChanges = new AtomicInteger();
        transport.setRequestCallback(req -> {
            if ("DELETE".equals(req.method) && FULL_FIFO[1].equals(req.url)
                    && stateChanges.getAndIncrement() == 0) {
                KeepADBRegisterClient.setWlanStateForTesting(OLD_URL, "192.168.1.50:41234");
                KeepADBPreferences.addPendingWebhookCleanupUrl(context, OLD_URL);
                plantRecord(OLD_URL, SPENT_TWICE);
            }
        });

        KeepADBRegisterClient.updateEndpointAsync(context, "192.168.1.51", 41235);
        waitUntil(() -> NEW_URL.equals(KeepADBPreferences.getWebhookLastReportedUrl(context)), 3000);
        KeepADBRegisterClient.awaitIdleForTesting(3000);

        assertEquals("the fixture ran: the flush attempted the entry that was due", 1,
                stateChanges.get());
        assertEquals("the commit block ran", "192.168.1.51:41235",
                KeepADBPreferences.getWebhookLastReportedEndpoint(context));
        assertEquals("the old URL's own cleanup failed in the transaction, not in the flush", 1,
                countDeletes(OLD_URL));
        assertEquals("queued again, nothing evicted, the order is unchanged",
                Arrays.asList(FULL_FIFO[0], FULL_FIFO[1], OLD_URL),
                new ArrayList<>(KeepADBPreferences.getPendingWebhookCleanupUrls(context)));
        assertTrue("the backlog had room, so there was nothing to evict",
                KeepADBPreferences.getPendingWebhookCleanupUrls(context).size()
                        < KeepADBPreferences.MAX_PENDING_CLEANUPS);
        assertEquals("attempts, nextAttemptAt and expiresAt of the queued-again entry are kept",
                SPENT_TWICE, retryRecord(OLD_URL));
        assertEquals("a neighbour that was not touched keeps its record as well", SPENT_TWICE,
                retryRecord(FULL_FIFO[0]));
    }

    /**
     * The failure side of the commit path (#718, follow-up to #711): when the replacement POST
     * fails, the commit block does not run, so an entry that is pending already keeps its queue
     * position and its spent retry record ({@code attempts}, {@code nextAttemptAt}, {@code expiresAt}), and the old
     * URL's cleanup ({@code cleanupToRemember}) is neither removed nor reset. The fixture has
     * queued that entry already, so a bare re-queueing is a no-op here (see below).
     *
     * <p>The state is built the same way as in the success test above, through the request
     * callback during the flush, because a normal transaction cannot reach it
     * ({@code hasLiveRegistrationAtUrl} in the flush drops such an entry first).
     *
     * <p>Which violation would stay green: one that only touches the success branch (that is
     * the test above), and one that changes an entry other than the queued-again old URL and
     * the untouched neighbour, or that changes the retry record only after this test's last
     * assertion (e.g. a later flush). Removing the record of {@code cleanupToRemember} in the
     * failure branch turns this test red, and so does queueing it again combined with removing
     * the record. A bare {@code addPendingWebhookCleanupUrl(cleanupToRemember)} stays green: the
     * entry is already queued, so the add is idempotent. Its harmful case (entry not queued,
     * backlog full, oldest entry evicted) is not reachable through this fixture.
     */
    @Test
    public void aFailedReplacementPostLeavesTheQueuedCleanupAndItsSpentRetryRecordAlone()
            throws Exception {
        configureWebhook(NEW_URL);
        KeepADBPreferences.addPendingWebhookCleanupUrl(context, FULL_FIFO[0]);
        plantRecord(FULL_FIFO[0], SPENT_TWICE);
        KeepADBPreferences.addPendingWebhookCleanupUrl(context, FULL_FIFO[1]);
        KeepADBRegisterClient.setPendingCleanupNowForTesting(1_000L);
        transport.setDeleteSuccess(false);
        transport.setPostSuccess(false);
        AtomicInteger stateChanges = new AtomicInteger();
        transport.setRequestCallback(req -> {
            if ("DELETE".equals(req.method) && FULL_FIFO[1].equals(req.url)
                    && stateChanges.getAndIncrement() == 0) {
                KeepADBRegisterClient.setWlanStateForTesting(OLD_URL, "192.168.1.50:41234");
                KeepADBPreferences.addPendingWebhookCleanupUrl(context, OLD_URL);
                plantRecord(OLD_URL, SPENT_TWICE);
            }
        });

        KeepADBRegisterClient.updateEndpointAsync(context, "192.168.1.51", 41235);
        waitUntil(() -> KeepADBPreferences.WEBHOOK_STATUS_FAILED.equals(
                KeepADBPreferences.getWebhookLastReportStatus(context)), 3000);
        KeepADBRegisterClient.awaitIdleForTesting(3000);

        assertEquals("the fixture ran", 1, stateChanges.get());
        assertEquals("the replacement POST was attempted and failed",
                KeepADBPreferences.WEBHOOK_STATUS_FAILED,
                KeepADBPreferences.getWebhookLastReportStatus(context));
        assertNotEquals("the commit block did not run", "192.168.1.51:41235",
                KeepADBPreferences.getWebhookLastReportedEndpoint(context));
        assertEquals("the old URL's own cleanup failed in the transaction", 1,
                countDeletes(OLD_URL));
        assertEquals("nothing was queued again or removed, the order is unchanged",
                Arrays.asList(FULL_FIFO[0], FULL_FIFO[1], OLD_URL),
                new ArrayList<>(KeepADBPreferences.getPendingWebhookCleanupUrls(context)));
        assertEquals("attempts, nextAttemptAt and expiresAt of the queued entry are kept",
                SPENT_TWICE, retryRecord(OLD_URL));
        assertEquals("a neighbour keeps its record as well", SPENT_TWICE,
                retryRecord(FULL_FIFO[0]));
    }

    /**
     * The generation guard of the commit block covers the eviction as well: a superseded update
     * neither queues its cleanup nor evicts anything (that is {@code
     * KeepADBRegisterClientTest#testUpdateSupersededDuringItsSecondaryTransportsDoesNotEvictOrQueue}
     * for the commit block itself); here the check after the primary POST.
     */
    @Test
    public void anUpdateSupersededDuringItsPostEvictsNothing() throws Exception {
        configureWebhook(NEW_URL);
        fillTheBacklogWithSpentRecords();
        KeepADBRegisterClient.setPendingCleanupNowForTesting(1_000L);
        KeepADBRegisterClient.setWlanStateForTesting(OLD_URL, "192.168.1.50:41234");
        transport.setDeleteSuccess(false);
        transport.setRequestCallback(req -> {
            if ("POST".equals(req.method)) KeepADBRegisterClient.bumpOpGenerationForTesting();
        });

        KeepADBRegisterClient.updateEndpointAsync(context, "192.168.1.51", 41235);
        KeepADBRegisterClient.awaitIdleForTesting(3000);

        assertEquals("the POST was sent, then the operation noticed it was superseded", "POST",
                transport.getLastRequest().method);
        assertEquals("nothing was queued and so nothing was evicted", Arrays.asList(FULL_FIFO),
                new ArrayList<>(KeepADBPreferences.getPendingWebhookCleanupUrls(context)));
        for (String entry : FULL_FIFO) {
            assertEquals(SPENT_TWICE, retryRecord(entry));
        }
    }

    @Test
    public void aFlushSweepsTheRecordsOfEntriesThatAreNoLongerPendingAndNothingElse() {
        KeepADBPreferences.addPendingWebhookCleanupUrl(context, FULL_FIFO[1]);
        plantRecord(FULL_FIFO[1], SPENT_TWICE);
        plantRecord(FULL_FIFO[0], "1,31000,86401000");
        plantRecord("http://admin:secret@legacy.example/register?token=abc", "garbage");
        plantRecord("", "no entry at all");
        android.content.SharedPreferences prefs =
                context.getSharedPreferences("keepadb_prefs", android.content.Context.MODE_PRIVATE);
        java.util.Map<String, Object> nonRecordKeysBefore = nonRecordKeys();
        List<String> pendingBefore = new ArrayList<>(KeepADBPreferences.getPendingWebhookCleanupUrls(context));

        flushAt(1_000L);

        assertEquals("only the record of the entry that is still pending stays: " + retryKeys(),
                java.util.Collections.singleton(RETRY_KEY_PREFIX + FULL_FIFO[1]), retryKeys());
        assertEquals(SPENT_TWICE, retryRecord(FULL_FIFO[1]));
        assertEquals("the sweep sends nothing", 0, transport.getRequestCount());
        assertEquals("and changes no entry", pendingBefore,
                new ArrayList<>(KeepADBPreferences.getPendingWebhookCleanupUrls(context)));
        assertEquals("nor any other preference", nonRecordKeysBefore, nonRecordKeys());
        assertEquals(String.join("\u001D", pendingBefore),
                prefs.getString("register_webhook_pending_cleanup_order", null));
    }

    /**
     * Records are orphaned by evictions, and an eviction only happens on a backlog that is at its
     * cap of four: that is where an earlier build leaves them. The sweep must not depend on how
     * full the backlog is, and must leave the four records of the pending entries alone.
     */
    @Test
    public void aFlushSweepsTheOrphansOfAFullBacklogToo() {
        fillTheBacklogWithSpentRecords();
        plantRecord("http://evicted-earlier-a.example/register", SPENT_TWICE);
        plantRecord("http://evicted-earlier-b.example/register", "garbage");

        flushAt(1_000L);

        assertEquals("the backlog is untouched", Arrays.asList(FULL_FIFO),
                new ArrayList<>(KeepADBPreferences.getPendingWebhookCleanupUrls(context)));
        assertEquals("exactly the four records of the pending entries stay: " + retryKeys(),
                FULL_FIFO.length, retryKeys().size());
        for (String entry : FULL_FIFO) {
            assertEquals("a pending entry keeps its record: " + entry, SPENT_TWICE,
                    retryRecord(entry));
        }
        assertEquals("the sweep sends nothing", 0, transport.getRequestCount());
    }

    @Test
    public void aFlushKeepsTheRecordsOfLegacyEntriesThatHaveNoOrderKeyYet() {
        String legacyKey = "register_webhook_pending_cleanup";
        String credentials = "http://admin:secret@legacy.example/register?token=abc";
        android.content.SharedPreferences prefs =
                context.getSharedPreferences("keepadb_prefs", android.content.Context.MODE_PRIVATE);
        prefs.edit().putStringSet(legacyKey,
                new java.util.HashSet<>(Arrays.asList(credentials, FULL_FIFO[1]))).apply();
        assertFalse("test precondition: the pre-#368 shape has no order key",
                prefs.contains(legacyKey + "_order"));
        plantRecord(credentials, SPENT_TWICE);
        plantRecord(FULL_FIFO[1], SPENT_TWICE);
        plantRecord(FULL_FIFO[0], SPENT_TWICE);

        flushAt(1_000L);

        assertEquals("the legacy entries are active, only the dead one goes: " + retryKeys(),
                new java.util.TreeSet<>(Arrays.asList(RETRY_KEY_PREFIX + credentials,
                        RETRY_KEY_PREFIX + FULL_FIFO[1])),
                retryKeys());
        assertEquals(new java.util.HashSet<>(Arrays.asList(credentials, FULL_FIFO[1])),
                KeepADBPreferences.getPendingWebhookCleanupUrls(context));
        assertEquals(0, transport.getRequestCount());
    }

    @Test
    public void aFlushWithAnEmptyBacklogLeavesNoRetryRecordBehind() {
        plantRecord(FULL_FIFO[0], SPENT_TWICE);
        plantRecord(FULL_FIFO[1], "garbage");
        assertTrue(KeepADBPreferences.getPendingWebhookCleanupUrls(context).isEmpty());

        flushAt(1_000L);

        assertTrue("without a pending entry every record is dead: " + retryKeys(),
                retryKeys().isEmpty());
        assertTrue(KeepADBPreferences.getPendingWebhookCleanupUrls(context).isEmpty());
        assertEquals(0, transport.getRequestCount());
    }

    @Test
    public void anUpdateTransactionSweepsOnTheExecutorBeforeItDoesAnythingElse() throws Exception {
        configureWebhook(NEW_URL);
        KeepADBPreferences.addPendingWebhookCleanupUrl(context, FULL_FIFO[1]);
        plantRecord(FULL_FIFO[1], SPENT_TWICE);
        plantRecord(FULL_FIFO[0], SPENT_TWICE);
        KeepADBRegisterClient.setPendingCleanupNowForTesting(1_000L);

        KeepADBRegisterClient.updateEndpointAsync(context, "192.168.1.51", 41235);
        waitUntil(() -> NEW_URL.equals(KeepADBPreferences.getWebhookLastReportedUrl(context)), 3000);
        KeepADBRegisterClient.awaitIdleForTesting(3000);

        assertEquals(java.util.Collections.singleton(RETRY_KEY_PREFIX + FULL_FIFO[1]), retryKeys());
    }

    @Test
    public void aDeleteTransactionSweepsOnTheExecutorBeforeItDoesAnythingElse() throws Exception {
        configureWebhook(NEW_URL);
        KeepADBPreferences.addPendingWebhookCleanupUrl(context, FULL_FIFO[1]);
        plantRecord(FULL_FIFO[1], SPENT_TWICE);
        plantRecord(FULL_FIFO[0], SPENT_TWICE);
        KeepADBRegisterClient.setPendingCleanupNowForTesting(1_000L);

        KeepADBRegisterClient.unregisterAndDisableAsync(context);
        waitUntil(() -> KeepADBPreferences.WEBHOOK_STATUS_DEREGISTERED.equals(
                KeepADBPreferences.getWebhookLastReportStatus(context)), 3000);
        KeepADBRegisterClient.awaitIdleForTesting(3000);

        assertEquals(java.util.Collections.singleton(RETRY_KEY_PREFIX + FULL_FIFO[1]), retryKeys());
    }

    /**
     * Only the register executor sweeps. The caller of an entry point returns at once with the work
     * queued behind a running transaction, and must not have touched the preferences file; the
     * queued transaction sweeps when it starts.
     */
    @Test
    public void theSweepRunsOnTheRegisterExecutorNeverOnTheCaller() throws Exception {
        configureWebhook(NEW_URL);
        CountDownLatch firstPostRunning = new CountDownLatch(1);
        CountDownLatch releaseFirstPost = new CountDownLatch(1);
        transport.setRequestCallback(req -> {
            if (!"POST".equals(req.method) || firstPostRunning.getCount() == 0) return;
            firstPostRunning.countDown();
            try {
                releaseFirstPost.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        KeepADBRegisterClient.updateEndpointAsync(context, "192.168.1.51", 41235);
        assertTrue("the first transaction is inside its POST on the executor",
                firstPostRunning.await(3, TimeUnit.SECONDS));
        plantRecord(FULL_FIFO[0], SPENT_TWICE);
        try {
            KeepADBRegisterClient.updateEndpointAsync(context, "192.168.1.52", 41236);
            assertEquals("the caller returned and has swept nothing: the executor is busy and owns "
                    + "the sweep", SPENT_TWICE, retryRecord(FULL_FIFO[0]));
        } finally {
            releaseFirstPost.countDown();
        }
        KeepADBRegisterClient.awaitIdleForTesting(3000);

        assertNull("the queued transaction swept when it started", retryRecord(FULL_FIFO[0]));
    }

    /**
     * The sweep is no commit work: it must not wait for the class monitor. The test holds the
     * monitor itself while a transaction is queued; the executor reaches the monitor only after its
     * flush, so a sweep that needs the monitor (or runs inside a commit block) would not have run
     * by the time the monitor is released.
     */
    @Test
    public void theDeleteTransactionSweepsWithoutTheClassMonitor() throws Exception {
        configureWebhook(NEW_URL);
        plantRecord(FULL_FIFO[0], SPENT_TWICE);

        boolean sweptWhileTheMonitorWasHeld;
        synchronized (KeepADBRegisterClient.class) {
            KeepADBRegisterClient.unregisterAndDisableAsync(context);
            sweptWhileTheMonitorWasHeld = waitForRecordToDisappear(FULL_FIFO[0], 2000);
        }
        KeepADBRegisterClient.awaitIdleForTesting(3000);

        assertTrue("the executor swept while this thread still held the class monitor",
                sweptWhileTheMonitorWasHeld);
    }

    @Test
    public void theUpdateTransactionSweepsWithoutTheClassMonitor() throws Exception {
        configureWebhook(NEW_URL);
        plantRecord(FULL_FIFO[0], SPENT_TWICE);

        boolean sweptWhileTheMonitorWasHeld;
        synchronized (KeepADBRegisterClient.class) {
            KeepADBRegisterClient.updateEndpointAsync(context, "192.168.1.51", 41235);
            sweptWhileTheMonitorWasHeld = waitForRecordToDisappear(FULL_FIFO[0], 2000);
        }
        KeepADBRegisterClient.awaitIdleForTesting(3000);

        assertTrue("the executor swept while this thread still held the class monitor",
                sweptWhileTheMonitorWasHeld);
    }

    // ---- helpers of the #707 tests ----

    private void fillTheBacklogWithSpentRecords() {
        for (String entry : FULL_FIFO) {
            KeepADBPreferences.addPendingWebhookCleanupUrl(context, entry);
            plantRecord(entry, SPENT_TWICE);
        }
        assertEquals(KeepADBPreferences.MAX_PENDING_CLEANUPS,
                KeepADBPreferences.getPendingWebhookCleanupUrls(context).size());
    }

    /**
     * Replaces {@link #OLD_URL} by {@link #NEW_URL} while the old one is unreachable and the
     * backlog is full: the old URL is queued as a cleanup and evicts the oldest entry. At 1 s every
     * entry of {@link #SPENT_TWICE} is neither due nor expired, so the flush in front of the update
     * changes none of them.
     */
    private void runTheEvictingTransaction() throws Exception {
        KeepADBRegisterClient.setPendingCleanupNowForTesting(1_000L);
        KeepADBRegisterClient.setWlanStateForTesting(OLD_URL, "192.168.1.50:41234");
        transport.setDeleteSuccess(false);
        KeepADBRegisterClient.updateEndpointAsync(context, "192.168.1.51", 41235);
        waitUntil(() -> NEW_URL.equals(KeepADBPreferences.getWebhookLastReportedUrl(context)), 3000);
        KeepADBRegisterClient.awaitIdleForTesting(3000);
    }

    private void plantRecord(String entry, String value) {
        context.getSharedPreferences("keepadb_prefs", android.content.Context.MODE_PRIVATE).edit()
                .putString(RETRY_KEY_PREFIX + entry, value).commit();
    }

    private int countDeletes(String url) {
        int count = 0;
        synchronized (transport.recordedRequests) {
            for (KeepADBFakeHttpTransport.Request request : transport.recordedRequests) {
                if ("DELETE".equals(request.method) && url.equals(request.url)) count++;
            }
        }
        return count;
    }

    private java.util.Map<String, Object> nonRecordKeys() {
        java.util.Map<String, Object> others = new java.util.TreeMap<>();
        for (java.util.Map.Entry<String, ?> stored : context.getSharedPreferences("keepadb_prefs",
                android.content.Context.MODE_PRIVATE).getAll().entrySet()) {
            if (!stored.getKey().startsWith(RETRY_KEY_PREFIX)) others.put(stored.getKey(), stored.getValue());
        }
        return others;
    }

    private boolean waitForRecordToDisappear(String entry, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (retryRecord(entry) == null) return true;
            Thread.sleep(10);
        }
        return retryRecord(entry) == null;
    }

    private void flushAt(long now) {
        KeepADBRegisterClient.setPendingCleanupNowForTesting(now);
        KeepADBRegisterClient.flushPendingCleanupsForTesting(context);
    }

    /** A new process: all static client state is gone, the preferences file and the server stay. */
    private void restartProcess() {
        KeepADBRegisterClient.resetForTesting();
        KeepADBRegisterClient.setHttpTransport(transport);
    }

    private String retryRecord(String entry) {
        return context.getSharedPreferences("keepadb_prefs", android.content.Context.MODE_PRIVATE)
                .getString(RETRY_KEY_PREFIX + entry, null);
    }

    private Set<String> retryKeys() {
        Set<String> keys = new java.util.TreeSet<>();
        for (String key : context.getSharedPreferences("keepadb_prefs",
                android.content.Context.MODE_PRIVATE).getAll().keySet()) {
            if (key.startsWith(RETRY_KEY_PREFIX)) keys.add(key);
        }
        return keys;
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
        /** Sees the keys of an editor transaction and the stored values just before it is applied. */
        volatile java.util.function.BiConsumer<Set<String>, java.util.Map<String, Object>> beforeApply;

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
                java.util.function.BiConsumer<Set<String>, java.util.Map<String, Object>> observer =
                        beforeApply;
                if (observer != null) {
                    java.util.Set<String> changed = new java.util.HashSet<>(updates.keySet());
                    changed.addAll(removals);
                    java.util.Map<String, Object> stored;
                    synchronized (values) {
                        stored = new java.util.HashMap<>(values);
                    }
                    observer.accept(changed, stored);
                }
                synchronized (values) {
                    if (clear) values.clear();
                    for (String key : removals) values.remove(key);
                    values.putAll(updates);
                }
            }
        }
    }
}
