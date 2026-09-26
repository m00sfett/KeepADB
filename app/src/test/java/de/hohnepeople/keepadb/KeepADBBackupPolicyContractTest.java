package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * Static contract for issue #252: KeepADB's only persisted state is two SharedPreferences
 * files ("keepadb_prefs", "keepadb_diagnostics"), both of which can carry webhook URLs,
 * endpoint data, device identifiers and diagnostics. Rather than maintaining an
 * ever-growing per-key backup exclusion list that a new preference could silently bypass,
 * backups and device-to-device transfer are disabled entirely, which removes the mechanism
 * that could leak such data instead of relying on someone remembering to update it.
 *
 * <p>#566 adds one more private file, the debug-build-only diagnostics journal in
 * {@code filesDir}; it carries the same kind of endpoint/diagnostics data and is covered by the
 * same app-wide backup exclusion rather than by a per-file rule.
 *
 * <p>#573: {@code android:allowBackup="false"} alone is not sufficient. Per Android 12 (API 31)
 * behavior changes, some OEMs honor it for cloud backup but still perform device-to-device (D2D)
 * transfer of app data regardless of that flag. {@code android:dataExtractionRules} is the
 * OEM-independent mechanism that closes that gap, so this contract now requires it to exist and
 * to exclude every domain in both {@code <cloud-backup>} and {@code <device-transfer>}.
 */
public class KeepADBBackupPolicyContractTest {
    private static final Pattern SHARED_PREFS_NAME = Pattern.compile(
            "getSharedPreferences\\(\\s*PREFS_NAME");
    private static final Pattern PREFS_NAME_VALUE = Pattern.compile(
            "PREFS_NAME\\s*=\\s*\"([^\"]+)\"");

    /**
     * All domains the data-extraction-rules schema defines. Both transfer sections must exclude
     * every one of these; omitting a domain leaves it fully included for that transfer type
     * (there is no implicit "exclude everything not mentioned" default).
     */
    private static final String[] ALL_DOMAINS = {
            "root", "file", "database", "sharedpref", "external",
            "device_root", "device_file", "device_database", "device_sharedpref",
    };

    @Test
    public void backupAndDeviceTransferAreDisabled() throws IOException {
        String manifest = read("app/src/main/AndroidManifest.xml");
        assertTrue(manifest.contains("android:allowBackup=\"false\""));
        assertFalse(manifest.contains("android:fullBackupContent"));
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""));
        assertFalse(Files.exists(projectRoot().resolve("app/src/main/res/xml/backup_rules.xml")));

        Path rulesFile = projectRoot().resolve("app/src/main/res/xml/data_extraction_rules.xml");
        assertTrue("data_extraction_rules.xml must exist", Files.exists(rulesFile));
        String rules = new String(Files.readAllBytes(rulesFile), StandardCharsets.UTF_8);
        // Strip XML comments first: the file's own explanatory comment mentions tag names like
        // "<device-transfer>" in prose, which would otherwise confuse the tag-boundary regex
        // below into starting a section at that mention instead of the real element.
        rules = Pattern.compile("<!--.*?-->", Pattern.DOTALL).matcher(rules).replaceAll("");

        assertAllDomainsExcluded(rules, "cloud-backup");
        assertAllDomainsExcluded(rules, "device-transfer");
    }

    /**
     * Fails if a section is missing, if it re-includes a domain via {@code <include>}, or if any
     * of the known domains isn't explicitly excluded. A partial exclusion list (e.g. dropping
     * "external") is exactly the kind of regression this guards against, since it silently
     * re-enables transfer for that domain instead of failing loudly.
     */
    private static void assertAllDomainsExcluded(String rules, String sectionTag) {
        String section = extractSection(rules, sectionTag);
        assertFalse("<" + sectionTag + "> must not contain an <include> element "
                        + "(that would re-enable a domain)",
                section.contains("<include"));
        for (String domain : ALL_DOMAINS) {
            assertTrue("<" + sectionTag + "> must exclude domain \"" + domain + "\"",
                    Pattern.compile("<exclude\\s+domain=\"" + domain + "\"").matcher(section).find());
        }
    }

    private static String extractSection(String rules, String tag) {
        Matcher matcher = Pattern.compile("<" + tag + "[^>]*>(.*?)</" + tag + ">", Pattern.DOTALL)
                .matcher(rules);
        if (!matcher.find()) {
            throw new IllegalStateException("<" + tag + "> section not found in data_extraction_rules.xml");
        }
        return matcher.group(1);
    }

    /**
     * Guards the premise the disabled-backup decision relies on: every persisted
     * SharedPreferences file in the app is one of the two known, documented names. If a
     * future change introduces a third file under a different name, this fails loudly
     * instead of silently creating a new, unreviewed persistence surface.
     */
    @Test
    public void allSharedPreferencesUseADocumentedFileName() throws IOException {
        String prefs = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBPreferences.java");
        String usbProfile = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBUsbProfile.java");
        String diagnostics = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBDiagnostics.java");

        assertTrue(SHARED_PREFS_NAME.matcher(prefs).find());
        assertTrue(SHARED_PREFS_NAME.matcher(usbProfile).find());
        assertTrue(SHARED_PREFS_NAME.matcher(diagnostics).find());

        assertEquals("keepadb_prefs", namedPrefsFile(prefs));
        assertEquals("keepadb_prefs", namedPrefsFile(usbProfile));
        assertEquals("keepadb_diagnostics", namedPrefsFile(diagnostics));
    }

    @Test
    public void debugDiagnosticsJournalIsAppPrivateAndDocumented() throws IOException {
        String journal = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBDiagnosticJournal.java");
        assertTrue(journal.contains("getFilesDir()"));
        assertEquals("keepadb_diagnostics_journal.log", KeepADBDiagnosticJournal.FILE_NAME);
    }

    private static String namedPrefsFile(String source) {
        Matcher matcher = PREFS_NAME_VALUE.matcher(source);
        if (!matcher.find()) {
            throw new IllegalStateException("PREFS_NAME constant not found");
        }
        return matcher.group(1);
    }

    private static Path projectRoot() {
        Path directory = Paths.get("").toAbsolutePath();
        while (directory != null && !Files.exists(directory.resolve("settings.gradle"))) {
            directory = directory.getParent();
        }
        if (directory == null) {
            throw new IllegalStateException("Could not locate project root");
        }
        return directory;
    }

    private static String read(String relativePath) throws IOException {
        return new String(Files.readAllBytes(projectRoot().resolve(relativePath)), StandardCharsets.UTF_8);
    }
}
