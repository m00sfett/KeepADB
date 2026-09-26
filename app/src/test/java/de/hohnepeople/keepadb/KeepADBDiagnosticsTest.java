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
    public void exportMaskingShortensBssidToOuiAndFullyMasksSsid() {
        // #574: invented, locally-administered addresses (RFC-style "02:.." prefix) -- never a
        // real access point's BSSID. Mixed-case hex covers both letter cases in one pass.
        String masked = KeepADBDiagnostics.maskNetworkIdentifiersForExport(
                "event=network_trust_prompt outcome=shown detail=bssid=02:1A:2b:3C:44:55\n"
                        + "event=user_action outcome=allowed detail=bssid=02:aa:BB:cc:DD:ee\n"
                        + "event=x outcome=y detail=ssid=MyInventedNetwork");

        assertTrue("OUI must stay visible", masked.contains("bssid=02:1A:2b:*:*:*"));
        assertTrue("OUI must stay visible regardless of hex letter case",
                masked.contains("bssid=02:aa:BB:*:*:*"));
        assertFalse("the masked remainder must not leak", masked.contains("3C:44:55"));
        assertFalse("the masked remainder must not leak", masked.contains("cc:DD:ee"));
        assertTrue("an SSID has no OUI-style rule and is masked outright",
                masked.contains("ssid=[REDACTED]"));
        assertFalse(masked.contains("MyInventedNetwork"));
    }

    @Test
    public void exportMaskingFullyRedactsAValueThatIsNotASixOctetMac() {
        String masked = KeepADBDiagnostics.maskNetworkIdentifiersForExport(
                "event=x outcome=y detail=bssid=not-a-real-mac");
        assertTrue(masked.contains("bssid=[REDACTED]"));
        assertFalse(masked.contains("not-a-real-mac"));
    }

    @Test
    public void exportMaskingRedactsAnSsidWithEmbeddedSpacesCompletely() {
        // A free-text SSID may contain spaces; the mask must not stop at the first one and leak
        // the remainder.
        String masked = KeepADBDiagnostics.maskNetworkIdentifiersForExport(
                "event=x outcome=y detail=ssid=My Invented Guest Network");
        assertTrue(masked.contains("ssid=[REDACTED]"));
        assertFalse(masked.contains("My Invented Guest Network"));
        assertFalse(masked.contains("Invented"));
    }

    @Test
    public void requiredPathsAreInstrumentedAndExportIsUserReachable() throws IOException {
        String core = read("app/src/main/java/de/hohnepeople/keepadb/KeepADB.java");
        String service = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBService.java");
        String settings = read("app/src/main/java/de/hohnepeople/keepadb/SettingsActivity.java");

        assertTrue(core.contains("intentId="));
        assertTrue(core.contains("\"recovery_attempt\""));
        assertTrue(service.contains("\"state_observed\""));
        assertTrue(service.contains("\"wifi_change\""));
        assertTrue(service.contains("\"service_create\""));
        assertTrue(service.contains("heartbeatGapMs="));
        assertTrue(settings.contains("KeepADBDiagnostics.export(this)"));
        assertTrue(settings.contains("Intent.ACTION_SEND"));
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
