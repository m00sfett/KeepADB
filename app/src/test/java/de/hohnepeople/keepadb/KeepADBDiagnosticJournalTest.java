package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** #566: 48h debug diagnostics journal, per-minute snapshots and the debug-only boundary. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBDiagnosticJournalTest {

    private static final long MINUTE = 60_000L;
    private static final long HOUR = 60 * MINUTE;
    private static final long START = 1_790_000_000_000L;

    private final Context context = RuntimeEnvironment.getApplication();
    private final long[] now = {START};

    @After
    public void reset() {
        KeepADBBuildFlags.setOverrideForTesting(null);
        KeepADBDiagnosticJournal.resetForTesting();
    }

    private KeepADBDiagnosticJournal newJournal(File file) {
        return new KeepADBDiagnosticJournal(file, () -> now[0]);
    }

    private File tempFile() throws IOException {
        File dir = Files.createTempDirectory("journal").toFile();
        return new File(dir, KeepADBDiagnosticJournal.FILE_NAME);
    }

    @Test
    public void fortyEightHoursOfMinuteSnapshotsWithHourlyChangesStayInTheExport() throws IOException {
        KeepADBDiagnosticJournal journal = newJournal(tempFile());
        String firstState = null;
        for (int minute = 0; minute <= 48 * 60; minute++) {
            // A state change every hour, identical minutes in between.
            String state = "net=" + ((minute / 60) % 2 == 0 ? "wifi" : "cellular") + " hour=" + minute / 60;
            if (firstState == null) firstState = state;
            journal.recordSample("state_snapshot", state, "snapshot " + state);
            now[0] += MINUTE;
        }
        String export = journal.render();
        assertTrue("the oldest (48h old) state must still be exported", export.contains("snapshot " + firstState));
        assertTrue(export.contains("hour=48"));
        assertTrue("identical minutes are counted, not dropped", export.contains("samples=60"));
    }

    @Test
    public void entriesOlderThanTheRetentionWindowArePruned() throws IOException {
        KeepADBDiagnosticJournal journal = newJournal(tempFile());
        journal.record("ancient");
        now[0] += KeepADBDiagnosticJournal.RETENTION_MS + MINUTE;
        journal.record("fresh");
        String export = journal.render();
        assertFalse(export.contains("ancient"));
        assertTrue(export.contains("fresh"));
        assertTrue(KeepADBDiagnosticJournal.RETENTION_MS >= 48 * HOUR);
    }

    @Test
    public void persistsAtMostOncePerHourAcrossManyRecordsAndProcessRestarts() throws IOException {
        File file = tempFile();
        KeepADBDiagnosticJournal journal = newJournal(file);
        for (int minute = 0; minute < 3 * 60; minute++) {
            journal.record("event " + minute);
            now[0] += MINUTE;
        }
        // Writes at minute 0, 60 and 120 -- never more than once per hour.
        assertEquals(3, journal.getPersistCountForTesting());
        assertTrue(file.exists());

        // A restarted process resumes the hourly budget from the file's own write time instead
        // of writing again immediately.
        file.setLastModified(now[0] - 10 * MINUTE);
        KeepADBDiagnosticJournal restarted = newJournal(file);
        restarted.record("after restart");
        assertEquals(0, restarted.getPersistCountForTesting());
        assertTrue("persisted entries survive the restart", restarted.render().contains("event 0"));
        now[0] += HOUR;
        restarted.record("an hour later");
        assertEquals(1, restarted.getPersistCountForTesting());
    }

    @Test
    public void aSamplingGapStartsANewEntryInsteadOfBeingHiddenInACounter() throws IOException {
        KeepADBDiagnosticJournal journal = newJournal(tempFile());
        journal.recordSample("state_snapshot", "same", "snapshot same");
        now[0] += MINUTE;
        journal.recordSample("state_snapshot", "same", "snapshot same");
        now[0] += 20 * MINUTE; // service/heartbeat was not running
        journal.recordSample("state_snapshot", "same", "snapshot same");
        assertEquals(2, journal.size());
    }

    @Test
    public void snapshotDistinguishesShownEndpointFromConfirmedReachability() {
        long t = START;
        String confirmed = KeepADBDiagnostics.snapshotState("wifi", true,
                KeepADBTailscaleStatus.Status.ACTIVE, true, true, "192.0.2.4", 37123, t - MINUTE, t);
        String stale = KeepADBDiagnostics.snapshotState("cellular", false,
                KeepADBTailscaleStatus.Status.ACTIVE, true, true, "192.0.2.4", 37123, t - 30 * MINUTE, t);
        String none = KeepADBDiagnostics.snapshotState("cellular", false,
                KeepADBTailscaleStatus.Status.UNKNOWN, false, true, null, 0, 0, t);

        assertTrue(confirmed.contains("shownEndpoint=host=192.0.2.4 port=37123"));
        assertTrue(confirmed.contains("endpointReachability=confirmed"));
        assertTrue("a still shown but no longer verified endpoint is not reported as reachable",
                stale.contains("shownEndpoint=host=192.0.2.4 port=37123")
                        && stale.contains("endpointReachability=stale"));
        assertTrue(stale.contains("net=cellular") && stale.contains("wifiEligible=false"));
        assertTrue(none.contains("shownEndpoint=none") && none.contains("endpointReachability=none"));
        assertTrue("unreadable Tailscale state stays unknown", none.contains("tailscale=unknown"));
    }

    @Test
    public void changedFieldsNamesTailscaleAndNetworkTransitions() {
        String before = KeepADBDiagnostics.snapshotState("wifi", true,
                KeepADBTailscaleStatus.Status.INACTIVE, true, true, null, 0, 0, START);
        String after = KeepADBDiagnostics.snapshotState("cellular", false,
                KeepADBTailscaleStatus.Status.ACTIVE, true, true, null, 0, 0, START);
        assertEquals("initial", KeepADBDiagnostics.changedFields(null, before));
        assertEquals("none", KeepADBDiagnostics.changedFields(before, before));
        assertEquals("net,wifiEligible,tailscale", KeepADBDiagnostics.changedFields(before, after));
    }

    @Test
    public void releaseBuildRecordsNoSnapshotAndKeepsTheV1Export() {
        KeepADBBuildFlags.setOverrideForTesting(false);
        KeepADBDiagnostics.snapshot(context);
        KeepADBDiagnostics.event(context, "user_action", "test", "enable", "release-marker");
        String export = KeepADBDiagnostics.export(context);
        assertTrue(export.startsWith(KeepADBDiagnostics.EXPORT_HEADER));
        assertFalse(export.contains("state_snapshot"));
        assertTrue(export.contains("release-marker"));
        assertFalse("release builds never create the journal file",
                new File(context.getFilesDir(), KeepADBDiagnosticJournal.FILE_NAME).exists());
    }

    @Test
    public void debugBuildRecordsSnapshotsAndEventsIntoTheJournalExport() {
        KeepADBBuildFlags.setOverrideForTesting(true);
        KeepADBDiagnostics.snapshot(context);
        KeepADBDiagnostics.event(context, "user_action", "test", "enable", "debug-marker");
        String export = KeepADBDiagnostics.export(context);
        assertTrue(export.startsWith(KeepADBDiagnosticJournal.EXPORT_HEADER));
        assertTrue(export.contains("coverageFrom="));
        assertTrue(export.contains("event=state_snapshot"));
        assertTrue(export.contains("tailscale="));
        assertTrue(export.contains("debug-marker"));
        assertTrue("the issue report keeps a compact v1-sized excerpt",
                KeepADBDiagnostics.exportForIssueReport(context).startsWith(KeepADBDiagnostics.EXPORT_HEADER));
    }
}
