package de.hohnepeople.keepadb;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Static contract for issue #247: cleartext HTTP is permitted globally only because the
 * webhook target host is user-supplied and unknown at build time, and that scope is
 * explained in code and paired with an in-app warning when configured.
 */
public class KeepADBNetworkSecurityContractTest {

    @Test
    public void cleartextTrafficConfigDocumentsWhyItCannotBeScopedToTheWebhookHost() throws IOException {
        String config = read("app/src/main/res/xml/network_security_config.xml");
        assertTrue(config.contains("cleartextTrafficPermitted=\"true\""));
        assertTrue(config.contains("#247"));
        assertTrue(config.contains("KeepADBRegisterClient"));
        assertTrue(config.contains("network-security-config only supports scoping cleartext"));
    }

    @Test
    public void settingsWarnsWhenAnHttpWebhookUrlIsConfigured() throws IOException {
        String settings = read("app/src/main/java/de/hohnepeople/keepadb/SettingsActivity.java");
        assertTrue(settings.contains("webhookCleartextWarning"));
        assertTrue(settings.contains("startsWith(\"http://\")"));

        String layout = read("app/src/main/res/layout/activity_settings.xml");
        assertTrue(layout.contains("settings_webhook_cleartext_warning"));

        String strings = read("app/src/main/res/values/strings.xml");
        assertTrue(strings.contains("name=\"settings_webhook_cleartext_warning\""));
    }

    private static String read(String relativePath) throws IOException {
        Path directory = Paths.get("").toAbsolutePath();
        while (directory != null && !Files.exists(directory.resolve("settings.gradle"))) {
            directory = directory.getParent();
        }
        if (directory == null) {
            throw new IllegalStateException("Could not locate project root");
        }
        return new String(Files.readAllBytes(directory.resolve(relativePath)), StandardCharsets.UTF_8);
    }
}
