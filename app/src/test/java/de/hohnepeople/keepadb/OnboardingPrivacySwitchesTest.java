package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Switch;
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
import org.robolectric.shadows.ShadowWifiInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * #904: the three privacy switches sit on the intro page of the setup assistant, below the intro
 * text and before any step shows data, in both variants of the intro; they write at once, the rest
 * of the assistant respects them, and no extra step exists.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class OnboardingPrivacySwitchesTest {

    private static final String HOME = "Heimnetz";
    private static final String KITCHEN = "aa:bb:cc:11:22:33";

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private Context context;
    private final List<ActivityController<?>> controllers = new ArrayList<>();

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        prefs().edit().clear().commit();
        KeepADBPreferences.migratePrivacySplit(context);
        KeepADB.resetForTesting();
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        KeepADBOnboarding.setAutoStartEnabledForTesting(true);
        shadowOf((Application) context).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS,
                Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.ACCESS_FINE_LOCATION);
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
    }

    // ---- Where they are ----------------------------------------------------------------------------

    @Test
    public void theNewInstallIntroShowsThreeSwitchesAllOffBelowTheIntroText() {
        OnboardingActivity assistant = open(OnboardingActivity.autoStartIntent(context));

        assertEquals(context.getString(R.string.onboarding_intro_title_new,
                OnboardingActivity.buildSteps().size()), text(assistant, R.id.onboarding_page_title));
        assertEquals(View.VISIBLE, assistant.findViewById(R.id.privacy_switches).getVisibility());
        assertFalse(app(assistant).isChecked());
        assertFalse(notifications(assistant).isChecked());
        assertFalse(widgets(assistant).isChecked());
        assertEquals(context.getString(R.string.privacy_app_title), app(assistant).getText().toString());
        assertEquals(context.getString(R.string.privacy_notifications_title),
                notifications(assistant).getText().toString());
        assertEquals(context.getString(R.string.privacy_widgets_title),
                widgets(assistant).getText().toString());
        // Inside the page content, which comes after the intro text view.
        ViewGroup content = assistant.findViewById(R.id.onboarding_page_content);
        assertTrue(isDescendantOf(assistant.findViewById(R.id.privacy_switches), content));
        assertEquals(View.VISIBLE, assistant.findViewById(R.id.onboarding_page_question).getVisibility());
    }

    @Test
    public void theIntroForAnExistingInstallationShowsThemWithTheirDerivedState() {
        // An update from before #904: no details key (they were hidden), the old privacy mode on.
        prefs().edit().clear().putBoolean("keep_alive_enabled", true)
                .putBoolean("privacy_mode_enabled", true).commit();
        KeepADBPreferences.migratePrivacySplit(context);

        OnboardingActivity assistant = open(OnboardingActivity.autoStartIntent(context));

        assertEquals(context.getString(R.string.onboarding_intro_title_existing),
                text(assistant, R.id.onboarding_page_title));
        assertTrue("old privacy mode is the app switch", app(assistant).isChecked());
        assertTrue("details stay hidden", notifications(assistant).isChecked());
        assertTrue("widgets keep following the old mode", widgets(assistant).isChecked());
    }

    @Test
    public void theSwitchesAreOnTheIntroPageOnly() {
        OnboardingActivity assistant = open(OnboardingActivity.autoStartIntent(context));
        assertNotNull(assistant.findViewById(R.id.privacy_switches));
        assistant.findViewById(R.id.onboarding_next).performClick(); // Keep-Alive
        assertNull("no step repeats them", assistant.findViewById(R.id.privacy_switches));

        OnboardingActivity single = open(OnboardingActivity.stepIntent(context,
                KeepADBOnboarding.Step.PROTECTION));
        assertNull(single.findViewById(R.id.privacy_switches));
    }

    @Test
    public void thereIsNoLockScreenStepAnyMoreAndNoExtraPrivacyStep() {
        List<String> ids = new ArrayList<>();
        for (OnboardingStep step : OnboardingActivity.buildSteps()) ids.add(step.id.id);
        assertEquals(java.util.Arrays.asList("keep_alive", "protection", "permissions", "network",
                "webhook"), ids);
        assertNull("an old deep link to the retired step", KeepADBOnboarding.Step.fromId("details"));
    }

    @Test
    public void theIntroShowsNoNetworkDataWhateverTheSwitchesSay() {
        connectTo(HOME, KITCHEN);

        OnboardingActivity assistant = open(OnboardingActivity.autoStartIntent(context));

        String all = allText(assistant);
        assertFalse(all, all.contains(HOME));
        assertFalse(all, all.toLowerCase(java.util.Locale.ROOT).contains(KITCHEN));
    }

    // ---- They write at once, each its own key ----------------------------------------------------------

    @Test
    public void eachSwitchWritesItsOwnPreferenceAtOnceAndNoOther() {
        OnboardingActivity assistant = open(OnboardingActivity.autoStartIntent(context));

        app(assistant).performClick();
        assertTrue(KeepADBPreferences.isAppPrivacyEnabled(context));
        assertFalse(KeepADBPreferences.isNotificationPrivacyEnabled(context));
        assertFalse(KeepADBPreferences.isWidgetPrivacyEnabled(context));

        notifications(assistant).performClick();
        assertTrue(KeepADBPreferences.isNotificationPrivacyEnabled(context));
        assertFalse(KeepADBPreferences.isWidgetPrivacyEnabled(context));

        widgets(assistant).performClick();
        assertTrue(KeepADBPreferences.isWidgetPrivacyEnabled(context));

        app(assistant).performClick();
        assertFalse("a switch turns off again", KeepADBPreferences.isAppPrivacyEnabled(context));
        assertTrue(KeepADBPreferences.isNotificationPrivacyEnabled(context));
    }

    @Test
    public void laterAndNextAfterTheSwitchesWriteNothingOnTop() {
        OnboardingActivity assistant = open(OnboardingActivity.autoStartIntent(context));
        notifications(assistant).performClick();
        Map<String, String> afterSwitch = snapshot();

        assistant.findViewById(R.id.onboarding_secondary).performClick(); // Later

        Map<String, String> expected = new TreeMap<>(afterSwitch);
        expected.put(KeepADBPreferences.KEY_ONBOARDING_COMPLETED_VERSION,
                String.valueOf(KeepADBOnboarding.CURRENT_VERSION));
        assertEquals(expected, snapshot());
    }

    @Test
    public void walkingThroughWithoutTouchingTheSwitchesLeavesThemOff() {
        OnboardingActivity assistant = open(OnboardingActivity.autoStartIntent(context));
        for (int i = 0; i < OnboardingActivity.buildSteps().size() + 2; i++) {
            assistant.findViewById(R.id.onboarding_next).performClick();
        }
        assertFalse(KeepADBPreferences.isAppPrivacyEnabled(context));
        assertFalse(KeepADBPreferences.isNotificationPrivacyEnabled(context));
        assertFalse(KeepADBPreferences.isWidgetPrivacyEnabled(context));
        assertFalse(prefs().contains("notification_privacy_enabled"));
        assertFalse(prefs().contains("widget_privacy_enabled"));
    }

    // ---- The rest of the assistant respects them ----------------------------------------------------------

    @Test
    public void theNetworkStepIsMaskedWhenTheAppSwitchWasTurnedOnOnTheIntro() {
        connectTo(HOME, KITCHEN);
        OnboardingActivity assistant = open(OnboardingActivity.autoStartIntent(context));
        app(assistant).performClick();

        // Intro -> Keep-Alive -> protection -> permissions -> network.
        for (int i = 0; i < 4; i++) assistant.findViewById(R.id.onboarding_next).performClick();
        assertEquals(context.getString(R.string.onboarding_network_title),
                text(assistant, R.id.onboarding_page_title));

        String masked = allText(assistant);
        assertFalse("name masked: " + masked, masked.contains(HOME));
        assertFalse("address masked: " + masked,
                masked.toLowerCase(java.util.Locale.ROOT).contains(KITCHEN));
        assertTrue(masked.contains(context.getString(R.string.onboarding_network_privacy_note)));
    }

    @Test
    public void theNetworkStepShowsTheNetworkWhenTheAppSwitchStayedOff() {
        connectTo(HOME, KITCHEN);
        OnboardingActivity assistant = open(OnboardingActivity.autoStartIntent(context));

        for (int i = 0; i < 4; i++) assistant.findViewById(R.id.onboarding_next).performClick();

        String shown = allText(assistant);
        assertTrue(shown, shown.contains(HOME));
        assertFalse(shown.contains(context.getString(R.string.onboarding_network_privacy_note)));
    }

    // ---- Helpers ---------------------------------------------------------------------------------------

    private OnboardingActivity open(Intent intent) {
        ActivityController<OnboardingActivity> controller =
                Robolectric.buildActivity(OnboardingActivity.class, intent).setup();
        controllers.add(controller);
        return controller.get();
    }

    private static Switch app(android.app.Activity activity) {
        return activity.findViewById(R.id.privacy_switch_app);
    }

    private static Switch notifications(android.app.Activity activity) {
        return activity.findViewById(R.id.privacy_switch_notifications);
    }

    private static Switch widgets(android.app.Activity activity) {
        return activity.findViewById(R.id.privacy_switch_widgets);
    }

    private static String text(android.app.Activity activity, int id) {
        return ((TextView) activity.findViewById(id)).getText().toString();
    }

    private static boolean isDescendantOf(View view, View ancestor) {
        android.view.ViewParent parent = view.getParent();
        while (parent != null) {
            if (parent == ancestor) return true;
            if (!(parent instanceof View)) return false;
            parent = ((View) parent).getParent();
        }
        return false;
    }

    private static String allText(android.app.Activity activity) {
        StringBuilder out = new StringBuilder();
        appendText(activity.findViewById(R.id.onboarding_page), out);
        return out.toString();
    }

    private static void appendText(View view, StringBuilder out) {
        if (view.getVisibility() == View.GONE) return;
        if (view instanceof TextView) out.append(((TextView) view).getText()).append('\n');
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) appendText(group.getChildAt(i), out);
        }
    }

    private Map<String, String> snapshot() {
        Map<String, String> result = new TreeMap<>();
        for (Map.Entry<String, ?> entry : prefs().getAll().entrySet()) {
            result.put(entry.getKey(), String.valueOf(entry.getValue()));
        }
        return result;
    }

    private void connectTo(String ssid, String bssid) {
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = ShadowWifiInfo.newInstance();
        shadowOf(info).setSSID(ssid);
        shadowOf(info).setBSSID(bssid);
        shadowOf(wifiManager).setConnectionInfo(info);
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE);
    }
}
