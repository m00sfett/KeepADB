package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
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
        assertTrue(body.indexOf("KeepADBNotification.refreshForTile(this, this);")
                > body.indexOf("registerListeningInstance(this)"));
        assertTrue(body.indexOf("updateTile();")
                > body.indexOf("KeepADBNotification.refreshForTile(this, this);"));
        assertFalse(body.contains("KeepADBPreferences.isKeepAliveEnabled"));
    }

    @Test
    public void refreshStartsDiscoveryForAnEnabledTileWithoutAnEndpointCache() throws IOException {
        String notification = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBNotification.java");
        int refreshStart = notification.indexOf(
                "private static synchronized void refreshInternal(Context context, Object discoveryOwner) {");
        int refreshEnd = notification.indexOf(
                "    private static void verifyCachedEndpointAsync", refreshStart);

        assertTrue(refreshStart >= 0);
        assertTrue(refreshEnd > refreshStart);
        String body = notification.substring(refreshStart, refreshEnd);
        int enabledGuard = body.indexOf("if (!KeepADB.isEnabled(appContext))");
        int cacheBranch = body.indexOf("if (currentHost != null && currentPort > 0)");
        int discovery = body.indexOf("startDiscoveryDirectLocked(appContext, manager, discoveryOwner);");
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
    public void disabledWirelessDebuggingStopsBeforeDiscovery() throws IOException {
        String notification = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBNotification.java");
        String body = methodBody(notification,
                "private static synchronized void refreshInternal(Context context, Object discoveryOwner) {");
        int enabledGuard = body.indexOf("if (!KeepADB.isEnabled(appContext))");
        int stop = body.indexOf("stop(appContext, manager);", enabledGuard);
        int stopReturn = body.indexOf("return;", stop);
        int discovery = body.indexOf("startDiscoveryDirectLocked(appContext, manager, discoveryOwner);");

        assertTrue(enabledGuard >= 0);
        assertTrue(stop > enabledGuard);
        assertTrue(stopReturn > stop);
        assertTrue(discovery > stopReturn);
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
    public void tileOwnsAndCancelsItsDiscoverySessionAtBothLifecycleBoundaries() throws IOException {
        String tile = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBTileService.java");
        String notification = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBNotification.java");
        String startBody = methodBody(tile, "public void onStartListening() {");
        String stopBody = methodBody(tile, "public void onStopListening() {");
        String destroyBody = methodBody(tile, "public void onDestroy() {");
        String scheduleCancelBody = methodBody(tile, "private void schedulePendingDiscoveryCancel() {");
        String cancelBody = methodBody(notification, "static synchronized void cancelTileDiscovery(Object tileOwner) {");

        assertTrue(startBody.contains("KeepADBNotification.refreshForTile(this, this);"));
        // Issue #267 (3): onStopListening() no longer cancels the discovery synchronously --
        // a brief panel close/reopen must not abort a search that is still in flight. It only
        // schedules the cancellation; onStartListening() cancels that schedule if the panel
        // reopens in time. onDestroy() still cancels immediately, unconditionally.
        assertTrue(startBody.contains("cancelPendingDiscoveryCancel();"));
        assertTrue(stopBody.contains("schedulePendingDiscoveryCancel();"));
        assertFalse(stopBody.contains("KeepADBNotification.cancelTileDiscovery(this);"));
        assertTrue(scheduleCancelBody.contains("KeepADBNotification.cancelTileDiscovery(this);"));
        assertTrue(scheduleCancelBody.contains("handler.postDelayed("));
        assertTrue(destroyBody.contains("cancelPendingDiscoveryCancel();"));
        assertTrue(destroyBody.contains("KeepADBNotification.cancelTileDiscovery(this);"));
        assertTrue(cancelBody.contains("activeDiscoveryOwner != tileOwner"));
        assertTrue(cancelBody.contains("discoveryRequestGeneration++"));
        assertTrue(cancelBody.contains("endpoint.stop();"));
        assertTrue(cancelBody.contains("endpoint = null;"));
        assertFalse(cancelBody.contains("markUnavailableAsync"));
        assertFalse(cancelBody.contains("manager.cancel"));
        assertFalse(cancelBody.contains("postSurfaceRefresh"));
    }

    @Test
    public void closingThePanelBrieflyDoesNotCancelAnInFlightDiscovery() throws IOException {
        // Regression test for issue #267 (3): before the fix, onStopListening() called
        // KeepADBNotification.cancelTileDiscovery(this) directly and unconditionally, so a
        // 1-2s open/close of the Quick Settings panel aborted a still-running discovery
        // before it could resolve an endpoint. This asserts the grace-period mechanism that
        // replaces that immediate cancellation is actually wired up end to end.
        String tile = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBTileService.java");
        String stopBody = methodBody(tile, "public void onStopListening() {");
        String startBody = methodBody(tile, "public void onStartListening() {");

        assertFalse("onStopListening() must not cancel discovery synchronously anymore",
                stopBody.contains("KeepADBNotification.cancelTileDiscovery(this);"));
        assertTrue("onStopListening() must schedule a delayed cancellation instead",
                stopBody.contains("schedulePendingDiscoveryCancel();"));
        assertTrue("Reopening the panel must cancel the pending delayed cancellation",
                startBody.contains("cancelPendingDiscoveryCancel();"));
        assertTrue(tile.contains("STOP_LISTENING_CANCEL_GRACE_MS"));
    }

    @Test
    public void ownershipFollowsTheLatestTileButNeverAdoptsAnExistingGlobalRun() throws IOException {
        String notification = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBNotification.java");
        String endpoint = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBEndpoint.java");
        String retryBody = methodBody(notification,
                "private static void scheduleRetryLocked(Context appContext, NotificationManager manager) {");
        String startBody = methodBody(notification,
                "private static void startDiscoveryDirectLocked(Context appContext, NotificationManager manager,");
        String discoverBody = methodBody(endpoint,
                "synchronized void discover(Listener listener, boolean allowRecoveryPulse) {");
        String claimBody = methodBody(notification,
                "private static void claimDiscoveryOwnerLocked(Object discoveryOwner) {");

        assertTrue(retryBody.contains("startDiscoveryDirectLocked(appContext, manager, activeDiscoveryOwner);"));
        assertTrue(startBody.contains("claimDiscoveryOwnerLocked(discoveryOwner);"));
        assertTrue(startBody.contains("}, activeDiscoveryOwner == GLOBAL_DISCOVERY_OWNER);"));
        assertTrue(claimBody.contains(
                "activeDiscoveryOwner == null || activeDiscoveryOwner != GLOBAL_DISCOVERY_OWNER"));
        assertTrue(claimBody.contains("activeDiscoveryOwner = discoveryOwner;"));
        assertTrue(discoverBody.contains("!allowRecoveryPulse || recoveryPulseEnabled"));
        assertTrue(discoverBody.contains("stop();"));
    }

    @Test
    public void discoveryRetriesUseAFiniteExponentialBudget() throws IOException {
        String notification = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBNotification.java");
        String retryBody = methodBody(notification,
                "private static void scheduleRetryLocked(Context appContext, NotificationManager manager) {");

        assertTrue(retryBody.contains("MAX_RETRY_ATTEMPTS"));
        assertTrue(retryBody.contains("retryAttempt >= MAX_RETRY_ATTEMPTS"));
        assertTrue(retryBody.contains("retryDelayMsForAttemptForTesting(retryAttempt)"));
        assertTrue(retryBody.contains("KeepADBDiagnostics.event(appContext, \"endpoint_discovery\""));
        String exhausted = methodBody(retryBody, "if (retryAttempt >= MAX_RETRY_ATTEMPTS) {");
        assertTrue(exhausted.contains("activeDiscoveryOwner = null;"));
        assertTrue(exhausted.contains("return;"));
        assertFalse(exhausted.contains("postDelayed("));
        assertFalse(exhausted.contains("startDiscoveryDirectLocked("));
        assertTrue(retryBody.indexOf("if (retryAttempt >= MAX_RETRY_ATTEMPTS)")
                < retryBody.indexOf("MAIN_HANDLER.postDelayed("));

        String delayMethod = methodBody(notification,
                "static long retryDelayMsForAttemptForTesting(int attempt) {");
        assertTrue(delayMethod.contains("RETRY_DELAY_MAX_MS"));
        assertTrue(delayMethod.contains("Math.min(delay, RETRY_DELAY_MAX_MS)"));
        assertTrue(delayMethod.contains("delay *= 2;"));
    }

    @Test
    public void cachedEndpointVerificationRejectsStaleAttemptTokens() throws IOException {
        String notification = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBNotification.java");
        String verifyBody = methodBody(notification,
                "private static void verifyCachedEndpointAsync(Context appContext, NotificationManager manager,");

        assertTrue(verifyBody.contains("final long verificationToken"));
        assertTrue(verifyBody.contains("++endpointVerificationToken"));
        assertTrue(verifyBody.contains("if (verificationToken != endpointVerificationToken) return;"));
        int tokenGuard = verifyBody.indexOf(
                "if (verificationToken != endpointVerificationToken) return;");
        int worker = verifyBody.indexOf("new Thread(() -> {");
        int lock = verifyBody.indexOf("synchronized (KeepADBNotification.class) {", worker);
        int lockEnd = findMatchingBrace(verifyBody, verifyBody.indexOf('{', lock));
        assertTrue(tokenGuard > lock && tokenGuard < lockEnd);
        for (String mutation : new String[] { "stop(appContext, manager);",
                "activeDiscoveryOwner = null;", "shouldLogReachable()",
                "resetReachableConfirmed();", "currentHost = null;", "currentPort = 0;",
                "endpointListener.onUnavailable();", "cancelRetryLocked();",
                "startDiscoveryDirectLocked(appContext, manager, discoveryOwner);" }) {
            int position = verifyBody.indexOf(mutation);
            assertTrue(mutation + " must follow the token guard under the same lock",
                    position > tokenGuard && position < lockEnd);
        }

        // Refusing Tile ownership must not first invalidate the global worker's token.
        int ownershipGuard = verifyBody.indexOf("if (activeDiscoveryOwner != discoveryOwner) return;");
        int capture = verifyBody.indexOf("verificationToken = ++endpointVerificationToken;");
        assertTrue(ownershipGuard >= 0 && ownershipGuard < capture && capture < worker);

        String wifiBranch = methodBody(verifyBody,
                "if (KeepADBService.isWifiConnected(appContext)) {");
        assertTrue(wifiBranch.contains("startDiscoveryDirectLocked(appContext, manager, discoveryOwner);"));
        assertTrue(verifyBody.indexOf("if (!KeepADB.isEnabled(appContext))") <
                verifyBody.indexOf("if (reachable)"));

        for (String signature : new String[] {
                "static synchronized void resetForTesting() {",
                "static synchronized void invalidateEndpoint(Context context) {",
                "private static void startDiscoveryDirectLocked(Context appContext, NotificationManager manager,",
                "private static synchronized void stop(Context context, NotificationManager manager) {",
                "static synchronized void cancelTileDiscovery(Object tileOwner) {"
        }) {
            assertTrue(signature + " must invalidate older verification attempts",
                    methodBody(notification, signature).contains("endpointVerificationToken++"));
        }
        String replacement = methodBody(notification, "public void onEndpoint(String host, int port) {");
        int replacementToken = replacement.indexOf("endpointVerificationToken++;");
        assertTrue(replacementToken > replacement.indexOf(
                "if (requestGeneration != discoveryRequestGeneration) return;"));
        assertTrue(replacementToken < replacement.indexOf("currentHost = host;"));
        assertTrue(replacementToken < replacement.indexOf("currentPort = port;"));
    }

    @Test
    public void tileOnlyDiscoveryCannotScheduleTheGlobalRecoveryPulse() throws IOException {
        String endpoint = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBEndpoint.java");
        String discoverBody = methodBody(endpoint,
                "synchronized void discover(Listener listener, boolean allowRecoveryPulse) {");
        String stopBody = methodBody(endpoint, "synchronized void stop() {");
        int pulseGuard = discoverBody.indexOf("if (allowRecoveryPulse)");
        int pulseSchedule = discoverBody.indexOf("scheduler.postDelayed(recoveryPulseRunnable");

        assertTrue(pulseGuard >= 0);
        assertTrue(pulseSchedule > pulseGuard);
        assertTrue(stopBody.contains("recoveryPulseEnabled = false;"));
    }

    @Test
    public void activityWidgetAndKeepAlivePathsRetainGlobalDiscoveryOwnership() throws IOException {
        String notification = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBNotification.java");
        String activity = read("app/src/main/java/de/hohnepeople/keepadb/MainActivity.java");
        String widget = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBWidget.java");
        String service = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBService.java");
        String refreshBody = methodBody(notification, "static synchronized void refresh(Context context) {");
        String healthBody = methodBody(notification,
                "static synchronized void verifyEndpointHealth(Context context) {");

        assertTrue(refreshBody.contains("refreshInternal(context, GLOBAL_DISCOVERY_OWNER);"));
        assertTrue(healthBody.contains("claimDiscoveryOwnerLocked(GLOBAL_DISCOVERY_OWNER);"));
        assertTrue(activity.contains("KeepADBNotification.refresh(this);"));
        assertTrue(widget.contains("KeepADBNotification.refresh(context);"));
        assertTrue(service.contains("KeepADBNotification.refresh(this);"));
        assertFalse(activity.contains("refreshForTile"));
        assertFalse(widget.contains("refreshForTile"));
        assertFalse(service.contains("refreshForTile"));
    }

    @Test
    public void tileOwnedCachedVerificationCannotStartDiscoveryAfterLifecycleCancellation() throws IOException {
        String notification = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBNotification.java");
        String refreshBody = methodBody(notification,
                "private static synchronized void refreshInternal(Context context, Object discoveryOwner) {");
        String verifyBody = methodBody(notification,
                "private static void verifyCachedEndpointAsync(Context appContext, NotificationManager manager,");

        int assignOwner = refreshBody.indexOf("claimDiscoveryOwnerLocked(discoveryOwner);");
        int verify = refreshBody.indexOf(
                "verifyCachedEndpointAsync(appContext, manager, currentHost, currentPort, discoveryOwner);");
        int ownerGuard = verifyBody.indexOf(
                "if (activeDiscoveryOwner != discoveryOwner) return;");
        int clearHost = verifyBody.indexOf("currentHost = null;");
        int rediscover = verifyBody.indexOf(
                "startDiscoveryDirectLocked(appContext, manager, discoveryOwner);");

        assertTrue(assignOwner >= 0);
        assertTrue(verify > assignOwner);
        assertTrue(ownerGuard >= 0);
        assertTrue(clearHost > ownerGuard);
        assertTrue(rediscover > clearHost);
    }

    @Test
    public void cancellationRejectsLateResultsAndLeavesANewSessionPossible() throws IOException {
        String notification = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBNotification.java");
        String cancelBody = methodBody(notification,
                "static synchronized void cancelTileDiscovery(Object tileOwner) {");
        String discoveryBody = methodBody(notification,
                "private static void startDiscoveryDirectLocked(Context appContext, NotificationManager manager,");
        String endpointBody = methodBody(discoveryBody,
                discoveryBody.indexOf("public void onEndpoint(String host, int port) {"));
        String unavailableBody = methodBody(discoveryBody,
                discoveryBody.indexOf("public void onUnavailable() {"));

        assertTrue(cancelBody.contains("discoveryRequestGeneration++;"));
        assertTrue(cancelBody.contains("endpoint = null;"));
        int createEndpoint = discoveryBody.indexOf(
                "if (endpoint == null) endpoint = new KeepADBEndpoint(appContext);");
        int nextGeneration = discoveryBody.indexOf(
                "final long requestGeneration = ++discoveryRequestGeneration;");
        assertTrue(createEndpoint >= 0);
        assertTrue(nextGeneration > createEndpoint);
        assertPublicationIsGenerationLocked(endpointBody,
                "show(appContext, manager, host, port);",
                "KeepADBRegisterClient.updateEndpointAsync(appContext, host, port);");
        assertPublicationIsGenerationLocked(unavailableBody,
                "manager.cancel(NOTIFICATION_ID);",
                "KeepADBRegisterClient.markUnavailableAsync(appContext);");
    }

    @Test
    public void discoveryCompletionRefreshesOnlyTheCurrentListeningInstance() throws IOException {
        String notification = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBNotification.java");
        int discoveryStart = notification.indexOf("startDiscoveryDirectLocked(appContext, manager,");
        int callback = notification.indexOf(
                "public void onEndpoint(String host, int port) {", discoveryStart);
        String callbackBody = methodBody(notification, callback);

        assertTrue(discoveryStart >= 0);
        assertTrue(callback >= 0);
        assertTrue(callbackBody.indexOf("currentHost = host;") >= 0);
        assertTrue(callbackBody.indexOf("currentPort = port;") >= 0);
        assertTrue(callbackBody.indexOf("postSurfaceRefresh(appContext);")
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
        assertTrue(callbackBody.indexOf("postSurfaceRefresh(appContext);")
                > callbackBody.indexOf("currentPort = 0;"));
    }

    @Test
    public void notificationRejectsCallbacksFromSupersededDiscoveryRequests() throws IOException {
        String notification = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBNotification.java");
        String discoveryBody = methodBody(notification,
                "private static void startDiscoveryDirectLocked(Context appContext, NotificationManager manager,");
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
    public void supersededCallbacksCannotPublishAfterGenerationValidation() throws IOException {
        String notification = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBNotification.java");
        String discoveryBody = methodBody(notification,
                "private static void startDiscoveryDirectLocked(Context appContext, NotificationManager manager,");
        String endpointBody = methodBody(discoveryBody,
                discoveryBody.indexOf("public void onEndpoint(String host, int port) {"));
        String unavailableBody = methodBody(discoveryBody,
                discoveryBody.indexOf("public void onUnavailable() {"));

        assertPublicationIsGenerationLocked(endpointBody,
                "show(appContext, manager, host, port);",
                "KeepADBRegisterClient.updateEndpointAsync(appContext, host, port);");
        assertPublicationIsGenerationLocked(unavailableBody,
                "manager.cancel(NOTIFICATION_ID);",
                "KeepADBRegisterClient.markUnavailableAsync(appContext);");
    }

    @Test
    public void discoveryErrorsKeepTheTileDisconnected() throws IOException {
        String notification = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBNotification.java");
        String endpoint = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBEndpoint.java");
        String unavailableBody = methodBody(notification, "public void onUnavailable() {");
        String invalidateBody = methodBody(notification,
                "static synchronized void invalidateEndpoint(Context context) {");
        String stopBody = methodBody(notification,
                "private static synchronized void stop(Context context, NotificationManager manager) {");
        String timeoutBody = methodBody(endpoint, "private void giveUpIfStillUnresolved(long generation) {");
        String resolveFailureBody = methodBody(endpoint,
                "public void onResolveFailed(NsdServiceInfo ignored, int errorCode) {");
        String startFailureBody = methodBody(endpoint,
                "public void onStartDiscoveryFailed(String serviceType, int errorCode) {");

        assertTrue(unavailableBody.contains("currentHost = null;"));
        assertTrue(unavailableBody.contains("currentPort = 0;"));
        assertTrue(unavailableBody.contains("postSurfaceRefresh(appContext);"));
        assertTrue(invalidateBody.contains("postSurfaceRefresh(context.getApplicationContext());"));
        assertTrue(stopBody.contains("postSurfaceRefresh(context.getApplicationContext());"));
        assertTrue(timeoutBody.indexOf("stop();") >= 0);
        assertTrue(timeoutBody.indexOf("targetListener.onUnavailable();")
                > timeoutBody.indexOf("stop();"));
        assertTrue(resolveFailureBody.contains("processNextResolveLocked(generation);"));
        assertFalse(startFailureBody.contains("onEndpoint("));
    }

    @Test
    public void endpointDiscoverySessionRemainsIdempotentAndGuardsItsInternalCallbacks() throws IOException {
        String endpoint = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBEndpoint.java");

        assertTrue(endpoint.contains("if (discovering && !endpointDelivered.get())"));
        assertTrue(endpoint.contains("if (discovering) {\n            stop();\n        }"));
        assertTrue(endpoint.contains("if (!isCurrent(generation) || endpointDelivered.get()"));
        assertTrue(endpoint.contains("if (!isCurrent(generation) || currentResolveAttemptToken != attemptToken"));
    }

    @Test
    public void tappingTheTileWhileKeepAliveIsWaitingStillForcesAnImmediateReenable()
            throws IOException {
        // Carries issue #267 (2) forward across the #318 rework. #267 established that a tap must
        // not switch Wireless Debugging *off* while it only looks disconnected, and that the
        // "WLAN-ADB actually off, Keep-Alive merely waiting for its own timer" sub-case must force
        // an immediate re-enable rather than a no-op refreshForTile(). #318 kept that behavior but
        // stopped expressing it as a tile-only special case: the sub-case became its own state,
        // OFF_KEEP_ALIVE_WAITING, and KeepADB.desiredOnForClick() maps it to "enable" for the
        // tile, the widget and MainActivity's switch alike.
        String keepAdb = read("app/src/main/java/de/hohnepeople/keepadb/KeepADB.java");
        String tile = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBTileService.java");
        String clickBody = methodBody(keepAdb, "static boolean desiredOnForClick(State state) {");
        String tileClick = methodBody(tile, "public void onClick() {");

        assertTrue("OFF_KEEP_ALIVE_WAITING must resolve to an enable",
                clickBody.contains("state == State.OFF_KEEP_ALIVE_WAITING"));
        assertTrue("The tile must route its tap through the shared click semantics",
                tileClick.contains("KeepADB.desiredOnForClick(state)"));
    }

    @Test
    public void tappingTheTileWhileEnabledButDisconnectedStillReconnectsInsteadOfDisabling()
            throws IOException {
        // Issue #267 (2), upheld across #318 by explicit user decision (#318 comment 5605497911):
        // ENABLED_DISCONNECTED means WLAN-ADB is on but no endpoint is known, and a tap on the
        // tile in that situation must retry discovery, never switch WLAN-ADB off. This is the one
        // sanctioned deviation from the otherwise uniform click semantics; MainActivity's switch
        // and the widget deliberately keep disabling for the same state.
        String tile = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBTileService.java");
        String body = methodBody(tile, "public void onClick() {");

        int disconnectedBranch = body.indexOf("if (state == KeepADB.State.ENABLED_DISCONNECTED) {");
        int reconnectCall = body.indexOf("KeepADBNotification.refreshForTile(this, this);", disconnectedBranch);
        int earlyReturn = body.indexOf("return;", disconnectedBranch);
        int wantAssignment = body.indexOf("boolean want = KeepADB.desiredOnForClick(state);");

        assertTrue("onClick() must keep the documented ENABLED_DISCONNECTED exception",
                disconnectedBranch >= 0);
        assertTrue("The exception must retrigger discovery", reconnectCall > disconnectedBranch);
        assertTrue("It must return before reaching the shared toggle logic",
                earlyReturn > reconnectCall);
        assertTrue("The toggle (which would disable WLAN-ADB) must be unreachable from it",
                wantAssignment > earlyReturn);
        assertFalse("The exception must never disable wireless debugging",
                body.substring(disconnectedBranch, earlyReturn).contains("KeepADB.setEnabled(this, false"));
        assertFalse("It must not fall back to the shared definition for this state either",
                body.substring(disconnectedBranch, earlyReturn).contains("desiredOnForClick"));

        // The exception must be exactly one state wide: no other state may be intercepted before
        // the shared definition is consulted.
        assertEquals("only ENABLED_DISCONNECTED may be intercepted", 1,
                countOccurrences(body.substring(0, wantAssignment), "if (state == KeepADB.State."));

        // ... and it must stay a tile-only exception.
        String main = read("app/src/main/java/de/hohnepeople/keepadb/MainActivity.java");
        String widget = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBWidget.java");
        assertFalse("MainActivity must not adopt the tile exception",
                main.contains("if (state == KeepADB.State.ENABLED_DISCONNECTED)"));
        assertFalse("The widget must not adopt the tile exception",
                widget.contains("if (state == KeepADB.State.ENABLED_DISCONNECTED)"));
        assertFalse("Only the tile may retrigger discovery from a tap",
                main.contains("refreshForTile") || widget.contains("refreshForTile"));
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            count++;
        }
        return count;
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

    private static void assertPublicationIsGenerationLocked(String callbackBody,
            String firstPublication, String secondPublication) {
        int guard = callbackBody.indexOf(
                "if (requestGeneration != discoveryRequestGeneration) return;");
        int lock = callbackBody.indexOf("synchronized (KeepADBNotification.class) {");
        int lockOpeningBrace = callbackBody.indexOf('{', lock);
        int lockEnd = findMatchingBrace(callbackBody, lockOpeningBrace);
        int first = callbackBody.indexOf(firstPublication);
        int second = callbackBody.indexOf(secondPublication);
        int surfaceRefresh = callbackBody.indexOf("postSurfaceRefresh(appContext);");

        assertTrue(guard >= 0);
        assertTrue(guard > lock);
        assertTrue(lockEnd > lockOpeningBrace);
        assertTrue(guard < lockEnd);
        assertTrue(first > lock && first < lockEnd);
        assertTrue(second > lock && second < lockEnd);
        assertTrue(surfaceRefresh > lockEnd);
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
