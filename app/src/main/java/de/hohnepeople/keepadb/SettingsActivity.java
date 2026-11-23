package de.hohnepeople.keepadb;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Central settings screen for KeepADB options (Keep-Alive, Language, Webhook, etc.). */
public class SettingsActivity extends Activity {
    /**
     * #763: formerly the request to expand the force-mode row of the Network card. The card is the
     * Protection page since #959 ({@link ProtectionActivity#EXTRA_FOCUS_FORCE}); a stale
     * notification of an older version that still carries this extra is forwarded there.
     */
    public static final String EXTRA_FOCUS_FORCE = "focus_force";
    /**
     * #801: formerly the request to expand the Network card at its protection level row. Since
     * #959 it is forwarded to {@link ProtectionActivity#EXTRA_FOCUS_LEVEL}, like the other two
     * extras of the former card.
     */
    public static final String EXTRA_FOCUS_NETWORK = "focus_network";
    public static final String EXTRA_FOCUS_DETAILS = "focus_notification_details";
    public static final String EXTRA_FOCUS_WEBHOOK = "focus_webhook";
    /** #891/#893: the webhook alert's "Battery tips" action lands on the VPN battery hint. */
    public static final String EXTRA_FOCUS_WEBHOOK_VPN_HINT = "focus_webhook_vpn_hint";
    public static final String EXTRA_FOCUS_MISC = "focus_misc";
    /** #941: expands and scrolls to the Privacy card (eye long-press menu, home menu). */
    public static final String EXTRA_FOCUS_PRIVACY = "focus_privacy";
    /** #940: expands and scrolls to the Diagnostics and Maintenance card (home menu). */
    public static final String EXTRA_FOCUS_DIAGNOSTICS = "focus_diagnostics";
    /** #999: opens the Automation card, the plugin edit screen links here while it is off. */
    public static final String EXTRA_FOCUS_AUTOMATION = "focus_automation";
    /** Formerly the heading of the Network card; since #959 it opens the Protection page. */
    public static final String EXTRA_FOCUS_NETWORK_CARD = "focus_network_card";
    private static final String STATE_CONSUMED_FOCUS = "settings_consumed_focus";
    private static final String STATE_STARTED_ONLY_TO_FORWARD = "settings_started_only_to_forward";
    /** True when this instance was started only to forward an old extra to the Protection page. */
    private boolean startedOnlyToForward;
    private final java.util.ArrayList<String> consumedFocus = new java.util.ArrayList<>();
    /**
     * #672: flags for the reset-app and language dialogs showing at the time of
     * a {@code recreate()} (rotation). Pure "was showing" markers; a restored reset-app dialog is
     * only re-shown and still needs the user's own confirm tap. The Bundle keys of the feedback
     * report dialog are owned by {@link KeepADBDiagnosticsController} (#698); those of the
     * background-location dialog by {@link KeepADBNetworkCard}, on the Protection page (#959).
     */
    static final String STATE_RESET_APP_SHOWING = "settings_reset_app_showing";
    static final String STATE_LANGUAGE_SELECTION_SHOWING = "settings_language_selection_showing";

    private ScrollView scrollView;
    private View permissionPanel;
    private View languageButton;
    private TextView onboardingSummary;

    private Switch hideNotificationToggle;
    private TextView hideNotificationSubtext;
    /** #904, #941: the block with the privacy switches, in the Privacy card. */
    private View privacySwitches;
    private Switch keepDisplayOnToggle;

    private TextView protectionSummary;

    private KeepADBWebhookForm webhookForm;
    private KeepADBAutomationSection automationSection;
    private TextView versionNameText;
    private TextView versionCodeText;
    private TextView versionDebugBadge;
    private TextView websiteLinkText;

    // #698: the diagnostics export and the feedback report dialog (draft, preview, opt-in).
    private KeepADBDiagnosticsController diagnosticsController;
    private KeepADBSettingsBackupController backupController;

    private AlertDialog activeResetAppDialog;
    /** #672: the language selection dialog, if showing. */
    private AlertDialog activeLanguageSelectionDialog;
    /** #1047: the default-duration choice, dismissed with the activity so it cannot leak. */
    private AlertDialog activeTimerDefaultDialog;

    static final String WEBSITE_URL = "https://keepadb.roteson.de";

