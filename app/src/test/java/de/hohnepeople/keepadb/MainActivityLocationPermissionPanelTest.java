package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
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
 * Onboarding coverage for issue #459, #504, and #507:
 * Following #507, {@code MainActivity} no longer surfaces any location permission panel or
 * in-context prompt -- the location permission flow has moved into {@link SettingsActivity}
 * under the "Wi-Fi &amp; Access Points" beta opt-in card.
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
    }

    @After
    public void tearDown() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit();
        KeepADBTrustedNetwork.resetVerifiedTrustForTesting();
        KeepADB.resetForTesting();
    }

    @Test
    public void homeScreenNeverSurfacesLocationPermissionPrompt() {
        // Test in both allowlist mode and all-Wi-Fi mode
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();

        assertNull(activity.findViewById(R.id.btn_wifi_aps_grant_location_permission));

        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        controller.pause().resume();
        ShadowLooper.idleMainLooper();

        assertNull(activity.findViewById(R.id.btn_wifi_aps_grant_location_permission));
    }

    @Test
    public void settingsWifiApsCardShowsLocationGrantButtonWhenOptedInAndPermissionMissing() {
        KeepADBPreferences.setWifiApsFeatureEnabled(context, true);

        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();
        activity.findViewById(R.id.settings_wifi_aps_header).performClick();
        ShadowLooper.idleMainLooper();

        Button inContextButton = activity.findViewById(R.id.btn_wifi_aps_grant_location_permission);
        assertNotNull("In-context grant button must be present in wifiApsCurrentRow when opted in", inContextButton);
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

        Button updatedButton = activity.findViewById(R.id.btn_wifi_aps_grant_location_permission);
        assertNotNull(updatedButton);
        assertEquals(context.getString(R.string.location_permission_settings_button),
                updatedButton.getText().toString());

        updatedButton.performClick();
        Intent opened = shadowOf(activity).getNextStartedActivity();
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, opened.getAction());
        assertEquals(Uri.parse("package:" + activity.getPackageName()), opened.getData());
    }

    @Test
    public void settingsWifiApsCardHidesLocationGrantButtonWhenPermissionGranted() {
        KeepADBPreferences.setWifiApsFeatureEnabled(context, true);
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.ACCESS_FINE_LOCATION);

        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();
        activity.findViewById(R.id.settings_wifi_aps_header).performClick();
        ShadowLooper.idleMainLooper();

        assertNull(activity.findViewById(R.id.btn_wifi_aps_grant_location_permission));
    }

    @Test
    public void settingsWifiApsCardHidesLocationGrantButtonWhenOptInIsDisabled() {
        KeepADBPreferences.setWifiApsFeatureEnabled(context, false);

        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();
        activity.findViewById(R.id.settings_wifi_aps_header).performClick();
        ShadowLooper.idleMainLooper();

        assertNull(activity.findViewById(R.id.btn_wifi_aps_grant_location_permission));
    }
}
