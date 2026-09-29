package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.view.View;
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

/**
 * #616: optional ACCESS_BACKGROUND_LOCATION setup card on the main screen and the always-available
 * status/setup entry in the trusted-network settings.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MainActivityBackgroundLocationPanelTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        shadowOf((Application) context).denyPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
    }

    @After
    public void tearDown() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADB.resetForTesting();
    }

    private static int panelVisibility(ActivityController<MainActivity> controller) {
        return controller.get().findViewById(R.id.background_location_panel).getVisibility();
    }

    @Test
    public void cardShownWhenAllowlistModeOnAndGrantMissing() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        assertEquals(View.VISIBLE, panelVisibility(controller));
    }

    @Test
    public void cardHiddenInAllWifiModeBecauseNoIdentityIsNeeded() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        assertEquals(View.GONE, panelVisibility(controller));
    }

    @Test
    public void cardHiddenWhenGranted() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        shadowOf((Application) context).grantPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        assertEquals(View.GONE, panelVisibility(controller));
    }

    @Test
    public void setupButtonOpensThisAppsDetailsSettingsWithoutGranting() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        controller.get().findViewById(R.id.btn_background_location_setup).performClick();

        Intent intent = shadowOf(controller.get()).getNextStartedActivity();
        assertNotNull(intent);
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.getAction());
        assertEquals("package:" + context.getPackageName(), intent.getData().toString());
        assertFalse(KeepADBBackgroundLocation.isGranted(context));
        // No runtime permission dialog is ever requested for the background grant.
        assertEquals(null, shadowOf(controller.get()).getLastRequestedPermission());
    }

    @Test
    public void dismissHidesCardWithoutTouchingPermissionOrTrustSettings() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBTrustedNetwork.setSsidMatchingEnabled(context, true);
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();

        controller.get().findViewById(R.id.btn_dismiss_background_location_panel).performClick();

        assertEquals(View.GONE, panelVisibility(controller));
        assertEquals(KeepADBTrustedNetwork.MODE_ALLOWLIST, KeepADBTrustedNetwork.getMode(context));
        assertTrue(KeepADBTrustedNetwork.isSsidMatchingEnabled(context));
        assertFalse(KeepADBBackgroundLocation.isGranted(context));

        controller.pause().resume();
        assertEquals(View.GONE, panelVisibility(controller));
    }

    @Test
    public void dismissSurvivesActivityRestartAndProcessRecreation() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        ActivityController<MainActivity> first = Robolectric.buildActivity(MainActivity.class).setup();
        first.get().findViewById(R.id.btn_dismiss_background_location_panel).performClick();
        first.pause().stop().destroy();

        // A brand-new activity instance reads only the persisted preference.
        ActivityController<MainActivity> second = Robolectric.buildActivity(MainActivity.class).setup();
        assertEquals(View.GONE, panelVisibility(second));
        assertFalse(KeepADBPreferences.isBackgroundLocationPanelVisible(context));
    }

    @Test
    public void grantAfterReturnHidesCardAndLaterRevocationShowsItAgainEvenAfterDismiss() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        controller.get().findViewById(R.id.btn_dismiss_background_location_panel).performClick();

        // User returns from the system page with "Allow all the time" set.
        controller.pause();
        shadowOf((Application) context).grantPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        controller.resume();
        assertEquals(View.GONE, panelVisibility(controller));

        // Later revoked in system settings: the missing state is visible again.
        controller.pause();
        shadowOf((Application) context).denyPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        controller.resume();
        assertEquals(View.VISIBLE, panelVisibility(controller));
    }

    @Test
    public void missingGrantNeverMakesAnUnreadableIdentityTrusted() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBTrustedNetwork.setSsidMatchingEnabled(context, true);
        KeepADBTrustedNetwork.addSsid(context, "Home");
        assertFalse(KeepADBBackgroundLocation.isGranted(context));
        // What a background-started service sees without the grant: masked SSID/BSSID.
        assertFalse(KeepADBTrustedNetwork.isTrustedForTesting(context,
                new KeepADBNetworkIdentity(android.net.wifi.WifiManager.UNKNOWN_SSID,
                        KeepADBNetworkIdentity.REDACTED_BSSID)));
        assertFalse(KeepADBTrustedNetwork.isTrustedForTesting(context,
                new KeepADBNetworkIdentity(null, null)));
    }

    @Test
    public void settingsShowStatusAndSetupEntryIndependentOfDismiss() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBPreferences.setBackgroundLocationPanelVisible(context, false);

        SettingsActivity settings = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        TextView status = settings.findViewById(R.id.settings_background_location_status);
        assertEquals(context.getString(R.string.background_location_status_missing), status.getText().toString());
        settings.findViewById(R.id.settings_background_location_button).performClick();
        // #644: the rationale dialog comes first; the jump is its "Open settings" action.
        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
                .getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        Intent intent = shadowOf(settings).getNextStartedActivity();
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.getAction());

        shadowOf((Application) context).grantPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        settings = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        status = settings.findViewById(R.id.settings_background_location_status);
        assertEquals(context.getString(R.string.background_location_status_granted), status.getText().toString());
    }

    @Test
    public void settingsExplainGrantIsOnlyNeededInTrustedNetworkMode() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        SettingsActivity settings = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        TextView status = settings.findViewById(R.id.settings_background_location_status);
        assertEquals(context.getString(R.string.background_location_status_missing_inactive),
                status.getText().toString());
    }
}
