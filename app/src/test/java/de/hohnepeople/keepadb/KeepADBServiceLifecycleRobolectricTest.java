package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowConnectivityManager;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowService;

/**
 * Robolectric unit tests for Issue #311:
 * Verifies that BootReceiver and KeepADBService honor persisted user intent and do not keep
 * the foreground service running if Wireless Debugging was explicitly switched OFF.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBServiceLifecycleRobolectricTest {

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
        KeepADBRegisterClient.setWlanStateForTesting(webhookUrl, "192.168.1.50:41234");

        KeepADBFakeHttpTransport transport = new KeepADBFakeHttpTransport();
        transport.setDeleteSuccess(false);
        KeepADBRegisterClient.setHttpTransport(transport);
        AtomicBoolean listenerNotified = new AtomicBoolean(false);
        KeepADBRegisterClient.setRegisterStateListener(() -> listenerNotified.set(true));

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

            long deadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < deadline) {
                ShadowLooper.idleMainLooper();
                if (KeepADBPreferences.WEBHOOK_STATUS_FAILED.equals(
                        KeepADBPreferences.getWebhookLastReportStatus(context))
                        && listenerNotified.get()) {
                    break;
                }
                Thread.sleep(20);
            }

            assertEquals(KeepADBPreferences.WEBHOOK_STATUS_FAILED,
                    KeepADBPreferences.getWebhookLastReportStatus(context));
            assertTrue("Network loss cleanup failure must reach the register listener",
                    listenerNotified.get());
            assertEquals("DELETE", transport.getLastRequest().method);
            assertEquals(webhookUrl, transport.getLastRequest().url);
            assertEquals("Failed cleanup must remain retryable via the last-known WLAN state",
                    webhookUrl, KeepADBRegisterClient.getLastRegisteredUrlForTesting());
            assertEquals("192.168.1.50:41234",
                    KeepADBRegisterClient.getLastRegisteredEndpointForTesting());
        } finally {
            controller.destroy();
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
}
