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

/** Static contracts for the privacy-safe feedback report draft flow. */
public class KeepADBIssueReporterContractTest {
    @Test
    public void builderTargetsTheStaticFeedbackPageInsteadOfGitHub() throws IOException {
        String reporter = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBIssueReporter.java");
        String activity = read("app/src/main/java/de/hohnepeople/keepadb/SettingsActivity.java");
        assertTrue(reporter.contains("https://hohnepeople.de/keepadb/feedback"));
        assertFalse(reporter.contains("github.com"));
        assertFalse(reporter.contains("issues/new"));
        assertTrue(reporter.contains("KeepADBDiagnostics.export(context)"));
        assertTrue(activity.contains("KeepADBIssueReporter.FEEDBACK_URL"));
        assertFalse(activity.contains("github.com"));
        assertFalse(activity.contains("issues/new"));
        assertTrue(activity.contains("preview.getText().toString()"));
        assertTrue(activity.contains("Intent.ACTION_VIEW"));
        assertTrue(activity.contains("setText(withoutDiagnostics)"));
    }

    @Test
    public void shareActionSendsTheEditableBodyWithoutAnyGitHubOrIssueTrackerDependency()
            throws IOException {
        String activity = read("app/src/main/java/de/hohnepeople/keepadb/SettingsActivity.java");
        assertTrue(activity.contains("setNeutralButton(R.string.settings_issue_report_share"));
        assertTrue(activity.contains("BUTTON_NEUTRAL"));
        assertTrue(activity.contains("new Intent(Intent.ACTION_SEND)"));
        assertTrue(activity.contains("Intent.EXTRA_TEXT"));
        assertTrue(activity.contains("Intent.createChooser("));
    }

    @Test
    public void previewRequiresExplicitDiagnosticsOptInAndKeepsDraftEditable() throws IOException {
        String activity = read("app/src/main/java/de/hohnepeople/keepadb/SettingsActivity.java");
        assertTrue(activity.contains("settings_issue_report_include_diagnostics"));
        assertTrue(activity.contains("setOnCheckedChangeListener"));
        assertTrue(activity.contains("setInputType(InputType.TYPE_CLASS_TEXT"));
        assertTrue(activity.contains("setPositiveButton(R.string.settings_issue_report_open_feedback"));
        assertTrue(activity.contains("removeDiagnosticsSection"));
        assertTrue(activity.contains("buildDiagnosticsSection"));
        assertTrue(activity.indexOf("buildDiagnosticsSection(this)")
                > activity.indexOf("if (checked &&"));
        assertTrue(read("app/src/main/res/values/strings.xml")
                .contains("Nothing is sent automatically"));
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
                "settings_issue_report_share",
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
