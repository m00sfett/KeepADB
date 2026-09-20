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
                savedState.getBoolean(SettingsActivity.STATE_ISSUE_REPORT_SHOWING));
        assertNotNull("Saved state must contain draft body",
                savedState.getString(SettingsActivity.STATE_ISSUE_REPORT_DRAFT));

        // Dismissing clears active reference
        dialog.dismiss();
        ShadowLooper.idleMainLooper();
        assertNull("Active dialog reference must be cleared after dismissal",
                activity.getActiveIssueReportDialog());

        Bundle afterDismissState = new Bundle();
        controller.saveInstanceState(afterDismissState);
        assertFalse("Saved state must not report dialog showing after dismiss",
                afterDismissState.getBoolean(SettingsActivity.STATE_ISSUE_REPORT_SHOWING));

        // Now test restoration with customized draft values
        Bundle restoreBundle = new Bundle();
        restoreBundle.putBoolean(SettingsActivity.STATE_ISSUE_REPORT_SHOWING, true);
        restoreBundle.putString(SettingsActivity.STATE_ISSUE_REPORT_DRAFT, "Restored draft problem description");
        restoreBundle.putBoolean(SettingsActivity.STATE_ISSUE_REPORT_DIAGNOSTICS, true);

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
                reSavedState.getString(SettingsActivity.STATE_ISSUE_REPORT_DRAFT));
        assertTrue(reSavedState.getBoolean(SettingsActivity.STATE_ISSUE_REPORT_DIAGNOSTICS));

        restoredDialog.dismiss();
        ShadowLooper.idleMainLooper();
    }

    @Test
    public void profileEditDraftSavesAndRestoresAcrossRecreation() {
        Bundle restoreBundle = new Bundle();
        restoreBundle.putBoolean(SettingsActivity.STATE_PROFILE_EDIT_SHOWING, true);
        restoreBundle.putInt(SettingsActivity.STATE_PROFILE_EDIT_ID, -1);
        restoreBundle.putString(SettingsActivity.STATE_PROFILE_EDIT_NAME, "My Workstation");
        restoreBundle.putString(SettingsActivity.STATE_PROFILE_EDIT_IP, "192.168.1.100");
        restoreBundle.putString(SettingsActivity.STATE_PROFILE_EDIT_HOSTNAME, "workstation.local");
        restoreBundle.putString(SettingsActivity.STATE_PROFILE_EDIT_TAILNET, "workstation.tailnet");

        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup(restoreBundle);
        SettingsActivity activity = controller.get();

        AlertDialog dialog = activity.getActiveProfileEditDialog();
        assertNotNull("Profile edit dialog should be restored on recreation", dialog);
        assertTrue("Restored profile edit dialog must be showing", dialog.isShowing());

        Bundle reSavedState = new Bundle();
        controller.saveInstanceState(reSavedState);
        assertTrue(reSavedState.getBoolean(SettingsActivity.STATE_PROFILE_EDIT_SHOWING));
        assertEquals(-1, reSavedState.getInt(SettingsActivity.STATE_PROFILE_EDIT_ID));
        assertEquals("My Workstation", reSavedState.getString(SettingsActivity.STATE_PROFILE_EDIT_NAME));
        assertEquals("192.168.1.100", reSavedState.getString(SettingsActivity.STATE_PROFILE_EDIT_IP));
        assertEquals("workstation.local", reSavedState.getString(SettingsActivity.STATE_PROFILE_EDIT_HOSTNAME));
        assertEquals("workstation.tailnet", reSavedState.getString(SettingsActivity.STATE_PROFILE_EDIT_TAILNET));

        dialog.dismiss();
        ShadowLooper.idleMainLooper();
        assertNull("Active profile edit dialog reference must be cleared after dismissal",
                activity.getActiveProfileEditDialog());

        Bundle afterDismissState = new Bundle();
        controller.saveInstanceState(afterDismissState);
        assertFalse(afterDismissState.getBoolean(SettingsActivity.STATE_PROFILE_EDIT_SHOWING));
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
        editBundle.putBoolean(SettingsActivity.STATE_PROFILE_EDIT_SHOWING, true);
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
            {R.id.settings_usb_notification_header, R.id.settings_usb_notification_body},
            {R.id.settings_usb_handover_header, R.id.settings_usb_handover_body},
            {R.id.settings_trusted_network_header, R.id.settings_trusted_network_body},
            {R.id.settings_wifi_aps_header, R.id.settings_wifi_aps_body},
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

    @SuppressWarnings("unchecked")
    /**
     * #492: the two policy switches are the only surface the security decision is taken on, so the
     * wiring itself needs pinning, not just the persisted semantics in {@link
     * KeepADBTrustedNetworkTest}. Asserts all three properties the issue names for them: both start
     * off on a fresh install, the global switch actually persists the mode, and the SSID switch is
     * inoperable until the restriction it widens is on (and becomes operable in the same refresh,
     * not only after re-entering Settings).
     */
    @Test
    public void bothPolicySwitchesStartOffAndTheSsidOneIsGatedOnTheRestriction() {
        shadowOf(RuntimeEnvironment.getApplication())
                .grantPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION);
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();

        Switch restriction = activity.findViewById(R.id.settings_trusted_network_toggle);
        Switch ssid = activity.findViewById(R.id.settings_trusted_ssid_toggle);

        assertFalse("The restriction is opt-in and off on a fresh install", restriction.isChecked());
        assertFalse("The SSID alternative is a second, separate opt-in", ssid.isChecked());
        assertFalse("A switch that widens nothing must not be operable", ssid.isEnabled());
        assertFalse(KeepADBTrustedNetwork.isAllowlistMode(activity));

        // A CompoundButton toggles itself inside performClick() before the listener runs, so the
        // click alone is the user gesture -- no setChecked() priming.
        restriction.performClick();
        assertTrue("The switch must persist the mode, not just render it",
                KeepADBTrustedNetwork.isAllowlistMode(activity));
        assertTrue(restriction.isChecked());
        assertTrue("The SSID switch must become operable in the same refresh", ssid.isEnabled());
        assertFalse("Enabling the restriction must not enable the SSID alternative with it",
                KeepADBTrustedNetwork.isSsidMatchingEnabled(activity));

        ssid.performClick();
        assertTrue(KeepADBTrustedNetwork.isSsidMatchingEnabled(activity));

        restriction.performClick();
        assertFalse(KeepADBTrustedNetwork.isAllowlistMode(activity));
        assertFalse("Opting back out must disarm the widening switch again", ssid.isEnabled());
    }

    @Test
    public void wifiApsPanelStartsCollapsedAndDisabledByDefault() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();

        View body = activity.findViewById(R.id.settings_wifi_aps_body);
        assertNotNull(body);
        assertEquals("WiFi APs body starts collapsed", View.GONE, body.getVisibility());

        activity.findViewById(R.id.settings_wifi_aps_header).performClick();
        assertEquals("Expanding header makes body visible", View.VISIBLE, body.getVisibility());

        Switch toggle = activity.findViewById(R.id.settings_wifi_aps_feature_toggle);
        assertNotNull(toggle);
        assertFalse("Opt-in toggle is off by default", toggle.isChecked());
        assertFalse(KeepADBPreferences.isWifiApsFeatureEnabled(activity));

        View content = activity.findViewById(R.id.settings_wifi_aps_content);
        assertNotNull(content);
        assertEquals("Content container is GONE when opt-in is disabled", View.GONE, content.getVisibility());

        TextView betaBadge = activity.findViewById(R.id.settings_wifi_aps_beta_badge);
        assertNotNull(betaBadge);
        assertEquals(View.VISIBLE, betaBadge.getVisibility());
        assertEquals("BETA", betaBadge.getText().toString());

        TextView betaDesc = activity.findViewById(R.id.settings_wifi_aps_beta_description);
        assertNotNull(betaDesc);
        assertEquals(View.VISIBLE, betaDesc.getVisibility());
        assertEquals(activity.getString(R.string.settings_wifi_aps_beta_description), betaDesc.getText().toString());
    }

    @Test
    public void togglingWifiApsFeatureEnablesAndShowsContent() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();

        activity.findViewById(R.id.settings_wifi_aps_header).performClick();
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
     * #510/#519: Trusted Networks and Wi-Fi &amp; access points are nested as independently
     * collapsible sub-cards inside the "Network (Beta)" card, which is itself collapsible and,
     * once expanded, shows the shared description explaining the relationship and beta status.
     * Both sub-cards stay independently collapsible and both carry their own "BETA" badge --
     * previously only the Wi-Fi &amp; access points card had one.
     */
    @Test
    public void networkBetaGroupHeadingIntroducesBothBetaFeaturesConsistently() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();

        View outerPanel = activity.findViewById(R.id.settings_network_beta_panel);
        assertNotNull(outerPanel);
        assertEquals(View.VISIBLE, outerPanel.getVisibility());

        // #519: the outer card itself starts collapsed, like every other collapsible card, and
        // must be expanded before its description and sub-cards become reachable.
        View outerBody = activity.findViewById(R.id.settings_network_beta_body);
        assertEquals(View.GONE, outerBody.getVisibility());
        activity.findViewById(R.id.settings_network_beta_header).performClick();
        assertEquals(View.VISIBLE, outerBody.getVisibility());

        TextView groupSubtext = activity.findViewById(R.id.settings_network_beta_group_subtext);
        assertNotNull(groupSubtext);
        assertEquals(activity.getString(R.string.settings_network_beta_group_subtext),
                groupSubtext.getText().toString());

        TextView trustedNetworkBadge = activity.findViewById(R.id.settings_trusted_network_beta_badge);
        assertNotNull(trustedNetworkBadge);
        assertEquals(View.VISIBLE, trustedNetworkBadge.getVisibility());
        assertEquals("BETA", trustedNetworkBadge.getText().toString());

        TextView wifiApsBadge = activity.findViewById(R.id.settings_wifi_aps_beta_badge);
        assertNotNull(wifiApsBadge);
        assertEquals(View.VISIBLE, wifiApsBadge.getVisibility());
        assertEquals("BETA", wifiApsBadge.getText().toString());

        // Both features must remain separately expandable -- expanding one must not affect the
        // other's collapsed state (this is the same independence guarantee as #471, just applied
        // across the new shared group heading).
        View trustedNetworkBody = activity.findViewById(R.id.settings_trusted_network_body);
        View wifiApsBody = activity.findViewById(R.id.settings_wifi_aps_body);
        assertEquals(View.GONE, trustedNetworkBody.getVisibility());
        assertEquals(View.GONE, wifiApsBody.getVisibility());

        activity.findViewById(R.id.settings_trusted_network_header).performClick();
        assertEquals(View.VISIBLE, trustedNetworkBody.getVisibility());
        assertEquals("Expanding trusted networks must not expand Wi-Fi & access points",
                View.GONE, wifiApsBody.getVisibility());

        activity.findViewById(R.id.settings_wifi_aps_header).performClick();
        assertEquals(View.VISIBLE, wifiApsBody.getVisibility());
        assertEquals("Wi-Fi & access points must stay expanded independently of trusted networks",
                View.VISIBLE, trustedNetworkBody.getVisibility());
    }

    /**
     * #510/#521: the four notice/display-preference switches (persistent notification, keep
     * display on, security/network advice banner, battery-optimization advice) are bundled
     * directly inside the single collapsible "Sonstiges" card -- unlike #519/#520, none of them
     * is its own independently collapsible sub-card; they all become visible together as soon as
     * the outer card is expanded.
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
     * #510 acceptance criterion 7: a fresh install must leave both network beta features
     * (Trusted Networks and Wi-Fi &amp; access points) disabled -- the visual regrouping must
     * not change either feature's default preference value.
     */
    @Test
    public void bothNetworkBetaFeaturesAreDisabledOnFreshInstall() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();

        assertFalse("Trusted-network allowlist mode must default to off",
                KeepADBTrustedNetwork.isAllowlistMode(activity));
        assertFalse("Wi-Fi & access points feature must default to off",
                KeepADBPreferences.isWifiApsFeatureEnabled(activity));

        Switch trustedNetworkToggle = activity.findViewById(R.id.settings_trusted_network_toggle);
        activity.findViewById(R.id.settings_trusted_network_header).performClick();
        assertFalse(trustedNetworkToggle.isChecked());

        Switch wifiApsToggle = activity.findViewById(R.id.settings_wifi_aps_feature_toggle);
        activity.findViewById(R.id.settings_wifi_aps_header).performClick();
        assertFalse(wifiApsToggle.isChecked());
    }

    @Test
    public void debugBuildBadgeIsShownWhenRunningPackageEndsInDebugSuffix() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();

        // The debug build type (applicationIdSuffix '.debug', app/build.gradle) is the only
        // variant this unit test harness runs under (testBuildType defaults to debug), so the
        // running package name here is always the debug one -- verifying the release path
        // would need a release-variant test harness, which this project does not maintain.
        assertTrue("Unit tests run under the debug applicationId suffix",
                activity.getPackageName().endsWith(".debug"));

        TextView debugBadge = activity.findViewById(R.id.settings_version_debug_badge);
        assertNotNull("Debug build badge view must exist", debugBadge);
        assertEquals("Debug build badge must be visible for a debug-suffixed package",
                View.VISIBLE, debugBadge.getVisibility());
        assertEquals(activity.getString(R.string.settings_version_debug_badge),
                debugBadge.getText().toString());
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
