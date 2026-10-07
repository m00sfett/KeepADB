package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.widget.Switch;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

/**
 * #795: the user-facing callers of {@link KeepADB#setEnabled} tell the failure causes apart
 * instead of reading every {@code false} as "permission missing". Each test drives the real
 * caller (receiver action, trust action, widget, main switch, tile) against a gateway that
 * produces exactly one cause, and checks which message the user gets. The automatic service
 * callers are covered in {@link KeepADBServiceLifecycleRobolectricTest}.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBToggleResultCallersTest {
    private static final String BSSID = "aa:bb:cc:dd:ee:01";

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = RuntimeEnvironment.getApplication();

    @Before
    public void setUp() {
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.POST_NOTIFICATIONS,
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
        clearPreferences();
        KeepADB.resetForTesting();
        ShadowToast.reset();
        KeepADBFakeScheduler scheduler = new KeepADBFakeScheduler();
        scheduler.setClockMs(100_000);
        KeepADB.setSchedulerForTesting(scheduler);
    }

    @After
    public void tearDown() {
        clearPreferences();
        KeepADB.resetForTesting();
        ShadowToast.reset();
    }

    private void clearPreferences() {
        for (String name : new String[] {"keepadb_prefs", "keepadb_diagnostics",
                "keepadb_trusted_networks"}) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit();
        }
    }

    private String permissionToast() {
        return context.getString(R.string.permission_error_toast, context.getPackageName());
    }

    private String failedToast() {
        return context.getString(R.string.toggle_failed_toast);
    }

    private KeepADBFakeSettingsGateway rejectingGateway(boolean initiallyEnabled) {
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(initiallyEnabled);
        gateway.setWriteSuccess(false);
        KeepADB.setGatewayForTesting(gateway);
        return gateway;
    }

    private void denyGrant() {
        shadowOf((Application) context).denyPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS);
    }

    private boolean permissionNotificationPosted() {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        Notification notification =
                shadowOf(manager).getNotification(KeepADBNotification.NOTIFICATION_ID);
        if (notification == null) return false;
        return context.getString(R.string.notification_permission_missing_title,
                context.getString(R.string.app_name))
                .equals(notification.extras.getString(Notification.EXTRA_TITLE));
    }

    // -- notification "disable" action -----------------------------------------------------

    @Test
    public void theDisableActionPointsAtThePermissionOnlyWhenItIsMissing() {
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(true));
        denyGrant();

        assertFalse(KeepADBReceiver.handleDisableAction(context));

        assertEquals(permissionToast(), ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void theDisableActionReportsARejectedWriteAsSuchNotAsAMissingPermission() {
        rejectingGateway(true);

        assertFalse(KeepADBReceiver.handleDisableAction(context));

        assertEquals(failedToast(), ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void theDisableActionShowsNoErrorWhenItSucceeds() {
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(true));

        assertTrue(KeepADBReceiver.handleDisableAction(context));

        assertEquals(0, ShadowToast.shownToastCount());
    }

    // -- network trust prompt "allow" ------------------------------------------------------

    private void armTrustPromptEnable() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
    }

    /**
     * The trust action re-renders the status notification right after the permission hint, which
     * replaces the hint with whatever the (off) state shows. The hint is therefore only visible
     * between those two steps; this context looks at the notification when {@code
     * KeepADBService.sync} requests the service start, which happens right after the hint.
     */
    private final class NotificationSpyContext extends ContextWrapper {
        boolean sawPermissionNotification;

        NotificationSpyContext() {
            super(context);
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }

        @Override
        public ComponentName startForegroundService(Intent service) {
            if (permissionNotificationPosted()) {
                sawPermissionNotification = true;
            }
            return super.startForegroundService(service);
        }
    }

    @Test
    public void allowingAnAccessPointRaisesNoPermissionNotificationForARejectedWrite() {
        armTrustPromptEnable();
        rejectingGateway(false);
        NotificationSpyContext spy = new NotificationSpyContext();

        assertFalse(KeepADBReceiver.trustBssidAndAttemptConnect(spy, BSSID, "Cafe-WLAN"));

        assertFalse("a rejected write with the grant present is not a missing permission",
                spy.sawPermissionNotification);
    }

    @Test
    public void allowingAnAccessPointStillRaisesThePermissionNotificationWhenTheGrantIsMissing() {
        armTrustPromptEnable();
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));
        denyGrant();
        NotificationSpyContext spy = new NotificationSpyContext();

        assertFalse(KeepADBReceiver.trustBssidAndAttemptConnect(spy, BSSID, "Cafe-WLAN"));

        assertTrue(spy.sawPermissionNotification);
    }

    // -- widget ----------------------------------------------------------------------------

    private void tapWidget() {
        new KeepADBWidget().onReceive(context,
                new Intent("de.hohnepeople.keepadb.TOGGLE").setPackage(context.getPackageName()));
        ShadowLooper.idleMainLooper();
    }

    @Test
    public void theWidgetPointsAtThePermissionOnlyWhenItIsMissing() {
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));
        denyGrant();

        tapWidget();

        assertEquals(permissionToast(), ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void theWidgetReportsARejectedWriteAsSuch() {
        KeepADBFakeSettingsGateway gateway = rejectingGateway(false);

        tapWidget();

        assertEquals("the tap must have attempted the enable", java.util.Arrays.asList(true), gateway.writes);
        assertEquals(failedToast(), ShadowToast.getTextOfLatestToast());
    }

    // -- main switch -----------------------------------------------------------------------

    private Switch tapMainSwitch() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        Switch toggle = activity.findViewById(R.id.toggle);
        ShadowToast.reset();
        toggle.performClick();
        ShadowLooper.idleMainLooper();
        return toggle;
    }

    @Test
    public void theMainSwitchPointsAtThePermissionOnlyWhenItIsMissing() {
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));
        denyGrant();

        tapMainSwitch();

        assertEquals(permissionToast(), ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void theMainSwitchReportsARejectedWriteAsSuchAndFallsBackToTheRealState() {
        KeepADBFakeSettingsGateway gateway = rejectingGateway(false);

        Switch toggle = tapMainSwitch();

        assertEquals(java.util.Arrays.asList(true), gateway.writes);
        assertEquals(failedToast(), ShadowToast.getTextOfLatestToast());
        assertFalse("the switch must show the unchanged real state", toggle.isChecked());
    }

    // -- quick settings tile ---------------------------------------------------------------

    private void tapTile() {
        // Same lifecycle handling as KeepADBPermanentReadRestrictionBehaviorTest: the tile service
        // controller is not destroyed, onStopListening() is the cleanup.
        KeepADBTileService tileService = Robolectric.buildService(KeepADBTileService.class)
                .create().get();
        try {
            tileService.onStartListening();
            ShadowToast.reset();
            tileService.onClick();
        } finally {
            tileService.onStopListening();
        }
    }

    @Test
    public void theTilePointsAtThePermissionOnlyWhenItIsMissing() {
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));
        denyGrant();

        tapTile();

        assertEquals(context.getString(R.string.tile_permission_error,
                context.getString(R.string.app_name)), ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void theTileReportsARejectedWriteAsSuch() {
        KeepADBFakeSettingsGateway gateway = rejectingGateway(false);

        tapTile();

        assertEquals(java.util.Arrays.asList(true), gateway.writes);
        assertEquals(failedToast(), ShadowToast.getTextOfLatestToast());
        assertFalse("no permission hint may be posted for a rejected write",
                permissionNotificationPosted());
    }
}
