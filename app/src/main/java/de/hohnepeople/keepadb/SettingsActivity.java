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
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Toolbar;

/** Central settings screen for KeepADB options (Keep-Alive, Language, Webhook, etc.). */
public class SettingsActivity extends Activity {
    /** #759: Intent extra requesting that the USB-ADB card be expanded and scrolled into view. */
    public static final String EXTRA_FOCUS_USB = "focus_usb";
    /** #763: Intent extra requesting that the force-mode row be expanded and scrolled into view. */
    public static final String EXTRA_FOCUS_FORCE = "focus_force";
    /**
     * #801: Intent extra requesting that the network card be expanded and its protection level row
     * scrolled into view (the Keep-Alive line of the home screen). Not the removed
     * {@code EXTRA_FOCUS_NETWORK} of #619: that one had no sender, this one has.
     */
    public static final String EXTRA_FOCUS_NETWORK = "focus_network";
    public static final String EXTRA_FOCUS_DETAILS = "focus_notification_details";
    public static final String EXTRA_FOCUS_WEBHOOK = "focus_webhook";
    public static final String EXTRA_FOCUS_MISC = "focus_misc";
    // The menu opens the card heading; the existing Keep-Alive entry keeps its level-row target.
    public static final String EXTRA_FOCUS_NETWORK_CARD = "focus_network_card";
    private static final String STATE_CONSUMED_FOCUS = "settings_consumed_focus";
    private final java.util.ArrayList<String> consumedFocus = new java.util.ArrayList<>();
    /**
     * #672: flags for the reset-app, USB handover mode and language dialogs showing at the time of
     * a {@code recreate()} (rotation). Pure "was showing" markers; a restored reset-app dialog is
     * only re-shown and still needs the user's own confirm tap. The Bundle keys of the Network
     * card's dialog (background location) is owned by
     * {@link KeepADBNetworkCard} (#697), those of the feedback report dialog by {@link
     * KeepADBDiagnosticsController} (#698).
     */
    static final String STATE_RESET_APP_SHOWING = "settings_reset_app_showing";
    static final String STATE_USB_HANDOVER_MODE_SHOWING = "settings_usb_handover_mode_showing";
    static final String STATE_LANGUAGE_SELECTION_SHOWING = "settings_language_selection_showing";

    private ScrollView scrollView;
    private View permissionPanel;
    private MenuItem languageMenuItem;
    private TextView onboardingSummary;

    private Switch hideNotificationToggle;
    private TextView hideNotificationSubtext;
    private Switch notificationDetailsToggle;
    private Switch keepDisplayOnToggle;
    private Switch adviceBannerToggle;
    private Switch usbNotificationToggle;
    private Switch usbProfileNotificationToggle;
    private TextView usbProfileSummary;
    private Button usbProfileAction;

    private TextView usbHandoverSelectedText;
    private View usbHandoverSelector;

    // #697: the Network card (views, rendered action snapshot, Wi-Fi callback and its dialogs).
    private KeepADBNetworkCard networkCard;

    // #763: the force-mode row inside the Network area and its confirmation dialog.
    private KeepADBForceSection forceSection;

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

    static final String WEBSITE_URL = "https://keepadb.roteson.de";

    private static final String CARD_COLLAPSED_SYMBOL = "+";
    private static final String CARD_EXPANDED_SYMBOL = "−";

    // Card expansion lives only in the view tree; opening Settings starts cards collapsed.
    // The permission notice remains visible when applicable, while the version entry is static.
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
        permissionPanel = findViewById(R.id.settings_permission_panel);

        // #471: wire every card's header to toggle its own body, independently of the others.
        for (int[] card : COLLAPSIBLE_CARDS) {
            bindCollapsibleCard(card[0], card[1], card[2]);
        }

