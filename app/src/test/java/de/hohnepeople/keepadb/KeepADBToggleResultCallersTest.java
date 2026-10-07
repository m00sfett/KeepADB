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
                "keepadb_trusted_networks", "keepadb_usb_profiles"}) {
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

        KeepADBReceiver.TrustAttempt attempt =
                KeepADBReceiver.trustBssidAndAttemptConnect(spy, BSSID, "Cafe-WLAN");

        assertFalse(attempt.enabled);
        assertFalse("#812: the cause is handed on, a rejected write is no permission failure",
                attempt.permissionFailure);
        assertFalse("a rejected write with the grant present is not a missing permission",
                spy.sawPermissionNotification);
    }

    @Test
    public void allowingAnAccessPointReportsASecurityExceptionAsAPermissionFailureEvenWithTheGrantPresent() {
        armTrustPromptEnable();
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(false);
        gateway.setWriteThrowsSecurityException(true);
        KeepADB.setGatewayForTesting(gateway);
        NotificationSpyContext spy = new NotificationSpyContext();

        KeepADBReceiver.TrustAttempt attempt =
                KeepADBReceiver.trustBssidAndAttemptConnect(spy, BSSID, "Cafe-WLAN");

        assertEquals("the write was attempted", java.util.Arrays.asList(true), gateway.writes);
        assertFalse(attempt.enabled);
        assertTrue(attempt.permissionFailure);
        assertTrue(spy.sawPermissionNotification);
    }

    @Test
    public void allowingAnAccessPointThatIsNotPermittedToEnableIsNoFailureAtAll() {
        // Keep-Alive off: the guard refuses before anything is toggled, so there is no cause.
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        KeepADBPreferences.setKeepAliveEnabled(context, false);
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(false);
        KeepADB.setGatewayForTesting(gateway);

        KeepADBReceiver.TrustAttempt attempt =
                KeepADBReceiver.trustBssidAndAttemptConnect(context, BSSID, "Cafe-WLAN");

        assertTrue("nothing may have been written", gateway.writes.isEmpty());
        assertFalse(attempt.enabled);
        assertFalse(attempt.permissionFailure);
    }

    @Test
    public void allowingAnAccessPointStillRaisesThePermissionNotificationWhenTheGrantIsMissing() {
        armTrustPromptEnable();
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));
        denyGrant();
        NotificationSpyContext spy = new NotificationSpyContext();

        KeepADBReceiver.TrustAttempt attempt =
                KeepADBReceiver.trustBssidAndAttemptConnect(spy, BSSID, "Cafe-WLAN");

        assertFalse(attempt.enabled);
        assertTrue(attempt.permissionFailure);
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

    // -- #817: SECURITY_EXCEPTION with the grant still reading present ---------------------------

    private KeepADBFakeSettingsGateway securityExceptionGateway(boolean initiallyEnabled) {
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(initiallyEnabled);
        gateway.setWriteThrowsSecurityException(true);
        KeepADB.setGatewayForTesting(gateway);
        return gateway;
    }

    @Test
    public void theDisableActionTreatsASecurityExceptionAsAPermissionFailure() {
        KeepADBFakeSettingsGateway gateway = securityExceptionGateway(true);

        assertFalse(KeepADBReceiver.handleDisableAction(context));

        assertEquals(java.util.Arrays.asList(false), gateway.writes);
        assertEquals(permissionToast(), ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void theWidgetTreatsASecurityExceptionAsAPermissionFailure() {
        KeepADBFakeSettingsGateway gateway = securityExceptionGateway(false);

        tapWidget();

        assertEquals(java.util.Arrays.asList(true), gateway.writes);
        assertEquals(permissionToast(), ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void theMainSwitchTreatsASecurityExceptionAsAPermissionFailure() {
        KeepADBFakeSettingsGateway gateway = securityExceptionGateway(false);

        Switch toggle = tapMainSwitch();

        assertEquals(java.util.Arrays.asList(true), gateway.writes);
        assertEquals(permissionToast(), ShadowToast.getTextOfLatestToast());
        assertFalse("the switch must fall back to the real state", toggle.isChecked());
    }

    @Test
    public void theTileTreatsASecurityExceptionAsAPermissionFailure() {
        KeepADBFakeSettingsGateway gateway = securityExceptionGateway(false);

        tapTile();

        assertEquals(java.util.Arrays.asList(true), gateway.writes);
        assertEquals(context.getString(R.string.tile_permission_error,
                context.getString(R.string.app_name)), ShadowToast.getTextOfLatestToast());
    }

    // -- #817: a request that lost to a newer intent is no permission failure --------------------

    /** Runs {@code tap} with a newer manual intent injected into the window before the write. */
    private void tapSuperseded(boolean newerIntentOn, Runnable tap) {
        try (KeepADBIntentRaceHook hook = KeepADBIntentRaceHook.arm(context,
                () -> KeepADB.setEnabled(context, newerIntentOn, "app"))) {
            tap.run();
            assertTrue("the request must have passed through the armed window", hook.fired());
        }
    }

    @Test
    public void aSupersededDisableActionIsNoPermissionFailure() {
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(true);
        KeepADB.setGatewayForTesting(gateway);
        boolean[] result = new boolean[1];

        tapSuperseded(true, () -> result[0] = KeepADBReceiver.handleDisableAction(context));

        assertFalse(result[0]);
        assertEquals("only the newer intent (enable) may have been written",
                java.util.Arrays.asList(true), gateway.writes);
        assertEquals(failedToast(), ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void aSupersededWidgetTapIsNoPermissionFailure() {
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(false);
        KeepADB.setGatewayForTesting(gateway);

        // The widget wants "on" (gateway is off); the newer intent is "off" and lands first.
        tapSuperseded(false, this::tapWidget);

        assertEquals("only the newer intent may have been written",
                java.util.Arrays.asList(false), gateway.writes);
        assertEquals(failedToast(), ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void aSupersededMainSwitchTapIsNoPermissionFailure() {
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(false);
        KeepADB.setGatewayForTesting(gateway);
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        Switch toggle = activity.findViewById(R.id.toggle);
        ShadowToast.reset();

        tapSuperseded(false, () -> {
            toggle.performClick();
            ShadowLooper.idleMainLooper();
        });

        assertEquals(java.util.Arrays.asList(false), gateway.writes);
        assertEquals(failedToast(), ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void aSupersededTileTapIsNoPermissionFailure() {
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(false);
        KeepADB.setGatewayForTesting(gateway);

        tapSuperseded(false, this::tapTile);

        assertEquals(java.util.Arrays.asList(false), gateway.writes);
        assertEquals(failedToast(), ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void aSupersededTrustAnswerRaisesNoPermissionNotification() {
        armTrustPromptEnable();
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(false);
        KeepADB.setGatewayForTesting(gateway);
        NotificationSpyContext spy = new NotificationSpyContext();
        KeepADBReceiver.TrustAttempt[] attempt = new KeepADBReceiver.TrustAttempt[1];

        tapSuperseded(false, () ->
                attempt[0] = KeepADBReceiver.trustBssidAndAttemptConnect(spy, BSSID, "Cafe-WLAN"));

        assertFalse(attempt[0].enabled);
        assertFalse(attempt[0].permissionFailure);
        assertFalse(spy.sawPermissionNotification);
        assertEquals(java.util.Arrays.asList(false), gateway.writes);
    }

    @Test
    public void aSupersededUsbHandoverTapShowsTheNeutralTextNotCheckPermission() {
        KeepADBUsbNotification.resetForTesting();
        KeepADBPreferences.setUsbWlanHandoverMode(context,
                KeepADBPreferences.USB_WLAN_HANDOVER_MODE_MANUAL);
        KeepADBUsbProfile.setNotificationEnabled(context, true);
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(false);
        KeepADB.setGatewayForTesting(gateway);
        context.sendStickyBroadcast(new Intent(KeepADBUsbReceiver.ACTION_USB_STATE)
                .putExtra("connected", true).putExtra("configured", true).putExtra("adb", true));

        tapSuperseded(false, () -> KeepADBUsbReceiver.handleHandoverEnableAction(context));

        assertEquals(KeepADB.ToggleResult.SUPERSEDED, KeepADBUsbHandover.lastManualActionResult());
        assertTrue(KeepADBUsbNotification.isLastHandoverActionFailed());
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        Notification posted = shadowOf(manager).getNotification(KeepADBUsbNotification.NOTIFICATION_ID);
        assertEquals(context.getString(R.string.usb_notification_handover_error_generic),
                String.valueOf(posted.extras.getCharSequence(Notification.EXTRA_TEXT)));
        KeepADBUsbNotification.resetForTesting();
    }
}
