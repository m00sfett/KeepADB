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
import org.robolectric.shadows.ShadowToast;

/**
 * Onboarding coverage for issue #459 and #504: {@code MainActivity} surfaces -- and lets the user
 * resolve -- a missing {@code ACCESS_FINE_LOCATION} grant both in allowlist mode (#459) and in
 * all-Wi-Fi mode (#504) for Wi-Fi and access point discovery, both via the header panel and
 * in-context on the Wi-Fi & Access Points card.
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
        // #492: allowlist mode is no longer the default -- it is an opt-in taken in Settings.
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
    public void panelIsVisibleWhenAllowlistModeIsOnAndPermissionIsMissing() {
        assertTrue(KeepADBTrustedNetwork.isAllowlistMode(context));

        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();

        assertEquals(View.VISIBLE,
                activity.findViewById(R.id.location_permission_panel).getVisibility());
        assertEquals(context.getString(R.string.location_permission_panel_title),
                ((TextView) activity.findViewById(R.id.location_permission_title)).getText().toString());
        assertEquals(context.getString(R.string.location_permission_panel_body),
                ((TextView) activity.findViewById(R.id.location_permission_body)).getText().toString());
        assertEquals(context.getString(R.string.location_permission_panel_grant_button),
                ((Button) activity.findViewById(R.id.btn_grant_location_permission)).getText().toString());

        // Acceptance criterion 3: the fallback is only offered after a request was actually made,
        // not on first sight of the panel.
        assertEquals(View.GONE,
                activity.findViewById(R.id.location_permission_fallback_body).getVisibility());
        assertEquals(View.GONE,
                activity.findViewById(R.id.btn_trust_all_networks).getVisibility());
    }

    @Test
    public void panelIsVisibleInAllWifiModeWhenPermissionIsMissing() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);

        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();

        // #504: location panel is decoupled from allowlist mode -- discovery needs it too.
        assertEquals(View.VISIBLE,
                activity.findViewById(R.id.location_permission_panel).getVisibility());
        assertEquals(context.getString(R.string.location_permission_panel_title_discovery),
                ((TextView) activity.findViewById(R.id.location_permission_title)).getText().toString());
        assertEquals(context.getString(R.string.location_permission_panel_body_discovery),
                ((TextView) activity.findViewById(R.id.location_permission_body)).getText().toString());
        assertEquals(context.getString(R.string.location_permission_panel_grant_button),
                ((Button) activity.findViewById(R.id.btn_grant_location_permission)).getText().toString());

        // In all-Wi-Fi mode, fallback to "trust all Wi-Fi" is irrelevant and stays hidden.
        assertEquals(View.GONE,
                activity.findViewById(R.id.location_permission_fallback_body).getVisibility());
        assertEquals(View.GONE,
                activity.findViewById(R.id.btn_trust_all_networks).getVisibility());
    }

    @Test
    public void panelHidesOnceLocationPermissionIsGranted() {
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.ACCESS_FINE_LOCATION);

        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();

        assertEquals(View.GONE,
                activity.findViewById(R.id.location_permission_panel).getVisibility());
    }

    @Test
    public void grantButtonRequestsLocationPermission() {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();

        assertTrue(activity.findViewById(R.id.btn_grant_location_permission).performClick());

        org.robolectric.shadows.ShadowActivity.PermissionsRequest request =
                shadowOf(activity).getLastRequestedPermission();
        assertEquals(android.Manifest.permission.ACCESS_FINE_LOCATION, request.requestedPermissions[0]);
    }

    @Test
    public void deniedPermissionRevealsTheTrustAllWifiFallbackInAllowlistMode() {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();

        activity.findViewById(R.id.btn_grant_location_permission).performClick();
        activity.onRequestPermissionsResult(30,
                new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION},
                new int[]{PackageManager.PERMISSION_DENIED, PackageManager.PERMISSION_DENIED});

        assertEquals("Denied permission must keep the trusted-network mode intact and still block "
                        + "auto re-enable", View.VISIBLE,
                activity.findViewById(R.id.location_permission_panel).getVisibility());
        assertEquals(View.VISIBLE,
                activity.findViewById(R.id.location_permission_fallback_body).getVisibility());
        assertEquals(View.VISIBLE,
                activity.findViewById(R.id.btn_trust_all_networks).getVisibility());
        assertEquals(context.getString(R.string.location_permission_settings_button),
                ((Button) activity.findViewById(R.id.btn_grant_location_permission)).getText().toString());
    }

    @Test
    public void afterADenialTheButtonSwitchesToOpeningAppSettings() {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();

        activity.findViewById(R.id.btn_grant_location_permission).performClick();
        activity.onRequestPermissionsResult(30,
                new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION},
                new int[]{PackageManager.PERMISSION_DENIED, PackageManager.PERMISSION_DENIED});

        assertEquals(context.getString(R.string.location_permission_settings_button),
                ((Button) activity.findViewById(R.id.btn_grant_location_permission)).getText().toString());

        activity.findViewById(R.id.btn_grant_location_permission).performClick();
        Intent opened = shadowOf(activity).getNextStartedActivity();
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, opened.getAction());
        assertEquals(Uri.parse("package:" + activity.getPackageName()), opened.getData());
    }

    @Test
    public void grantedPermissionResultHidesThePanelWithoutNeedingTheFallback() {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();

        activity.findViewById(R.id.btn_grant_location_permission).performClick();
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.ACCESS_FINE_LOCATION);
        activity.onRequestPermissionsResult(30,
                new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION},
                new int[]{PackageManager.PERMISSION_GRANTED, PackageManager.PERMISSION_GRANTED});

        assertEquals(View.GONE,
                activity.findViewById(R.id.location_permission_panel).getVisibility());
    }

    @Test
    public void trustAllNetworksButtonSwitchesModeAndUpdatesPanel() {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();

        // Simulate a prior request to reveal fallback
        activity.findViewById(R.id.btn_grant_location_permission).performClick();
        activity.onRequestPermissionsResult(30,
                new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION},
                new int[]{PackageManager.PERMISSION_DENIED, PackageManager.PERMISSION_DENIED});

        activity.findViewById(R.id.btn_trust_all_networks).performClick();

        assertEquals(KeepADBTrustedNetwork.MODE_ALL_WIFI, KeepADBTrustedNetwork.getMode(context));
        assertEquals(View.VISIBLE,
                activity.findViewById(R.id.location_permission_panel).getVisibility());
        assertEquals(context.getString(R.string.location_permission_panel_title_discovery),
                ((TextView) activity.findViewById(R.id.location_permission_title)).getText().toString());
        assertEquals(View.GONE,
                activity.findViewById(R.id.location_permission_fallback_body).getVisibility());
        assertEquals(View.GONE,
                activity.findViewById(R.id.btn_trust_all_networks).getVisibility());
        assertEquals(context.getString(R.string.location_permission_panel_fallback_toast),
                ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void wifiApsCardShowsLocationGrantButtonWhenPermissionMissing() {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();

        Button inContextButton = activity.findViewById(R.id.btn_wifi_aps_grant_location_permission);
        assertNotNull("In-context grant button must be present in wifiApsCurrentRow", inContextButton);
        assertEquals(context.getString(R.string.location_permission_panel_grant_button),
                inContextButton.getText().toString());

        assertTrue(inContextButton.performClick());

        org.robolectric.shadows.ShadowActivity.PermissionsRequest request =
                shadowOf(activity).getLastRequestedPermission();
        assertEquals(android.Manifest.permission.ACCESS_FINE_LOCATION, request.requestedPermissions[0]);

        // After denial, in-context button switches to settings fallback
        activity.onRequestPermissionsResult(30,
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
    public void wifiApsCardHidesLocationGrantButtonWhenPermissionGranted() {
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.ACCESS_FINE_LOCATION);

        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();

        assertNull(activity.findViewById(R.id.btn_wifi_aps_grant_location_permission));
    }
}
