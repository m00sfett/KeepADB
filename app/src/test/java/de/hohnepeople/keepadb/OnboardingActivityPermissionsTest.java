package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The permissions step of the setup assistant (#767) through its real entry points: the activity
 * with {@code EXTRA_STEP}, the buttons of its rows and the system's result callback. Every case of
 * a permission has both sides next to it: missing and granted, denied once and denied for good,
 * Android 13 and later and before.
 *
 * <p>The platform is the only source of truth in these tests: a grant is made with {@code
 * grantPermissions} and the activity has to read it back, the result arrays handed to the
 * callback are deliberately wrong in one test to prove they are not trusted.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class OnboardingActivityPermissionsTest {

    private static final String[] RUNTIME_PERMISSIONS = {
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            Manifest.permission.WRITE_SECURE_SETTINGS};

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private Context context;
    private final List<ActivityController<?>> controllers = new ArrayList<>();
    private ActivityController<OnboardingActivity> lastController;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        prefs().edit().clear().commit();
        KeepADB.resetForTesting();
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));
        // Nothing is granted unless a test says so.
        shadowOf((Application) context).denyPermissions(RUNTIME_PERMISSIONS);
    }

    @After
    public void tearDown() {
        for (int i = controllers.size() - 1; i >= 0; i--) {
            try {
                controllers.get(i).pause().stop().destroy();
            } catch (RuntimeException ignored) {
                // Already finished.
            }
        }
        prefs().edit().clear().commit();
        KeepADB.resetForTesting();
        KeepADBForceMode.resetForTesting();
    }

    // ---- sync() on resume (#628, N2) ---------------------------------------------------------------

    @Test
    public void thePermissionsStepCallsSyncEveryTimeItResumes() {
        keepAliveRunning();
        open(permissionsStepIntent());
        assertServiceStartRequested("first resume");
        drainServices();

        lastController.pause().resume();
        assertServiceStartRequested("coming back from a system page");
    }

    /** The other side: only the permissions step is the target of the "not readable" fix. */
    @Test
    public void otherPagesAndStepsDoNotCallSyncWhenTheyResume() {
        keepAliveRunning();
        open(OnboardingActivity.stepIntent(context, KeepADBOnboarding.Step.KEEP_ALIVE));
        lastController.pause().resume();
        assertNull("a single Keep-Alive step", shadowOf((Application) context).getNextStartedService());

        OnboardingActivity full = open(OnboardingActivity.fullIntent(context));
        click(full, R.id.onboarding_next); // Keep-Alive page
        lastController.pause().resume();
        assertNull("the full assistant away from the permissions page",
                shadowOf((Application) context).getNextStartedService());

        click(full, R.id.onboarding_next); // protection
        click(full, R.id.onboarding_next); // permissions
        lastController.pause().resume();
        assertNotNull("the full assistant on the permissions page",
                shadowOf((Application) context).getNextStartedService());
    }

    // ---- System permission -------------------------------------------------------------------------

    @Test
    public void theSystemPermissionShowsTheCommandWhileMissingAndIsDoneOnceGranted() {
        OnboardingActivity assistant = open(permissionsStepIntent());
        assertEquals("Missing", status(assistant, R.string.onboarding_perm_system_title));
        String shown = allText(assistant);
        assertTrue(shown, shown.contains(context.getString(R.string.setup_command,
                context.getPackageName())));
        assertFalse("the several-devices help is folded away",
                shown.contains(context.getString(R.string.setup_multi_device_label)));

        shadowOf((Application) context).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS);
        // Granted from the computer while the assistant is open: "Check permission" reads it back.
        button(assistant, context.getString(R.string.setup_refresh)).performClick();

        assertEquals("Done", status(assistant, R.string.onboarding_perm_system_title));
        shown = allText(assistant);
        assertFalse(shown, shown.contains(context.getString(R.string.setup_command,
                context.getPackageName())));
    }

    @Test
    public void theSeveralDevicesHelpOpensAndFoldsAgain() {
        OnboardingActivity assistant = open(permissionsStepIntent());
        String more = context.getString(R.string.onboarding_perm_system_more);
        findTextStarting(assistant, more).performClick();
        String shown = allText(assistant);
        assertTrue(shown, shown.contains(context.getString(R.string.setup_command_multi,
                context.getPackageName())));
        assertTrue(shown.contains(context.getString(R.string.setup_state_unauthorized_title)));

        findTextStarting(assistant, more).performClick();
        assertFalse(allText(assistant).contains(context.getString(R.string.setup_command_multi,
                context.getPackageName())));
    }

    // ---- Notifications: denied, denied for good, granted, API 30 against 33 and later ---------------

    @Test
    public void notificationsMissingAskTheSystemAndAreDoneOnceGranted() {
        OnboardingActivity assistant = open(permissionsStepIntent());
        String notifications = context.getString(R.string.notification_permission_panel_title);
        assertEquals("Missing", statusOf(assistant, notifications));

        button(assistant, context.getString(R.string.notification_permission_request_button))
                .performClick();
        ShadowActivity.PermissionsRequest request = shadowOf(assistant).getLastRequestedPermission();
        assertNotNull(request);
        assertEquals(Manifest.permission.POST_NOTIFICATIONS, request.requestedPermissions[0]);
        assertEquals(OnboardingPermissions.REQUEST_NOTIFICATIONS, request.requestCode);

        shadowOf((Application) context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        assistant.onRequestPermissionsResult(OnboardingPermissions.REQUEST_NOTIFICATIONS,
                new String[] {Manifest.permission.POST_NOTIFICATIONS},
                new int[] {PackageManager.PERMISSION_GRANTED});
        assertEquals("Done", statusOf(assistant, notifications));
        assertNull("no button once granted", findButton(assistant,
                context.getString(R.string.notification_permission_request_button)));
        assertNull(findButton(assistant,
                context.getString(R.string.notification_permission_settings_button)));
    }

    @Test
    public void aDeniedNotificationPermissionIsAskedAgainWhileAndroidStillShowsTheDialog() {
        OnboardingActivity assistant = open(permissionsStepIntent());
        shadowOf(context.getPackageManager()).setShouldShowRequestPermissionRationale(
                Manifest.permission.POST_NOTIFICATIONS, true);
        button(assistant, context.getString(R.string.notification_permission_request_button))
                .performClick();
        ShadowActivity.PermissionsRequest first = shadowOf(assistant).getLastRequestedPermission();

        // Denied once: Android keeps offering a rationale, so the same button asks again.
        assistant.onRequestPermissionsResult(OnboardingPermissions.REQUEST_NOTIFICATIONS,
                new String[] {Manifest.permission.POST_NOTIFICATIONS},
                new int[] {PackageManager.PERMISSION_DENIED});
        button(assistant, context.getString(R.string.notification_permission_request_button))
                .performClick();

        assertNotSame("it asked a second time", first,
                shadowOf(assistant).getLastRequestedPermission());
        // Robolectric records the system dialog as a started activity; nothing else was opened.
        for (Intent started = shadowOf(assistant).getNextStartedActivity(); started != null;
                started = shadowOf(assistant).getNextStartedActivity()) {
            assertEquals("android.content.pm.action.REQUEST_PERMISSIONS", started.getAction());
        }
    }

    @Test
    public void aPermanentlyDeniedNotificationPermissionOpensTheNotificationSettings() {
        OnboardingActivity assistant = open(permissionsStepIntent());
        button(assistant, context.getString(R.string.notification_permission_request_button))
                .performClick();
        // Asked, denied, and no rationale any more: Android will not show the dialog again.
        assistant.onRequestPermissionsResult(OnboardingPermissions.REQUEST_NOTIFICATIONS,
                new String[] {Manifest.permission.POST_NOTIFICATIONS},
                new int[] {PackageManager.PERMISSION_DENIED});

        assertNull("the label changed, the dead button is gone", findButton(assistant,
                context.getString(R.string.notification_permission_request_button)));
        button(assistant, context.getString(R.string.notification_permission_settings_button))
                .performClick();
        Intent opened = shadowOf(assistant).getNextStartedActivity();
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, opened.getAction());
        assertEquals(context.getPackageName(), opened.getStringExtra(Settings.EXTRA_APP_PACKAGE));
    }

    @Test
    public void theResultArraysAreNotTrustedTheGrantIsReadFromTheSystem() {
        OnboardingActivity assistant = open(permissionsStepIntent());
        String notifications = context.getString(R.string.notification_permission_panel_title);
        assistant.onRequestPermissionsResult(OnboardingPermissions.REQUEST_NOTIFICATIONS,
                new String[] {Manifest.permission.POST_NOTIFICATIONS},
                new int[] {PackageManager.PERMISSION_GRANTED});
        assertEquals("the system still says no", "Missing", statusOf(assistant, notifications));

        assistant.onRequestPermissionsResult(OnboardingPermissions.REQUEST_NOTIFICATIONS,
                new String[0], new int[0]);
        shadowOf((Application) context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        assistant.onRequestPermissionsResult(OnboardingPermissions.REQUEST_NOTIFICATIONS,
                new String[0], new int[0]);
        assertEquals("an empty result still re-reads it", "Done", statusOf(assistant, notifications));
    }

    @Test
    @Config(sdk = 30)
    public void beforeAndroid13ThereIsNoNotificationPermissionToAsk() {
        assertFalse(OnboardingPermissions.hasNotificationPermission());
        assertFalse(OnboardingPermissions.isNotificationMissing(context));
        OnboardingActivity assistant = open(permissionsStepIntent());

        assertNull("no notification row on API 30", findText(assistant,
                context.getString(R.string.notification_permission_panel_title)));
        assertNull(findButton(assistant,
                context.getString(R.string.notification_permission_request_button)));
        // The system permission and the location permission count; the notifications do not.
        assertEquals(2, OnboardingPermissions.missingCount(context));
    }

    @Test
    @Config(sdk = 33)
    public void fromAndroid13OnTheNotificationPermissionIsAskedAndCounted() {
        assertTrue(OnboardingPermissions.hasNotificationPermission());
        assertTrue(OnboardingPermissions.isNotificationMissing(context));
        OnboardingActivity assistant = open(permissionsStepIntent());

        assertEquals("Missing", statusOf(assistant,
                context.getString(R.string.notification_permission_panel_title)));
        assertEquals(3, OnboardingPermissions.missingCount(context));
    }

    // ---- Location ----------------------------------------------------------------------------------

    @Test
    public void locationMissingAsksForPreciseAndApproximateAndIsDoneOnceGranted() {
        OnboardingActivity assistant = open(permissionsStepIntent());
        assertEquals("Missing", status(assistant, R.string.onboarding_perm_location_title));

        button(assistant, context.getString(R.string.location_permission_panel_grant_button))
                .performClick();
        ShadowActivity.PermissionsRequest request = shadowOf(assistant).getLastRequestedPermission();
        assertEquals(Manifest.permission.ACCESS_FINE_LOCATION, request.requestedPermissions[0]);
        assertEquals(Manifest.permission.ACCESS_COARSE_LOCATION, request.requestedPermissions[1]);
        assertEquals(NetworkListRenderer.REQUEST_LOCATION, request.requestCode);

        shadowOf((Application) context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
        assistant.onRequestPermissionsResult(NetworkListRenderer.REQUEST_LOCATION,
                new String[0], new int[0]);
        assertEquals("Done", status(assistant, R.string.onboarding_perm_location_title));
    }

    @Test
    public void aDeniedOnceLocationPermissionIsAskedAgain() {
        OnboardingActivity assistant = open(permissionsStepIntent());
        shadowOf(context.getPackageManager()).setShouldShowRequestPermissionRationale(
                Manifest.permission.ACCESS_FINE_LOCATION, true);
        button(assistant, context.getString(R.string.location_permission_panel_grant_button))
                .performClick();
        ShadowActivity.PermissionsRequest first = shadowOf(assistant).getLastRequestedPermission();
        assistant.onRequestPermissionsResult(NetworkListRenderer.REQUEST_LOCATION,
                new String[0], new int[0]);

        // Android still shows a rationale: the same button asks again, it does not open settings.
        button(assistant, context.getString(R.string.location_permission_panel_grant_button))
                .performClick();
        assertNotSame(first, shadowOf(assistant).getLastRequestedPermission());
        assertNull(findButton(assistant,
                context.getString(R.string.location_permission_settings_button)));
    }

    @Test
    public void aPermanentlyDeniedLocationPermissionOpensTheAppSettings() {
        OnboardingActivity assistant = open(permissionsStepIntent());
        button(assistant, context.getString(R.string.location_permission_panel_grant_button))
                .performClick();
        // Asked, denied, no rationale any more: the dialog will not come again.
        assistant.onRequestPermissionsResult(NetworkListRenderer.REQUEST_LOCATION,
                new String[0], new int[0]);

        assertNull(findButton(assistant,
                context.getString(R.string.location_permission_panel_grant_button)));
        shadowOf(assistant).getNextStartedActivity(); // the system dialog of the first tap
        button(assistant, context.getString(R.string.location_permission_settings_button))
                .performClick();
        Intent opened = shadowOf(assistant).getNextStartedActivity();
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, opened.getAction());
        assertEquals("package:" + context.getPackageName(), opened.getData().toString());
    }

    @Test
    public void theDecisionBetweenAskingAndOpeningTheSettingsHasFourCases() {
        assertEquals(OnboardingPermissions.Action.REQUEST, OnboardingPermissions.decide(false, false));
        assertEquals(OnboardingPermissions.Action.REQUEST, OnboardingPermissions.decide(false, true));
        assertEquals(OnboardingPermissions.Action.REQUEST, OnboardingPermissions.decide(true, true));
        assertEquals(OnboardingPermissions.Action.OPEN_SETTINGS,
                OnboardingPermissions.decide(true, false));
    }

    // ---- Background location and battery -----------------------------------------------------------

    @Test
    public void backgroundDetectionWaitsForLocationThenLeadsToTheAppSettings() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        OnboardingActivity assistant = open(permissionsStepIntent());
        String blocked = context.getString(R.string.onboarding_perm_background_blocked);
        assertEquals("Recommended", status(assistant, R.string.onboarding_perm_background_title));
        assertTrue(allText(assistant).contains(blocked));
        assertNull("no button before location is allowed", findButton(assistant,
                context.getString(R.string.location_permission_settings_button)));

        shadowOf((Application) context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
        lastController.pause().resume();
        assertFalse(allText(assistant).contains(blocked));
        button(assistant, context.getString(R.string.location_permission_settings_button))
                .performClick();
        Intent opened = shadowOf(assistant).getNextStartedActivity();
        assertEquals(KeepADBBackgroundLocation.appDetailsIntent(context).getAction(),
                opened.getAction());
        assertEquals("package:" + context.getPackageName(), opened.getData().toString());

        shadowOf((Application) context).grantPermissions(
                Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        lastController.pause().resume();
        assertEquals("Done", status(assistant, R.string.onboarding_perm_background_title));
    }

    @Test
    public void withKeepAliveOffBackgroundAndBatteryAreOptionalAndOfferNothing() {
        OnboardingActivity off = open(permissionsStepIntent());
        String onlyKeepAlive = context.getString(R.string.onboarding_perm_keep_alive_only);
        assertEquals("Optional", status(off, R.string.onboarding_perm_background_title));
        assertEquals("Optional", status(off, R.string.onboarding_perm_battery_title));
        assertEquals(2, countOccurrences(allText(off), onlyKeepAlive));
        assertNull(findButton(off, context.getString(R.string.battery_optimization_button)));

        // The other side: Keep-Alive on turns both into recommendations with their action.
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        OnboardingActivity on = open(permissionsStepIntent());
        assertEquals("Recommended", status(on, R.string.onboarding_perm_battery_title));
        assertEquals(0, countOccurrences(allText(on), onlyKeepAlive));
        button(on, context.getString(R.string.battery_optimization_button)).performClick();
        Intent opened = shadowOf(on).getNextStartedActivity();
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, opened.getAction());
        assertEquals("package:" + context.getPackageName(), opened.getData().toString());
    }

    @Test
    public void anExemptionFromTheBatteryOptimizationIsDone() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        shadowOf(power).setIgnoringBatteryOptimizations(context.getPackageName(), true);
        OnboardingActivity assistant = open(permissionsStepIntent());

        assertEquals("Done", status(assistant, R.string.onboarding_perm_battery_title));
        assertNull(findButton(assistant, context.getString(R.string.battery_optimization_button)));
    }

    @Test
    public void theForceModeNotesThatLocationIsNeededAgainAfterwards() {
        KeepADBForceMode.setClockForTesting(new KeepADBForceTestSupport.TestClock());
        String note = context.getString(R.string.onboarding_perm_force_note);
        assertEquals(0, countOccurrences(allText(open(permissionsStepIntent())), note));

        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.DEFAULT, true));
        assertEquals("on the location row and on the background row", 2,
                countOccurrences(allText(open(permissionsStepIntent())), note));
    }

    // ---- Large font, narrow display ------------------------------------------------------------------

    @Test
    @Config(sdk = 34, qualifiers = "w320dp-h640dp")
    public void theStepWithEveryRowOpenFitsAt200PercentFontOn320Dp() {
        org.robolectric.RuntimeEnvironment.setFontScale(2.0f);
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        OnboardingActivity assistant = open(permissionsStepIntent());
        findTextStarting(assistant, context.getString(R.string.onboarding_perm_system_more))
                .performClick();
        OnboardingLayoutAssertions.assertFits(assistant, 320, "missing, help open, Keep-Alive on");

        shadowOf((Application) context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
        lastController.pause().resume();
        OnboardingLayoutAssertions.assertFits(assistant, 320, "location granted");
    }

    // ---- Status changes are announced --------------------------------------------------------------

    @Test
    public void onlyARowThatChangedItsStatusWordIsAnnounced() {
        Map<String, String> before = new HashMap<>();
        before.put(OnboardingActionSteps.Permissions.ITEM_SYSTEM, "Missing");
        before.put(OnboardingActionSteps.Permissions.ITEM_LOCATION, "Missing");
        Map<String, String> after = new HashMap<>();
        after.put(OnboardingActionSteps.Permissions.ITEM_SYSTEM, "Done");
        after.put(OnboardingActionSteps.Permissions.ITEM_LOCATION, "Missing");
        after.put(OnboardingActionSteps.Permissions.ITEM_BATTERY, "Done");

        assertEquals(List.of(context.getString(R.string.onboarding_perm_system_title) + ": Done"),
                OnboardingActionSteps.Permissions.changedRows(before, after, context));
        assertTrue("the first read announces nothing",
                OnboardingActionSteps.Permissions.changedRows(new HashMap<>(), after, context)
                        .isEmpty());
        assertTrue(OnboardingActionSteps.Permissions.changedRows(after, after, context).isEmpty());
    }

    // ---- Skip, Next, focus ---------------------------------------------------------------------------

    @Test
    public void skipGoesOnWithoutWritingAndOnlyActionStepsOfferIt() {
        OnboardingActivity assistant = open(OnboardingActivity.fullIntent(context));
        assertEquals(context.getString(R.string.onboarding_later), text(assistant, R.id.onboarding_secondary));
        click(assistant, R.id.onboarding_next);
        assertEquals("a pure choice has no Skip", View.GONE,
                assistant.findViewById(R.id.onboarding_secondary).getVisibility());
        click(assistant, R.id.onboarding_next);
        click(assistant, R.id.onboarding_next); // permissions
        assertEquals(context.getString(R.string.onboarding_permissions_title),
                text(assistant, R.id.onboarding_page_title));
        assertEquals(context.getString(R.string.onboarding_skip),
                text(assistant, R.id.onboarding_secondary));

        Map<String, ?> before = prefs().getAll();
        click(assistant, R.id.onboarding_secondary);
        assertEquals(context.getString(R.string.onboarding_network_title),
                text(assistant, R.id.onboarding_page_title));
        assertEquals("Skip wrote nothing", before, prefs().getAll());
        assertFalse(assistant.isFinishing());
        assertEquals("Skip is no Later: the assistant is not closed", 0,
                KeepADBPreferences.getOnboardingCompletedVersion(context));

        click(assistant, R.id.onboarding_secondary); // skip the Wi-Fi as well
        assertEquals(context.getString(R.string.onboarding_details_title),
                text(assistant, R.id.onboarding_page_title));
        assertEquals(View.GONE, assistant.findViewById(R.id.onboarding_secondary).getVisibility());
    }

    @Test
    public void aSingleStepNeverOffersSkip() {
        OnboardingActivity assistant = open(permissionsStepIntent());
        assertEquals(View.GONE, assistant.findViewById(R.id.onboarding_secondary).getVisibility());
        assertEquals(context.getString(R.string.onboarding_done), text(assistant, R.id.onboarding_next));
    }

    @Test
    public void aDeepLinkBringsTheNamedRowIntoFocusAndAnUnknownItemChangesNothing() {
        OnboardingActivity assistant = open(OnboardingActivity.notificationIntent(context,
                KeepADBOnboarding.Step.PERMISSIONS,
                OnboardingActionSteps.Permissions.ITEM_BACKGROUND_LOCATION));
        View focused = focusOf(assistant);
        assertNotNull("the named row has the focus", focused);
        assertTrue(allText(focused).contains(
                context.getString(R.string.onboarding_perm_background_title)));

        for (String item : new String[] {"nonsense", null}) {
            OnboardingActivity other = open(OnboardingActivity.notificationIntent(context,
                    KeepADBOnboarding.Step.PERMISSIONS, item));
            View focus = focusOf(other);
            assertTrue("no row of the step is focused for " + item,
                    focus == null || !allText(focus).contains(
                            context.getString(R.string.onboarding_perm_background_title)));
        }
    }

    // ---- The deep links of #759 phase 2, through the real notifications ------------------------------

    @Test
    public void thePermissionMissingNotificationOpensTheStepAtTheCommandRowAndLeavesToTheHomeScreen() {
        shadowOf((Application) context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        KeepADBNotification.showPermissionMissing(context);
        Intent tap = tapTarget(KeepADBNotification.NOTIFICATION_ID);

        OnboardingActivity assistant = open(tap);
        assertEquals(context.getString(R.string.onboarding_permissions_title),
                text(assistant, R.id.onboarding_header_title));
        assertTrue(allText(focusOf(assistant)).contains(
                context.getString(R.string.onboarding_perm_system_title)));

        click(assistant, R.id.onboarding_next); // Done
        assertTrue(assistant.isFinishing());
        assertEquals("leaving a notification target opens the home screen",
                MainActivity.class.getName(),
                shadowOf(assistant).getNextStartedActivity().getComponent().getClassName());
        assertEquals("a single step never closes the assistant", 0,
                KeepADBPreferences.getOnboardingCompletedVersion(context));
    }

    @Test
    public void theNotReadableNotificationOpensTheBackgroundRowAndTheStepPromotesTheService() {
        keepAliveRunning();
        shadowOf((Application) context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.ACCESS_FINE_LOCATION);
        android.net.wifi.WifiInfo info = org.robolectric.shadows.ShadowWifiInfo.newInstance();
        shadowOf(info).setSSID("Cafe-WLAN");
        shadowOf(info).setBSSID(KeepADBNetworkIdentity.REDACTED_BSSID);
        shadowOf((android.net.wifi.WifiManager) context.getSystemService(Context.WIFI_SERVICE))
                .setConnectionInfo(info);
        shadowOf((android.location.LocationManager)
                context.getSystemService(Context.LOCATION_SERVICE)).setLocationEnabled(true);
        assertTrue(KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));
        Intent tap = tapTarget(KeepADBNetworkTrustPrompt.NOTIFICATION_ID);

        OnboardingActivity assistant = open(tap);

        assertTrue("the background-detection row is where the user lands",
                allText(focusOf(assistant)).contains(
                        context.getString(R.string.onboarding_perm_background_title)));
        assertServiceStartRequested("the foreground start that re-promotes the service (#628)");
    }

    /**
     * Two cards open the same activity with different extras. A PendingIntent ignores extras when
     * it is matched, so a shared request code would let one card overwrite the other's target
     * (#603).
     */
    @Test
    public void theTwoCardsThatOpenTheStepKeepPendingIntentsOfTheirOwn() {
        keepAliveRunning();
        shadowOf((Application) context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.ACCESS_FINE_LOCATION);
        android.net.wifi.WifiInfo info = org.robolectric.shadows.ShadowWifiInfo.newInstance();
        shadowOf(info).setSSID("Cafe-WLAN");
        shadowOf(info).setBSSID(KeepADBNetworkIdentity.REDACTED_BSSID);
        shadowOf((android.net.wifi.WifiManager) context.getSystemService(Context.WIFI_SERVICE))
                .setConnectionInfo(info);
        shadowOf((android.location.LocationManager)
                context.getSystemService(Context.LOCATION_SERVICE)).setLocationEnabled(true);
        assertTrue(KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));
        KeepADBNotification.showPermissionMissing(context);

        android.app.NotificationManager manager =
                context.getSystemService(android.app.NotificationManager.class);
        Notification missing = shadowOf(manager).getNotification(KeepADBNotification.NOTIFICATION_ID);
        Notification unreadable =
                shadowOf(manager).getNotification(KeepADBNetworkTrustPrompt.NOTIFICATION_ID);
        assertNotNull(missing);
        assertNotNull(unreadable);
        assertEquals(OnboardingActionSteps.Permissions.ITEM_SYSTEM, shadowOf(missing.contentIntent)
                .getSavedIntent().getStringExtra(OnboardingActivity.EXTRA_FOCUS_ITEM));
        assertEquals(OnboardingActionSteps.Permissions.ITEM_BACKGROUND_LOCATION,
                shadowOf(unreadable.contentIntent).getSavedIntent()
                        .getStringExtra(OnboardingActivity.EXTRA_FOCUS_ITEM));
        assertTrue("each card needs a request code of its own",
                shadowOf(missing.contentIntent).getRequestCode()
                        != shadowOf(unreadable.contentIntent).getRequestCode());
    }

    // ---- Summary and the Keep-Alive hint -------------------------------------------------------------

    @Test
    public void theSummaryCountsTheMissingPermissionsAndSaysWhenNoneIsMissing() {
        OnboardingActivity assistant = open(OnboardingActivity.fullIntent(context));
        advanceToSummary(assistant);
        assertTrue(allText(assistant), allText(assistant).contains(
                context.getString(R.string.onboarding_permissions_summary_missing, 3)));

        shadowOf((Application) context).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS,
                Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.ACCESS_FINE_LOCATION);
        OnboardingActivity granted = open(OnboardingActivity.fullIntent(context));
        advanceToSummary(granted);
        String shown = allText(granted);
        assertTrue(shown, shown.contains(context.getString(R.string.onboarding_permissions_summary_done)));
        assertFalse(shown.contains(context.getString(R.string.onboarding_permissions_summary_missing, 0)));
    }

    @Test
    public void theKeepAliveStepNamesTheStepWhereTheNotificationPermissionIsAsked() {
        OnboardingActivity assistant = open(OnboardingActivity.fullIntent(context));
        click(assistant, R.id.onboarding_next); // Keep-Alive, Off preselected
        String hint = context.getString(R.string.onboarding_keep_alive_notification_hint,
                OnboardingActivity.stepNumber(KeepADBOnboarding.Step.PERMISSIONS));
        assertTrue(hint, hint.contains("3"));
        assertFalse("Off needs no notification", isShown(assistant, hint));

        cardAt(assistant, 1).performClick(); // On
        assertTrue("On without the permission", isShown(assistant, hint));
        cardAt(assistant, 0).performClick();
        assertFalse(isShown(assistant, hint));

        // The other side: the permission is there, or the step stands alone.
        cardAt(assistant, 1).performClick();
        shadowOf((Application) context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        OnboardingActivity granted = open(OnboardingActivity.stepIntent(context,
                KeepADBOnboarding.Step.KEEP_ALIVE));
        cardAt(granted, 1).performClick();
        assertFalse("permission granted", isShown(granted, hint));

        shadowOf((Application) context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        OnboardingActivity alone = open(OnboardingActivity.stepIntent(context,
                KeepADBOnboarding.Step.KEEP_ALIVE));
        cardAt(alone, 1).performClick();
        assertFalse("a step on its own has no next step to point at", isShown(alone, hint));
    }

    @Test
    @Config(sdk = 30)
    public void beforeAndroid13TheKeepAliveStepShowsNoNotificationHint() {
        OnboardingActivity assistant = open(OnboardingActivity.fullIntent(context));
        click(assistant, R.id.onboarding_next);
        cardAt(assistant, 1).performClick(); // On
        String hint = context.getString(R.string.onboarding_keep_alive_notification_hint,
                OnboardingActivity.stepNumber(KeepADBOnboarding.Step.PERMISSIONS));
        assertFalse(isShown(assistant, hint));
    }

    // ---- Helpers ---------------------------------------------------------------------------------------

    /** The view with the focus (the window's own lookup answers null under Robolectric). */
    private static View focusOf(android.app.Activity activity) {
        return activity.getWindow().getDecorView().findFocus();
    }

    private Intent permissionsStepIntent() {
        return OnboardingActivity.stepIntent(context, KeepADBOnboarding.Step.PERMISSIONS);
    }

    private OnboardingActivity open(Intent intent) {
        lastController = Robolectric.buildActivity(OnboardingActivity.class, intent).setup();
        controllers.add(lastController);
        return lastController.get();
    }

    private void keepAliveRunning() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBPreferences.setLastDesiredOn(context, true);
        assertTrue(KeepADBService.shouldRun(context));
    }

    private void assertServiceStartRequested(String when) {
        Intent started = shadowOf((Application) context).getNextStartedService();
        assertNotNull(when + ": sync() must request the foreground service", started);
        assertEquals(KeepADBService.class.getName(), started.getComponent().getClassName());
    }

    private void drainServices() {
        while (shadowOf((Application) context).getNextStartedService() != null) {
            // Drop what earlier steps started.
        }
    }

    private Intent tapTarget(int notificationId) {
        Notification notification = shadowOf(context.getSystemService(
                android.app.NotificationManager.class)).getNotification(notificationId);
        assertNotNull("the notification is posted", notification);
        return shadowOf(notification.contentIntent).getSavedIntent();
    }

    private void advanceToSummary(OnboardingActivity assistant) {
        for (int i = 0; i < 12
                && !context.getString(R.string.onboarding_summary_title).equals(
                        text(assistant, R.id.onboarding_page_title)); i++) {
            click(assistant, R.id.onboarding_next);
        }
    }

    private void click(android.app.Activity activity, int id) {
        View view = activity.findViewById(id);
        assertEquals("button " + id + " must be visible", View.VISIBLE, view.getVisibility());
        view.performClick();
    }

    private String text(android.app.Activity activity, int id) {
        return ((TextView) activity.findViewById(id)).getText().toString();
    }

    /** The status word of the row whose title is {@code titleRes}, from its spoken line "Title, Status". */
    private String status(android.app.Activity activity, int titleRes) {
        return statusOf(activity, context.getString(titleRes));
    }

    private String statusOf(android.app.Activity activity, String title) {
        for (View view : allViews(activity.findViewById(R.id.onboarding_page_content))) {
            CharSequence description = view.getContentDescription();
            if (description != null && description.toString().startsWith(title + ", ")) {
                return description.toString().substring(title.length() + 2);
            }
        }
        throw new AssertionError("no row \"" + title + "\" in " + allText(activity));
    }

    private Button button(android.app.Activity activity, String label) {
        Button found = findButton(activity, label);
        assertNotNull("no button \"" + label + "\" in " + allText(activity), found);
        return found;
    }

    private Button findButton(android.app.Activity activity, String label) {
        for (View view : allViews(activity.findViewById(R.id.onboarding_page_content))) {
            if (view instanceof Button && view.getVisibility() == View.VISIBLE
                    && label.contentEquals(((Button) view).getText())) {
                return (Button) view;
            }
        }
        return null;
    }

    private TextView findText(android.app.Activity activity, String text) {
        for (View view : allViews(activity.findViewById(R.id.onboarding_page_content))) {
            if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) {
                return (TextView) view;
            }
        }
        return null;
    }

    private TextView findTextStarting(android.app.Activity activity, String prefix) {
        for (View view : allViews(activity.findViewById(R.id.onboarding_page_content))) {
            if (view instanceof TextView && ((TextView) view).getText().toString().startsWith(prefix)) {
                return (TextView) view;
            }
        }
        throw new AssertionError("no text starting \"" + prefix + "\" in " + allText(activity));
    }

    private boolean isShown(android.app.Activity activity, String text) {
        TextView view = findText(activity, text);
        return view != null && view.getVisibility() == View.VISIBLE;
    }

    private View cardAt(android.app.Activity activity, int index) {
        List<View> cards = new ArrayList<>();
        for (View view : allViews(activity.findViewById(R.id.onboarding_page_content))) {
            if (view.getId() == R.id.choice_card) cards.add(view);
        }
        return cards.get(index);
    }

    private String allText(android.app.Activity activity) {
        return allText(activity.findViewById(R.id.onboarding_page));
    }

    private static String allText(View root) {
        StringBuilder out = new StringBuilder();
        for (View view : allViews(root)) {
            if (view instanceof TextView && view.getVisibility() == View.VISIBLE) {
                out.append(((TextView) view).getText()).append('\n');
            }
        }
        return out.toString();
    }

    private static List<View> allViews(View root) {
        List<View> result = new ArrayList<>();
        collect(root, result);
        return result;
    }

    private static void collect(View view, List<View> out) {
        out.add(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), out);
        }
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE);
    }
}
