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
 * Contracts for async endpoint publication and cross-surface state refresh.
 *
 * <p>#596 (codequality review CQ-03) removed two of this class's original nine tests --
 * {@code connectedStateUsesAnAtomicEndpointPair} and {@code
 * stateContractPinsConnectedAndMissingEndpointDirections} -- which grepped {@code
 * KeepADB.getState()}'s source for method-call presence and branch order to prove its Wi-Fi/
 * endpoint guard structure. {@link KeepADBMultiStateContractTest} already drives {@code
 * getState()} end-to-end for both {@code ENABLED_CONNECTED} (Wi-Fi + cached endpoint) and {@code
 * ENABLED_DISCONNECTED} (Wi-Fi, no endpoint) across many scenarios via its {@code
 * assertMainAction}/{@code assertWidgetAction}/{@code assertTileAction} matrix; #596 added {@link
 * KeepADBMultiStateContractTest#aCachedEndpointNeverReportsConnectedOnceWifiIsDisconnected()} to
 * close the one direction that matrix did not yet cover (a cached endpoint must not be
 * misreported as connected once Wi-Fi itself is down) -- together a full behavioral replacement
 * of what those two source-content tests asserted, proven by outcome rather than by source order.
 *
 * <p>Also removed: {@code activityRendersEveryPersistedWebhookReportState}, which grepped {@code
 * MainActivity#refreshWebhookStatus()}'s source for the {@code WEBHOOK_STATUS_FAILED}/{@code
 * R.string.webhook_status_*} constant names. Replaced by three real behavior tests in {@link
 * MainActivityWebhookStatusTest} ({@code webhookStatusRendersTheFailedReportText}, {@code
 * webhookStatusRendersTheDeregisteredEndpointTextAfterASuccessfulDeregistration}, {@code
 * webhookStatusRendersTheNoEndpointTextWhenNothingWasEverReported}) that drive a real {@code
 * MainActivity} through each of the three distinct last-report outcomes and assert on the
 * actually rendered {@code TextView} text.
 *
 * <p>#601 removed two more of this class's tests -- {@code
 * activityDropsQueuedEndpointCallbacksAfterPauseAndRecreation} and {@code
 * widgetStateRefreshRunsOnMainThreadWithoutStartingDiscovery} -- which the previous revision of
 * this Javadoc had flagged as follow-up work rather than attempted, since real behavior tests for
 * them needed test infrastructure this class's helpers do not provide: a full {@code
 * ActivityController}-driven exercise of {@code MainActivity}'s private {@code
 * endpointListenerGeneration}/{@code endpointSurfaceActive} race guard across pause/resume/
 * recreate, and a real {@code AppWidgetManager} fake for {@code KeepADBWidget}'s main-thread
 * dispatch. Both are now covered by real Robolectric behavior tests instead: {@link
 * MainActivityEndpointListenerGenerationTest#activityDropsQueuedEndpointCallbacksAfterPauseAndRecreation()}
 * drives a real {@code MainActivity} through exactly that {@code ActivityController} pause/
 * resume/recreate choreography and asserts that a callback delivered through a superseded
 * listener/generation never reaches the rendered {@code R.id.endpoint} text, while a
 * current-generation callback does; {@link
 * KeepADBWidgetMainThreadRefreshTest#widgetStateRefreshRunsOnMainThreadWithoutStartingDiscovery()}
 * drives {@code KeepADBWidget.refreshAllState(Context)} from a genuine background {@link Thread}
 * against a real {@code ShadowAppWidgetManager}-backed widget and asserts both that the rendered
 * {@code RemoteViews} text only changes after the main looper runs the posted update, and that no
 * discovery attempt is ever started (via {@code
 * KeepADBEndpointCoordinator#hasActiveDiscoveryAttemptForTesting()}).
 *
 * <p>The remaining five tests stay intentionally static source-content contracts. Each protects a
 * specific internal ordering invariant (a field write landing before a cross-surface refresh
 * fires, a listener registration landing before the call that would otherwise race it) inside
 * {@code KeepADBEndpointCoordinator}'s asynchronous discovery callbacks and {@code
 * MainActivity}'s endpoint-listener/webhook-listener registration order. None of these orderings
 * has an external, black-box-observable outcome distinguishable from source inspection without a
 * raw background {@link Thread} plus real socket check -- already documented as deliberately out
 * of Robolectric's scope by {@link
 * KeepADBNotificationRobolectricTest#wifiNetworkCallbackIsRegisteredAgainstARealConnectivityManager()}.
 * Kept per AGENTS.md/#286: each protects a clearly named invariant and a text check is well suited
 * to it, since the property under test is an ordering fact about the source itself, not a
 * computed runtime value.
 */
public class KeepADBAsyncSurfaceRefreshContractTest {

    @Test
    public void successfulDiscoveryPublishesBeforeRefreshingEverySurface() throws IOException {
        String coordinator = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBEndpointCoordinator.java");
        String discoveryBody = methodBody(coordinator,
                "private static void startDiscoveryDirectLocked(Context appContext, Object discoveryOwner) {");
        String callbackBody = methodBody(discoveryBody,
                discoveryBody.indexOf("public void onEndpoint(String host, int port) {"));
        String refreshBody = methodBody(coordinator,
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
        String coordinator = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBEndpointCoordinator.java");
        String discoveryBody = methodBody(coordinator,
                "private static void startDiscoveryDirectLocked(Context appContext, Object discoveryOwner) {");
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
        String coordinator = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBEndpointCoordinator.java");
        String refreshBody = methodBody(coordinator,
                "private static synchronized void refreshInternal(Context context, Object discoveryOwner) {");

        int unavailable = refreshBody.indexOf("if (endpointListener != null) endpointListener.onUnavailable();");
        int discovery = refreshBody.indexOf("startDiscoveryDirectLocked(appContext, discoveryOwner);");
        int surfaceRefresh = refreshBody.indexOf("postSurfaceRefresh(appContext);");

        assertTrue(unavailable >= 0);
        assertTrue(discovery > unavailable);
        assertTrue(surfaceRefresh > discovery);
    }

    @Test
    public void activityRefreshesStateOnBothEndpointListenerDirections() throws IOException {
        String activity = read("app/src/main/java/de/hohnepeople/keepadb/MainActivity.java");
        int listenerStart = activity.indexOf("KeepADBEndpointCoordinator.setEndpointListener(");
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
    public void activityRegistersWebhookListenerBeforeRefreshingNotification() throws IOException {
        String activity = read("app/src/main/java/de/hohnepeople/keepadb/MainActivity.java");
        String resumeBody = methodBody(activity, "protected void onResume() {");
        String pauseBody = methodBody(activity, "protected void onPause() {");

        int endpointListener = resumeBody.indexOf("KeepADBEndpointCoordinator.setEndpointListener(");
        int registerListener = resumeBody.indexOf(
                "KeepADBRegisterClient.setRegisterStateListener(this::refreshWebhookStatus);");
        int notificationRefresh = resumeBody.indexOf("KeepADBEndpointCoordinator.refresh(this);");

        assertTrue(endpointListener >= 0);
        assertTrue(registerListener > endpointListener);
        assertTrue(notificationRefresh > registerListener);
        assertTrue(pauseBody.contains("KeepADBRegisterClient.clearRegisterStateListener();"));
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
