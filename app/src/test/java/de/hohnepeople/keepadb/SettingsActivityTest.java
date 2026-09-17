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

        // 1. Trusted networks manage dialog
        KeepADBTrustedNetwork.addBssid(activity, "11:22:33:44:55:66", "Test-WiFi");
        ShadowLooper.idleMainLooper();
        activity.findViewById(R.id.settings_trusted_network_manage).performClick();
        ShadowLooper.idleMainLooper();
        AlertDialog trustedDialog = activity.getActiveManageNetworksDialog();
        assertNotNull("Trusted networks dialog should be showing", trustedDialog);
        assertTrue(trustedDialog.isShowing());
        ScrollView trustedScroll = findViewByType(trustedDialog.findViewById(android.R.id.custom), ScrollView.class);
        assertNotNull("Trusted networks dialog rows must be wrapped in a ScrollView", trustedScroll);
        trustedDialog.dismiss();
        ShadowLooper.idleMainLooper();

        // 2. Profile switch dialog
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

        // Trusted network delete button
        KeepADBTrustedNetwork.addBssid(activity, "aa:bb:cc:dd:ee:ff", "MyOfficeNetwork");
        ShadowLooper.idleMainLooper();
        activity.findViewById(R.id.settings_trusted_network_manage).performClick();
        ShadowLooper.idleMainLooper();
        AlertDialog trustedDialog = activity.getActiveManageNetworksDialog();
        assertNotNull("Trusted networks dialog should be showing", trustedDialog);
        View customPanel = trustedDialog.findViewById(android.R.id.custom);
        ScrollView trustedScroll = findViewByType(customPanel, ScrollView.class);
        assertNotNull("Custom panel must contain ScrollView", trustedScroll);
        List<Button> trustedButtons = findViewsByType(trustedScroll, Button.class);
        assertFalse("Delete button should be present in trusted networks list", trustedButtons.isEmpty());
        assertEquals("Trusted network delete button must set contextual content description with network label",
                activity.getString(R.string.settings_trusted_network_delete_accessibility, "MyOfficeNetwork"),
                trustedButtons.get(0).getContentDescription());
        trustedDialog.dismiss();
        ShadowLooper.idleMainLooper();

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

        View languageSelector = activity.findViewById(R.id.settings_language_selector);
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

        activity.findViewById(R.id.settings_language_selector).performClick();
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

    @SuppressWarnings("unchecked")
    /** #446: the recently-blocked list is the transparency half of the issue. */
    @Test
    public void blockedNetworkDialogListsBlockedAccessPointsAndCanAllowOne() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();
        KeepADBBlockedNetworkHistory.record(activity,
                new KeepADBNetworkIdentity("Cafe-WLAN", "aa:bb:cc:dd:ee:01"), 1_000L);
        KeepADBBlockedNetworkHistory.record(activity,
                new KeepADBNetworkIdentity("Hotel-WLAN", "aa:bb:cc:dd:ee:02"), 2_000L);
        controller.pause().resume();
        ShadowLooper.idleMainLooper();

        Button entryPoint = activity.findViewById(R.id.settings_trusted_network_blocked);
        assertTrue("The entry point must show the count: " + entryPoint.getText(),
                entryPoint.getText().toString().contains("2"));

        entryPoint.performClick();
        ShadowLooper.idleMainLooper();
        AlertDialog dialog = activity.getActiveBlockedNetworksDialog();
        assertNotNull("Blocked networks dialog should be showing", dialog);
        List<TextView> texts = findViewsByType(dialog.findViewById(android.R.id.custom), TextView.class);
        List<String> rendered = new ArrayList<>();
        for (TextView view : texts) rendered.add(view.getText().toString());
        assertTrue(rendered.toString(), rendered.contains("Cafe-WLAN"));
        assertTrue(rendered.toString(), rendered.contains("Hotel-WLAN"));
        // Newest first: the access point the user just failed on is at the top.
        assertTrue(rendered.indexOf("Hotel-WLAN") < rendered.indexOf("Cafe-WLAN"));

        // Nothing is trusted merely by looking at the list.
        assertTrue(KeepADBTrustedNetwork.getEntries(activity).isEmpty());

        List<Button> allowButtons =
                findViewsByType(dialog.findViewById(android.R.id.custom), Button.class);
        assertEquals(2, allowButtons.size());
        allowButtons.get(0).performClick();
        ShadowLooper.idleMainLooper();

        List<KeepADBTrustedNetwork.Entry> trusted = KeepADBTrustedNetwork.getEntries(activity);
        assertEquals(1, trusted.size());
        assertEquals("aa:bb:cc:dd:ee:02", trusted.get(0).bssid);
        assertEquals("Hotel-WLAN", trusted.get(0).label);
        // The allowed one leaves the blocked log; the other stays for a later decision.
        List<KeepADBBlockedNetworkHistory.Entry> remaining =
                KeepADBBlockedNetworkHistory.getEntries(activity);
        assertEquals(1, remaining.size());
        assertEquals("aa:bb:cc:dd:ee:01", remaining.get(0).bssid);
    }

    @Test
    public void blockedNetworkDialogExplainsItselfWhenNothingWasBlocked() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();

        activity.findViewById(R.id.settings_trusted_network_blocked).performClick();
        ShadowLooper.idleMainLooper();

        assertNull("An empty log must not open the row dialog",
                activity.getActiveBlockedNetworksDialog());
        assertTrue(KeepADBTrustedNetwork.getEntries(activity).isEmpty());
    }

    /**
     * #475: the "Allow" button in the blocked-access-points dialog must go through the same
     * trust-and-connect entry point MainActivity's per-access-point trust button uses (#470),
     * not just a bare {@code addBssid} that leaves the user waiting for Keep-Alive's next pass.
     * Also verifies the #474 fix keeps applying: allowing clears only this BSSID's own pending
     * prompt marker.
     */
    @Test
    public void blockedNetworkDialogAllowButtonImmediatelyAttemptsTheConnectionItWasBlocking() {
        String bssid = "aa:bb:cc:dd:ee:01";
        grantAutoEnableForTesting(bssid, "Cafe-WLAN");

        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();
        // Raises this BSSID's own pending-prompt marker, exactly like the real block path does.
        assertTrue(KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(activity));
        controller.pause().resume();
        ShadowLooper.idleMainLooper();

        Button entryPoint = activity.findViewById(R.id.settings_trusted_network_blocked);
        entryPoint.performClick();
        ShadowLooper.idleMainLooper();
        AlertDialog dialog = activity.getActiveBlockedNetworksDialog();
        assertNotNull("Blocked networks dialog should be showing", dialog);
        List<Button> allowButtons =
                findViewsByType(dialog.findViewById(android.R.id.custom), Button.class);
        assertEquals(1, allowButtons.size());

        allowButtons.get(0).performClick();
        ShadowLooper.idleMainLooper();

        assertTrue("Wireless Debugging must be turned on immediately after allowing the "
                        + "blocked access point from the recently-blocked list",
                KeepADB.isEnabled(activity));
        assertTrue("Allowing the access point must clear its own pending prompt marker",
                KeepADBNetworkTrustPrompt.shouldPrompt(activity, bssid, System.currentTimeMillis()));
    }

    /**
     * #475: the manual "add current network" button must immediately attempt the connection it
     * was blocking too, matching MainActivity's per-access-point trust button (#470) instead of
     * only the notification's own "allow" action doing so.
     */
    @Test
    public void addCurrentNetworkButtonImmediatelyAttemptsTheConnectionItWasBlocking() {
        String bssid = "aa:bb:cc:dd:ee:02";
        grantAutoEnableForTesting(bssid, "HomeMesh");

        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();

        Button addButton = activity.findViewById(R.id.settings_trusted_network_add);
        addButton.performClick();
        ShadowLooper.idleMainLooper();

        assertTrue("Wireless Debugging must be turned on immediately after manually adding the "
                        + "current, blocking access point",
                KeepADB.isEnabled(activity));
    }

    /**
     * #475: accepting the mesh-BSSID convenience prompt must also immediately attempt the
     * connection for each newly trusted access point, the same way the other two trust entry
     * points above do -- proven here by the mesh BSSID's own pending prompt marker (raised while
     * the device was briefly connected to it) getting cleared, which a bare {@code addBssid} call
     * would not do.
     */
    @Test
    public void meshBssidConvenienceImmediatelyAttemptsTheConnectionAndClearsItsPromptState() {
        String currentBssid = "aa:bb:cc:dd:ee:03";
        String meshBssid = "aa:bb:cc:dd:ee:04";
        String ssid = "MeshHome";
        grantAutoEnableForTesting(currentBssid, ssid);

        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();
        // The device was briefly connected to the mesh AP earlier and got its own pending
        // prompt for it, before roaming to the network under test.
        connectTo(ssid, meshBssid);
        assertTrue(KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(activity));
        KeepADBBssidHistory.recordObservation(activity, ssid, meshBssid);
        connectTo(ssid, currentBssid);

        Button addButton = activity.findViewById(R.id.settings_trusted_network_add);
        addButton.performClick();
        ShadowLooper.idleMainLooper();
        AlertDialog meshDialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("The mesh convenience dialog should be showing", meshDialog);

        meshDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        assertEquals(2, KeepADBTrustedNetwork.getEntries(activity).size());
        assertTrue("Wireless Debugging must be turned on", KeepADB.isEnabled(activity));
        assertTrue("Accepting the mesh BSSID must clear its own pending prompt marker",
                KeepADBNetworkTrustPrompt.shouldPrompt(activity, meshBssid, System.currentTimeMillis()));
    }

    // #471: settings cards start collapsed and expand/collapse independently, without any
    // persisted state.
    private static final int[][] COLLAPSIBLE_CARDS = {
            {R.id.settings_language_header, R.id.settings_language_body},
            {R.id.settings_webhook_header, R.id.settings_webhook_body},
            {R.id.settings_usb_notification_header, R.id.settings_usb_notification_body},
            {R.id.settings_usb_handover_header, R.id.settings_usb_handover_body},
            {R.id.settings_trusted_network_header, R.id.settings_trusted_network_body},
            {R.id.settings_notification_header, R.id.settings_notification_body},
            {R.id.settings_display_header, R.id.settings_display_body},
            {R.id.settings_advice_banner_header, R.id.settings_advice_banner_body},
            {R.id.settings_diagnostics_header, R.id.settings_diagnostics_body},
            {R.id.settings_version_header, R.id.settings_version_body},
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
        View languageBody = activity.findViewById(R.id.settings_language_body);

        webhookHeader.performClick();
        assertEquals(View.VISIBLE, webhookBody.getVisibility());
        assertEquals("−", webhookArrow.getText().toString());
        // The untouched card must stay exactly as it was -- collapse is per card, not global.
        assertEquals(View.GONE, languageBody.getVisibility());

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
