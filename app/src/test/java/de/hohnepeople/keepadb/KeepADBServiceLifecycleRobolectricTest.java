package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.provider.Settings;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowConnectivityManager;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowNetwork;
import org.robolectric.shadows.ShadowNotificationManager;
import org.robolectric.shadows.ShadowService;
import org.robolectric.shadows.ShadowWifiInfo;

/**
 * Robolectric unit tests for Issue #311:
 * Verifies that BootReceiver and KeepADBService honor persisted user intent and do not keep
 * the foreground service running if Wireless Debugging was explicitly switched OFF.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBServiceLifecycleRobolectricTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = RuntimeEnvironment.getApplication();

    @Before
    public void setUp() {
        shadowOf((Application) context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
        resetState();
    }

    @After
    public void tearDown() {
        resetState();
    }

    private void resetState() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
        context.getSharedPreferences("keepadb_diagnostics", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
        context.getSharedPreferences("keepadb_trusted_networks", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
        KeepADBTrustedNetwork.resetVerifiedTrustForTesting();
        KeepADBNotification.resetForTesting();
        KeepADBNetwork.resetForTesting();
        KeepADB.resetForTesting();
        KeepADBRegisterClient.resetForTesting();
    }

    @Test
    public void bootReceiverSkipsServiceStartWhenKeepAliveEnabledButLastExplicitIntentOff() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBPreferences.setLastDesiredOn(context, false);
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));

        assertFalse(KeepADBService.shouldRun(context));

        Intent intent = new Intent(Intent.ACTION_BOOT_COMPLETED);
        new BootReceiver().onReceive(context, intent);

        assertNull("Service must not be started when last explicit intent was OFF",
                shadowOf((Application) context).getNextStartedService());

        String export = KeepADBDiagnostics.export(context);
        assertTrue(export.contains("event=boot_recovery source=boot_receiver outcome=skipped detail=persisted_intent_off"));
    }

    @Test
    public void bootReceiverStartsServiceWhenKeepAliveEnabledAndLastExplicitIntentNotOff() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBPreferences.setLastDesiredOn(context, true);
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));

        assertTrue(KeepADBService.shouldRun(context));

        Intent intent = new Intent(Intent.ACTION_BOOT_COMPLETED);
        new BootReceiver().onReceive(context, intent);

        Intent started = shadowOf((Application) context).getNextStartedService();
        assertNotNull("Service must be started when keepAlive is enabled and intent not off", started);
        assertEquals(KeepADBService.class.getName(), started.getComponent().getClassName());

        String export = KeepADBDiagnostics.export(context);
        assertTrue(export.contains("event=boot_recovery source=boot_receiver outcome=success detail=service_start"));
    }

    @Test
    public void packageReplacedSkipsServiceStartWhenLastExplicitIntentOff() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBPreferences.setLastDesiredOn(context, false);
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));

        assertFalse(KeepADBService.shouldRun(context));

        Intent intent = new Intent(Intent.ACTION_MY_PACKAGE_REPLACED);
        new BootReceiver().onReceive(context, intent);

        assertNull(shadowOf((Application) context).getNextStartedService());

        String export = KeepADBDiagnostics.export(context);
        assertTrue(export.contains("event=package_recovery source=boot_receiver outcome=skipped detail=persisted_intent_off"));
    }

    @Test
    public void onStartCommandStopsImmediatelyWhenShouldRunIsFalse() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBPreferences.setLastDesiredOn(context, false);
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));

        assertFalse(KeepADBService.shouldRun(context));

        ConnectivityManager connectivityManager = context.getSystemService(ConnectivityManager.class);
        ShadowConnectivityManager shadowConnectivityManager = shadowOf(connectivityManager);

        ServiceController<KeepADBService> controller = Robolectric.buildService(KeepADBService.class);
        try {
            controller.create();
            int startId = 17;
            int result = controller.get().onStartCommand(new Intent(context, KeepADBService.class), 0, startId);

            assertEquals("Must return START_NOT_STICKY when service should not run",
                    Service.START_NOT_STICKY, result);
            ShadowLooper.idleMainLooper();

            assertTrue("NetworkCallback must not be registered when shouldRun is false",
                    shadowConnectivityManager.getNetworkCallbacks().isEmpty());

            ShadowService shadowService = shadowOf(controller.get());
            assertTrue("stopForeground must have been called", shadowService.isForegroundStopped());
            assertTrue("Foreground notification should be removed", shadowService.getNotificationShouldRemoved());
            assertEquals("stopSelfResult must have been called with startId",
                    startId, shadowService.getStopSelfResultId());

            String export = KeepADBDiagnostics.export(context);
            assertTrue(export.contains("event=service_start_command source=lifecycle outcome=stopped detail=should_not_run"));

            long heartbeatBefore = KeepADBPreferences.getServiceLastHeartbeat(context);
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
            assertEquals("Heartbeat ticker must not be running",
                    heartbeatBefore, KeepADBPreferences.getServiceLastHeartbeat(context));
        } finally {
            controller.destroy();
        }
    }

    @Test
    public void onStartCommandRunsAndRegistersListenersWhenShouldRunIsTrue() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBPreferences.setLastDesiredOn(context, true);
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(true));

        assertTrue(KeepADBService.shouldRun(context));

        ConnectivityManager connectivityManager = context.getSystemService(ConnectivityManager.class);
        ShadowConnectivityManager shadowConnectivityManager = shadowOf(connectivityManager);

        ServiceController<KeepADBService> controller = Robolectric.buildService(KeepADBService.class);
        try {
            controller.create();
            int startId = 1;
            int result = controller.get().onStartCommand(new Intent(context, KeepADBService.class), 0, startId);

            assertEquals("Must return START_STICKY when shouldRun is true",
                    Service.START_STICKY, result);
            ShadowLooper.idleMainLooper();

            assertFalse("NetworkCallback must be registered when shouldRun is true",
                    shadowConnectivityManager.getNetworkCallbacks().isEmpty());

            ShadowService shadowService = shadowOf(controller.get());
            assertFalse("Foreground must not be stopped when shouldRun is true",
                    shadowService.isForegroundStopped());

            String export = KeepADBDiagnostics.export(context);
            assertTrue(export.contains("event=service_start_command source=lifecycle outcome=ready detail=foreground=true"));
        } finally {
            controller.destroy();
        }
    }

    /**
     * #460: roaming onto a new, untrusted access point while Wireless Debugging is already ON
     * used to raise nothing -- both existing block sites in this class only ever ask while it is
     * currently off and Keep-Alive is deciding whether to turn it back on. The network callback
     * must now surface the same throttled prompt regardless of that decision.
     */
    @Test
    public void networkCallbackPromptsForAnUntrustedAccessPointEvenWhileAlreadyActive() {
        // #492: the restriction is an opt-in now, and "untrusted access point" only exists while
        // it is on -- so this scenario states it instead of relying on the former default.
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBPreferences.setLastDesiredOn(context, true);
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(true));
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        connectTo("Cafe-WLAN", "aa:bb:cc:dd:ee:01");

        ConnectivityManager connectivityManager = context.getSystemService(ConnectivityManager.class);
        ShadowConnectivityManager shadowConnectivityManager = shadowOf(connectivityManager);
        ServiceController<KeepADBService> controller = Robolectric.buildService(KeepADBService.class);
        try {
            controller.create();
            controller.get().onStartCommand(new Intent(context, KeepADBService.class), 0, 1);
            ShadowLooper.idleMainLooper();

            assertTrue("Wireless Debugging must already be on for this scenario",
                    KeepADB.isEnabled(context));

            Network newNetwork = ShadowNetwork.newInstance(2001);
            for (ConnectivityManager.NetworkCallback callback
                    : shadowConnectivityManager.getNetworkCallbacks()) {
                callback.onAvailable(newNetwork);
            }
            ShadowLooper.idleMainLooper();

            NotificationManager manager = context.getSystemService(NotificationManager.class);
            Notification notification =
                    shadowOf(manager).getNotification(KeepADBNetworkTrustPrompt.NOTIFICATION_ID);
            assertNotNull("An untrusted access point must prompt even while already active",
                    notification);
        } finally {
            controller.destroy();
        }
    }

    /**
     * The throttle in {@link KeepADBNetworkTrustPrompt} must apply here exactly as it does for
     * the existing block sites: repeated network events for the same untrusted access point must
     * not re-alert on every roam callback.
     */
    @Test
    public void networkCallbackDoesNotReprompForTheSameAccessPointOnRepeatedEvents() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBPreferences.setLastDesiredOn(context, true);
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(true));
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        connectTo("Cafe-WLAN", "aa:bb:cc:dd:ee:01");

        ConnectivityManager connectivityManager = context.getSystemService(ConnectivityManager.class);
        ShadowConnectivityManager shadowConnectivityManager = shadowOf(connectivityManager);
        ServiceController<KeepADBService> controller = Robolectric.buildService(KeepADBService.class);
        try {
            controller.create();
            controller.get().onStartCommand(new Intent(context, KeepADBService.class), 0, 1);
            ShadowLooper.idleMainLooper();

            Network newNetwork = ShadowNetwork.newInstance(2001);
            for (ConnectivityManager.NetworkCallback callback
                    : shadowConnectivityManager.getNetworkCallbacks()) {
                callback.onAvailable(newNetwork);
            }
            ShadowLooper.idleMainLooper();
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            manager.cancel(KeepADBNetworkTrustPrompt.NOTIFICATION_ID);

            for (ConnectivityManager.NetworkCallback callback
                    : shadowConnectivityManager.getNetworkCallbacks()) {
                callback.onAvailable(newNetwork);
            }
            ShadowLooper.idleMainLooper();

            assertNull("The same access point must not re-prompt within the throttle interval",
                    shadowOf(manager).getNotification(KeepADBNetworkTrustPrompt.NOTIFICATION_ID));
        } finally {
            controller.destroy();
        }
    }

    private void connectTo(String ssid, String bssid) {
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = ShadowWifiInfo.newInstance();
        shadowOf(info).setSSID(ssid);
        shadowOf(info).setBSSID(bssid);
        shadowOf(wifiManager).setConnectionInfo(info);
    }

    @Test
    public void networkCallbackOnLostInvalidatesWlanRegistrationAndSurfacesCleanupFailure()
            throws InterruptedException {
        final String webhookUrl = "http://register.example/register";
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBPreferences.setLastDesiredOn(context, true);
        KeepADBPreferences.setRegisterWebhookUrl(context, webhookUrl);
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(true));
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> false);
        KeepADBRegisterClient.setWlanStateForTesting(webhookUrl, "test-wlan-endpoint");

        KeepADBFakeHttpTransport transport = new KeepADBFakeHttpTransport();
        transport.setDeleteSuccess(false);
        KeepADBRegisterClient.setHttpTransport(transport);
        CountDownLatch listenerNotified = new CountDownLatch(1);
        KeepADBRegisterClient.setRegisterStateListener(listenerNotified::countDown);

        ConnectivityManager connectivityManager = context.getSystemService(ConnectivityManager.class);
        ShadowConnectivityManager shadowConnectivityManager = shadowOf(connectivityManager);
        ServiceController<KeepADBService> controller = Robolectric.buildService(KeepADBService.class);
        try {
            controller.create();
            assertEquals(Service.START_STICKY,
                    controller.get().onStartCommand(new Intent(context, KeepADBService.class), 0, 1));
            ShadowLooper.idleMainLooper();

            assertFalse(shadowConnectivityManager.getNetworkCallbacks().isEmpty());
            for (ConnectivityManager.NetworkCallback callback
                    : shadowConnectivityManager.getNetworkCallbacks()) {
                callback.onLost(connectivityManager.getActiveNetwork());
            }

            awaitMainLooperSignal(listenerNotified, 3000);

            assertEquals(KeepADBPreferences.WEBHOOK_STATUS_FAILED,
                    KeepADBPreferences.getWebhookLastReportStatus(context));
            assertTrue("Network loss cleanup failure must reach the register listener",
                    listenerNotified.getCount() == 0);
            assertEquals("DELETE", transport.getLastRequest().method);
            assertEquals(webhookUrl, transport.getLastRequest().url);
            assertEquals("Failed cleanup must remain retryable via the last-known WLAN state",
                    webhookUrl, KeepADBRegisterClient.getLastRegisteredUrlForTesting());
            assertEquals("test-wlan-endpoint",
                    KeepADBRegisterClient.getLastRegisteredEndpointForTesting());
        } finally {
            controller.destroy();
        }
    }

    @Test
    public void lateLossOfOldWifiNetworkDoesNotInvalidateTheStillAvailableNetwork()
            throws InterruptedException {
        final String webhookUrl = "http://register.example/register";
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBPreferences.setLastDesiredOn(context, true);
        KeepADBPreferences.setRegisterWebhookUrl(context, webhookUrl);
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(true));
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        KeepADBRegisterClient.setWlanStateForTesting(webhookUrl, "test-wlan-endpoint");

        KeepADBFakeHttpTransport transport = new KeepADBFakeHttpTransport();
        transport.setDeleteSuccess(false);
        CountDownLatch unexpectedCleanup = new CountDownLatch(1);
        transport.setFailureCallback(unexpectedCleanup::countDown);
        KeepADBRegisterClient.setHttpTransport(transport);

        ConnectivityManager connectivityManager = context.getSystemService(ConnectivityManager.class);
        ShadowConnectivityManager shadowConnectivityManager = shadowOf(connectivityManager);
        ServiceController<KeepADBService> controller = Robolectric.buildService(KeepADBService.class);
        try {
            controller.create();
            assertEquals(Service.START_STICKY,
                    controller.get().onStartCommand(new Intent(context, KeepADBService.class), 0, 1));
            ShadowLooper.idleMainLooper();

            Network oldNetwork = ShadowNetwork.newInstance(1001);
            Network newNetwork = ShadowNetwork.newInstance(1002);
            for (ConnectivityManager.NetworkCallback callback
                    : shadowConnectivityManager.getNetworkCallbacks()) {
                callback.onAvailable(newNetwork);
            }
            for (ConnectivityManager.NetworkCallback callback
                    : shadowConnectivityManager.getNetworkCallbacks()) {
                callback.onLost(oldNetwork);
            }
            ShadowLooper.idleMainLooper();

            assertFalse("A late old-network loss must not start a failed cleanup",
                    unexpectedCleanup.await(500, TimeUnit.MILLISECONDS));
            assertEquals("A late loss of the old network must not queue WLAN cleanup", 0,
                    transport.getRequestCount());
            assertEquals(webhookUrl, KeepADBRegisterClient.getLastRegisteredUrlForTesting());
        } finally {
            controller.destroy();
        }
    }

    private static void awaitMainLooperSignal(CountDownLatch signal, long timeoutMs)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (true) {
            ShadowLooper.idleMainLooper();
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new AssertionError("Signal timed out after " + timeoutMs + " ms");
            }
            if (signal.await(Math.min(remainingNanos, TimeUnit.MILLISECONDS.toNanos(20)),
                    TimeUnit.NANOSECONDS)) {
                return;
            }
        }
    }

    @Test
    public void packageReplacedStartsServiceWhenKeepAliveEnabledAndLastExplicitIntentNotOff() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBPreferences.setLastDesiredOn(context, true);
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));

        assertTrue(KeepADBService.shouldRun(context));

        Intent intent = new Intent(Intent.ACTION_MY_PACKAGE_REPLACED);
        new BootReceiver().onReceive(context, intent);

        Intent started = shadowOf((Application) context).getNextStartedService();
        assertNotNull("Service must be started on package replaced when intent not off", started);
        assertEquals(KeepADBService.class.getName(), started.getComponent().getClassName());

        String export = KeepADBDiagnostics.export(context);
        assertTrue(export.contains("event=package_recovery source=boot_receiver outcome=success detail=service_start"));
    }

    @Test
    public void bootReceiverDoesNothingWhenKeepAliveDisabled() {
        KeepADBPreferences.setKeepAliveEnabled(context, false);
        KeepADBPreferences.setLastDesiredOn(context, true);
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));

        assertFalse(KeepADBService.shouldRun(context));

        Intent intent = new Intent(Intent.ACTION_BOOT_COMPLETED);
        new BootReceiver().onReceive(context, intent);

        assertNull(shadowOf((Application) context).getNextStartedService());

        String export = KeepADBDiagnostics.export(context);
        assertTrue(export.contains("event=boot_completed source=system outcome=received detail=keepAlive=false"));
        assertFalse(export.contains("source=boot_receiver"));
    }

    @Test
    public void keepAliveArmWithoutWifiStartsServiceForLaterReconnect() {
        // Step 1: User switches ADB manually off (L = OFF, lastDesiredOn = false, wasLastExplicitIntentOff = true)
        KeepADB.recordExplicitIntent(context, false);
        assertTrue("wasLastExplicitIntentOff must be true after manual OFF",
                KeepADB.wasLastExplicitIntentOff(context));
        assertFalse("shouldRun must be false when manual intent is OFF",
                KeepADBService.shouldRun(context));

        // Step 2: Wi-Fi disconnects and ADB is disabled
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> false);
        assertFalse("ADB must be disabled", KeepADB.isEnabled(context));
        assertFalse("Wi-Fi must be disconnected", KeepADBService.isWifiConnected(context));

        // Step 3: User enables Keep-Alive in Settings / MainActivity
        KeepADBPreferences.setKeepAliveEnabled(context, true);

        // Step 4: Verify wasLastExplicitIntentOff is now false and shouldRun is true
        assertFalse("wasLastExplicitIntentOff must be false after Keep-Alive is enabled",
                KeepADB.wasLastExplicitIntentOff(context));
        assertTrue("shouldRun must be true even without active Wi-Fi",
                KeepADBService.shouldRun(context));

        // Step 5: Verify that KeepADBService.sync(context) requests service start in standby
        KeepADBService.sync(context);

        Intent started = shadowOf((Application) context).getNextStartedService();
        assertNotNull("Service must be started to wait for reconnect", started);
        assertEquals(KeepADBService.class.getName(), started.getComponent().getClassName());
    }

    @Test
    public void keepAliveArmWithoutWifiAfterManualSetEnabledOffStartsService() {
        shadowOf((Application) context).grantPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS);
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(true);
        KeepADB.setGatewayForTesting(gateway);

        // Step 1: User switches ADB manually off via setEnabled("app")
        assertTrue(KeepADB.setEnabled(context, false, "app"));
        assertFalse(KeepADB.isEnabled(context));
        assertTrue("wasLastExplicitIntentOff must be true after manual setEnabled(false)",
                KeepADB.wasLastExplicitIntentOff(context));
        assertFalse(KeepADBService.shouldRun(context));

        // Step 2: Wi-Fi disconnects
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> false);
        assertFalse(KeepADBService.isWifiConnected(context));

        // Step 3: User enables Keep-Alive
        KeepADBPreferences.setKeepAliveEnabled(context, true);

        // Step 4: Intent is reset, service should run
        assertFalse("wasLastExplicitIntentOff must be false after Keep-Alive is enabled",
                KeepADB.wasLastExplicitIntentOff(context));
        assertTrue("shouldRun must be true even without active Wi-Fi",
                KeepADBService.shouldRun(context));

        // Step 5: Service sync starts service
        KeepADBService.sync(context);
        Intent started = shadowOf((Application) context).getNextStartedService();
        assertNotNull("Service must be started in standby", started);
        assertEquals(KeepADBService.class.getName(), started.getComponent().getClassName());
    }

    @Test
    public void fullLifecycleManualOffDisconnectKeepAliveOnReconnectAutoEnablesAdb() {
        shadowOf((Application) context).grantPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS);
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(true);
        KeepADB.setGatewayForTesting(gateway);

        // 1. Manual OFF
        assertTrue(KeepADB.setEnabled(context, false, "app"));
        assertFalse(KeepADB.isEnabled(context));
        assertTrue(KeepADB.wasLastExplicitIntentOff(context));
        assertFalse(KeepADBService.shouldRun(context));

        // 2. Wi-Fi disconnect
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> false);
        assertFalse(KeepADBService.isWifiConnected(context));

        // 3. Keep-Alive ON
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        assertFalse(KeepADB.wasLastExplicitIntentOff(context));
        assertTrue(KeepADBService.shouldRun(context));

        // 4. Start service in standby
        ConnectivityManager connectivityManager = context.getSystemService(ConnectivityManager.class);
        ShadowConnectivityManager shadowConnectivityManager = shadowOf(connectivityManager);
        ServiceController<KeepADBService> controller = Robolectric.buildService(KeepADBService.class);
        try {
            controller.create();
            int result = controller.get().onStartCommand(new Intent(context, KeepADBService.class), 0, 1);
            assertEquals(Service.START_STICKY, result);
            ShadowLooper.idleMainLooper();
            assertFalse("ADB must still be disabled while disconnected", gateway.isEnabled(context));
            assertFalse("Network callback must be registered", shadowConnectivityManager.getNetworkCallbacks().isEmpty());

            // 5. Wi-Fi connect: advance time to clear recheck cooldown, reconnect Wi-Fi, trigger network callback
            android.os.SystemClock.sleep(350);
            KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
            KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
            for (ConnectivityManager.NetworkCallback cb : shadowConnectivityManager.getNetworkCallbacks()) {
                cb.onAvailable(connectivityManager.getActiveNetwork());
            }
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1200));

            assertTrue("Gateway must have received enable write", gateway.writes.contains(true));
            assertTrue("Wireless Debugging must be auto-enabled upon Wi-Fi reconnect", gateway.isEnabled(context));
            assertTrue("KeepADB.isEnabled must return true", KeepADB.isEnabled(context));
        } finally {
            controller.destroy();
        }
    }

    @Test
    public void shouldRunReturnsFalseForNullContext() {
        assertFalse(KeepADBService.shouldRun(null));
    }

    @Test
    public void contentObserverOnUntrustedNetworkTriggersNetworkTrustPrompt() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBPreferences.setLastDesiredOn(context, true);
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> false);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);

        String bssid = "11:22:33:44:55:66";
        setWifiConnection("Untrusted-Cafe", bssid);

        ServiceController<KeepADBService> controller = Robolectric.buildService(KeepADBService.class);
        try {
            controller.create();
            controller.get().onStartCommand(new Intent(context, KeepADBService.class), 0, 1);
            ShadowLooper.idleMainLooper();
            assertNull("No prompt before wifi connection", postedPrompt());

            // Connect Wi-Fi
            KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);

            // Trigger content observer
            controller.get().getAdbContentObserverForTesting()
                    .onChange(false, Settings.Global.getUriFor(KeepADB.KEY));
            ShadowLooper.idleMainLooper();

            Notification prompt = postedPrompt();
            assertNotNull("Content observer on untrusted network must raise trust prompt", prompt);
            String text = prompt.extras.getString(Notification.EXTRA_TEXT);
            assertNotNull(text);
            assertTrue("Prompt text must contain SSID", text.contains("Untrusted-Cafe"));
            assertTrue("Prompt text must contain BSSID", text.contains(bssid));
        } finally {
            controller.destroy();
        }
    }

    @Test
    public void recheckAndEnableOnUntrustedNetworkTriggersNetworkTrustPrompt() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBPreferences.setLastDesiredOn(context, true);
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> false);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);

        String bssid = "11:22:33:44:55:77";
        setWifiConnection("Untrusted-Hotel", bssid);

        ServiceController<KeepADBService> controller = Robolectric.buildService(KeepADBService.class);
        try {
            controller.create();
            controller.get().onStartCommand(new Intent(context, KeepADBService.class), 0, 1);
            ShadowLooper.idleMainLooper();
            assertNull("No prompt before wifi connection", postedPrompt());

            // Connect Wi-Fi and advance clock past cooldown
            KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400));

            controller.get().recheckAndEnable();
            ShadowLooper.idleMainLooper();

            Notification prompt = postedPrompt();
            assertNotNull("recheckAndEnable on untrusted network must raise trust prompt", prompt);
            String text = prompt.extras.getString(Notification.EXTRA_TEXT);
            assertNotNull(text);
            assertTrue("Prompt text must contain SSID", text.contains("Untrusted-Hotel"));
            assertTrue("Prompt text must contain BSSID", text.contains(bssid));
        } finally {
            controller.destroy();
        }
    }

    private void setWifiConnection(String ssid, String bssid) {
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = ShadowWifiInfo.newInstance();
        shadowOf(info).setSSID(ssid);
        shadowOf(info).setBSSID(bssid);
        shadowOf(wifiManager).setConnectionInfo(info);
    }

    private Notification postedPrompt() {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        ShadowNotificationManager shadow = shadowOf(manager);
        return shadow.getNotification(KeepADBNetworkTrustPrompt.NOTIFICATION_ID);
    }

    private void postedPromptClear() {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(KeepADBNetworkTrustPrompt.NOTIFICATION_ID);
    }

    // -----------------------------------------------------------------------------------------
    // #496: the #496 recovery backoff's call-site gating, as wired into KeepADBService's two
    // automatic re-enable paths (recheckAndEnable's 60s heartbeat and the ContentObserver), plus
    // its reset triggers (app/service restart, an externally observed successful readback, a
    // Wi-Fi network change). The pure state machine is covered without any of this Android
    // wiring in KeepADBRecoveryBackoffTest; the KeepADB.setEnabled()/applyNow() bookkeeping that
    // feeds it is covered in KeepADBRecoveryBackoffSchedulingTest.
    // -----------------------------------------------------------------------------------------

    /**
     * Acceptance criterion: "Ein automatischer Enable-Versuch mit akzeptiertem Write, aber
     * wiederholt adb_wifi_enabled == 0 im Readback, hat einen expliziten begrenzten
     * Retry-/Backoff-/Abbruchpfad; keine unbegrenzten Versuche im 1,5s-Abstand." The 60s
     * heartbeat is the call site this pins.
     */
    @Test
    public void recheckAndEnableStopsRetryingAfterAnAcceptedWriteWithAStaleReadback() {
        shadowOf((Application) context).grantPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS);
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBPreferences.setLastDesiredOn(context, true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        KeepADBStuckOffSettingsGateway gateway = new KeepADBStuckOffSettingsGateway();
        KeepADB.setGatewayForTesting(gateway);

        ServiceController<KeepADBService> controller = Robolectric.buildService(KeepADBService.class);
        try {
            controller.create();
            controller.get().onStartCommand(new Intent(context, KeepADBService.class), 0, 1);
            ShadowLooper.idleMainLooper();
            // onStartCommand's own initial recheckAndEnable() lands inside its own 300ms
            // internal throttle in this test environment (Robolectric's elapsed clock starts
            // near zero) and is skipped -- trigger the first real recheck explicitly, exactly
            // like the heartbeat's first real 60s tick would, then let the debounced write (#310)
            // actually land before asserting on it.
            android.os.SystemClock.sleep(1600);
            controller.get().recheckAndEnable();
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1600));

            assertEquals("the initial recheck must make exactly one automatic attempt",
                    1, gateway.writes.size());
            assertTrue("the mismatched write must engage the #496 backoff",
                    KeepADB.isAutomaticEnableBackoffBlocked());

            // Advance past recheckAndEnable's own 300ms internal throttle and try again -- as
            // the 60s heartbeat would on its next tick.
            android.os.SystemClock.sleep(350);
            controller.get().recheckAndEnable();
            ShadowLooper.idleMainLooper();

            assertEquals("a blocked backoff must prevent a second write, not merely delay it",
                    1, gateway.writes.size());
            String export = KeepADBDiagnostics.export(context);
            assertTrue("the block must be diagnosable, not silent",
                    export.contains("reason=recovery_backoff_active"));
        } finally {
            controller.destroy();
        }
    }

    /** Same acceptance criterion as above, pinned against the ContentObserver call site. */
    @Test
    public void contentObserverStopsRetryingAfterAnAcceptedWriteWithAStaleReadback() {
        shadowOf((Application) context).grantPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS);
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBPreferences.setLastDesiredOn(context, true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        KeepADBStuckOffSettingsGateway gateway = new KeepADBStuckOffSettingsGateway();
        KeepADB.setGatewayForTesting(gateway);

        ServiceController<KeepADBService> controller = Robolectric.buildService(KeepADBService.class);
        try {
            controller.create();
            controller.get().onStartCommand(new Intent(context, KeepADBService.class), 0, 1);
            ShadowLooper.idleMainLooper();

            // First externally-observed drop triggers the automatic re-enable attempt. (Not
            // relying on onStartCommand's own initial recheckAndEnable() here -- it lands inside
            // its own 300ms internal throttle in this test environment and is a no-op.)
            controller.get().getAdbContentObserverForTesting()
                    .onChange(false, Settings.Global.getUriFor(KeepADB.KEY));
            android.os.SystemClock.sleep(1600);
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1600));

            assertEquals(1, gateway.writes.size());
            assertTrue(KeepADB.isAutomaticEnableBackoffBlocked());

            // The exact real-world trigger from #496: the OS reverts the accepted write, which
            // re-fires this very ContentObserver.
            controller.get().getAdbContentObserverForTesting()
                    .onChange(false, Settings.Global.getUriFor(KeepADB.KEY));
            ShadowLooper.idleMainLooper();

            assertEquals("the ContentObserver must not retry into a blocked backoff either",
                    1, gateway.writes.size());
        } finally {
            controller.destroy();
        }
    }

    /**
     * Acceptance criterion: "Nach dem Abbruch bleibt Keep-Alive ... Ein klar definierter neuer
     * Auslöser gibt den Zustand wieder frei" -- an app/service restart is one of the named
     * triggers. Deliberately blocks the backoff *outside* the service under test (a direct
     * KeepADB.setEnabled() call, like a keep-alive attempt that already happened in a previous
     * process) so this pins {@code onCreate()}'s own reset rather than accidentally passing via
     * {@code resetForTesting()} in {@link #setUp()}.
     */
    @Test
    public void aFreshServiceInstanceReopensABlockedBackoff() {
        shadowOf((Application) context).grantPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS);
        KeepADB.setGatewayForTesting(new KeepADBStuckOffSettingsGateway());
        assertTrue(KeepADB.setEnabled(context, true, "keep_alive_check"));
        // The automatic enable is debounced (#310); let the scheduled write actually land.
        android.os.SystemClock.sleep(1600);
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1600));
        assertTrue("precondition: the backoff must be blocked before the restart",
                KeepADB.isAutomaticEnableBackoffBlocked());

        ServiceController<KeepADBService> controller = Robolectric.buildService(KeepADBService.class);
        try {
            controller.create();

            assertFalse("a fresh service instance (KeepADBService#onCreate) must reopen a "
                            + "blocked automatic-enable backoff",
                    KeepADB.isAutomaticEnableBackoffBlocked());
        } finally {
            controller.destroy();
        }
    }

    /**
     * Acceptance criterion: "erfolgreicher Gegenpfad (Freigabe erteilt -&gt; normale
     * Wiederherstellung)". Exercises two of the named reset triggers together -- an externally
     * observed readback and a Wi-Fi network change -- and proves recovery actually completes
     * afterward rather than merely reporting "not blocked".
     */
    @Test
    public void grantingThePermissionReopensTheBackoffAndRecoveryResumesNormally() {
        shadowOf((Application) context).grantPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS);
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBPreferences.setLastDesiredOn(context, true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        KeepADBStuckOffSettingsGateway stuckGateway = new KeepADBStuckOffSettingsGateway();
        KeepADB.setGatewayForTesting(stuckGateway);

        ServiceController<KeepADBService> controller = Robolectric.buildService(KeepADBService.class);
        try {
            controller.create();
            controller.get().onStartCommand(new Intent(context, KeepADBService.class), 0, 1);
            ShadowLooper.idleMainLooper();
            // onStartCommand's own initial recheckAndEnable() lands inside its own 300ms
            // internal throttle in this test environment and is a no-op; trigger the first real
            // recheck explicitly instead.
            android.os.SystemClock.sleep(1600);
            controller.get().recheckAndEnable();
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1600));

            assertEquals(1, stuckGateway.writes.size());
            assertTrue("precondition: the backoff must be blocked before recovery",
                    KeepADB.isAutomaticEnableBackoffBlocked());

            // The user confirmed Android's pairing dialog and reconnected: a network change is
            // one of the named triggers, and the next write actually succeeds now.
            KeepADB.noteNetworkChanged();
            assertFalse("a network change must reopen the backoff immediately",
                    KeepADB.isAutomaticEnableBackoffBlocked());

            KeepADBFakeSettingsGateway workingGateway = new KeepADBFakeSettingsGateway(false);
            KeepADB.setGatewayForTesting(workingGateway);
            android.os.SystemClock.sleep(1600);
            controller.get().recheckAndEnable();
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1600));

            assertTrue("automatic recovery must actually complete once the block is lifted",
                    workingGateway.writes.contains(true));
            assertTrue(KeepADB.isEnabled(context));
            assertFalse("a successful readback must not leave the backoff blocked",
                    KeepADB.isAutomaticEnableBackoffBlocked());
        } finally {
            controller.destroy();
        }
    }
}
