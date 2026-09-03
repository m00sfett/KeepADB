package de.hohnepeople.keepadb;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/** Static contract requiring a Fastlane "what's new" entry for the current versionCode. */
public class KeepADBFastlaneChangelogContractTest {
    @Test
    public void fastlaneChangelogExistsForCurrentVersionCode() throws IOException {
        String buildGradle = read("app/build.gradle");
        Matcher matcher = Pattern.compile("versionCode\\s+(\\d+)").matcher(buildGradle);
        assertTrue("Could not find versionCode in app/build.gradle", matcher.find());
        String versionCode = matcher.group(1);

        Path changelog = projectPath(
                "fastlane/metadata/android/en-US/changelogs/" + versionCode + ".txt");
        assertTrue(
                "Missing fastlane changelog for versionCode " + versionCode + ": " + changelog,
                Files.exists(changelog));
        assertTrue(
                "Fastlane changelog for versionCode " + versionCode + " is empty",
                Files.size(changelog) > 0);
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
        return new String(Files.readAllBytes(projectPath(relativePath)));
    }
}
