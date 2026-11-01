package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.List;

/** #892: the bounded local report history and its hook into the register client. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBReportHistoryTest {
    private static final long T0 = 1_800_000_000_000L;

    @Rule
    public final KeepADBRegisterClientResetRule registerClientResetRule =
            new KeepADBRegisterClientResetRule();

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        KeepADBReportHistory.clear(context);
        KeepADBReportHistory.setNowForTesting(T0);
    }

    @After
    public void tearDown() {
        KeepADBReportHistory.setNowForTesting(null);
        KeepADBReportHistory.clear(context);
        KeepADBRegisterClientResetRule.awaitIdle();
        KeepADBRegisterClient.resetForTesting();
    }

    @Test
    public void entriesComeBackNewestFirstWithAllFields() {
        KeepADBReportHistory.endpointFound(context, "192.168.178.24:42631");
        KeepADBReportHistory.setNowForTesting(T0 + 1000);
        KeepADBReportHistory.report(context, "192.168.178.24:42631", false,
                "error=SocketTimeoutException", 2, 4);

        List<KeepADBReportHistory.Entry> entries = KeepADBReportHistory.entries(context);
        assertEquals(2, entries.size());
        KeepADBReportHistory.Entry newest = entries.get(0);
        assertEquals(KeepADBReportHistory.Event.REPORT, newest.event);
        assertEquals(T0 + 1000, newest.timeMs);
        assertFalse(newest.ok);
        assertEquals("error=SocketTimeoutException", newest.outcome);
        assertEquals(2, newest.retry);
        assertEquals(4, newest.maxRetries);
        assertEquals(KeepADBReportHistory.Event.ENDPOINT_FOUND, entries.get(1).event);
    }

    @Test
    public void entriesOlderThanADayAreDroppedOnReadAndWrite() {
        KeepADBReportHistory.report(context, "a:1", true, "http=200", 0, 4);
        KeepADBReportHistory.setNowForTesting(T0 + KeepADBReportHistory.MAX_AGE_MS + 1);
        assertTrue(KeepADBReportHistory.entries(context).isEmpty());

        KeepADBReportHistory.report(context, "a:2", true, "http=200", 0, 4);
        assertEquals(1, KeepADBReportHistory.entries(context).size());
    }

    /** Codex review: expired entries leave the stored file, not only the shown list. */
    @Test
    public void expiredEntriesAreRemovedFromDiskWithoutANewRecord() {
        KeepADBReportHistory.report(context, "192.168.178.24:42631", true, "http=200", 0, 4);
        KeepADBReportHistory.setNowForTesting(T0 + KeepADBReportHistory.MAX_AGE_MS + 1);

        KeepADBReportHistory.pruneStored(context);

        String stored = context.getSharedPreferences("keepadb_report_history", Context.MODE_PRIVATE)
                .getString("entries", null);
        assertTrue("no expired endpoint may stay on disk",
                stored == null || !stored.contains("192.168.178.24"));
    }

    @Test
    public void theCapKeepsTheNewestEntries() {
        for (int i = 0; i < KeepADBReportHistory.MAX_ENTRIES + 25; i++) {
            KeepADBReportHistory.setNowForTesting(T0 + i);
            KeepADBReportHistory.report(context, "h:" + i, true, "http=200", 0, 4);
        }
        List<KeepADBReportHistory.Entry> entries = KeepADBReportHistory.entries(context);
        assertEquals(KeepADBReportHistory.MAX_ENTRIES, entries.size());
        assertEquals("h:" + (KeepADBReportHistory.MAX_ENTRIES + 24), entries.get(0).endpoint);
        assertEquals("h:25", entries.get(entries.size() - 1).endpoint);
    }

    @Test
    public void aClockThatMovedBackNeitherBlocksNewEntriesNorKeepsOldOnesForever() {
        KeepADBReportHistory.report(context, "future:1", true, "http=200", 0, 4);
        KeepADBReportHistory.setNowForTesting(T0 - 60L * 60L * 1000L);
        KeepADBReportHistory.report(context, "now:1", true, "http=200", 0, 4);
        assertEquals("A future-dated entry must not block a new one", 2,
                KeepADBReportHistory.entries(context).size());

        KeepADBReportHistory.setNowForTesting(T0 + KeepADBReportHistory.MAX_AGE_MS + 1);
        assertTrue(KeepADBReportHistory.entries(context).isEmpty());
    }

    @Test
    public void repeatedEndpointStatesAreRecordedOnce() {
        KeepADBReportHistory.endpointLost(context);
        KeepADBReportHistory.endpointLost(context);
        KeepADBReportHistory.endpointFound(context, "a:1");
        KeepADBReportHistory.report(context, "a:1", true, "http=200", 0, 4);
        KeepADBReportHistory.endpointFound(context, "a:1");
        KeepADBReportHistory.endpointFound(context, "a:2");
        assertEquals(4, KeepADBReportHistory.entries(context).size());
    }

    @Test
    public void clearEmptiesTheHistoryAndADamagedStoreStartsOver() {
        KeepADBReportHistory.report(context, "a:1", true, "http=200", 0, 4);
        KeepADBReportHistory.clear(context);
        assertTrue(KeepADBReportHistory.entries(context).isEmpty());
        assertTrue(KeepADBReportHistory.parse("{not json").isEmpty());
    }

    /** The register client records each attempt, and the stored form never holds the URL. */
    @Test
    public void registerReportsAreRecordedWithoutTheWebhookUrl() throws Exception {
        String url = "http://100.111.111.21:50829/register/s20?token=secret-token-123";
        KeepADBPreferences.setRegisterWebhookUrl(context, url);
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        KeepADBFakeHttpTransport transport = new KeepADBFakeHttpTransport();
        KeepADBRegisterClient.setHttpTransport(transport);

        KeepADBRegisterClient.updateEndpointAsync(context, "192.168.178.24", 42631);
        KeepADBRegisterClientResetRule.awaitIdle();

        List<KeepADBReportHistory.Entry> entries = KeepADBReportHistory.entries(context);
        assertFalse("The POST must be recorded", entries.isEmpty());
        KeepADBReportHistory.Entry report = entries.get(0);
        assertEquals(KeepADBReportHistory.Event.REPORT, report.event);
        assertEquals("192.168.178.24:42631", report.endpoint);
        String stored = context.getSharedPreferences("keepadb_report_history", Context.MODE_PRIVATE)
                .getString("entries", "");
        assertFalse("The webhook URL must never be stored", stored.contains("100.111.111.21"));
        assertFalse(stored.contains("secret-token-123"));
        assertFalse(stored.contains("register/s20"));
    }

    /** Codex review: the DELETE at the previous URL after a URL change is recorded as well. */
    @Test
    public void urlChangeRecordsTheUnregisterAtTheOldUrl() throws Exception {
        KeepADBPreferences.setRegisterWebhookUrl(context, "http://old.example/register/s20");
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        KeepADBFakeHttpTransport transport = new KeepADBFakeHttpTransport();
        KeepADBRegisterClient.setHttpTransport(transport);
        KeepADBRegisterClient.updateEndpointAsync(context, "192.168.178.24", 42631);
        KeepADBRegisterClientResetRule.awaitIdle();

        KeepADBPreferences.setRegisterWebhookUrl(context, "http://new.example/register/s20");
        KeepADBRegisterClient.updateEndpointAsync(context, "192.168.178.24", 42632);
        KeepADBRegisterClientResetRule.awaitIdle();

        boolean unregister = false;
        for (KeepADBReportHistory.Entry entry : KeepADBReportHistory.entries(context)) {
            if (entry.event == KeepADBReportHistory.Event.UNREGISTER
                    && "192.168.178.24:42631".equals(entry.endpoint)) {
                unregister = true;
            }
        }
        assertTrue("the old registration's DELETE must be in the history", unregister);
    }

    /** Codex review: a user's unregister after failed endpoint-loss DELETEs is not their retry. */
    @Test
    public void anExplicitUnregisterIsNotCountedAsAnEndpointLossRetry() throws Exception {
        KeepADBPreferences.setRegisterWebhookUrl(context, "http://reg.example/register/s20");
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        KeepADBFakeHttpTransport transport = new KeepADBFakeHttpTransport();
        KeepADBRegisterClient.setHttpTransport(transport);
        KeepADBRegisterClient.updateEndpointAsync(context, "192.168.178.24", 42631);
        KeepADBRegisterClientResetRule.awaitIdle();
        transport.setDeleteSuccess(false);
        KeepADBRegisterClient.markUnavailableAsync(context);
        KeepADBRegisterClientResetRule.awaitIdle();

        KeepADBRegisterClient.unregisterAndDisableAsync(context);
        KeepADBRegisterClientResetRule.awaitIdle();

        KeepADBReportHistory.Entry newest = KeepADBReportHistory.entries(context).get(0);
        assertEquals(KeepADBReportHistory.Event.UNREGISTER, newest.event);
        assertEquals("a fresh user-requested unregister starts at zero", 0, newest.retry);
    }

    /** Codex review: deferred cleanup DELETEs carry their retry number and the attempt budget. */
    @Test
    public void deferredCleanupRetriesAreNumbered() {
        KeepADBPreferences.addPendingWebhookCleanupUrl(context, "http://old.example/register/s20");
        KeepADBFakeHttpTransport transport = new KeepADBFakeHttpTransport();
        transport.setDeleteSuccess(false);
        KeepADBRegisterClient.setHttpTransport(transport);

        for (long now : new long[] {1_000L, 31_000L, 91_000L}) {
            KeepADBRegisterClient.setPendingCleanupNowForTesting(now);
            KeepADBRegisterClient.flushPendingCleanupsForTesting(context);
        }

        List<KeepADBReportHistory.Entry> entries = KeepADBReportHistory.entries(context);
        assertEquals(3, entries.size());
        for (int i = 0; i < 3; i++) {
            KeepADBReportHistory.Entry entry = entries.get(2 - i);
            assertEquals(KeepADBReportHistory.Event.UNREGISTER, entry.event);
            assertEquals(i + 1, entry.retry);
            assertEquals(PendingCleanupRetryRepository.MAX_ATTEMPTS, entry.maxRetries);
        }
    }

    @Test
    public void rowDetailsNameTheEndpointTheResultAndTheRetry() {
        Context activity = Robolectric.buildActivity(ReportHistoryActivity.class).setup().get();
        KeepADBReportHistory.Entry entry = new KeepADBReportHistory.Entry(T0,
                KeepADBReportHistory.Event.REPORT, "192.168.178.24:42631", false,
                "error=ConnectException", 3, 4);
        assertEquals("192.168.178.24:42631 · connection refused · Retry 3 of 4",
                ReportHistoryActivity.details(activity, entry, false));
        assertFalse("Privacy mode masks the host",
                ReportHistoryActivity.details(activity, entry, true).contains("192.168.178.24"));
        assertEquals("HTTP 200", ReportHistoryActivity.outcomeLabel(activity, "http=200"));
        assertEquals("", ReportHistoryActivity.outcomeLabel(activity, "unknown"));
    }
}
