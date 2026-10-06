package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * #764: the home screen shows the status and only real problems. The setup cards (system
 * permission commands, notifications, battery, trusted-network onboarding, background location,
 * webhook setup) are gone; what is wrong is a warning card (W1 to W5 of the UX concept) that
 * leads into the matching step of the setup assistant. The baseline of every test is "all is
 * fine", so each card is shown by its own condition only, and each test names the opposite state
 * that must hide it again.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MainActivityWarningsTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private static final String BSSID = "aa:bb:cc:dd:ee:ff";
    private static final String[] REMOVED_CARD_IDS = {
            "setup_panel", "setup_command", "setup_command_multi", "setup_refresh",
            "battery_optimization_panel", "btn_open_battery_settings",
            "btn_dismiss_battery_optimization_panel",
            "notification_permission_panel", "btn_open_notification_settings",
            "btn_dismiss_notification_permission_panel",
            "network_onboarding_panel", "network_onboarding_setup_button",
            "network_onboarding_dismiss_button",
            "background_location_panel", "btn_background_location_setup",
            "btn_dismiss_background_location_panel", "webhook_setup_button"
    };

    private final Context context = RuntimeEnvironment.getApplication();
    private WifiManager wifiManager;

    @Before
    public void setUp() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADB.resetForTesting(context);
        KeepADBForceMode.resetForTesting();
        KeepADBForceMode.setClockForTesting(new KeepADBForceTestSupport.TestClock());
        KeepADBPreferences.setAppLanguage(context, "en");
        // All is fine: every permission is there, no network is connected, nothing is marked.
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS,
                android.Manifest.permission.POST_NOTIFICATIONS,
                android.Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        setBatteryExempt(true);
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> false);
        wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
    }

    @After
    public void tearDown() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADBForceMode.resetForTesting();
        KeepADB.resetForTesting();
    }

    private MainActivity open() {
        return Robolectric.buildActivity(MainActivity.class).setup().get();
    }

    private void setBatteryExempt(boolean exempt) {
        shadowOf((PowerManager) context.getSystemService(Context.POWER_SERVICE))
                .setIgnoringBatteryOptimizations(context.getPackageName(), exempt);
    }

    private static boolean shown(MainActivity activity, int cardId) {
        return activity.findViewById(cardId).getVisibility() == View.VISIBLE;
    }

    private static TextView text(MainActivity activity, int cardId) {
        return activity.findViewById(cardId).findViewById(R.id.home_warning_text);
    }

    private static android.widget.Button action(MainActivity activity, int cardId) {
        return activity.findViewById(cardId).findViewById(R.id.home_warning_action);
    }

    private static int visibleCards(MainActivity activity) {
        int count = 0;
        for (int id : new int[] {R.id.warning_system, R.id.force_warning_panel,
                R.id.warning_less_secure, R.id.warning_paused, R.id.warning_limited}) {
            if (shown(activity, id)) count++;
        }
        return count;
    }

    private void connectTo(String bssid) {
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        shadowOf(wifiManager).setConnectionInfo(new WifiInfo.Builder().setBssid(bssid).build());
    }

    private void connectTo(String ssid, String bssid) {
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        shadowOf(wifiManager).setConnectionInfo(
                new WifiInfo.Builder().setSsid(ssid.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .setBssid(bssid).build());
    }

    /** The assistant intent a card leads to: its step, and the item it points at. */
    private static void assertLeadsToStep(MainActivity activity, int cardId,
            KeepADBOnboarding.Step step, String item) {
        action(activity, cardId).performClick();
        Intent intent = shadowOf(activity).getNextStartedActivity();
        assertNotNull("the card must open something", intent);
        assertEquals(OnboardingActivity.class.getName(), intent.getComponent().getClassName());
        assertEquals(step.id, intent.getStringExtra(OnboardingActivity.EXTRA_STEP));
        assertEquals(item, intent.getStringExtra(OnboardingActivity.EXTRA_FOCUS_ITEM));
    }

    // ---- The setup cards are gone ---------------------------------------------------------------

    @Test
    public void noSetupCardExistsOnTheHomeScreenEvenWhenEverythingIsMissing() {
        shadowOf((Application) context).denyPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS,
                android.Manifest.permission.POST_NOTIFICATIONS,
                android.Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        setBatteryExempt(false);
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        MainActivity activity = open();

        for (String name : REMOVED_CARD_IDS) {
            int id = activity.getResources().getIdentifier(name, "id", context.getPackageName());
            assertEquals("the home screen must not carry the view " + name, 0, id);
        }
    }

    @Test
    public void whenAllIsFineThereIsNoWarningAtAll() {
        MainActivity activity = open();

        assertEquals(0, visibleCards(activity));
        // The status stays: the switch, its status line and the Keep-Alive switch.
        assertNotNull(activity.findViewById(R.id.toggle));
        assertEquals(context.getString(R.string.status_off),
                ((TextView) activity.findViewById(R.id.status)).getText().toString());
    }

    // ---- W1: the system permission ---------------------------------------------------------------

    @Test
    public void missingSystemPermissionShowsW1ThatLeadsToTheCommandRow() {
        shadowOf((Application) context).denyPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
        MainActivity activity = open();

        assertTrue(shown(activity, R.id.warning_system));
        assertEquals(context.getString(R.string.home_warning_system_text),
                text(activity, R.id.warning_system).getText().toString());
        assertLeadsToStep(activity, R.id.warning_system, KeepADBOnboarding.Step.PERMISSIONS,
                OnboardingActionSteps.Permissions.ITEM_SYSTEM);

        // The opposite: with the permission back, the card is gone on the next draw.
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
        org.robolectric.android.controller.ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        assertFalse(shown(controller.get(), R.id.warning_system));
    }

    @Test
    public void w1SilencesTheKeepAliveWarningsBecauseTheRestIsSecondary() {
        shadowOf((Application) context).denyPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
        setBatteryExempt(false);
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        connectTo("");
        MainActivity activity = open();

        assertTrue(shown(activity, R.id.warning_system));
        assertFalse(shown(activity, R.id.warning_paused));
        assertFalse(shown(activity, R.id.warning_limited));
    }

    // ---- W2: force -------------------------------------------------------------------------------

    @Test
    public void forceModeShowsW2AndItIsNotCountedAgainAsLessSecure() {
        KeepADBPreferences.setNotificationDetailsEnabled(context, false);
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));
        MainActivity activity = open();

        assertTrue(shown(activity, R.id.force_warning_panel));
        assertFalse("force has its own card, W3 must not repeat it",
                shown(activity, R.id.warning_less_secure));
        assertEquals(1, visibleCards(activity));
    }

    // ---- W3: marked as less secure -----------------------------------------------------------------

    @Test
    public void anExistingInstallWithAMarkedValueShowsW3ThatLeadsToTheValuesStep() {
        KeepADBPreferences.setNotificationDetailsEnabled(context, true);
        MainActivity activity = open();

        assertTrue(shown(activity, R.id.warning_less_secure));
        assertEquals(context.getString(R.string.onboarding_intro_less_secure),
                text(activity, R.id.warning_less_secure).getText().toString());
        assertLeadsToStep(activity, R.id.warning_less_secure, KeepADBOnboarding.Step.DETAILS, null);
    }

    @Test
    public void w3PointsAtTheStepOfTheHeaviestMarkedValueAndCountsAll() {
        KeepADBPreferences.setNotificationDetailsEnabled(context, true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        MainActivity activity = open();

        assertEquals(context.getString(R.string.onboarding_intro_less_secure),
                text(activity, R.id.warning_less_secure).getText().toString());
        assertLeadsToStep(activity, R.id.warning_less_secure,
                KeepADBOnboarding.Step.PROTECTION, null);
    }

    @Test
    public void aNewInstallationNeverGetsW3AndANeutralExistingOneNeitherDoes() {
        // New: the assistant decided "new" while nothing was stored, then a value gets marked.
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        assertFalse(KeepADBOnboarding.isExistingInstall(context));
        KeepADBPreferences.setNotificationDetailsEnabled(context, true);
        assertFalse(shown(open(), R.id.warning_less_secure));

        // Existing, but nothing marked.
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADBPreferences.setAppLanguage(context, "en");
        assertFalse(shown(open(), R.id.warning_less_secure));
    }

    // ---- W4: Keep-Alive paused ------------------------------------------------------------------

    @Test
    public void keepAliveOnWithAnUnreadableNetworkShowsW4ThatLeadsToTheFixOfTheNotification() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        connectTo("");
        MainActivity activity = open();

        assertTrue(shown(activity, R.id.warning_paused));
        // Without the location permission the shared fix is the app's own permission page.
        shadowOf((Application) context).denyPermissions(
                android.Manifest.permission.ACCESS_FINE_LOCATION);
        action(activity, R.id.warning_paused).performClick();
        Intent intent = shadowOf(activity).getNextStartedActivity();
        assertNotNull(intent);
        assertEquals(KeepADBNetworkTrustPrompt.identityUnavailableFixIntent(context).getAction(),
                intent.getAction());
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.getAction());
    }

    @Test
    public void w4StaysAwayWithKeepAliveOffOrWithAReadableNetworkOrWithoutWifi() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        // Keep-Alive off, network unreadable.
        connectTo("");
        assertFalse(shown(open(), R.id.warning_paused));

        // Keep-Alive on, network readable (just not trusted: that is the status line's matter).
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        connectTo(BSSID);
        assertFalse(shown(open(), R.id.warning_paused));

        // Keep-Alive on, no Wi-Fi at all.
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> false);
        assertFalse(shown(open(), R.id.warning_paused));
    }

    // ---- W5: Keep-Alive limited ------------------------------------------------------------------

    @Test
    public void keepAliveOnWithMissingPermissionsShowsW5WithTheCountAndTheFirstMissingRow() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        setBatteryExempt(false);
        MainActivity activity = open();

        assertTrue(shown(activity, R.id.warning_limited));
        assertEquals(context.getString(R.string.home_warning_limited_text),
                text(activity, R.id.warning_limited).getText().toString());
        assertLeadsToStep(activity, R.id.warning_limited, KeepADBOnboarding.Step.PERMISSIONS,
                OnboardingActionSteps.Permissions.ITEM_BATTERY);

        // All three missing: counted together, and the notification row comes first.
        shadowOf((Application) context).denyPermissions(
                android.Manifest.permission.POST_NOTIFICATIONS,
                android.Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        MainActivity all = open();
        assertEquals(context.getString(R.string.home_warning_limited_text),
                text(all, R.id.warning_limited).getText().toString());
        assertLeadsToStep(all, R.id.warning_limited, KeepADBOnboarding.Step.PERMISSIONS,
                OnboardingActionSteps.Permissions.ITEM_NOTIFICATIONS);
    }

    @Test
    public void w5IsGoneOnceTheCauseIsGoneAndNeverShownWithKeepAliveOff() {
        setBatteryExempt(false);
        assertFalse("Keep-Alive off: nothing to be limited",
                shown(open(), R.id.warning_limited));

        KeepADBPreferences.setKeepAliveEnabled(context, true);
        org.robolectric.android.controller.ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        assertTrue(shown(controller.get(), R.id.warning_limited));

        // Back from the system page with the exemption granted: the card is gone, nothing written.
        controller.pause();
        setBatteryExempt(true);
        controller.resume();
        assertFalse(shown(controller.get(), R.id.warning_limited));
    }

    @Test
    public void theBackgroundLocationCountsOnlyWhereTrustIsDecidedAndNotInForce() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        shadowOf((Application) context).denyPermissions(
                android.Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        assertTrue(shown(open(), R.id.warning_limited));

        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        assertFalse("no identity is read in the all-Wi-Fi policy", shown(open(), R.id.warning_limited));

        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));
        assertFalse("force reads no identity either", shown(open(), R.id.warning_limited));
    }

    @Test
    public void w4WinsTheSlotItSharesWithW5() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        setBatteryExempt(false);
        connectTo("");
        MainActivity activity = open();

        assertTrue(shown(activity, R.id.warning_paused));
        assertFalse(shown(activity, R.id.warning_limited));
    }

    // ---- At most three -----------------------------------------------------------------------------

    @Test
    public void withEveryConditionTrueThereAreNeverMoreThanThreeCards() {
        shadowOf((Application) context).denyPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS,
                android.Manifest.permission.POST_NOTIFICATIONS,
                android.Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        setBatteryExempt(false);
        KeepADBPreferences.setNotificationDetailsEnabled(context, true);
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        connectTo("");
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));
        MainActivity activity = open();

        assertEquals(3, visibleCards(activity));
        assertTrue(shown(activity, R.id.warning_system));
        assertTrue(shown(activity, R.id.force_warning_panel));
        assertTrue(shown(activity, R.id.warning_less_secure));
    }

    @Test
    public void withoutW1ForceAndMarkedValuesAndALimitedKeepAliveAreStillThree() {
        setBatteryExempt(false);
        KeepADBPreferences.setNotificationDetailsEnabled(context, true);
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));
        MainActivity activity = open();

        assertEquals(3, visibleCards(activity));
        assertTrue(shown(activity, R.id.warning_limited));
    }

    @Test
    public void noWarningCanBeDismissed() {
        shadowOf((Application) context).denyPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
        MainActivity activity = open();

        int buttons = 0;
        android.view.ViewGroup card = (android.view.ViewGroup) activity.findViewById(R.id.warning_system);
        for (int i = 0; i < ((android.view.ViewGroup) card.getChildAt(1)).getChildCount(); i++) {
            if (((android.view.ViewGroup) card.getChildAt(1)).getChildAt(i) instanceof android.widget.Button) {
                buttons++;
            }
        }
        assertEquals("one action, no dismiss", 1, buttons);
        assertEquals(2, card.getChildCount());
    }

    // ---- The status line -----------------------------------------------------------------------------

    @Test
    public void theUntrustedStatusLineOpensTheDecisionForTheCurrentAccessPoint() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADB.recordExplicitIntent(context, true);
        connectTo(BSSID);
        MainActivity activity = open();
        TextView status = activity.findViewById(R.id.status);

        assertEquals(context.getString(R.string.status_off_keep_alive_blocked_untrusted) + "\n"
                + context.getString(R.string.status_tap_to_decide), status.getText().toString());
        assertTrue(status.isClickable());
        status.performClick();
        Intent intent = shadowOf(activity).getNextStartedActivity();
        assertNotNull(intent);
        assertEquals(NetworkDecisionActivity.class.getName(), intent.getComponent().getClassName());
        assertEquals(BSSID, intent.getStringExtra(KeepADBNetworkTrustPrompt.EXTRA_BSSID));
    }

    /**
     * #790: a blocked access point is no open question. The line says "blocked" instead of "not
     * trusted" and leads to the Networks list, not to a dialog that can only answer "already
     * decided". Control: the test above, where nothing is decided, still opens the dialog.
     */
    @Test
    public void theStatusLineOfABlockedAccessPointSaysBlockedAndOpensTheNetworksList() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADB.recordExplicitIntent(context, true);
        KeepADBNetworkBlocklist.blockBssid(context, BSSID);
        connectTo(BSSID);
        MainActivity activity = open();
        TextView status = activity.findViewById(R.id.status);

        assertEquals(context.getString(R.string.status_off_keep_alive_blocked_by_user) + "\n"
                + context.getString(R.string.status_tap_to_open_list), status.getText().toString());
        assertFalse(status.getText().toString().contains(
                context.getString(R.string.status_off_keep_alive_blocked_untrusted)));
        assertTrue(status.isClickable());
        status.performClick();
        Intent intent = shadowOf(activity).getNextStartedActivity();
        assertNotNull(intent);
        assertEquals(NetworkListActivity.class.getName(), intent.getComponent().getClassName());
    }

    @Test
    public void theStatusLineOfABlockedNameAlsoOpensTheNetworksList() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADB.recordExplicitIntent(context, true);
        KeepADBNetworkBlocklist.blockSsid(context, "Cafe");
        connectTo("Cafe", BSSID);
        MainActivity activity = open();
        TextView status = activity.findViewById(R.id.status);

        assertEquals(context.getString(R.string.status_off_keep_alive_blocked_by_user) + "\n"
                + context.getString(R.string.status_tap_to_open_list), status.getText().toString());
        status.performClick();
        assertEquals(NetworkListActivity.class.getName(),
                shadowOf(activity).getNextStartedActivity().getComponent().getClassName());
    }

    /** The tap reads the decision when it happens: trusted since the line was drawn, no dialog. */
    @Test
    public void aNetworkDecidedWhileTheLineWasShownOpensTheListAndNotTheDialog() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADB.recordExplicitIntent(context, true);
        connectTo(BSSID);
        MainActivity activity = open();
        TextView status = activity.findViewById(R.id.status);
        assertEquals(context.getString(R.string.status_tap_to_decide),
                status.getText().toString().split("\n")[1]);

        KeepADBTrustedNetwork.addBssid(context, BSSID, "Home");
        status.performClick();

        assertEquals(NetworkListActivity.class.getName(),
                shadowOf(activity).getNextStartedActivity().getComponent().getClassName());
    }

    @Test
    public void otherStatusLinesAreNotTappableAndTheEntryIsRemovedWhenTheReasonGoes() {
        // Plain "off": not tappable.
        MainActivity off = open();
        assertFalse(((TextView) off.findViewById(R.id.status)).isClickable());

        // Untrusted and then trusted again on the same screen: the tap target must not linger.
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADB.recordExplicitIntent(context, true);
        connectTo(BSSID);
        org.robolectric.android.controller.ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        TextView status = controller.get().findViewById(R.id.status);
        assertTrue(status.isClickable());
        controller.pause();
        KeepADBTrustedNetwork.addBssid(context, BSSID, "Home");
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> false);
        controller.resume();
        assertFalse(status.isClickable());
        assertNull(status.getBackground());
    }

    // ---- The keys of the removed cards ----------------------------------------------------------------

    @Test
    public void theDismissKeysOfTheRemovedCardsAreDeletedAndOtherKeysStay() {
        SharedPreferences prefs = context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE);
        prefs.edit().clear()
                .putBoolean("notification_permission_panel_visible", false)
                .putBoolean("battery_optimization_panel_visible", false)
                .putBoolean("network_onboarding_panel_visible", false)
                .putBoolean("background_location_panel_visible", false)
                .putBoolean("advice_banner_visible", false)
                .commit();

        open();

        assertFalse(prefs.contains("notification_permission_panel_visible"));
        assertFalse(prefs.contains("battery_optimization_panel_visible"));
        assertFalse(prefs.contains("network_onboarding_panel_visible"));
        assertFalse(prefs.contains("background_location_panel_visible"));
        assertTrue("the advice banner flag is not part of this", prefs.contains("advice_banner_visible"));
    }

    @Test
    public void deletingTheKeysNeverTurnsAnExistingInstallIntoANewOne() {
        // The dismiss flags are the only thing this installation ever stored.
        SharedPreferences prefs = context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE);
        prefs.edit().clear().putBoolean("battery_optimization_panel_visible", false).commit();

        open();

        assertTrue(KeepADBOnboarding.isExistingInstall(context));
    }
}
