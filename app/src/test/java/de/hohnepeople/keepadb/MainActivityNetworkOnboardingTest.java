package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.Switch;

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
 * Unit and behavioral coverage for issue #619:
 * Network onboarding banner on the main screen educating Keep-Alive users
 * about restricting wireless debugging to trusted Wi-Fi networks.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MainActivityNetworkOnboardingTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    @After
    public void tearDown() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit();
        KeepADB.resetForTesting();
    }

    @Test
    public void panelIsHiddenWhenKeepAliveIsDisabled() {
        KeepADBPreferences.setKeepAliveEnabled(context, false);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);

        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();

        assertEquals(View.GONE,
                activity.findViewById(R.id.network_onboarding_panel).getVisibility());
    }

    @Test
    public void panelIsVisibleWhenKeepAliveEnabledAndAllWifiMode() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);

        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();

        assertEquals(View.VISIBLE,
                activity.findViewById(R.id.network_onboarding_panel).getVisibility());
    }

    @Test
    public void panelIsHiddenWhenModeIsAllowlist() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);

        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();

        assertEquals(View.GONE,
                activity.findViewById(R.id.network_onboarding_panel).getVisibility());
    }

    @Test
    public void panelIsHiddenWhenDismissed() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        KeepADBPreferences.setNetworkOnboardingPanelVisible(context, false);

        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();

        assertEquals(View.GONE,
                activity.findViewById(R.id.network_onboarding_panel).getVisibility());
    }

    @Test
    public void setupButtonClickLaunchesSettingsActivityWithFocusNetworkExtra() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);

        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();

        assertTrue(activity.findViewById(R.id.network_onboarding_setup_button).performClick());

        Intent intent = shadowOf(activity).getNextStartedActivity();
        assertNotNull(intent);
        assertEquals(SettingsActivity.class.getName(), intent.getComponent().getClassName());
        assertTrue(intent.getBooleanExtra(SettingsActivity.EXTRA_FOCUS_NETWORK, false));
    }

    @Test
    public void dismissButtonHidesPanelAndPersistsPreferenceAndSurvivesRestart() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);

        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        assertEquals(View.VISIBLE,
                activity.findViewById(R.id.network_onboarding_panel).getVisibility());

        assertTrue(activity.findViewById(R.id.network_onboarding_dismiss_button).performClick());
        assertEquals(View.GONE,
                activity.findViewById(R.id.network_onboarding_panel).getVisibility());
        assertFalse(KeepADBPreferences.isNetworkOnboardingPanelVisible(activity));
        assertTrue(KeepADBPreferences.isNetworkOnboardingDismissed(activity));

        controller.pause().stop().destroy();

        ActivityController<MainActivity> restarted =
                Robolectric.buildActivity(MainActivity.class).setup();
        assertEquals("Dismiss state must survive restart", View.GONE,
                restarted.get().findViewById(R.id.network_onboarding_panel).getVisibility());
    }

    @Test
    public void togglingKeepAliveUpdatesBannerVisibility() {
        KeepADBPreferences.setKeepAliveEnabled(context, false);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);

        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        assertEquals(View.GONE,
                activity.findViewById(R.id.network_onboarding_panel).getVisibility());

        // Toggle Keep-Alive ON
        Switch keepAliveToggle = activity.findViewById(R.id.keep_alive_toggle);
        assertFalse(keepAliveToggle.isChecked());
        keepAliveToggle.performClick();
        ShadowLooper.idleMainLooper();

        assertEquals(View.VISIBLE,
                activity.findViewById(R.id.network_onboarding_panel).getVisibility());

        // Toggle Keep-Alive OFF
        keepAliveToggle.performClick();
        ShadowLooper.idleMainLooper();

        assertEquals(View.GONE,
                activity.findViewById(R.id.network_onboarding_panel).getVisibility());
    }

    @Test
    public void switchingToAllowlistOnResumeHidesBanner() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);

        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        assertEquals(View.VISIBLE,
                activity.findViewById(R.id.network_onboarding_panel).getVisibility());

        // User switches mode to allowlist (e.g. in SettingsActivity), then returns
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        controller.pause().resume();
        ShadowLooper.idleMainLooper();

        assertEquals(View.GONE,
                activity.findViewById(R.id.network_onboarding_panel).getVisibility());
    }
}
