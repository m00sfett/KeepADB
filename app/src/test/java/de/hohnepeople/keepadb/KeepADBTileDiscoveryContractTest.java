package de.hohnepeople.keepadb;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Static contracts for the Tile-only discovery entry and its asynchronous refresh. */
public class KeepADBTileDiscoveryContractTest {

    @Test
    public void tileStartsTheExistingRefreshPathBeforeReadingItsState() throws IOException {
        String tile = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBTileService.java");
        int startListening = tile.indexOf("public void onStartListening() {");
        int nextMethod = tile.indexOf("    @Override", startListening + 1);

        assertTrue(startListening >= 0);
        assertTrue(nextMethod > startListening);
        String body = tile.substring(startListening, nextMethod);
        assertTrue(body.contains("KeepADBNotification.refresh(this);")
                && body.indexOf("KeepADBNotification.refresh(this);") < body.indexOf("updateTile();"));
        assertFalse(body.contains("KeepADBPreferences.isKeepAliveEnabled"));
    }

    @Test
    public void discoveryCompletionRequestsTileRefreshAfterPublishingTheEndpoint() throws IOException {
        String notification = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBNotification.java");
        int discoveryStart = notification.indexOf("startDiscoveryDirectLocked(appContext, manager)");
        int callback = notification.indexOf(
                "public void onEndpoint(String host, int port) {", discoveryStart);
        assertTrue(discoveryStart >= 0);
        assertTrue(callback >= 0);
        int callbackBodyStart = notification.indexOf("{", callback);
        assertTrue(callbackBodyStart > callback);
        int callbackEnd = findMatchingBrace(notification, callbackBodyStart);
        assertTrue(callbackEnd > callbackBodyStart);
        String callbackBody = notification.substring(callback, callbackEnd + 1);
        int endpointPublish = callbackBody.indexOf("currentHost = host;");
        int tileRefresh = callbackBody.indexOf("KeepADBTileService.requestRefresh(appContext);");

        assertTrue(endpointPublish >= 0);
        assertTrue(tileRefresh > endpointPublish);
        assertTrue(callbackBody.indexOf("currentPort = port;") < tileRefresh);
    }

    @Test
    public void refreshStartsDiscoveryForAnEnabledTileWithoutAnEndpointCache() throws IOException {
        String notification = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBNotification.java");
        int refreshStart = notification.indexOf("static synchronized void refresh(Context context) {");
        int refreshEnd = notification.indexOf(
                "    private static void verifyCachedEndpointAsync", refreshStart);

        assertTrue(refreshStart >= 0);
        assertTrue(refreshEnd > refreshStart);
        String body = notification.substring(refreshStart, refreshEnd);
        int enabledGuard = body.indexOf("if (!KeepADB.isEnabled(appContext))");
        int cacheBranch = body.indexOf("if (currentHost != null && currentPort > 0)");
        int discovery = body.indexOf("startDiscoveryDirectLocked(appContext, manager);");
        int keepAlivePlaceholder = body.indexOf("if (KeepADBPreferences.isKeepAliveEnabled(appContext))");
        int placeholderBlockEnd = body.indexOf(
                "\n        }\n\n        cancelRetryLocked();", keepAlivePlaceholder);

        assertTrue(enabledGuard >= 0);
        assertTrue(cacheBranch > enabledGuard);
        assertTrue(discovery > cacheBranch);
        assertTrue(keepAlivePlaceholder > cacheBranch);
        assertTrue(placeholderBlockEnd > keepAlivePlaceholder);
        assertTrue(discovery > placeholderBlockEnd);
        assertFalse(body.substring(placeholderBlockEnd, discovery).contains("return;"));
    }

    @Test
    public void tileRefreshRequestIsSafeWithoutAnActiveTileInstance() throws IOException {
        String tile = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBTileService.java");
        int requestRefresh = tile.indexOf("static void requestRefresh(Context context) {");
        int methodEnd = tile.indexOf("\n    }", requestRefresh);

        assertTrue(requestRefresh >= 0);
        assertTrue(methodEnd > requestRefresh);
        String body = tile.substring(requestRefresh, methodEnd);
        assertTrue(body.contains("if (context == null) return;"));
        assertTrue(body.contains("requestListeningState("));
        assertTrue(body.contains("catch (RuntimeException ignored)"));
    }

    @Test
    public void manifestEnablesProcessRefreshRequestsForTheTile() throws IOException {
        String manifest = read("app/src/main/AndroidManifest.xml");
        int serviceStart = manifest.indexOf("android:name=\".KeepADBTileService\"");
        int serviceEnd = manifest.indexOf("</service>", serviceStart);

        assertTrue(serviceStart >= 0);
        assertTrue(serviceEnd > serviceStart);
        String service = manifest.substring(serviceStart, serviceEnd);
        assertTrue(service.contains("android:name=\"android.service.quicksettings.ACTIVE_TILE\""));
        assertTrue(service.contains("android:value=\"true\""));
    }

    @Test
    public void discoverySessionRemainsIdempotent() throws IOException {
        String endpoint = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBEndpoint.java");
        assertTrue(endpoint.contains("if (discovering && !endpointDelivered.get())"));
        assertTrue(endpoint.contains("if (discovering) {\n            stop();\n        }"));
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

    private static int findMatchingBrace(String source, int openingBrace) {
        int depth = 0;
        for (int i = openingBrace; i < source.length(); i++) {
            char current = source.charAt(i);
            if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }
}
