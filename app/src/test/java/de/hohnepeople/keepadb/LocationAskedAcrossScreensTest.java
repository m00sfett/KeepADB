package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.location.LocationManager;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

/**
 * #797: "the location permission was asked before" is one fact for the whole app, not one per
 * screen. After the system stopped asking (denied, no rationale left), the first tap on any
 * screen's grant button leads to the app settings instead of being an idle tap on a request the
 * system answers at once.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LocationAskedAcrossScreensTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        KeepADB.resetForTesting();
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));
        shadowOf((Application) context).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION);
        shadowOf(context.getSystemService(LocationManager.class)).setLocationEnabled(true);
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
    }

    @Test
    public void askingInTheAssistantMakesTheSettingsButtonOpenTheAppSettings() {
        OnboardingActivity assistant = Robolectric.buildActivity(OnboardingActivity.class,
                OnboardingActivity.stepIntent(context, KeepADBOnboarding.Step.PERMISSIONS))
                .setup().get();
        button(assistant, R.id.onboarding_page_content,
                context.getString(R.string.location_permission_panel_grant_button)).performClick();
        assertNotNull(shadowOf(assistant).getLastRequestedPermission());

        SettingsActivity settings = openSettings();
        settings.findViewById(R.id.network_status_action).performClick();

        assertNull("no idle request the system answers at once",
                shadowOf(settings).getLastRequestedPermission());
        Intent opened = shadowOf(settings).getNextStartedActivity();
        assertNotNull(opened);
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, opened.getAction());
    }

    @Test
    public void askingInTheSettingsMakesTheAssistantButtonOpenTheAppSettings() {
        SettingsActivity settings = openSettings();
        settings.findViewById(R.id.network_status_action).performClick();
        assertNotNull(shadowOf(settings).getLastRequestedPermission());

        OnboardingActivity assistant = Robolectric.buildActivity(OnboardingActivity.class,
                OnboardingActivity.stepIntent(context, KeepADBOnboarding.Step.PERMISSIONS))
                .setup().get();
        button(assistant, R.id.onboarding_page_content,
                context.getString(R.string.location_permission_settings_button)).performClick();

        Intent opened = shadowOf(assistant).getNextStartedActivity();
        assertNotNull(opened);
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, opened.getAction());
        assertNull(shadowOf(assistant).getLastRequestedPermission());
    }

    /** Control: nothing asked anywhere, the settings button asks. */
    @Test
    public void withoutAnyEarlierAskingTheSettingsButtonAsks() {
        SettingsActivity settings = openSettings();
        settings.findViewById(R.id.network_status_action).performClick();
        assertNotNull(shadowOf(settings).getLastRequestedPermission());
    }

    private SettingsActivity openSettings() {
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        activity.findViewById(R.id.settings_network_beta_header).performClick();
        ShadowLooper.idleMainLooper();
        return activity;
    }

    private static Button button(Activity activity, int containerId, String label) {
        Button found = find(activity.findViewById(containerId), label);
        assertNotNull("no button \"" + label + "\"", found);
        return found;
    }

    private static Button find(View view, String label) {
        if (view instanceof Button && view.getVisibility() == View.VISIBLE
                && label.contentEquals(((Button) view).getText())) {
            return (Button) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Button found = find(group.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
    }
}
