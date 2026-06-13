package de.moos.wifiadb;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private Switch toggle;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        toggle = findViewById(R.id.toggle);
        status = findViewById(R.id.status);

        // OnClick (nicht OnCheckedChanged): feuert nur bei Nutzer-Tipp, nicht bei refresh().
        toggle.setOnClickListener(v -> {
            boolean want = toggle.isChecked();
            if (!AdbWifi.setEnabled(this, want)) {
                toggle.setChecked(!want); // Berechtigung fehlt -> zurücksetzen
                Toast.makeText(this,
                        "Keine Berechtigung. Am PC einmalig ausführen:\n"
                                + "adb shell pm grant " + getPackageName()
                                + " android.permission.WRITE_SECURE_SETTINGS",
                        Toast.LENGTH_LONG).show();
            }
            refresh();
            AdbWifiWidget.refreshAll(this);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        boolean on = AdbWifi.isEnabled(this);
        toggle.setChecked(on);
        status.setText(on ? "WLAN-ADB ist AN" : "WLAN-ADB ist AUS");
    }
}
