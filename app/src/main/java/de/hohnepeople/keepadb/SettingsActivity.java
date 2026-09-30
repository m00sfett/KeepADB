package de.hohnepeople.keepadb;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageInfo;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

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

    // #654/#655: the Network card.
    private TextView networkSubtitle;
    private TextView networkStatusLabel;
    private TextView networkConnectionLine;
    private TextView networkStatusCause;
    private Button networkStatusAction;
    private TextView networkPrivacyHint;
    private RadioGroup networkModeGroup;
    private RadioButton networkModeAllWifi;
    private RadioButton networkModeAllowlist;
    private TextView backgroundLocationStatus;
    private TextView networkDetectionNow;
    private Button backgroundLocationButton;
    private TextView networkAllowedCount;
    private TextView networkPreventedCount;
    private TextView networkListsInactiveHint;
    private View networkSsidHeader;
    private View networkSsidBody;
    private TextView networkSsidArrow;
    private TextView networkSsidState;
    private TextView networkSsidEffect;
    private Switch trustedSsidToggle;
    /**
     * What {@link #networkStatusAction} does right now and which access point it is bound to,
     * captured when the card was rendered: the button acts on the access point the user saw,
     * never on whatever the device happens to be connected to at click time.
     */
    private KeepADBNetworkCardState.Action networkStatusActionKind =
            KeepADBNetworkCardState.Action.NONE;
    private String networkActionBssid;
    private String networkActionLabel;

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

    // #507/#654: Wi-Fi observation and the Wi-Fi-name list of the Network card.
    static final int WIFI_APS_LOCATION_PERMISSION_REQUEST = 3002;
    private static final String LOCATION_PERMISSION_REQUESTED = "location_permission_requested";

    private Switch wifiApsFeatureToggle;
    private View wifiApsContent;
    private LinearLayout wifiSsidsCurrentRow;
    private LinearLayout wifiSsidsList;
    private TextView wifiSsidsEmpty;
    private AlertDialog activeTrustConfirmationDialog;
    /** #644: the step-2 rationale dialog for the optional background location grant, if showing. */
    private AlertDialog activeBackgroundLocationDialog;

    /** #661: refreshes the visible Network card while a Wi-Fi network changes. */
    private ConnectivityManager.NetworkCallback wifiStatusCallback;
    /** #604: the BSSID {@link #activeTrustConfirmationDialog} is bound to, or null if none is showing. */
    private String activeTrustConfirmationBssid;

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
    // Sonstiges structure). Only the outer network card is in this table; the advanced Wi-Fi-name
    // section inside it (#655) is one more collapsed-by-default toggle, bound in bindNetworkCard().
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

        bindNetworkCard();

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
    protected void onStart() {
        super.onStart();
        registerWifiStatusCallback();
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
    protected void onStop() {
        unregisterWifiStatusCallback();
        super.onStop();
    }

    private void registerWifiStatusCallback() {
        if (wifiStatusCallback != null) return;
        ConnectivityManager connectivityManager = getSystemService(ConnectivityManager.class);
        if (connectivityManager == null) return;

        ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                refresh();
            }

            @Override
            public void onLost(Network network) {
                refresh();
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                refresh();
            }
        };
        try {
            NetworkRequest request = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build();
            connectivityManager.registerNetworkCallback(
                    request, callback, new Handler(Looper.getMainLooper()));
            wifiStatusCallback = callback;
        } catch (RuntimeException e) {
            android.util.Log.w("KeepADB", "Failed to register Settings Wi-Fi callback", e);
        }
    }

    private void unregisterWifiStatusCallback() {
        ConnectivityManager.NetworkCallback callback = wifiStatusCallback;
        if (callback == null) return;
        wifiStatusCallback = null;
        ConnectivityManager connectivityManager = getSystemService(ConnectivityManager.class);
        if (connectivityManager == null) return;
        try {
            connectivityManager.unregisterNetworkCallback(callback);
        } catch (RuntimeException e) {
            android.util.Log.w("KeepADB", "Failed to unregister Settings Wi-Fi callback", e);
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

        if (activeTrustConfirmationDialog != null) {
            if (activeTrustConfirmationDialog.isShowing()) {
                activeTrustConfirmationDialog.dismiss();
            }
            activeTrustConfirmationDialog = null;
        }

        if (activeBackgroundLocationDialog != null) {
            if (activeBackgroundLocationDialog.isShowing()) {
                activeBackgroundLocationDialog.dismiss();
            }
            activeBackgroundLocationDialog = null;
        }

        super.onDestroy();
    }

    static String resolveWebhookDraft(String savedUrl, String currentDraft, boolean hasCurrentDraft) {
        if (hasCurrentDraft) {
            return currentDraft == null ? "" : currentDraft;
        }
        return savedUrl == null ? "" : savedUrl;
    }

    /**
     * #654/#655: wires the Network card. Every switch and choice uses OnClick, not a
     * checked-change listener: {@link #refresh()} re-renders them from the persisted state, and a
     * checked-change listener would fire on that programmatic write too (and so could change a
     * stored setting just by opening Settings).
     */
    private void bindNetworkCard() {
        networkSubtitle = findViewById(R.id.settings_network_beta_subtitle);
        networkStatusLabel = findViewById(R.id.network_status_label);
        networkConnectionLine = findViewById(R.id.network_connection_line);
        networkStatusCause = findViewById(R.id.network_status_cause);
        networkStatusAction = findViewById(R.id.network_status_action);
        networkPrivacyHint = findViewById(R.id.network_privacy_hint);
        networkModeGroup = findViewById(R.id.network_mode_group);
        networkModeAllWifi = findViewById(R.id.network_mode_all_wifi);
        networkModeAllowlist = findViewById(R.id.network_mode_allowlist);
        backgroundLocationStatus = findViewById(R.id.settings_background_location_status);
        networkDetectionNow = findViewById(R.id.network_detection_now);
        backgroundLocationButton = findViewById(R.id.settings_background_location_button);
        networkAllowedCount = findViewById(R.id.network_allowed_count);
        networkPreventedCount = findViewById(R.id.network_prevented_count);
        networkListsInactiveHint = findViewById(R.id.network_lists_inactive_hint);
        wifiApsFeatureToggle = findViewById(R.id.settings_wifi_aps_feature_toggle);
        wifiApsContent = findViewById(R.id.settings_wifi_aps_content);
        networkSsidHeader = findViewById(R.id.network_ssid_header);
        networkSsidBody = findViewById(R.id.network_ssid_body);
        networkSsidArrow = findViewById(R.id.network_ssid_arrow);
        networkSsidState = findViewById(R.id.network_ssid_state);
        networkSsidEffect = findViewById(R.id.network_ssid_effect);
        trustedSsidToggle = findViewById(R.id.settings_trusted_ssid_toggle);
        wifiSsidsCurrentRow = findViewById(R.id.wifi_ssids_current_row);
        wifiSsidsList = findViewById(R.id.wifi_ssids_list);
        wifiSsidsEmpty = findViewById(R.id.wifi_ssids_empty);

        networkStatusAction.setOnClickListener(v -> onNetworkStatusActionClicked());
        networkModeAllWifi.setOnClickListener(v -> {
            KeepADBTrustedNetwork.setMode(this, KeepADBTrustedNetwork.MODE_ALL_WIFI);
            refresh();
        });
        networkModeAllowlist.setOnClickListener(v -> onAllowlistOptionClicked());

        // #616/#644: never grants anything; the user picks "Allow all the time" on the system
        // page. Without the grant, the rationale dialog comes first (step 2); with it, the button
        // goes straight to the page so the grant can still be checked or revoked.
        backgroundLocationButton.setOnClickListener(v -> {
            if (KeepADBBackgroundLocation.isGranted(this)) {
                KeepADBBackgroundLocation.openSettings(this);
            } else {
                showBackgroundLocationDialog();
            }
        });

        findViewById(R.id.network_allowed_row).setOnClickListener(v ->
                startActivity(NetworkListActivity.intent(this, NetworkListActivity.VIEW_ALLOWED)));
        findViewById(R.id.network_prevented_row).setOnClickListener(v ->
                startActivity(NetworkListActivity.intent(this, NetworkListActivity.VIEW_PREVENTED)));
        findViewById(R.id.network_observed_row).setOnClickListener(v ->
                startActivity(NetworkListActivity.intent(this, NetworkListActivity.VIEW_OBSERVED)));

        // The observation option only controls the observation and its list (#654).
        wifiApsFeatureToggle.setOnClickListener(v -> {
            KeepADBPreferences.setWifiApsFeatureEnabled(this, wifiApsFeatureToggle.isChecked());
            refresh();
        });

        networkSsidHeader.setOnClickListener(v ->
                setSsidSectionExpanded(networkSsidBody.getVisibility() != View.VISIBLE));
        setSsidSectionExpanded(false);
        trustedSsidToggle.setOnClickListener(v -> {
            KeepADBTrustedNetwork.setSsidMatchingEnabled(this, trustedSsidToggle.isChecked());
            refresh();
        });
    }

    /**
     * #655: the advanced Wi-Fi-name section starts collapsed like every card and, like the cards,
     * keeps no expand state across openings. Its closed header already names the state and the
     * number of names, so collapsing it never hides an active setting.
     */
    private void setSsidSectionExpanded(boolean expanded) {
        setCardExpanded(networkSsidBody, networkSsidArrow, expanded);
        networkSsidHeader.setStateDescription(getString(expanded
                ? R.string.card_state_expanded : R.string.card_state_collapsed));
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
     * #492: the global opt-in, now the second option of the mode choice (#654). Moved back here
     * from MainActivity's home card because turning it on is a security decision taken with the
     * background-access facts in view. Under Variante C2 (#606), granting ACCESS_FINE_LOCATION
     * enables {@link KeepADBService} to run with {@code FOREGROUND_SERVICE_TYPE_LOCATION}, which
     * keeps Wi-Fi identity unmasked during keep-alive when the service's foreground promotion
     * originated from the foreground, or from the background with the optional #616 {@code
     * ACCESS_BACKGROUND_LOCATION} grant ("Allow all the time"). Without that grant, a
     * background-originated promotion stays masked until a later foreground-originated restart,
     * and on API 34+ it falls back to {@code connectedDevice} alone (#629/#630; see {@code
     * docs/trusted-networks-measurement.md}, "Nachtrag 5").
     *
     * <p>Turning it *on* requires ACCESS_FINE_LOCATION, because without it the platform hands the
     * app a masked identity (and the foreground service cannot adopt the location type), causing
     * allowlist mode to fail closed on every check. Choosing "all Wi-Fi networks" again never asks
     * for anything. Clicking the option that is already selected does nothing, so it can never
     * re-open a dialog.
     */
    private void onAllowlistOptionClicked() {
        if (KeepADBTrustedNetwork.isAllowlistMode(this)) {
            refresh();
            return;
        }
        // Keep the choice on "all networks" until permission is confirmed; refresh() re-derives
        // the shown choice from the persisted mode either way.
        networkModeGroup.check(R.id.network_mode_all_wifi);
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            enableAllowlistMode();
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

    /**
     * Step 1 is done (ACCESS_FINE_LOCATION is granted): switches to allowlist mode and, unless the
     * optional background grant is already there, follows up with the step-2 rationale (#644).
     */
    private void enableAllowlistMode() {
        KeepADBTrustedNetwork.setMode(this, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        refresh();
        if (!KeepADBBackgroundLocation.isGranted(this)) {
            showBackgroundLocationDialog();
        }
    }

    /**
     * #644, step 2: explains why "Allow all the time" is wanted and lets the user act on it. This
     * never requests ACCESS_BACKGROUND_LOCATION itself -- since Android 11 the only way is the
     * app's system permission page, which "Open settings" jumps to.
     *
     * <p>"Trust all Wi-Fi networks instead" is only offered while allowlist mode is on; in
     * all-Wi-Fi mode it would be a no-op. "Later" keeps whatever mode is set (allowlist then runs
     * with foreground location only, and the status line keeps showing the missing grant).
     */
    private void showBackgroundLocationDialog() {
        if (activeBackgroundLocationDialog != null && activeBackgroundLocationDialog.isShowing()) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(R.string.background_location_panel_title)
                .setMessage(R.string.background_location_panel_body)
                .setPositiveButton(R.string.location_permission_settings_button, (d, which) ->
                        KeepADBBackgroundLocation.openSettings(this))
                .setNegativeButton(R.string.background_location_dialog_later, null);
        if (KeepADBTrustedNetwork.isAllowlistMode(this)) {
            builder.setNeutralButton(R.string.location_permission_panel_fallback_button, (d, which) -> {
                KeepADBTrustedNetwork.setMode(this, KeepADBTrustedNetwork.MODE_ALL_WIFI);
                refresh();
            });
        }
        AlertDialog dialog = builder.create();
        activeBackgroundLocationDialog = dialog;
        dialog.setOnDismissListener(d -> {
            if (activeBackgroundLocationDialog == d) {
                activeBackgroundLocationDialog = null;
            }
        });
        dialog.show();
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
                enableAllowlistMode();
            } else {
                Toast.makeText(this, R.string.settings_trusted_network_permission_denied_toast,
                        Toast.LENGTH_LONG).show();
                refresh();
            }
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
        // identity read instead of adding a new background poll/service for it. The user's
        // observation option gates every write, including reads caused by opening Settings.
        KeepADBNetworkIdentity currentIdentity = KeepADBNetworkIdentity.current(this);
        if (currentIdentity.isKnown() && KeepADBPreferences.isWifiApsFeatureEnabled(this)) {
            KeepADBBssidHistory.recordObservation(this, currentIdentity.displaySsid(), currentIdentity.bssid);
        }

        renderNetworkCard(currentIdentity);
    }

    /**
     * #654/#655: renders the Network card from the persisted settings and the current connection,
     * top to bottom in the order the user reads it. All facts come from {@link
     * KeepADBNetworkCardState}; this method only shows them. It never writes a setting: opening or
     * refreshing Settings can therefore never switch the mode or the Wi-Fi-name matching, whatever
     * the connection looks like.
     */
    private void renderNetworkCard(KeepADBNetworkIdentity identity) {
        boolean fineLocation = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        // A readable BSSID proves an association, so the transport is only asked without one.
        boolean wifiConnected = identity.isKnown() || KeepADBService.isWifiConnected(this);
        KeepADBNetworkCardState.Snapshot state = KeepADBNetworkCardState.derive(
                KeepADBNetworkCardState.read(this, identity, wifiConnected, fineLocation,
                        isLocationServiceOn(), KeepADBBackgroundLocation.isGranted(this)));
        boolean ssidMatching = KeepADBTrustedNetwork.isSsidMatchingEnabled(this);

        // Head: the active mode, read back from the stored settings, visible while closed.
        networkSubtitle.setText(getString(R.string.network_head_mode,
                getString(KeepADBNetworkCardText.modeOption(state.mode))));

        // Current connection: decision, cause and the fitting action come before the mode choice.
        networkStatusLabel.setText(KeepADBNetworkCardText.connectionLabel(state.connection));
        networkStatusLabel.setTextColor(getColor(
                KeepADBNetworkCardText.connectionColor(state.connection, state.mode)));
        if (identity.isKnown()) {
            networkConnectionLine.setText(KeepADBNetworkDisplay.ssid(this, identity.displaySsid(), null)
                    + " · " + KeepADBNetworkDisplay.bssid(this, identity.bssid));
            networkConnectionLine.setVisibility(View.VISIBLE);
        } else {
            networkConnectionLine.setVisibility(View.GONE);
        }
        networkStatusCause.setText(KeepADBNetworkCardText.cause(state.cause));
        renderStatusAction(state, identity);
        networkPrivacyHint.setVisibility(KeepADBNetworkDisplay.hidden(this) ? View.VISIBLE : View.GONE);

        // Mode choice: rendered from the persisted mode, so a cancelled opt-in can never leave the
        // choice claiming a mode that is not stored. The second option names the Wi-Fi names
        // while the matching setting is on.
        networkModeGroup.check(state.mode == KeepADBNetworkCardState.Mode.ALL_WIFI
                ? R.id.network_mode_all_wifi : R.id.network_mode_allowlist);
        networkModeAllowlist.setText(KeepADBNetworkCardText.modeOption(
                KeepADBNetworkCardState.mode(true, ssidMatching)));

        // Background access (#616/#645) and the current reading are two separate facts.
        backgroundLocationStatus.setText(KeepADBNetworkCardText.background(state.background));
        backgroundLocationStatus.setTextColor(
                getColor(KeepADBNetworkCardText.backgroundColor(state.background)));
        networkDetectionNow.setText(KeepADBNetworkCardText.detection(state.detection));
        backgroundLocationButton.setText(
                state.background == KeepADBNetworkCardState.Background.RESTRICTED
                        ? R.string.network_background_setup_button
                        : R.string.background_location_settings_button);

        // Management entries stay reachable in every mode and whatever the observation says.
        int allowedCount = KeepADBTrustedNetwork.getEntries(this).size();
        networkAllowedCount.setText(String.valueOf(allowedCount));
        networkPreventedCount.setText(
                String.valueOf(KeepADBBlockedNetworkHistory.getEntries(this).size()));
        networkListsInactiveHint.setText(
                KeepADBNetworkCardText.inactiveListHint(this, ssidMatching));
        networkListsInactiveHint.setVisibility(
                state.mode == KeepADBNetworkCardState.Mode.ALL_WIFI && allowedCount > 0
                        ? View.VISIBLE : View.GONE);

        // Observation: controls only the observation and its list (#654).
        boolean observing = KeepADBPreferences.isWifiApsFeatureEnabled(this);
        wifiApsFeatureToggle.setChecked(observing);
        wifiApsContent.setVisibility(observing ? View.VISIBLE : View.GONE);

        // Advanced Wi-Fi-name section (#655): the header states the effect and the number of
        // names, never a name. The switch stays inoperable while it would change nothing.
        int nameCount = KeepADBTrustedNetwork.getSsidEntries(this).size();
        switch (state.nameMatching) {
            case ACTIVE:
                networkSsidState.setText(getString(R.string.network_ssid_state_on, nameCount));
                break;
            case NO_EFFECT:
                networkSsidState.setText(getString(R.string.network_ssid_state_no_effect, nameCount));
                break;
            case OFF:
            default:
                networkSsidState.setText(R.string.network_ssid_state_off);
                break;
        }
        networkSsidEffect.setText(state.nameMatching == KeepADBNetworkCardState.NameMatching.NO_EFFECT
                ? KeepADBNetworkCardText.inactiveListHint(this, ssidMatching)
                : getString(KeepADBNetworkCardText.nameMatchingEffect(state.nameMatching)));
        trustedSsidToggle.setEnabled(KeepADBTrustedNetwork.isAllowlistMode(this));
        trustedSsidToggle.setChecked(ssidMatching);
        renderSsidNames(identity);
    }

    private void renderStatusAction(KeepADBNetworkCardState.Snapshot state,
                                    KeepADBNetworkIdentity identity) {
        networkStatusActionKind = state.action;
        String ssid = identity.isKnown() ? identity.displaySsid() : null;
        networkActionBssid = identity.isKnown() ? identity.bssid : null;
        networkActionLabel = (ssid == null || ssid.isEmpty()) ? networkActionBssid : ssid;

        int label = KeepADBNetworkCardText.action(state.action);
        if (label == 0) {
            networkStatusAction.setVisibility(View.GONE);
            return;
        }
        if (state.action == KeepADBNetworkCardState.Action.GRANT_LOCATION
                && isLocationPermissionPermanentlyDenied()) {
            label = R.string.location_permission_settings_button;
        }
        networkStatusAction.setText(label);
        networkStatusAction.setContentDescription(
                state.action == KeepADBNetworkCardState.Action.ALLOW_ACCESS_POINT
                        ? getString(R.string.network_action_allow_ap_accessibility,
                                KeepADBNetworkDisplay.label(this, ssid, identity.bssid, null))
                        : null);
        networkStatusAction.setVisibility(View.VISIBLE);
    }

    private void onNetworkStatusActionClicked() {
        switch (networkStatusActionKind) {
            case ALLOW_ACCESS_POINT:
                if (networkActionBssid != null) {
                    // Grants exactly the access point the card showed; never switches anything on.
                    KeepADBNetworkActions.allowAccessPoint(this, networkActionBssid,
                            networkActionLabel, true, this::refresh);
                }
                break;
            case GRANT_LOCATION:
                onLocationPermissionActionClick();
                break;
            case OPEN_LOCATION_SETTINGS:
                KeepADBNetworkActions.openLocationSettings(this);
                break;
            case OPEN_WIFI_SETTINGS:
                KeepADBNetworkActions.openWifiSettings(this);
                break;
            case SET_UP_BACKGROUND:
                showBackgroundLocationDialog();
                break;
            case NONE:
            default:
                break;
        }
    }

    private boolean isLocationServiceOn() {
        LocationManager manager = getSystemService(LocationManager.class);
        return manager == null || manager.isLocationEnabled();
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

    /**
     * #655: the Wi-Fi-name list of the advanced section -- the current name with its add action,
     * then every saved name with its remove action. It renders whatever the switch says, so saved
     * names stay reachable in every mode; the effect line above it says whether they count.
     */
    private void renderSsidNames(KeepADBNetworkIdentity identity) {
        wifiSsidsCurrentRow.removeAllViews();
        wifiSsidsList.removeAllViews();
        // One numbering for the current name and the list, so a hidden name reads alike in both.
        KeepADBNetworkDisplay.Numbering numbering = new KeepADBNetworkDisplay.Numbering();

        String currentSsid = identity.isKnown() ? identity.displaySsid() : null;
        if (currentSsid == null || currentSsid.isEmpty()) {
            TextView unknown = new TextView(this);
            unknown.setText(R.string.wifi_ssids_current_unknown);
            unknown.setTextColor(getColor(R.color.night_muted));
            unknown.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
            wifiSsidsCurrentRow.addView(unknown);
        } else {
            wifiSsidsCurrentRow.addView(buildCurrentSsidRow(currentSsid, numbering));
        }

        List<KeepADBTrustedNetwork.SsidEntry> entries = KeepADBTrustedNetwork.getSsidEntries(this);
        for (KeepADBTrustedNetwork.SsidEntry entry : entries) {
            wifiSsidsList.addView(buildTrustedSsidRow(entry, numbering));
        }
        wifiSsidsEmpty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private View buildCurrentSsidRow(String currentSsid, KeepADBNetworkDisplay.Numbering numbering) {
        boolean listed = KeepADBTrustedNetwork.findSsidEntryForCurrentNetwork(this) != null;
        String shownName = KeepADBNetworkDisplay.ssid(this, currentSsid, numbering);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);

        TextView label = new TextView(this);
        label.setText(getString(R.string.wifi_aps_current_badge) + " · " + shownName);
        label.setTextColor(getColor(R.color.night_text));
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        row.addView(label);

        if (!listed) {
            Button add = newRowActionButton(R.drawable.bg_btn_primary, R.color.title_yellow);
            add.setText(R.string.wifi_ssids_add_button);
            add.setContentDescription(getString(R.string.wifi_ssids_add_accessibility, shownName));
            add.setOnClickListener(v -> {
                KeepADBTrustedNetwork.SsidEntry added = KeepADBTrustedNetwork.addCurrentSsid(this);
                if (added == null) {
                    Toast.makeText(this, R.string.settings_trusted_network_add_failed_toast,
                            Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, getString(R.string.wifi_ssids_added_toast,
                            KeepADBNetworkDisplay.ssid(this, added.ssid, null)), Toast.LENGTH_SHORT).show();
                }
                refresh();
            });
            row.addView(add);
        }
        return row;
    }

    /**
     * #655: the Allow/Remove action of a Wi-Fi-name row, laid out like the row buttons of {@link
     * NetworkListActivity}. The Material default button has no horizontal padding, so at a large
     * font the label filled the whole button and touched both edges; here the padding is explicit,
     * the height is at least 48dp, and the button sits at the start edge below the name, so a long
     * label wraps inside the row instead of being cut.
     */
    private Button newRowActionButton(int backgroundRes, int textColorRes) {
        Button button = new Button(this);
        button.setBackgroundResource(backgroundRes);
        button.setMinHeight(dp(48));
        button.setPadding(dp(16), dp(8), dp(16), dp(8));
        button.setTextColor(getColor(textColorRes));
        button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        button.setTypeface(android.graphics.Typeface.create("sans-serif-condensed",
                android.graphics.Typeface.BOLD));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        params.gravity = android.view.Gravity.START;
        button.setLayoutParams(params);
        return button;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private View buildTrustedSsidRow(KeepADBTrustedNetwork.SsidEntry entry,
                                     KeepADBNetworkDisplay.Numbering numbering) {
        String shownName = KeepADBNetworkDisplay.ssid(this, entry.ssid, numbering);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        int topMargin = (int) (12 * getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = topMargin;
        row.setLayoutParams(rowParams);

        TextView label = new TextView(this);
        label.setText(shownName);
        label.setTextColor(getColor(R.color.night_text));
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        row.addView(label);

        Button remove = newRowActionButton(R.drawable.bg_btn_secondary, R.color.text_yellow);
        remove.setText(R.string.wifi_ssids_remove_button);
        remove.setContentDescription(getString(R.string.wifi_ssids_remove_accessibility, shownName));
        remove.setOnClickListener(v -> {
            if (KeepADBTrustedNetwork.removeSsid(this, entry.id)) {
                Toast.makeText(this, getString(R.string.wifi_ssids_removed_toast, shownName),
                        Toast.LENGTH_SHORT).show();
            }
            refresh();
        });
        row.addView(remove);
        return row;
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
            startActivity(NetworkListActivity.intent(this, NetworkListActivity.VIEW_PREVENTED));
            return;
        }
        final String confirmedBssid = entry.bssid;
        final String confirmedLabel = entry.label();
        final String displayLabel = KeepADBNetworkDisplay.quoted(this, confirmedLabel);
        final String displayBssid = KeepADBNetworkDisplay.bssid(this, confirmedBssid);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.network_prompt_title)
                .setMessage(getString(R.string.network_prompt_text, displayLabel, displayBssid))
                .setPositiveButton(R.string.network_prompt_allow, (d, which) -> {
                    boolean enabled = KeepADBReceiver.handleTrustNetworkAction(
                            this, confirmedBssid, confirmedLabel);
                    if (isListedAsTrusted(confirmedBssid)) {
                        Toast.makeText(this,
                                getString(R.string.settings_trusted_network_added_toast,
                                        KeepADBNetworkDisplay.quoted(this, confirmedLabel)),
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
