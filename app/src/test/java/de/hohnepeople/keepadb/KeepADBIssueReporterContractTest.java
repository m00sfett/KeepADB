package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import org.junit.Test;

/**
 * Static contracts for the privacy-safe feedback report draft flow.
 *
 * <p>#698: the dialog moved from {@code SettingsActivity.java} into {@code
 * KeepADBDiagnosticsController.java}. The positive wiring assertions now read the controller; the
 * negative ones (no GitHub, no issue tracker) read both files, so the guard cannot go blind by the
 * code moving out of the file it used to scan. The browser path stays in the activity and is
 * asserted there.
 */
public class KeepADBIssueReporterContractTest {
    private static final String ACTIVITY_SOURCE =
            "app/src/main/java/de/hohnepeople/keepadb/SettingsActivity.java";
    private static final String CONTROLLER_SOURCE =
            "app/src/main/java/de/hohnepeople/keepadb/KeepADBDiagnosticsController.java";

    @Test
    public void builderTargetsTheFeedbackPageInsteadOfGitHub() throws IOException {
        String reporter = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBIssueReporter.java");
        String activity = read(ACTIVITY_SOURCE);
        String controller = read(CONTROLLER_SOURCE);
        assertTrue(reporter.contains("https://keepadb.roteson.de/feedback"));
        assertFalse(reporter.contains("github.com"));
        assertFalse(reporter.contains("issues/new"));
        assertTrue(reporter.contains("KeepADBDiagnostics.exportForIssueReport(context)"));
        assertTrue(controller.contains("KeepADBIssueReporter.buildFeedbackUrl(activity,"));
        for (String source : new String[] {activity, controller}) {
            assertFalse(source.contains("github.com"));
            assertFalse(source.contains("issues/new"));
        }
        assertTrue(controller.contains("preview.getText().toString()"));
        assertTrue(controller.contains("setText(withoutDiagnostics)"));
        // The feedback page is opened through the activity's shared browser path, which owns the
        // ACTION_VIEW intent and the missing-browser handling (#673).
        assertTrue(controller.contains("openWebLink.accept(KeepADBIssueReporter.buildFeedbackUrl(activity,"));
        assertFalse(controller.contains("Intent.ACTION_VIEW"));
        assertTrue(activity.contains("Intent.ACTION_VIEW"));
        assertTrue(activity.contains("catch (ActivityNotFoundException | SecurityException"));
        assertTrue(activity.contains("new KeepADBDiagnosticsController(this, this::openWebLink)"));
    }

    @Test
    public void shareActionSendsTheEditableBodyWithoutAnyGitHubOrIssueTrackerDependency()
            throws IOException {
        String controller = read(CONTROLLER_SOURCE);
        assertTrue(controller.contains("setNeutralButton(R.string.settings_issue_report_share"));
        assertTrue(controller.contains("BUTTON_NEUTRAL"));
        assertTrue(controller.contains("new Intent(Intent.ACTION_SEND)"));
        assertTrue(controller.contains("Intent.EXTRA_TEXT"));
        assertTrue(controller.contains("Intent.createChooser("));
    }

    @Test
    public void previewRequiresExplicitDiagnosticsOptInAndKeepsDraftEditable() throws IOException {
        String controller = read(CONTROLLER_SOURCE);
        assertTrue(controller.contains("settings_issue_report_include_diagnostics"));
        assertTrue(controller.contains("setOnCheckedChangeListener"));
        assertTrue(controller.contains("setInputType(InputType.TYPE_CLASS_TEXT"));
        assertTrue(controller.contains("setPositiveButton(R.string.settings_issue_report_open_feedback"));
        assertTrue(controller.contains("removeDiagnosticsSection"));
        assertTrue(controller.contains("buildDiagnosticsSection"));
        assertTrue(controller.indexOf("buildDiagnosticsSection(activity)")
                > controller.indexOf("if (checked &&"));
        assertTrue(read("app/src/main/res/values/strings.xml")
                .contains("The browser request sends"));
    }

    @Test
    public void bodyContainsAllStructuredFieldsAndNoPrivateSource() throws IOException {
        String reporter = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBIssueReporter.java");
        String body = read("app/src/main/res/values/strings.xml");
        String[] headings = {
                "Problem type", "Affected language / locale", "Expected behavior",
                "Actual behavior", "Reproduction steps", "App version", "Version code",
                "Android version", "Android API level", "Device model", "Logs",
                "Screenshots", "Additional notes"
        };
        for (String heading : headings) assertTrue(body.contains("## " + heading));
        for (int index = 1; index <= 13; index++) {
            assertTrue(body.contains("%" + index + "$"));
        }
        assertTrue(reporter.contains("NETWORK_HOST"));
        assertTrue(reporter.contains("NETWORK_PORT"));
        assertTrue(reporter.contains("KeepADBDiagnostics.redact(line)"));
        assertFalse(reporter.contains("getRegisterWebhookUrl"));
        assertFalse(reporter.contains("getUsbWebhookLast"));
        assertFalse(reporter.contains("getWifiIpAddress"));
    }

