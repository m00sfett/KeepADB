package de.hohnepeople.keepadb;

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

    @Test
    public void currentFastlaneChangelogStaysWithinFdroidLimit() throws IOException {
        String versionCode = currentVersionCode();
        Path changelog = projectPath(
                "fastlane/metadata/android/en-US/changelogs/" + versionCode + ".txt");
        String text = new String(Files.readAllBytes(changelog), StandardCharsets.UTF_8);
        int length = characterCount(text);
        assertTrue(
                "Fastlane changelog for versionCode " + versionCode + " has " + length
                        + " characters, limit is " + MAX_CHANGELOG_CHARACTERS,
                isWithinLimit(text));
    }

    @Test
    public void limitBoundaryIsInclusive() {
        assertTrue(isWithinLimit(repeat("a", MAX_CHANGELOG_CHARACTERS)));
        assertFalse(isWithinLimit(repeat("a", MAX_CHANGELOG_CHARACTERS + 1)));
        // Supplementary code points count once, not as two UTF-16 units.
        assertTrue(isWithinLimit(repeat("\uD83D\uDE00", MAX_CHANGELOG_CHARACTERS)));
        assertFalse(isWithinLimit(repeat("\uD83D\uDE00", MAX_CHANGELOG_CHARACTERS + 1)));
    }

    private static final int MAX_CHANGELOG_CHARACTERS = 500;

    static int characterCount(String text) {
        return text.codePointCount(0, text.length());
    }

    static boolean isWithinLimit(String text) {
        return characterCount(text) <= MAX_CHANGELOG_CHARACTERS;
    }

    private static String repeat(String unit, int times) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < times; i++) {
            builder.append(unit);
        }
        return builder.toString();
    }

    private static String currentVersionCode() throws IOException {
        Matcher matcher = Pattern.compile("versionCode\\s+(\\d+)")
                .matcher(read("app/build.gradle"));
        assertTrue("Could not find versionCode in app/build.gradle", matcher.find());
        return matcher.group(1);
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
