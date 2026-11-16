package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowNotificationManager;
import org.robolectric.shadows.ShadowService;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBSelfPairServiceTest {

    private Context context;
    private NotificationManager notificationManager;
    private ShadowNotificationManager shadowNotificationManager;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        notificationManager = context.getSystemService(NotificationManager.class);
        shadowNotificationManager = shadowOf(notificationManager);
        prefsSelfPair().edit().clear().commit();
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit();
        KeepADB.resetForTesting();
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));
        shadowOf((Application) context).denyPermissions(Manifest.permission.WRITE_SECURE_SETTINGS);
        KeepADBSelfPairService.publishForTesting(new KeepADBSelfPairService.Status(
                KeepADBSelfPairService.Phase.IDLE, null,
                KeepADBSelfPairService.Cleanup.KEPT_ON_FOR_KEEP_ALIVE));
    }

    @After
    public void tearDown() {
        KeepADBSelfPairService.publishForTesting(new KeepADBSelfPairService.Status(
                KeepADBSelfPairService.Phase.IDLE, null,
                KeepADBSelfPairService.Cleanup.KEPT_ON_FOR_KEEP_ALIVE));
        prefsSelfPair().edit().clear().commit();
        KeepADB.resetForTesting();
    }

    private SharedPreferences prefsSelfPair() {
        return context.getSharedPreferences("keepadb_selfpair", Context.MODE_PRIVATE);
    }

    // ---- B3: conclude() --------------------------------------------------------------------------

    @Test
    public void concludeReturnsNullWhenPermissionNotGranted() {
        shadowOf((Application) context).denyPermissions(Manifest.permission.WRITE_SECURE_SETTINGS);
        prefsSelfPair().edit().putLong("cleanup_pending_at", 12345L).commit();

        KeepADBSelfPairService.Status result = KeepADBSelfPairService.conclude(context, "fingerprint-123");
        assertNull("conclude must return null if permission not granted", result);
        assertFalse("cleanup marker must be cleared by conclude",
                prefsSelfPair().contains("cleanup_pending_at"));
    }

    @Test
    public void concludeWithKeepAliveOnReturnsKeptOn() {
        shadowOf((Application) context).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS);
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        prefsSelfPair().edit().putLong("cleanup_pending_at", 12345L).commit();

        KeepADBSelfPairService.Status result = KeepADBSelfPairService.conclude(context, "fingerprint-123");
        assertNotNull(result);
        assertEquals(KeepADBSelfPairService.Phase.DONE, result.phase);
        assertEquals(KeepADBSelfPairService.Cleanup.KEPT_ON_FOR_KEEP_ALIVE, result.cleanup);
        assertEquals("fingerprint-123", result.fingerprint);
        assertFalse("cleanup marker must be cleared", prefsSelfPair().contains("cleanup_pending_at"));
    }

    @Test
    public void concludeWithKeepAliveOffAndWriteSuccessReturnsSwitchedOff() {
        shadowOf((Application) context).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS);
        KeepADBPreferences.setKeepAliveEnabled(context, false);
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(true);
        KeepADB.setGatewayForTesting(gateway);

        KeepADBSelfPairService.Status result = KeepADBSelfPairService.conclude(context, "fingerprint-123");
        assertNotNull(result);
        assertEquals(KeepADBSelfPairService.Phase.DONE, result.phase);
        assertEquals(KeepADBSelfPairService.Cleanup.SWITCHED_OFF, result.cleanup);
        assertEquals("fingerprint-123", result.fingerprint);
        assertFalse("Wifi-ADB must be switched off", gateway.isEnabled(context));
        assertFalse("cleanup marker must be cleared", prefsSelfPair().contains("cleanup_pending_at"));
    }

    @Test
    public void concludeWithKeepAliveOffAndWriteRefusedReturnsStillOn() {
        shadowOf((Application) context).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS);
        KeepADBPreferences.setKeepAliveEnabled(context, false);
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(true);
        gateway.setWriteSuccess(false);
        KeepADB.setGatewayForTesting(gateway);

        KeepADBSelfPairService.Status result = KeepADBSelfPairService.conclude(context, "fingerprint-123");
        assertNotNull(result);
        assertEquals(KeepADBSelfPairService.Phase.DONE, result.phase);
        assertEquals(KeepADBSelfPairService.Cleanup.STILL_ON, result.cleanup);
        assertEquals("fingerprint-123", result.fingerprint);
        assertTrue("Wifi-ADB is still on", gateway.isEnabled(context));
    }

    // ---- B4: reconcile() -------------------------------------------------------------------------

    @Test
    public void reconcileWithFreshMarkerSwitchesOffAndClearsMarker() {
        shadowOf((Application) context).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS);
        KeepADBPreferences.setKeepAliveEnabled(context, false);
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(true);
        KeepADB.setGatewayForTesting(gateway);

        prefsSelfPair().edit().putLong("cleanup_pending_at", System.currentTimeMillis() - 5_000L).commit();
        KeepADBSelfPairService.reconcile(context);

        assertFalse("marker must be cleared", prefsSelfPair().contains("cleanup_pending_at"));
        assertFalse("Wifi-ADB must be switched off by reconcile", gateway.isEnabled(context));
    }

    @Test
    public void reconcileWithStaleMarkerClearsMarkerWithoutSwitchingOff() {
        shadowOf((Application) context).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS);
        KeepADBPreferences.setKeepAliveEnabled(context, false);
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(true);
        KeepADB.setGatewayForTesting(gateway);

        // Older than 1 hour (70 minutes)
        prefsSelfPair().edit().putLong("cleanup_pending_at",
                System.currentTimeMillis() - 70 * 60 * 1000L).commit();
        KeepADBSelfPairService.reconcile(context);

        assertFalse("stale marker must be removed", prefsSelfPair().contains("cleanup_pending_at"));
        assertTrue("Wifi-ADB must remain on if marker is stale", gateway.isEnabled(context));
    }

    @Test
    public void reconcileWithKeepAliveOnClearsMarkerWithoutSwitchingOff() {
        shadowOf((Application) context).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS);
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(true);
        KeepADB.setGatewayForTesting(gateway);

        prefsSelfPair().edit().putLong("cleanup_pending_at", System.currentTimeMillis() - 5_000L).commit();
        KeepADBSelfPairService.reconcile(context);

        assertFalse("marker must be cleared", prefsSelfPair().contains("cleanup_pending_at"));
        assertTrue("Wifi-ADB must remain on if Keep-Alive is enabled", gateway.isEnabled(context));
    }

    @Test
    public void reconcileWithPermissionMissingClearsMarkerWithoutSwitchingOff() {
        shadowOf((Application) context).denyPermissions(Manifest.permission.WRITE_SECURE_SETTINGS);
        KeepADBPreferences.setKeepAliveEnabled(context, false);
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(true);
        KeepADB.setGatewayForTesting(gateway);

        prefsSelfPair().edit().putLong("cleanup_pending_at", System.currentTimeMillis() - 5_000L).commit();
        KeepADBSelfPairService.reconcile(context);

        assertFalse("marker must be cleared", prefsSelfPair().contains("cleanup_pending_at"));
        assertTrue("Wifi-ADB must remain untouched when permission is missing", gateway.isEnabled(context));
    }

    @Test
    public void reconcileWhileActiveDoesNothing() {
        KeepADBSelfPairService.publishForTesting(new KeepADBSelfPairService.Status(
                KeepADBSelfPairService.Phase.WORKING, null,
                KeepADBSelfPairService.Cleanup.KEPT_ON_FOR_KEEP_ALIVE));
        prefsSelfPair().edit().putLong("cleanup_pending_at", System.currentTimeMillis() - 5_000L).commit();

        KeepADBSelfPairService.reconcile(context);
        assertTrue("active run must not clear marker", prefsSelfPair().contains("cleanup_pending_at"));
    }

    // ---- B5: notificationsUsable() ---------------------------------------------------------------

    @Test
    public void notificationsUsableChecksBothGlobalAndChannelState() {
        shadowNotificationManager.setNotificationsEnabled(true);
        notificationManager.createNotificationChannel(new NotificationChannel("keepadb_selfpair",
                "SelfPair", NotificationManager.IMPORTANCE_HIGH));
        assertTrue(KeepADBSelfPairService.notificationsUsable(context));

        // Blocked channel
        notificationManager.createNotificationChannel(new NotificationChannel("keepadb_selfpair",
                "SelfPair", NotificationManager.IMPORTANCE_NONE));
        assertFalse("blocked channel must be detected",
                KeepADBSelfPairService.notificationsUsable(context));

        // Reset channel to high, but disable notifications globally
        notificationManager.createNotificationChannel(new NotificationChannel("keepadb_selfpair",
                "SelfPair", NotificationManager.IMPORTANCE_HIGH));
        shadowNotificationManager.setNotificationsEnabled(false);
        assertFalse("disabled notifications must be detected",
                KeepADBSelfPairService.notificationsUsable(context));
    }

    // ---- B7: second START preserves RemoteInput reply action & stale tokens ignored --------------

    @Test
    public void secondStartInDialogFoundPreservesReplyAction() {
        Intent startIntent = new Intent(context, KeepADBSelfPairService.class)
                .setAction("de.hohnepeople.keepadb.selfpair.START");
        ServiceController<KeepADBSelfPairService> controller =
                Robolectric.buildService(KeepADBSelfPairService.class, startIntent).create().startCommand(0, 0);
        KeepADBSelfPairService service = controller.get();

        // Simulate phase changing to DIALOG_FOUND
        KeepADBSelfPairService.publishForTesting(new KeepADBSelfPairService.Status(
                KeepADBSelfPairService.Phase.DIALOG_FOUND, null,
                KeepADBSelfPairService.Cleanup.KEPT_ON_FOR_KEEP_ALIVE));

        // Second tap on "Grant on this phone" (sends another START intent)
        controller.get().onStartCommand(startIntent, 0, 1);

        Notification notif = shadowOf(service).getLastForegroundNotification();
        assertNotNull(notif);
        assertEquals(1, notif.actions.length);
        assertEquals(context.getString(R.string.selfpair_enter_code), notif.actions[0].title.toString());
        assertNotNull(notif.actions[0].getRemoteInputs());
        controller.destroy();
    }

    @Test
    public void actionCodeWithStaleOrInvalidTokenIsIgnoredWithoutCancellingSession() {
        Intent startIntent = new Intent(context, KeepADBSelfPairService.class)
                .setAction("de.hohnepeople.keepadb.selfpair.START");
        ServiceController<KeepADBSelfPairService> controller =
                Robolectric.buildService(KeepADBSelfPairService.class, startIntent).create().startCommand(0, 0);

        // Send ACTION_CODE with no URI data or bad token
        Intent badCodeIntent = new Intent(context, KeepADBSelfPairService.class)
                .setAction("de.hohnepeople.keepadb.selfpair.CODE")
                .setData(Uri.parse("keepadb-selfpair:wrong-token"));
        controller.get().onStartCommand(badCodeIntent, 0, 1);

        // Service should still be in its active phase, not cancelled/idle
        assertTrue(KeepADBSelfPairService.status().isActive());
        controller.destroy();
    }

    // ---- B2: cancel when not working -------------------------------------------------------------

    @Test
    public void cancelWhenNotWorkingPublishesIdleAndStops() {
        Intent startIntent = new Intent(context, KeepADBSelfPairService.class)
                .setAction("de.hohnepeople.keepadb.selfpair.START");
        ServiceController<KeepADBSelfPairService> controller =
                Robolectric.buildService(KeepADBSelfPairService.class, startIntent).create().startCommand(0, 0);
        KeepADBSelfPairService service = controller.get();
        assertTrue(KeepADBSelfPairService.status().isActive());

        Intent cancelIntent = new Intent(context, KeepADBSelfPairService.class)
                .setAction("de.hohnepeople.keepadb.selfpair.CANCEL");
        controller.get().onStartCommand(cancelIntent, 0, 1);

        assertFalse(KeepADBSelfPairService.status().isActive());
        assertEquals(KeepADBSelfPairService.Phase.IDLE, KeepADBSelfPairService.status().phase);
        assertTrue(shadowOf(service).isStoppedBySelf());
        controller.destroy();
    }

    @Test
    public void cancellingDuringWorkingEndsInIdle() throws Exception {
        Intent startIntent = new Intent(context, KeepADBSelfPairService.class)
                .setAction("de.hohnepeople.keepadb.selfpair.START");
        ServiceController<KeepADBSelfPairService> controller =
                Robolectric.buildService(KeepADBSelfPairService.class, startIntent).create().startCommand(0, 0);
        KeepADBSelfPairService service = controller.get();

        service.onCode("123456");
        assertEquals(KeepADBSelfPairService.Phase.WORKING, KeepADBSelfPairService.status().phase);

        Intent cancelIntent = new Intent(context, KeepADBSelfPairService.class)
                .setAction("de.hohnepeople.keepadb.selfpair.CANCEL");
        controller.get().onStartCommand(cancelIntent, 0, 1);

        // Wait for the background worker to finish and post onFinished back to main thread
        for (int i = 0; i < 100 && KeepADBSelfPairService.status().isActive(); i++) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
            if (KeepADBSelfPairService.status().isActive()) {
                java.util.concurrent.locks.LockSupport.parkNanos(10_000_000L);
            }
        }
        org.robolectric.shadows.ShadowLooper.idleMainLooper();

        assertFalse("must not remain active", KeepADBSelfPairService.status().isActive());
        assertEquals(KeepADBSelfPairService.Phase.IDLE, KeepADBSelfPairService.status().phase);
        assertTrue(shadowOf(service).isStoppedBySelf());
        controller.destroy();
    }
}
