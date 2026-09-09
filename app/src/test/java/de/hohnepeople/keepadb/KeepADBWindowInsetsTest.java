package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Edge-to-edge inset handling (#324). The view layout itself needs a device, so the unit test
 * covers the pure padding rule plus the static contract that both activities are wired up.
 */
public class KeepADBWindowInsetsTest {

    @Test
    public void keyboardWinsOverNavigationBar() {
        assertEquals(720, KeepADBWindowInsets.contentBottomInset(48, 720));
    }

    @Test
    public void navigationBarAppliesWhileKeyboardIsHidden() {
        assertEquals(48, KeepADBWindowInsets.contentBottomInset(48, 0));
    }

    @Test
    public void noInsetsMeanNoExtraSpace() {
        assertEquals(0, KeepADBWindowInsets.contentBottomInset(0, 0));
    }

    @Test
    public void activitiesWireHeaderAndContentInsets() throws IOException {
        String main = read("app/src/main/java/de/hohnepeople/keepadb/MainActivity.java");
        String settings = read("app/src/main/java/de/hohnepeople/keepadb/SettingsActivity.java");
        String mainLayout = read("app/src/main/res/layout/activity_main.xml");
        String settingsLayout = read("app/src/main/res/layout/activity_settings.xml");

        assertTrue(main.contains("KeepADBWindowInsets.apply("));
        assertTrue(main.contains("R.id.header_bar"));
        assertTrue(main.contains("R.id.content_scroll"));
        assertTrue(settings.contains("KeepADBWindowInsets.apply("));
        assertTrue(settings.contains("R.id.header_bar"));
        assertTrue(settings.contains("R.id.settings_scroll_view"));
        assertTrue(mainLayout.contains("android:id=\"@+id/header_bar\""));
        assertTrue(mainLayout.contains("android:id=\"@+id/content_scroll\""));
        assertTrue(settingsLayout.contains("android:id=\"@+id/header_bar\""));
        assertTrue(settingsLayout.contains("android:id=\"@+id/settings_scroll_view\""));
    }

    @Test
    public void insetHandlingCoversSystemBarsAndKeyboard() throws IOException {
        String source =
                read("app/src/main/java/de/hohnepeople/keepadb/KeepADBWindowInsets.java");

        assertTrue(source.contains("setDecorFitsSystemWindows(false)"));
        assertTrue(source.contains("WindowInsets.Type.systemBars()"));
        assertTrue(source.contains("WindowInsets.Type.ime()"));
        assertTrue(source.contains("setOnApplyWindowInsetsListener"));
    }

    private static String read(String relativePath) throws IOException {
        return new String(Files.readAllBytes(projectPath(relativePath)), StandardCharsets.UTF_8);
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
