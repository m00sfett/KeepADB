package de.hohnepeople.keepadb;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Central settings screen for KeepADB options (Keep-Alive, Language, Webhook, etc.). */
public class SettingsActivity extends Activity {
    /** Intent extra requesting that the webhook section be scrolled into view and focused. */
    public static final String EXTRA_FOCUS_WEBHOOK = "focus_webhook";
    /** #619: Intent extra requesting that the network section be expanded and scrolled into view. */
    public static final String EXTRA_FOCUS_NETWORK = "focus_network";
    static final String STATE_ISSUE_REPORT_SHOWING = "settings_issue_report_showing";
    static final String STATE_ISSUE_REPORT_DRAFT = "settings_issue_report_draft";
    static final String STATE_ISSUE_REPORT_DIAGNOSTICS = "settings_issue_report_diagnostics";
    /**
     * #604: the BSSID the currently showing #598 trust confirmation dialog is bound to, carried
     * across a {@code recreate()} (rotation). Only ever a BSSID this app already captured from its
     * own {@link KeepADBBlockedNetworkHistory} record via {@link #showTrustConfirmationDialog} --
     * never re-read from the intent (already consumed by then) or from the current connection.
     */
    static final String STATE_TRUST_CONFIRMATION_BSSID = "settings_trust_confirmation_bssid";

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

    private Switch trustedNetworkToggle;
    private Switch trustedSsidToggle;
    private TextView trustedNetworkStatus;
    private TextView backgroundLocationStatus;

    private KeepADBWebhookForm webhookForm;
    private TextView versionNameText;
    private TextView versionCodeText;
    private TextView versionDebugBadge;
    private TextView websiteLinkText;

    private AlertDialog activeIssueReportDialog;
    private EditText activeIssueReportPreview;
    private CheckBox activeIssueReportDiagnostics;

    private AlertDialog activeResetAppDialog;

    private KeepADBUsbProfileEditor usbProfileEditor;

    // #507: Wi-Fi & Access Points settings views and state
    static final int WIFI_APS_LOCATION_PERMISSION_REQUEST = 3002;
    private static final String LOCATION_PERMISSION_REQUESTED = "location_permission_requested";
    private static final int WIFI_APS_COLLAPSED_OTHERS = 5;

    private Switch wifiApsFeatureToggle;
    private View wifiApsContent;
    private Switch wifiApsTrustedOnlyToggle;
    private LinearLayout wifiApsCurrentRow;
    private LinearLayout wifiApsList;
    private TextView wifiApsToggle;
    private TextView wifiApsEmpty;
    private LinearLayout wifiSsidsSection;
    private LinearLayout wifiSsidsCurrentRow;
    private LinearLayout wifiSsidsList;
    private TextView wifiSsidsEmpty;
    private Button wifiApsRecentlyBlockedButton;
    private AlertDialog activeBlockedNetworksDialog;
    private AlertDialog activeTrustConfirmationDialog;
    /** #604: the BSSID {@link #activeTrustConfirmationDialog} is bound to, or null if none is showing. */
    private String activeTrustConfirmationBssid;
    private boolean wifiApsExpanded;
    private boolean wifiApsTrustedOnly;

    static final String WEBSITE_URL = "https://hohnepeople.de";

    // #471: settings cards start collapsed and are toggled independently of each other. The
    // symbols are plain literals (matching the existing static "▼" selector arrows elsewhere in
    // this layout) rather than string resources -- they carry no natural-language content, so
    // there is nothing for check-i18n/lint to translate.
    /** #492: request code for the Location grant the trusted-network opt-in needs. */
    private static final int TRUSTED_NETWORK_LOCATION_PERMISSION_REQUEST = 3001;
    private static final String CARD_COLLAPSED_SYMBOL = "+";
    private static final String CARD_EXPANDED_SYMBOL = "−";

