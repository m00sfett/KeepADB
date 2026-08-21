package de.hohnepeople.keepadb;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.Switch;
import android.widget.Toast;

/** Central settings screen for KeepADB options (Keep-Alive, Webhook, etc.). */
public class SettingsActivity extends Activity {
    private Switch keepAliveToggle;
    private View permissionPanel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        keepAliveToggle = findViewById(R.id.settings_keep_alive_toggle);
        permissionPanel = findViewById(R.id.settings_permission_panel);

        keepAliveToggle.setOnClickListener(v -> {
            boolean wantKeepAlive = keepAliveToggle.isChecked();
            KeepADBPreferences.setKeepAliveEnabled(this, wantKeepAlive);
            KeepADBService.sync(this);
            if (wantKeepAlive && KeepADBService.isWifiConnected(this) && !KeepADB.isEnabled(this)) {
                if (!KeepADB.setEnabled(this, true)) {
                    showPermissionErrorToast();
                }
            }
            KeepADBWidget.refreshAll(this);
            KeepADBNotification.refresh(this);
            refresh();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        boolean hasPermission = checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
        permissionPanel.setVisibility(hasPermission ? View.GONE : View.VISIBLE);
        keepAliveToggle.setEnabled(hasPermission);
        keepAliveToggle.setChecked(KeepADBPreferences.isKeepAliveEnabled(this));
    }

    private void showPermissionErrorToast() {
        Toast.makeText(this, getString(R.string.permission_error_toast, getPackageName()),
                Toast.LENGTH_LONG).show();
    }
}
