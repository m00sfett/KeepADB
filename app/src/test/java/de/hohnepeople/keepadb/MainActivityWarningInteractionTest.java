package de.hohnepeople.keepadb;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.PowerManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Switch;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MainActivityWarningInteractionTest {
    @Rule public final KeepADBNetworkResetRule networkReset = new KeepADBNetworkResetRule();
    private final Context context = RuntimeEnvironment.getApplication();
    @Before public void setUp() {
        context.getSharedPreferences("keepadb_prefs", 0).edit().clear().commit();
        KeepADBRegisterClient.setHttpTransport(new KeepADBFakeHttpTransport(true));
        KeepADB.resetForTesting(context);
        KeepADBForceMode.resetForTesting();
        KeepADBForceMode.setClockForTesting(new KeepADBForceTestSupport.TestClock());
        KeepADBOnboarding.setAutoStartEnabledForTesting(false);
        KeepADBPreferences.setAppLanguage(context, "en");
        shadowOf((Application) context).grantPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS,
                android.Manifest.permission.POST_NOTIFICATIONS, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        shadowOf((PowerManager) context.getSystemService(Context.POWER_SERVICE))
                .setIgnoringBatteryOptimizations(context.getPackageName(), true);
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> false);
    }
    @After public void tearDown() {
        KeepADBRegisterClient.resetHttpTransport();
        KeepADBOnboarding.setAutoStartEnabledForTesting(true);
        KeepADBForceMode.resetForTesting();
        KeepADB.resetForTesting();
        context.getSharedPreferences("keepadb_prefs", 0).edit().clear().commit();
    }
    private ActivityController<MainActivity> open() {
        return Robolectric.buildActivity(MainActivity.class).setup();
    }
    private void close(MainActivity activity, int card) {
        activity.findViewById(card).findViewById(card == R.id.force_warning_panel
                ? R.id.force_warning_dismiss : R.id.home_warning_dismiss).performClick();
    }
    private boolean visible(MainActivity activity, int id) {
        return activity.findViewById(id).getVisibility() == View.VISIBLE;
    }
    @Test public void closeKeepsTriangleUndoRestoresAndRotationKeepsAcknowledgment() {
        KeepADBPreferences.setNotificationDetailsEnabled(context, true);
        ActivityController<MainActivity> controller = open();
        MainActivity activity = controller.get();
        assertTrue(visible(activity, R.id.warning_less_secure));
        close(activity, R.id.warning_less_secure);
        assertFalse(visible(activity, R.id.warning_less_secure));
        assertTrue(visible(activity, R.id.btn_security_warnings));
        assertTrue(visible(activity, R.id.warning_feedback));
        assertTrue(activity.findViewById(R.id.warning_feedback_text).hasFocus());
        Bundle state = new Bundle();
        controller.saveInstanceState(state).pause().stop().destroy();
        activity = Robolectric.buildActivity(MainActivity.class).create(state).start().resume().visible().get();
        assertFalse(visible(activity, R.id.warning_less_secure));
        assertTrue(visible(activity, R.id.warning_feedback));
        activity.findViewById(R.id.warning_undo).performClick();
        assertTrue(visible(activity, R.id.warning_less_secure));
        assertFalse(visible(activity, R.id.warning_feedback));
    }
    @Test public void triangleActivatesOnFirstTouchAfterReturningFocusAndClosingAnotherCard() {
        KeepADBPreferences.setNotificationDetailsEnabled(context, true);
        ActivityController<MainActivity> controller = open();
        MainActivity activity = controller.get();
        View triangle = activity.findViewById(R.id.btn_security_warnings);
        triangle.performClick();
        assertEquals(WarningsActivity.class.getName(), shadowOf(activity)
                .getNextStartedActivity().getComponent().getClassName());
        controller.pause().stop().start().resume();
        shadowOf(android.os.Looper.getMainLooper()).idle();
        close(activity, R.id.warning_less_secure);
        assertFalse("the feedback owns focus before the next triangle touch", triangle.hasFocus());
        long now = android.os.SystemClock.uptimeMillis();
        android.view.MotionEvent down = android.view.MotionEvent.obtain(now, now,
                android.view.MotionEvent.ACTION_DOWN, triangle.getWidth() / 2f,
                triangle.getHeight() / 2f, 0);
        android.view.MotionEvent up = android.view.MotionEvent.obtain(now, now + 10,
                android.view.MotionEvent.ACTION_UP, triangle.getWidth() / 2f,
                triangle.getHeight() / 2f, 0);
        try {
            triangle.dispatchTouchEvent(down);
            triangle.dispatchTouchEvent(up);
            shadowOf(android.os.Looper.getMainLooper()).idle();
            Intent opened = shadowOf(activity).getNextStartedActivity();
            assertNotNull("one touch must activate, rather than only take focus", opened);
            assertEquals(WarningsActivity.class.getName(), opened.getComponent().getClassName());
        } finally {
            down.recycle();
            up.recycle();
            controller.pause().stop().destroy();
        }
    }

    @Test public void forceXDoesNotEndModeAndSettingsEndPathRemainsAccessible() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));
        MainActivity activity = open().get();
        close(activity, R.id.force_warning_panel);
        assertFalse(visible(activity, R.id.force_warning_panel));
        assertTrue(KeepADBForceMode.isActive(context));
        assertTrue(visible(activity, R.id.btn_security_warnings));
        assertFalse(visible(activity, R.id.warning_mute));
        activity.findViewById(R.id.btn_security_warnings).performClick();
        Intent listIntent = shadowOf(activity).getNextStartedActivity();
        assertEquals(WarningsActivity.class.getName(), listIntent.getComponent().getClassName());
        WarningsActivity list = Robolectric.buildActivity(WarningsActivity.class, listIntent).setup().get();
        Button review = findButton((ViewGroup) list.findViewById(android.R.id.content),
                list.getString(R.string.home_warning_action_review));
        assertNotNull(review);
        review.performClick();
        Intent target = shadowOf(list).getNextStartedActivity();
        assertEquals(SettingsActivity.class.getName(), target.getComponent().getClassName());
        assertTrue(target.getBooleanExtra(SettingsActivity.EXTRA_FOCUS_FORCE, false));
    }
    @Test public void singleReasonMuteRemovesTriangleAndCanBeRestoredViaSettings() {
        KeepADBPreferences.setNotificationDetailsEnabled(context, true);
        MainActivity activity = open().get();
        close(activity, R.id.warning_less_secure);
        activity.findViewById(R.id.warning_mute).performClick();
        assertFalse(visible(activity, R.id.btn_security_warnings));
        assertFalse(visible(activity, R.id.warning_less_secure));
        Intent intent = new Intent(context, WarningsActivity.class).putExtra(WarningsActivity.EXTRA_MUTES, true);
        WarningsActivity settings = Robolectric.buildActivity(WarningsActivity.class, intent).setup().get();
        Switch details = findSwitch((ViewGroup) settings.findViewById(android.R.id.content),
                settings.getString(R.string.settings_notification_details_toggle));
        assertNotNull(details);
        assertTrue(details.isChecked());
        details.performClick();
        assertTrue(KeepADBWarningState.observe(context).visible.contains(KeepADBWarningState.Card.LESS_SECURE));
        assertTrue(KeepADBWarningState.observe(context).security().contains(KeepADBWarningState.Reason.NOTIFICATION_DETAILS));
    }
    @Test public void headerCountAndListContainEverySecurityReasonDespiteCardLimit() {
        shadowOf((Application) context).denyPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS);
        KeepADBPreferences.setNotificationDetailsEnabled(context, true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        // Enable the warning without triggering register traffic from an activity resume.
        KeepADBPreferences.setRegisterWebhookUrl(context, "http://localhost/register/test");
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));
        KeepADBWarningState.Snapshot snapshot = KeepADBWarningState.observe(context);
        assertEquals(5, snapshot.security().size());
        assertEquals(3, snapshot.visible.size());
        MainActivity activity = open().get();
        assertEquals(activity.getString(R.string.warnings_count, 5),
                activity.findViewById(R.id.btn_security_warnings).getContentDescription());
        activity.findViewById(R.id.btn_security_warnings).performClick();
        Intent listIntent = shadowOf(activity).getNextStartedActivity();
        WarningsActivity list = Robolectric.buildActivity(WarningsActivity.class, listIntent).setup().get();
        assertEquals(5, countButtons((ViewGroup) list.findViewById(android.R.id.content),
                list.getString(R.string.home_warning_action_review)));
        assertNull(findButton((ViewGroup) list.findViewById(android.R.id.content),
                list.getString(R.string.force_action_end)));
        // Close the higher priority card: the triangle's reason set is independent of dismissal.
        assertTrue(KeepADBWarningState.dismiss(context, KeepADBWarningState.Card.SYSTEM, snapshot));
        assertEquals(5, KeepADBWarningState.observe(context).security().size());
    }
    @Test public void operationalWarningsHaveNoTriangleAndMuteControlsHaveSevenReasons() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        shadowOf((PowerManager) context.getSystemService(Context.POWER_SERVICE))
                .setIgnoringBatteryOptimizations(context.getPackageName(), false);
        MainActivity activity = open().get();
        assertTrue(visible(activity, R.id.warning_limited));
        assertFalse(visible(activity, R.id.btn_security_warnings));
        close(activity, R.id.warning_limited);
        assertTrue(visible(activity, R.id.warning_mute));
        WarningsActivity settings = Robolectric.buildActivity(WarningsActivity.class,
                new Intent(context, WarningsActivity.class).putExtra(WarningsActivity.EXTRA_MUTES, true)).setup().get();
        assertEquals(7, countSwitches((ViewGroup) settings.findViewById(android.R.id.content)));
        assertNull(findSwitch((ViewGroup) settings.findViewById(android.R.id.content),
                settings.getString(R.string.force_card_title)));
    }
    @Test public void reviewTargetsUseSpecificSettingsRowsAndSystemGuide() {
        assertTrue(KeepADBWarningState.reviewIntent(context, KeepADBWarningState.Reason.NOTIFICATION_DETAILS)
                .getBooleanExtra(SettingsActivity.EXTRA_FOCUS_DETAILS, false));
        assertTrue(KeepADBWarningState.reviewIntent(context, KeepADBWarningState.Reason.WEBHOOK_CLEARTEXT)
                .getBooleanExtra(SettingsActivity.EXTRA_FOCUS_WEBHOOK, false));
        Intent system = KeepADBWarningState.reviewIntent(context, KeepADBWarningState.Reason.SYSTEM_PERMISSION);
        assertEquals(OnboardingActivity.class.getName(), system.getComponent().getClassName());
        assertEquals(OnboardingActionSteps.Permissions.ITEM_SYSTEM,
                system.getStringExtra(OnboardingActivity.EXTRA_FOCUS_ITEM));
    }
    @Test public void settingsReviewOpensAndFocusesTheExactRow() {
        for (String extra : new String[]{SettingsActivity.EXTRA_FOCUS_DETAILS, SettingsActivity.EXTRA_FOCUS_WEBHOOK}) {
            ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class,
                    new Intent(context, SettingsActivity.class).putExtra(extra, true)).setup();
            SettingsActivity activity = controller.get();
            shadowOf(android.os.Looper.getMainLooper()).idle();
            boolean details = extra.equals(SettingsActivity.EXTRA_FOCUS_DETAILS);
            assertEquals(View.VISIBLE, activity.findViewById(details ? R.id.settings_misc_body
                    : R.id.settings_webhook_body).getVisibility());
            assertTrue(activity.findViewById(details ? R.id.settings_notification_details_toggle
                    : R.id.settings_webhook_url).hasFocus());
            controller.pause().stop().destroy();
        }
    }
    private int countButtons(ViewGroup parent, String text) {
        int count = 0;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof Button && text.contentEquals(((Button) child).getText())) count++;
            if (child instanceof ViewGroup) count += countButtons((ViewGroup) child, text);
        }
        return count;
    }

    private Button findButton(ViewGroup parent, String text) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof Button && text.contentEquals(((Button) child).getText())) return (Button) child;
            if (child instanceof ViewGroup) {
                Button found = findButton((ViewGroup) child, text);
                if (found != null) return found;
            }
        }
        return null;
    }
    private Switch findSwitch(ViewGroup parent, String text) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof Switch && text.contentEquals(((Switch) child).getText())) return (Switch) child;
            if (child instanceof ViewGroup) {
                Switch found = findSwitch((ViewGroup) child, text);
                if (found != null) return found;
            }
        }
        return null;
    }
    private int countSwitches(ViewGroup parent) {
        int count = 0;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof Switch) count++;
            if (child instanceof ViewGroup) count += countSwitches((ViewGroup) child);
        }
        return count;
    }
}
