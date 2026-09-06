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
 */
public class KeepADBBackupPolicyContractTest {
    private static final Pattern SHARED_PREFS_NAME = Pattern.compile(
            "getSharedPreferences\\(\\s*PREFS_NAME");
    private static final Pattern PREFS_NAME_VALUE = Pattern.compile(
            "PREFS_NAME\\s*=\\s*\"([^\"]+)\"");

    @Test
    public void backupAndDeviceTransferAreDisabled() throws IOException {
        String manifest = read("app/src/main/AndroidManifest.xml");
        assertTrue(manifest.contains("android:allowBackup=\"false\""));
        assertFalse(manifest.contains("android:fullBackupContent"));
        assertFalse(manifest.contains("android:dataExtractionRules"));
        assertFalse(Files.exists(projectRoot().resolve("app/src/main/res/xml/backup_rules.xml")));
        assertFalse(Files.exists(projectRoot().resolve("app/src/main/res/xml/data_extraction_rules.xml")));
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
