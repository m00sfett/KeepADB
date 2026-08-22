package de.hohnepeople.keepadb;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import org.junit.Test;

/** Static contracts for UI/accessibility resources that do not require an Android runtime. */
public class KeepADBAccessibilityContractTest {

    @Test
    public void interactiveLayoutsKeepMinimumTouchTargets() throws IOException {
        String main = read("app/src/main/res/layout/activity_main.xml");
        String settings = read("app/src/main/res/layout/activity_settings.xml");
        String widget = read("app/src/main/res/layout/widget_keepadb.xml");

        assertTrue(main.contains("android:layout_width=\"48dp\""));
        assertTrue(main.contains("android:layout_height=\"48dp\""));
        assertTrue(main.contains("android:minHeight=\"48dp\""));
        assertTrue(settings.contains("android:layout_width=\"48dp\""));
        assertTrue(settings.contains("android:layout_height=\"48dp\""));
        assertTrue(settings.contains("android:minHeight=\"48dp\""));
        assertTrue(widget.contains("android:minWidth=\"48dp\""));
        assertTrue(widget.contains("android:minHeight=\"48dp\""));
    }

    @Test
    public void backNavigationIsRtlAdaptive() throws IOException {
        String layout = read("app/src/main/res/layout/activity_settings.xml");
        String drawable = read("app/src/main/res/drawable/ic_arrow_back.xml");

        assertTrue(layout.contains("android:src=\"@drawable/ic_arrow_back\""));
        assertTrue(drawable.contains("android:autoMirrored=\"true\""));
    }

    @Test
    public void everyLocaleProvidesAccessibilityAndPermissionText() throws IOException {
        Path valuesRoot = projectPath("app/src/main/res");
        try (Stream<Path> paths = Files.list(valuesRoot)) {
            List<Path> localeDirectories = paths
                    .filter(path -> path.getFileName().toString().startsWith("values"))
                    .toList();
            assertTrue(localeDirectories.size() >= 19);
            for (Path directory : localeDirectories) {
                Path strings = directory.resolve("strings.xml");
                assertTrue("Missing strings.xml in " + directory, Files.exists(strings));
                String content = readFile(strings);
                assertTrue("Missing notification permission title in " + directory,
                        content.contains("name=\"notification_permission_panel_title\""));
                assertTrue("Missing notification settings action in " + directory,
                        content.contains("name=\"notification_permission_settings_button\""));
                assertTrue("Missing back description in " + directory,
                        content.contains("name=\"back\""));
            }
        }
    }

    @Test
    public void mainActivityHeaderIncludesVisualAppIcon() throws IOException {
        String main = read("app/src/main/res/layout/activity_main.xml");
        assertTrue(main.contains("android:src=\"@drawable/ic_keepadb\""));
        assertTrue(main.contains("android:importantForAccessibility=\"no\""));
        int iconIndex = main.indexOf("android:src=\"@drawable/ic_keepadb\"");
        int titleIndex = main.indexOf("android:text=\"@string/title_keepadb\"");
        int settingsIndex = main.indexOf("android:id=\"@+id/btn_open_settings\"");
        assertTrue(iconIndex < titleIndex);
        assertTrue(titleIndex < settingsIndex);
    }

    @Test
    public void vectorDrawableKeepADBMatchesNotificationStandard() throws IOException {
        String xml = read("app/src/main/res/drawable/ic_keepadb.xml");
        assertTrue(xml.contains("<vector"));
        assertTrue(xml.contains("android:width=\"24dp\""));
        assertTrue(xml.contains("android:height=\"24dp\""));
        assertTrue(xml.contains("android:viewportWidth=\"24\""));
        assertTrue(xml.contains("android:viewportHeight=\"24\""));
        assertTrue(xml.contains("android:fillColor=\"@color/bright_yellow\""));
    }

    private static String read(String relativePath) throws IOException {
        return readFile(projectPath(relativePath));
    }

    private static String readFile(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
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
}