        Toolbar toolbar = findViewById(R.id.header_bar);
        languageMenuItem = toolbar.getMenu().add(Menu.NONE, R.id.settings_language_menu_item,
                Menu.NONE, R.string.settings_language_menu_label);
        languageMenuItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() != R.id.settings_language_menu_item) return false;
            showLanguageSelectionDialog();
            return true;
        });

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

        // #761: the setup assistant, whole from the top row, the protection step from its level line.
        onboardingSummary = findViewById(R.id.settings_onboarding_summary);
        findViewById(R.id.settings_onboarding_row).setOnClickListener(v ->
                startActivity(OnboardingActivity.fullIntent(this)));
        findViewById(R.id.network_level_line).setOnClickListener(v ->
                startActivity(OnboardingActivity.stepIntent(this,
                        KeepADBOnboarding.Step.PROTECTION)));

        networkCard = new KeepADBNetworkCard(this, this::refresh);
        forceSection = new KeepADBForceSection(this, this::refresh);

        diagnosticsController = new KeepADBDiagnosticsController(this, this::openWebLink);
        findViewById(R.id.settings_reset_app).setOnClickListener(v -> showResetAppDialog());

        webhookForm = new KeepADBWebhookForm(this, this::refresh);
        webhookForm.restoreDraft(savedInstanceState);
        usbProfileEditor.restore(savedInstanceState);

        if (savedInstanceState != null) {
            java.util.ArrayList<String> restoredFocus =
                    savedInstanceState.getStringArrayList(STATE_CONSUMED_FOCUS);
            if (restoredFocus != null) consumedFocus.addAll(restoredFocus);
            diagnosticsController.restore(savedInstanceState);
            // #672/#682 (#697): the Network card re-shows its own background-location and
            // allowlist permission dialogs from the same bundle.
            networkCard.restore(savedInstanceState);
            forceSection.restore(savedInstanceState);
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
        findViewById(R.id.settings_muted_warnings).setOnClickListener(v -> startActivity(
                new Intent(this, WarningsActivity.class).putExtra(WarningsActivity.EXTRA_MUTES, true)));
        webhookForm.ensureDraftInitialized();
        KeepADBPrivacyToggle.update(this);
        // #763: finish an expired force mode before drawing, and redraw when it starts or ends.
        KeepADBForceMode.finishIfExpired(this);
        KeepADBForceMode.setStateListener(this::refresh);
        refresh();

        if (getIntent().hasExtra(KeepADBUsbNotification.EXTRA_PROFILE_ACTION)) {
            usbProfileEditor.showDialog(getIntent().getStringExtra(KeepADBUsbNotification.EXTRA_PROFILE_ACTION));
            getIntent().removeExtra(KeepADBUsbNotification.EXTRA_PROFILE_ACTION);
        }

        if (consumeFocus(EXTRA_FOCUS_USB)) {
            focusUsbPanel();
        }

        if (consumeFocus(EXTRA_FOCUS_FORCE)) {
            focusForcePanel();
        }

        if (consumeFocus(EXTRA_FOCUS_NETWORK)) {
            focusNetworkLevel();
        }
        if (consumeFocus(EXTRA_FOCUS_WEBHOOK)) {
            focusCard(R.id.settings_webhook_header, R.id.settings_webhook_body,
                    R.id.settings_webhook_arrow);
            focusWarningTarget(findViewById(R.id.settings_webhook_url));
        }
        if (consumeFocus(EXTRA_FOCUS_DETAILS)) {
            focusCard(R.id.settings_misc_header, R.id.settings_misc_body, R.id.settings_misc_arrow);
            focusWarningTarget(findViewById(R.id.settings_notification_details_toggle));
        }
        if (consumeFocus(EXTRA_FOCUS_MISC)) {
            focusCard(R.id.settings_misc_header, R.id.settings_misc_body,
                    R.id.settings_misc_arrow);
        }
        if (consumeFocus(EXTRA_FOCUS_NETWORK_CARD)) {
            focusCard(R.id.settings_network_beta_header, R.id.settings_network_beta_body,
                    R.id.settings_network_beta_arrow);
        }

    }

    @Override
    protected void onPause() {
        KeepADBForceMode.clearStateListener();
        super.onPause();
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
        KeepADBWarningState.observe(this);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putStringArrayList(STATE_CONSUMED_FOCUS, new java.util.ArrayList<>(consumedFocus));
        webhookForm.saveState(outState);
        diagnosticsController.saveState(outState);
        usbProfileEditor.saveState(outState);
        networkCard.saveState(outState);
        forceSection.saveState(outState);
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
        forceSection.destroy();

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

    private boolean consumeFocus(String extra) {
        if (!getIntent().hasExtra(extra)) return false;
        getIntent().removeExtra(extra);
        if (consumedFocus.contains(extra)) return false;
        consumedFocus.add(extra);
        return true;
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

    private void focusForcePanel() {
        // #763: expand the network card and bring the force row into view.
        setCardExpanded(this, findViewById(R.id.settings_network_beta_header),
                findViewById(R.id.settings_network_beta_body),
                findViewById(R.id.settings_network_beta_arrow), true);
        View forcePanel = forceSection.getPanel();
        if (forcePanel != null && scrollView != null) {
            scrollView.post(() -> {
                android.graphics.Rect rect = new android.graphics.Rect();
                forcePanel.getDrawingRect(rect);
                scrollView.offsetDescendantRectToMyCoords(forcePanel, rect);
                scrollView.smoothScrollTo(0, rect.top);
                focusWarningTarget(findViewById(KeepADBForceMode.isActive(this)
                        ? R.id.settings_force_end : R.id.settings_force_activate));
            });
        }
    }

    private void focusNetworkLevel() {
        // #801: expand the network card and bring the protection level row into view.
        setCardExpanded(this, findViewById(R.id.settings_network_beta_header),
                findViewById(R.id.settings_network_beta_body),
                findViewById(R.id.settings_network_beta_arrow), true);
        View levelPanel = findViewById(R.id.settings_network_level_panel);
        if (levelPanel != null && scrollView != null) {
            scrollView.post(() -> {
                android.graphics.Rect rect = new android.graphics.Rect();
                levelPanel.getDrawingRect(rect);
                scrollView.offsetDescendantRectToMyCoords(levelPanel, rect);
                scrollView.smoothScrollTo(0, rect.top);
                focusWarningTarget(findViewById(R.id.network_level_line));
            });
        }
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

    private void focusUsbPanel() {
        // #759: expand the USB-ADB card and scroll it into view.
        setCardExpanded(this, findViewById(R.id.settings_usb_adb_header),
                findViewById(R.id.settings_usb_adb_body),
                findViewById(R.id.settings_usb_adb_arrow), true);
        View usbPanel = findViewById(R.id.settings_usb_adb_panel);
        if (usbPanel != null && scrollView != null) {
            scrollView.post(() -> scrollView.smoothScrollTo(0, usbPanel.getTop()));
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
        KeepADBWarningState.observe(this);
        boolean hasPermission = checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
        permissionPanel.setVisibility(hasPermission ? View.GONE : View.VISIBLE);

        String currentLanguageTag = KeepADBLocaleHelper.getSelectedLanguageTag(this);
        String displayName = KeepADBLocaleHelper.getLanguageDisplayName(this, currentLanguageTag);
        languageMenuItem.setContentDescription(
                getString(R.string.settings_language_accessibility, displayName));

        webhookForm.refreshVisual();

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

        notificationDetailsToggle.setChecked(KeepADBPreferences.isNotificationDetailsEnabled(this));

        keepDisplayOnToggle.setChecked(KeepADBPreferences.isKeepDisplayOnEnabled(this));

        adviceBannerToggle.setChecked(KeepADBPreferences.isAdviceBannerVisible(this));

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

        onboardingSummary.setText(getString(R.string.onboarding_settings_summary,
                KeepADBForceNotice.levelLabel(this),
                getString(keepAliveActive ? R.string.onboarding_value_on
                        : R.string.onboarding_value_off)));

        networkCard.refresh();
        forceSection.refresh();
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