    @Test
    public void diagnosticsRedactionExcludesUrlsSecretsAndNetworkEndpoints() {
        String safe = KeepADBIssueReporter.redactDiagnostics(
                "url=https://private.example/x host=192.168.1.5 port=37821 "
                        + "token=secret password=hunter2");
        assertFalse(safe.contains("private.example"));
        assertFalse(safe.contains("192.168.1.5"));
        assertFalse(safe.contains("37821"));
        assertFalse(safe.contains("secret"));
        assertFalse(safe.contains("hunter2"));
        assertTrue(safe.contains("[URL_REDACTED]"));
        assertTrue(safe.contains("host=[REDACTED]"));
        assertTrue(safe.contains("port=[REDACTED]"));
    }

    @Test
    public void diagnosticsRedactionFullyMasksBssidAndSsidRegardlessOfHexLetterCase() {
        // #574: invented, locally-administered BSSIDs (never a real access point's address).
        // Mixed-case hex covers both letter cases the platform may report.
        String safe = KeepADBIssueReporter.redactDiagnostics(
                "detail=bssid=02:1A:2b:3C:44:55\n"
                        + "detail=bssid=02:aa:BB:cc:DD:ee\n"
                        + "detail=ssid=MyInventedNetwork");
        assertFalse(safe.contains("1A:2b:3C:44:55"));
        assertFalse(safe.contains("aa:BB:cc:DD:ee"));
        assertFalse(safe.contains("MyInventedNetwork"));
        assertEquals(3, safe.split("\\[REDACTED\\]", -1).length - 1);
        assertTrue(safe.contains("bssid=[REDACTED]"));
        assertTrue(safe.contains("ssid=[REDACTED]"));
    }

    @Test
    public void diagnosticsRedactionMasksAnSsidWithEmbeddedSpacesCompletely() {
        // A free-text SSID may contain spaces; the mask must not stop at the first one and leak
        // the remainder into the shared draft.
        String safe = KeepADBIssueReporter.redactDiagnostics(
                "detail=ssid=My Invented Guest Network");
        assertTrue(safe.contains("ssid=[REDACTED]"));
        assertFalse(safe.contains("My Invented Guest Network"));
        assertFalse(safe.contains("Invented"));
    }

    @Test
    public void diagnosticsTogglePreservesEditsOutsideTheOptionalSection() {
        String body = "## Logs\nuser log\n\n## Additional notes\nuser note";
        String title = "Optional diagnostics";
        String section = title + "\n\nKeepADB diagnostics v1\nhost=[REDACTED]";
        String withDiagnostics = KeepADBIssueReporter.addDiagnosticsSection(body, section);
        String edited = withDiagnostics.replace("host=[REDACTED]", "host=[user edit]");

        assertTrue(KeepADBIssueReporter.containsDiagnosticsSection(withDiagnostics, title));
        assertEquals(body, KeepADBIssueReporter.removeDiagnosticsSection(edited, title));
    }

    @Test
    public void everySupportedLocaleContainsIssueReportKeys() throws IOException {
        String[] required = {
                "settings_issue_report_button", "settings_issue_report_accessibility",
                "settings_issue_report_dialog_title", "settings_issue_report_dialog_message",
                "settings_issue_report_include_diagnostics", "settings_issue_report_preview",
                "settings_issue_report_preview_hint", "settings_issue_report_open_feedback",
                "settings_issue_report_share", "settings_general_feedback", "feedback_type",
                "feedback_type_bug", "feedback_type_translation", "feedback_type_suggestion",
                "feedback_type_other", "feedback_report_body", "feedback_draft_copied",
                "feedback_copy_failed",
                "issue_report_title", "issue_report_unavailable", "issue_report_body",
                "issue_report_placeholder_problem_type", "issue_report_placeholder_expected",
                "issue_report_placeholder_actual", "issue_report_placeholder_steps",
                "issue_report_placeholder_logs", "issue_report_placeholder_screenshots",
                "issue_report_placeholder_notes", "issue_report_diagnostics_section"
        };
        Path valuesRoot = projectPath("app/src/main/res");
        try (Stream<Path> paths = Files.list(valuesRoot)) {
            long[] localeCount = {0};
            paths.filter(path -> path.getFileName().toString().startsWith("values"))
                    .forEach(path -> {
                        try {
                            localeCount[0]++;
                            String xml = new String(Files.readAllBytes(path.resolve("strings.xml")),
                                    StandardCharsets.UTF_8);
                            for (String key : required) {
                                assertTrue(path.getFileName() + " misses " + key,
                                        xml.contains("name=\"" + key + "\""));
                            }
                            if (!"values".equals(path.getFileName().toString())) {
                                assertFalse(path.getFileName() + " uses the English issue template",
                                        xml.contains("<string name=\"issue_report_body\">## Problem type"));
                                assertFalse(path.getFileName() + " uses the English unavailable value",
                                        xml.contains("<string name=\"issue_report_unavailable\">Unavailable</string>"));
                            }
                        } catch (IOException exception) {
                            throw new RuntimeException(exception);
                        }
                    });
            assertEquals(19, localeCount[0]);
        }
    }

    private static Path projectPath(String relativePath) {
        Path directory = Paths.get("").toAbsolutePath();
        while (directory != null && !Files.exists(directory.resolve("settings.gradle"))) {
            directory = directory.getParent();
        }
        if (directory == null) throw new IllegalStateException("Could not locate project root");
        return directory.resolve(relativePath);
    }

    private static String read(String relativePath) throws IOException {
        return new String(Files.readAllBytes(projectPath(relativePath)), StandardCharsets.UTF_8);
    }
}
