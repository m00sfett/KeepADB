package de.hohnepeople.keepadb;

import static de.hohnepeople.keepadb.KeepADBForceTestSupport.HOUR;
import static de.hohnepeople.keepadb.KeepADBForceTestSupport.posted;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlertDialog;
import android.app.Application;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.RadioButton;
import android.widget.RadioGroup;
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
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

/**
 * #763: the confirmation dialog and the Settings row. The invariants have two sides each: the
 * confirm button of the unlimited span is not usable without the checked box and usable with it;
 * the warning stages are shown exactly for the durations they belong to and hidden for the others;
 * the dialog starts nothing by itself, canceling or rotating changes nothing.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBForceDialogTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = RuntimeEnvironment.getApplication();
    private KeepADBForceTestSupport.TestClock clock;

    @Before
    public void setUp() {
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.POST_NOTIFICATIONS,
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
        resetState();
        KeepADBPreferences.setAppLanguage(context, "en");
        clock = new KeepADBForceTestSupport.TestClock();
        KeepADBForceMode.setClockForTesting(clock);
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));
    }

    @After
    public void tearDown() {
        resetState();
    }

    private void resetState() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADBForceMode.resetForTesting();
        KeepADBEndpointCoordinator.resetForTesting();
        KeepADBEndpoint.resetForTesting();
        KeepADB.resetForTesting();
        KeepADBRegisterClient.resetForTesting();
        context.getSystemService(NotificationManager.class).cancelAll();
    }

    // --- The row ------------------------------------------------------------------------------------------------

    @Test
    public void theRowStartsOffAndOffersOnlyTheActivationButton() {
        SettingsActivity activity = open();

        assertEquals("Off", text(activity, R.id.settings_force_status));
        assertEquals(View.VISIBLE, activity.findViewById(R.id.settings_force_activate).getVisibility());
        assertEquals(View.GONE, activity.findViewById(R.id.settings_force_end).getVisibility());
        assertEquals(View.GONE, activity.findViewById(R.id.settings_force_change).getVisibility());
        assertFalse(KeepADBForceMode.isActive(context));
    }

    // --- Warning stages and the confirm button ----------------------------------------------------------------------

    @Test
    public void theDialogPreselectsOneHourAndShowsOnlyTheGeneralWarning() {
        AlertDialog dialog = openDialog(open());

        RadioGroup spans = dialog.findViewById(R.id.force_dialog_spans);
        assertEquals("One hour, the shortest, is preselected", R.id.force_span_1h,
                spans.getCheckedRadioButtonId());
        assertEquals("Turn on force mode?", titleOf(dialog));
        assertEquals(View.VISIBLE, dialog.findViewById(R.id.force_dialog_warning_all).getVisibility());
        assertEquals(View.GONE, dialog.findViewById(R.id.force_dialog_warning_days).getVisibility());
        assertEquals(View.GONE, dialog.findViewById(R.id.force_dialog_warning_unlimited).getVisibility());
        assertEquals(View.GONE, dialog.findViewById(R.id.force_dialog_ack).getVisibility());
        assertEquals("Turn on for 1 hour", ((Button) dialog.findViewById(R.id.force_dialog_confirm)).getText().toString());
        String general = ((TextView) dialog.findViewById(R.id.force_dialog_warning_all)).getText().toString();
        assertTrue(general, general.contains("full ADB access"));
        assertTrue(general, general.contains("Your blocks and trusted networks don't apply during this time."));
        assertTrue(general, general.contains("end it at any time from the home screen or the notification"));
        assertFalse("Opening the dialog starts nothing", KeepADBForceMode.isActive(context));
    }

    @Test
    public void warningStagesAndLabelsAppearExactlyForTheDurationsOfTheConcept() {
        AlertDialog dialog = openDialog(open());
        Object[][] expected = {
                // radio, stage-2 warning, stage-3 warning and checkbox, confirm label
                {R.id.force_span_1h, false, false, "Turn on for 1 hour"},
                {R.id.force_span_24h, false, false, "Turn on for 24 hours"},
                {R.id.force_span_7d, true, false, "Turn on for 7 days"},
                {R.id.force_span_30d, true, false, "Turn on for 30 days"},
                {R.id.force_span_unlimited, true, true, "Turn on without end time"},
        };
        for (Object[] row : expected) {
            ((RadioButton) dialog.findViewById((int) row[0])).setChecked(true);

            assertEquals("several-days warning for " + row[3], (boolean) row[1],
                    dialog.findViewById(R.id.force_dialog_warning_days).getVisibility() == View.VISIBLE);
            assertEquals("no-end-time warning for " + row[3], (boolean) row[2],
                    dialog.findViewById(R.id.force_dialog_warning_unlimited).getVisibility() == View.VISIBLE);
            assertEquals("acknowledgment box for " + row[3], (boolean) row[2],
                    dialog.findViewById(R.id.force_dialog_ack).getVisibility() == View.VISIBLE);
            assertEquals(row[3], ((Button) dialog.findViewById(R.id.force_dialog_confirm)).getText().toString());
            assertEquals("The general warning is there for every duration", View.VISIBLE,
                    dialog.findViewById(R.id.force_dialog_warning_all).getVisibility());
        }
        String days = ((TextView) dialog.findViewById(R.id.force_dialog_warning_days)).getText().toString();
        assertTrue(days, days.contains("hotel, train, café, work"));
        String unlimited = ((TextView) dialog.findViewById(R.id.force_dialog_warning_unlimited)).getText().toString();
        assertTrue(unlimited, unlimited.contains("until you end it yourself, including after restarts and app updates"));
    }

    @Test
    public void theUnlimitedConfirmButtonIsDisabledWithoutTheBoxAndEnabledWithIt() {
        AlertDialog dialog = openDialog(open());
        ((RadioButton) dialog.findViewById(R.id.force_span_unlimited)).setChecked(true);
        Button confirm = dialog.findViewById(R.id.force_dialog_confirm);
        CheckBox box = dialog.findViewById(R.id.force_dialog_ack);

        assertFalse("Without the tick the button is not usable", confirm.isEnabled());
        confirm.performClick(); // a programmatic click bypasses the disabled state
        assertFalse("... and a click that gets through changes nothing", KeepADBForceMode.isActive(context));
        assertTrue(dialog.isShowing());

        box.setChecked(true);
        assertTrue("With the tick it is usable", confirm.isEnabled());

        box.setChecked(false);
        assertFalse("Untick again: not usable again", confirm.isEnabled());
        ((RadioButton) dialog.findViewById(R.id.force_span_7d)).setChecked(true);
        assertTrue("A limited span needs no tick", confirm.isEnabled());
        ((RadioButton) dialog.findViewById(R.id.force_span_unlimited)).setChecked(true);
        assertFalse("Back to unlimited: the earlier untick still holds", confirm.isEnabled());

        box.setChecked(true);
        confirm.performClick();
        ShadowLooper.idleMainLooper();
        assertTrue(KeepADBForceMode.isActive(context));
        assertTrue(KeepADBForceMode.status(context).isUnlimited());
        assertFalse(dialog.isShowing());
    }

    @Test
    public void theTickBelongsToTheUnlimitedChoiceAndMustBeGivenAgainAfterLeavingIt() {
        AlertDialog dialog = openDialog(open());
        CheckBox box = dialog.findViewById(R.id.force_dialog_ack);
        Button confirm = dialog.findViewById(R.id.force_dialog_confirm);
        ((RadioButton) dialog.findViewById(R.id.force_span_unlimited)).setChecked(true);
        box.setChecked(true);
        assertTrue(confirm.isEnabled());

        ((RadioButton) dialog.findViewById(R.id.force_span_30d)).setChecked(true);
        assertFalse("Leaving the choice takes the tick back", box.isChecked());
        ((RadioButton) dialog.findViewById(R.id.force_span_unlimited)).setChecked(true);

        assertEquals(View.VISIBLE, box.getVisibility());
        assertFalse("Back on unlimited: a fresh tick is needed", box.isChecked());
        assertFalse(confirm.isEnabled());
        confirm.performClick();
        assertFalse(KeepADBForceMode.isActive(context));
    }

    // --- Activation, cancel, keep-alive hint -------------------------------------------------------------------------

    @Test
    public void confirmingActivatesTheSelectedSpanSwitchesKeepAliveOnAndUpdatesTheRow() {
        SettingsActivity activity = open();
        assertFalse(KeepADBPreferences.isKeepAliveEnabled(context));
        AlertDialog dialog = openDialog(activity);
        ((RadioButton) dialog.findViewById(R.id.force_span_24h)).setChecked(true);

        dialog.findViewById(R.id.force_dialog_confirm).performClick();
        ShadowLooper.idleMainLooper();

        KeepADBForceMode.Status status = KeepADBForceMode.status(context);
        assertNotNull(status);
        assertEquals(KeepADBForceMode.Span.HOURS_24, status.span);
        assertEquals(24 * HOUR, status.remainingMs);
        assertTrue("F7: Force switches Keep-Alive on with it", KeepADBPreferences.isKeepAliveEnabled(context));
        assertFalse(dialog.isShowing());
        assertTrue(text(activity, R.id.settings_force_status).startsWith("Active until "));
        assertEquals(View.GONE, activity.findViewById(R.id.settings_force_activate).getVisibility());
        assertEquals(View.VISIBLE, activity.findViewById(R.id.settings_force_end).getVisibility());
        assertEquals(View.VISIBLE, activity.findViewById(R.id.settings_force_change).getVisibility());
    }

    @Test
    public void aLimitedModeThatCannotKeepItsLimitIsRefusedWithAHintAndTheDialogStaysOpen() {
        clock.bootCountReadable = false; // Settings.Global.boot_count cannot be read
        AlertDialog dialog = openDialog(open());

        dialog.findViewById(R.id.force_dialog_confirm).performClick();
        ShadowLooper.idleMainLooper();

        assertEquals("The user is told instead of facing a button that does nothing",
                "Force mode was not turned on: a time limit can't be kept reliably on this device right now. "
                        + "Nothing was changed.",
                ShadowToast.getTextOfLatestToast());
        assertTrue("The dialog stays, another choice is still possible", dialog.isShowing());
        assertFalse(KeepADBForceMode.isActive(context));
        assertFalse("Refused: nothing stored", context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE)
                .contains(KeepADBForceMode.KEY_STATE));
        assertFalse("... and Keep-Alive is not switched on for a mode that cannot run",
                KeepADBPreferences.isKeepAliveEnabled(context));
    }

    @Test
    public void thereIsNoRefusalHintWhenTheBootCounterIsReadableOrTheChoiceHasNoTimeLimit() {
        SettingsActivity activity = open();
        AlertDialog dialog = openDialog(activity);
        dialog.findViewById(R.id.force_dialog_confirm).performClick();
        ShadowLooper.idleMainLooper();
        assertTrue(KeepADBForceMode.isActive(context));
        assertNull("Readable counter: started, nothing refused", ShadowToast.getLatestToast());

        KeepADBForceMode.endNow(context);
        ShadowToast.reset();
        clock.bootCountReadable = false;
        AlertDialog unlimited = openDialog(activity);
        ((RadioButton) unlimited.findViewById(R.id.force_span_unlimited)).setChecked(true);
        ((CheckBox) unlimited.findViewById(R.id.force_dialog_ack)).setChecked(true);
        unlimited.findViewById(R.id.force_dialog_confirm).performClick();
        ShadowLooper.idleMainLooper();

        assertTrue("An unlimited mode has no budget and needs no counter", KeepADBForceMode.isActive(context));
        assertNull(ShadowToast.getLatestToast());
    }

    @Test
    public void cancelAndBackChangeNothing() {
        SettingsActivity activity = open();
        AlertDialog dialog = openDialog(activity);
        ((RadioButton) dialog.findViewById(R.id.force_span_30d)).setChecked(true);

        dialog.findViewById(R.id.force_dialog_cancel).performClick();
        assertFalse(dialog.isShowing());
        assertFalse(KeepADBForceMode.isActive(context));
        assertFalse(KeepADBPreferences.isKeepAliveEnabled(context));
        assertEquals("Off", text(activity, R.id.settings_force_status));

        AlertDialog again = openDialog(activity);
        again.cancel(); // what the back button does
        assertFalse(KeepADBForceMode.isActive(context));
        assertFalse(KeepADBPreferences.isKeepAliveEnabled(context));
    }

    @Test
    public void theKeepAliveHintIsShownOnlyWhileKeepAliveIsOff() {
        AlertDialog off = openDialog(open());
        assertEquals(View.VISIBLE, off.findViewById(R.id.force_dialog_keepalive_hint).getVisibility());
        assertEquals("Force mode only works with Keep-Alive. Keep-Alive is turned on with it.",
                ((TextView) off.findViewById(R.id.force_dialog_keepalive_hint)).getText().toString());
        off.dismiss();

        KeepADBPreferences.setKeepAliveEnabled(context, true);
        AlertDialog on = openDialog(open());

        assertEquals(View.GONE, on.findViewById(R.id.force_dialog_keepalive_hint).getVisibility());
    }

    // --- Change and end ------------------------------------------------------------------------------------------------------

    @Test
    public void changingTheDurationGoesThroughTheFullDialogAndStartsTheTimeAnew() {
        SettingsActivity activity = open();
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.DAYS_7, false));
        clock.advance(2 * HOUR);
        ShadowLooper.idleMainLooper();
        activity.findViewById(R.id.settings_force_change).performClick();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("The same full dialog, warnings included", dialog);
        assertEquals("... preselecting the safe default, not the running span", R.id.force_span_1h,
                ((RadioGroup) dialog.findViewById(R.id.force_dialog_spans)).getCheckedRadioButtonId());
        assertEquals("Nothing changed by merely opening it", 7 * 24 * HOUR - 2 * HOUR,
                KeepADBForceMode.status(context).remainingMs);

        dialog.findViewById(R.id.force_dialog_confirm).performClick();

        assertEquals("A new full start, not an extension", HOUR, KeepADBForceMode.status(context).remainingMs);
    }

    @Test
    public void theEndButtonEndsTheModeAndTheRowShowsOffAgain() {
        SettingsActivity activity = open();
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.UNLIMITED, true));
        ShadowLooper.idleMainLooper();
        assertEquals("Active, no end time", text(activity, R.id.settings_force_status));

        activity.findViewById(R.id.settings_force_end).performClick();

        assertFalse(KeepADBForceMode.isActive(context));
        assertEquals("Off", text(activity, R.id.settings_force_status));
        assertEquals(View.VISIBLE, activity.findViewById(R.id.settings_force_activate).getVisibility());
        assertNull("Ended by hand: no expiry notice", posted(context, KeepADBForceNotice.NOTIFICATION_ID));
    }

    // --- The row follows the deadline --------------------------------------------------------------------------------------------

    @Test
    public void theRowReadsOffAtTheDeadlineBeforeAnyTransitionAndTheOpenScreenRedrawsOnExpiry() {
        SettingsActivity activity = open();
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));
        ShadowLooper.idleMainLooper();
        assertTrue(text(activity, R.id.settings_force_status).startsWith("Active until "));

        clock.advance(HOUR);
        // The transition (heartbeat, alarm) redraws the open screen through the state listener.
        assertTrue(KeepADBForceMode.finishIfExpired(context));
        ShadowLooper.idleMainLooper();

        assertEquals("Off", text(activity, R.id.settings_force_status));
        assertNotNull(posted(context, KeepADBForceNotice.NOTIFICATION_ID));
    }

    @Test
    public void settingsListensForTheModeOnlyWhileItIsResumed() {
        ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class).setup();
        assertTrue(KeepADBForceMode.hasStateListenerForTesting());

        controller.pause();

        assertFalse(KeepADBForceMode.hasStateListenerForTesting());
        controller.resume().pause().stop().destroy();
        assertFalse(KeepADBForceMode.hasStateListenerForTesting());
    }

    @Test
    public void openingSettingsAfterTheDeadlineFinishesTheModeAndReportsIt() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));
        clock.advance(2 * HOUR);

        SettingsActivity activity = open();

        assertEquals("Off", text(activity, R.id.settings_force_status));
        assertNotNull("Opening the screen delivered the expiry notice",
                posted(context, KeepADBForceNotice.NOTIFICATION_ID));
    }

    // --- Rotation, focus ----------------------------------------------------------------------------------------------------------

    @Test
    public void theSelectionSurvivesARotationButTheRestoredDialogStillNeedsItsOwnTap() {
        ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class).setup();
        AlertDialog dialog = openDialog(controller.get());
        ((RadioButton) dialog.findViewById(R.id.force_span_unlimited)).setChecked(true);
        ((CheckBox) dialog.findViewById(R.id.force_dialog_ack)).setChecked(true);
        Bundle saved = new Bundle();
        controller.saveInstanceState(saved);
        assertTrue(saved.getBoolean(KeepADBForceDialog.STATE_SHOWING));
        controller.pause().stop().destroy();

        ActivityController<SettingsActivity> restored =
                Robolectric.buildActivity(SettingsActivity.class).setup(saved);

        AlertDialog again = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue("The dialog is back", again.isShowing());
        assertEquals(R.id.force_span_unlimited,
                ((RadioGroup) again.findViewById(R.id.force_dialog_spans)).getCheckedRadioButtonId());
        assertTrue(((CheckBox) again.findViewById(R.id.force_dialog_ack)).isChecked());
        assertFalse("Restoring is not confirming", KeepADBForceMode.isActive(context));
        restored.pause().stop().destroy();
    }

    @Test
    public void aDismissedDialogIsNotRestored() {
        ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class).setup();
        openDialog(controller.get()).dismiss();
        Bundle saved = new Bundle();
        controller.saveInstanceState(saved);
        assertFalse(saved.getBoolean(KeepADBForceDialog.STATE_SHOWING));
        controller.pause().stop().destroy();

        Robolectric.buildActivity(SettingsActivity.class).setup(saved);

        AlertDialog latest = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue("No force dialog: " + latest, latest == null || latest.findViewById(R.id.force_dialog_spans) == null
                || !latest.isShowing());
    }

    @Test
    public void theFocusExtraExpandsTheNetworkCardWithTheForceRowInside() {
        Intent intent = new Intent(context, SettingsActivity.class)
                .putExtra(SettingsActivity.EXTRA_FOCUS_FORCE, true);

        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class, intent).setup().get();

        View body = activity.findViewById(R.id.settings_network_beta_body);
        assertEquals("The network card is expanded", View.VISIBLE, body.getVisibility());
        View panel = activity.findViewById(R.id.settings_force_panel);
        assertTrue("... and the force row is part of it", isDescendantOf(panel, body));
        assertFalse("The extra is consumed so a later resume does not scroll again",
                activity.getIntent().hasExtra(SettingsActivity.EXTRA_FOCUS_FORCE));
    }

    @Test
    public void withoutTheFocusExtraTheNetworkCardStaysCollapsed() {
        SettingsActivity activity = open();

        assertEquals(View.GONE, activity.findViewById(R.id.settings_network_beta_body).getVisibility());
    }

    // --- Helpers ----------------------------------------------------------------------------------------------------------------------

    private SettingsActivity open() {
        return Robolectric.buildActivity(SettingsActivity.class).setup().get();
    }

    private AlertDialog openDialog(SettingsActivity activity) {
        activity.findViewById(R.id.settings_force_activate).performClick();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
        return dialog;
    }

    private static String text(SettingsActivity activity, int id) {
        return ((TextView) activity.findViewById(id)).getText().toString();
    }

    private static String titleOf(AlertDialog dialog) {
        return String.valueOf(shadowOf(dialog).getTitle());
    }

    private static boolean isDescendantOf(View view, View ancestor) {
        for (android.view.ViewParent parent = view.getParent(); parent != null; parent = parent.getParent()) {
            if (parent == ancestor) return true;
        }
        return false;
    }
}
