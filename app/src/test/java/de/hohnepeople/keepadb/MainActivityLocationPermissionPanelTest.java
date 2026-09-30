package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
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
import org.robolectric.shadows.ShadowLooper;

/**
 * Onboarding coverage for issue #459, #504, #507 and #654:
 * Following #507, {@code MainActivity} no longer surfaces any location permission panel or
 * in-context prompt -- the location permission flow lives in {@link SettingsActivity}'s Network
 * card. Since #654 the grant button belongs to the current-connection status there and does not
 * depend on the observation option.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MainActivityLocationPermissionPanelTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit();
        shadowOf((Application) context).denyPermissions(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
    }

    @After
    public void tearDown() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit();
        KeepADB.resetForTesting();
    }

    @Test
    public void homeScreenNeverSurfacesLocationPermissionPrompt() {
        // Test in both allowlist mode and all-Wi-Fi mode
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();

        assertNull(activity.findViewById(R.id.network_status_action));

        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        controller.pause().resume();
        ShadowLooper.idleMainLooper();

        assertNull(activity.findViewById(R.id.network_status_action));
    }

    @Test
    public void settingsNetworkCardShowsLocationGrantButtonWhenPermissionIsMissing() {

        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();
        activity.findViewById(R.id.settings_network_beta_header).performClick();
        ShadowLooper.idleMainLooper();

        Button inContextButton = activity.findViewById(R.id.network_status_action);
        assertNotNull("In-context grant button must be present in the current-connection status",
                inContextButton);
        assertEquals(View.VISIBLE, inContextButton.getVisibility());
        assertEquals(context.getString(R.string.location_permission_panel_grant_button),
                inContextButton.getText().toString());

        assertTrue(inContextButton.performClick());

        org.robolectric.shadows.ShadowActivity.PermissionsRequest request =
                shadowOf(activity).getLastRequestedPermission();
        assertEquals(android.Manifest.permission.ACCESS_FINE_LOCATION, request.requestedPermissions[0]);

        // After denial, in-context button switches to settings fallback
        activity.onRequestPermissionsResult(SettingsActivity.WIFI_APS_LOCATION_PERMISSION_REQUEST,
                new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION},
                new int[]{PackageManager.PERMISSION_DENIED, PackageManager.PERMISSION_DENIED});

        Button updatedButton = activity.findViewById(R.id.network_status_action);
        assertNotNull(updatedButton);
        assertEquals(context.getString(R.string.location_permission_settings_button),
                updatedButton.getText().toString());

        updatedButton.performClick();
        Intent opened = shadowOf(activity).getNextStartedActivity();
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, opened.getAction());
        assertEquals(Uri.parse("package:" + activity.getPackageName()), opened.getData());
    }

    @Test
    public void settingsNetworkCardHidesLocationGrantButtonWhenPermissionGranted() {
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.ACCESS_FINE_LOCATION);

        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();
        activity.findViewById(R.id.settings_network_beta_header).performClick();
        ShadowLooper.idleMainLooper();

        Button action = activity.findViewById(R.id.network_status_action);
        assertNotEquals("The grant is not offered once it is granted",
                context.getString(R.string.location_permission_panel_grant_button),
                action.getText().toString());
        assertNotEquals(context.getString(R.string.location_permission_settings_button),
                action.getText().toString());
    }

    /**
     * #654: the grant sits in the current-connection status, which is independent of the
     * observation option -- switching observation off must not take it away.
     */
    @Test
    public void locationGrantButtonDoesNotDependOnTheObservationOption() {
        KeepADBPreferences.setWifiApsFeatureEnabled(context, false);

        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();
        activity.findViewById(R.id.settings_network_beta_header).performClick();
        ShadowLooper.idleMainLooper();

        Button action = activity.findViewById(R.id.network_status_action);
        assertEquals(View.VISIBLE, action.getVisibility());
        assertEquals(context.getString(R.string.location_permission_panel_grant_button),
                action.getText().toString());
    }
}
