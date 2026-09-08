package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

/**
 * Unit and behavioral tests for {@link SettingsActivity}, covering dialog scrollability,
 * TalkBack context labels, draft persistence across activity recreation, and dialog cleanup (Issue #322).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SettingsActivityTest {

    @Before
    public void setUp() {
        RuntimeEnvironment.getApplication()
                .getSharedPreferences("keepadb_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
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

    @SuppressWarnings("unchecked")
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
