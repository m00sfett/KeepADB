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
import org.robolectric.shadows.ShadowWifiInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The "Trusted Wi-Fi" step of the setup assistant (#767): the current network and the embedded
 * decision of #766, driven through the real activity. What is pinned, each with its other side:
 * answering stores the answer and "Next" or "Skip" without answering stores nothing; the name is
 * shown while the privacy mode is on (the step is a deliberate exception) and the note says so
 * only then; the states without a readable network offer the fix and a way to check again.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class OnboardingActivityNetworkTest {

    private static final String HOME = "Heimnetz";
    private static final String KITCHEN = "aa:bb:cc:11:22:33";

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private Context context;
    private final List<ActivityController<?>> controllers = new ArrayList<>();
    private boolean wifiConnected = true;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        prefs().edit().clear().commit();
        KeepADB.resetForTesting();
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> wifiConnected);
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
        KeepADBForceMode.resetForTesting();
    }

    // ---- The question and its answers ----------------------------------------------------------------

    @Test
    public void anUnknownNetworkIsAskedThroughTheEmbeddedDecisionAndTrustingIsStored() {
        connectTo(HOME, KITCHEN);
        OnboardingActivity assistant = open();

        NetworkDecisionView decision = findDecision(assistant);
        assertNotNull("the component of #766 is embedded, not a dialog of this step", decision);
        assertTrue(decision.isBound());
        String shown = allText(assistant);
        assertTrue(shown, shown.contains(HOME));
        assertTrue(shown.contains(KITCHEN.toUpperCase(java.util.Locale.ROOT)));
        assertEquals("there is nothing to close, so no \"decide later\"", View.GONE,
                assistant.findViewById(R.id.decision_later).getVisibility());
        assertTrue(KeepADBTrustedNetwork.getEntries(context).isEmpty());

        assistant.findViewById(R.id.decision_trust).performClick();

        assertEquals(1, KeepADBTrustedNetwork.getEntries(context).size());
        assertEquals(KITCHEN, KeepADBTrustedNetwork.getEntries(context).get(0).bssid);
        assertNull("the question is gone", findDecision(assistant));
        assertTrue(allText(assistant), allText(assistant).contains(
                context.getString(R.string.networks_badge_trusted)));
    }

    @Test
    public void blockingTheAccessPointIsStoredAndOffersTheWayToTheList() {
        connectTo(HOME, KITCHEN);
        OnboardingActivity assistant = open();

        assistant.findViewById(R.id.decision_block_ap).performClick();

        assertTrue(KeepADBNetworkBlocklist.isBssidBlocked(context, KITCHEN));
        assertTrue(KeepADBTrustedNetwork.getEntries(context).isEmpty());
        assertTrue(allText(assistant).contains(context.getString(R.string.networks_reason_access_point)));
        button(assistant, context.getString(R.string.onboarding_network_open_list)).performClick();
        assertEquals(NetworkListActivity.class.getName(),
                shadowOf(assistant).getNextStartedActivity().getComponent().getClassName());
    }

    @Test
    public void aTrustedNetworkShowsNoQuestionAndNoTapToChange() {
        connectTo(HOME, KITCHEN);
        KeepADBTrustedNetwork.addBssid(context, KITCHEN, HOME);
        OnboardingActivity assistant = open();

        assertNull(findDecision(assistant));
        String shown = allText(assistant);
        assertTrue(shown, shown.contains(context.getString(R.string.networks_badge_trusted)));
        assertTrue(shown.contains(context.getString(R.string.networks_current_trusted)));
        assertFalse("the assistant answers a question, it does not edit the list",
                shown.contains(context.getString(R.string.networks_tap_to_change)));
        assertNull(findButton(assistant, context.getString(R.string.onboarding_network_open_list)));
    }

    @Test
    public void nextAndSkipWithoutAnsweringStoreNothingOnANewAndAnExistingInstall() {
        connectTo(HOME, KITCHEN);
        // New installation: the whole walk equals "Later" on the intro.
        Map<String, String> later = walk(false);
        prefs().edit().clear().commit();
        KeepADB.resetForTesting();
        Map<String, String> walkedNext = walk(true);
        assertEquals(later, walkedNext);
        assertTrue(KeepADBTrustedNetwork.getEntries(context).isEmpty());
        assertFalse(KeepADBNetworkBlocklist.isBssidBlocked(context, KITCHEN));

        // Existing installation: nothing a step could write moves.
        prefs().edit().clear().commit();
        KeepADB.resetForTesting();
        KeepADBTrustedNetwork.addBssid(context, "11:22:33:44:55:66", "Garten");
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBPreferences.setNotificationDetailsEnabled(context, true);
        Map<String, String> before = snapshotWithoutAssistantKeys();
        OnboardingActivity assistant = open(OnboardingActivity.autoStartIntent(context));
        for (int i = 0; i < OnboardingActivity.buildSteps().size() + 2; i++) {
            click(assistant, R.id.onboarding_next);
        }
        assertEquals(before, snapshotWithoutAssistantKeys());
        assertTrue("the intro records its one-time notice separately from network settings",
                prefs().getBoolean(KeepADBPreferences.KEY_UPGRADE_NOTICE_SHOWN, false));
    }

    // ---- Privacy mode: the name is shown here on purpose -------------------------------------------------

    @Test
    public void thePrivacyModeStillShowsTheNameHereAndSaysSoOnlyThen() {
        connectTo(HOME, KITCHEN);
        String note = context.getString(R.string.onboarding_network_privacy_note);

        OnboardingActivity visible = open();
        assertFalse(allText(visible).contains(note));

        KeepADBPreferences.setPrivacyModeEnabled(context, true);
        OnboardingActivity hidden = open();
        String shown = allText(hidden);
        assertTrue("the name is shown although the privacy mode is on", shown.contains(HOME));
        assertTrue(shown.contains(note));
    }

    /** The other side of the exception: a network that needs no question still shows its name. */
    @Test
    public void thePrivacyModeShowsTheNameOfATrustedNetworkToo() {
        connectTo(HOME, KITCHEN);
        KeepADBTrustedNetwork.addBssid(context, KITCHEN, HOME);
        KeepADBPreferences.setPrivacyModeEnabled(context, true);

        OnboardingActivity assistant = open();

        assertNull("a trusted network is not asked about", findDecision(assistant));
        assertTrue(allText(assistant), allText(assistant).contains(HOME));
    }

    @Test
    public void whileTheForceModeIsOnTheStepSaysTheAnswerAppliesAfterwards() {
        connectTo(HOME, KITCHEN);
        String note = context.getString(R.string.networks_force_note);
        assertFalse(allText(open()).contains(note));

        KeepADBForceMode.setClockForTesting(new KeepADBForceTestSupport.TestClock());
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.DEFAULT, true));
        OnboardingActivity assistant = open();
        assertTrue(allText(assistant).contains(note));
        assertNotNull("the question is still asked", findDecision(assistant));
    }

    // ---- States without a readable network -----------------------------------------------------------------

    @Test
    public void withoutWifiTheStepSaysSoOffersTheSettingsAndChecksAgain() {
        wifiConnected = false;
        connectNothing();
        OnboardingActivity assistant = open();
        assertTrue(allText(assistant).contains(context.getString(R.string.network_status_no_wifi)));
        assertNull(findDecision(assistant));

        button(assistant, context.getString(R.string.network_action_wifi_settings)).performClick();
        assertEquals(Settings.ACTION_WIFI_SETTINGS,
                shadowOf(assistant).getNextStartedActivity().getAction());

        wifiConnected = true;
        connectTo(HOME, KITCHEN);
        button(assistant, context.getString(R.string.onboarding_recheck)).performClick();
        assertNotNull("after connecting, the question shows", findDecision(assistant));
    }

    @Test
    public void anUnreadableNetworkWithoutLocationPermissionAsksForItAndReadsBackTheResult() {
        shadowOf((Application) context).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
        connectUnreadable();
        OnboardingActivity assistant = open();
        assertTrue(allText(assistant).contains(context.getString(R.string.network_status_unreadable)));
        assertTrue(allText(assistant).contains(
                context.getString(R.string.network_cause_permission_missing)));

        button(assistant, context.getString(R.string.location_permission_panel_grant_button))
                .performClick();
        assertEquals(Manifest.permission.ACCESS_FINE_LOCATION,
                shadowOf(assistant).getLastRequestedPermission().requestedPermissions[0]);
        assertEquals(NetworkListRenderer.REQUEST_LOCATION,
                shadowOf(assistant).getLastRequestedPermission().requestCode);

        // The user allowed it and the network can be read now: the callback redraws the step.
        shadowOf((Application) context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
        connectTo(HOME, KITCHEN);
        assistant.onRequestPermissionsResult(NetworkListRenderer.REQUEST_LOCATION,
                new String[0], new int[0]);
        assertNotNull(findDecision(assistant));
        assertFalse(allText(assistant).contains(
                context.getString(R.string.network_cause_permission_missing)));
    }

    @Test
    public void aMaskedNetworkDespiteAllPermissionsLeadsToTheBackgroundGrant() {
        shadowOf((android.location.LocationManager)
                context.getSystemService(Context.LOCATION_SERVICE)).setLocationEnabled(true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        connectUnreadable();
        OnboardingActivity assistant = open();

        button(assistant, context.getString(R.string.network_background_setup_button)).performClick();
        Intent opened = shadowOf(assistant).getNextStartedActivity();
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, opened.getAction());
        assertEquals("package:" + context.getPackageName(), opened.getData().toString());
        assertNotNull(findButton(assistant, context.getString(R.string.onboarding_recheck)));
    }

    // ---- Large font, narrow display -------------------------------------------------------------------------

    @Test
    @Config(sdk = 34, qualifiers = "w320dp-h640dp")
    public void everyStateOfTheStepFitsAt200PercentFontOn320Dp() {
        org.robolectric.RuntimeEnvironment.setFontScale(2.0f);
        // Undecided (the embedded decision), trusted, blocked, not readable, no Wi-Fi.
        connectTo("Ein sehr langer Name eines WLAN im Treppenhaus", KITCHEN);
        fits(open(), "undecided");
        KeepADBTrustedNetwork.addBssid(context, KITCHEN, HOME);
        fits(open(), "trusted");
        KeepADBNetworkBlocklist.blockBssid(context, KITCHEN);
        fits(open(), "blocked");
        shadowOf((Application) context).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
        connectUnreadable();
        fits(open(), "unreadable");
        wifiConnected = false;
        connectNothing();
        fits(open(), "no Wi-Fi");
    }

    private static void fits(OnboardingActivity assistant, String where) {
        OnboardingLayoutAssertions.assertFits(assistant, 320, where);
    }

    // ---- Summary -----------------------------------------------------------------------------------------

    @Test
    public void theSummaryCountsTheTrustedAndBlockedAccessPoints() {
        connectTo(HOME, KITCHEN);
        KeepADBTrustedNetwork.addBssid(context, KITCHEN, HOME);
        OnboardingActivity assistant = open(OnboardingActivity.fullIntent(context));
        for (int i = 0; i < OnboardingActivity.buildSteps().size() + 1; i++) {
            click(assistant, R.id.onboarding_next);
        }
        assertTrue(allText(assistant), allText(assistant).contains(
                context.getString(R.string.networks_count, 1, 0)));
    }

    // ---- Helpers ---------------------------------------------------------------------------------------------

    private Map<String, String> walk(boolean next) {
        OnboardingActivity assistant = open(OnboardingActivity.autoStartIntent(context));
        if (!next) {
            click(assistant, R.id.onboarding_secondary); // Later
        } else {
            click(assistant, R.id.onboarding_next);
            for (int i = 0; i < OnboardingActivity.buildSteps().size() + 1; i++) {
                click(assistant, R.id.onboarding_next);
            }
        }
        assertTrue(assistant.isFinishing());
        KeepADBTrustedNetwork.getMode(context);
        return snapshotWithoutAssistantKeys();
    }

    private OnboardingActivity open() {
        return open(OnboardingActivity.stepIntent(context, KeepADBOnboarding.Step.NETWORK));
    }

    private OnboardingActivity open(Intent intent) {
        ActivityController<OnboardingActivity> controller =
                Robolectric.buildActivity(OnboardingActivity.class, intent).setup();
        controllers.add(controller);
        return controller.get();
    }

    private void connectTo(String ssid, String bssid) {
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = ShadowWifiInfo.newInstance();
        shadowOf(info).setSSID(ssid);
        shadowOf(info).setBSSID(bssid);
        shadowOf(wifiManager).setConnectionInfo(info);
    }

    private void connectUnreadable() {
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = ShadowWifiInfo.newInstance();
        shadowOf(info).setSSID(WifiManager.UNKNOWN_SSID);
        shadowOf(info).setBSSID(KeepADBNetworkIdentity.REDACTED_BSSID);
        shadowOf(wifiManager).setConnectionInfo(info);
    }

    private void connectNothing() {
        shadowOf((WifiManager) context.getSystemService(Context.WIFI_SERVICE))
                .setConnectionInfo(null);
    }

    private void click(android.app.Activity activity, int id) {
        View view = activity.findViewById(id);
        assertEquals("button " + id + " must be visible", View.VISIBLE, view.getVisibility());
        view.performClick();
    }

    private NetworkDecisionView findDecision(android.app.Activity activity) {
        for (View view : allViews(activity.findViewById(R.id.onboarding_page_content))) {
            if (view instanceof NetworkDecisionView && ((NetworkDecisionView) view).isBound()) {
                return (NetworkDecisionView) view;
            }
        }
        return null;
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

    private String allText(android.app.Activity activity) {
        StringBuilder out = new StringBuilder();
        for (View view : allViews(activity.findViewById(R.id.onboarding_page))) {
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

    private SharedPreferences prefs() {
        return context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE);
    }

    private Map<String, String> snapshotWithoutAssistantKeys() {
        Map<String, String> result = new TreeMap<>();
        for (Map.Entry<String, ?> entry : prefs().getAll().entrySet()) {
            if (KeepADBPreferences.KEY_ONBOARDING_COMPLETED_VERSION.equals(entry.getKey())
                    || KeepADBPreferences.KEY_ONBOARDING_EXISTING_INSTALL.equals(entry.getKey())
                    || KeepADBPreferences.KEY_UPGRADE_NOTICE_SHOWN.equals(entry.getKey())) {
                continue;
            }
            Object value = entry.getValue();
            result.put(entry.getKey(), value instanceof java.util.Set
                    ? new java.util.TreeSet<>((java.util.Set<?>) value).toString()
                    : String.valueOf(value));
        }
        return result;
    }
}
