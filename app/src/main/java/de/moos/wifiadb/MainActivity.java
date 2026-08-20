package de.moos.wifiadb;

import android.app.Activity;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int NOTIFICATION_PERMISSION_REQUEST = 10;
    private Switch toggle;
    private Switch keepAliveToggle;
    private TextView status;
    private TextView endpoint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        toggle = findViewById(R.id.toggle);
        keepAliveToggle = findViewById(R.id.keep_alive_toggle);
        status = findViewById(R.id.status);
        endpoint = findViewById(R.id.endpoint);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    NOTIFICATION_PERMISSION_REQUEST);
        }

        // OnClick (nicht OnCheckedChanged): feuert nur bei Nutzer-Tipp, nicht bei refresh().
        toggle.setOnClickListener(v -> {
            boolean want = toggle.isChecked();
            if (!AdbWifi.setEnabled(this, want)) {
                toggle.setChecked(!want); // Berechtigung fehlt -> zurücksetzen
                showPermissionErrorToast();
            } else if (!want) {
                // Bei manuellem Ausschalten von WLAN-ADB auch Keep-Alive deaktivieren
                AdbWifiPreferences.setKeepAliveEnabled(this, false);
                AdbWifiService.stop(this);
            }
            refreshUiAndComponents();
        });

        keepAliveToggle.setOnClickListener(v -> {
            boolean wantKeepAlive = keepAliveToggle.isChecked();
            AdbWifiPreferences.setKeepAliveEnabled(this, wantKeepAlive);
            AdbWifiService.sync(this);
            if (wantKeepAlive && AdbWifiService.isWifiConnected(this) && !AdbWifi.isEnabled(this)) {
                if (!AdbWifi.setEnabled(this, true)) {
                    showPermissionErrorToast();
                }
            }
            refreshUiAndComponents();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        AdbWifiNotification.setEndpointListener(new AdbWifiNotification.EndpointListener() {
            @Override
            public void onEndpoint(String host, int port) {
                runOnUiThread(() -> endpoint.setText("Endpoint: " + host + ":" + port));
            }

            @Override
            public void onUnavailable() {
                runOnUiThread(() -> endpoint.setText(AdbWifi.isEnabled(MainActivity.this)
                        ? "Endpoint wird gesucht …" : "Endpoint nicht verfügbar"));
            }
        });
        refresh();
        AdbWifiNotification.refresh(this);
    }

    @Override
    protected void onPause() {
        AdbWifiNotification.clearEndpointListener();
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST) {
            AdbWifiNotification.refresh(this);
        }
    }

    private void refresh() {
        boolean on = AdbWifi.isEnabled(this);
        toggle.setChecked(on);
        status.setText(on ? "WLAN-ADB ist AN" : "WLAN-ADB ist AUS");
        keepAliveToggle.setChecked(AdbWifiPreferences.isKeepAliveEnabled(this));
    }

    private void refreshUiAndComponents() {
        refresh();
        AdbWifiWidget.refreshAll(this);
        AdbWifiNotification.refresh(this);
    }

    private void showPermissionErrorToast() {
        Toast.makeText(this,
                "Keine Berechtigung. Am PC einmalig ausführen:\n"
                        + "adb shell pm grant " + getPackageName()
                        + " android.permission.WRITE_SECURE_SETTINGS",
                Toast.LENGTH_LONG).show();
    }
}
