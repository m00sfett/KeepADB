package de.hohnepeople.keepadb;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Contracts for async endpoint publication and cross-surface state refresh. */
public class KeepADBAsyncSurfaceRefreshContractTest {

    @Test
    public void connectedStateUsesAnAtomicEndpointPair() throws IOException {
        String keepAdb = read("app/src/main/java/de/hohnepeople/keepadb/KeepADB.java");
        String notification = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBNotification.java");
        String stateBody = methodBody(keepAdb, "static State getState(Context ctx) {");
        String endpointBody = methodBody(notification, "static synchronized boolean hasCurrentEndpoint() {");

        assertTrue(stateBody.contains("KeepADBNotification.hasCurrentEndpoint()"));
        assertFalse(stateBody.contains("KeepADBNotification.getCurrentHost()"));
        assertFalse(stateBody.contains("KeepADBNotification.getCurrentPort()"));
        assertTrue(endpointBody.contains("currentHost != null && currentPort > 0"));
    }

    @Test
    public void stateContractPinsConnectedAndMissingEndpointDirections() throws IOException {
        String keepAdb = read("app/src/main/java/de/hohnepeople/keepadb/KeepADB.java");
        String stateBody = methodBody(keepAdb, "static State getState(Context ctx) {");

        int wifiGuard = stateBody.indexOf("if (!KeepADBService.isWifiConnected(appContext))");
        int disconnectedWithoutWifi = stateBody.indexOf("return State.ENABLED_DISCONNECTED;", wifiGuard);
        int endpointGuard = stateBody.indexOf("if (KeepADBNotification.hasCurrentEndpoint())");
        int connected = stateBody.indexOf("return State.ENABLED_CONNECTED;", endpointGuard);
        int disconnectedWithoutEndpoint = stateBody.indexOf("return State.ENABLED_DISCONNECTED;", connected);

        assertTrue(wifiGuard >= 0);
        assertTrue(disconnectedWithoutWifi > wifiGuard);
        assertTrue(endpointGuard > disconnectedWithoutWifi);
        assertTrue(connected > endpointGuard);
        assertTrue(disconnectedWithoutEndpoint > connected);
        assertTrue(stateBody.substring(endpointGuard, connected).contains(
                "KeepADBNotification.hasCurrentEndpoint()"));
    }

    @Test
    public void successfulDiscoveryPublishesBeforeRefreshingEverySurface() throws IOException {
        String notification = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBNotification.java");
        String discoveryBody = methodBody(notification,
                "private static void startDiscoveryDirectLocked(Context appContext, NotificationManager manager) {");
        String callbackBody = methodBody(discoveryBody,
                discoveryBody.indexOf("public void onEndpoint(String host, int port) {"));
        String refreshBody = methodBody(notification,
                "private static void postSurfaceRefresh(Context appContext) {");

        int generationGuard = callbackBody.indexOf(
                "if (requestGeneration != discoveryRequestGeneration) return;");
        int host = callbackBody.indexOf("currentHost = host;");
        int port = callbackBody.indexOf("currentPort = port;");
        int refresh = callbackBody.indexOf("postSurfaceRefresh(appContext);");

        assertTrue(generationGuard >= 0);
        assertTrue(host > generationGuard);
        assertTrue(port > host);
        assertTrue(refresh > port);
        assertTrue(refreshBody.contains("MAIN_HANDLER.post("));
        assertTrue(refreshBody.contains("KeepADBWidget.refreshAllState(appContext);"));
        assertTrue(refreshBody.contains("KeepADBTileService.refreshListeningTile();"));
    }

    @Test
    public void unavailableDiscoveryClearsStateBeforeRefreshingSurfaces() throws IOException {
        String notification = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBNotification.java");
        String discoveryBody = methodBody(notification,
                "private static void startDiscoveryDirectLocked(Context appContext, NotificationManager manager) {");
        String callbackBody = methodBody(discoveryBody,
                discoveryBody.indexOf("public void onUnavailable() {"));

        int host = callbackBody.indexOf("currentHost = null;");
        int port = callbackBody.indexOf("currentPort = 0;");
        int refresh = callbackBody.indexOf("postSurfaceRefresh(appContext);");

        assertTrue(host >= 0);
        assertTrue(port > host);
        assertTrue(refresh > port);
        assertFalse(callbackBody.contains("currentHost = host;"));
    }

    @Test
    public void missingEndpointRefreshesDisconnectedSurfacesBeforeRetryingDiscovery() throws IOException {
        String notification = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBNotification.java");
        String refreshBody = methodBody(notification, "static synchronized void refresh(Context context) {");

        int unavailable = refreshBody.indexOf("if (endpointListener != null) endpointListener.onUnavailable();");
        int discovery = refreshBody.indexOf("startDiscoveryDirectLocked(appContext, manager);");
        int surfaceRefresh = refreshBody.indexOf("postSurfaceRefresh(appContext);");

        assertTrue(unavailable >= 0);
        assertTrue(discovery > unavailable);
        assertTrue(surfaceRefresh > discovery);
    }

