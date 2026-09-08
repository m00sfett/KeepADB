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
        KeepADB.resetForTesting();
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
    public void shouldRunReturnsFalseForNullContext() {
        assertFalse(KeepADBService.shouldRun(null));
    }
}
