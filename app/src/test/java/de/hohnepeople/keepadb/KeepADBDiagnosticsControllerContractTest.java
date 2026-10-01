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
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.Test;

/**
 * #698: static contracts for the ownership split between {@link SettingsActivity} and {@link
 * KeepADBDiagnosticsController}. The behavior tests ({@code SettingsIssueReportDialogTest} and the
 * Settings tests) pin what happens; these pin what a behavior test cannot see: that there is one
 * single owner of the export and the feedback report dialog, that the reset-app flow did not move
 * with it, and that no second code path reads diagnostics for sharing.
 */
public class KeepADBDiagnosticsControllerContractTest {
    private static final String SOURCE_DIRECTORY = "app/src/main/java/de/hohnepeople/keepadb/";

    /**
     * The activity keeps the screen composition and the Android lifecycle; the export, the dialog
     * with its preview and opt-in checkbox, the draft Bundle keys and every button handler live in
     * the controller only. A second owner would bring back the split state the extraction removed.
     */
    @Test
    public void theControllerIsTheOnlyOwnerOfTheExportAndTheFeedbackReportDialog() throws IOException {
        String activity = read(SOURCE_DIRECTORY + "SettingsActivity.java");
        String controller = read(SOURCE_DIRECTORY + "KeepADBDiagnosticsController.java");

        String[] ownedByTheController = {
                "activeIssueReportDialog", "activeIssueReportPreview", "activeIssueReportDiagnostics",
                "STATE_ISSUE_REPORT_SHOWING", "STATE_ISSUE_REPORT_DRAFT",
                "STATE_ISSUE_REPORT_DIAGNOSTICS", "KeepADBIssueReporter", "KeepADBDiagnostics.",
                "CheckBox", "setOnCheckedChangeListener", "Intent.ACTION_SEND", "Intent.createChooser",
                "diagnostics_export", "settings_diagnostics_export", "settings_issue_report",
                "showIssueReportDialog", "shareDiagnostics"};
        for (String name : ownedByTheController) {
            assertFalse("SettingsActivity must not own '" + name + "' any more (#698)",
                    activity.contains(name));
        }
        for (String name : new String[] {"activeIssueReportDialog", "activeIssueReportPreview",
                "activeIssueReportDiagnostics", "STATE_ISSUE_REPORT_SHOWING",
                "STATE_ISSUE_REPORT_DRAFT", "STATE_ISSUE_REPORT_DIAGNOSTICS",
                "KeepADBIssueReporter.buildBody(activity, false)",
                "KeepADBIssueReporter.buildDiagnosticsSection(activity)",
                "KeepADBDiagnostics.event(activity, \"diagnostics_export\"",
                "KeepADBDiagnostics.export(activity)", "R.id.settings_diagnostics_export",
                "R.id.settings_issue_report"}) {
            assertTrue("KeepADBDiagnosticsController must own '" + name + "' in code, not only in"
                    + " a comment", countOutsideComments(controller, name) >= 1);
        }

        // The activity reaches the controller only through its explicit lifecycle hooks.
        for (String hook : new String[] {
                "new KeepADBDiagnosticsController(this, this::openWebLink)",
                "diagnosticsController.restore(savedInstanceState)",
                "diagnosticsController.saveState(outState)", "diagnosticsController.destroy()",
                "diagnosticsController.getActiveIssueReportDialog()"}) {
            assertEquals("SettingsActivity must call '" + hook + "' exactly once", 1,
                    count(activity, hook));
        }
    }

    /**
     * The reset-app dialog shares the card with the export and the report but is not part of this
     * extraction: its destructive action stays in the activity, behind the user's own confirm tap.
     */
    @Test
    public void theResetAppFlowStaysCompletelyInTheActivity() throws IOException {
        String activity = read(SOURCE_DIRECTORY + "SettingsActivity.java");
        String controller = read(SOURCE_DIRECTORY + "KeepADBDiagnosticsController.java");

        for (String name : new String[] {"R.id.settings_reset_app", "showResetAppDialog",
                "activeResetAppDialog", "clearApplicationUserData", "revokeSelfPermissionOnKill",
                "STATE_RESET_APP_SHOWING"}) {
            assertTrue("SettingsActivity must still own '" + name + "'", activity.contains(name));
            assertFalse("The controller must not touch '" + name + "'", controller.contains(name));
        }
    }

    /**
     * Diagnostics leave the app only through an explicit user action. The report body is the only
     * place that embeds them, and it is built only by {@link KeepADBIssueReporter} on behalf of
     * the controller's opt-in checkbox; the raw export is read only by the export button. Any
     * other main-source file calling these entry points would be a second, unreviewed path.
     */
    @Test
    public void onlyTheControllerReadsDiagnosticsForSharing() throws IOException {
        List<String> readers = new ArrayList<>();
        List<Path> sources;
        try (Stream<Path> files = Files.list(projectPath(SOURCE_DIRECTORY))) {
            sources = files.filter(path -> path.getFileName().toString().endsWith(".java"))
                    .collect(Collectors.toList());
        }
        assertTrue("The scan must see the main sources", sources.size() > 50);
        for (Path file : sources) {
            String name = file.getFileName().toString();
            if (name.equals("KeepADBDiagnostics.java") || name.equals("KeepADBIssueReporter.java")) {
                continue;
            }
            String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            if (source.contains("KeepADBDiagnostics.export(")
                    || source.contains("KeepADBDiagnostics.exportForIssueReport(")
                    || source.contains("buildDiagnosticsSection(")
                    || source.contains("KeepADBIssueReporter.buildBody(")) {
                readers.add(name);
            }
        }
        assertEquals("Only the controller may read diagnostics for sharing: " + readers,
                List.of("KeepADBDiagnosticsController.java"), readers);
    }

    private static int count(String text, String part) {
        int count = 0;
        for (int index = text.indexOf(part); index >= 0; index = text.indexOf(part, index + 1)) {
            count++;
        }
        return count;
    }

    /** Occurrences outside of {@code //} and block comments, so documentation cannot satisfy a guard. */
    private static int countOutsideComments(String source, String part) {
        String code = source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
        return count(code, part);
    }

    private static Path projectPath(String relativePath) {
        Path directory = Paths.get("").toAbsolutePath();
        while (directory != null && !Files.exists(directory.resolve("settings.gradle"))) {
            directory = directory.getParent();
        }
        if (directory == null) {
            throw new IllegalStateException("Could not locate project root");
        }
        return directory.resolve(relativePath);
    }

    private static String read(String relativePath) throws IOException {
        return new String(Files.readAllBytes(projectPath(relativePath)), StandardCharsets.UTF_8);
    }
}