    @Test
    public void activityRefreshesStateOnBothEndpointListenerDirections() throws IOException {
        String activity = read("app/src/main/java/de/hohnepeople/keepadb/MainActivity.java");
        int listenerStart = activity.indexOf("KeepADBNotification.setEndpointListener(");
        int listenerEnd = activity.indexOf("\n        });", listenerStart);
        assertTrue(listenerStart >= 0);
        assertTrue(listenerEnd > listenerStart);
        String listener = activity.substring(listenerStart, listenerEnd);
        assertCallbackContains(listener, "public void onEndpoint(String host, int port) {",
                "postEndpointAvailable(listenerGeneration, host, port);");
        assertCallbackContains(listener, "public void onUnavailable() {",
                "postEndpointUnavailable(listenerGeneration);");
    }

    @Test
    public void activityDropsQueuedEndpointCallbacksAfterPauseAndRecreation() throws IOException {
        String activity = read("app/src/main/java/de/hohnepeople/keepadb/MainActivity.java");
        String resumeBody = methodBody(activity, "protected void onResume() {");
        String pauseBody = methodBody(activity, "protected void onPause() {");
        String availableBody = methodBody(activity,
                "private void postEndpointAvailable(long listenerGeneration, String host, int port) {");
        String unavailableBody = methodBody(activity,
                "private void postEndpointUnavailable(long listenerGeneration) {");
        String guardBody = methodBody(activity,
                "private boolean isEndpointSurfaceActive(long listenerGeneration) {");

        int resumeGeneration = resumeBody.indexOf(
                "final long listenerGeneration = ++endpointListenerGeneration;");
        int resumeActive = resumeBody.indexOf("endpointSurfaceActive = true;");
        int listenerRegistration = resumeBody.indexOf("KeepADBNotification.setEndpointListener(");
        int pauseInactive = pauseBody.indexOf("endpointSurfaceActive = false;");
        int pauseGeneration = pauseBody.indexOf("endpointListenerGeneration++;");
        int listenerClear = pauseBody.indexOf("KeepADBNotification.clearEndpointListener();");

        assertTrue(resumeGeneration >= 0);
        assertTrue(resumeActive > resumeGeneration);
        assertTrue(listenerRegistration > resumeActive);
        assertTrue(pauseInactive >= 0);
        assertTrue(pauseGeneration > pauseInactive);
        assertTrue(listenerClear > pauseGeneration);
        assertQueuedCallbackIsGuarded(availableBody);
        assertQueuedCallbackIsGuarded(unavailableBody);
        assertTrue(guardBody.contains("endpointSurfaceActive"));
        assertTrue(guardBody.contains("listenerGeneration == endpointListenerGeneration"));
        assertTrue(guardBody.contains("isFinishing()"));
        assertTrue(guardBody.contains("isDestroyed()"));
    }

    @Test
    public void widgetStateRefreshRunsOnMainThreadWithoutStartingDiscovery() throws IOException {
        String widget = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBWidget.java");
        String stateRefreshBody = methodBody(widget, "static void refreshAllState(Context context) {");
        String refreshBody = methodBody(widget,
                "private static void refreshAll(Context context, boolean refreshNotification) {");
        String renderBody = methodBody(widget,
                "private void render(Context context, AppWidgetManager mgr, int id, boolean refreshNotification) {");

        assertTrue(widget.contains("static void refreshAllState(Context context)"));
        assertTrue(stateRefreshBody.contains("refreshAll(context, false);"));
        assertFalse(stateRefreshBody.contains("KeepADBNotification.refresh(context);"));
        assertTrue(refreshBody.contains("Looper.myLooper() != Looper.getMainLooper()"));
        assertTrue(refreshBody.contains("MAIN_HANDLER.post("));
        assertTrue(renderBody.contains("if (refreshNotification)"));
        int conditional = renderBody.indexOf("if (refreshNotification)");
        int notificationRefresh = renderBody.indexOf("KeepADBNotification.refresh(context);");
        assertTrue(notificationRefresh > conditional);
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

    private static void assertCallbackContains(String listener, String signature, String updateCall) {
        int start = listener.indexOf(signature);
        assertTrue("Missing callback: " + signature, start >= 0);
        int nextCallback = listener.indexOf("public void ", start + signature.length());
        if (nextCallback < 0) nextCallback = listener.length();
        String callback = listener.substring(start, nextCallback);
        assertTrue(callback.contains(updateCall));
    }

    private static void assertQueuedCallbackIsGuarded(String callbackBody) {
        int post = callbackBody.indexOf("runOnUiThread");
        int guard = callbackBody.indexOf("if (!isEndpointSurfaceActive(listenerGeneration)) return;");
        int endpoint = callbackBody.indexOf("endpoint.setText");
        int refresh = callbackBody.indexOf("refresh();");
        assertTrue(post >= 0);
        assertTrue(guard > post);
        assertTrue(endpoint > guard);
        assertTrue(refresh > endpoint);
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
