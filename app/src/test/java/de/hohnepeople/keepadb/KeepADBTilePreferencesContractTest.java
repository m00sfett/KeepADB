package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
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
 * #953: a long press on the Quick Settings tile asks the system for an activity that answers
 * {@code QS_TILE_PREFERENCES}; without one Android falls back to the app-info page. The home
 * screen is that activity, and this contract keeps the filter from disappearing silently.
 */
public class KeepADBTilePreferencesContractTest {
    private static final String ACTION = "android.service.quicksettings.action.QS_TILE_PREFERENCES";

    @Test
    public void mainActivityAnswersTheTilePreferencesAction() throws IOException {
        String manifest = read("app/src/main/AndroidManifest.xml");
        Matcher activity = Pattern.compile(
                "<activity\\b[^>]*android:name=\"\\.MainActivity\"[^>]*>(.*?)</activity>",
                Pattern.DOTALL).matcher(manifest);
        assertTrue("MainActivity is not declared", activity.find());
        assertTrue("MainActivity must be exported so the system can start it from the tile",
                activity.group().contains("android:exported=\"true\""));

        Matcher filters = Pattern.compile("<intent-filter>(.*?)</intent-filter>", Pattern.DOTALL)
                .matcher(activity.group(1));
        int withTileAction = 0;
        while (filters.find()) {
            if (filters.group(1).contains("android:name=\"" + ACTION + "\"")) {
                withTileAction++;
            }
        }
        assertEquals("MainActivity must declare an intent filter for " + ACTION + " (#953)",
                1, withTileAction);
    }

    @Test
    public void onlyMainActivityDeclaresTheTilePreferencesAction() throws IOException {
        String manifest = read("app/src/main/AndroidManifest.xml");
        int first = manifest.indexOf(ACTION);
        assertTrue(first >= 0);
        assertEquals("exactly one component may answer the action", -1,
                manifest.indexOf(ACTION, first + 1));
        int mainStart = manifest.indexOf("android:name=\".MainActivity\"");
        int mainEnd = manifest.indexOf("</activity>", mainStart);
        assertTrue("the action belongs to MainActivity", first > mainStart && first < mainEnd);
    }

    private static String read(String relativePath) throws IOException {
        Path directory = Paths.get("").toAbsolutePath();
        while (directory != null && !Files.exists(directory.resolve("settings.gradle"))) {
            directory = directory.getParent();
        }
        if (directory == null) {
            throw new IllegalStateException("Could not locate project root");
        }
        return new String(Files.readAllBytes(directory.resolve(relativePath)),
                StandardCharsets.UTF_8);
    }
}
