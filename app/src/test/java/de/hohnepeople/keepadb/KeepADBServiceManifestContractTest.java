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
 * {@code location} foreground-service type while only holding {@code ACCESS_FINE_LOCATION} --
 * never {@code ACCESS_BACKGROUND_LOCATION}, which this project deliberately avoids (see
 * {@code SECURITY.md} and {@code docs/trusted-networks-measurement.md}, "Nachtrag 2", point 2).
 *
 * <p>#626 also found that C2's protection is conditional on the service having been started
 * while the app was in the foreground at least once (see {@code docs/trusted-networks-measurement.md},
 * "Nachtrag 3"): a background-originated service record never gets the while-in-use location
 * capability, regardless of the declared type or granted permission. This test cannot exercise
 * that runtime behavior (no Robolectric/instrumentation here), but it locks down the static
 * manifest contract the whole approach depends on: the declared type still includes {@code
 * location}, the matching {@code FOREGROUND_SERVICE_LOCATION} permission is present, and
 * {@code ACCESS_BACKGROUND_LOCATION} is never declared. Losing any one of these silently would
 * either break the C2 recovery path or regress into the very permission this project avoids.
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

    @Test
    public void backgroundLocationPermissionIsNeverDeclared() throws IOException {
        String manifest = readManifest();
        assertFalse(
                "android.permission.ACCESS_BACKGROUND_LOCATION must never be declared -- C2 (#606) "
                        + "was chosen specifically to avoid it; see docs/trusted-networks-measurement.md",
                manifest.contains("android.permission.ACCESS_BACKGROUND_LOCATION"));
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
