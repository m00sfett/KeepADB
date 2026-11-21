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
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toolbar;

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

    @Rule
    public final KeepADBRegisterClientResetRule registerClientResetRule =
            new KeepADBRegisterClientResetRule();

    @Before
    public void setUp() {
        RuntimeEnvironment.getApplication()
                .getSharedPreferences("keepadb_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
        // A new installation as the application leaves it (#904): only the privacy marker.
        KeepADBPreferences.migratePrivacySplit(RuntimeEnvironment.getApplication());
    }

    @After
    public void tearDown() {
        RuntimeEnvironment.getApplication()
                .getSharedPreferences("keepadb_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
        KeepADB.resetForTesting();
        KeepADBEndpointCoordinator.resetForTesting();
    }

    /** #677: every main card header announces its expanded/collapsed state to TalkBack. */
    @Test
    public void mainCardHeadersKeepStateDescriptionInSyncWithExpandedState() {
        KeepADBPreferences.setAppLanguage(RuntimeEnvironment.getApplication(), "en");
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        int[] headers = {R.id.settings_webhook_header,
                R.id.settings_misc_header,
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
     * #597/#904: the persistent main notification gates its port/IP endpoint text behind the
     * notification privacy switch too, so it needs the same "switch re-renders the already-visible
     * notification" wiring (#592).
     */
    @Test
    public void notificationPrivacySwitchAlsoRerendersTheVisibleMainNotification() throws Exception {
        android.app.Application app = RuntimeEnvironment.getApplication();
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
        KeepADBPreferences.setAppLanguage(app, "en");
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(true);
        KeepADB.setGatewayForTesting(gateway);
        KeepADBPreferences.setKeepAliveEnabled(app, true);
        setCoordinatorEndpoint("192.168.1.50", 39123);
        // #922: refresh() verifies the cached endpoint on a worker thread with a real socket probe.
        // Against the unroutable test address that probe fails late and invalidates the endpoint
        // while the assertions below run. Confirm reachability and run the worker inline instead.
        KeepADBEndpointCoordinator.setReachabilityProbeForTesting((host, port, timeoutMs) -> true);
        KeepADBEndpointCoordinator.setWorkerStarterForTesting(Thread::run);
        KeepADBEndpointCoordinator.refresh(app);
        assertEquals("refresh() must have verified the cached endpoint once", 1,
                KeepADBEndpointCoordinator.getVerificationWorkerStartCountForTesting());
        assertFalse("The verification must have finished before the assertions",
                KeepADBEndpointCoordinator.isVerificationInFlightForTesting());

        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        Switch toggle = activity.findViewById(R.id.privacy_switch_notifications);
        assertNotNull(toggle);
        assertFalse("Notification privacy must be off by default", toggle.isChecked());
        CharSequence textBefore = postedMainNotification(app).extras.getCharSequence(
                android.app.Notification.EXTRA_TEXT);
        assertTrue("A new installation shows the endpoint: " + textBefore,
                String.valueOf(textBefore).contains("39123"));

        toggle.performClick();
        assertTrue(KeepADBPreferences.isNotificationPrivacyEnabled(app));
        KeepADBNotificationTextScan.assertMentionsNone(postedMainNotification(app), "39123", "192.168.1.50");

        toggle.performClick();
        assertFalse(KeepADBPreferences.isNotificationPrivacyEnabled(app));
        CharSequence textOn = postedMainNotification(app).extras.getCharSequence(
                android.app.Notification.EXTRA_TEXT);
        assertTrue("The visible main notification must be re-rendered with the endpoint: " + textOn,
                String.valueOf(textOn).contains("39123") && String.valueOf(textOn).contains("192.168.1.50"));
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
    }

    @Test
    public void languageSelectorHasFormattedAccessibilityDescription() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();

        View languageSelector = activity.findViewById(R.id.btn_settings_language);
        assertNotNull(languageSelector);
        String currentLanguageTag = KeepADBLocaleHelper.getSelectedLanguageTag(activity);
        String languageDisplayName = KeepADBLocaleHelper.getLanguageDisplayName(activity, currentLanguageTag);
        assertEquals(activity.getString(R.string.settings_language_accessibility, languageDisplayName),
                languageSelector.getContentDescription());
    }

    @Test
    public void languageButtonReplacesTheOverflowAndOpensExistingDialog() {
        // #942: no overflow menu any more; the language button opens the dialog directly.
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();
        Toolbar toolbar = activity.findViewById(R.id.header_bar);
        assertEquals(0, toolbar.getMenu().size());
        View language = activity.findViewById(R.id.btn_settings_language);
        assertNotNull(language);
        assertTrue(language instanceof android.widget.ImageButton);
        assertNotNull(activity.findViewById(R.id.btn_back));
        assertTrue(activity.findViewById(R.id.btn_back).hasOnClickListeners());
        assertNotNull(activity.findViewById(R.id.btn_toggle_privacy_mode));
        assertTrue(activity.findViewById(R.id.btn_toggle_privacy_mode).hasOnClickListeners());
        assertTrue(language.performClick());
        assertDialogShowingWithTitle(R.string.settings_language_dialog_title, activity);
        ShadowAlertDialog.getLatestAlertDialog().dismiss();
        activity.findViewById(R.id.btn_back).performClick();
        assertTrue(activity.isFinishing());
        controller.pause().stop().destroy();
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
        Switch toggle = activity.findViewById(R.id.settings_webhook_header_toggle);
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

        // #1034 review: the header switch turns the saved URL back on and leaves an unsaved
        // draft alone (the assistant's form switch still sanitizes what is typed, see
        // KeepADBWebhookFormTest).
        input.setText("http://admin:pass@100.111.111.22:50829/register/other");
        toggle.performClick();
        ShadowLooper.idleMainLooper();

        assertTrue(KeepADBPreferences.isRegisterWebhookEnabled(activity));
        assertEquals("only the enabled state changes", "http://100.111.111.21:50829/register/s20",
                KeepADBPreferences.getRegisterWebhookUrl(activity));
        assertEquals("the draft stays in the field",
                "http://admin:pass@100.111.111.22:50829/register/other", input.getText().toString());
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
            {R.id.settings_privacy_header, R.id.settings_privacy_body},
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
     * was removed from this content column entirely (it is now a button in the header, #942), so it is no longer part of this contract.
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

    /** #1034: one switch, in the header, with the state above it, visible while collapsed. */
    @Test
    public void theWebhookSwitchAndStatusSitInTheCollapsedHeader() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();
        Switch header = activity.findViewById(R.id.settings_webhook_header_toggle);
        TextView status = activity.findViewById(R.id.settings_webhook_status);

        assertEquals(View.GONE, activity.findViewById(R.id.settings_webhook_body).getVisibility());
        assertTrue("the switch is usable without expanding", header.isShown());
        assertFalse(header.isChecked());
        assertEquals(View.GONE, activity.findViewById(R.id.settings_webhook_toggle).getVisibility());
        assertEquals(activity.getString(R.string.webhook_status_unconfigured),
                status.getText().toString());

        controller.pause().stop().destroy();

        // A saved URL, sync off: the form loads the URL on start.
        KeepADBPreferences.setRegisterWebhookUrl(activity, "https://100.111.111.21:50829/register/s20");
        controller = Robolectric.buildActivity(SettingsActivity.class).setup();
        activity = controller.get();
        header = activity.findViewById(R.id.settings_webhook_header_toggle);
        status = activity.findViewById(R.id.settings_webhook_status);
        assertEquals(activity.getString(R.string.webhook_status_disabled),
                status.getText().toString());

        header.performClick();
        ShadowLooper.idleMainLooper();
        assertTrue(KeepADBPreferences.isRegisterWebhookEnabled(activity));
        assertTrue(header.isChecked());
        assertEquals("On · last reported: never", status.getText().toString());
        assertEquals("Switching does not open the card",
                View.GONE, activity.findViewById(R.id.settings_webhook_body).getVisibility());

        header.performClick();
        ShadowLooper.idleMainLooper();
        assertFalse(KeepADBPreferences.isRegisterWebhookEnabled(activity));
        assertEquals("Only the enabled state changes",
                "https://100.111.111.21:50829/register/s20",
                KeepADBPreferences.getRegisterWebhookUrl(activity));
        controller.pause().stop().destroy();
    }

    /**
     * #1034 design review: an enable without a valid URL is refused; the collapsed card opens so
     * the message and the field are visible.
     */
    @Test
    public void aRefusedEnableOpensTheCardAndShowsTheError() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();
        Switch header = activity.findViewById(R.id.settings_webhook_header_toggle);

        header.performClick();
        ShadowLooper.idleMainLooper();

        assertFalse(header.isChecked());
        assertFalse(KeepADBPreferences.isRegisterWebhookEnabled(activity));
        assertEquals(View.VISIBLE, activity.findViewById(R.id.settings_webhook_body).getVisibility());
        TextView error = activity.findViewById(R.id.settings_webhook_error);
        assertTrue(error.isShown());
        assertEquals(activity.getString(R.string.settings_webhook_error_missing_url),
                error.getText().toString());
        assertTrue(activity.findViewById(R.id.settings_webhook_url).hasFocus());
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
                activity.findViewById(R.id.settings_webhook_url).isShown());

        EditText input = activity.findViewById(R.id.settings_webhook_url);
        Switch toggle = activity.findViewById(R.id.settings_webhook_header_toggle);
        input.setText("https://100.111.111.21:50829/register/s20");
        // #1034 review: a typed but unsaved URL is not enough for the header switch ...
        toggle.performClick();
        ShadowLooper.idleMainLooper();
        assertFalse(KeepADBPreferences.isRegisterWebhookEnabled(activity));
        assertFalse(toggle.isChecked());

        // ... "Save URL" stores it and turns sync on (#733), and the switch follows.
        activity.findViewById(R.id.settings_webhook_save).performClick();
        ShadowLooper.idleMainLooper();
        assertTrue(KeepADBPreferences.isRegisterWebhookEnabled(activity));
        assertTrue(toggle.isChecked());
        assertEquals("https://100.111.111.21:50829/register/s20",
                KeepADBPreferences.getRegisterWebhookUrl(activity));
    }

    /** #1034 review: a report finishing while Settings is open updates the status line. */
    @Test
    public void theHeaderStatusFollowsABackgroundReport() {
        KeepADBPreferences.setRegisterWebhookUrl(RuntimeEnvironment.getApplication(),
                "https://100.111.111.21:50829/register/s20");
        KeepADBPreferences.setRegisterWebhookEnabled(RuntimeEnvironment.getApplication(), true);
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();
        TextView status = activity.findViewById(R.id.settings_webhook_status);
        assertEquals("On · last reported: never", status.getText().toString());

        KeepADBPreferences.setWebhookLastReportStatus(activity, KeepADBPreferences.WEBHOOK_STATUS_FAILED);
        KeepADBRegisterClient.notifyRegisterStateChangedForTesting(activity);
        ShadowLooper.idleMainLooper();
        assertEquals("On · last reported: failed", status.getText().toString());

        controller.pause();
        KeepADBPreferences.setWebhookLastReportStatus(activity, KeepADBPreferences.WEBHOOK_STATUS_NEVER);
        KeepADBRegisterClient.notifyRegisterStateChangedForTesting(activity);
        ShadowLooper.idleMainLooper();
        assertEquals("no listener once paused", "On · last reported: failed",
                status.getText().toString());
        controller.stop().destroy();
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
     * #959: "Protection" is one row that opens its own page, not a card that expands inline. None
     * of the former card's content is on the Settings page any more, and the removed controls of
     * #769 stay gone.
     */
    @Test
    public void protectionIsOneRowThatOpensItsPageAndNothingOfTheFormerCardExpandsInline() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();

        View row = activity.findViewById(R.id.settings_protection_row);
        assertNotNull(row);
        assertTrue(row.isShown());
        assertTrue(row.isClickable());
        for (int gone : new int[] {R.id.network_status_label, R.id.network_networks_row,
                R.id.settings_background_location_button, R.id.settings_force_panel,
                R.id.settings_untrusted_shutdown_toggle, R.id.settings_security_check_toggle}) {
            assertNull("The former card's content is on the Protection page: " + gone,
                    activity.findViewById(gone));
        }
        for (String removed : new String[] {"settings_network_beta_panel",
                "settings_network_beta_header", "settings_network_beta_body",
                "settings_network_beta_arrow", "settings_trust_by_name_toggle",
                "network_level_line", "network_comfort_no_effect", "network_mode_group",
                "network_allowed_row", "network_prevented_row", "network_observed_row",
                "settings_wifi_aps_feature_toggle", "network_ssid_header",
                "settings_trusted_ssid_toggle", "wifi_ssids_list",
                "settings_trusted_network_beta_badge", "settings_wifi_aps_beta_badge"}) {
            assertEquals("The removed control must be gone: " + removed, 0,
                    activity.getResources().getIdentifier(removed, "id", activity.getPackageName()));
        }

        row.performClick();
        Intent opened = shadowOf(activity).getNextStartedActivity();
        assertEquals(ProtectionActivity.class.getName(), opened.getComponent().getClassName());
        assertFalse("The row opens the page at its top", opened.hasExtra(
                ProtectionActivity.EXTRA_FOCUS_LEVEL) || opened.hasExtra(
                ProtectionActivity.EXTRA_FOCUS_FORCE));
        controller.pause().stop().destroy();
    }

    /** #959: an old extra only forwards to the Protection page and the shell then finishes. */
    @Test
    public void anOldProtectionExtraForwardsAndFinishesTheSettingsShell() {
        for (String extra : new String[] {SettingsActivity.EXTRA_FOCUS_FORCE,
                SettingsActivity.EXTRA_FOCUS_NETWORK, SettingsActivity.EXTRA_FOCUS_NETWORK_CARD}) {
            Intent launch = new Intent(RuntimeEnvironment.getApplication(), SettingsActivity.class)
                    .putExtra(extra, true);
            ActivityController<SettingsActivity> controller =
                    Robolectric.buildActivity(SettingsActivity.class, launch).setup();
            SettingsActivity activity = controller.get();
            assertEquals(ProtectionActivity.class.getName(),
                    shadowOf(activity).getNextStartedActivity().getComponent().getClassName());
            assertTrue("Finished after forwarding " + extra, activity.isFinishing());
        }
    }

    /** #959: a normally opened Settings page stays open and is not finished by the forwarding. */
    @Test
    public void aNormallyOpenedSettingsPageIsNotFinished() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();
        assertFalse(activity.isFinishing());
        activity.findViewById(R.id.settings_protection_row).performClick();
        controller.pause();
        controller.resume();
        assertFalse(activity.isFinishing());
        controller.pause().stop().destroy();
    }

    /** #959: the row's summary names the protection level and the counts, and hides the counts. */
    @Test
    public void theProtectionRowSummaryNamesTheLevelAndTheCountsAndFollowsThePrivacyMode() {
        android.content.Context context = RuntimeEnvironment.getApplication();
        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:01", "Home");
        KeepADBPreferences.setAppPrivacyEnabled(context, false);
        SettingsActivity shown = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        TextView summary = shown.findViewById(R.id.settings_protection_summary);
        assertEquals(context.getString(R.string.networks_level,
                        context.getString(R.string.force_level_maximum))
                        + " \u00b7 " + context.getString(R.string.networks_count, 1, 0),
                summary.getText().toString());

        KeepADBPreferences.setAppPrivacyEnabled(context, true);
        SettingsActivity hidden = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        assertEquals(context.getString(R.string.networks_level,
                        context.getString(R.string.force_level_maximum))
                        + " \u00b7 " + context.getString(R.string.networks_count_hidden),
                ((TextView) hidden.findViewById(R.id.settings_protection_summary)).getText()
                        .toString());
    }

    /**
     * #510/#521: the four notice/display-preference switches (persistent notification, keep
     * display on, security/network advice banner, battery-optimization advice) are bundled
     * directly inside the single collapsible "Sonstiges" card. None
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
        };
        for (int id : miscSwitches) {
            assertNotNull("Switch must exist before expansion: " + id, activity.findViewById(id));
        }

        activity.findViewById(R.id.settings_misc_header).performClick();
        assertEquals(View.VISIBLE, outerBody.getVisibility());

        // All three switches must become visible together, with no further click needed -- there
        // is exactly one expand step, not one per section (acceptance criterion 2).
        for (int id : miscSwitches) {
            assertTrue("Switch must be shown once the outer card is expanded: " + id,
                    activity.findViewById(id).isShown());
        }
    }

    /**
     * #510 acceptance criterion 7 / #618, as amended by #760: a fresh install leaves the
     * Wi-Fi &amp; access points feature disabled -- the visual regrouping must not change that
     * default -- and starts on the secure network default, the allowlist (trusted access points
     * only), with the name switch off.
     */
    @Test
    public void bothNetworkFeaturesAreDisabledOnFreshInstall() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();

        assertTrue("A new installation must start on the allowlist (#760)",
                KeepADBTrustedNetwork.isAllowlistMode(activity));
        assertFalse("The name alternative must default to off",
                KeepADBTrustedNetwork.isSsidMatchingEnabled(activity));

        assertFalse(KeepADBTrustedNetwork.isTrustByNameEnabled(activity));
        assertTrue(((TextView) activity.findViewById(R.id.settings_protection_summary)).getText()
                .toString().startsWith(activity.getString(R.string.networks_level,
                        KeepADBForceNotice.levelLabel(activity))));
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
    public void languageSelectionDialogSurvivesRotation() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        controller.get().findViewById(R.id.btn_settings_language).performClick();
        assertDialogShowingWithTitle(R.string.settings_language_dialog_title, controller.get());

        ActivityController<SettingsActivity> restored = rotate(controller);
        assertDialogShowingWithTitle(R.string.settings_language_dialog_title, restored.get());

        ShadowAlertDialog.getLatestAlertDialog().dismiss();
        restored.pause().stop().destroy();
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

    /**
     * #793: a test that calls the reset itself stays green if the {@code @Rule} field is deleted.
     * This pins that {@link KeepADBRegisterClientResetRule} is really applied to the class.
     */
    @Test
    public void theRegisterClientResetRuleIsAppliedToThisClass() {
        boolean applied = false;
        for (java.lang.reflect.Field field : SettingsActivityTest.class.getFields()) {
            if (field.getType() == KeepADBRegisterClientResetRule.class
                    && field.isAnnotationPresent(org.junit.Rule.class)) {
                applied = true;
            }
        }
        org.junit.Assert.assertTrue(
                "KeepADBRegisterClientResetRule must be a public @Rule field", applied);
    }
}
