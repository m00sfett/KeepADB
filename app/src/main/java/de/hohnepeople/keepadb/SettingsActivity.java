package de.hohnepeople.keepadb;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** Central settings screen for KeepADB options (Keep-Alive, Language, Webhook, etc.). */
public class SettingsActivity extends Activity {
    /** Intent extra requesting that the webhook section be scrolled into view and focused. */
    public static final String EXTRA_FOCUS_WEBHOOK = "focus_webhook";
    static final String STATE_WEBHOOK_DRAFT_URL = "settings_webhook_draft_url";
    static final String STATE_ISSUE_REPORT_SHOWING = "settings_issue_report_showing";
    static final String STATE_ISSUE_REPORT_DRAFT = "settings_issue_report_draft";
    static final String STATE_ISSUE_REPORT_DIAGNOSTICS = "settings_issue_report_diagnostics";
    static final String STATE_PROFILE_EDIT_SHOWING = "settings_profile_edit_showing";
    static final String STATE_PROFILE_EDIT_ID = "settings_profile_edit_id";
    static final String STATE_PROFILE_EDIT_NAME = "settings_profile_edit_name";
    static final String STATE_PROFILE_EDIT_IP = "settings_profile_edit_ip";
    static final String STATE_PROFILE_EDIT_HOSTNAME = "settings_profile_edit_hostname";
    static final String STATE_PROFILE_EDIT_TAILNET = "settings_profile_edit_tailnet";

    private ScrollView scrollView;
    private View webhookPanel;
    private View permissionPanel;
    private TextView languageSelectedText;
    private View languageSelector;

    private Switch hideNotificationToggle;
    private Switch keepDisplayOnToggle;
    private Switch adviceBannerToggle;
    private Switch usbNotificationToggle;
    private Switch usbProfileNotificationToggle;
    private TextView usbProfileSummary;
    private Button usbProfileAction;

    private TextView usbHandoverSelectedText;
    private View usbHandoverSelector;

    private Switch trustedNetworkToggle;
    private TextView trustedNetworkStatus;
    private Button trustedNetworkAdd;
    private Button trustedNetworkManage;
    private static final int TRUSTED_NETWORK_LOCATION_PERMISSION_REQUEST = 20;

    private Switch webhookToggle;
    private EditText webhookUrlInput;
    private TextView webhookError;
    private TextView webhookCleartextWarning;
    private Button webhookSave;
    private Button webhookClear;
    private boolean webhookDraftInitialized;
    private TextView versionNameText;
    private TextView versionCodeText;
    private TextView websiteLinkText;

    private AlertDialog activeIssueReportDialog;
    private EditText activeIssueReportPreview;
    private CheckBox activeIssueReportDiagnostics;

    private AlertDialog activeProfileEditDialog;
    private Integer activeProfileEditId;
    private EditText activeProfileEditName;
    private EditText activeProfileEditIp;
    private EditText activeProfileEditHostname;
    private EditText activeProfileEditTailnet;

    private AlertDialog activeManageNetworksDialog;
    private AlertDialog activeSwitchProfileDialog;
    private AlertDialog activeDeleteConfirmDialog;

