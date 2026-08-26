package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class KeepADBDiagnosticsTest {
    @Test
    public void eventFormatContainsCorrelationFieldsAndStableNames() {
        String event = KeepADBDiagnostics.formatEvent(0L, 1234L, 42,
                "recovery_attempt", "endpoint", "failed", "reason=timeout");
        assertTrue(event.contains("ts="));
        assertTrue(event.contains("elapsedMs=1234"));
        assertTrue(event.contains("pid=42"));
        assertTrue(event.contains("event=recovery_attempt"));
        assertTrue(event.contains("source=endpoint"));
        assertTrue(event.contains("outcome=failed"));
    }

    @Test
    public void redactionRemovesSecretsAndUrls() {
        String safe = KeepADBDiagnostics.redact(
                "pairing_code=123456 token=abc password=hunter2; Authorization: Bearer abc; "
                        + "url=https://private.example/x");
        assertFalse(safe.contains("123456"));
        assertFalse(safe.contains("hunter2"));
        assertFalse(safe.contains("Bearer abc"));
        assertFalse(safe.contains("private.example"));
        assertTrue(safe.contains("[REDACTED]"));
        assertTrue(safe.contains("[URL_REDACTED]"));
    }

    @Test
    public void ringBufferKeepsExactlyNewest128Events() {
        List<String> events = new ArrayList<>();
        for (int i = 0; i < KeepADBDiagnostics.MAX_EVENTS + 2; i++) {
            KeepADBDiagnostics.appendBounded(events, "event-" + i);
        }
        assertEquals(KeepADBDiagnostics.MAX_EVENTS, events.size());
        assertEquals("event-2", events.get(0));
        assertEquals("event-129", events.get(events.size() - 1));
    }

    @Test
    public void exportHasVersionedHeaderAndPreservesEventOrder() {
        String export = KeepADBDiagnostics.renderExport(List.of("first", "second"));
        assertEquals("KeepADB diagnostics v1\nfirst\nsecond\n", export);
    }

    @Test
    public void requiredPathsAreInstrumentedAndExportIsUserReachable() throws IOException {
        String core = read("app/src/main/java/de/hohnepeople/keepadb/KeepADB.java");
        String service = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBService.java");
        String settings = read("app/src/main/java/de/hohnepeople/keepadb/SettingsActivity.java");
        String backup = read("app/src/main/res/xml/backup_rules.xml");
        String extraction = read("app/src/main/res/xml/data_extraction_rules.xml");

        assertTrue(core.contains("intentId="));
        assertTrue(core.contains("\"recovery_attempt\""));
        assertTrue(service.contains("\"state_observed\""));
        assertTrue(service.contains("\"wifi_change\""));
        assertTrue(service.contains("\"service_create\""));
        assertTrue(service.contains("heartbeatGapMs="));
        assertTrue(settings.contains("KeepADBDiagnostics.export(this)"));
        assertTrue(settings.contains("Intent.ACTION_SEND"));
        assertTrue(backup.contains("keepadb_diagnostics.xml"));
        assertTrue(extraction.contains("keepadb_diagnostics.xml"));
    }

    private static String read(String relativePath) throws IOException {
        Path directory = Paths.get("").toAbsolutePath();
        while (directory != null && !Files.exists(directory.resolve("settings.gradle"))) {
            directory = directory.getParent();
        }
        if (directory == null) throw new IllegalStateException("Could not locate project root");
        return new String(Files.readAllBytes(directory.resolve(relativePath)), StandardCharsets.UTF_8);
    }
}