    private static final String CARD_COLLAPSED_SYMBOL = "+";
    private static final String CARD_EXPANDED_SYMBOL = "−";

    // Card expansion lives only in the view tree; opening Settings starts cards collapsed.
    // The permission notice remains visible when applicable, while the version entry is static.
    private static final int[][] COLLAPSIBLE_CARDS = {
            {R.id.settings_webhook_header, R.id.settings_webhook_body, R.id.settings_webhook_arrow},
            {R.id.settings_automation_header, R.id.settings_automation_body,
                    R.id.settings_automation_arrow},
            {R.id.settings_privacy_header, R.id.settings_privacy_body,
                    R.id.settings_privacy_arrow},
            {R.id.settings_misc_header, R.id.settings_misc_body, R.id.settings_misc_arrow},
            {R.id.settings_backup_header, R.id.settings_backup_body, R.id.settings_backup_arrow},
            {R.id.settings_diagnostics_header, R.id.settings_diagnostics_body, R.id.settings_diagnostics_arrow},
    };

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(KeepADBLocaleHelper.wrapContext(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startedOnlyToForward = savedInstanceState != null
                ? savedInstanceState.getBoolean(STATE_STARTED_ONLY_TO_FORWARD, false)
                : getIntent().hasExtra(EXTRA_FOCUS_FORCE)
                        || getIntent().hasExtra(EXTRA_FOCUS_NETWORK)
                        || getIntent().hasExtra(EXTRA_FOCUS_NETWORK_CARD);
        setContentView(R.layout.activity_settings);
        // #324: keep header and content clear of the system bars and the keyboard under forced
        // edge-to-edge.
        KeepADBWindowInsets.apply(
                getWindow(),
                findViewById(R.id.header_bar),
                findViewById(R.id.settings_scroll_view));

        versionNameText = findViewById(R.id.settings_version_name);
        versionCodeText = findViewById(R.id.settings_version_code);
        versionDebugBadge = findViewById(R.id.settings_version_debug_badge);
        bindVersionInfo();

        websiteLinkText = findViewById(R.id.settings_website_link);
        websiteLinkText.setPaintFlags(websiteLinkText.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        websiteLinkText.setOnClickListener(v -> openWebLink(WEBSITE_URL));

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        // #725: same eye as on the main view; refresh() redraws the masked card and webhook.
        KeepADBPrivacyToggle.bind(this, this::refresh);
        scrollView = findViewById(R.id.settings_scroll_view);
        permissionPanel = findViewById(R.id.settings_permission_panel);

        // #471: wire every card's header to toggle its own body, independently of the others.
        for (int[] card : COLLAPSIBLE_CARDS) {
            bindCollapsibleCard(card[0], card[1], card[2]);
        }

        // #942: the language button replaces the overflow menu whose only entry was Language.
        languageButton = findViewById(R.id.btn_settings_language);
        languageButton.setOnClickListener(v -> showLanguageSelectionDialog());

        hideNotificationToggle = findViewById(R.id.settings_hide_notification_toggle);
        hideNotificationSubtext = findViewById(R.id.settings_hide_notification_subtext);
        hideNotificationToggle.setOnClickListener(v -> {
            if (KeepADBPreferences.isKeepAliveEnabled(this)) {
                refresh();
                return;
            }
            // #456: the switch shows positive framing ("persistent notification" ON = visible),
            // while the underlying preference and its accessor names stay hide-framed. Invert here.
            boolean wantVisible = hideNotificationToggle.isChecked();
            boolean wantHidden = !wantVisible;
            KeepADBPreferences.setNotificationHidden(this, wantHidden);
            KeepADBEndpointCoordinator.refresh(this);
            Toast.makeText(this,
                    wantHidden ? R.string.settings_notification_hidden_toast : R.string.settings_notification_visible_toast,
                    Toast.LENGTH_SHORT).show();
            refresh();
        });

        privacySwitches = findViewById(R.id.privacy_switches);
        KeepADBPrivacySwitches.bind(this, privacySwitches, this::refresh);

        // #1000: the preselected timer duration.
        findViewById(R.id.settings_timer_default_row).setOnClickListener(v -> showTimerDefaultChoice());

        keepDisplayOnToggle = findViewById(R.id.settings_keep_display_on_toggle);
        keepDisplayOnToggle.setOnClickListener(v ->
                KeepADBPreferences.setKeepDisplayOnEnabled(this, keepDisplayOnToggle.isChecked()));

        // #761: the setup assistant, whole from the top row.
        onboardingSummary = findViewById(R.id.settings_onboarding_summary);
        findViewById(R.id.settings_onboarding_row).setOnClickListener(v ->
                startActivity(OnboardingActivity.fullIntent(this)));

        // #959: the Protection page holds the former Network card; the level is chosen there.
        protectionSummary = findViewById(R.id.settings_protection_summary);
        // #1047: registered once here, not on every onResume().
        findViewById(R.id.settings_muted_warnings).setOnClickListener(v -> startActivity(
                new Intent(this, WarningsActivity.class).putExtra(WarningsActivity.EXTRA_MUTES, true)));
        findViewById(R.id.settings_protection_row).setOnClickListener(v ->
                startActivity(ProtectionActivity.intent(this)));

        diagnosticsController = new KeepADBDiagnosticsController(this, this::openWebLink);
        findViewById(R.id.settings_reset_app).setOnClickListener(v -> showResetAppDialog());

        backupController = new KeepADBSettingsBackupController(this, this::refresh,
                () -> webhookForm.reloadFromPreferences());

        // #1034: one switch, in the section header; a refused enable opens the card so the
        // error and the URL field are visible.
        webhookForm = new KeepADBWebhookForm(this, this::refresh,
                findViewById(R.id.settings_webhook_header_toggle),
                () -> setCardExpanded(this, findViewById(R.id.settings_webhook_header),
                        findViewById(R.id.settings_webhook_body),
                        findViewById(R.id.settings_webhook_arrow), true));
        automationSection = new KeepADBAutomationSection(this, this::openWebLink); // #998
        webhookForm.restoreDraft(savedInstanceState);
        findViewById(R.id.settings_webhook_history).setOnClickListener(
                v -> startActivity(ReportHistoryActivity.intent(this)));

        if (savedInstanceState != null) {
            java.util.ArrayList<String> restoredFocus =
                    savedInstanceState.getStringArrayList(STATE_CONSUMED_FOCUS);
            if (restoredFocus != null) consumedFocus.addAll(restoredFocus);
            diagnosticsController.restore(savedInstanceState);
            backupController.restore(savedInstanceState);
            // #672: re-show the remaining plain dialogs. The reset-app dialog is only re-shown,
            // its destructive action still runs solely from the user's own confirm tap.
            if (savedInstanceState.getBoolean(STATE_RESET_APP_SHOWING, false)) {
                showResetAppDialog();
            }
            if (savedInstanceState.getBoolean(STATE_LANGUAGE_SELECTION_SHOWING, false)) {
                showLanguageSelectionDialog();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderTimerDefault();
        webhookForm.ensureDraftInitialized();
        KeepADBPrivacyToggle.update(this);
        // #1034 review: a report finishing in the background updates the header's status line.
        RegisterStateNotifier.setListener(webhookStateListener);
        refresh();

        // #959: the three extras of the former Network card lead to the Protection page. Current
        // senders open it directly; these only come from a notification an older version posted.
        boolean forwarded = false;
        if (consumeFocus(EXTRA_FOCUS_FORCE)) {
            startActivity(ProtectionActivity.forceIntent(this));
            forwarded = true;
        }
        if (consumeFocus(EXTRA_FOCUS_NETWORK)) {
            startActivity(ProtectionActivity.levelIntent(this));
            forwarded = true;
        }
        if (consumeFocus(EXTRA_FOCUS_NETWORK_CARD)) {
            startActivity(ProtectionActivity.intent(this));
            forwarded = true;
        }
        // Back from the Protection page then returns to where the user came from, not to a
        // Settings page they never asked for.
        if (forwarded && startedOnlyToForward) {
            finish();
            return;
        }
        if (consumeFocus(EXTRA_FOCUS_WEBHOOK)) {
            focusCard(R.id.settings_webhook_header, R.id.settings_webhook_body,
                    R.id.settings_webhook_arrow);
            // #966: the card header, not the URL field. Focusing the EditText raises the
            // keyboard over the section the user came to read; typing starts with a tap on it.
            focusWarningTarget(findViewById(R.id.settings_webhook_header));
        }
        if (consumeFocus(EXTRA_FOCUS_WEBHOOK_VPN_HINT)) {
            focusCard(R.id.settings_webhook_header, R.id.settings_webhook_body,
                    R.id.settings_webhook_arrow);
            // Hidden while the webhook is off; the expanded section is the target then.
            View hint = findViewById(R.id.settings_webhook_vpn_hint);
            if (hint.getVisibility() == View.VISIBLE) {
                focusWarningTarget(findViewById(R.id.settings_webhook_vpn_hint_text));
            }
        }
        if (consumeFocus(EXTRA_FOCUS_DETAILS)) {
            focusPrivacyCard();
            focusWarningTarget(findViewById(R.id.privacy_switch_notifications));
        }
        if (consumeFocus(EXTRA_FOCUS_PRIVACY)) {
            focusPrivacyCard();
        }
        if (consumeFocus(EXTRA_FOCUS_AUTOMATION)) {
            focusCard(R.id.settings_automation_header, R.id.settings_automation_body,
                    R.id.settings_automation_arrow);
        }
        if (consumeFocus(EXTRA_FOCUS_DIAGNOSTICS)) {
            focusCard(R.id.settings_diagnostics_header, R.id.settings_diagnostics_body,
                    R.id.settings_diagnostics_arrow);
        }
        if (consumeFocus(EXTRA_FOCUS_MISC)) {
            focusCard(R.id.settings_misc_header, R.id.settings_misc_body,
                    R.id.settings_misc_arrow);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        RegisterStateNotifier.clearListener(webhookStateListener);
        // #998: the revealed token is hidden again once the screen is left.
        automationSection.onPause();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (!backupController.onActivityResult(requestCode, resultCode, data)) {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putStringArrayList(STATE_CONSUMED_FOCUS, new java.util.ArrayList<>(consumedFocus));
        outState.putBoolean(STATE_STARTED_ONLY_TO_FORWARD, startedOnlyToForward);
        webhookForm.saveState(outState);
        diagnosticsController.saveState(outState);
        backupController.saveState(outState);
        outState.putBoolean(STATE_RESET_APP_SHOWING, isShowing(activeResetAppDialog));
        outState.putBoolean(STATE_LANGUAGE_SELECTION_SHOWING,
                isShowing(activeLanguageSelectionDialog));
    }

    private static boolean isShowing(AlertDialog dialog) {
        return dialog != null && dialog.isShowing();
    }

    @Override
    protected void onDestroy() {
        diagnosticsController.destroy();
        backupController.destroy();
        automationSection.destroy();

        if (activeResetAppDialog != null) {
            if (activeResetAppDialog.isShowing()) {
                activeResetAppDialog.dismiss();
            }
            activeResetAppDialog = null;
        }

        if (activeLanguageSelectionDialog != null) {
            if (activeLanguageSelectionDialog.isShowing()) {
                activeLanguageSelectionDialog.dismiss();
            }
            activeLanguageSelectionDialog = null;
        }

        if (activeTimerDefaultDialog != null) {
            if (activeTimerDefaultDialog.isShowing()) {
                activeTimerDefaultDialog.dismiss();
            }
            activeTimerDefaultDialog = null;
        }

        super.onDestroy();
    }

    static String resolveWebhookDraft(String savedUrl, String currentDraft, boolean hasCurrentDraft) {
        if (hasCurrentDraft) {
            return currentDraft == null ? "" : currentDraft;
        }
        return savedUrl == null ? "" : savedUrl;
    }

    private boolean consumeFocus(String extra) {
        if (!getIntent().hasExtra(extra)) return false;
        getIntent().removeExtra(extra);
        if (consumedFocus.contains(extra)) return false;
        consumedFocus.add(extra);
        return true;
    }

    /** #941: expands the Privacy card and scrolls to it (also the eye menu's link on this page). */
    void focusPrivacyCard() {
        focusCard(R.id.settings_privacy_header, R.id.settings_privacy_body,
                R.id.settings_privacy_arrow);
    }

    private void focusCard(int headerId, int bodyId, int arrowId) {
        View header = findViewById(headerId);
        setCardExpanded(this, header, findViewById(bodyId), findViewById(arrowId), true);
        scrollView.post(() -> {
            android.graphics.Rect rect = new android.graphics.Rect();
            header.getDrawingRect(rect);
            scrollView.offsetDescendantRectToMyCoords(header, rect);
            scrollView.smoothScrollTo(0, rect.top);
            header.requestFocus();
        });
    }

    private void focusWarningTarget(View target) {
        if (target == null) return;
        target.post(() -> {
            android.graphics.Rect rect = new android.graphics.Rect();
            target.getDrawingRect(rect);
            scrollView.offsetDescendantRectToMyCoords(target, rect);
            scrollView.smoothScrollTo(0, rect.top);
            target.setFocusable(true);
            Runnable focusTarget = () -> {
                target.requestFocusFromTouch();
                target.performAccessibilityAction(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
            };
            focusTarget.run();
            // A window arriving in touch mode can clear an early focus request on Android 11.
            if (!target.hasWindowFocus()) {
                target.getViewTreeObserver().addOnWindowFocusChangeListener(
                        new android.view.ViewTreeObserver.OnWindowFocusChangeListener() {
                            @Override public void onWindowFocusChanged(boolean hasFocus) {
                                if (!hasFocus) return;
                                target.getViewTreeObserver().removeOnWindowFocusChangeListener(this);
                                focusTarget.run();
                            }
                        });
            }
        });
    }

    /**
     * #471: wires one settings card's header to independently toggle its body's visibility
     * (and flip the +/− arrow to match) on click. See {@link #COLLAPSIBLE_CARDS} for why this
     * expand state is deliberately never persisted.
     */
    private void bindCollapsibleCard(int headerId, int bodyId, int arrowId) {
        View header = findViewById(headerId);
        View body = findViewById(bodyId);
        TextView arrow = findViewById(arrowId);
        setCardExpanded(this, header, body, arrow, body.getVisibility() == View.VISIBLE);
        header.setOnClickListener(v -> setCardExpanded(
                this, header, body, arrow, body.getVisibility() != View.VISIBLE));
    }

    /**
     * Renders one collapsible card header, body and arrow so every card announces and shows its
     * state alike.
     */
    static void setCardExpanded(Activity activity, View header, View body, TextView arrow,
                                boolean expanded) {
        // TalkBack: announce the expanded/collapsed state like the Wi-Fi-name section header (#655).
        header.setStateDescription(activity.getString(expanded
                ? R.string.card_state_expanded : R.string.card_state_collapsed));
        body.setVisibility(expanded ? View.VISIBLE : View.GONE);
        arrow.setText(expanded ? CARD_EXPANDED_SYMBOL : CARD_COLLAPSED_SYMBOL);
    }

    private void showLanguageSelectionDialog() {
        if (isShowing(activeLanguageSelectionDialog)) return;
        AlertDialog picker = KeepADBLanguagePicker.create(this);
        activeLanguageSelectionDialog = picker;
        picker.setOnDismissListener(d -> {
            if (activeLanguageSelectionDialog == d) {
                activeLanguageSelectionDialog = null;
            }
        });
        picker.show();
    }

    /** Opens a web link; devices without a browser get a neutral toast instead of a crash. */
    void openWebLink(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.settings_no_browser_found, Toast.LENGTH_SHORT).show();
        }
    }

    private void showResetAppDialog() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.settings_reset_app_dialog_title)
                .setMessage(R.string.settings_reset_app_dialog_message)
                .setPositiveButton(R.string.settings_reset_app_confirm, (d, which) -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        try {
                            revokeSelfPermissionOnKill(Manifest.permission.POST_NOTIFICATIONS);
                            revokeSelfPermissionOnKill(Manifest.permission.ACCESS_FINE_LOCATION);
                            revokeSelfPermissionOnKill(Manifest.permission.ACCESS_COARSE_LOCATION);
                        } catch (Exception ignored) {
                            // Defensive: PermissionController may be unavailable in test environments
                            // or headless runtimes; proceed with clearing application data.
                        }
                    }
                    ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                    if (am != null) {
                        am.clearApplicationUserData();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        activeResetAppDialog = dialog;
        dialog.setOnDismissListener(d -> {
            if (activeResetAppDialog == d) {
                activeResetAppDialog = null;
            }
        });
        dialog.show();
    }

    AlertDialog getActiveResetAppDialog() {
        return activeResetAppDialog;
    }

    AlertDialog getActiveBackupDialog() {
        return backupController.getActiveDialog();
    }

    AlertDialog getActiveIssueReportDialog() {
        return diagnosticsController.getActiveIssueReportDialog();
    }

    private final RegisterStateNotifier.Listener webhookStateListener =
            this::refreshWebhookStatusLine;

    /** #1034: the one line under the webhook section title. */
    private void refreshWebhookStatusLine() {
        if (isFinishing() || isDestroyed()) return;
        ((TextView) findViewById(R.id.settings_webhook_status))
                .setText(KeepADBWebhookStatusText.headerStatus(this));
    }

    void refresh() {
        KeepADBWarningState.observe(this);
        boolean hasPermission = checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
        permissionPanel.setVisibility(hasPermission ? View.GONE : View.VISIBLE);

        String currentLanguageTag = KeepADBLocaleHelper.getSelectedLanguageTag(this);
        String displayName = KeepADBLocaleHelper.getLanguageDisplayName(this, currentLanguageTag);
        languageButton.setContentDescription(
                getString(R.string.settings_language_accessibility, displayName));

        webhookForm.refreshVisual();
        refreshWebhookStatusLine();
        automationSection.refresh();

        boolean notificationHidden = KeepADBPreferences.isNotificationHidden(this);
        boolean keepAliveActive = KeepADBPreferences.isKeepAliveEnabled(this);
        // Keep the saved choice while Android requires the foreground-service notification.
        hideNotificationToggle.setEnabled(!keepAliveActive);
        hideNotificationToggle.setChecked(keepAliveActive || !notificationHidden);
        if (hideNotificationSubtext != null) {
            hideNotificationSubtext.setText(keepAliveActive
                    ? R.string.settings_hide_notification_subtext_keepalive
                    : R.string.settings_hide_notification_subtext);
        }

        KeepADBPrivacySwitches.refresh(this, privacySwitches);

        keepDisplayOnToggle.setChecked(KeepADBPreferences.isKeepDisplayOnEnabled(this));

        onboardingSummary.setText(getString(R.string.onboarding_settings_summary,
                KeepADBForceNotice.levelLabel(this),
                getString(keepAliveActive ? R.string.onboarding_value_on
                        : R.string.onboarding_value_off)));

        // #959: the one Protection row; the page itself is drawn by ProtectionActivity.
        protectionSummary.setText(KeepADBProtectionSummary.text(this));
    }

    private void bindVersionInfo() {
        try {
            PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            versionNameText.setText(getString(R.string.settings_version_value, packageInfo.versionName));
            versionCodeText.setText(getString(R.string.settings_version_code_value,
                    packageInfo.getLongVersionCode()));
        } catch (PackageManager.NameNotFoundException exception) {
            versionNameText.setText(R.string.settings_version_unavailable);
            versionCodeText.setText(R.string.settings_version_unavailable);
        }
        versionDebugBadge.setVisibility(KeepADBBuildFlags.isDebugBuild(this) ? View.VISIBLE : View.GONE);
    }

    /** #1000: the stored default duration as the row's value. */
    private void renderTimerDefault() {
        ((TextView) findViewById(R.id.settings_timer_default_value)).setText(
                KeepADBTimerText.duration(this, KeepADBPreferences.getTimerDefaultDuration(this)));
    }

    /**
     * #1000: the same durations as on the home screen; the choice is what the launcher shortcut
     * starts (#1032: the home row always asks).
     */
    private void showTimerDefaultChoice() {
        KeepADBTimer.Duration current = KeepADBPreferences.getTimerDefaultDuration(this);
        if (isShowing(activeTimerDefaultDialog)) activeTimerDefaultDialog.dismiss();
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.settings_timer_default_title)
                .setSingleChoiceItems(KeepADBTimerText.durations(this), current.ordinal(), (d, which) -> {
                    d.dismiss();
                    KeepADBPreferences.setTimerDefaultDuration(this, KeepADBTimer.Duration.values()[which]);
                    renderTimerDefault();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOwnerActivity(this);
        activeTimerDefaultDialog = dialog;
        dialog.setOnDismissListener(d -> {
            if (activeTimerDefaultDialog == d) activeTimerDefaultDialog = null;
        });
        dialog.show();
    }

    /** Test hook (#1047): the default-duration choice while it is open, or null. */
    AlertDialog getActiveTimerDefaultDialog() {
        return activeTimerDefaultDialog;
    }
}
