package de.hohnepeople.keepadb;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
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
    private TextView usbProfileSummary;
    private Button usbProfileAction;

    private Switch webhookToggle;
    private EditText webhookUrlInput;
    private TextView webhookError;
    private Button webhookSave;
    private Button webhookClear;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(KeepADBLocaleHelper.wrapContext(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

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
        usbProfileSummary = findViewById(R.id.settings_usb_profile_summary);
        usbProfileAction = findViewById(R.id.settings_usb_profile_action);
        usbNotificationToggle.setOnClickListener(v -> {
            KeepADBUsbProfile.setNotificationEnabled(this, usbNotificationToggle.isChecked());
            KeepADBUsbReceiver.refresh(this);
            refresh();
        });
        usbProfileAction.setOnClickListener(v -> showProfileDialog(
                KeepADBUsbProfile.getProfiles(this).isEmpty()
                        ? KeepADBUsbNotification.ACTION_CREATE : KeepADBUsbNotification.ACTION_SWITCH));

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

    private void showProfileDialog(String action) {
        List<KeepADBUsbProfile.Profile> profiles = KeepADBUsbProfile.getProfiles(this);
        if (KeepADBUsbNotification.ACTION_SWITCH.equals(action) && !profiles.isEmpty()) {
            String[] labels = new String[profiles.size()];
            int selected = 0;
            KeepADBUsbProfile.Profile current = KeepADBUsbProfile.getSelected(this);
            for (int i = 0; i < profiles.size(); i++) {
                labels[i] = profiles.get(i).summary();
                if (current != null && current.id == profiles.get(i).id) selected = i;
            }
            new AlertDialog.Builder(this)
                    .setTitle(R.string.usb_profile_switch_title)
                    .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                        KeepADBUsbProfile.select(this, profiles.get(which).id);
                        dialog.dismiss();
                        KeepADBUsbReceiver.refresh(this);
                        refresh();
                    })
                    .setPositiveButton(R.string.usb_profile_new_button, (dialog, which) -> showProfileDialog(
                            KeepADBUsbNotification.ACTION_CREATE))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }

        android.widget.LinearLayout fields = new android.widget.LinearLayout(this);
        fields.setOrientation(android.widget.LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        fields.setPadding(padding, 0, padding, 0);
        EditText name = profileField(R.string.usb_profile_name_hint);
        EditText ip = profileField(R.string.usb_profile_ip_hint);
        EditText hostname = profileField(R.string.usb_profile_hostname_hint);
        EditText tailnet = profileField(R.string.usb_profile_tailnet_hint);
        fields.addView(name);
        fields.addView(ip);
        fields.addView(hostname);
        fields.addView(tailnet);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.usb_profile_create_title)
                .setView(fields)
                .setPositiveButton(R.string.usb_profile_save_button, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (name.getText().toString().trim().isEmpty()) {
                name.setError(getString(R.string.usb_profile_name_required));
                return;
            }
            KeepADBUsbProfile.add(this, name.getText().toString(), ip.getText().toString(),
                    hostname.getText().toString(), tailnet.getText().toString());
            dialog.dismiss();
            KeepADBUsbReceiver.refresh(this);
            refresh();
        }));
        dialog.show();
    }

    private EditText profileField(int hint) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setSingleLine(true);
        return field;
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
        KeepADBUsbProfile.Profile selectedProfile = KeepADBUsbProfile.getSelected(this);
        usbProfileSummary.setText(selectedProfile == null
                ? getString(R.string.usb_profile_none)
                : getString(R.string.usb_profile_selected, selectedProfile.summary()));
        usbProfileAction.setText(KeepADBUsbProfile.getProfiles(this).isEmpty()
                ? R.string.usb_profile_create_button : R.string.usb_profile_switch_button);
    }
}