    static final String WEBSITE_URL = "https://hohnepeople.de";

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(KeepADBLocaleHelper.wrapContext(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        versionNameText = findViewById(R.id.settings_version_name);
        versionCodeText = findViewById(R.id.settings_version_code);
        bindVersionInfo();

        websiteLinkText = findViewById(R.id.settings_website_link);
        websiteLinkText.setPaintFlags(websiteLinkText.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        websiteLinkText.setOnClickListener(v ->
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(WEBSITE_URL))));

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        scrollView = findViewById(R.id.settings_scroll_view);
        webhookPanel = findViewById(R.id.settings_webhook_panel);
        permissionPanel = findViewById(R.id.settings_permission_panel);

        languageSelectedText = findViewById(R.id.settings_language_selected_text);
        languageSelector = findViewById(R.id.settings_language_selector);
        languageSelector.setOnClickListener(v -> showLanguageSelectionDialog());

        hideNotificationToggle = findViewById(R.id.settings_hide_notification_toggle);
        hideNotificationToggle.setOnClickListener(v -> {
            boolean wantHidden = hideNotificationToggle.isChecked();
            KeepADBPreferences.setNotificationHidden(this, wantHidden);
            KeepADBNotification.refresh(this);
            Toast.makeText(this,
                    wantHidden ? R.string.settings_notification_hidden_toast : R.string.settings_notification_visible_toast,
                    Toast.LENGTH_SHORT).show();
            refresh();
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
        usbProfileAction.setOnClickListener(v -> showProfileDialog(
                KeepADBUsbProfile.getProfiles(this).isEmpty()
                        ? KeepADBUsbNotification.ACTION_CREATE : KeepADBUsbNotification.ACTION_SWITCH));

        usbHandoverSelectedText = findViewById(R.id.settings_usb_handover_selected_text);
        usbHandoverSelector = findViewById(R.id.settings_usb_handover_selector);
        usbHandoverSelector.setOnClickListener(v -> showUsbHandoverModeDialog());

        trustedNetworkToggle = findViewById(R.id.settings_trusted_network_toggle);
        trustedNetworkStatus = findViewById(R.id.settings_trusted_network_status);
        trustedNetworkAdd = findViewById(R.id.settings_trusted_network_add);
        trustedNetworkManage = findViewById(R.id.settings_trusted_network_manage);
        trustedNetworkToggle.setOnClickListener(v -> onTrustedNetworkToggleClicked());
        trustedNetworkAdd.setOnClickListener(v -> onAddOrRemoveCurrentNetworkClicked());
        trustedNetworkManage.setOnClickListener(v -> showTrustedNetworkManageDialog());

        findViewById(R.id.settings_diagnostics_export).setOnClickListener(v -> shareDiagnostics());
        findViewById(R.id.settings_issue_report).setOnClickListener(v -> showIssueReportDialog());

        webhookToggle = findViewById(R.id.settings_webhook_toggle);
        webhookUrlInput = findViewById(R.id.settings_webhook_url);
        webhookError = findViewById(R.id.settings_webhook_error);
        webhookCleartextWarning = findViewById(R.id.settings_webhook_cleartext_warning);
        webhookSave = findViewById(R.id.settings_webhook_save);
        webhookClear = findViewById(R.id.settings_webhook_clear);

        if (savedInstanceState != null) {
            if (savedInstanceState.containsKey(STATE_WEBHOOK_DRAFT_URL)) {
                webhookUrlInput.setText(resolveWebhookDraft(
                        KeepADBPreferences.getRegisterWebhookUrl(this),
                        savedInstanceState.getString(STATE_WEBHOOK_DRAFT_URL), true));
                webhookDraftInitialized = true;
            }
            if (savedInstanceState.getBoolean(STATE_ISSUE_REPORT_SHOWING, false)) {
                String draftBody = savedInstanceState.getString(STATE_ISSUE_REPORT_DRAFT);
                boolean includeDiagnostics = savedInstanceState.getBoolean(
                        STATE_ISSUE_REPORT_DIAGNOSTICS, false);
                showIssueReportDialog(draftBody, includeDiagnostics);
            }
            if (savedInstanceState.getBoolean(STATE_PROFILE_EDIT_SHOWING, false)) {
                int profileId = savedInstanceState.getInt(STATE_PROFILE_EDIT_ID, -1);
                KeepADBUsbProfile.Profile editingProfile = null;
                if (profileId != -1) {
                    for (KeepADBUsbProfile.Profile p : KeepADBUsbProfile.getProfiles(this)) {
                        if (p.id == profileId) {
                            editingProfile = p;
                            break;
                        }
                    }
                }
                String draftName = savedInstanceState.getString(STATE_PROFILE_EDIT_NAME);
                String draftIp = savedInstanceState.getString(STATE_PROFILE_EDIT_IP);
                String draftHostname = savedInstanceState.getString(STATE_PROFILE_EDIT_HOSTNAME);
                String draftTailnet = savedInstanceState.getString(STATE_PROFILE_EDIT_TAILNET);
                showProfileEditDialog(editingProfile, draftName, draftIp, draftHostname, draftTailnet);
            }
        }

        webhookToggle.setOnClickListener(v -> {
            boolean wantEnabled = webhookToggle.isChecked();
            if (wantEnabled) {
                String inputUrl = webhookUrlInput.getText() != null
                        ? webhookUrlInput.getText().toString().trim() : "";
                if (!KeepADBPreferences.isValidWebhookUrl(inputUrl)) {
                    webhookToggle.setChecked(false);
                    webhookError.setText(R.string.settings_webhook_error_missing_url);
                    webhookError.setVisibility(View.VISIBLE);
                    webhookUrlInput.requestFocus();
                    return;
                }
                webhookError.setVisibility(View.GONE);
                KeepADBPreferences.setRegisterWebhookUrl(this, inputUrl);
                KeepADBPreferences.setRegisterWebhookEnabled(this, true);
                KeepADBNotification.refresh(this);
                Toast.makeText(this, R.string.settings_webhook_enabled_toast, Toast.LENGTH_SHORT).show();
            } else {
                webhookError.setVisibility(View.GONE);
                KeepADBRegisterClient.unregisterAndDisableAsync(this);
                KeepADBPreferences.setRegisterWebhookEnabled(this, false);
                Toast.makeText(this, R.string.settings_webhook_disabled_toast, Toast.LENGTH_SHORT).show();
            }
            refresh();
        });

        webhookSave.setOnClickListener(v -> {
            String inputUrl = webhookUrlInput.getText() != null
                    ? webhookUrlInput.getText().toString().trim() : "";
            if (inputUrl.isEmpty()) {
                if (KeepADBPreferences.isRegisterWebhookEnabled(this)) {
                    webhookError.setText(R.string.settings_webhook_error_missing_url);
                    webhookError.setVisibility(View.VISIBLE);
                    return;
                }
                KeepADBPreferences.setRegisterWebhookUrl(this, null);
                webhookError.setVisibility(View.GONE);
                Toast.makeText(this, R.string.settings_webhook_saved_toast, Toast.LENGTH_SHORT).show();
                refresh();
                return;
            }
            if (!KeepADBPreferences.isValidWebhookUrl(inputUrl)) {
                webhookError.setText(R.string.settings_webhook_error_invalid_url);
                webhookError.setVisibility(View.VISIBLE);
                return;
            }
            webhookError.setVisibility(View.GONE);
            KeepADBPreferences.setRegisterWebhookUrl(this, inputUrl);
            if (KeepADBPreferences.isRegisterWebhookEnabled(this)) {
                KeepADBNotification.refresh(this);
            }
            Toast.makeText(this, R.string.settings_webhook_saved_toast, Toast.LENGTH_SHORT).show();
            refresh();
        });

        webhookClear.setOnClickListener(v -> {
            webhookUrlInput.setText("");
            webhookError.setVisibility(View.GONE);
            if (KeepADBPreferences.isRegisterWebhookEnabled(this)) {
                KeepADBRegisterClient.unregisterAndDisableAsync(this);
                KeepADBPreferences.setRegisterWebhookEnabled(this, false);
            }
            KeepADBPreferences.setRegisterWebhookUrl(this, null);
            Toast.makeText(this, R.string.settings_webhook_cleared_toast, Toast.LENGTH_SHORT).show();
            refresh();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!webhookDraftInitialized) {
            webhookUrlInput.setText(resolveWebhookDraft(
                    KeepADBPreferences.getRegisterWebhookUrl(this), null, false));
            webhookDraftInitialized = true;
        }
        refresh();

        if (getIntent().hasExtra(KeepADBUsbNotification.EXTRA_PROFILE_ACTION)) {
            showProfileDialog(getIntent().getStringExtra(KeepADBUsbNotification.EXTRA_PROFILE_ACTION));
            getIntent().removeExtra(KeepADBUsbNotification.EXTRA_PROFILE_ACTION);
        }

        if (getIntent().hasExtra(EXTRA_FOCUS_WEBHOOK)) {
            focusWebhookPanel();
            getIntent().removeExtra(EXTRA_FOCUS_WEBHOOK);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_WEBHOOK_DRAFT_URL,
                webhookUrlInput.getText() == null ? "" : webhookUrlInput.getText().toString());
        if (activeIssueReportDialog != null && activeIssueReportDialog.isShowing()) {
            outState.putBoolean(STATE_ISSUE_REPORT_SHOWING, true);
            outState.putString(STATE_ISSUE_REPORT_DRAFT,
                    activeIssueReportPreview != null && activeIssueReportPreview.getText() != null
                            ? activeIssueReportPreview.getText().toString() : "");
            outState.putBoolean(STATE_ISSUE_REPORT_DIAGNOSTICS,
                    activeIssueReportDiagnostics != null && activeIssueReportDiagnostics.isChecked());
        }
        if (activeProfileEditDialog != null && activeProfileEditDialog.isShowing()) {
            outState.putBoolean(STATE_PROFILE_EDIT_SHOWING, true);
            outState.putInt(STATE_PROFILE_EDIT_ID, activeProfileEditId != null ? activeProfileEditId : -1);
            outState.putString(STATE_PROFILE_EDIT_NAME,
                    activeProfileEditName != null && activeProfileEditName.getText() != null
                            ? activeProfileEditName.getText().toString() : "");
            outState.putString(STATE_PROFILE_EDIT_IP,
                    activeProfileEditIp != null && activeProfileEditIp.getText() != null
                            ? activeProfileEditIp.getText().toString() : "");
            outState.putString(STATE_PROFILE_EDIT_HOSTNAME,
                    activeProfileEditHostname != null && activeProfileEditHostname.getText() != null
                            ? activeProfileEditHostname.getText().toString() : "");
            outState.putString(STATE_PROFILE_EDIT_TAILNET,
                    activeProfileEditTailnet != null && activeProfileEditTailnet.getText() != null
                            ? activeProfileEditTailnet.getText().toString() : "");
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

        if (activeProfileEditDialog != null) {
            if (activeProfileEditDialog.isShowing()) {
                activeProfileEditDialog.dismiss();
            }
            activeProfileEditDialog = null;
        }
        activeProfileEditId = null;
        activeProfileEditName = null;
        activeProfileEditIp = null;
        activeProfileEditHostname = null;
        activeProfileEditTailnet = null;

        if (activeManageNetworksDialog != null) {
            if (activeManageNetworksDialog.isShowing()) {
                activeManageNetworksDialog.dismiss();
            }
            activeManageNetworksDialog = null;
        }

        if (activeSwitchProfileDialog != null) {
            if (activeSwitchProfileDialog.isShowing()) {
                activeSwitchProfileDialog.dismiss();
            }
            activeSwitchProfileDialog = null;
        }

        if (activeDeleteConfirmDialog != null) {
            if (activeDeleteConfirmDialog.isShowing()) {
                activeDeleteConfirmDialog.dismiss();
            }
            activeDeleteConfirmDialog = null;
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
        scrollView.post(() -> scrollView.smoothScrollTo(0, webhookPanel.getTop()));
        webhookUrlInput.requestFocus();
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
                    KeepADBNotification.refresh(this);
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

    private void onTrustedNetworkToggleClicked() {
        boolean wantAllowlist = trustedNetworkToggle.isChecked();
        if (!wantAllowlist) {
            KeepADBTrustedNetwork.setMode(this, KeepADBTrustedNetwork.MODE_ALL_WIFI);
            refresh();
            return;
        }
        // Revert the switch until permission is confirmed; refresh() below re-derives the
        // actual checked state from the persisted mode either way.
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
                        // offers the user a precise/approximate choice in one dialog. Only a
                        // FINE grant is actually usable here (see onRequestPermissionsResult).
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
            // grantResults can be shorter than permissions (even empty) if the request was
            // interrupted (e.g. the app was backgrounded while the system dialog was up), so
            // re-query the actual permission state instead of indexing into it, matching
            // MainActivity's existing onRequestPermissionsResult pattern.
            boolean granted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                KeepADBTrustedNetwork.setMode(this, KeepADBTrustedNetwork.MODE_ALLOWLIST);
            } else {
                Toast.makeText(this, R.string.settings_trusted_network_permission_denied_toast,
                        Toast.LENGTH_LONG).show();
            }
            refresh();
        }
    }

    private void onAddOrRemoveCurrentNetworkClicked() {
        if (KeepADBTrustedNetwork.findEntryForCurrentNetwork(this) != null) {
            KeepADBTrustedNetwork.Entry removed = KeepADBTrustedNetwork.removeCurrentNetwork(this);
            if (removed == null) {
                Toast.makeText(this, R.string.settings_trusted_network_add_failed_toast, Toast.LENGTH_LONG).show();
                return;
            }
            Toast.makeText(this, getString(R.string.settings_trusted_network_removed_toast, removed.label),
                    Toast.LENGTH_SHORT).show();
            refresh();
            return;
        }
        KeepADBTrustedNetwork.Entry entry = KeepADBTrustedNetwork.addCurrentNetwork(this, null);
        if (entry == null) {
            Toast.makeText(this, R.string.settings_trusted_network_add_failed_toast, Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, getString(R.string.settings_trusted_network_added_toast, entry.label),
                Toast.LENGTH_SHORT).show();
        offerAdditionalMeshBssids();
        refresh();
    }

    /**
     * After adding the current network, offers to also add any other BSSIDs the observation
     * history (#266) has seen broadcasting the same SSID -- covers Wi-Fi mesh setups (several
     * access points, one SSID, different BSSIDs) without ever trusting by SSID: declining still
     * keeps only the just-added BSSID trusted, and accepting adds each additional BSSID through
     * the same {@link KeepADBTrustedNetwork} entry point as a normal manual add.
     */
    private void offerAdditionalMeshBssids() {
        KeepADBNetworkIdentity identity = KeepADBNetworkIdentity.current(this);
        if (!identity.isKnown()) return;
        String ssid = identity.displaySsid();
        if (ssid == null || ssid.isEmpty()) return;

        List<String> alreadyListed = new java.util.ArrayList<>();
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
                        KeepADBTrustedNetwork.addBssid(this, bssid, ssid);
                    }
                    Toast.makeText(this,
                            getString(R.string.settings_trusted_network_mesh_added_toast, additional.size()),
                            Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showTrustedNetworkManageDialog() {
        List<KeepADBTrustedNetwork.Entry> entries = KeepADBTrustedNetwork.getEntries(this);
        if (entries.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.settings_trusted_network_manage_title)
                    .setMessage(R.string.settings_trusted_network_empty_message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        android.widget.LinearLayout rows = new android.widget.LinearLayout(this);
        rows.setOrientation(android.widget.LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        rows.setPadding(padding, 0, padding, 0);
        final AlertDialog[] dialogHolder = new AlertDialog[1];
        for (KeepADBTrustedNetwork.Entry entry : entries) {
            android.widget.LinearLayout row = new android.widget.LinearLayout(this);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            android.widget.LinearLayout labelColumn = new android.widget.LinearLayout(this);
            labelColumn.setOrientation(android.widget.LinearLayout.VERTICAL);
            labelColumn.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            TextView label = new TextView(this);
            label.setText(entry.label);
            label.setTextColor(getColor(R.color.night_text));
            TextView bssid = new TextView(this);
            bssid.setText(entry.bssid);
            bssid.setTextColor(getColor(R.color.night_muted));
            bssid.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
            labelColumn.addView(label);
            labelColumn.addView(bssid);
            Button delete = new Button(this);
            delete.setText(R.string.settings_trusted_network_delete_button);
            delete.setContentDescription(getString(
                    R.string.settings_trusted_network_delete_accessibility, entry.label));
            delete.setOnClickListener(v -> {
                KeepADBTrustedNetwork.remove(this, entry.id);
                dialogHolder[0].dismiss();
                refresh();
            });
            row.addView(labelColumn);
            row.addView(delete);
            rows.addView(row);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(rows);
        dialogHolder[0] = new AlertDialog.Builder(this)
                .setTitle(R.string.settings_trusted_network_manage_title)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        activeManageNetworksDialog = dialogHolder[0];
        dialogHolder[0].setOnDismissListener(d -> {
            if (activeManageNetworksDialog == d) {
                activeManageNetworksDialog = null;
            }
        });
        dialogHolder[0].show();
    }

    private void showProfileDialog(String action) {
        List<KeepADBUsbProfile.Profile> profiles = KeepADBUsbProfile.getProfiles(this);
        if (KeepADBUsbNotification.ACTION_SWITCH.equals(action) && !profiles.isEmpty()) {
            KeepADBUsbProfile.Profile current = KeepADBUsbProfile.getSelected(this);
            android.widget.LinearLayout options = new android.widget.LinearLayout(this);
            options.setOrientation(android.widget.LinearLayout.VERTICAL);
            int padding = (int) (20 * getResources().getDisplayMetrics().density);
            options.setPadding(padding, 0, padding, 0);
            final AlertDialog[] dialogHolder = new AlertDialog[1];
            for (int i = 0; i < profiles.size(); i++) {
                KeepADBUsbProfile.Profile profile = profiles.get(i);
                android.widget.LinearLayout row = new android.widget.LinearLayout(this);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                android.widget.RadioButton select = new android.widget.RadioButton(this);
                select.setText(profile.summary());
                select.setContentDescription(profile.summary());
                select.setChecked(current != null && current.id == profile.id);
                select.setMinHeight((int) (48 * getResources().getDisplayMetrics().density));
                select.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1));
                select.setOnClickListener(v -> {
                    KeepADBUsbProfile.select(this, profile.id);
                    dialogHolder[0].dismiss();
                    KeepADBUsbReceiver.refresh(this);
                    refresh();
                });
                android.widget.Button edit = new android.widget.Button(this);
                edit.setText(R.string.usb_profile_edit_button);
                edit.setContentDescription(getString(
                        R.string.usb_profile_edit_action_accessibility, profile.name));
                edit.setOnClickListener(v -> {
                    dialogHolder[0].dismiss();
                    showProfileEditDialog(profile);
                });
                android.widget.Button delete = new android.widget.Button(this);
                delete.setText(R.string.usb_profile_delete_button);
                delete.setContentDescription(getString(
                        R.string.usb_profile_delete_action_accessibility, profile.name));
                delete.setOnClickListener(v -> {
                    dialogHolder[0].dismiss();
                    showProfileDeleteDialog(profile);
                });
                row.addView(select);
                row.addView(edit);
                row.addView(delete);
                options.addView(row);
            }
            ScrollView scroll = new ScrollView(this);
            scroll.addView(options);
            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle(R.string.usb_profile_switch_title)
                    .setView(scroll)
                    .setPositiveButton(R.string.usb_profile_new_button, (buttonDialog, which) -> showProfileDialog(
                            KeepADBUsbNotification.ACTION_CREATE))
                    .setNegativeButton(android.R.string.cancel, null)
                    .create();
            dialogHolder[0] = dialog;
            activeSwitchProfileDialog = dialog;
            dialog.setOnDismissListener(d -> {
                if (activeSwitchProfileDialog == d) {
                    activeSwitchProfileDialog = null;
                }
            });
            dialog.show();
            return;
        }

        showProfileEditDialog(null);
    }

    private void showProfileEditDialog(KeepADBUsbProfile.Profile profile) {
        showProfileEditDialog(profile, null, null, null, null);
    }

    private void showProfileEditDialog(KeepADBUsbProfile.Profile profile,
            String draftName, String draftIp, String draftHostname, String draftTailnet) {
        ScrollView scroll = new ScrollView(this);
        android.widget.LinearLayout fields = new android.widget.LinearLayout(this);
        fields.setOrientation(android.widget.LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        fields.setPadding(padding, 0, padding, 0);
        EditText name = profileField(R.string.usb_profile_name_hint);
        EditText ip = profileField(R.string.usb_profile_ip_hint);
        EditText hostname = profileField(R.string.usb_profile_hostname_hint);
        EditText tailnet = profileField(R.string.usb_profile_tailnet_hint);
        if (profile != null) {
            name.setText(profile.name);
            ip.setText(profile.ipAddress);
            hostname.setText(profile.hostname);
            tailnet.setText(profile.tailnetHostname);
        }
        if (draftName != null) {
            name.setText(draftName);
        }
        if (draftIp != null) {
            ip.setText(draftIp);
        }
        if (draftHostname != null) {
            hostname.setText(draftHostname);
        }
        if (draftTailnet != null) {
            tailnet.setText(draftTailnet);
        }
        fields.addView(name);
        fields.addView(ip);
        fields.addView(hostname);
        fields.addView(tailnet);
        scroll.addView(fields);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(profile == null ? R.string.usb_profile_create_title
                        : R.string.usb_profile_edit_title)
                .setView(scroll)
                .setPositiveButton(R.string.usb_profile_save_button, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        activeProfileEditDialog = dialog;
        activeProfileEditId = profile != null ? profile.id : null;
        activeProfileEditName = name;
        activeProfileEditIp = ip;
        activeProfileEditHostname = hostname;
        activeProfileEditTailnet = tailnet;
        dialog.setOnDismissListener(d -> {
            activeProfileEditDialog = null;
            activeProfileEditId = null;
            activeProfileEditName = null;
            activeProfileEditIp = null;
            activeProfileEditHostname = null;
            activeProfileEditTailnet = null;
        });
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (name.getText().toString().trim().isEmpty()) {
                name.setError(getString(R.string.usb_profile_name_required));
                return;
            }
            if (profile == null) {
                KeepADBUsbProfile.add(this, name.getText().toString(), ip.getText().toString(),
                        hostname.getText().toString(), tailnet.getText().toString());
            } else if (KeepADBUsbProfile.update(this, profile.id, name.getText().toString(),
                    ip.getText().toString(), hostname.getText().toString(),
                    tailnet.getText().toString()) == null) {
                name.setError(getString(R.string.usb_profile_name_required));
                return;
            }
            dialog.dismiss();
            KeepADBUsbReceiver.refresh(this);
            refresh();
        }));
        dialog.show();
    }

    private void showProfileDeleteDialog(KeepADBUsbProfile.Profile profile) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.usb_profile_delete_title)
                .setMessage(getString(R.string.usb_profile_delete_message, profile.name))
                .setPositiveButton(R.string.usb_profile_delete_button, (d, which) -> {
                    if (KeepADBUsbProfile.delete(this, profile.id)) {
                        KeepADBUsbReceiver.refresh(this);
                        refresh();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        activeDeleteConfirmDialog = dialog;
        dialog.setOnDismissListener(d -> {
            if (activeDeleteConfirmDialog == d) {
                activeDeleteConfirmDialog = null;
            }
        });
        dialog.show();
    }

    private EditText profileField(int hint) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setSingleLine(true);
        return field;
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

    AlertDialog getActiveIssueReportDialog() {
        return activeIssueReportDialog;
    }

    AlertDialog getActiveProfileEditDialog() {
        return activeProfileEditDialog;
    }

    AlertDialog getActiveManageNetworksDialog() {
        return activeManageNetworksDialog;
    }

    AlertDialog getActiveSwitchProfileDialog() {
        return activeSwitchProfileDialog;
    }

    private void refresh() {
        boolean hasPermission = checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
        permissionPanel.setVisibility(hasPermission ? View.GONE : View.VISIBLE);

        String currentLanguageTag = KeepADBLocaleHelper.getSelectedLanguageTag(this);
        String displayName = KeepADBLocaleHelper.getLanguageDisplayName(this, currentLanguageTag);
        languageSelectedText.setText(displayName);
        languageSelector.setContentDescription(
                getString(R.string.settings_language_accessibility, displayName));

        boolean webhookEnabled = KeepADBPreferences.isRegisterWebhookEnabled(this);
        webhookToggle.setChecked(webhookEnabled);

        boolean notificationHidden = KeepADBPreferences.isNotificationHidden(this);
        hideNotificationToggle.setChecked(notificationHidden);

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

        // Piggyback the mesh-BSSID observation history (#266) on this already-happening
        // identity read instead of adding a new background poll/service for it.
        KeepADBNetworkIdentity currentIdentity = KeepADBNetworkIdentity.current(this);
        if (currentIdentity.isKnown()) {
            KeepADBBssidHistory.recordObservation(this, currentIdentity.displaySsid(), currentIdentity.bssid);
        }

        trustedNetworkToggle.setChecked(KeepADBTrustedNetwork.isAllowlistMode(this));
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
        trustedNetworkAdd.setText(KeepADBTrustedNetwork.findEntryForCurrentNetwork(this) != null
                ? R.string.settings_trusted_network_remove_button
                : R.string.settings_trusted_network_add_button);

        String savedWebhookUrl = KeepADBPreferences.getRegisterWebhookUrl(this);
        boolean showCleartextWarning = savedWebhookUrl != null
                && savedWebhookUrl.toLowerCase(java.util.Locale.ROOT).startsWith("http://");
        webhookCleartextWarning.setVisibility(showCleartextWarning ? View.VISIBLE : View.GONE);
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
    }
}
