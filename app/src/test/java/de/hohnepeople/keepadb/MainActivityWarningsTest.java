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

    private static void assertLeadsToSettings(MainActivity activity, int cardId, String extra) {
        action(activity, cardId).performClick();
        Intent intent = shadowOf(activity).getNextStartedActivity();
        assertEquals(SettingsActivity.class.getName(), intent.getComponent().getClassName());
        assertTrue(intent.getBooleanExtra(extra, false));
    }

    /** #959: the force row and the protection level are on the Protection page. */
    private static void assertLeadsToProtection(MainActivity activity, int cardId, String extra) {
        action(activity, cardId).performClick();
        Intent intent = shadowOf(activity).getNextStartedActivity();
        assertEquals(ProtectionActivity.class.getName(), intent.getComponent().getClassName());
        assertTrue(intent.getBooleanExtra(extra, false));
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
        assertEquals(context.getString(R.string.home_state_off),
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

    /** A value the concept marks "less secure": the webhook reports over plain http. */
    private void markLessSecure() {
        KeepADBPreferences.setRegisterWebhookUrl(context, "http://example.org/register/x");
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
    }

    @Test
    public void forceModeShowsW2AndItIsNotCountedAgainAsLessSecure() {
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
        markLessSecure();
        MainActivity activity = open();

        assertTrue(shown(activity, R.id.warning_less_secure));
        assertEquals(context.getString(R.string.settings_webhook_cleartext_warning),
                text(activity, R.id.warning_less_secure).getText().toString());
        assertLeadsToSettings(activity, R.id.warning_less_secure, SettingsActivity.EXTRA_FOCUS_WEBHOOK);
    }

    @Test
    public void w3PointsAtTheStepOfTheHeaviestMarkedValueAndCountsAll() {
        markLessSecure();
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        MainActivity activity = open();

        assertEquals(context.getString(R.string.network_mode_option_all_wifi) + "\n"
                        + context.getString(R.string.settings_webhook_cleartext_warning),
                text(activity, R.id.warning_less_secure).getText().toString());
        assertLeadsToProtection(activity, R.id.warning_less_secure, ProtectionActivity.EXTRA_FOCUS_LEVEL);
    }

    @Test
    public void aNewInstallationShowsMarkedValuesAndANeutralOneHasNoWarning() {
        // New: the assistant decided "new" while nothing was stored, then a value gets marked.
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        assertFalse(KeepADBOnboarding.isExistingInstall(context));
        markLessSecure();
        assertTrue(shown(open(), R.id.warning_less_secure));

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
        assertEquals(context.getString(R.string.home_warning_limited_text) + "\n"
                        + context.getString(R.string.onboarding_perm_battery_title),
                text(activity, R.id.warning_limited).getText().toString());
        assertLeadsToStep(activity, R.id.warning_limited, KeepADBOnboarding.Step.PERMISSIONS,
                OnboardingActionSteps.Permissions.ITEM_BATTERY);

        // All three missing: counted together, and the notification row comes first.
        shadowOf((Application) context).denyPermissions(
                android.Manifest.permission.POST_NOTIFICATIONS,
                android.Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        MainActivity all = open();
        assertTrue(text(all, R.id.warning_limited).getText().toString()
                .contains(context.getString(R.string.notification_permission_panel_title)));
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
        markLessSecure();
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
        markLessSecure();
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));
        MainActivity activity = open();

        assertEquals(3, visibleCards(activity));
        assertTrue(shown(activity, R.id.warning_limited));
    }

    @Test
    public void warningHasAnIndependentDismissTarget() {
        shadowOf((Application) context).denyPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
        MainActivity activity = open();

        // #963: the row is icon, title, the one review action and the independent close target.
        android.view.ViewGroup card = (android.view.ViewGroup) activity.findViewById(R.id.warning_system);
        android.view.ViewGroup row = card.findViewById(R.id.warning_row);
        int buttons = 0;
        for (int i = 0; i < row.getChildCount(); i++) {
            if (row.getChildAt(i) instanceof android.widget.Button) buttons++;
        }
        assertEquals("one review action in the card row", 1, buttons);
        assertEquals("icon, title, action, independent close target and the explanation", 5, row.getChildCount());
        assertEquals(R.id.warning_details, row.getChildAt(4).getId());
        assertTrue("TalkBack still reaches the explanation inside the focusable row",
                card.findViewById(R.id.home_warning_text).isFocusable());
        assertTrue(card.findViewById(R.id.home_warning_dismiss).isClickable());
        assertEquals(48, card.findViewById(R.id.home_warning_dismiss).getLayoutParams().width);
    }

    // ---- The status: state, reason, action (#957) ---------------------------------------------------

    private static TextView reason(MainActivity activity) {
        return activity.findViewById(R.id.status_reason);
    }

    private static View reasonRow(MainActivity activity) {
        return activity.findViewById(R.id.status_reason_row);
    }

    private static android.widget.Button action(MainActivity activity) {
        return activity.findViewById(R.id.status_action);
    }

    private static String stateOf(MainActivity activity) {
        return ((TextView) activity.findViewById(R.id.status)).getText().toString();
    }

    /** The plain dialog behind the reason line: the long sentence, one "OK" button. */
    private static android.app.AlertDialog openDetails(MainActivity activity) {
        reasonRow(activity).performClick();
        android.app.AlertDialog dialog =
                org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
        return dialog;
    }

    private static String detailsMessage(android.app.AlertDialog dialog) {
        return ((TextView) dialog.findViewById(android.R.id.message)).getText().toString();
    }

    @Test
    public void theUntrustedStatusIsOffWithAReasonAndAnActionThatOpensTheDecision() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADB.recordExplicitIntent(context, true);
        connectTo(BSSID);
        // The prompt for this access point was raised (and swiped away): the app has its record.
        KeepADBBlockedNetworkHistory.record(context, KeepADBNetworkIdentity.current(context),
                System.currentTimeMillis());
        MainActivity activity = open();

        assertEquals(context.getString(R.string.home_state_off), stateOf(activity));
        assertEquals(context.getString(R.string.home_reason_untrusted), reason(activity).getText().toString());
        assertEquals(context.getString(R.string.networks_recent_decide), action(activity).getText().toString());
        assertEquals(View.VISIBLE, action(activity).getVisibility());
        // No longer a text with a card background that only looks like a field.
        assertFalse(activity.findViewById(R.id.status).isClickable());
        assertNull(activity.findViewById(R.id.status).getBackground());
        action(activity).performClick();
        Intent intent = shadowOf(activity).getNextStartedActivity();
        assertNotNull(intent);
        assertEquals(NetworkDecisionActivity.class.getName(), intent.getComponent().getClassName());
        assertEquals(BSSID, intent.getStringExtra(KeepADBNetworkTrustPrompt.EXTRA_BSSID));
        assertFalse("The decision dialog gets no list focus extra",
                intent.hasExtra(NetworkListActivity.EXTRA_FOCUS_BSSID));
    }

    @Test
    public void theReasonLineOpensTheLongSentenceInAPlainDialog() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADB.recordExplicitIntent(context, true);
        connectTo(BSSID);
        MainActivity activity = open();

        View row = reasonRow(activity);
        assertTrue(row.isClickable());
        assertTrue(row.isFocusable());
        assertTrue("a 48dp tap target", row.getMinimumHeight()
                >= 48 * context.getResources().getDisplayMetrics().density - 1);
        assertEquals(View.VISIBLE, activity.findViewById(R.id.status_reason_info).getVisibility());
        android.app.AlertDialog dialog = openDetails(activity);
        assertEquals(context.getString(R.string.status_off_keep_alive_blocked_untrusted),
                detailsMessage(dialog));
        android.widget.Button ok = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE);
        assertEquals(context.getString(android.R.string.ok), ok.getText().toString());
        assertEquals(View.GONE, dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).getVisibility());
        ok.performClick();
        shadowOf(android.os.Looper.getMainLooper()).idle();
        assertFalse(dialog.isShowing());
    }

    @Test
    public void theReasonLineAnnouncesItselfAsAButtonThatShowsDetails() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADB.recordExplicitIntent(context, true);
        connectTo(BSSID);
        MainActivity activity = open();

        android.view.accessibility.AccessibilityNodeInfo info =
                reasonRow(activity).createAccessibilityNodeInfo();
        assertEquals(android.widget.Button.class.getName(), info.getClassName().toString());
        String label = null;
        for (android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction candidate
                : info.getActionList()) {
            if (candidate.getId() == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) {
                label = String.valueOf(candidate.getLabel());
            }
        }
        assertEquals(context.getString(R.string.home_reason_details_action), label);
    }

    /**
     * #949: Wireless Debugging already on when the device joins a Wi-Fi that is not trusted. The
     * reason says that Keep-Alive will not switch it back on here and the action opens the decision.
     */
    @Test
    public void anOnStatusOnAnUntrustedWifiSaysSoAndOpensTheDecision() {
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(true));
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        connectTo(BSSID);
        KeepADBBlockedNetworkHistory.record(context, KeepADBNetworkIdentity.current(context),
                System.currentTimeMillis());
        MainActivity activity = open();

        assertEquals(context.getString(R.string.home_state_on), stateOf(activity));
        assertEquals(context.getString(R.string.home_reason_stays_off_untrusted),
                reason(activity).getText().toString());
        assertEquals(context.getString(R.string.status_on_network_untrusted),
                detailsMessage(openDetails(activity)));
        action(activity).performClick();
        Intent intent = shadowOf(activity).getNextStartedActivity();
        assertNotNull(intent);
        assertEquals(NetworkDecisionActivity.class.getName(), intent.getComponent().getClassName());
        assertEquals(BSSID, intent.getStringExtra(KeepADBNetworkTrustPrompt.EXTRA_BSSID));
    }

    @Test
    public void anOnStatusOnABlockedWifiSaysBlockedAndOpensTheList() {
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(true));
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBNetworkBlocklist.blockBssid(context, BSSID);
        connectTo(BSSID);
        MainActivity activity = open();

        assertEquals(context.getString(R.string.home_state_on), stateOf(activity));
        assertEquals(context.getString(R.string.home_reason_stays_off_blocked),
                reason(activity).getText().toString());
        assertEquals(context.getString(R.string.home_action_networks),
                action(activity).getText().toString());
        assertEquals(context.getString(R.string.status_on_network_blocked),
                detailsMessage(openDetails(activity)));
        action(activity).performClick();
        assertEquals(NetworkListActivity.class.getName(),
                shadowOf(activity).getNextStartedActivity().getComponent().getClassName());
    }

    /** Control: without Keep-Alive nothing would switch it back on anyway; no hint, no action. */
    @Test
    public void anOnStatusWithoutKeepAliveGetsNoNetworkHint() {
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(true));
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        connectTo(BSSID);
        MainActivity activity = open();

        assertEquals(context.getString(R.string.home_state_on), stateOf(activity));
        assertFalse(reason(activity).getText().toString().contains(
                context.getString(R.string.home_reason_stays_off_untrusted)));
        // #1027: on Wi-Fi without a known endpoint the only action is the restart, never the
        // trust decision.
        assertEquals(View.VISIBLE, action(activity).getVisibility());
        assertEquals(context.getString(R.string.home_action_restart_wifi_adb),
                ((android.widget.TextView) action(activity)).getText().toString());
    }

    /** #1027: the restart is a manual off now and a manual on after the pause. */
    @Test
    public void theRestartActionSwitchesOffAndOnAgain() {
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(true);
        KeepADB.setGatewayForTesting(gateway);
        connectTo(BSSID);
        MainActivity activity = open();

        action(activity).performClick();
        assertEquals(java.util.Arrays.asList(false), gateway.writes);
        shadowOf(android.os.Looper.getMainLooper()).idleFor(
                java.time.Duration.ofMillis(KeepADBRestart.PAUSE_MS));
        assertEquals(java.util.Arrays.asList(false, true), gateway.writes);
        assertTrue(gateway.isEnabled(context));
        assertFalse(KeepADB.wasLastExplicitIntentOff(context));
    }

    /** #1027: a newer decision during the pause wins; the restart does not switch back on. */
    @Test
    public void aNewerDecisionDuringTheRestartPauseDropsTheOn() {
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(true);
        KeepADB.setGatewayForTesting(gateway);
        connectTo(BSSID);
        MainActivity activity = open();

        action(activity).performClick();
        assertTrue(KeepADB.setEnabled(context, false, "tile").isSuccess());
        shadowOf(android.os.Looper.getMainLooper()).idleFor(
                java.time.Duration.ofMillis(KeepADBRestart.PAUSE_MS));
        assertFalse(gateway.writes.contains(true));
        assertFalse(gateway.isEnabled(context));
    }

    /**
     * #949: an undecided access point the app has no record of (no prompt was raised for it) would
     * only end in "expired" in the dialog; the action leads to the list, whose current card asks.
     */
    @Test
    public void theUntrustedActionWithoutARecordOpensTheNetworksListOnTheAccessPoint() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADB.recordExplicitIntent(context, true);
        connectTo(BSSID);
        MainActivity activity = open();

        action(activity).performClick();
        Intent intent = shadowOf(activity).getNextStartedActivity();
        assertNotNull(intent);
        assertEquals(NetworkListActivity.class.getName(), intent.getComponent().getClassName());
        assertEquals(BSSID, intent.getStringExtra(NetworkListActivity.EXTRA_FOCUS_BSSID));
    }

    /**
     * #790: a blocked access point is no open question. The reason says "blocked" instead of "not
     * trusted" and the action leads to the Networks list, not to a dialog that can only answer
     * "already decided". Control: the test above, where nothing is decided, still opens the dialog.
     */
    @Test
    public void theStatusOfABlockedAccessPointSaysBlockedAndOpensTheNetworksList() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADB.recordExplicitIntent(context, true);
        KeepADBNetworkBlocklist.blockBssid(context, BSSID);
        connectTo(BSSID);
        MainActivity activity = open();

        assertEquals(context.getString(R.string.home_reason_blocked), reason(activity).getText().toString());
        assertEquals(context.getString(R.string.status_off_keep_alive_blocked_by_user),
                detailsMessage(openDetails(activity)));
        assertEquals(context.getString(R.string.home_action_networks),
                action(activity).getText().toString());
        action(activity).performClick();
        Intent intent = shadowOf(activity).getNextStartedActivity();
        assertNotNull(intent);
        assertEquals(NetworkListActivity.class.getName(), intent.getComponent().getClassName());
        // #799: the list jumps to the row of the access point the status talks about.
        assertEquals(BSSID, intent.getStringExtra(NetworkListActivity.EXTRA_FOCUS_BSSID));
    }

    @Test
    public void theStatusOfABlockedNameAlsoOpensTheNetworksList() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADB.recordExplicitIntent(context, true);
        KeepADBNetworkBlocklist.blockSsid(context, "Cafe");
        connectTo("Cafe", BSSID);
        MainActivity activity = open();

        assertEquals(context.getString(R.string.home_reason_blocked), reason(activity).getText().toString());
        action(activity).performClick();
        assertEquals(NetworkListActivity.class.getName(),
                shadowOf(activity).getNextStartedActivity().getComponent().getClassName());
    }

    /** The tap reads the decision when it happens: trusted since the status was drawn, no dialog. */
    @Test
    public void aNetworkDecidedWhileTheStatusWasShownOpensTheListAndNotTheDialog() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADB.recordExplicitIntent(context, true);
        connectTo(BSSID);
        MainActivity activity = open();
        assertEquals(context.getString(R.string.networks_recent_decide),
                action(activity).getText().toString());

        KeepADBTrustedNetwork.addBssid(context, BSSID, "Home");
        action(activity).performClick();

        Intent opened = shadowOf(activity).getNextStartedActivity();
        assertEquals(NetworkListActivity.class.getName(), opened.getComponent().getClassName());
        assertEquals(BSSID, opened.getStringExtra(NetworkListActivity.EXTRA_FOCUS_BSSID));
    }

    /** #797 (point 3): the name-trusted branch of the same tap, on its own and not only by mutation. */
    @Test
    public void aNameTrustedWhileTheStatusWasShownOpensTheListOnThatAccessPoint() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADB.recordExplicitIntent(context, true);
        connectTo("Cafe", BSSID);
        MainActivity activity = open();
        assertEquals(context.getString(R.string.networks_recent_decide),
                action(activity).getText().toString());

        KeepADBTrustedNetwork.setTrustByNameEnabled(context, true);
        // Another access point of the same name is trusted; this one is covered by its name only.
        KeepADBTrustedNetwork.addBssid(context, "11:22:33:44:55:66", "Cafe");
        assertEquals("control: trusted by name now, not by address",
                KeepADBTrustedNetwork.Decision.TRUSTED_NAME,
                KeepADBTrustedNetwork.evaluate(context, KeepADBNetworkIdentity.current(context)));
        action(activity).performClick();

        Intent opened = shadowOf(activity).getNextStartedActivity();
        assertEquals(NetworkListActivity.class.getName(), opened.getComponent().getClassName());
        assertEquals(BSSID, opened.getStringExtra(NetworkListActivity.EXTRA_FOCUS_BSSID));
    }

    @Test
    public void otherStatesHaveNoActionAndTheActionGoesWhenTheReasonGoes() {
        // Plain "off": no reason, no action, nothing tappable.
        MainActivity off = open();
        assertEquals(View.GONE, reasonRow(off).getVisibility());
        assertEquals(View.GONE, action(off).getVisibility());
        assertFalse(off.findViewById(R.id.status).isClickable());

        // Untrusted and then trusted again on the same screen: the action must not linger.
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADB.recordExplicitIntent(context, true);
        connectTo(BSSID);
        org.robolectric.android.controller.ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        assertEquals(View.VISIBLE, action(activity).getVisibility());
        controller.pause();
        KeepADBTrustedNetwork.addBssid(context, BSSID, "Home");
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> false);
        controller.resume();
        assertEquals(View.GONE, action(activity).getVisibility());
        // What is left is the plain wait for Wi-Fi: a reason with its explanation, no action.
        assertEquals(context.getString(R.string.home_reason_waiting), reason(activity).getText().toString());
    }

    @Test
    public void aMissingPermissionShowsSetupMissingWithInstructionsThatLeadWhereTheWarningLeads() {
        shadowOf((Application) context).denyPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
        MainActivity activity = open();

        assertEquals(context.getString(R.string.home_state_setup), stateOf(activity));
        assertEquals(context.getString(R.string.home_reason_permission),
                reason(activity).getText().toString());
        assertFalse("no long sentence exists for this reason", reasonRow(activity).isClickable());
        assertEquals(context.getString(R.string.home_warning_action_guide),
                action(activity).getText().toString());
        action(activity).performClick();
        Intent expected = KeepADBHomeWarnings.systemIntent(context);
        Intent started = shadowOf(activity).getNextStartedActivity();
        assertEquals(expected.getComponent(), started.getComponent());
        assertEquals(expected.getStringExtra(OnboardingActivity.EXTRA_STEP),
                started.getStringExtra(OnboardingActivity.EXTRA_STEP));
    }

    /** "Retry" runs the switch's own path: the same click semantics, the same manual write. */
    @Test
    public void retryAfterAnUnconfirmedAndroidDialogRunsTheSwitchPath() {
        KeepADBStuckOffSettingsGateway gateway = new KeepADBStuckOffSettingsGateway();
        KeepADB.setGatewayForTesting(gateway);
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADB.recordExplicitIntent(context, true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        assertTrue(KeepADB.setEnabled(context, true, "keep_alive_check").isSuccess());
        android.os.SystemClock.sleep(1600);
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1600));
        assertTrue("precondition: the accepted-but-ineffective write engaged the backoff",
                KeepADB.isAutomaticEnableBackoffBlocked());
        int writesBefore = gateway.writes.size();
        MainActivity activity = open();

        assertEquals(context.getString(R.string.home_state_off), stateOf(activity));
        assertEquals(context.getString(R.string.home_reason_backoff), reason(activity).getText().toString());
        assertEquals(context.getString(R.string.status_off_keep_alive_blocked_recovery_backoff),
                detailsMessage(openDetails(activity)));
        assertEquals(context.getString(R.string.home_action_retry), action(activity).getText().toString());

        action(activity).performClick();
        shadowOf(android.os.Looper.getMainLooper()).idle();

        assertTrue("the retry writes like the switch does", gateway.writes.size() > writesBefore);
        assertEquals(Boolean.TRUE, gateway.writes.get(gateway.writes.size() - 1));
    }

    /** "Retry" only ever enables: a state that flipped to on since it was drawn is not switched off. */
    @Test
    public void retryNeverSwitchesAnAlreadyEnabledStateOff() {
        KeepADBStuckOffSettingsGateway gateway = new KeepADBStuckOffSettingsGateway();
        KeepADB.setGatewayForTesting(gateway);
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADB.recordExplicitIntent(context, true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        assertTrue(KeepADB.setEnabled(context, true, "keep_alive_check").isSuccess());
        android.os.SystemClock.sleep(1600);
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1600));
        MainActivity activity = open();
        assertEquals(context.getString(R.string.home_action_retry), action(activity).getText().toString());

        // The state flips to on before the tap.
        java.util.List<Boolean> writes = new java.util.ArrayList<>();
        KeepADB.setGatewayForTesting(new KeepADBSettingsGateway() {
            @Override
            public boolean isEnabled(Context c) {
                return true;
            }

            @Override
            public boolean write(Context appContext, boolean on) {
                writes.add(on);
                return true;
            }
        });
        action(activity).performClick();
        shadowOf(android.os.Looper.getMainLooper()).idle();

        assertFalse("retry must never write off", writes.contains(Boolean.FALSE));
    }

    @Test
    public void anUnreadableNetworkOffersFixWhereThePausedWarningLeads() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADB.recordExplicitIntent(context, true);
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        // No BSSID configured: the identity stays unknown, as without the Location permission.
        MainActivity activity = open();

        assertEquals(context.getString(R.string.home_reason_identity), reason(activity).getText().toString());
        assertEquals(context.getString(R.string.home_warning_action_fix),
                action(activity).getText().toString());
        action(activity).performClick();
        Intent expected = KeepADBNetworkTrustPrompt.identityUnavailableFixIntent(context);
        Intent started = shadowOf(activity).getNextStartedActivity();
        assertEquals(expected.getComponent(), started.getComponent());
        assertEquals(expected.getStringExtra(OnboardingActivity.EXTRA_STEP),
                started.getStringExtra(OnboardingActivity.EXTRA_STEP));
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
                .commit();

        open();

        assertFalse(prefs.contains("notification_permission_panel_visible"));
        assertFalse(prefs.contains("battery_optimization_panel_visible"));
        assertFalse(prefs.contains("network_onboarding_panel_visible"));
        assertFalse(prefs.contains("background_location_panel_visible"));
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
