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
    private View permissionPanel;
    private TextView languageSelectedText;
    private View languageSelector;

    private Switch hideNotificationToggle;
    private Switch usbNotificationToggle;
    private Switch usbProfileNotificationToggle;
    private TextView usbProfileSummary;
    private Button usbProfileAction;

    private TextView usbHandoverSelectedText;
    private View usbHandoverSelector;

    private Switch webhookToggle;
    private EditText webhookUrlInput;
    private TextView webhookError;
    private Button webhookSave;
    private Button webhookClear;
    private TextView versionNameText;
    private TextView versionCodeText;

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

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
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

        findViewById(R.id.settings_diagnostics_export).setOnClickListener(v -> shareDiagnostics());
        findViewById(R.id.settings_issue_report).setOnClickListener(v -> showIssueReportDialog());

        webhookToggle = findViewById(R.id.settings_webhook_toggle);
        webhookUrlInput = findViewById(R.id.settings_webhook_url);
        webhookError = findViewById(R.id.settings_webhook_error);
        webhookSave = findViewById(R.id.settings_webhook_save);
        webhookClear = findViewById(R.id.settings_webhook_clear);

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
        String savedUrl = KeepADBPreferences.getRegisterWebhookUrl(this);
        if (savedUrl != null) {
            webhookUrlInput.setText(savedUrl);
        } else {
            webhookUrlInput.setText("");
        }
        refresh();

        if (getIntent().hasExtra(KeepADBUsbNotification.EXTRA_PROFILE_ACTION)) {
            showProfileDialog(getIntent().getStringExtra(KeepADBUsbNotification.EXTRA_PROFILE_ACTION));
            getIntent().removeExtra(KeepADBUsbNotification.EXTRA_PROFILE_ACTION);
        }
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
                edit.setOnClickListener(v -> {
                    dialogHolder[0].dismiss();
                    showProfileEditDialog(profile);
                });
                android.widget.Button delete = new android.widget.Button(this);
                delete.setText(R.string.usb_profile_delete_button);
                delete.setOnClickListener(v -> {
                    dialogHolder[0].dismiss();
                    showProfileDeleteDialog(profile);
                });
                row.addView(select);
                row.addView(edit);
                row.addView(delete);
                options.addView(row);
            }
            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle(R.string.usb_profile_switch_title)
                    .setView(options)
                    .setPositiveButton(R.string.usb_profile_new_button, (buttonDialog, which) -> showProfileDialog(
                            KeepADBUsbNotification.ACTION_CREATE))
                    .setNegativeButton(android.R.string.cancel, null)
                    .create();
            dialogHolder[0] = dialog;
            dialog.show();
            return;
        }

        showProfileEditDialog(null);
    }

    private void showProfileEditDialog(KeepADBUsbProfile.Profile profile) {

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
        fields.addView(name);
        fields.addView(ip);
        fields.addView(hostname);
        fields.addView(tailnet);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(profile == null ? R.string.usb_profile_create_title
                        : R.string.usb_profile_edit_title)
                .setView(fields)
                .setPositiveButton(R.string.usb_profile_save_button, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
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
        new AlertDialog.Builder(this)
                .setTitle(R.string.usb_profile_delete_title)
                .setMessage(getString(R.string.usb_profile_delete_message, profile.name))
                .setPositiveButton(R.string.usb_profile_delete_button, (dialog, which) -> {
                    if (KeepADBUsbProfile.delete(this, profile.id)) {
                        KeepADBUsbReceiver.refresh(this);
                        refresh();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
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
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, 0, padding, 0);

        TextView intro = new TextView(this);
        intro.setText(R.string.settings_issue_report_dialog_message);
        intro.setTextSize(13);
        intro.setMaxLines(3);
        intro.setEllipsize(android.text.TextUtils.TruncateAt.END);
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

    private void refresh() {
        boolean hasPermission = checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
        permissionPanel.setVisibility(hasPermission ? View.GONE : View.VISIBLE);

        String currentLanguageTag = KeepADBLocaleHelper.getSelectedLanguageTag(this);
        String displayName = KeepADBLocaleHelper.getLanguageDisplayName(this, currentLanguageTag);
        languageSelectedText.setText(displayName);
        languageSelector.setContentDescription(
                getString(R.string.settings_language_label) + ": " + displayName);

        boolean webhookEnabled = KeepADBPreferences.isRegisterWebhookEnabled(this);
        webhookToggle.setChecked(webhookEnabled);

        boolean notificationHidden = KeepADBPreferences.isNotificationHidden(this);
        hideNotificationToggle.setChecked(notificationHidden);

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
                getString(R.string.settings_usb_handover_label) + ": " + getString(handoverModeLabel));
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
