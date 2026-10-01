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

/**
 * Static contract for the C2 decision (#606, measured in #626): {@link KeepADBService} keeps
 * its while-in-use-only location footprint. C2 relies on the service qualifying for the
 * {@code location} foreground-service type while holding {@code ACCESS_FINE_LOCATION}; since #616
 * {@code ACCESS_BACKGROUND_LOCATION} is additionally declared as an optional, user-set grant for
 * the background-start path C2 cannot cover (see
 * {@code docs/archive/trusted-networks-measurements-2026-09.md}).
 *
 * <p>#626 also found that C2's protection is conditional on the service having been started
 * while the app was in the foreground at least once (see
 * {@code docs/archive/trusted-networks-measurements-2026-09.md}, "Nachtrag 3"): without
 * ACCESS_BACKGROUND_LOCATION, a background-originated service record
 * cannot rely on the declared location type alone for while-in-use access. A missing while-in-use
 * location permission also prevents access; the optional background grant with the required
 * location permission can exempt this while-in-use restriction. Other background-FGS start rules
 * still apply. This test cannot exercise
 * that runtime behavior (no Robolectric/instrumentation here), but it locks down the static
 * manifest contract the whole approach depends on: the declared type still includes {@code
 * location}, the matching {@code FOREGROUND_SERVICE_LOCATION} permission is present, and
 * {@code ACCESS_BACKGROUND_LOCATION} is declared but never requested through a runtime dialog.
 */
public class KeepADBServiceManifestContractTest {
    private static final Pattern KEEPADB_SERVICE = Pattern.compile(
            "<service\\b[^>]*android:name=\"\\.KeepADBService\"[^>]*/?>", Pattern.DOTALL);

    @Test
    public void keepADBServiceDeclaresConnectedDeviceAndLocationType() throws IOException {
        String manifest = readManifest();
        String serviceElement = extractKeepADBServiceElement(manifest);
        assertTrue(
                "<service android:name=\".KeepADBService\"> must declare "
                        + "android:foregroundServiceType=\"connectedDevice|location\" (#606, C2)",
                serviceElement.contains("android:foregroundServiceType=\"connectedDevice|location\""));
    }

    @Test
    public void foregroundServiceLocationPermissionIsDeclared() throws IOException {
        String manifest = readManifest();
        assertTrue(
                "android.permission.FOREGROUND_SERVICE_LOCATION must be declared -- required to "
                        + "start a foreground service with the \"location\" type (#606, C2)",
                manifest.contains(
                        "<uses-permission android:name=\"android.permission.FOREGROUND_SERVICE_LOCATION\""));
    }

    /**
     * #616 reverses the former "never declared" contract: the user opted into an optional
     * background grant for the background-start path C2 cannot cover (#626/#630). It must be
     * declared (otherwise Android never offers "Allow all the time"), but it is never requested
     * through a runtime dialog -- the user sets it on the app's permission page.
     */
    @Test
    public void backgroundLocationPermissionIsDeclaredButNeverRequestedAtRuntime() throws IOException {
        String manifest = readManifest();
        assertTrue(
                "android.permission.ACCESS_BACKGROUND_LOCATION must be declared (#616)",
                manifest.contains(
                        "<uses-permission android:name=\"android.permission.ACCESS_BACKGROUND_LOCATION\""));
        for (String source : new String[]{"MainActivity.java", "SettingsActivity.java",
                "KeepADBBackgroundLocation.java", "KeepADBNetworkTrustPrompt.java"}) {
            String code = read("app/src/main/java/de/hohnepeople/keepadb/" + source);
            Matcher request = Pattern.compile("requestPermissions\\([^;]*ACCESS_BACKGROUND_LOCATION",
                    Pattern.DOTALL).matcher(code);
            assertFalse(source + " must never request ACCESS_BACKGROUND_LOCATION at runtime (#616)",
                    request.find());
        }
    }

    private static String extractKeepADBServiceElement(String manifest) {
        Matcher matcher = KEEPADB_SERVICE.matcher(manifest);
        if (!matcher.find()) {
            throw new IllegalStateException(
                    "<service android:name=\".KeepADBService\"> not found in AndroidManifest.xml");
        }
        return matcher.group();
    }

    private static String readManifest() throws IOException {
        return read("app/src/main/AndroidManifest.xml");
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
