package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import static org.robolectric.Shadows.shadowOf;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

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
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowWifiInfo;

/**
 * Unit and behavioral tests for {@link SettingsActivity}, covering dialog scrollability,
 * TalkBack context labels, draft persistence across activity recreation, and dialog cleanup (Issue #322).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SettingsActivityTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    @Before
    public void setUp() {
        RuntimeEnvironment.getApplication()
                .getSharedPreferences("keepadb_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
    }

    @After
    public void tearDown() {
        RuntimeEnvironment.getApplication()
                .getSharedPreferences("keepadb_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
        KeepADB.resetForTesting();
        KeepADBRegisterClient.resetHttpTransport();
        KeepADBEndpointCoordinator.resetForTesting();
    }

    /** #677: every main card header announces its expanded/collapsed state to TalkBack. */
    @Test
    public void mainCardHeadersKeepStateDescriptionInSyncWithExpandedState() {
        KeepADBPreferences.setAppLanguage(RuntimeEnvironment.getApplication(), "en");
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        int[] headers = {R.id.settings_webhook_header, R.id.settings_usb_adb_header,
                R.id.settings_network_beta_header, R.id.settings_misc_header,
                R.id.settings_diagnostics_header};
        for (int id : headers) {
            View header = activity.findViewById(id);
            assertEquals("Collapsed initially", "Collapsed", String.valueOf(header.getStateDescription()));
            header.performClick();
            assertEquals("Expanded after click", "Expanded", String.valueOf(header.getStateDescription()));
            header.performClick();
            assertEquals("Collapsed again", "Collapsed", String.valueOf(header.getStateDescription()));
        }
    }

    /**
     * #592: the opt-in switch is off by default, persists the preference and re-renders an
     * already visible USB card through the existing KeepADBUsbReceiver.refresh path.
     */
    @Test
    public void notificationDetailsToggleDefaultsOffAndRerendersTheVisibleUsbCard() {
        android.app.Application app = RuntimeEnvironment.getApplication();
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
        app.sendStickyBroadcast(new Intent(KeepADBUsbReceiver.ACTION_USB_STATE)
                .putExtra("connected", true)
                .putExtra("configured", true)
                .putExtra("adb", true));
        KeepADBPreferences.setAppLanguage(app, "en");
        KeepADBUsbProfile.setNotificationEnabled(app, true);
        KeepADBUsbProfile.add(app, "TestHost", "10.0.0.99", "testhost.local", "");
        KeepADBUsbReceiver.refresh(app);

        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        Switch toggle = activity.findViewById(R.id.settings_notification_details_toggle);
        assertNotNull(toggle);
        assertFalse("Details must be off by default", toggle.isChecked());
        KeepADBNotificationTextScan.assertMentionsNone(postedUsbCard(app), "TestHost", "10.0.0.99");

        toggle.performClick();
        assertTrue(KeepADBPreferences.isNotificationDetailsEnabled(app));
        CharSequence text = postedUsbCard(app).extras.getCharSequence(
                android.app.Notification.EXTRA_TEXT);
        assertTrue("The visible card must be re-rendered with details: " + text,
                String.valueOf(text).contains("TestHost"));

        toggle.performClick();
        assertFalse(KeepADBPreferences.isNotificationDetailsEnabled(app));
        KeepADBNotificationTextScan.assertMentionsNone(postedUsbCard(app), "TestHost", "10.0.0.99");
        KeepADBUsbNotification.cancel(app);
    }

    private static android.app.Notification postedUsbCard(android.content.Context context) {
        android.app.NotificationManager manager =
                context.getSystemService(android.app.NotificationManager.class);
        return shadowOf(manager).getNotification(KeepADBUsbNotification.NOTIFICATION_ID);
    }

    /**
     * #597: the persistent main notification also gates its port/IP endpoint text behind
     * {@code notification_details_enabled} now, so it needs the same "toggle re-renders the
     * already-visible notification" wiring the USB card got in #592.
     */
    @Test
    public void notificationDetailsToggleAlsoRerendersTheVisibleMainNotification() throws Exception {
        android.app.Application app = RuntimeEnvironment.getApplication();
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
        KeepADBPreferences.setAppLanguage(app, "en");
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(true);
        KeepADB.setGatewayForTesting(gateway);
        KeepADBPreferences.setKeepAliveEnabled(app, true);
        setCoordinatorEndpoint("192.168.1.50", 39123);
        KeepADBEndpointCoordinator.refresh(app);

        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        Switch toggle = activity.findViewById(R.id.settings_notification_details_toggle);
        assertNotNull(toggle);
        assertFalse("Details must be off by default", toggle.isChecked());
        KeepADBNotificationTextScan.assertMentionsNone(postedMainNotification(app), "39123", "192.168.1.50");

        toggle.performClick();
        assertTrue(KeepADBPreferences.isNotificationDetailsEnabled(app));
        CharSequence textOn = postedMainNotification(app).extras.getCharSequence(
                android.app.Notification.EXTRA_TEXT);
        assertTrue("The visible main notification must be re-rendered with the endpoint: " + textOn,
                String.valueOf(textOn).contains("39123") && String.valueOf(textOn).contains("192.168.1.50"));

        toggle.performClick();
        assertFalse(KeepADBPreferences.isNotificationDetailsEnabled(app));
        KeepADBNotificationTextScan.assertMentionsNone(postedMainNotification(app), "39123", "192.168.1.50");
    }

    private static android.app.Notification postedMainNotification(android.content.Context context) {
        android.app.NotificationManager manager =
                context.getSystemService(android.app.NotificationManager.class);
        return shadowOf(manager).getNotification(KeepADBNotification.NOTIFICATION_ID);
    }

    /** Seeds {@link KeepADBEndpointCoordinator}'s cached endpoint the way a completed discovery
     * would, without driving the full mDNS/socket discovery flow -- same seam
     * {@link KeepADBNotificationRobolectricTest} already uses for this. */
    private static void setCoordinatorEndpoint(String host, int port) throws Exception {
        java.lang.reflect.Field hostField = KeepADBEndpointCoordinator.class.getDeclaredField("currentHost");
        hostField.setAccessible(true);
        hostField.set(null, host);
        java.lang.reflect.Field portField = KeepADBEndpointCoordinator.class.getDeclaredField("currentPort");
        portField.setAccessible(true);
        portField.set(null, port);
    }

    @Test
    public void dialogContainersAreWrappedInScrollView() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();

        // 1. Profile switch dialog (the trusted-network manage dialog's own ScrollView wrapping
        // is covered by MainActivityTrustedNetworkTest -- that dialog moved there for #485).
        KeepADBUsbProfile.add(activity, "Test-Profile", "10.0.0.1", "host", "tail");
        ShadowLooper.idleMainLooper();
        activity.findViewById(R.id.settings_usb_profile_action).performClick();
        ShadowLooper.idleMainLooper();
        AlertDialog switchDialog = activity.getActiveSwitchProfileDialog();
        assertNotNull("Profile switch dialog should be showing", switchDialog);
        assertTrue(switchDialog.isShowing());
        ScrollView switchScroll = findViewByType(switchDialog.findViewById(android.R.id.custom), ScrollView.class);
        assertNotNull("Profile switch dialog options must be wrapped in a ScrollView", switchScroll);

        // 3. Profile edit dialog (opened via positive 'New' button)
        switchDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        AlertDialog editDialog = activity.getActiveProfileEditDialog();
        assertNotNull("Profile edit dialog should be showing", editDialog);
        assertTrue(editDialog.isShowing());
        ScrollView editScroll = findViewByType(editDialog.findViewById(android.R.id.custom), ScrollView.class);
        assertNotNull("Profile edit dialog fields must be wrapped in a ScrollView", editScroll);
        editDialog.dismiss();
        ShadowLooper.idleMainLooper();
    }

    /**
     * #593: the switch-profile dialog row must show name and endpoint details as two separate
     * TextViews (name on its own line, "IP · Host · Tailnet" underneath) instead of one long
     * radio label, and the Edit/Delete buttons must no longer share the radio button's row --
     * both changes avoid the character-by-character wrap a long profile summary used to force.
     */
    @Test
    public void switchProfileDialogRowSplitsNameAndDetailsAndMovesActionsOffTheSelectionRow() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();

        KeepADBUsbProfile.add(activity, "LongHostName", "10.20.30.40", "longhostname.example.local",
                "longhostname.tailnet.example.net");
        ShadowLooper.idleMainLooper();
        activity.findViewById(R.id.settings_usb_profile_action).performClick();
        ShadowLooper.idleMainLooper();
        AlertDialog switchDialog = activity.getActiveSwitchProfileDialog();
        assertNotNull("Profile switch dialog should be showing", switchDialog);
        ScrollView switchScroll = findViewByType(switchDialog.findViewById(android.R.id.custom), ScrollView.class);
        assertNotNull(switchScroll);

        KeepADBUsbProfile.Profile profile = KeepADBUsbProfile.getProfiles(activity).get(0);
        List<TextView> textViews = findViewsByType(switchScroll, TextView.class);
        boolean nameOnOwnLine = false;
        boolean detailsOnOwnLine = false;
        boolean fullSummaryAsSingleLine = false;
        for (TextView tv : textViews) {
            String text = tv.getText().toString();
            if (text.equals(profile.name)) nameOnOwnLine = true;
            if (text.equals(profile.details())) detailsOnOwnLine = true;
            if (text.equals(profile.summary())) fullSummaryAsSingleLine = true;
        }
        assertTrue("Profile name must appear as its own TextView", nameOnOwnLine);
        assertTrue("Profile details (IP/host/tailnet) must appear as their own TextView",
                detailsOnOwnLine);
        assertFalse("Name and details must no longer be a single concatenated label",
                fullSummaryAsSingleLine);

        List<android.widget.RadioButton> radios =
                findViewsByType(switchScroll, android.widget.RadioButton.class);
        assertEquals(1, radios.size());
        android.widget.RadioButton radio = radios.get(0);
        assertEquals("The radio button's accessibility description must still carry the full "
                        + "summary for screen readers", profile.summary(),
                radio.getContentDescription().toString());

        // The Edit/Delete buttons must not be direct siblings of the radio button anymore --
        // that row used to force the whole line into a character-wide wrap on long summaries.
        // (RadioButton is itself a Button subclass, so it is excluded from this check.)
        ViewGroup selectionRow = (ViewGroup) radio.getParent();
        List<Button> buttonsInSelectionRow = findViewsByType(selectionRow, Button.class);
        buttonsInSelectionRow.remove(radio);
        assertTrue("Edit/Delete must not share the radio button's row",
                buttonsInSelectionRow.isEmpty());

        List<Button> allButtons = findViewsByType(switchScroll, Button.class);
        Button editButton = findButtonWithText(allButtons,
                activity.getString(R.string.usb_profile_edit_button));
        Button deleteButton = findButtonWithText(allButtons,
                activity.getString(R.string.usb_profile_delete_button));
        assertNotNull(editButton);
        assertNotNull(deleteButton);
        assertNotEquals("Edit button must be in a different row than the radio button",
                selectionRow, editButton.getParent());
        assertNotEquals("Delete button must be in a different row than the radio button",
                selectionRow, deleteButton.getParent());

        switchDialog.dismiss();
        ShadowLooper.idleMainLooper();
    }

    /**
     * #593: after splitting the radio label into separate TextViews, selecting a profile must
     * still work both from the whole selection row (the text beside the radio) and from the radio
     * itself, and must close the dialog.
     */
    @Test
    public void switchProfileDialogSelectsFromTheRowAndFromTheRadio() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();
        KeepADBUsbProfile.Profile first = KeepADBUsbProfile.add(activity, "First", "10.0.0.1", "", "");
        KeepADBUsbProfile.Profile second = KeepADBUsbProfile.add(activity, "Second", "10.0.0.2", "", "");
        assertEquals(second.id, KeepADBUsbProfile.getSelected(activity).id);

        android.widget.RadioButton firstRadio = openSwitchDialogRadios(activity).get(0);
        ((View) firstRadio.getParent()).performClick();
        ShadowLooper.idleMainLooper();
        assertEquals(first.id, KeepADBUsbProfile.getSelected(activity).id);
        assertNull("Row click must close the dialog", activity.getActiveSwitchProfileDialog());

        android.widget.RadioButton secondRadio = openSwitchDialogRadios(activity).get(1);
        secondRadio.performClick();
        ShadowLooper.idleMainLooper();
        assertEquals(second.id, KeepADBUsbProfile.getSelected(activity).id);
        assertNull("Radio click must close the dialog", activity.getActiveSwitchProfileDialog());
    }

    /**
     * #595 AC5: the USB notification's profile actions reach SettingsActivity as an intent extra
     * that onResume() hands to the extracted profile editor. SWITCH (with existing profiles) must
     * open the switch list, CREATE the edit dialog; the extra is consumed, so a later resume does
     * not reopen the dialog.
     */
    @Test
    public void profileActionIntentExtraOpensTheMatchingDialogOnceAndIsConsumed() {
        android.content.Context app = RuntimeEnvironment.getApplication();
        KeepADBUsbProfile.add(app, "Desk", "10.0.0.1", "", "");

        Intent switchIntent = new Intent(app, SettingsActivity.class)
                .putExtra(KeepADBUsbNotification.EXTRA_PROFILE_ACTION, KeepADBUsbNotification.ACTION_SWITCH);
        ActivityController<SettingsActivity> switchController =
                Robolectric.buildActivity(SettingsActivity.class, switchIntent).setup();
        SettingsActivity switchActivity = switchController.get();
        ShadowLooper.idleMainLooper();
        AlertDialog switchDialog = switchActivity.getActiveSwitchProfileDialog();
        assertNotNull("SWITCH must open the profile switch list", switchDialog);
        assertNull(switchActivity.getActiveProfileEditDialog());
        assertFalse("The extra must be consumed",
                switchActivity.getIntent().hasExtra(KeepADBUsbNotification.EXTRA_PROFILE_ACTION));
        switchDialog.dismiss();
        ShadowLooper.idleMainLooper();
        switchController.pause().resume();
        ShadowLooper.idleMainLooper();
        assertNull("A later resume must not reopen the dialog",
                switchActivity.getActiveSwitchProfileDialog());
        switchController.pause().stop().destroy();

        Intent createIntent = new Intent(app, SettingsActivity.class)
                .putExtra(KeepADBUsbNotification.EXTRA_PROFILE_ACTION, KeepADBUsbNotification.ACTION_CREATE);
        ActivityController<SettingsActivity> createController =
                Robolectric.buildActivity(SettingsActivity.class, createIntent).setup();
        SettingsActivity createActivity = createController.get();
        ShadowLooper.idleMainLooper();
        assertNotNull("CREATE must open the edit dialog", createActivity.getActiveProfileEditDialog());
        assertNull(createActivity.getActiveSwitchProfileDialog());
        createController.pause().stop().destroy();
    }

    private List<android.widget.RadioButton> openSwitchDialogRadios(SettingsActivity activity) {
        activity.findViewById(R.id.settings_usb_profile_action).performClick();
        ShadowLooper.idleMainLooper();
        AlertDialog dialog = activity.getActiveSwitchProfileDialog();
        assertNotNull("Profile switch dialog should be showing", dialog);
        List<android.widget.RadioButton> radios = findViewsByType(
                dialog.findViewById(android.R.id.custom), android.widget.RadioButton.class);
        assertEquals(2, radios.size());
        return radios;
    }

    @Test
    public void actionButtonsHaveContextualAccessibilityDescriptions() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();

        // Trusted network delete button's contextual content description is covered by
        // MainActivityTrustedNetworkTest -- the manage dialog moved there for #485.

        // Profile edit & delete buttons
        KeepADBUsbProfile.add(activity, "ProductionHost", "10.0.0.5", "prod.local", "prod.tail");
        ShadowLooper.idleMainLooper();
        activity.findViewById(R.id.settings_usb_profile_action).performClick();
        ShadowLooper.idleMainLooper();
        AlertDialog switchDialog = activity.getActiveSwitchProfileDialog();
        assertNotNull("Profile switch dialog should be showing", switchDialog);
        ScrollView switchScroll = findViewByType(switchDialog.findViewById(android.R.id.custom), ScrollView.class);
        assertNotNull(switchScroll);
        List<Button> profileButtons = findViewsByType(switchScroll, Button.class);
        Button editButton = null;
        Button deleteButton = null;
        for (Button b : profileButtons) {
            if (activity.getString(R.string.usb_profile_edit_button).equals(b.getText().toString())) {
                editButton = b;
            } else if (activity.getString(R.string.usb_profile_delete_button).equals(b.getText().toString())) {
                deleteButton = b;
            }
        }
        assertNotNull("Profile edit button must be present", editButton);
        assertNotNull("Profile delete button must be present", deleteButton);
        assertEquals("Profile edit button must set contextual content description with profile name",
                activity.getString(R.string.usb_profile_edit_action_accessibility, "ProductionHost"),
                editButton.getContentDescription());
        assertEquals("Profile delete button must set contextual content description with profile name",
                activity.getString(R.string.usb_profile_delete_action_accessibility, "ProductionHost"),
                deleteButton.getContentDescription());
        switchDialog.dismiss();
        ShadowLooper.idleMainLooper();
    }

    @Test
    public void issueReportPrivacyNoticeIsNotTruncated() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();

        activity.findViewById(R.id.settings_issue_report).performClick();
        AlertDialog dialog = activity.getActiveIssueReportDialog();
        assertNotNull("Issue report dialog should be showing", dialog);

        List<TextView> textViews = findViewsByType(dialog.getWindow().getDecorView(), TextView.class);
        TextView intro = null;
        String expectedMessage = activity.getString(R.string.settings_issue_report_dialog_message);
        for (TextView tv : textViews) {
            if (expectedMessage.equals(tv.getText().toString())) {
                intro = tv;
                break;
            }
        }
        assertNotNull("Privacy notice TextView must be present in issue dialog", intro);
        assertNotEquals("Privacy notice must not be limited to 3 lines", 3, intro.getMaxLines());
        assertNull("Privacy notice must not ellipsize", intro.getEllipsize());

        dialog.dismiss();
        ShadowLooper.idleMainLooper();
    }

    @Test
    public void issueReportDraftSavesAndRestoresAcrossRecreation() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();

        // Open issue report dialog
        activity.findViewById(R.id.settings_issue_report).performClick();
        AlertDialog dialog = activity.getActiveIssueReportDialog();
        assertNotNull("Issue report dialog should be showing", dialog);
        assertTrue("Dialog must be showing", dialog.isShowing());

        Bundle savedState = new Bundle();
        controller.saveInstanceState(savedState);

        assertTrue("Saved state must record issue report is showing",
                savedState.getBoolean(KeepADBDiagnosticsController.STATE_ISSUE_REPORT_SHOWING));
        assertNotNull("Saved state must contain draft body",
                savedState.getString(KeepADBDiagnosticsController.STATE_ISSUE_REPORT_DRAFT));

        // Dismissing clears active reference
        dialog.dismiss();
        ShadowLooper.idleMainLooper();
        assertNull("Active dialog reference must be cleared after dismissal",
                activity.getActiveIssueReportDialog());

        Bundle afterDismissState = new Bundle();
        controller.saveInstanceState(afterDismissState);
        assertFalse("Saved state must not report dialog showing after dismiss",
                afterDismissState.getBoolean(KeepADBDiagnosticsController.STATE_ISSUE_REPORT_SHOWING));

        // Now test restoration with customized draft values
        Bundle restoreBundle = new Bundle();
        restoreBundle.putBoolean(KeepADBDiagnosticsController.STATE_ISSUE_REPORT_SHOWING, true);
        restoreBundle.putString(KeepADBDiagnosticsController.STATE_ISSUE_REPORT_DRAFT, "Restored draft problem description");
        restoreBundle.putBoolean(KeepADBDiagnosticsController.STATE_ISSUE_REPORT_DIAGNOSTICS, true);

        ActivityController<SettingsActivity> restoredController =
                Robolectric.buildActivity(SettingsActivity.class).setup(restoreBundle);
        SettingsActivity restoredActivity = restoredController.get();

        AlertDialog restoredDialog = restoredActivity.getActiveIssueReportDialog();
        assertNotNull("Issue report dialog should be restored on recreation", restoredDialog);
        assertTrue("Restored dialog must be showing", restoredDialog.isShowing());

        // Save state again on restored activity and verify preserved values
        Bundle reSavedState = new Bundle();
        restoredController.saveInstanceState(reSavedState);
        assertEquals("Restored draft problem description",
                reSavedState.getString(KeepADBDiagnosticsController.STATE_ISSUE_REPORT_DRAFT));
        assertTrue(reSavedState.getBoolean(KeepADBDiagnosticsController.STATE_ISSUE_REPORT_DIAGNOSTICS));

        restoredDialog.dismiss();
        ShadowLooper.idleMainLooper();
    }

    @Test
    public void profileEditDraftSavesAndRestoresAcrossRecreation() {
        Bundle restoreBundle = new Bundle();
        restoreBundle.putBoolean(KeepADBUsbProfileEditor.STATE_PROFILE_EDIT_SHOWING, true);
        restoreBundle.putInt(KeepADBUsbProfileEditor.STATE_PROFILE_EDIT_ID, -1);
        restoreBundle.putString(KeepADBUsbProfileEditor.STATE_PROFILE_EDIT_NAME, "My Workstation");
        restoreBundle.putString(KeepADBUsbProfileEditor.STATE_PROFILE_EDIT_IP, "192.168.1.100");
        restoreBundle.putString(KeepADBUsbProfileEditor.STATE_PROFILE_EDIT_HOSTNAME, "workstation.local");
        restoreBundle.putString(KeepADBUsbProfileEditor.STATE_PROFILE_EDIT_TAILNET, "workstation.tailnet");

        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup(restoreBundle);
        SettingsActivity activity = controller.get();

        AlertDialog dialog = activity.getActiveProfileEditDialog();
        assertNotNull("Profile edit dialog should be restored on recreation", dialog);
        assertTrue("Restored profile edit dialog must be showing", dialog.isShowing());

        Bundle reSavedState = new Bundle();
        controller.saveInstanceState(reSavedState);
        assertTrue(reSavedState.getBoolean(KeepADBUsbProfileEditor.STATE_PROFILE_EDIT_SHOWING));
        assertEquals(-1, reSavedState.getInt(KeepADBUsbProfileEditor.STATE_PROFILE_EDIT_ID));
        assertEquals("My Workstation", reSavedState.getString(KeepADBUsbProfileEditor.STATE_PROFILE_EDIT_NAME));
        assertEquals("192.168.1.100", reSavedState.getString(KeepADBUsbProfileEditor.STATE_PROFILE_EDIT_IP));
        assertEquals("workstation.local", reSavedState.getString(KeepADBUsbProfileEditor.STATE_PROFILE_EDIT_HOSTNAME));
        assertEquals("workstation.tailnet", reSavedState.getString(KeepADBUsbProfileEditor.STATE_PROFILE_EDIT_TAILNET));

        dialog.dismiss();
        ShadowLooper.idleMainLooper();
        assertNull("Active profile edit dialog reference must be cleared after dismissal",
                activity.getActiveProfileEditDialog());

        Bundle afterDismissState = new Bundle();
        controller.saveInstanceState(afterDismissState);
        assertFalse(afterDismissState.getBoolean(KeepADBUsbProfileEditor.STATE_PROFILE_EDIT_SHOWING));
    }

    /**
     * #579 (UI-02): an unsaved webhook URL typed into the field must survive a real activity
     * recreation (saveInstanceState -> new instance -> setup(bundle)) instead of being replaced by
     * the saved preference value in onResume().
     *
     * <p>#596: also pins the rest of what {@code KeepADBSettingsWebhookDraftContractTest}'s
     * removed {@code lifecycleRestorationDoesNotPersistOrToggleTheDraft} source-content check used
     * to (only) assert by grepping {@code onResume()}'s body for absent method calls -- that
     * restoring a draft must not toggle the persisted enabled flag, and must not trigger a webhook
     * network request either. The enabled flag and the fake transport's recorded requests are both
     * observable side effects of the exact two calls that check used to grep for
     * ({@code setRegisterWebhookEnabled}/{@code unregisterAndDisableAsync}); driving the real
     * lifecycle end-to-end and observing their absence is equivalent proof without depending on
     * those specific method names.
     */
    @Test
    public void unsavedWebhookDraftSurvivesActivityRecreation() {
        String savedUrl = "https://saved.example/register/device";
        String unsavedDraft = "https://draft.example/register/other";
        android.content.Context context = RuntimeEnvironment.getApplication();
        KeepADBPreferences.setRegisterWebhookUrl(context, savedUrl);
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        KeepADBFakeHttpTransport transport = new KeepADBFakeHttpTransport();
        KeepADBRegisterClient.setHttpTransport(transport);

        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        EditText input = controller.get().findViewById(R.id.settings_webhook_url);
        assertEquals(savedUrl, input.getText().toString());

        input.setText(unsavedDraft);
        Bundle savedState = new Bundle();
        controller.saveInstanceState(savedState);
        controller.pause().stop().destroy();

        ActivityController<SettingsActivity> recreated =
                Robolectric.buildActivity(SettingsActivity.class).setup(savedState);
        EditText restoredInput = recreated.get().findViewById(R.id.settings_webhook_url);
        assertEquals("Unsaved webhook draft must survive recreation",
                unsavedDraft, restoredInput.getText().toString());
        assertEquals("Recreation must not persist the draft",
                savedUrl, KeepADBPreferences.getRegisterWebhookUrl(context));
        assertTrue("Recreation must not toggle the webhook enabled flag",
                KeepADBPreferences.isRegisterWebhookEnabled(context));
        assertTrue("Recreation must not trigger any webhook network request",
                transport.recordedRequests.isEmpty());
        recreated.pause().stop().destroy();
    }

    /**
     * #596: the empty-draft counterpart of {@link #unsavedWebhookDraftSurvivesActivityRecreation()}
     * -- an explicitly cleared field is itself a draft (distinct from "never touched", which would
     * fall back to the saved URL) and must survive recreation the same way, not be silently
     * replaced by the saved preference value.
     */
    @Test
    public void emptyWebhookDraftSurvivesActivityRecreation() {
        String savedUrl = "https://saved.example/register/device";
        android.content.Context context = RuntimeEnvironment.getApplication();
        KeepADBPreferences.setRegisterWebhookUrl(context, savedUrl);
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        KeepADBFakeHttpTransport transport = new KeepADBFakeHttpTransport();
        KeepADBRegisterClient.setHttpTransport(transport);

        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        EditText input = controller.get().findViewById(R.id.settings_webhook_url);
        assertEquals(savedUrl, input.getText().toString());

        input.setText("");
        Bundle savedState = new Bundle();
        controller.saveInstanceState(savedState);
        controller.pause().stop().destroy();

        ActivityController<SettingsActivity> recreated =
                Robolectric.buildActivity(SettingsActivity.class).setup(savedState);
        EditText restoredInput = recreated.get().findViewById(R.id.settings_webhook_url);
        assertEquals("An explicitly cleared draft must survive recreation as empty, not fall back "
                        + "to the saved URL", "", restoredInput.getText().toString());
        assertEquals("Recreation must not persist the empty draft",
                savedUrl, KeepADBPreferences.getRegisterWebhookUrl(context));
        assertTrue("Recreation must not toggle the webhook enabled flag",
                KeepADBPreferences.isRegisterWebhookEnabled(context));
        assertTrue("Recreation must not trigger any webhook network request",
                transport.recordedRequests.isEmpty());
        recreated.pause().stop().destroy();
    }

    @Test
    public void dialogsDismissOnDestroyToPreventWindowAndContextLeaks() {
        // Test Issue Report Dialog dismissal on destroy
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();

        activity.findViewById(R.id.settings_issue_report).performClick();
        AlertDialog issueDialog = activity.getActiveIssueReportDialog();
        assertNotNull(issueDialog);
        assertTrue(issueDialog.isShowing());

        controller.pause().stop().destroy();
        assertFalse("Issue report dialog must be dismissed on destroy to prevent window leak",
                issueDialog.isShowing());
        assertNull("Active issue report dialog reference must be cleared",
                activity.getActiveIssueReportDialog());

        // Test Profile Edit Dialog dismissal on destroy
        Bundle editBundle = new Bundle();
        editBundle.putBoolean(KeepADBUsbProfileEditor.STATE_PROFILE_EDIT_SHOWING, true);
        ActivityController<SettingsActivity> profileController =
                Robolectric.buildActivity(SettingsActivity.class).setup(editBundle);
        SettingsActivity profileActivity = profileController.get();

        AlertDialog editDialog = profileActivity.getActiveProfileEditDialog();
        assertNotNull(editDialog);
        assertTrue(editDialog.isShowing());

        profileController.pause().stop().destroy();
        assertFalse("Profile edit dialog must be dismissed on destroy to prevent window leak",
                editDialog.isShowing());
        assertNull("Active profile edit dialog reference must be cleared",
                profileActivity.getActiveProfileEditDialog());
    }

    @Test
    public void languageAndHandoverSelectorsHaveFormattedAccessibilityDescriptions() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();

        View languageSelector = activity.findViewById(R.id.settings_language_toolbar_button);
        assertNotNull(languageSelector);
        String currentLanguageTag = KeepADBLocaleHelper.getSelectedLanguageTag(activity);
        String languageDisplayName = KeepADBLocaleHelper.getLanguageDisplayName(activity, currentLanguageTag);
        assertEquals(activity.getString(R.string.settings_language_accessibility, languageDisplayName),
                languageSelector.getContentDescription());

        View handoverSelector = activity.findViewById(R.id.settings_usb_handover_selector);
        assertNotNull(handoverSelector);
        assertEquals(activity.getString(R.string.settings_usb_handover_accessibility,
                        activity.getString(R.string.settings_usb_handover_mode_off)),
                handoverSelector.getContentDescription());
    }

    @Test
    public void languageSelectionTriggersUsbNotificationRefresh() {
        org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication())
                .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();

        KeepADBUsbProfile.setNotificationEnabled(activity, true);

        android.content.Intent stickyUsbState = new android.content.Intent(KeepADBUsbReceiver.ACTION_USB_STATE)
                .putExtra("connected", true)
                .putExtra("configured", true)
                .putExtra("adb", true);
        RuntimeEnvironment.getApplication().sendStickyBroadcast(stickyUsbState);

        activity.findViewById(R.id.settings_language_toolbar_button).performClick();
        ShadowLooper.idleMainLooper();

        AlertDialog dialog = (AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertNotNull("Language selection dialog should be showing", dialog);
        assertTrue(dialog.isShowing());

        int deIndex = -1;
        for (int i = 0; i < KeepADBLocaleHelper.SUPPORTED_LANGUAGES.length; i++) {
            if ("de".equals(KeepADBLocaleHelper.SUPPORTED_LANGUAGES[i].tag)) {
                deIndex = i;
                break;
            }
        }
        assertTrue(deIndex >= 0);

        dialog.getListView().performItemClick(
                dialog.getListView().getChildAt(deIndex),
                deIndex,
                dialog.getListView().getAdapter().getItemId(deIndex));
        ShadowLooper.idleMainLooper();

        android.app.NotificationManager manager = activity.getSystemService(android.app.NotificationManager.class);
        android.app.Notification notification = org.robolectric.Shadows.shadowOf(manager)
                .getNotification(KeepADBUsbNotification.NOTIFICATION_ID);
        assertNotNull("Active USB notification should be refreshed on language change", notification);
    }

    @Test
    public void liveCleartextWarningAppearsOnTypingHttp() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();

        EditText input = activity.findViewById(R.id.settings_webhook_url);
        TextView warning = activity.findViewById(R.id.settings_webhook_cleartext_warning);
        assertNotNull(input);
        assertNotNull(warning);

        assertEquals(View.GONE, warning.getVisibility());

        input.setText("http://");
        assertEquals(View.VISIBLE, warning.getVisibility());

        input.setText("http://example.com/endpoint");
        assertEquals(View.VISIBLE, warning.getVisibility());

        input.setText("HTTP://100.111.111.21:50829/register/s20");
        assertEquals(View.VISIBLE, warning.getVisibility());

        input.setText("https://example.com/endpoint");
        assertEquals(View.GONE, warning.getVisibility());

        input.setText("");
        assertEquals(View.GONE, warning.getVisibility());
    }

    @Test
    public void savingUrlWithCredentialsStripsUserinfo() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();

        EditText input = activity.findViewById(R.id.settings_webhook_url);
        Button saveButton = activity.findViewById(R.id.settings_webhook_save);
        Switch toggle = activity.findViewById(R.id.settings_webhook_toggle);
        assertNotNull(input);
        assertNotNull(saveButton);
        assertNotNull(toggle);

        // Test save button sanitization
        input.setText("http://user:secret@100.111.111.21:50829/register/s20#frag");
        saveButton.performClick();
        ShadowLooper.idleMainLooper();

        assertEquals("http://100.111.111.21:50829/register/s20", input.getText().toString());
        assertEquals("http://100.111.111.21:50829/register/s20",
                KeepADBPreferences.getRegisterWebhookUrl(activity));

        // #733: a valid save also activates the webhook, so switch it off again before
        // exercising the toggle's own enable path.
        assertTrue(toggle.isChecked());
        toggle.performClick();
        ShadowLooper.idleMainLooper();
        assertFalse(KeepADBPreferences.isRegisterWebhookEnabled(activity));

        // Test toggle button sanitization
        input.setText("http://admin:pass@100.111.111.21:50829/register/s20");
        toggle.performClick();
        ShadowLooper.idleMainLooper();

        assertEquals("http://100.111.111.21:50829/register/s20", input.getText().toString());
        assertEquals("http://100.111.111.21:50829/register/s20",
                KeepADBPreferences.getRegisterWebhookUrl(activity));
        assertTrue(KeepADBPreferences.isRegisterWebhookEnabled(activity));
    }

    @Test
    public void clearingInputHidesCleartextWarningEvenWithSavedHttpUrl() {
        KeepADBPreferences.setRegisterWebhookUrl(RuntimeEnvironment.getApplication(),
                "http://100.111.111.21:50829/register/s20");

        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();

        EditText input = activity.findViewById(R.id.settings_webhook_url);
        TextView warning = activity.findViewById(R.id.settings_webhook_cleartext_warning);
        assertNotNull(input);
        assertNotNull(warning);

        assertEquals(View.VISIBLE, warning.getVisibility());

        input.setText("");
        assertEquals(View.GONE, warning.getVisibility());

        activity.refresh();
        assertEquals(View.GONE, warning.getVisibility());
    }

    // #485: the recently-blocked dialog's listing/allow/empty-state tests moved to
    // MainActivityTrustedNetworkTest -- that entry point now lives on the home screen.

    /**
     * #475: the manual "add current network" button must immediately attempt the connection it
     * was blocking too, matching MainActivity's per-access-point trust button (#470) instead of
     * only the notification's own "allow" action doing so.
     */
    // #471: settings cards start collapsed and expand/collapse independently, without any
    // persisted state. #478: the language and version cards are no longer part of this group --
    // they are permanently visible and never collapse -- so they were removed from this list.
    private static final int[][] COLLAPSIBLE_CARDS = {
            {R.id.settings_webhook_header, R.id.settings_webhook_body},
            {R.id.settings_usb_adb_header, R.id.settings_usb_adb_body},
            {R.id.settings_network_beta_header, R.id.settings_network_beta_body},
            {R.id.settings_misc_header, R.id.settings_misc_body},
            {R.id.settings_diagnostics_header, R.id.settings_diagnostics_body},
    };

    @Test
    public void allSettingsCardsAreCollapsedByDefault() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();

        for (int[] card : COLLAPSIBLE_CARDS) {
            assertEquals("Card body must start collapsed: " + card[1],
                    View.GONE, activity.findViewById(card[1]).getVisibility());
        }
    }

    @Test
    public void clickingACardHeaderTogglesOnlyThatCardsBodyAndArrow() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();

        View webhookHeader = activity.findViewById(R.id.settings_webhook_header);
        View webhookBody = activity.findViewById(R.id.settings_webhook_body);
        TextView webhookArrow = activity.findViewById(R.id.settings_webhook_arrow);
        View diagnosticsBody = activity.findViewById(R.id.settings_diagnostics_body);

        webhookHeader.performClick();
        assertEquals(View.VISIBLE, webhookBody.getVisibility());
        assertEquals("−", webhookArrow.getText().toString());
        // The untouched card must stay exactly as it was -- collapse is per card, not global.
        assertEquals(View.GONE, diagnosticsBody.getVisibility());

        webhookHeader.performClick();
        assertEquals(View.GONE, webhookBody.getVisibility());
        assertEquals("+", webhookArrow.getText().toString());
    }

    @Test
    public void everyCardExpandsAndCollapsesIndependentlyOfTheOthers() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();

        // Expand every other card and confirm the untouched ones are unaffected in both
        // directions -- this is the actual independence guarantee behind acceptance criterion 2,
        // not just "clicking one card doesn't crash the others".
        for (int i = 0; i < COLLAPSIBLE_CARDS.length; i += 2) {
            activity.findViewById(COLLAPSIBLE_CARDS[i][0]).performClick();
        }
        for (int i = 0; i < COLLAPSIBLE_CARDS.length; i++) {
            int expected = (i % 2 == 0) ? View.VISIBLE : View.GONE;
            assertEquals("Card " + i + " expand state must be independent of the others",
                    expected, activity.findViewById(COLLAPSIBLE_CARDS[i][1]).getVisibility());
        }
    }

    @Test
    public void expandedCardStateIsNotPersistedAcrossReopeningTheSettingsPage() {
        ActivityController<SettingsActivity> firstOpen =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity firstActivity = firstOpen.get();
        firstActivity.findViewById(R.id.settings_webhook_header).performClick();
        assertEquals(View.VISIBLE,
                firstActivity.findViewById(R.id.settings_webhook_body).getVisibility());
        firstOpen.pause().stop().destroy();

        // A fresh SettingsActivity instance -- standing in for the user closing and reopening
        // the settings page -- must start collapsed again: no SharedPreferences and no
        // onSaveInstanceState/onCreate(savedInstanceState) restoration ever carries the expand
        // state over (#471 acceptance criterion 3).
        ActivityController<SettingsActivity> secondOpen =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity secondActivity = secondOpen.get();
        assertEquals(View.GONE,
                secondActivity.findViewById(R.id.settings_webhook_body).getVisibility());
        secondOpen.pause().stop().destroy();
    }

    /**
     * #478: the last (version) settings entry is pinned -- always visible and never collapsible
     * -- unlike every other card in {@link #COLLAPSIBLE_CARDS}. #518: the former language entry
     * was removed from this content column entirely (it is now the toolbar button in the
     * header), so it is no longer part of this contract.
     */
    @Test
    public void versionCardIsPermanentlyVisibleAndDoesNotCollapse() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();

        View versionBody = activity.findViewById(R.id.settings_version_body);
        View versionHeader = activity.findViewById(R.id.settings_version_header);

        assertEquals(View.VISIBLE, versionBody.getVisibility());
        assertFalse("Version header must not be clickable -- it no longer collapses",
                versionHeader.hasOnClickListeners());

        // Clicking the (non-interactive) header row must not toggle anything.
        versionHeader.performClick();
        assertEquals(View.VISIBLE, versionBody.getVisibility());

        controller.pause().stop().destroy();
    }

    @Test
    public void expandingTheWebhookCardKeepsItsControlsFullyFunctional() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();

        activity.findViewById(R.id.settings_webhook_header).performClick();
        assertTrue("Expanded card contents must actually be shown, not merely VISIBLE while a "
                        + "collapsed ancestor still clips them",
                activity.findViewById(R.id.settings_webhook_toggle).isShown());

        EditText input = activity.findViewById(R.id.settings_webhook_url);
        Switch toggle = activity.findViewById(R.id.settings_webhook_toggle);
        input.setText("https://100.111.111.21:50829/register/s20");
        toggle.performClick();
        ShadowLooper.idleMainLooper();

        assertTrue(KeepADBPreferences.isRegisterWebhookEnabled(activity));
        assertEquals("https://100.111.111.21:50829/register/s20",
                KeepADBPreferences.getRegisterWebhookUrl(activity));
    }

    @Test
    public void focusWebhookExtraExpandsTheWebhookCardBeforeFocusingTheUrlField() {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), SettingsActivity.class)
                .putExtra(SettingsActivity.EXTRA_FOCUS_WEBHOOK, true);
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class, intent).setup();
        SettingsActivity activity = controller.get();
        ShadowLooper.idleMainLooper();

        // A GONE view cannot take focus, so this also guards against a silent requestFocus()
        // no-op if the webhook card were ever left collapsed on this deep-link path.
        assertEquals(View.VISIBLE, activity.findViewById(R.id.settings_webhook_body).getVisibility());
        assertTrue(activity.findViewById(R.id.settings_webhook_url).isFocused());
    }

    @Test
    public void focusNetworkExtraExpandsTheNetworkCard() {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), SettingsActivity.class)
                .putExtra(SettingsActivity.EXTRA_FOCUS_NETWORK, true);
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class, intent).setup();
        SettingsActivity activity = controller.get();
        ShadowLooper.idleMainLooper();

        assertEquals(View.VISIBLE, activity.findViewById(R.id.settings_network_beta_body).getVisibility());
        TextView arrow = activity.findViewById(R.id.settings_network_beta_arrow);
        assertEquals("−", arrow.getText().toString());
    }

    @Test
    public void focusUsbExtraExpandsTheUsbCardAndIsConsumed() {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), SettingsActivity.class)
                .putExtra(SettingsActivity.EXTRA_FOCUS_USB, true);
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class, intent).setup();
        SettingsActivity activity = controller.get();
        ShadowLooper.idleMainLooper();

        assertEquals(View.VISIBLE, activity.findViewById(R.id.settings_usb_adb_body).getVisibility());
        TextView arrow = activity.findViewById(R.id.settings_usb_adb_arrow);
        assertEquals("−", arrow.getText().toString());
        assertFalse(activity.getIntent().hasExtra(SettingsActivity.EXTRA_FOCUS_USB));
    }

    @Test
    public void usbCardStaysCollapsedWithoutTheFocusExtra() {
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        assertEquals(View.GONE, activity.findViewById(R.id.settings_usb_adb_body).getVisibility());
    }

    @Test
    public void resetAppButtonShowsConfirmationDialogWithCorrectContentAndWiring() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();

        Button resetButton = activity.findViewById(R.id.settings_reset_app);
        assertNotNull("Reset button must be present in settings", resetButton);
        assertEquals(activity.getString(R.string.settings_reset_app), resetButton.getText().toString());

        resetButton.performClick();
        ShadowLooper.idleMainLooper();

        AlertDialog dialog = activity.getActiveResetAppDialog();
        assertNotNull("Reset confirmation dialog must be showing", dialog);
        assertTrue(dialog.isShowing());

        ShadowAlertDialog shadowDialog = shadowOf(dialog);
        assertEquals(activity.getString(R.string.settings_reset_app_dialog_title), shadowDialog.getTitle());
        assertEquals(activity.getString(R.string.settings_reset_app_dialog_message), shadowDialog.getMessage());
        assertEquals(activity.getString(R.string.settings_reset_app_confirm),
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());

        // Cancel button dismisses without performing reset
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertNull("Dialog should be dismissed after cancel", activity.getActiveResetAppDialog());
    }

    @Test
    public void resetAppDialogPositiveClickExecutesResetAndDismisses() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();

        activity.findViewById(R.id.settings_reset_app).performClick();
        ShadowLooper.idleMainLooper();

        AlertDialog dialog = activity.getActiveResetAppDialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertFalse(dialog.isShowing());
    }

    @Test
    public void resetAppDialogIsDismissedWhenActivityIsDestroyed() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();

        activity.findViewById(R.id.settings_reset_app).performClick();
        ShadowLooper.idleMainLooper();

        AlertDialog dialog = activity.getActiveResetAppDialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());

        controller.destroy();
        assertNull(activity.getActiveResetAppDialog());
    }

    private static <T extends View> T findViewByType(View root, Class<T> type) {
        if (root == null) return null;
        if (type.isInstance(root)) {
            return (T) root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                T found = findViewByType(group.getChildAt(i), type);
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * Shared setup for the three #475 immediate-connect tests, mirroring {@code
     * MainActivityAccessPointOverviewTest#trustButtonImmediatelyAttemptsTheConnectionItWasBlocking}
     * (#470): grants {@code WRITE_SECURE_SETTINGS}, switches to {@code MODE_ALL_WIFI} (Robolectric
     * cannot drive the allowlist branch's BSSID-based trust check), enables Keep-Alive, forces
     * the Wi-Fi-connectivity check to report connected, installs a gateway that starts
     * switched off, and connects to {@code (ssid, bssid)}.
     */
    private void grantAutoEnableForTesting(String bssid, String ssid) {
        android.content.Context context = RuntimeEnvironment.getApplication();
        shadowOf((android.app.Application) context).grantPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS,
                android.Manifest.permission.POST_NOTIFICATIONS);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));
        connectTo(ssid, bssid);
    }

    private void connectTo(String ssid, String bssid) {
        android.content.Context context = RuntimeEnvironment.getApplication();
        WifiManager wifiManager = (WifiManager) context.getSystemService(android.content.Context.WIFI_SERVICE);
        WifiInfo info = ShadowWifiInfo.newInstance();
        shadowOf(info).setSSID(ssid);
        shadowOf(info).setBSSID(bssid);
        shadowOf(wifiManager).setConnectionInfo(info);
    }

    /**
     * #492/#654: the mode choice and the Wi-Fi-name switch are the only surfaces the security
     * decision is taken on, so the wiring itself needs pinning, not just the persisted semantics in
     * {@link KeepADBTrustedNetworkTest}. Asserts the properties the issue names for them: both start
     * off on a fresh install, the mode choice actually persists the mode, and the name switch is
     * inoperable until the restriction it widens is on (and becomes operable in the same refresh,
     * not only after re-entering Settings). Going back to all networks never touches the saved
     * name setting -- the UI never turns it on or off by itself.
     */
    @Test
    public void bothPolicyControlsStartOffAndTheSsidOneIsGatedOnTheRestriction() {
        shadowOf(RuntimeEnvironment.getApplication())
                .grantPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION);
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();

        android.widget.RadioButton allWifi = activity.findViewById(R.id.network_mode_all_wifi);
        android.widget.RadioButton allowlist = activity.findViewById(R.id.network_mode_allowlist);
        Switch ssid = activity.findViewById(R.id.settings_trusted_ssid_toggle);

        assertTrue("All networks is the default on a fresh install", allWifi.isChecked());
        assertFalse("The restriction is opt-in and off on a fresh install", allowlist.isChecked());
        assertFalse("The SSID alternative is a second, separate opt-in", ssid.isChecked());
        assertFalse("A switch that widens nothing must not be operable", ssid.isEnabled());
        assertFalse(KeepADBTrustedNetwork.isAllowlistMode(activity));

        // A RadioButton checks itself inside performClick() before the listener runs, so the
        // click alone is the user gesture -- no setChecked() priming.
        allowlist.performClick();
        assertTrue("The choice must persist the mode, not just render it",
                KeepADBTrustedNetwork.isAllowlistMode(activity));
        assertTrue(allowlist.isChecked());
        assertFalse(allWifi.isChecked());
        assertTrue("The SSID switch must become operable in the same refresh", ssid.isEnabled());
        assertFalse("Enabling the restriction must not enable the SSID alternative with it",
                KeepADBTrustedNetwork.isSsidMatchingEnabled(activity));

        ssid.performClick();
        assertTrue(KeepADBTrustedNetwork.isSsidMatchingEnabled(activity));

        allWifi.performClick();
        assertFalse(KeepADBTrustedNetwork.isAllowlistMode(activity));
        assertTrue(allWifi.isChecked());
        assertFalse("Opting back out must disarm the widening switch again", ssid.isEnabled());
        assertTrue("Leaving the restriction must keep the saved name setting untouched",
                KeepADBTrustedNetwork.isSsidMatchingEnabled(activity));
    }

    @Test
    public void wifiApsSectionDisabledByDefault() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();

        activity.findViewById(R.id.settings_network_beta_header).performClick();

        Switch toggle = activity.findViewById(R.id.settings_wifi_aps_feature_toggle);
        assertNotNull(toggle);
        assertFalse("Opt-in toggle is off by default", toggle.isChecked());
        assertFalse(KeepADBPreferences.isWifiApsFeatureEnabled(activity));

        View content = activity.findViewById(R.id.settings_wifi_aps_content);
        assertNotNull(content);
        assertEquals("Content container is GONE when opt-in is disabled", View.GONE, content.getVisibility());

        int betaBadgeId = activity.getResources().getIdentifier(
                "settings_wifi_aps_beta_badge", "id", activity.getPackageName());
        assertEquals("Beta badge id must be removed", 0, betaBadgeId);
    }

    @Test
    public void togglingWifiApsFeatureEnablesAndShowsContent() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();

        activity.findViewById(R.id.settings_network_beta_header).performClick();
        Switch toggle = activity.findViewById(R.id.settings_wifi_aps_feature_toggle);
        View content = activity.findViewById(R.id.settings_wifi_aps_content);

        // Turn on
        toggle.performClick();
        ShadowLooper.idleMainLooper();

        assertTrue(KeepADBPreferences.isWifiApsFeatureEnabled(activity));
        assertTrue(toggle.isChecked());
        assertEquals(View.VISIBLE, content.getVisibility());

        // Turn off
        toggle.performClick();
        ShadowLooper.idleMainLooper();

        assertFalse(KeepADBPreferences.isWifiApsFeatureEnabled(activity));
        assertFalse(toggle.isChecked());
        assertEquals(View.GONE, content.getVisibility());
    }

    /**
     * #529: USB-ADB notification and handover are direct sections inside one collapsible card.
     * Opening the outer card must reveal every existing control without another expand target.
     */
    @Test
    public void usbAdbCardShowsBothDirectSectionsAfterOneExpandStep() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();

        View outerBody = activity.findViewById(R.id.settings_usb_adb_body);
        TextView outerArrow = activity.findViewById(R.id.settings_usb_adb_arrow);
        View notificationSection = activity.findViewById(R.id.settings_usb_notification_panel);
        View handoverSection = activity.findViewById(R.id.settings_usb_handover_panel);
        TextView notificationTitle =
                activity.findViewById(R.id.settings_usb_notification_title);
        TextView handoverTitle = activity.findViewById(R.id.settings_usb_handover_title);
        int[] directControls = {
                R.id.settings_usb_notification_toggle,
                R.id.settings_usb_profile_notification_toggle,
                R.id.settings_usb_profile_action,
                R.id.settings_usb_handover_selector,
        };

        assertEquals(View.GONE, outerBody.getVisibility());
        assertEquals("+", outerArrow.getText().toString());
        assertFalse(notificationSection.hasOnClickListeners());
        assertFalse(handoverSection.hasOnClickListeners());
        assertFalse(notificationTitle.isClickable());
        assertFalse(notificationTitle.isFocusable());
        assertFalse(handoverTitle.isClickable());
        assertFalse(handoverTitle.isFocusable());
        assertTrue(notificationTitle.isAccessibilityHeading());
        assertTrue(handoverTitle.isAccessibilityHeading());
        for (int id : directControls) {
            assertFalse("USB-ADB control must stay hidden while the outer card is closed: " + id,
                    activity.findViewById(id).isShown());
        }

        activity.findViewById(R.id.settings_usb_adb_header).performClick();

        assertEquals(View.VISIBLE, outerBody.getVisibility());
        assertEquals("−", outerArrow.getText().toString());
        for (int id : directControls) {
            View control = activity.findViewById(id);
            assertTrue("USB-ADB control must be shown after one outer expand step: " + id,
                    control.isShown());
            assertTrue("USB-ADB control must keep its click listener: " + id,
                    control.hasOnClickListeners());
        }

        activity.findViewById(R.id.settings_usb_adb_header).performClick();
        assertEquals(View.GONE, outerBody.getVisibility());
        assertEquals("+", outerArrow.getText().toString());
        controller.pause().stop().destroy();
    }

    @Test
    public void usbAdbSettingsPersistAcrossActivityRecreation() {
        ActivityController<SettingsActivity> firstOpen =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity firstActivity = firstOpen.get();
        firstActivity.findViewById(R.id.settings_usb_adb_header).performClick();

        Switch notificationToggle =
                firstActivity.findViewById(R.id.settings_usb_notification_toggle);
        Switch profileNotificationToggle =
                firstActivity.findViewById(R.id.settings_usb_profile_notification_toggle);
        assertFalse(notificationToggle.isChecked());
        assertTrue(profileNotificationToggle.isChecked());
        notificationToggle.performClick();
        profileNotificationToggle.performClick();

        firstActivity.findViewById(R.id.settings_usb_handover_selector).performClick();
        ShadowLooper.idleMainLooper();
        AlertDialog handoverDialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(handoverDialog);
        shadowOf(handoverDialog).clickOnItem(2);
        ShadowLooper.idleMainLooper();

        assertTrue(KeepADBUsbProfile.isNotificationEnabled(firstActivity));
        assertFalse(KeepADBUsbProfile.isProfileNotificationEnabled(firstActivity));
        assertEquals(KeepADBPreferences.USB_WLAN_HANDOVER_MODE_AUTOMATIC,
                KeepADBPreferences.getUsbWlanHandoverMode(firstActivity));
        firstOpen.pause().stop().destroy();

        ActivityController<SettingsActivity> secondOpen =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity secondActivity = secondOpen.get();
        secondActivity.findViewById(R.id.settings_usb_adb_header).performClick();

        assertTrue(((Switch) secondActivity.findViewById(
                R.id.settings_usb_notification_toggle)).isChecked());
        assertFalse(((Switch) secondActivity.findViewById(
                R.id.settings_usb_profile_notification_toggle)).isChecked());
        assertEquals(secondActivity.getString(R.string.settings_usb_handover_mode_automatic),
                ((TextView) secondActivity.findViewById(
                        R.id.settings_usb_handover_selected_text)).getText().toString());
        assertEquals(secondActivity.getString(R.string.settings_usb_handover_accessibility,
                        secondActivity.getString(R.string.settings_usb_handover_mode_automatic)),
                secondActivity.findViewById(
                        R.id.settings_usb_handover_selector).getContentDescription());
        secondOpen.pause().stop().destroy();
    }

    /**
     * #618/#654: the Network card is one collapsible card. Opening it must reveal every section
     * without another expand target, except the advanced Wi-Fi-name section, which is the one
     * deliberately collapsed sub-section at the very bottom. Beta badges stay removed.
     */
    @Test
    public void networkCardShowsItsSectionsAfterOneExpandStep() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();

        View outerPanel = activity.findViewById(R.id.settings_network_beta_panel);
        assertNotNull(outerPanel);
        assertEquals(View.VISIBLE, outerPanel.getVisibility());

        View outerBody = activity.findViewById(R.id.settings_network_beta_body);
        TextView outerArrow = activity.findViewById(R.id.settings_network_beta_arrow);
        int[] headings = {
                R.id.network_status_heading,
                R.id.network_mode_heading,
                R.id.network_background_heading,
                R.id.network_manage_heading,
                R.id.network_observation_heading,
        };
        int[] directControls = {
                R.id.network_mode_all_wifi,
                R.id.network_mode_allowlist,
                R.id.settings_background_location_button,
                R.id.network_allowed_row,
                R.id.network_prevented_row,
                R.id.settings_wifi_aps_feature_toggle,
                R.id.network_ssid_header,
        };

        assertEquals(View.GONE, outerBody.getVisibility());
        assertEquals("+", outerArrow.getText().toString());
        for (int id : headings) {
            TextView heading = activity.findViewById(id);
            assertTrue("Section heading must be an accessibility heading: " + id,
                    heading.isAccessibilityHeading());
            assertFalse(heading.isClickable());
            assertFalse(heading.isFocusable());
        }
        for (int id : directControls) {
            assertFalse("Network control must stay hidden while the card is closed: " + id,
                    activity.findViewById(id).isShown());
        }
        for (String badge : new String[] {"settings_trusted_network_beta_badge",
                "settings_wifi_aps_beta_badge"}) {
            assertEquals("Beta badge id must be removed: " + badge, 0,
                    activity.getResources().getIdentifier(badge, "id", activity.getPackageName()));
        }

        activity.findViewById(R.id.settings_network_beta_header).performClick();

        assertEquals(View.VISIBLE, outerBody.getVisibility());
        assertEquals("−", outerArrow.getText().toString());
        for (int id : directControls) {
            View control = activity.findViewById(id);
            assertTrue("Network control must be shown after one outer expand step: " + id,
                    control.isShown());
            assertTrue("Network control must keep its click listener: " + id,
                    control.hasOnClickListeners());
        }
        assertFalse("The advanced section starts collapsed: its switch is still hidden",
                activity.findViewById(R.id.settings_trusted_ssid_toggle).isShown());

        activity.findViewById(R.id.settings_network_beta_header).performClick();
        assertEquals(View.GONE, outerBody.getVisibility());
        assertEquals("+", outerArrow.getText().toString());
        controller.pause().stop().destroy();
    }

    /**
     * #510/#521: the four notice/display-preference switches (persistent notification, keep
     * display on, security/network advice banner, battery-optimization advice) are bundled
     * directly inside the single collapsible "Sonstiges" card. Like #529's USB sections, none
     * is independently collapsible; they all become visible together as soon as the outer card
     * is expanded.
     */
    @Test
    public void miscCardBundlesTheFourNoticeSwitchesDirectlyWithoutSubCards() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();

        View outerPanel = activity.findViewById(R.id.settings_misc_panel);
        assertNotNull(outerPanel);
        assertEquals(View.VISIBLE, outerPanel.getVisibility());

        View outerBody = activity.findViewById(R.id.settings_misc_body);
        assertEquals(View.GONE, outerBody.getVisibility());

        int[] miscSwitches = {
                R.id.settings_hide_notification_toggle,
                R.id.settings_keep_display_on_toggle,
                R.id.settings_advice_banner_toggle,
                R.id.settings_battery_optimization_panel_toggle,
        };
        for (int id : miscSwitches) {
            assertNotNull("Switch must exist before expansion: " + id, activity.findViewById(id));
        }

        activity.findViewById(R.id.settings_misc_header).performClick();
        assertEquals(View.VISIBLE, outerBody.getVisibility());

        // All four switches must become visible together, with no further click needed -- there
        // is exactly one expand step, not one per section (acceptance criterion 2).
        for (int id : miscSwitches) {
            assertTrue("Switch must be shown once the outer card is expanded: " + id,
                    activity.findViewById(id).isShown());
        }
    }

    /**
     * #510 acceptance criterion 7 / #618: a fresh install must leave both network features
     * (Trusted Networks and Wi-Fi &amp; access points) disabled -- the visual regrouping must
     * not change either feature's default preference value.
     */
    @Test
    public void bothNetworkFeaturesAreDisabledOnFreshInstall() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();

        assertFalse("Trusted-network allowlist mode must default to off",
                KeepADBTrustedNetwork.isAllowlistMode(activity));
        assertFalse("Wi-Fi & access points feature must default to off",
                KeepADBPreferences.isWifiApsFeatureEnabled(activity));

        activity.findViewById(R.id.settings_network_beta_header).performClick();

        assertTrue(((android.widget.RadioButton) activity.findViewById(R.id.network_mode_all_wifi))
                .isChecked());
        assertFalse(((android.widget.RadioButton) activity.findViewById(R.id.network_mode_allowlist))
                .isChecked());

        Switch wifiApsToggle = activity.findViewById(R.id.settings_wifi_aps_feature_toggle);
        assertFalse(wifiApsToggle.isChecked());
    }

    @Test
    public void debugBuildBadgeIsPresentOnlyInDebugVariant() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();

        TextView debugBadge = activity.findViewById(R.id.settings_version_debug_badge);
        assertNotNull("Debug build badge view must exist", debugBadge);
        boolean debugBuild = activity.getPackageName().endsWith(".debug");
        assertEquals("Debug badge visibility must follow the applicationId suffix",
                debugBuild ? View.VISIBLE : View.GONE, debugBadge.getVisibility());
        if (debugBuild) {
            assertEquals("⚠ DEBUG BUILD", debugBadge.getText().toString());
            assertEquals("Debug badge must remain understandable to screen readers",
                    "⚠ DEBUG BUILD", debugBadge.getContentDescription().toString());
            assertTrue("Debug badge must remain an accessibility node",
                    debugBadge.isImportantForAccessibility());
        }
    }

    /**
     * #672: rotates the activity (saveInstanceState -> destroy -> fresh instance restored from the
     * bundle, like the #604 tests) and returns the new controller.
     */
    private static ActivityController<SettingsActivity> rotate(
            ActivityController<SettingsActivity> controller) {
        Bundle state = new Bundle();
        controller.saveInstanceState(state);
        controller.pause().stop().destroy();
        ShadowLooper.idleMainLooper();
        return Robolectric.buildActivity(SettingsActivity.class).setup(state);
    }

    private static void assertDialogShowingWithTitle(int titleRes, SettingsActivity activity) {
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("Dialog must be showing after rotation", dialog);
        assertTrue(dialog.isShowing());
        assertEquals(activity.getString(titleRes), shadowOf(dialog).getTitle().toString());
    }

    @Test
    public void resetAppDialogSurvivesRotationButStillNeedsConfirmTap() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        controller.get().findViewById(R.id.settings_reset_app).performClick();
        assertNotNull(controller.get().getActiveResetAppDialog());

        ActivityController<SettingsActivity> restored = rotate(controller);
        SettingsActivity activity = restored.get();

        AlertDialog dialog = activity.getActiveResetAppDialog();
        assertNotNull("Reset dialog must be restored", dialog);
        assertTrue(dialog.isShowing());
        assertFalse("Restoring must never execute the reset",
                shadowOf((android.app.ActivityManager) activity.getSystemService(
                        android.content.Context.ACTIVITY_SERVICE)).isApplicationUserDataCleared());

        dialog.dismiss();
        restored.pause().stop().destroy();
    }

    @Test
    public void backgroundLocationDialogSurvivesRotation() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        // #697: the dialog now belongs to KeepADBNetworkCard; open it through its real entry
        // point (the card's background-access button, no grant yet) instead of reflection.
        controller.get().findViewById(R.id.settings_background_location_button).performClick();
        assertDialogShowingWithTitle(R.string.background_location_panel_title, controller.get());

        ActivityController<SettingsActivity> restored = rotate(controller);
        assertDialogShowingWithTitle(R.string.background_location_panel_title, restored.get());

        ShadowAlertDialog.getLatestAlertDialog().dismiss();
        restored.pause().stop().destroy();
    }

    @Test
    public void usbHandoverModeDialogSurvivesRotation() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        controller.get().findViewById(R.id.settings_usb_handover_selector).performClick();
        assertDialogShowingWithTitle(R.string.settings_usb_handover_dialog_title, controller.get());

        ActivityController<SettingsActivity> restored = rotate(controller);
        assertDialogShowingWithTitle(R.string.settings_usb_handover_dialog_title, restored.get());

        ShadowAlertDialog.getLatestAlertDialog().dismiss();
        restored.pause().stop().destroy();
    }

    @Test
    public void languageSelectionDialogSurvivesRotation() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        controller.get().findViewById(R.id.settings_language_toolbar_button).performClick();
        assertDialogShowingWithTitle(R.string.settings_language_dialog_title, controller.get());

        ActivityController<SettingsActivity> restored = rotate(controller);
        assertDialogShowingWithTitle(R.string.settings_language_dialog_title, restored.get());

        ShadowAlertDialog.getLatestAlertDialog().dismiss();
        restored.pause().stop().destroy();
    }

    @Test
    public void allowlistPermissionDialogSurvivesRotationWithoutRequestingOrSwitching() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        controller.get().findViewById(R.id.network_mode_allowlist).performClick();
        assertNotNull(controller.get().getActiveAllowlistPermissionDialog());
        assertDialogShowingWithTitle(R.string.settings_trusted_network_permission_title,
                controller.get());
        assertFalse(KeepADBTrustedNetwork.isAllowlistMode(controller.get()));

        ActivityController<SettingsActivity> restored = rotate(controller);
        SettingsActivity activity = restored.get();

        assertNotNull("Permission rationale must be restored",
                activity.getActiveAllowlistPermissionDialog());
        assertDialogShowingWithTitle(R.string.settings_trusted_network_permission_title, activity);
        assertNull("Restoring must never request the permission",
                shadowOf(activity).getLastRequestedPermission());
        assertFalse("Restoring must never switch the mode",
                KeepADBTrustedNetwork.isAllowlistMode(activity));
        assertTrue(((android.widget.RadioButton) activity.findViewById(R.id.network_mode_all_wifi))
                .isChecked());

        activity.getActiveAllowlistPermissionDialog().dismiss();
        restored.pause().stop().destroy();
    }

    @Test
    public void allowlistPermissionDialogIsNotRestoredAfterDismissAndClosedOnDestroy() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        controller.get().findViewById(R.id.network_mode_allowlist).performClick();
        AlertDialog first = controller.get().getActiveAllowlistPermissionDialog();
        first.dismiss();
        ShadowLooper.idleMainLooper();
        assertNull(controller.get().getActiveAllowlistPermissionDialog());

        ActivityController<SettingsActivity> restored = rotate(controller);
        assertNull("A dismissed dialog must not come back",
                restored.get().getActiveAllowlistPermissionDialog());

        restored.get().findViewById(R.id.network_mode_allowlist).performClick();
        AlertDialog open = restored.get().getActiveAllowlistPermissionDialog();
        assertNotNull(open);
        restored.pause().stop().destroy();
        assertFalse("onDestroy must close the dialog (no window leak)", open.isShowing());
    }

    private static Button findButtonWithText(List<Button> buttons, String text) {
        for (Button button : buttons) {
            if (text.equals(button.getText().toString())) return button;
        }
        return null;
    }

    private static <T extends View> List<T> findViewsByType(View root, Class<T> type) {
        List<T> result = new ArrayList<>();
        findViewsByTypeInternal(root, type, result);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static <T extends View> void findViewsByTypeInternal(View root, Class<T> type, List<T> result) {
        if (root == null) return;
        if (type.isInstance(root)) {
            result.add((T) root);
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                findViewsByTypeInternal(group.getChildAt(i), type, result);
            }
        }
    }
}
