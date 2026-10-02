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
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Central settings screen for KeepADB options (Keep-Alive, Language, Webhook, etc.). */
public class SettingsActivity extends Activity {
    /** Intent extra requesting that the webhook section be scrolled into view and focused. */
    public static final String EXTRA_FOCUS_WEBHOOK = "focus_webhook";
    /** #619: Intent extra requesting that the network section be expanded and scrolled into view. */
    public static final String EXTRA_FOCUS_NETWORK = "focus_network";
    /**
     * #672: flags for the reset-app, USB handover mode and language dialogs showing at the time of
     * a {@code recreate()} (rotation). Pure "was showing" markers; a restored reset-app dialog is
     * only re-shown and still needs the user's own confirm tap. The Bundle keys of the Network
     * card's dialogs (trust confirmation, background location, allowlist permission) are owned by
     * {@link KeepADBNetworkCard} (#697), those of the feedback report dialog by {@link
     * KeepADBDiagnosticsController} (#698).
     */
    static final String STATE_RESET_APP_SHOWING = "settings_reset_app_showing";
    static final String STATE_USB_HANDOVER_MODE_SHOWING = "settings_usb_handover_mode_showing";
    static final String STATE_LANGUAGE_SELECTION_SHOWING = "settings_language_selection_showing";

    private ScrollView scrollView;
    private View webhookPanel;
    private View networkPanel;
    private View permissionPanel;
    private View languageToolbarButton;

    private Switch hideNotificationToggle;
    private TextView hideNotificationSubtext;
    private Switch notificationDetailsToggle;
    private Switch keepDisplayOnToggle;
    private Switch adviceBannerToggle;
    private Switch batteryOptimizationPanelToggle;
    private Switch usbNotificationToggle;
    private Switch usbProfileNotificationToggle;
    private TextView usbProfileSummary;
    private Button usbProfileAction;

    private TextView usbHandoverSelectedText;
    private View usbHandoverSelector;

    // #697: the Network card (views, rendered action snapshot, Wi-Fi callback and its dialogs).
    private KeepADBNetworkCard networkCard;

    private KeepADBWebhookForm webhookForm;
    private TextView versionNameText;
    private TextView versionCodeText;
    private TextView versionDebugBadge;
    private TextView websiteLinkText;

    // #698: the diagnostics export and the feedback report dialog (draft, preview, opt-in).
    private KeepADBDiagnosticsController diagnosticsController;

    private AlertDialog activeResetAppDialog;
    /** #672: the USB handover mode and language selection dialogs, if showing. */
    private AlertDialog activeUsbHandoverModeDialog;
    private AlertDialog activeLanguageSelectionDialog;

    private KeepADBUsbProfileEditor usbProfileEditor;

    static final String WEBSITE_URL = "https://hohnepeople.de";

    private static final String CARD_COLLAPSED_SYMBOL = "+";
    private static final String CARD_EXPANDED_SYMBOL = "−";

