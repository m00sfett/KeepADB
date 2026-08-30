package de.hohnepeople.keepadb;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import org.junit.Test;

/** Static contracts for the settings version metadata display. */
public class KeepADBVersionContractTest {
    @Test
    public void settingsDisplaysPackageMetadataAtTheEnd() throws IOException {
        String layout = read("app/src/main/res/layout/activity_settings.xml");
        String activity = read("app/src/main/java/de/hohnepeople/keepadb/SettingsActivity.java");
        String strings = read("app/src/main/res/values/strings.xml");
        String germanStrings = read("app/src/main/res/values-de/strings.xml");

        assertTrue(layout.contains("android:id=\"@+id/settings_version_panel\""));
        assertTrue(layout.contains("android:id=\"@+id/settings_version_name\""));
        assertTrue(layout.contains("android:id=\"@+id/settings_version_code\""));
        assertTrue(layout.contains("android:text=\"@string/settings_section_version\""));
        int versionPanelIndex = layout.indexOf("android:id=\"@+id/settings_version_panel\"");
        assertTrue(layout.indexOf("settings_security_panel") < versionPanelIndex);
        assertTrue(layout.indexOf("</LinearLayout>\n\n    </LinearLayout>", versionPanelIndex) > versionPanelIndex);
        assertTrue(activity.contains("getPackageManager().getPackageInfo(getPackageName(), 0)"));
        assertTrue(activity.contains("bindVersionInfo();"));
        assertTrue(activity.contains("packageInfo.versionName"));
        assertTrue(activity.contains("packageInfo.getLongVersionCode()"));
        assertTrue(activity.contains("settings_version_unavailable"));
        assertTrue(strings.contains("name=\"settings_version_value\""));
        assertTrue(strings.contains("%1$s"));
        assertTrue(strings.contains("name=\"settings_version_code_value\""));
        assertTrue(strings.contains("%1$d"));
        assertTrue(germanStrings.contains("name=\"settings_version_value\""));
        assertTrue(germanStrings.contains("name=\"settings_version_code_value\""));

        Path valuesRoot = projectPath("app/src/main/res");
        try (Stream<Path> paths = Files.list(valuesRoot)) {
            paths.filter(path -> path.getFileName().toString().startsWith("values"))
                    .forEach(path -> {
                        try {
                            String localeStrings = new String(Files.readAllBytes(path.resolve("strings.xml")),
                                    StandardCharsets.UTF_8);
                            assertTrue(localeStrings.contains("name=\"settings_section_version\""));
                            assertTrue(localeStrings.contains("name=\"settings_version_value\""));
                            assertTrue(localeStrings.contains("name=\"settings_version_code_value\""));
                            assertTrue(localeStrings.contains("name=\"settings_version_unavailable\""));
                            assertTrue(localeStrings.contains("%1$s"));
                            assertTrue(localeStrings.contains("%1$d"));
                        } catch (IOException exception) {
                            throw new RuntimeException(exception);
                        }
                    });
        }
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
