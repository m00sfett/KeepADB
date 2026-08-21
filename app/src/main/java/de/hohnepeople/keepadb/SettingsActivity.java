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

/** Central settings screen for KeepADB options (Keep-Alive, Language, Webhook, etc.). */
public class SettingsActivity extends Activity {
    private View permissionPanel;
    private TextView languageSelectedText;

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
        findViewById(R.id.settings_language_selector).setOnClickListener(v -> showLanguageSelectionDialog());

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

    private void refresh() {
        boolean hasPermission = checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
        permissionPanel.setVisibility(hasPermission ? View.GONE : View.VISIBLE);

        String currentLanguageTag = KeepADBLocaleHelper.getSelectedLanguageTag(this);
        languageSelectedText.setText(KeepADBLocaleHelper.getLanguageDisplayName(this, currentLanguageTag));

        boolean webhookEnabled = KeepADBPreferences.isRegisterWebhookEnabled(this);
        webhookToggle.setChecked(webhookEnabled);
    }
}
