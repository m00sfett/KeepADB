package de.hohnepeople.keepadb;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Static contract for issue #245: the trusted-network allowlist must only ever gate
 * *automatic* re-enable call sites, never a manual/explicit toggle -- and must not require
 * a new {@code KeepADB.State} value, since the exhaustive switches in {@link
 * KeepADBTileService} and {@link KeepADBWidget} (plus {@link KeepADBMultiStateContractTest})
 * would need updating for every existing and future surface if it did.
 */
public class KeepADBTrustedNetworkContractTest {

    @Test
    public void automaticReEnableCallSitesAreGuarded() throws IOException {
        String service = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBService.java");
        String endpoint = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBEndpoint.java");
        String usbHandover = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBUsbHandover.java");

        assertTrue(service.contains("KeepADBTrustedNetwork.isCurrentNetworkTrusted(this)"));
        assertTrue(service.contains("KeepADBTrustedNetwork.isCurrentNetworkTrusted(KeepADBService.this)"));
        assertTrue(endpoint.contains("KeepADBTrustedNetwork.isCurrentNetworkTrusted(appContext)"));
        assertTrue(usbHandover.contains("KeepADBTrustedNetwork.isCurrentNetworkTrusted(appContext)"));
        // The manual "Enable WLAN-ADB" notification action must stay ungated: it's a direct
        // user request, not an automatic re-enable, so it must never mention the allowlist.
        String manualActionBody = methodBody(usbHandover, "static boolean handleManualAction(Context context) {");
        assertFalse(manualActionBody.contains("KeepADBTrustedNetwork"));
    }

    private static String methodBody(String source, String signature) {
        int methodStart = source.indexOf(signature);
        assertTrue("Missing method: " + signature, methodStart >= 0);
        int openingBrace = source.indexOf('{', methodStart);
        assertTrue("Missing opening brace: " + signature, openingBrace > methodStart);
        int depth = 0;
        int methodEnd = -1;
        for (int i = openingBrace; i < source.length(); i++) {
            char current = source.charAt(i);
            if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                methodEnd = i;
                break;
            }
        }
        assertTrue("Missing closing brace: " + signature, methodEnd > openingBrace);
        return source.substring(methodStart, methodEnd + 1);
    }

    @Test
    public void keepAdbFacadeNeverReferencesTheAllowlist() throws IOException {
        // KeepADB.setEnabled()/applyNow() must keep unconditionally honoring an explicit
        // call -- the guard belongs at call sites that decide to auto-invoke it, not inside
        // the toggle facade itself, so manual toggling is never affected by network trust.
        String keepAdb = read("app/src/main/java/de/hohnepeople/keepadb/KeepADB.java");
        assertFalse(keepAdb.contains("KeepADBTrustedNetwork"));
    }

    @Test
    public void noNewStateEnumValueWasIntroduced() throws IOException {
        String keepAdb = read("app/src/main/java/de/hohnepeople/keepadb/KeepADB.java");
        int enumStart = keepAdb.indexOf("enum State {");
        assertTrue(enumStart >= 0);
        String enumDeclaration = keepAdb.substring(enumStart, keepAdb.indexOf('}', enumStart) + 1);
        assertTrue(enumDeclaration.contains("PERMISSION_MISSING"));
        assertTrue(enumDeclaration.contains("ENABLED_DISCONNECTED"));
        assertTrue(enumDeclaration.contains("ENABLED_CONNECTED"));
        assertFalse(enumDeclaration.contains("UNTRUSTED"));
        assertFalse(enumDeclaration.contains("BLOCKED"));
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
