package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;
import android.view.View;

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
 * Onboarding coverage for issue #459: {@code MainActivity} must surface -- and let the user
 * resolve -- a missing {@code ACCESS_FINE_LOCATION} grant whenever the trusted-network allowlist
 * mode (#260 default) is active, instead of leaving Keep-Alive stuck in {@code
 * identity_unavailable} forever with no on-screen explanation.
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
    }

    @After
    public void tearDown() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit();
        KeepADBTrustedNetwork.resetVerifiedTrustForTesting();
        KeepADB.resetForTesting();
    }

    @Test
    public void panelIsVisibleByDefaultBecauseAllowlistIsTheDefaultModeAndPermissionIsMissing() {
        // #260: allowlist mode is the default, even without ever touching Settings.
        assertTrue(KeepADBTrustedNetwork.isAllowlistMode(context));

        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();

        assertEquals(View.VISIBLE,
                activity.findViewById(R.id.location_permission_panel).getVisibility());
        // Acceptance criterion 3: the fallback is only offered after a request was actually made,
        // not on first sight of the panel.
        assertEquals(View.GONE,
                activity.findViewById(R.id.location_permission_fallback_body).getVisibility());
        assertEquals(View.GONE,
                activity.findViewById(R.id.btn_trust_all_networks).getVisibility());
    }

    @Test
    public void panelStaysHiddenInAllWifiMode() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);

        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();

        assertEquals(View.GONE,
                activity.findViewById(R.id.location_permission_panel).getVisibility());
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
    public void deniedPermissionRevealsTheTrustAllWifiFallback() {
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
    public void trustAllNetworksButtonSwitchesModeAndHidesThePanel() {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();

        activity.findViewById(R.id.btn_trust_all_networks).performClick();

        assertEquals(KeepADBTrustedNetwork.MODE_ALL_WIFI, KeepADBTrustedNetwork.getMode(context));
        assertEquals(View.GONE,
                activity.findViewById(R.id.location_permission_panel).getVisibility());
        assertEquals(context.getString(R.string.location_permission_panel_fallback_toast),
                ShadowToast.getTextOfLatestToast());
    }
}
