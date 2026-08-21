package de.hohnepeople.keepadb;

import android.app.Activity;
import android.content.Intent;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int NOTIFICATION_PERMISSION_REQUEST = 10;
    private Switch toggle;
    private TextView status;
    private TextView endpoint;
    private View setupPanel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        toggle = findViewById(R.id.toggle);
        status = findViewById(R.id.status);
        endpoint = findViewById(R.id.endpoint);
        setupPanel = findViewById(R.id.setup_panel);
        findViewById(R.id.setup_refresh).setOnClickListener(v -> refreshUiAndComponents());
        findViewById(R.id.btn_open_settings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    NOTIFICATION_PERMISSION_REQUEST);
        }

        // OnClick fires only for user interaction, unlike OnCheckedChanged during refresh().
        toggle.setOnClickListener(v -> {
            boolean want = toggle.isChecked();
            if (!KeepADB.setEnabled(this, want)) {
                toggle.setChecked(!want);
                showPermissionErrorToast();
            } else if (!want) {
                // A manual shutoff also disables keep-alive.
                KeepADBPreferences.setKeepAliveEnabled(this, false);
                KeepADBService.stop(this);
            }
            refreshUiAndComponents();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        KeepADBNotification.setEndpointListener(new KeepADBNotification.EndpointListener() {
            @Override
            public void onEndpoint(String host, int port) {
                runOnUiThread(() -> endpoint.setText(getString(R.string.endpoint_format, host, port)));
            }

            @Override
            public void onUnavailable() {
                runOnUiThread(() -> endpoint.setText(KeepADB.isEnabled(MainActivity.this)
                        ? getString(R.string.endpoint_searching) : getString(R.string.endpoint_unavailable)));
            }
        });
        refresh();
        KeepADBNotification.refresh(this);
    }

    @Override
    protected void onPause() {
        KeepADBNotification.clearEndpointListener();
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST) {
            KeepADBNotification.refresh(this);
        }
    }

    private void refresh() {
        boolean configured = hasSecureSettingsPermission();
        boolean on = KeepADB.isEnabled(this);
        setupPanel.setVisibility(configured ? View.GONE : View.VISIBLE);
        toggle.setEnabled(configured);
        toggle.setChecked(on);
        status.setText(on ? getString(R.string.status_on) : getString(R.string.status_off));
    }

    private boolean hasSecureSettingsPermission() {
        return checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void refreshUiAndComponents() {
        refresh();
        KeepADBWidget.refreshAll(this);
        KeepADBNotification.refresh(this);
    }

    private void showPermissionErrorToast() {
        Toast.makeText(this, getString(R.string.permission_error_toast, getPackageName()),
                Toast.LENGTH_LONG).show();
    }
}
