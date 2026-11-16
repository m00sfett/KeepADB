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
import android.os.Bundle;
import android.os.PowerManager;
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
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/**
 * #963: a home warning is one compact row; a tap on the row (not on its buttons) opens the
 * explanation below it. Collapsed is the default, the state belongs to the warning and survives
 * the periodic refresh and a rotation, a warning that goes away and returns starts collapsed, and
 * the action and close buttons work while the row is collapsed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MainActivityWarningRowTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = RuntimeEnvironment.getApplication();
    private final java.util.List<ActivityController<MainActivity>> controllers = new java.util.ArrayList<>();
    private KeepADBForceTestSupport.TestClock clock;

    @Before
    public void setUp() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADB.resetForTesting(context);
        KeepADBForceMode.resetForTesting();
        clock = new KeepADBForceTestSupport.TestClock();
        KeepADBForceMode.setClockForTesting(clock);
        KeepADBPreferences.setAppLanguage(context, "en");
        // All is fine: every permission is there, nothing is marked.
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS,
                android.Manifest.permission.POST_NOTIFICATIONS,
                android.Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        setBatteryExempt(true);
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> false);
    }

    @After
    public void tearDown() {
        for (ActivityController<MainActivity> controller : controllers) {
            try {
                controller.pause().stop().destroy();
            } catch (RuntimeException ignored) {
                // Already destroyed.
            }
        }
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADBForceMode.resetForTesting();
        KeepADB.resetForTesting();
    }

    private ActivityController<MainActivity> start() {
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        controllers.add(controller);
        return controller;
    }

    private void setBatteryExempt(boolean exempt) {
        shadowOf((PowerManager) context.getSystemService(Context.POWER_SERVICE))
                .setIgnoringBatteryOptimizations(context.getPackageName(), exempt);
    }

    /** Keep-Alive is on and the battery exemption is missing: the "Keep-Alive is limited" card. */
    private void makeKeepAliveLimited() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        setBatteryExempt(false);
    }

    private static KeepADBWarningRow row(MainActivity activity, int cardId) {
        return activity.findViewById(cardId).findViewById(R.id.warning_row);
    }

    private static View details(MainActivity activity, int cardId) {
        return activity.findViewById(cardId).findViewById(R.id.warning_details);
    }

    private static TextView text(MainActivity activity, int cardId) {
        return activity.findViewById(cardId).findViewById(R.id.home_warning_text);
    }

    // ---- Default and toggling ---------------------------------------------------------------------------

    @Test
    public void aWarningStartsCollapsedWithTheExplanationHidden() {
        makeKeepAliveLimited();
        MainActivity activity = start().get();

        assertEquals(View.VISIBLE, activity.findViewById(R.id.warning_limited).getVisibility());
        assertFalse(row(activity, R.id.warning_limited).isExpanded());
        assertEquals(View.GONE, details(activity, R.id.warning_limited).getVisibility());
        assertEquals(context.getString(R.string.card_state_collapsed),
                row(activity, R.id.warning_limited).getStateDescription());
    }

    @Test
    public void aTapOnTheRowOpensAndClosesTheExplanationWithTheListOfMissingItems() {
        makeKeepAliveLimited();
        MainActivity activity = start().get();
        KeepADBWarningRow row = row(activity, R.id.warning_limited);

        row.performClick();

        assertTrue(row.isExpanded());
        assertEquals(View.VISIBLE, details(activity, R.id.warning_limited).getVisibility());
        assertEquals(context.getString(R.string.card_state_expanded), row.getStateDescription());
        assertEquals(context.getString(R.string.home_warning_limited_text) + "\n"
                        + context.getString(R.string.onboarding_perm_battery_title),
                text(activity, R.id.warning_limited).getText().toString());

        row.performClick();

        assertFalse(row.isExpanded());
        assertEquals(View.GONE, details(activity, R.id.warning_limited).getVisibility());
        assertEquals(context.getString(R.string.card_state_collapsed), row.getStateDescription());
    }

    @Test
    public void theRowIsOneFocusTargetLabelledWithTheTitleAndAnnouncedAsAButton() {
        makeKeepAliveLimited();
        MainActivity activity = start().get();
        KeepADBWarningRow row = row(activity, R.id.warning_limited);

        assertTrue(row.isFocusable());
        assertTrue(row.isClickable());
        assertEquals(context.getString(R.string.home_warning_limited_title),
                row.getContentDescription().toString());
        assertEquals(android.widget.Button.class.getName(), row.getAccessibilityClassName().toString());
        // The two buttons stay separate focus targets of at least 48dp.
        float density = activity.getResources().getDisplayMetrics().density;
        View action = activity.findViewById(R.id.warning_limited).findViewById(R.id.home_warning_action);
        View close = activity.findViewById(R.id.warning_limited).findViewById(R.id.home_warning_dismiss);
        assertTrue(action.isFocusable() && close.isFocusable());
        assertTrue(((android.widget.Button) action).getMinHeight() >= Math.round(48 * density));
        assertEquals(Math.round(48 * density), close.getLayoutParams().width);
        assertEquals(Math.round(48 * density), close.getLayoutParams().height);
    }

    // ---- The buttons work while collapsed ---------------------------------------------------------------

    @Test
    public void theActionFiresWhileCollapsedAndDoesNotOpenTheRow() {
        makeKeepAliveLimited();
        MainActivity activity = start().get();

        activity.findViewById(R.id.warning_limited).findViewById(R.id.home_warning_action).performClick();

        Intent intent = shadowOf(activity).getNextStartedActivity();
        assertNotNull("the action opens the matching step", intent);
        assertEquals(OnboardingActivity.class.getName(), intent.getComponent().getClassName());
        assertFalse("a tap on a button is not a tap on the row", row(activity, R.id.warning_limited).isExpanded());
        assertEquals(View.GONE, details(activity, R.id.warning_limited).getVisibility());
    }

    @Test
    public void theCloseButtonFiresWhileCollapsed() {
        makeKeepAliveLimited();
        MainActivity activity = start().get();

        activity.findViewById(R.id.warning_limited).findViewById(R.id.home_warning_dismiss).performClick();

        assertEquals(View.GONE, activity.findViewById(R.id.warning_limited).getVisibility());
        assertEquals(View.VISIBLE, activity.findViewById(R.id.warning_feedback).getVisibility());
    }

    // ---- State: refresh, rotation, a warning that returns ------------------------------------------------

    @Test
    public void anOpenRowStaysOpenAcrossTheRefreshAndFollowsTheNewText() {
        makeKeepAliveLimited();
        ActivityController<MainActivity> controller = start();
        MainActivity activity = controller.get();
        row(activity, R.id.warning_limited).performClick();

        // The periodic refresh: the screen is drawn again, and what is missing changed meanwhile.
        shadowOf((Application) context).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
        controller.pause().resume();

        assertTrue(row(activity, R.id.warning_limited).isExpanded());
        assertEquals(View.VISIBLE, details(activity, R.id.warning_limited).getVisibility());
        assertTrue(text(activity, R.id.warning_limited).getText().toString()
                .contains(context.getString(R.string.notification_permission_panel_title)));
        assertEquals(context.getString(R.string.card_state_expanded),
                row(activity, R.id.warning_limited).getStateDescription());
    }

    @Test
    public void aCollapsedRowStaysCollapsedAcrossTheRefresh() {
        makeKeepAliveLimited();
        ActivityController<MainActivity> controller = start();
        MainActivity activity = controller.get();

        controller.pause().resume();

        assertFalse(row(activity, R.id.warning_limited).isExpanded());
        assertEquals(View.GONE, details(activity, R.id.warning_limited).getVisibility());
    }

    @Test
    public void everyWarningKeepsItsOwnState() {
        shadowOf((Application) context).denyPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS);
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        MainActivity activity = start().get();
        assertEquals(View.VISIBLE, activity.findViewById(R.id.warning_system).getVisibility());
        assertEquals(View.VISIBLE, activity.findViewById(R.id.warning_less_secure).getVisibility());

        row(activity, R.id.warning_system).performClick();

        assertTrue(row(activity, R.id.warning_system).isExpanded());
        assertFalse(row(activity, R.id.warning_less_secure).isExpanded());
        assertEquals(View.GONE, details(activity, R.id.warning_less_secure).getVisibility());
    }

    @Test
    public void anOpenRowStaysOpenAcrossARotation() {
        makeKeepAliveLimited();
        ActivityController<MainActivity> first = start();
        row(first.get(), R.id.warning_limited).performClick();
        Bundle state = new Bundle();
        first.saveInstanceState(state).pause().stop().destroy();

        ActivityController<MainActivity> second = Robolectric.buildActivity(MainActivity.class)
                .create(state).start().restoreInstanceState(state).resume().visible();
        controllers.add(second);

        MainActivity activity = second.get();
        assertTrue(row(activity, R.id.warning_limited).isExpanded());
        assertEquals(View.VISIBLE, details(activity, R.id.warning_limited).getVisibility());
    }

    @Test
    public void aWarningThatGoesAwayAndReturnsStartsCollapsed() {
        makeKeepAliveLimited();
        ActivityController<MainActivity> controller = start();
        MainActivity activity = controller.get();
        row(activity, R.id.warning_limited).performClick();
        assertTrue(row(activity, R.id.warning_limited).isExpanded());

        controller.pause();
        setBatteryExempt(true);
        controller.resume();
        assertEquals(View.GONE, activity.findViewById(R.id.warning_limited).getVisibility());

        controller.pause();
        setBatteryExempt(false);
        controller.resume();
        assertEquals(View.VISIBLE, activity.findViewById(R.id.warning_limited).getVisibility());
        assertFalse("it returned: collapsed again", row(activity, R.id.warning_limited).isExpanded());
        assertEquals(View.GONE, details(activity, R.id.warning_limited).getVisibility());
    }

    // ---- The force panel -----------------------------------------------------------------------------

    @Test
    public void theForcePanelKeepsTheRemainingTimeInTheCollapsedRowAndOpensTheExplanation() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOURS_24, false));
        MainActivity activity = start().get();
        KeepADBWarningRow row = row(activity, R.id.force_warning_panel);
        View panel = activity.findViewById(R.id.force_warning_panel);

        assertEquals(View.VISIBLE, panel.getVisibility());
        assertFalse(row.isExpanded());
        String title = ((TextView) panel.findViewById(R.id.force_warning_title)).getText().toString();
        assertTrue(title, title.startsWith(context.getString(R.string.force_card_title)));
        assertTrue("the end time is state and stays in the row: " + title, title.contains("Active until "));
        assertEquals(View.GONE, panel.findViewById(R.id.warning_details).getVisibility());
        assertFalse("the way to Settings is part of the explanation",
                panel.findViewById(R.id.btn_force_settings).isShown());

        row.performClick();

        assertTrue(row.isExpanded());
        assertEquals(View.VISIBLE, panel.findViewById(R.id.warning_details).getVisibility());
        assertTrue(((TextView) panel.findViewById(R.id.force_warning_text)).getText().toString()
                .startsWith("Wireless debugging is turned back on in every network."));
        assertEquals("Settings", ((TextView) panel.findViewById(R.id.btn_force_settings)).getText().toString());
    }

    @Test
    public void theForcePanelSaysNoEndTimeInItsRowWhenUnlimited() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.UNLIMITED, true));
        MainActivity activity = start().get();

        String title = ((TextView) activity.findViewById(R.id.force_warning_title)).getText().toString();

        assertTrue(title, title.contains(context.getString(R.string.force_status_unlimited)));
        assertEquals(context.getString(R.string.force_card_title) + ", "
                        + context.getString(R.string.force_status_unlimited),
                row(activity, R.id.force_warning_panel).getContentDescription().toString());
    }

    @Test
    public void theEndActionOfTheForcePanelFiresWhileCollapsed() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));
        MainActivity activity = start().get();

        activity.findViewById(R.id.btn_force_end).performClick();

        assertFalse(KeepADBForceMode.isActive(context));
        assertEquals(View.GONE, activity.findViewById(R.id.force_warning_panel).getVisibility());
    }

    @Test
    public void theForcePanelStartsCollapsedAgainAfterTheModeEndedAndCameBack() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));
        ActivityController<MainActivity> controller = start();
        MainActivity activity = controller.get();
        row(activity, R.id.force_warning_panel).performClick();
        assertTrue(row(activity, R.id.force_warning_panel).isExpanded());

        activity.findViewById(R.id.btn_force_end).performClick();
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));
        controller.pause().resume();

        assertEquals(View.VISIBLE, activity.findViewById(R.id.force_warning_panel).getVisibility());
        assertFalse(row(activity, R.id.force_warning_panel).isExpanded());
    }

    // ---- The list of security reasons reuses the card but opens nothing -------------------------------------

    @Test
    public void theWarningsListShowsRowsThatAreNoTapTargetAndHaveNoChevron() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        WarningsActivity list = Robolectric.buildActivity(WarningsActivity.class).setup().get();

        KeepADBWarningRow row = list.findViewById(R.id.warning_row);

        assertNotNull(row);
        assertFalse(row.isClickable());
        assertFalse(row.isFocusable());
        assertNull(row.getStateDescription());
        assertNull(((TextView) list.findViewById(R.id.home_warning_title))
                .getCompoundDrawablesRelative()[2]);
    }
}