    // Card expansion lives only in the view tree; opening Settings starts cards collapsed.
    // The permission notice remains visible when applicable, while the version entry is static.
    // Wi-Fi-name matching has its own nested section and does not persist its expansion state.
    private static final int[][] COLLAPSIBLE_CARDS = {
            {R.id.settings_webhook_header, R.id.settings_webhook_body, R.id.settings_webhook_arrow},
            {R.id.settings_usb_adb_header, R.id.settings_usb_adb_body, R.id.settings_usb_adb_arrow},
            {R.id.settings_network_beta_header, R.id.settings_network_beta_body,
                    R.id.settings_network_beta_arrow},
            {R.id.settings_misc_header, R.id.settings_misc_body, R.id.settings_misc_arrow},
            {R.id.settings_diagnostics_header, R.id.settings_diagnostics_body, R.id.settings_diagnostics_arrow},
    };

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(KeepADBLocaleHelper.wrapContext(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        webhookPanel = findViewById(R.id.settings_webhook_panel);
        networkPanel = findViewById(R.id.settings_network_beta_panel);
        permissionPanel = findViewById(R.id.settings_permission_panel);

        // #471: wire every card's header to toggle its own body, independently of the others.
        for (int[] card : COLLAPSIBLE_CARDS) {
            bindCollapsibleCard(card[0], card[1], card[2]);
        }

        languageToolbarButton = findViewById(R.id.settings_language_toolbar_button);
        languageToolbarButton.setOnClickListener(v -> showLanguageSelectionDialog());

        hideNotificationToggle = findViewById(R.id.settings_hide_notification_toggle);
        hideNotificationSubtext = findViewById(R.id.settings_hide_notification_subtext);
        hideNotificationToggle.setOnClickListener(v -> {
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

        notificationDetailsToggle = findViewById(R.id.settings_notification_details_toggle);
        notificationDetailsToggle.setOnClickListener(v -> {
            KeepADBPreferences.setNotificationDetailsEnabled(this, notificationDetailsToggle.isChecked());
            // #592: re-render a currently visible USB card right away; a trust prompt already on
            // screen keeps its text until it is posted again.
            KeepADBUsbReceiver.refresh(this);
            // #597: re-render the persistent main notification right away too -- same reasoning,
            // it also gates its port/IP text behind this preference now.
            KeepADBEndpointCoordinator.refresh(this);
        });

        keepDisplayOnToggle = findViewById(R.id.settings_keep_display_on_toggle);
        keepDisplayOnToggle.setOnClickListener(v ->
                KeepADBPreferences.setKeepDisplayOnEnabled(this, keepDisplayOnToggle.isChecked()));

        adviceBannerToggle = findViewById(R.id.settings_advice_banner_toggle);
        adviceBannerToggle.setOnClickListener(v ->
                KeepADBPreferences.setAdviceBannerVisible(this, adviceBannerToggle.isChecked()));

        batteryOptimizationPanelToggle = findViewById(R.id.settings_battery_optimization_panel_toggle);
        batteryOptimizationPanelToggle.setOnClickListener(v ->
                KeepADBPreferences.setBatteryOptimizationPanelVisible(
                        this, batteryOptimizationPanelToggle.isChecked()));

        usbNotificationToggle = findViewById(R.id.settings_usb_notification_toggle);
        usbProfileNotificationToggle = findViewById(R.id.settings_usb_profile_notification_toggle);
        usbProfileSummary = findViewById(R.id.settings_usb_profile_summary);
        usbProfileAction = findViewById(R.id.settings_usb_profile_action);
        usbNotificationToggle.setOnClickListener(v -> {
            KeepADBUsbProfile.setNotificationEnabled(this, usbNotificationToggle.isChecked());
            KeepADBUsbReceiver.refresh(this);
            refresh();
        });
        usbProfileNotificationToggle.setOnClickListener(v -> {
            KeepADBUsbProfile.setProfileNotificationEnabled(this, usbProfileNotificationToggle.isChecked());
            KeepADBUsbReceiver.refresh(this);
            refresh();
        });
        usbProfileEditor = new KeepADBUsbProfileEditor(this, () -> {
            KeepADBUsbReceiver.refresh(this);
            refresh();
        });
        usbProfileAction.setOnClickListener(v -> usbProfileEditor.showDialog(
                KeepADBUsbProfile.getProfiles(this).isEmpty()
                        ? KeepADBUsbNotification.ACTION_CREATE : KeepADBUsbNotification.ACTION_SWITCH));

        usbHandoverSelectedText = findViewById(R.id.settings_usb_handover_selected_text);
        usbHandoverSelector = findViewById(R.id.settings_usb_handover_selector);
        usbHandoverSelector.setOnClickListener(v -> showUsbHandoverModeDialog());

        networkCard = new KeepADBNetworkCard(this, this::refresh);

        diagnosticsController = new KeepADBDiagnosticsController(this, this::openWebLink);
        findViewById(R.id.settings_reset_app).setOnClickListener(v -> showResetAppDialog());

        webhookForm = new KeepADBWebhookForm(this, this::refresh);
        webhookForm.restoreDraft(savedInstanceState);
        usbProfileEditor.restore(savedInstanceState);

        if (savedInstanceState != null) {
            diagnosticsController.restore(savedInstanceState);
            // #604/#672/#682 (#697): the Network card re-shows its own trust confirmation,
            // background-location and allowlist permission dialogs from the same bundle.
            networkCard.restore(savedInstanceState);
            // #672: re-show the remaining plain dialogs. The reset-app dialog is only re-shown,
            // its destructive action still runs solely from the user's own confirm tap.
            if (savedInstanceState.getBoolean(STATE_RESET_APP_SHOWING, false)) {
                showResetAppDialog();
            }
            if (savedInstanceState.getBoolean(STATE_USB_HANDOVER_MODE_SHOWING, false)) {
                showUsbHandoverModeDialog();
            }
            if (savedInstanceState.getBoolean(STATE_LANGUAGE_SELECTION_SHOWING, false)) {
                showLanguageSelectionDialog();
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        networkCard.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        webhookForm.ensureDraftInitialized();
        KeepADBPrivacyToggle.update(this);
        refresh();

        if (getIntent().hasExtra(KeepADBUsbNotification.EXTRA_PROFILE_ACTION)) {
            usbProfileEditor.showDialog(getIntent().getStringExtra(KeepADBUsbNotification.EXTRA_PROFILE_ACTION));
            getIntent().removeExtra(KeepADBUsbNotification.EXTRA_PROFILE_ACTION);
        }

        if (getIntent().hasExtra(EXTRA_FOCUS_WEBHOOK)) {
            focusWebhookPanel();
            getIntent().removeExtra(EXTRA_FOCUS_WEBHOOK);
        }

        if (getIntent().hasExtra(EXTRA_FOCUS_NETWORK)) {
            focusNetworkPanel();
            getIntent().removeExtra(EXTRA_FOCUS_NETWORK);
        }

        // #598: the details-off trust prompt's content intent. Consumed like the extras above so a
        // later resume does not ask again.
        if (KeepADBNetworkTrustPrompt.ACTION_CONFIRM_IN_APP.equals(getIntent().getAction())) {
            String bssid = getIntent().getStringExtra(KeepADBNetworkTrustPrompt.EXTRA_BSSID);
            getIntent().setAction(null);
            getIntent().removeExtra(KeepADBNetworkTrustPrompt.EXTRA_BSSID);
            networkCard.showTrustConfirmationDialog(bssid);
        }
    }

    @Override
    protected void onStop() {
        networkCard.stop();
        super.onStop();
    }

    /**
     * #697: every permission result is handed to the Network card; the card owns the two request
     * codes it asks for and acts only on those.
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        networkCard.onRequestPermissionsResult(requestCode);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webhookForm.saveState(outState);
        diagnosticsController.saveState(outState);
        usbProfileEditor.saveState(outState);
        networkCard.saveState(outState);
        outState.putBoolean(STATE_RESET_APP_SHOWING, isShowing(activeResetAppDialog));
        outState.putBoolean(STATE_USB_HANDOVER_MODE_SHOWING, isShowing(activeUsbHandoverModeDialog));
        outState.putBoolean(STATE_LANGUAGE_SELECTION_SHOWING,
                isShowing(activeLanguageSelectionDialog));
    }

    private static boolean isShowing(AlertDialog dialog) {
        return dialog != null && dialog.isShowing();
    }

    @Override
    protected void onDestroy() {
        diagnosticsController.destroy();

        if (activeResetAppDialog != null) {
            if (activeResetAppDialog.isShowing()) {
                activeResetAppDialog.dismiss();
            }
            activeResetAppDialog = null;
        }

        usbProfileEditor.destroy();

        networkCard.destroy();

        if (activeUsbHandoverModeDialog != null) {
            if (activeUsbHandoverModeDialog.isShowing()) {
                activeUsbHandoverModeDialog.dismiss();
            }
            activeUsbHandoverModeDialog = null;
        }

        if (activeLanguageSelectionDialog != null) {
            if (activeLanguageSelectionDialog.isShowing()) {
                activeLanguageSelectionDialog.dismiss();
            }
            activeLanguageSelectionDialog = null;
        }

        super.onDestroy();
    }

    static String resolveWebhookDraft(String savedUrl, String currentDraft, boolean hasCurrentDraft) {
        if (hasCurrentDraft) {
            return currentDraft == null ? "" : currentDraft;
        }
        return savedUrl == null ? "" : savedUrl;
    }

    private void focusWebhookPanel() {
        // #471: the webhook card is collapsed by default like every other card; a caller asking
        // to focus its URL field (e.g. MainActivity's webhook setup shortcut) needs the body
        // actually expanded first, or requestFocus() below would silently no-op on a GONE view.
        setCardExpanded(this, findViewById(R.id.settings_webhook_header),
                findViewById(R.id.settings_webhook_body),
                findViewById(R.id.settings_webhook_arrow), true);
        scrollView.post(() -> scrollView.smoothScrollTo(0, webhookPanel.getTop()));
        webhookForm.requestUrlFocus();
    }

    private void focusNetworkPanel() {
        // #619: expand the network card and scroll it into view.
        setCardExpanded(this, findViewById(R.id.settings_network_beta_header),
                findViewById(R.id.settings_network_beta_body),
                findViewById(R.id.settings_network_beta_arrow), true);
        if (networkPanel != null && scrollView != null) {
            scrollView.post(() -> scrollView.smoothScrollTo(0, networkPanel.getTop()));
        }
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
     * Shared by the cards wired here and the Network card's nested Wi-Fi-name section (#697), so
     * every collapsible header announces and renders its state alike.
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
        KeepADBLocaleHelper.LanguageItem[] languages = KeepADBLocaleHelper.SUPPORTED_LANGUAGES;
        String[] displayItems = new String[languages.length];
        String currentTag = KeepADBLocaleHelper.getSelectedLanguageTag(this);
        int selectedIndex = 0;

        for (int i = 0; i < languages.length; i++) {
            if (languages[i].tag.isEmpty()) {
                displayItems[i] = getString(R.string.settings_language_system_default);
            } else {
                displayItems[i] = languages[i].endonym;
            }
            if (languages[i].tag.equalsIgnoreCase(currentTag)) {
                selectedIndex = i;
            }
        }

        if (isShowing(activeLanguageSelectionDialog)) {
            return;
        }
        AlertDialog picker = new AlertDialog.Builder(this)
                .setTitle(R.string.settings_language_dialog_title)
                .setSingleChoiceItems(displayItems, selectedIndex, (dialog, which) -> {
                    dialog.dismiss();
                    String chosenTag = languages[which].tag;
                    KeepADBLocaleHelper.setAppLanguage(this, chosenTag);
                    KeepADBWidget.refreshAll(this);
                    KeepADBEndpointCoordinator.refresh(this);
                    KeepADBUsbReceiver.refresh(this);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        activeLanguageSelectionDialog = picker;
        picker.setOnDismissListener(d -> {
            if (activeLanguageSelectionDialog == d) {
                activeLanguageSelectionDialog = null;
            }
        });
        picker.show();
    }

    private void showUsbHandoverModeDialog() {
        String[] modes = {
                KeepADBPreferences.USB_WLAN_HANDOVER_MODE_OFF,
                KeepADBPreferences.USB_WLAN_HANDOVER_MODE_MANUAL,
                KeepADBPreferences.USB_WLAN_HANDOVER_MODE_AUTOMATIC,
        };
        String[] displayItems = {
                getString(R.string.settings_usb_handover_mode_off),
                getString(R.string.settings_usb_handover_mode_manual),
                getString(R.string.settings_usb_handover_mode_automatic),
        };
        String currentMode = KeepADBPreferences.getUsbWlanHandoverMode(this);
        int selectedIndex = 0;
        for (int i = 0; i < modes.length; i++) {
            if (modes[i].equals(currentMode)) selectedIndex = i;
        }

        if (isShowing(activeUsbHandoverModeDialog)) {
            return;
        }
        AlertDialog picker = new AlertDialog.Builder(this)
                .setTitle(R.string.settings_usb_handover_dialog_title)
                .setSingleChoiceItems(displayItems, selectedIndex, (dialog, which) -> {
                    dialog.dismiss();
                    KeepADBPreferences.setUsbWlanHandoverMode(this, modes[which]);
                    // Re-derives current USB state for the notification only; this is the
                    // profile-edit-style refresh(Context) overload, not a real connect edge, so
                    // switching modes here never triggers AUTOMATIC's setEnabled() itself.
                    KeepADBUsbReceiver.refresh(this);
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        activeUsbHandoverModeDialog = picker;
        picker.setOnDismissListener(d -> {
            if (activeUsbHandoverModeDialog == d) {
                activeUsbHandoverModeDialog = null;
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

    AlertDialog getActiveAllowlistPermissionDialog() {
        return networkCard.getActiveAllowlistPermissionDialog();
    }

    AlertDialog getActiveTrustConfirmationDialog() {
        return networkCard.getActiveTrustConfirmationDialog();
    }

    AlertDialog getActiveResetAppDialog() {
        return activeResetAppDialog;
    }

    AlertDialog getActiveIssueReportDialog() {
        return diagnosticsController.getActiveIssueReportDialog();
    }

    AlertDialog getActiveProfileEditDialog() {
        return usbProfileEditor.getActiveEditDialog();
    }

    AlertDialog getActiveSwitchProfileDialog() {
        return usbProfileEditor.getActiveSwitchDialog();
    }

    void refresh() {
        boolean hasPermission = checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
        permissionPanel.setVisibility(hasPermission ? View.GONE : View.VISIBLE);

        String currentLanguageTag = KeepADBLocaleHelper.getSelectedLanguageTag(this);
        String displayName = KeepADBLocaleHelper.getLanguageDisplayName(this, currentLanguageTag);
        languageToolbarButton.setContentDescription(
                getString(R.string.settings_language_accessibility, displayName));

        webhookForm.refreshVisual();

        boolean notificationHidden = KeepADBPreferences.isNotificationHidden(this);
        // #456: positive framing — checked means the notification stays visible.
        hideNotificationToggle.setChecked(!notificationHidden);
        boolean keepAliveActive = KeepADBPreferences.isKeepAliveEnabled(this);
        if (hideNotificationSubtext != null) {
            hideNotificationSubtext.setText(keepAliveActive
                    ? R.string.settings_hide_notification_subtext_keepalive
                    : R.string.settings_hide_notification_subtext);
        }

        notificationDetailsToggle.setChecked(KeepADBPreferences.isNotificationDetailsEnabled(this));

        keepDisplayOnToggle.setChecked(KeepADBPreferences.isKeepDisplayOnEnabled(this));

        adviceBannerToggle.setChecked(KeepADBPreferences.isAdviceBannerVisible(this));

        batteryOptimizationPanelToggle.setChecked(
                KeepADBPreferences.isBatteryOptimizationPanelVisible(this));

        usbNotificationToggle.setChecked(KeepADBUsbProfile.isNotificationEnabled(this));
        usbProfileNotificationToggle.setChecked(KeepADBUsbProfile.isProfileNotificationEnabled(this));
        KeepADBUsbProfile.Profile selectedProfile = KeepADBUsbProfile.getSelected(this);
        usbProfileSummary.setText(selectedProfile == null
                ? getString(R.string.usb_profile_none)
                : getString(R.string.usb_profile_selected, selectedProfile.summary()));
        usbProfileAction.setText(KeepADBUsbProfile.getProfiles(this).isEmpty()
                ? R.string.usb_profile_create_button : R.string.usb_profile_switch_button);

        String handoverMode = KeepADBPreferences.getUsbWlanHandoverMode(this);
        int handoverModeLabel = KeepADBPreferences.USB_WLAN_HANDOVER_MODE_AUTOMATIC.equals(handoverMode)
                ? R.string.settings_usb_handover_mode_automatic
                : KeepADBPreferences.USB_WLAN_HANDOVER_MODE_MANUAL.equals(handoverMode)
                        ? R.string.settings_usb_handover_mode_manual
                        : R.string.settings_usb_handover_mode_off;
        usbHandoverSelectedText.setText(handoverModeLabel);
        usbHandoverSelector.setContentDescription(
                getString(R.string.settings_usb_handover_accessibility, getString(handoverModeLabel)));

        networkCard.refresh();
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
        versionDebugBadge.setVisibility(isDebugBuild() ? View.VISIBLE : View.GONE);
    }

    /**
     * The debug build type appends the {@code .debug} applicationIdSuffix (see app/build.gradle),
     * so the running package name is a reliable, dependency-free debug signal without needing
     * BuildConfig.DEBUG (which would require enabling the buildConfig build feature).
     */
    private boolean isDebugBuild() {
        return getPackageName().endsWith(".debug");
    }
}