    // #471: header/body/arrow id triples for every collapsible settings card. Each body starts
    // visibility="gone" in activity_settings.xml (collapsed by default); the expand state lives
    // only in the live View tree (View#setVisibility), never in SharedPreferences or
    // onSaveInstanceState, so a freshly created SettingsActivity instance -- i.e. every time the
    // settings page is opened -- is always collapsed again, per the #471 acceptance criteria.
    // The permission-warning panel is deliberately excluded: it is a conditional safety notice,
    // not a configurable option card, and stays fully visible whenever it is shown at all.
    // #478: the version card (last) is excluded here too -- it is a permanently visible,
    // non-collapsible entry pinned directly on the background, with neither an arrow nor a click
    // listener on its header. #518: the former language card was removed from the content
    // entirely and replaced by the compact toolbar button, so it no longer appears in this table.
    // #510: order below now matches the on-screen order -- Notification/Display/Advice-Banner/
    // Battery-Optimization sit together under the "Sonstiges" heading. #519: the former
    // #519 introduced the outer "Network" card (settings_network_beta_header/body/arrow below),
    // and #618 removes the two inner expand levels: Trusted Networks and Wi-Fi & access points
    // are now direct sections inside the network body (matching #529's USB-ADB and #521's
    // Sonstiges structure). Only the outer network card remains collapsible.
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
        websiteLinkText.setOnClickListener(v ->
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(WEBSITE_URL))));

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
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

        wifiApsFeatureToggle = findViewById(R.id.settings_wifi_aps_feature_toggle);
        wifiApsContent = findViewById(R.id.settings_wifi_aps_content);
        wifiApsTrustedOnlyToggle = findViewById(R.id.wifi_aps_trusted_only_toggle);
        wifiApsCurrentRow = findViewById(R.id.wifi_aps_current_row);
        wifiApsList = findViewById(R.id.wifi_aps_list);
        wifiApsEmpty = findViewById(R.id.wifi_aps_empty);
        wifiApsToggle = findViewById(R.id.wifi_aps_toggle);
        wifiSsidsSection = findViewById(R.id.wifi_ssids_section);
        wifiSsidsCurrentRow = findViewById(R.id.wifi_ssids_current_row);
        wifiSsidsList = findViewById(R.id.wifi_ssids_list);
        wifiSsidsEmpty = findViewById(R.id.wifi_ssids_empty);
        wifiApsRecentlyBlockedButton = findViewById(R.id.wifi_aps_recently_blocked_button);

        wifiApsFeatureToggle.setOnClickListener(v -> {
            boolean want = wifiApsFeatureToggle.isChecked();
            KeepADBPreferences.setWifiApsFeatureEnabled(this, want);
            refresh();
        });
        wifiApsToggle.setOnClickListener(v -> {
            wifiApsExpanded = !wifiApsExpanded;
            renderAccessPointOverview();
        });
        wifiApsTrustedOnlyToggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            wifiApsTrustedOnly = isChecked;
            renderAccessPointOverview();
        });
        if (wifiApsRecentlyBlockedButton != null) {
            wifiApsRecentlyBlockedButton.setOnClickListener(v -> showBlockedNetworkDialog());
        }


        trustedNetworkToggle = findViewById(R.id.settings_trusted_network_toggle);
        trustedNetworkStatus = findViewById(R.id.settings_trusted_network_status);
        backgroundLocationStatus = findViewById(R.id.settings_background_location_status);
        // #616: only opens the system page; the grant itself is the user's choice there.
        findViewById(R.id.settings_background_location_button).setOnClickListener(v ->
                KeepADBBackgroundLocation.openSettings(this));
        trustedSsidToggle = findViewById(R.id.settings_trusted_ssid_toggle);
        // OnClick, not OnCheckedChange: refresh() re-renders both switches from the persisted
        // state, and a checked-change listener would fire on that programmatic write too.
        trustedNetworkToggle.setOnClickListener(v -> onTrustedNetworkToggleClicked());
        trustedSsidToggle.setOnClickListener(v -> {
            KeepADBTrustedNetwork.setSsidMatchingEnabled(this, trustedSsidToggle.isChecked());
            refresh();
        });

        findViewById(R.id.settings_diagnostics_export).setOnClickListener(v -> shareDiagnostics());
        findViewById(R.id.settings_issue_report).setOnClickListener(v -> showIssueReportDialog());
        findViewById(R.id.settings_reset_app).setOnClickListener(v -> showResetAppDialog());

        webhookForm = new KeepADBWebhookForm(this, this::refresh);
        webhookForm.restoreDraft(savedInstanceState);
        usbProfileEditor.restore(savedInstanceState);

        if (savedInstanceState != null) {
            if (savedInstanceState.getBoolean(STATE_ISSUE_REPORT_SHOWING, false)) {
                String draftBody = savedInstanceState.getString(STATE_ISSUE_REPORT_DRAFT);
                boolean includeDiagnostics = savedInstanceState.getBoolean(
                        STATE_ISSUE_REPORT_DIAGNOSTICS, false);
                showIssueReportDialog(draftBody, includeDiagnostics);
            }
            // #604: re-show the #598 trust confirmation dialog after a rotation. The BSSID is the
            // one this instance already captured before the recreate -- showTrustConfirmationDialog
            // re-resolves it against KeepADBBlockedNetworkHistory exactly as it does for a fresh
            // notification tap, it is never taken from the intent (already consumed) or re-read
            // from the current connection.
            String pendingBssid = savedInstanceState.getString(STATE_TRUST_CONFIRMATION_BSSID);
            if (pendingBssid != null) {
                showTrustConfirmationDialog(pendingBssid);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        webhookForm.ensureDraftInitialized();
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
            showTrustConfirmationDialog(bssid);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webhookForm.saveState(outState);
        if (activeIssueReportDialog != null && activeIssueReportDialog.isShowing()) {
            outState.putBoolean(STATE_ISSUE_REPORT_SHOWING, true);
            outState.putString(STATE_ISSUE_REPORT_DRAFT,
                    activeIssueReportPreview != null && activeIssueReportPreview.getText() != null
                            ? activeIssueReportPreview.getText().toString() : "");
            outState.putBoolean(STATE_ISSUE_REPORT_DIAGNOSTICS,
                    activeIssueReportDiagnostics != null && activeIssueReportDiagnostics.isChecked());
        }
        usbProfileEditor.saveState(outState);
        if (activeTrustConfirmationDialog != null && activeTrustConfirmationDialog.isShowing()
                && activeTrustConfirmationBssid != null) {
            outState.putString(STATE_TRUST_CONFIRMATION_BSSID, activeTrustConfirmationBssid);
        }
    }

    @Override
    protected void onDestroy() {
        if (activeIssueReportDialog != null) {
            if (activeIssueReportDialog.isShowing()) {
                activeIssueReportDialog.dismiss();
            }
            activeIssueReportDialog = null;
        }
        activeIssueReportPreview = null;
        activeIssueReportDiagnostics = null;

        if (activeResetAppDialog != null) {
            if (activeResetAppDialog.isShowing()) {
                activeResetAppDialog.dismiss();
            }
            activeResetAppDialog = null;
        }

        usbProfileEditor.destroy();

        if (activeBlockedNetworksDialog != null) {
            if (activeBlockedNetworksDialog.isShowing()) {
                activeBlockedNetworksDialog.dismiss();
            }
            activeBlockedNetworksDialog = null;
        }

        if (activeTrustConfirmationDialog != null) {
            if (activeTrustConfirmationDialog.isShowing()) {
                activeTrustConfirmationDialog.dismiss();
            }
            activeTrustConfirmationDialog = null;
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
        setCardExpanded(findViewById(R.id.settings_webhook_body), findViewById(R.id.settings_webhook_arrow), true);
        scrollView.post(() -> scrollView.smoothScrollTo(0, webhookPanel.getTop()));
        webhookForm.requestUrlFocus();
    }

    private void focusNetworkPanel() {
        // #619: expand the network card and scroll it into view.
        setCardExpanded(findViewById(R.id.settings_network_beta_body),
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
        header.setOnClickListener(v -> setCardExpanded(body, arrow, body.getVisibility() != View.VISIBLE));
    }

    private void setCardExpanded(View body, TextView arrow, boolean expanded) {
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

        new AlertDialog.Builder(this)
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
                .show();
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

        new AlertDialog.Builder(this)
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
                .show();
    }

    /**
     * #492: the global opt-in. Moved back here from MainActivity's home card because turning it on
     * is a security decision taken against the warning next to it. Under Variante C2 (#606),
     * granting ACCESS_FINE_LOCATION enables {@link KeepADBService} to run with {@code
     * FOREGROUND_SERVICE_TYPE_LOCATION}, keeping Wi-Fi identity unmasked during background
     * keep-alive.
     *
     * <p>Turning it *on* requires ACCESS_FINE_LOCATION, because without it the platform hands the
     * app a masked identity (and the foreground service cannot adopt the location type), causing
     * allowlist mode to fail closed on every check. Turning it off never asks for anything.
     */
    private void onTrustedNetworkToggleClicked() {
        boolean wantAllowlist = trustedNetworkToggle.isChecked();
        if (!wantAllowlist) {
            KeepADBTrustedNetwork.setMode(this, KeepADBTrustedNetwork.MODE_ALL_WIFI);
            refresh();
            return;
        }
        // Revert the switch until permission is confirmed; refresh() below re-derives the actual
        // checked state from the persisted mode either way.
        trustedNetworkToggle.setChecked(false);
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            KeepADBTrustedNetwork.setMode(this, KeepADBTrustedNetwork.MODE_ALLOWLIST);
            refresh();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_trusted_network_permission_title)
                .setMessage(R.string.settings_trusted_network_permission_message)
                .setPositiveButton(R.string.settings_trusted_network_permission_grant, (dialog, which) ->
                        // Requested together per Android's guidance for FINE: the system then
                        // offers the user a precise/approximate choice in one dialog. Only a FINE
                        // grant is actually usable here (see onRequestPermissionsResult).
                        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION},
                                TRUSTED_NETWORK_LOCATION_PERMISSION_REQUEST))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == TRUSTED_NETWORK_LOCATION_PERMISSION_REQUEST) {
            // grantResults can be shorter than permissions (even empty) if the request was interrupted
            // (e.g. the app was backgrounded while the system dialog was up), so re-query the actual
            // permission state instead of indexing into it.
            boolean granted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                KeepADBTrustedNetwork.setMode(this, KeepADBTrustedNetwork.MODE_ALLOWLIST);
            } else {
                Toast.makeText(this, R.string.settings_trusted_network_permission_denied_toast,
                        Toast.LENGTH_LONG).show();
            }
            refresh();
        } else if (requestCode == WIFI_APS_LOCATION_PERMISSION_REQUEST) {
            refresh();
        }
    }

    private void shareDiagnostics() {
        KeepADBDiagnostics.event(this, "diagnostics_export", "settings", "requested",
                "share_sheet");
        Intent share = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.settings_diagnostics_export_subject))
                .putExtra(Intent.EXTRA_TEXT, KeepADBDiagnostics.export(this));
        startActivity(Intent.createChooser(share, getString(R.string.settings_diagnostics_export)));
    }

    private void showIssueReportDialog() {
        showIssueReportDialog(null, false);
    }

    private void showIssueReportDialog(String draftBody, boolean includeDiagnostics) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, 0, padding, 0);

        TextView intro = new TextView(this);
        intro.setText(R.string.settings_issue_report_dialog_message);
        intro.setTextSize(13);
        content.addView(intro);

        CheckBox diagnostics = new CheckBox(this);
        diagnostics.setText(R.string.settings_issue_report_include_diagnostics);
        diagnostics.setContentDescription(getString(R.string.settings_issue_report_include_diagnostics));
        content.addView(diagnostics);

        ScrollView previewScroll = new ScrollView(this);
        previewScroll.setFillViewport(true);
        previewScroll.setScrollbarFadingEnabled(false);
        LinearLayout.LayoutParams previewScrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (280 * getResources().getDisplayMetrics().density));
        previewScrollParams.topMargin = (int) (8 * getResources().getDisplayMetrics().density);
        previewScroll.setLayoutParams(previewScrollParams);

        EditText preview = new EditText(this);
        preview.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        preview.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        preview.setTextSize(13);
        preview.setBackgroundResource(R.drawable.bg_input);
        preview.setPadding(padding, padding, padding, padding);
        preview.setHint(R.string.settings_issue_report_preview_hint);
        preview.setContentDescription(getString(R.string.settings_issue_report_preview));
        previewScroll.addView(preview);
        content.addView(previewScroll);

        String withoutDiagnostics = KeepADBIssueReporter.buildBody(this, false);
        final String[] diagnosticsSection = {null};
        String diagnosticsTitle = getString(R.string.issue_report_diagnostics_section);
        preview.setText(withoutDiagnostics);
        if (draftBody != null) {
            preview.setText(draftBody);
        }
        if (includeDiagnostics) {
            diagnostics.setChecked(true);
        }
        diagnostics.setOnCheckedChangeListener((button, checked) -> {
            String current = preview.getText().toString();
            if (checked && !KeepADBIssueReporter.containsDiagnosticsSection(current, diagnosticsTitle)) {
                if (diagnosticsSection[0] == null) {
                    diagnosticsSection[0] = KeepADBIssueReporter.buildDiagnosticsSection(this);
                }
                preview.setText(KeepADBIssueReporter.addDiagnosticsSection(current,
                        diagnosticsSection[0]));
            } else if (!checked) {
                String withoutSection = KeepADBIssueReporter.removeDiagnosticsSection(
                        current, diagnosticsTitle);
                if (!withoutSection.equals(current)) preview.setText(withoutSection);
            }
        });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.settings_issue_report_dialog_title)
                .setView(content)
                .setPositiveButton(R.string.settings_issue_report_open_feedback, null)
                .setNeutralButton(R.string.settings_issue_report_share, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        activeIssueReportDialog = dialog;
        activeIssueReportPreview = preview;
        activeIssueReportDiagnostics = diagnostics;
        dialog.setOnDismissListener(d -> {
            activeIssueReportDialog = null;
            activeIssueReportPreview = null;
            activeIssueReportDiagnostics = null;
        });
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (!diagnostics.isChecked()) {
                    preview.setText(KeepADBIssueReporter.removeDiagnosticsSection(
                            preview.getText().toString(), diagnosticsTitle));
                }
                Intent browser = new Intent(Intent.ACTION_VIEW,
                        Uri.parse(KeepADBIssueReporter.FEEDBACK_URL));
                startActivity(browser);
                dialog.dismiss();
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                String body = preview.getText().toString();
                if (!diagnostics.isChecked()) {
                    body = KeepADBIssueReporter.removeDiagnosticsSection(body, diagnosticsTitle);
                    preview.setText(body);
                }
                Intent share = new Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.issue_report_title))
                        .putExtra(Intent.EXTRA_TEXT, body);
                startActivity(Intent.createChooser(
                        share, getString(R.string.settings_issue_report_share)));
            });
        });
        dialog.show();
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
        return activeIssueReportDialog;
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

        // Piggyback the mesh-BSSID observation history (#266) on this already-happening
        // identity read instead of adding a new background poll/service for it.
        KeepADBNetworkIdentity currentIdentity = KeepADBNetworkIdentity.current(this);
        if (currentIdentity.isKnown()) {
            KeepADBBssidHistory.recordObservation(this, currentIdentity.displaySsid(), currentIdentity.bssid);
        }

        // #492: the two policy switches, rendered from the persisted state on every refresh so a
        // permission-denied or cancelled opt-in can never leave the switch claiming to be on.
        trustedNetworkToggle.setChecked(KeepADBTrustedNetwork.isAllowlistMode(this));
        // The SSID alternative only widens what allowlist mode accepts, so offering it while
        // allowlist mode is off would present a switch that changes nothing.
        trustedSsidToggle.setEnabled(KeepADBTrustedNetwork.isAllowlistMode(this));
        trustedSsidToggle.setChecked(KeepADBTrustedNetwork.isSsidMatchingEnabled(this));
        KeepADBTrustedNetwork.BlockReason blockReason = KeepADBTrustedNetwork.getBlockReason(this);
        if (blockReason == KeepADBTrustedNetwork.BlockReason.UNTRUSTED_NETWORK) {
            trustedNetworkStatus.setText(R.string.settings_trusted_network_status_untrusted);
            trustedNetworkStatus.setVisibility(View.VISIBLE);
        } else if (blockReason == KeepADBTrustedNetwork.BlockReason.IDENTITY_UNAVAILABLE) {
            trustedNetworkStatus.setText(R.string.settings_trusted_network_status_identity_unavailable);
            trustedNetworkStatus.setVisibility(View.VISIBLE);
        } else {
            trustedNetworkStatus.setVisibility(View.GONE);
        }
        // #616: always visible here, so the background grant can be checked or set up later,
        // independent of the main-screen card and its dismiss state.
        backgroundLocationStatus.setText(KeepADBBackgroundLocation.isGranted(this)
                ? R.string.background_location_status_granted
                : KeepADBTrustedNetwork.isAllowlistMode(this)
                        ? R.string.background_location_status_missing
                        : R.string.background_location_status_missing_inactive);

        // #507: Wi-Fi & Access Points opt-in rendering
        boolean wifiApsEnabled = KeepADBPreferences.isWifiApsFeatureEnabled(this);
        wifiApsFeatureToggle.setChecked(wifiApsEnabled);
        wifiApsContent.setVisibility(wifiApsEnabled ? View.VISIBLE : View.GONE);
        if (wifiApsEnabled) {
            renderAccessPointOverview();
            renderTrustedSsidSection();
            renderBlockedNetworkButton();
        }
    }

    private void renderBlockedNetworkButton() {
        if (wifiApsRecentlyBlockedButton != null) {
            wifiApsRecentlyBlockedButton.setText(getString(R.string.settings_trusted_network_blocked_button,
                    KeepADBBlockedNetworkHistory.getEntries(this).size()));
        }
    }

    private void renderAccessPointOverview() {
        List<KeepADBAccessPointOverview.ApItem> items = KeepADBAccessPointOverview.buildItems(this);

        wifiApsCurrentRow.removeAllViews();
        wifiApsList.removeAllViews();

        KeepADBAccessPointOverview.ApItem currentItem = null;
        List<KeepADBAccessPointOverview.ApItem> others = new ArrayList<>();
        for (KeepADBAccessPointOverview.ApItem item : items) {
            if (item.current) {
                currentItem = item;
            } else {
                others.add(item);
            }
        }

        boolean currentHiddenByFilter = wifiApsTrustedOnly && currentItem != null && !currentItem.trusted;
        if (currentHiddenByFilter) {
            currentItem = null;
        }
        if (wifiApsTrustedOnly) {
            others.removeIf(item -> !item.trusted);
        }

        if (currentItem != null) {
            wifiApsCurrentRow.addView(buildAccessPointRow(currentItem, true));
        } else if (!currentHiddenByFilter) {
            TextView unknown = new TextView(this);
            unknown.setText(R.string.wifi_aps_current_unknown);
            unknown.setTextColor(getColor(R.color.night_muted));
            unknown.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
            wifiApsCurrentRow.addView(unknown);

            boolean locationGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
            if (!locationGranted) {
                Button grantButton = new Button(this);
                grantButton.setId(R.id.btn_wifi_aps_grant_location_permission);
                grantButton.setBackgroundResource(R.drawable.bg_btn_primary);
                grantButton.setMinHeight((int) (48 * getResources().getDisplayMetrics().density));
                grantButton.setPadding(
                        (int) (16 * getResources().getDisplayMetrics().density),
                        (int) (8 * getResources().getDisplayMetrics().density),
                        (int) (16 * getResources().getDisplayMetrics().density),
                        (int) (8 * getResources().getDisplayMetrics().density));
                grantButton.setTextColor(getColor(R.color.title_yellow));
                grantButton.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
                grantButton.setTypeface(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD));
                boolean permanentlyDenied = isLocationPermissionPermanentlyDenied();
                grantButton.setText(permanentlyDenied
                        ? R.string.location_permission_settings_button
                        : R.string.location_permission_panel_grant_button);
                grantButton.setOnClickListener(v -> onLocationPermissionActionClick());
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.topMargin = (int) (8 * getResources().getDisplayMetrics().density);
                grantButton.setLayoutParams(lp);
                wifiApsCurrentRow.addView(grantButton);
            }
        }

        boolean collapsible = others.size() > WIFI_APS_COLLAPSED_OTHERS;
        boolean expanded = wifiApsExpanded && collapsible;
        int visibleCount = expanded ? others.size() : Math.min(others.size(), WIFI_APS_COLLAPSED_OTHERS);
        for (int i = 0; i < visibleCount; i++) {
            wifiApsList.addView(buildAccessPointRow(others.get(i), false));
        }
        wifiApsEmpty.setVisibility(others.isEmpty() ? View.VISIBLE : View.GONE);

        if (collapsible) {
            wifiApsToggle.setVisibility(View.VISIBLE);
            wifiApsToggle.setText(expanded
                    ? getString(R.string.wifi_aps_show_less_button)
                    : getString(R.string.wifi_aps_show_more_button, others.size() - WIFI_APS_COLLAPSED_OTHERS));
        } else {
            wifiApsToggle.setVisibility(View.GONE);
            wifiApsExpanded = false;
        }
    }

    private View buildAccessPointRow(KeepADBAccessPointOverview.ApItem item, boolean highlightCurrent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        if (!highlightCurrent) {
            int topMargin = (int) (12 * getResources().getDisplayMetrics().density);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowParams.topMargin = topMargin;
            row.setLayoutParams(rowParams);
        }

        LinearLayout labelColumn = new LinearLayout(this);
        labelColumn.setOrientation(LinearLayout.VERTICAL);
        labelColumn.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView label = new TextView(this);
        String ssidLabel = (item.ssid == null || item.ssid.isEmpty())
                ? getString(R.string.wifi_aps_ssid_unknown) : item.ssid;
        label.setText(highlightCurrent
                ? getString(R.string.wifi_aps_current_badge) + " · " + ssidLabel
                : ssidLabel);
        label.setTextColor(getColor(R.color.night_text));
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);

        TextView bssidText = new TextView(this);
        bssidText.setText(item.bssid);
        bssidText.setTextColor(getColor(R.color.night_muted));
        bssidText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);

        labelColumn.addView(label);
        labelColumn.addView(bssidText);

        Button trustButton = new Button(this);
        trustButton.setBackgroundResource(
                item.trusted ? R.drawable.bg_btn_secondary : R.drawable.bg_btn_primary);
        trustButton.setMinHeight((int) (48 * getResources().getDisplayMetrics().density));
        trustButton.setTextColor(getColor(item.trusted ? R.color.text_yellow : R.color.title_yellow));
        trustButton.setText(item.trusted ? R.string.wifi_aps_untrust_button : R.string.wifi_aps_trust_button);
        trustButton.setContentDescription(getString(
                item.trusted ? R.string.wifi_aps_untrust_accessibility : R.string.wifi_aps_trust_accessibility,
                ssidLabel));
        trustButton.setOnClickListener(v -> toggleAccessPointTrust(item, ssidLabel));

        row.addView(labelColumn);
        row.addView(trustButton);
        return row;
    }

    private void toggleAccessPointTrust(KeepADBAccessPointOverview.ApItem item, String label) {
        if (item.trusted) {
            KeepADBTrustedNetwork.Entry match = null;
            for (KeepADBTrustedNetwork.Entry entry : KeepADBTrustedNetwork.getEntries(this)) {
                if (entry.bssid.equalsIgnoreCase(item.bssid)) {
                    match = entry;
                    break;
                }
            }
            if (match != null && KeepADBTrustedNetwork.remove(this, match.id)) {
                Toast.makeText(this, getString(R.string.settings_trusted_network_removed_toast, match.label),
                        Toast.LENGTH_SHORT).show();
            }
        } else {
            KeepADBTrustedNetwork.Entry added = KeepADBTrustedNetwork.addBssid(this, item.bssid, label);
            if (added != null) {
                Toast.makeText(this, getString(R.string.settings_trusted_network_added_toast, added.label),
                        Toast.LENGTH_SHORT).show();
                boolean enabled = KeepADBReceiver.trustBssidAndAttemptConnect(this, item.bssid, added.label);
                if (!enabled && !hasSecureSettingsPermission()) {
                    showToggleErrorToast();
                }
                if (item.current) {
                    offerAdditionalMeshBssids();
                }
            }
        }
        refresh();
    }

    private void offerAdditionalMeshBssids() {
        KeepADBNetworkIdentity identity = KeepADBNetworkIdentity.current(this);
        if (!identity.isKnown()) return;
        String ssid = identity.displaySsid();
        if (ssid == null || ssid.isEmpty()) return;

        List<String> alreadyListed = new ArrayList<>();
        for (KeepADBTrustedNetwork.Entry listed : KeepADBTrustedNetwork.getEntries(this)) {
            alreadyListed.add(listed.bssid);
        }
        List<String> additional = KeepADBBssidHistory.getAdditionalBssids(this, ssid, alreadyListed);
        if (additional.isEmpty()) return;

        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_trusted_network_mesh_title)
                .setMessage(getString(R.string.settings_trusted_network_mesh_message, additional.size(), ssid))
                .setPositiveButton(R.string.settings_trusted_network_mesh_add_button, (dialog, which) -> {
                    for (String bssid : additional) {
                        KeepADBReceiver.trustBssidAndAttemptConnect(this, bssid, ssid);
                    }
                    Toast.makeText(this,
                            getString(R.string.settings_trusted_network_mesh_added_toast, additional.size()),
                            Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private boolean isLocationPermissionPermanentlyDenied() {
        boolean previouslyRequested = getPreferences(MODE_PRIVATE)
                .getBoolean(LOCATION_PERMISSION_REQUESTED, false);
        return previouslyRequested
                && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION);
    }

    private void onLocationPermissionActionClick() {
        if (isLocationPermissionPermanentlyDenied()) {
            openAppSettings();
            return;
        }
        getPreferences(MODE_PRIVATE).edit()
                .putBoolean(LOCATION_PERMISSION_REQUESTED, true).apply();
        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION},
                WIFI_APS_LOCATION_PERMISSION_REQUEST);
    }

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.fromParts("package", getPackageName(), null));
        startActivity(intent);
    }

    private void renderTrustedSsidSection() {
        boolean enabled = KeepADBTrustedNetwork.isSsidMatchingEnabled(this);
        wifiSsidsSection.setVisibility(enabled ? View.VISIBLE : View.GONE);
        if (!enabled) return;

        wifiSsidsCurrentRow.removeAllViews();
        wifiSsidsList.removeAllViews();

        KeepADBNetworkIdentity identity = KeepADBNetworkIdentity.current(this);
        String currentSsid = identity.isKnown() ? identity.displaySsid() : null;
        if (currentSsid == null || currentSsid.isEmpty()) {
            TextView unknown = new TextView(this);
            unknown.setText(R.string.wifi_ssids_current_unknown);
            unknown.setTextColor(getColor(R.color.night_muted));
            unknown.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
            wifiSsidsCurrentRow.addView(unknown);
        } else {
            wifiSsidsCurrentRow.addView(buildCurrentSsidRow(currentSsid));
        }

        List<KeepADBTrustedNetwork.SsidEntry> entries = KeepADBTrustedNetwork.getSsidEntries(this);
        for (KeepADBTrustedNetwork.SsidEntry entry : entries) {
            wifiSsidsList.addView(buildTrustedSsidRow(entry));
        }
        wifiSsidsEmpty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private View buildCurrentSsidRow(String currentSsid) {
        boolean listed = KeepADBTrustedNetwork.findSsidEntryForCurrentNetwork(this) != null;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = new TextView(this);
        label.setText(getString(R.string.wifi_aps_current_badge) + " · " + currentSsid);
        label.setTextColor(getColor(R.color.night_text));
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        label.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.addView(label);

        if (!listed) {
            Button add = new Button(this);
            add.setBackgroundResource(R.drawable.bg_btn_primary);
            add.setMinHeight((int) (48 * getResources().getDisplayMetrics().density));
            add.setTextColor(getColor(R.color.title_yellow));
            add.setText(R.string.wifi_ssids_add_button);
            add.setContentDescription(getString(R.string.wifi_ssids_add_accessibility, currentSsid));
            add.setOnClickListener(v -> {
                KeepADBTrustedNetwork.SsidEntry added = KeepADBTrustedNetwork.addCurrentSsid(this);
                if (added == null) {
                    Toast.makeText(this, R.string.settings_trusted_network_add_failed_toast,
                            Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, getString(R.string.wifi_ssids_added_toast, added.ssid),
                            Toast.LENGTH_SHORT).show();
                }
                refresh();
            });
            row.addView(add);
        }
        return row;
    }

    private View buildTrustedSsidRow(KeepADBTrustedNetwork.SsidEntry entry) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int topMargin = (int) (12 * getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = topMargin;
        row.setLayoutParams(rowParams);

        TextView label = new TextView(this);
        label.setText(entry.ssid);
        label.setTextColor(getColor(R.color.night_text));
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        label.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.addView(label);

        Button remove = new Button(this);
        remove.setBackgroundResource(R.drawable.bg_btn_secondary);
        remove.setMinHeight((int) (48 * getResources().getDisplayMetrics().density));
        remove.setTextColor(getColor(R.color.text_yellow));
        remove.setText(R.string.wifi_ssids_remove_button);
        remove.setContentDescription(getString(R.string.wifi_ssids_remove_accessibility, entry.ssid));
        remove.setOnClickListener(v -> {
            if (KeepADBTrustedNetwork.removeSsid(this, entry.id)) {
                Toast.makeText(this, getString(R.string.wifi_ssids_removed_toast, entry.ssid),
                        Toast.LENGTH_SHORT).show();
            }
            refresh();
        });
        row.addView(remove);
        return row;
    }

    private void showBlockedNetworkDialog() {
        List<KeepADBBlockedNetworkHistory.Entry> entries =
                KeepADBBlockedNetworkHistory.getEntries(this);
        if (entries.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.settings_trusted_network_blocked_title)
                    .setMessage(R.string.settings_trusted_network_blocked_empty_message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        rows.setPadding(padding, 0, padding, 0);
        final AlertDialog[] dialogHolder = new AlertDialog[1];
        // Newest first: the access point the user just failed to connect on is the one they came
        // here for, and getEntries() returns the log oldest-first.
        for (int i = entries.size() - 1; i >= 0; i--) {
            KeepADBBlockedNetworkHistory.Entry entry = entries.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout labelColumn = new LinearLayout(this);
            labelColumn.setOrientation(LinearLayout.VERTICAL);
            labelColumn.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            TextView label = new TextView(this);
            label.setText(entry.label());
            label.setTextColor(getColor(R.color.night_text));
            TextView detail = new TextView(this);
            detail.setText(getString(R.string.settings_trusted_network_blocked_detail,
                    entry.bssid,
                    android.text.format.DateUtils.getRelativeTimeSpanString(entry.lastSeenAt,
                            System.currentTimeMillis(),
                            android.text.format.DateUtils.MINUTE_IN_MILLIS).toString()));
            detail.setTextColor(getColor(R.color.night_muted));
            detail.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
            labelColumn.addView(label);
            labelColumn.addView(detail);
            Button allow = new Button(this);
            allow.setText(R.string.settings_trusted_network_blocked_allow_button);
            allow.setContentDescription(getString(
                    R.string.settings_trusted_network_blocked_allow_accessibility, entry.label()));
            allow.setOnClickListener(v -> {
                KeepADBReceiver.trustBssidAndAttemptConnect(this, entry.bssid, entry.label());
                Toast.makeText(this,
                        getString(R.string.settings_trusted_network_added_toast, entry.label()),
                        Toast.LENGTH_SHORT).show();
                dialogHolder[0].dismiss();
                refresh();
            });
            row.addView(labelColumn);
            row.addView(allow);
            rows.addView(row);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(rows);
        dialogHolder[0] = new AlertDialog.Builder(this)
                .setTitle(R.string.settings_trusted_network_blocked_title)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        activeBlockedNetworksDialog = dialogHolder[0];
        dialogHolder[0].setOnDismissListener(d -> {
            if (activeBlockedNetworksDialog == d) {
                activeBlockedNetworksDialog = null;
            }
        });
        dialogHolder[0].show();
    }

    AlertDialog getActiveBlockedNetworksDialog() {
        return activeBlockedNetworksDialog;
    }

    /**
     * #598: the in-app half of the details-off trust prompt. The notification names no network, so
     * this dialog is where the user sees which one they are deciding on -- label and BSSID of the
     * access point the prompt was raised for, taken from {@link
     * KeepADBNetworkTrustPrompt#pendingConfirmation}, never from the intent or from the current
     * connection. Both buttons act on that captured entry only: allow goes through {@link
     * KeepADBReceiver#handleTrustNetworkAction} (the notification allow action's own path, with its
     * locked-device gate and BSSID validation, ending in {@code trustBssidAndAttemptConnect}), and
     * block through {@link KeepADBReceiver#handleDismissNetworkPromptAction}, exactly like the
     * notification's block action. Nothing is re-read at click time, so a roam between showing the
     * dialog and the click cannot swap in a different BSSID.
     *
     * <p>If there is no pending entry for {@code bssid} (already trusted, evicted, or unknown), no
     * trust choice is offered; the recently-blocked list opens instead, where every entry still
     * needs its own explicit click.
     */
    private void showTrustConfirmationDialog(String bssid) {
        KeepADBBlockedNetworkHistory.Entry entry =
                KeepADBNetworkTrustPrompt.pendingConfirmation(this, bssid);
        if (entry == null) {
            KeepADBDiagnostics.event(this, "user_action", "network_trust_prompt", "skipped",
                    "confirmation_not_pending");
            showBlockedNetworkDialog();
            return;
        }
        final String confirmedBssid = entry.bssid;
        final String confirmedLabel = entry.label();
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.network_prompt_title)
                .setMessage(getString(R.string.network_prompt_text, confirmedLabel, confirmedBssid))
                .setPositiveButton(R.string.network_prompt_allow, (d, which) -> {
                    boolean enabled = KeepADBReceiver.handleTrustNetworkAction(
                            this, confirmedBssid, confirmedLabel);
                    if (isListedAsTrusted(confirmedBssid)) {
                        Toast.makeText(this,
                                getString(R.string.settings_trusted_network_added_toast,
                                        confirmedLabel),
                                Toast.LENGTH_SHORT).show();
                        if (!enabled && !hasSecureSettingsPermission()) {
                            showToggleErrorToast();
                        }
                    }
                    refresh();
                })
                .setNegativeButton(R.string.network_prompt_block, (d, which) ->
                        KeepADBReceiver.handleDismissNetworkPromptAction(this))
                .create();
        activeTrustConfirmationDialog = dialog;
        activeTrustConfirmationBssid = confirmedBssid;
        dialog.setOnDismissListener(d -> {
            if (activeTrustConfirmationDialog == d) {
                activeTrustConfirmationDialog = null;
                activeTrustConfirmationBssid = null;
            }
        });
        dialog.show();
    }

    private boolean isListedAsTrusted(String bssid) {
        for (KeepADBTrustedNetwork.Entry entry : KeepADBTrustedNetwork.getEntries(this)) {
            if (entry.bssid.equalsIgnoreCase(bssid)) return true;
        }
        return false;
    }

    AlertDialog getActiveTrustConfirmationDialog() {
        return activeTrustConfirmationDialog;
    }

    private boolean hasSecureSettingsPermission() {
        return checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void showToggleErrorToast() {
        if (!hasSecureSettingsPermission()) {
            Toast.makeText(this, getString(R.string.permission_error_toast, getPackageName()),
                    Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, getString(R.string.toggle_failed_toast), Toast.LENGTH_LONG).show();
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
