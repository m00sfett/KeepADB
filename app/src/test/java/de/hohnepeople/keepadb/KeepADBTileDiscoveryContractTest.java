package de.hohnepeople.keepadb;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Static contracts for the normal TileService discovery entry and lifecycle-safe refresh. */
public class KeepADBTileDiscoveryContractTest {

    @Test
    public void tileIsNormalAndNotActive() throws IOException {
        String manifest = read("app/src/main/AndroidManifest.xml");
        int serviceStart = manifest.indexOf("android:name=\".KeepADBTileService\"");
        int serviceEnd = manifest.indexOf("</service>", serviceStart);

        assertTrue(serviceStart >= 0);
        assertTrue(serviceEnd > serviceStart);
        String service = manifest.substring(serviceStart, serviceEnd);
        assertFalse(service.contains("android.service.quicksettings.ACTIVE_TILE"));
    }

    @Test
    public void listeningRegistersBeforeStartingDiscoveryAndRendering() throws IOException {
        String tile = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBTileService.java");
        String body = methodBody(tile, "public void onStartListening() {");

        assertTrue(body.indexOf("registerListeningInstance(this)") >= 0);
        assertTrue(body.indexOf("KeepADBNotification.refresh(this);")
                > body.indexOf("registerListeningInstance(this)"));
        assertTrue(body.indexOf("updateTile();")
                > body.indexOf("KeepADBNotification.refresh(this);"));
        assertFalse(body.contains("KeepADBPreferences.isKeepAliveEnabled"));
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
    public void listeningInstanceIsDiscardedAtBothLifecycleBoundaries() throws IOException {
        String tile = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBTileService.java");
        String stopBody = methodBody(tile, "public void onStopListening() {");
        String destroyBody = methodBody(tile, "public void onDestroy() {");

        assertTrue(stopBody.contains("discardListeningInstance(this)"));
        assertTrue(destroyBody.contains("discardListeningInstance(this)"));
    }

    @Test
    public void discoveryCompletionRefreshesOnlyTheCurrentListeningInstance() throws IOException {
        String notification = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBNotification.java");
        int discoveryStart = notification.indexOf("startDiscoveryDirectLocked(appContext, manager)");
        int callback = notification.indexOf(
                "public void onEndpoint(String host, int port) {", discoveryStart);
        String callbackBody = methodBody(notification, callback);

        assertTrue(discoveryStart >= 0);
        assertTrue(callback >= 0);
        assertTrue(callbackBody.indexOf("currentHost = host;") >= 0);
        assertTrue(callbackBody.indexOf("currentPort = port;") >= 0);
        assertTrue(callbackBody.indexOf("KeepADBTileService.refreshListeningTile();")
                > callbackBody.indexOf("currentPort = port;"));
        assertFalse(callbackBody.contains("requestListeningState("));
        assertFalse(callbackBody.contains("KeepADBTileService.requestRefresh("));
    }

    @Test
    public void refreshRequestsUseTheListeningInstanceWithoutActiveTileBinding() throws IOException {
        String tile = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBTileService.java");
        String requestBody = methodBody(tile, "static void requestRefresh(Context context) {");
        String refreshBody = methodBody(tile, "static void refreshListeningTile() {");

        assertTrue(requestBody.contains("if (context == null) return;"));
        assertTrue(requestBody.contains("refreshListeningTile();"));
        assertFalse(tile.contains("requestListeningState("));
        assertTrue(refreshBody.contains("listeningInstance"));
        assertTrue(refreshBody.contains("!instance.listening"));
        assertTrue(refreshBody.contains("catch (RuntimeException ignored)"));
    }

    @Test
    public void unavailableDiscoveryRefreshesAListeningTileToDisconnectedState() throws IOException {
        String notification = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBNotification.java");
        int callback = notification.indexOf("public void onUnavailable() {");
        String callbackBody = methodBody(notification, callback);

        assertTrue(callback >= 0);
        assertTrue(callbackBody.indexOf("currentHost = null;") >= 0);
        assertTrue(callbackBody.indexOf("currentPort = 0;") >= 0);
        assertTrue(callbackBody.indexOf("KeepADBTileService.refreshListeningTile();")
                > callbackBody.indexOf("currentPort = 0;"));
    }

    @Test
    public void notificationRejectsCallbacksFromSupersededDiscoveryRequests() throws IOException {
        String notification = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBNotification.java");
        String discoveryBody = methodBody(notification,
                "private static void startDiscoveryDirectLocked(Context appContext, NotificationManager manager) {");
        int generation = discoveryBody.indexOf(
                "final long requestGeneration = ++discoveryRequestGeneration;");
        int discover = discoveryBody.indexOf("endpoint.discover(");
        int endpointCallback = discoveryBody.indexOf("public void onEndpoint(String host, int port) {");
        int unavailableCallback = discoveryBody.indexOf("public void onUnavailable() {");
        String endpointBody = methodBody(discoveryBody, endpointCallback);
        String unavailableBody = methodBody(discoveryBody, unavailableCallback);
        int endpointGuard = endpointBody.indexOf(
                "if (requestGeneration != discoveryRequestGeneration) return;");
        int endpointPublish = endpointBody.indexOf("currentHost = host;");
        int unavailableGuard = unavailableBody.indexOf(
                "if (requestGeneration != discoveryRequestGeneration) return;");
        int unavailablePublish = unavailableBody.indexOf("currentHost = null;");
        String invalidateBody = methodBody(notification,
                "static synchronized void invalidateEndpoint(Context context) {");
        String stopBody = methodBody(notification,
                "private static synchronized void stop(Context context, NotificationManager manager) {");

        assertTrue(generation >= 0);
        assertTrue(discover > generation);
        assertTrue(endpointGuard >= 0);
        assertTrue(endpointPublish > endpointGuard);
        assertTrue(unavailableGuard >= 0);
        assertTrue(unavailablePublish > unavailableGuard);
        assertTrue(invalidateBody.contains("discoveryRequestGeneration++;"));
        assertTrue(stopBody.contains("discoveryRequestGeneration++;"));
    }

    @Test
    public void endpointDiscoverySessionRemainsIdempotentAndGuardsItsInternalCallbacks() throws IOException {
        String endpoint = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBEndpoint.java");

        assertTrue(endpoint.contains("if (discovering && !endpointDelivered.get())"));
        assertTrue(endpoint.contains("if (discovering) {\n            stop();\n        }"));
        assertTrue(endpoint.contains("if (!isCurrent(generation) || endpointDelivered.get()"));
        assertTrue(endpoint.contains("if (!isCurrent(generation) || currentResolveAttemptToken != attemptToken"));
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

    private static String methodBody(String source, String signature) {
        int methodStart = source.indexOf(signature);
        assertTrue("Missing method: " + signature, methodStart >= 0);
        int openingBrace = source.indexOf('{', methodStart);
        assertTrue("Missing opening brace: " + signature, openingBrace > methodStart);
        int methodEnd = findMatchingBrace(source, openingBrace);
        assertTrue("Missing closing brace: " + signature, methodEnd > openingBrace);
        return source.substring(methodStart, methodEnd + 1);
    }

    private static String methodBody(String source, int methodStart) {
        assertTrue("Missing method", methodStart >= 0);
        int openingBrace = source.indexOf('{', methodStart);
        assertTrue("Missing opening brace", openingBrace > methodStart);
        int methodEnd = findMatchingBrace(source, openingBrace);
        assertTrue("Missing closing brace", methodEnd > openingBrace);
        return source.substring(methodStart, methodEnd + 1);
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
