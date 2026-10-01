package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;

import java.util.ArrayList;
import java.util.List;

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
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

/**
 * #698: behavior of the diagnostics export and the feedback report dialog after both moved from
 * {@link SettingsActivity} into {@link KeepADBDiagnosticsController}: what the body contains before
 * and after the opt-in, what Share and the feedback button do, and what a rotation, a restore and
 * a destroy bring back.
 *
 * <p>The privacy invariants are asserted from both sides. A diagnostics canary is written into the
 * real diagnostics store first, and the test proves that this canary is in the export and in the
 * section builder, so "the text does not contain it" cannot pass by the canary never being there.
 * Every "section absent" assertion has its "section present exactly once" twin: a body that never
 * gets diagnostics would satisfy the first half alone, and a body that always has them the second.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SettingsIssueReportDialogTest {

    /** Survives both redaction layers, so it is visible wherever diagnostics really are. */
    private static final String CANARY = "PRIVACY_CANARY_7F3A";
    private static final String HAND_TEXT = "MY_HANDWRITTEN_PROBLEM_TEXT";
    private static final String EXPORT_EVENT =
            "event=diagnostics_export source=settings outcome=requested detail=share_sheet";

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = RuntimeEnvironment.getApplication();

    @Before
    public void setUp() {
        clearStores();
        // The release ring buffer, so the export is a plain read of what the test recorded.
        KeepADBBuildFlags.setOverrideForTesting(false);
        KeepADBDiagnostics.event(context, "canary_event", "test", "recorded", "canary=" + CANARY);
        ShadowDialog.reset();

        // Precondition of every "does not contain" assertion below: the canary is really there.
        assertTrue(KeepADBDiagnostics.export(context).contains(CANARY));
        assertTrue(KeepADBIssueReporter.buildDiagnosticsSection(context).contains(CANARY));
        assertFalse(KeepADBIssueReporter.buildBody(context, false).contains(CANARY));
        assertTrue(KeepADBIssueReporter.buildBody(context, true).contains(CANARY));
    }

    @After
    public void tearDown() {
        KeepADBBuildFlags.setOverrideForTesting(null);
        clearStores();
        KeepADB.resetForTesting();
        KeepADBRegisterClient.resetHttpTransport();
        KeepADBEndpointCoordinator.resetForTesting();
    }

    private void clearStores() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("keepadb_diagnostics", Context.MODE_PRIVATE).edit().clear()
                .commit();
    }

    // --- opt-in: nothing without the box, exactly once with it ---------------------------------

    @Test
    public void theDialogOpensUncheckedAndWithoutAnyDiagnostics() {
        ActivityController<SettingsActivity> controller = start();
        SettingsActivity activity = controller.get();
        AlertDialog dialog = openIssueDialog(activity);

        assertFalse("Diagnostics are opt-in: the box starts unchecked", checkBox(dialog).isChecked());
        String text = preview(dialog).getText().toString();
        assertEquals(KeepADBIssueReporter.buildBody(activity, false), text);
        assertFalse(text.contains(CANARY));
        assertFalse(text.contains(sectionTitle()));
        assertNull("Opening the dialog opens nothing", shadowOf(activity).getNextStartedActivity());

        // The preview stays a real, multi-line text field the user can edit.
        EditText preview = preview(dialog);
        assertTrue(preview.isEnabled());
        assertTrue(preview.isFocusable());
        assertEquals(InputType.TYPE_CLASS_TEXT, preview.getInputType() & InputType.TYPE_MASK_CLASS);
        assertTrue((preview.getInputType() & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0);
        controller.pause().stop().destroy();
    }

    @Test
    public void theDialogUsesTheSameStringResourcesAsBefore() {
        ActivityController<SettingsActivity> controller = start();
        SettingsActivity activity = controller.get();
        AlertDialog dialog = openIssueDialog(activity);

        assertEquals(activity.getString(R.string.settings_issue_report_dialog_title),
                shadowOf(dialog).getTitle().toString());
        assertEquals(activity.getString(R.string.settings_issue_report_include_diagnostics),
                checkBox(dialog).getText().toString());
        assertEquals(activity.getString(R.string.settings_issue_report_include_diagnostics),
                checkBox(dialog).getContentDescription().toString());
        assertEquals(activity.getString(R.string.settings_issue_report_preview),
                preview(dialog).getContentDescription().toString());
        assertEquals(activity.getString(R.string.settings_issue_report_preview_hint),
                preview(dialog).getHint().toString());
        assertEquals(activity.getString(R.string.settings_issue_report_open_feedback),
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
        assertEquals(activity.getString(R.string.settings_issue_report_share),
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).getText().toString());
        assertEquals(activity.getString(android.R.string.cancel),
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());
        controller.pause().stop().destroy();
    }

    @Test
    public void aRotationWithoutAnOpenDialogOpensNone() {
        ActivityController<SettingsActivity> controller = start();
        assertNull(controller.get().getActiveIssueReportDialog());

        ActivityController<SettingsActivity> restored = rotate(controller);

        assertNull("Only a dialog that was showing is brought back",
                restored.get().getActiveIssueReportDialog());
        restored.pause().stop().destroy();
    }

    @Test
    public void checkingTheBoxAddsExactlyOneSectionAndUncheckingRemovesItKeepingTheHandwrittenText() {
        ActivityController<SettingsActivity> controller = start();
        AlertDialog dialog = openIssueDialog(controller.get());
        String edited = KeepADBIssueReporter.buildBody(context, false) + " " + HAND_TEXT;
        preview(dialog).setText(edited);

        checkBox(dialog).performClick();
        String opted = preview(dialog).getText().toString();
        assertTrue(checkBox(dialog).isChecked());
        assertEquals("Exactly one diagnostics section", 1, count(opted, sectionTitle()));
        assertEquals("The diagnostics are really in it", 1, count(opted, CANARY));
        assertTrue("The handwritten text stays", opted.contains(HAND_TEXT));

        // The user keeps typing below the section, then withdraws the opt-in.
        preview(dialog).setText(opted.replace(HAND_TEXT, HAND_TEXT + "_EDITED"));
        checkBox(dialog).performClick();
        String withdrawn = preview(dialog).getText().toString();
        assertFalse(checkBox(dialog).isChecked());
        assertEquals("Withdrawing the opt-in removes the section completely", 0,
                count(withdrawn, sectionTitle()));
        assertEquals(0, count(withdrawn, CANARY));
        assertEquals(edited.replace(HAND_TEXT, HAND_TEXT + "_EDITED"), withdrawn);

        // Opting in again must not stack a second section.
        checkBox(dialog).performClick();
        String again = preview(dialog).getText().toString();
        assertEquals(1, count(again, sectionTitle()));
        assertEquals(1, count(again, CANARY));
        assertTrue(again.contains(HAND_TEXT + "_EDITED"));
        controller.pause().stop().destroy();
    }

    // --- Share: ACTION_SEND, with the dialog and the draft left standing ----------------------

    @Test
    public void sharingWithoutOptInSendsTheEditedBodyAndLeavesTheDialogAndDraftStanding() {
        ActivityController<SettingsActivity> controller = start();
        SettingsActivity activity = controller.get();
        AlertDialog dialog = openIssueDialog(activity);
        String edited = KeepADBIssueReporter.buildBody(activity, false) + " " + HAND_TEXT;
        preview(dialog).setText(edited);

        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        ShadowLooper.idleMainLooper();

        Intent chooser = shadowOf(activity).getNextStartedActivity();
        assertNotNull("Share starts the chooser", chooser);
        assertEquals(Intent.ACTION_CHOOSER, chooser.getAction());
        assertEquals(activity.getString(R.string.settings_issue_report_share),
                chooser.getCharSequenceExtra(Intent.EXTRA_TITLE).toString());
        Intent send = sendIntent(chooser);
        assertEquals(Intent.ACTION_SEND, send.getAction());
        assertEquals("text/plain", send.getType());
        assertEquals(activity.getString(R.string.issue_report_title),
                send.getStringExtra(Intent.EXTRA_SUBJECT));
        String shared = send.getStringExtra(Intent.EXTRA_TEXT);
        assertEquals("The shared text is the editable body, exactly", edited, shared);
        assertFalse(shared.contains(CANARY));
        assertFalse(shared.contains(sectionTitle()));
        assertNull("One chooser, nothing else", shadowOf(activity).getNextStartedActivity());

        assertTrue("ACTION_SEND leaves the dialog standing", dialog.isShowing());
        assertSame(dialog, activity.getActiveIssueReportDialog());
        assertEquals("...and the draft", edited, preview(dialog).getText().toString());
        controller.pause().stop().destroy();
    }

    @Test
    public void sharingWithOptInSendsTheDiagnosticsSectionExactlyOnce() {
        ActivityController<SettingsActivity> controller = start();
        SettingsActivity activity = controller.get();
        AlertDialog dialog = openIssueDialog(activity);
        checkBox(dialog).performClick();
        String draft = preview(dialog).getText().toString();

        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        ShadowLooper.idleMainLooper();

        String shared = sendIntent(shadowOf(activity).getNextStartedActivity())
                .getStringExtra(Intent.EXTRA_TEXT);
        assertEquals(draft, shared);
        assertEquals(1, count(shared, sectionTitle()));
        assertEquals(1, count(shared, CANARY));
        assertTrue(dialog.isShowing());
        assertEquals(draft, preview(dialog).getText().toString());
        controller.pause().stop().destroy();
    }

    @Test
    public void anUncheckedBoxStripsAStaleSectionOnShareAndFeedbackAndNeverStacksOnReCheck() {
        // Reachable through a restore: the draft still has a section, but the saved box is off.
        // The listener is not involved in a restore, so Share and the feedback button strip it.
        String stale = restoredDraftWithSection();
        ActivityController<SettingsActivity> controller = Robolectric
                .buildActivity(SettingsActivity.class).setup(savedDialog(stale, false));
        SettingsActivity activity = controller.get();
        AlertDialog dialog = activity.getActiveIssueReportDialog();
        assertNotNull(dialog);
        assertFalse(checkBox(dialog).isChecked());
        assertEquals("A restore shows the saved draft verbatim", stale,
                preview(dialog).getText().toString());

        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        ShadowLooper.idleMainLooper();
        String shared = sendIntent(shadowOf(activity).getNextStartedActivity())
                .getStringExtra(Intent.EXTRA_TEXT);
        assertEquals("Unchecked: the section is gone from what is shared", 0,
                count(shared, sectionTitle()));
        assertFalse(shared.contains("SAVED_SECTION_MARKER"));
        assertTrue(shared.contains(HAND_TEXT));
        assertEquals("...and from the draft", shared, preview(dialog).getText().toString());
        assertTrue(dialog.isShowing());

        // The user now opts in on a text that has no section any more: exactly one comes back.
        checkBox(dialog).performClick();
        String opted = preview(dialog).getText().toString();
        assertEquals(1, count(opted, sectionTitle()));
        assertEquals(1, count(opted, CANARY));
        controller.pause().stop().destroy();
    }

    @Test
    public void checkingTheBoxOnATextThatAlreadyHasTheSectionDoesNotStackASecondOne() {
        // The duplicate guard of the listener: a section that is already in the text (here from a
        // restored draft whose saved box was off) is not built and added a second time.
        String stale = restoredDraftWithSection();
        ActivityController<SettingsActivity> controller = Robolectric
                .buildActivity(SettingsActivity.class).setup(savedDialog(stale, false));
        AlertDialog dialog = controller.get().getActiveIssueReportDialog();
        assertFalse(checkBox(dialog).isChecked());

        checkBox(dialog).performClick();

        assertTrue(checkBox(dialog).isChecked());
        String text = preview(dialog).getText().toString();
        assertEquals("No second section is added", stale, text);
        assertEquals(1, count(text, sectionTitle()));
        assertEquals(1, count(text, "SAVED_SECTION_MARKER"));
        assertEquals("...and nothing is read from the live diagnostics", 0, count(text, CANARY));
        controller.pause().stop().destroy();
    }

    @Test
    public void aStaleSectionWithAnUncheckedBoxIsStrippedBeforeTheFeedbackPageOpens() {
        ActivityController<SettingsActivity> controller = Robolectric
                .buildActivity(SettingsActivity.class)
                .setup(savedDialog(restoredDraftWithSection(), false));
        SettingsActivity activity = controller.get();
        AlertDialog dialog = activity.getActiveIssueReportDialog();
        EditText preview = preview(dialog);

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        assertEquals(0, count(preview.getText().toString(), sectionTitle()));
        assertFalse(preview.getText().toString().contains("SAVED_SECTION_MARKER"));
        assertFalse(dialog.isShowing());
        controller.pause().stop().destroy();
    }

    // --- the feedback button ----------------------------------------------------------------

    @Test
    public void theFeedbackButtonDismissesAndOpensOnlyTheStaticPageEvenWithOptIn() {
        ActivityController<SettingsActivity> controller = start();
        SettingsActivity activity = controller.get();
        AlertDialog dialog = openIssueDialog(activity);
        checkBox(dialog).performClick();
        assertTrue(preview(dialog).getText().toString().contains(CANARY));

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        Intent opened = shadowOf(activity).getNextStartedActivity();
        assertNotNull(opened);
        assertEquals(Intent.ACTION_VIEW, opened.getAction());
        assertEquals("Only the static feedback page, never the draft in the URL",
                KeepADBIssueReporter.FEEDBACK_URL, opened.getDataString());
        assertNull("Nothing from the draft rides along", opened.getExtras());
        assertNull(shadowOf(activity).getNextStartedActivity());
        assertFalse("The feedback button dismisses the dialog as before", dialog.isShowing());
        assertNull(activity.getActiveIssueReportDialog());
        assertNull(ShadowToast.getLatestToast());
        controller.pause().stop().destroy();
    }

    @Test
    public void aMissingBrowserOnTheFeedbackButtonIsCaughtThroughTheSharedPathAndStillDismisses() {
        shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true);
        ActivityController<SettingsActivity> controller = start();
        SettingsActivity activity = controller.get();
        AlertDialog dialog = openIssueDialog(activity);

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        assertEquals(activity.getString(R.string.settings_no_browser_found),
                ShadowToast.getTextOfLatestToast());
        assertNull(shadowOf(activity).getNextStartedActivity());
        assertFalse(dialog.isShowing());
        assertNull(activity.getActiveIssueReportDialog());
        controller.pause().stop().destroy();
    }

    @Test
    public void cancelDismissesWithoutSendingAnythingAndSavesNoDialogState() {
        ActivityController<SettingsActivity> controller = start();
        SettingsActivity activity = controller.get();
        AlertDialog dialog = openIssueDialog(activity);
        checkBox(dialog).performClick();

        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        ShadowLooper.idleMainLooper();

        assertFalse(dialog.isShowing());
        assertNull(activity.getActiveIssueReportDialog());
        assertNull(shadowOf(activity).getNextStartedActivity());
        Bundle state = new Bundle();
        controller.saveInstanceState(state);
        assertFalse("A dismissed dialog is not restored", state.containsKey("settings_issue_report_showing"));
        assertFalse(state.containsKey("settings_issue_report_draft"));
        assertFalse(state.containsKey("settings_issue_report_diagnostics"));
        controller.pause().stop().destroy();
    }

    // --- restore: the saved draft, the box before the listener --------------------------------

    @Test
    public void aRestoreWithTheBoxCheckedButNoSectionKeepsTheDraftAndReadsNoDiagnostics() {
        // The box is set before its listener is attached. Set after it, the listener would fire,
        // read the diagnostics now and add a section the saved draft never had.
        String draft = KeepADBIssueReporter.buildBody(context, false) + " " + HAND_TEXT;
        ActivityController<SettingsActivity> controller = Robolectric
                .buildActivity(SettingsActivity.class).setup(savedDialog(draft, true));
        SettingsActivity activity = controller.get();
        AlertDialog dialog = activity.getActiveIssueReportDialog();

        assertNotNull("The dialog is restored", dialog);
        assertTrue(dialog.isShowing());
        assertTrue("The saved box state is restored", checkBox(dialog).isChecked());
        String text = preview(dialog).getText().toString();
        assertEquals("The saved draft is used verbatim", draft, text);
        assertEquals("A restore reads no diagnostics into the draft", 0, count(text, CANARY));
        assertEquals(0, count(text, sectionTitle()));
        assertNull("A restore opens nothing", shadowOf(activity).getNextStartedActivity());

        // The listener is live afterwards: un-check and re-check behave as in a fresh dialog.
        checkBox(dialog).performClick();
        assertEquals(draft, preview(dialog).getText().toString());
        checkBox(dialog).performClick();
        String opted = preview(dialog).getText().toString();
        assertEquals(1, count(opted, sectionTitle()));
        assertEquals(1, count(opted, CANARY));
        controller.pause().stop().destroy();
    }

    @Test
    public void aRestoreWithTheSectionInTheDraftKeepsExactlyThatOneSection() {
        String draft = restoredDraftWithSection();
        ActivityController<SettingsActivity> controller = Robolectric
                .buildActivity(SettingsActivity.class).setup(savedDialog(draft, true));
        AlertDialog dialog = controller.get().getActiveIssueReportDialog();

        assertTrue(checkBox(dialog).isChecked());
        String text = preview(dialog).getText().toString();
        assertEquals(draft, text);
        assertEquals("No duplicate section", 1, count(text, sectionTitle()));
        assertEquals("The saved section is kept, not rebuilt from the current diagnostics", 1,
                count(text, "SAVED_SECTION_MARKER"));
        assertEquals(0, count(text, CANARY));

        // Un-check removes the restored section; the handwritten text stays.
        checkBox(dialog).performClick();
        String withdrawn = preview(dialog).getText().toString();
        assertEquals(0, count(withdrawn, sectionTitle()));
        assertEquals(0, count(withdrawn, "SAVED_SECTION_MARKER"));
        assertTrue(withdrawn.contains(HAND_TEXT));
        controller.pause().stop().destroy();
    }

    @Test
    public void aRestoreWithTheBoxUncheckedKeepsTheDraftWithoutAnySection() {
        String draft = KeepADBIssueReporter.buildBody(context, false) + " " + HAND_TEXT;
        ActivityController<SettingsActivity> controller = Robolectric
                .buildActivity(SettingsActivity.class).setup(savedDialog(draft, false));
        AlertDialog dialog = controller.get().getActiveIssueReportDialog();

        assertFalse(checkBox(dialog).isChecked());
        String text = preview(dialog).getText().toString();
        assertEquals(draft, text);
        assertEquals(0, count(text, CANARY));
        assertEquals(0, count(text, sectionTitle()));
        controller.pause().stop().destroy();
    }

    // --- rotation: handwritten text, the box and a single section survive ---------------------

    @Test
    public void rotationKeepsTheHandwrittenTextTheBoxAndExactlyOneSectionOverRepeatedRotations() {
        ActivityController<SettingsActivity> controller = start();
        AlertDialog dialog = openIssueDialog(controller.get());
        preview(dialog).setText(KeepADBIssueReporter.buildBody(context, false) + " " + HAND_TEXT);
        checkBox(dialog).performClick();
        String before = preview(dialog).getText().toString();
        assertEquals(1, count(before, sectionTitle()));

        for (int round = 1; round <= 2; round++) {
            controller = rotate(controller);
            dialog = controller.get().getActiveIssueReportDialog();
            assertNotNull("The dialog survives rotation " + round, dialog);
            assertTrue(checkBox(dialog).isChecked());
            String after = preview(dialog).getText().toString();
            assertEquals("The draft is the same text after rotation " + round, before, after);
            assertEquals("Still one section after rotation " + round, 1,
                    count(after, sectionTitle()));
            assertEquals(1, count(after, CANARY));
            assertTrue(after.contains(HAND_TEXT));
        }

        // After rotating, the opt-in can still be withdrawn: the section goes, the text stays.
        checkBox(dialog).performClick();
        String withdrawn = preview(dialog).getText().toString();
        assertEquals(0, count(withdrawn, sectionTitle()));
        assertEquals(0, count(withdrawn, CANARY));
        assertTrue(withdrawn.contains(HAND_TEXT));
        controller.pause().stop().destroy();
    }

    @Test
    public void rotationWithoutOptInKeepsTheHandwrittenTextAndStaysWithoutDiagnostics() {
        ActivityController<SettingsActivity> controller = start();
        AlertDialog dialog = openIssueDialog(controller.get());
        String edited = KeepADBIssueReporter.buildBody(context, false) + " " + HAND_TEXT;
        preview(dialog).setText(edited);

        controller = rotate(controller);
        dialog = controller.get().getActiveIssueReportDialog();

        assertNotNull(dialog);
        assertFalse(checkBox(dialog).isChecked());
        String after = preview(dialog).getText().toString();
        assertEquals(edited, after);
        assertEquals(0, count(after, CANARY));
        assertEquals(0, count(after, sectionTitle()));
        controller.pause().stop().destroy();
    }

    // --- destroy ----------------------------------------------------------------------------

    @Test
    public void destroyClosesTheDialogEvenWithAnOptedInDraft() {
        ActivityController<SettingsActivity> controller = start();
        SettingsActivity activity = controller.get();
        AlertDialog dialog = openIssueDialog(activity);
        checkBox(dialog).performClick();
        assertTrue(dialog.isShowing());

        controller.pause().stop().destroy();

        assertFalse("Destroy closes the dialog (no leaked window)", dialog.isShowing());
        assertNull("...and drops the reference", activity.getActiveIssueReportDialog());
    }

    // --- Bundle keys ------------------------------------------------------------------------

    @Test
    public void theBundleKeysAndTheSavedValuesAreTheSameAsBefore() {
        assertEquals("settings_issue_report_showing",
                KeepADBDiagnosticsController.STATE_ISSUE_REPORT_SHOWING);
        assertEquals("settings_issue_report_draft",
                KeepADBDiagnosticsController.STATE_ISSUE_REPORT_DRAFT);
        assertEquals("settings_issue_report_diagnostics",
                KeepADBDiagnosticsController.STATE_ISSUE_REPORT_DIAGNOSTICS);

        ActivityController<SettingsActivity> controller = start();
        Bundle idle = new Bundle();
        controller.saveInstanceState(idle);
        assertFalse("No dialog: no issue report key at all",
                idle.containsKey("settings_issue_report_showing"));
        assertFalse(idle.containsKey("settings_issue_report_draft"));
        assertFalse(idle.containsKey("settings_issue_report_diagnostics"));

        AlertDialog dialog = openIssueDialog(controller.get());
        checkBox(dialog).performClick();
        Bundle showing = new Bundle();
        controller.saveInstanceState(showing);
        assertTrue(showing.getBoolean("settings_issue_report_showing"));
        assertEquals(preview(dialog).getText().toString(),
                showing.getString("settings_issue_report_draft"));
        assertTrue(showing.getBoolean("settings_issue_report_diagnostics"));
        controller.pause().stop().destroy();
    }

    // --- the export button ------------------------------------------------------------------

    @Test
    public void theExportButtonSharesPlainTextAndRecordsEachRequestExactlyOnce() {
        ActivityController<SettingsActivity> controller = start();
        SettingsActivity activity = controller.get();

        activity.findViewById(R.id.settings_diagnostics_export).performClick();
        ShadowLooper.idleMainLooper();

        Intent chooser = shadowOf(activity).getNextStartedActivity();
        assertNotNull("The export starts the chooser", chooser);
        assertEquals(Intent.ACTION_CHOOSER, chooser.getAction());
        assertEquals(activity.getString(R.string.settings_diagnostics_export),
                chooser.getCharSequenceExtra(Intent.EXTRA_TITLE).toString());
        Intent send = sendIntent(chooser);
        assertEquals(Intent.ACTION_SEND, send.getAction());
        assertEquals("text/plain", send.getType());
        assertEquals(activity.getString(R.string.settings_diagnostics_export_subject),
                send.getStringExtra(Intent.EXTRA_SUBJECT));
        String first = send.getStringExtra(Intent.EXTRA_TEXT);
        assertTrue(first.startsWith(KeepADBDiagnostics.EXPORT_HEADER));
        assertEquals("The raw export carries the recorded diagnostics", 1, count(first, CANARY));
        assertEquals("The request is recorded before the export is read", 1,
                count(first, EXPORT_EVENT));
        assertNull("The export is not the feedback dialog", activity.getActiveIssueReportDialog());
        assertNull(shadowOf(activity).getNextStartedActivity());

        activity.findViewById(R.id.settings_diagnostics_export).performClick();
        ShadowLooper.idleMainLooper();
        String second = sendIntent(shadowOf(activity).getNextStartedActivity())
                .getStringExtra(Intent.EXTRA_TEXT);
        assertEquals("One event per request", 2, count(second, EXPORT_EVENT));
        controller.pause().stop().destroy();
    }

    // --- helpers ----------------------------------------------------------------------------

    private static ActivityController<SettingsActivity> start() {
        return Robolectric.buildActivity(SettingsActivity.class).setup();
    }

    /** Taps the real "report a problem" button and lets the dialog's show listener run. */
    private static AlertDialog openIssueDialog(SettingsActivity activity) {
        activity.findViewById(R.id.settings_issue_report).performClick();
        ShadowLooper.idleMainLooper();
        AlertDialog dialog = activity.getActiveIssueReportDialog();
        assertNotNull("The feedback report dialog must be showing", dialog);
        assertTrue(dialog.isShowing());
        return dialog;
    }

    /** Same as a real rotation: save, destroy, a fresh instance restored from the bundle. */
    private static ActivityController<SettingsActivity> rotate(
            ActivityController<SettingsActivity> controller) {
        Bundle state = new Bundle();
        controller.saveInstanceState(state);
        controller.pause().stop().destroy();
        ShadowLooper.idleMainLooper();
        ActivityController<SettingsActivity> restored =
                Robolectric.buildActivity(SettingsActivity.class).setup(state);
        ShadowLooper.idleMainLooper();
        return restored;
    }

    private static Bundle savedDialog(String draft, boolean includeDiagnostics) {
        Bundle state = new Bundle();
        state.putBoolean(KeepADBDiagnosticsController.STATE_ISSUE_REPORT_SHOWING, true);
        state.putString(KeepADBDiagnosticsController.STATE_ISSUE_REPORT_DRAFT, draft);
        state.putBoolean(KeepADBDiagnosticsController.STATE_ISSUE_REPORT_DIAGNOSTICS,
                includeDiagnostics);
        return state;
    }

    /** A saved draft that carries its own diagnostics section, distinct from the live export. */
    private String restoredDraftWithSection() {
        String body = KeepADBIssueReporter.buildBody(context, false) + " " + HAND_TEXT;
        return KeepADBIssueReporter.addDiagnosticsSection(body,
                sectionTitle() + "\n\nSAVED_SECTION_MARKER");
    }

    private String sectionTitle() {
        return context.getString(R.string.issue_report_diagnostics_section);
    }

    private static Intent sendIntent(Intent chooser) {
        assertNotNull(chooser);
        Intent send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
        assertNotNull("The chooser wraps the send intent", send);
        return send;
    }

    private static EditText preview(AlertDialog dialog) {
        return first(dialog.getWindow().getDecorView(), EditText.class);
    }

    private static CheckBox checkBox(AlertDialog dialog) {
        return first(dialog.getWindow().getDecorView(), CheckBox.class);
    }

    private static <T extends View> T first(View root, Class<T> type) {
        List<T> found = new ArrayList<>();
        collect(root, type, found);
        assertFalse("No " + type.getSimpleName() + " in the dialog", found.isEmpty());
        return found.get(0);
    }

    @SuppressWarnings("unchecked")
    private static <T extends View> void collect(View view, Class<T> type, List<T> found) {
        if (type.isInstance(view)) found.add((T) view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                collect(group.getChildAt(index), type, found);
            }
        }
    }

    private static int count(String text, String part) {
        int count = 0;
        for (int index = text.indexOf(part); index >= 0; index = text.indexOf(part, index + 1)) {
            count++;
        }
        return count;
    }
}
