package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;
import android.view.View;
import android.widget.Button;

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

/**
 * Onboarding coverage for issue #501: {@code MainActivity} must not fire the
 * {@code POST_NOTIFICATIONS} system prompt on cold start. Instead, {@code
 * notification_permission_panel} explains the benefit in place, and only a deliberate tap on its
 * button either requests the permission or, once the system has permanently denied further
 * prompts, opens the app's notification settings.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MainActivityNotificationPermissionPanelTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit();
        shadowOf((Application) context).denyPermissions(
                android.Manifest.permission.POST_NOTIFICATIONS);
    }

    @After
    public void tearDown() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit();
        KeepADB.resetForTesting();
    }

    @Test
    public void coldStartWithoutThePermissionNeverFiresTheSystemPrompt() {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();

        // The acceptance criterion this pins: onCreate() must not call requestPermissions() on
        // its own -- no PermissionsRequest is recorded until the user taps the panel's button.
        assertNull("onCreate() must not request POST_NOTIFICATIONS on its own",
                shadowOf(activity).getLastRequestedPermission());
        assertEquals(View.VISIBLE,
                activity.findViewById(R.id.notification_permission_panel).getVisibility());
        assertEquals(context.getString(R.string.notification_permission_request_button),
                ((Button) activity.findViewById(R.id.btn_open_notification_settings))
                        .getText().toString());
    }

    @Test
    public void panelStaysHiddenWhenThePermissionIsAlreadyGranted() {
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.POST_NOTIFICATIONS);

        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();

        assertEquals(View.GONE,
                activity.findViewById(R.id.notification_permission_panel).getVisibility());
    }

    @Test
    @Config(sdk = 32)
    public void panelStaysHiddenBeforeAndroid13() {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();

        assertEquals(View.GONE, controller.get()
                .findViewById(R.id.notification_permission_panel).getVisibility());
    }

    @Test
    public void dismissHidesPanelWithoutRequestingPermissionAndSurvivesRestart() {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();

        assertTrue(activity.findViewById(R.id.btn_dismiss_notification_permission_panel)
                .performClick());

        assertEquals(View.GONE,
                activity.findViewById(R.id.notification_permission_panel).getVisibility());
        assertFalse(KeepADBPreferences.isNotificationPermissionPanelVisible(activity));
        assertEquals(PackageManager.PERMISSION_DENIED,
                activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS));
        assertNull("Dismiss must not request POST_NOTIFICATIONS",
                shadowOf(activity).getLastRequestedPermission());
        assertNull("Dismiss must not open notification settings",
                shadowOf(activity).getNextStartedActivity());
        controller.pause().stop().destroy();

        ActivityController<MainActivity> restarted =
                Robolectric.buildActivity(MainActivity.class).setup();
        assertEquals("Dismiss state must survive an app restart", View.GONE,
                restarted.get().findViewById(R.id.notification_permission_panel).getVisibility());
        assertEquals(PackageManager.PERMISSION_DENIED, restarted.get()
                .checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS));
    }

    @Test
    public void firstTapRequestsThePermissionInsteadOfOpeningSettings() {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();

        assertTrue(activity.findViewById(R.id.btn_open_notification_settings).performClick());

        org.robolectric.shadows.ShadowActivity.PermissionsRequest request =
                shadowOf(activity).getLastRequestedPermission();
        assertEquals(android.Manifest.permission.POST_NOTIFICATIONS,
                request.requestedPermissions[0]);
    }

    @Test
    public void panelHidesOncePermissionIsGrantedThroughTheResultCallback() {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();

        activity.findViewById(R.id.btn_open_notification_settings).performClick();
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.POST_NOTIFICATIONS);
        activity.onRequestPermissionsResult(10,
                new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                new int[]{PackageManager.PERMISSION_GRANTED});

        assertEquals(View.GONE,
                activity.findViewById(R.id.notification_permission_panel).getVisibility());
    }

    @Test
    public void afterADenialTheButtonSwitchesToOpeningNotificationSettings() {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();

        activity.findViewById(R.id.btn_open_notification_settings).performClick();
        activity.onRequestPermissionsResult(10,
                new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                new int[]{PackageManager.PERMISSION_DENIED});

        assertEquals(View.VISIBLE,
                activity.findViewById(R.id.notification_permission_panel).getVisibility());
        assertEquals(context.getString(R.string.notification_permission_settings_button),
                ((Button) activity.findViewById(R.id.btn_open_notification_settings))
                        .getText().toString());

        activity.findViewById(R.id.btn_open_notification_settings).performClick();
        android.content.Intent opened = shadowOf(activity).getNextStartedActivity();
        assertEquals(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS, opened.getAction());
        assertEquals(activity.getPackageName(),
                opened.getStringExtra(android.provider.Settings.EXTRA_APP_PACKAGE));
    }
}
