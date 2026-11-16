package de.hohnepeople.keepadb.selfpair;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * STAGE 1 TEST HARNESS (#962), debug build only: a bare screen to start the self-pairing run
 * and watch its log. Hard-coded English text on purpose; nothing here is meant to ship.
 *
 * <p>For runs driven over adb the launch intent may carry {@code pair_port},
 * {@code connect_port} and {@code code} extras, which bypass discovery and the notification.
 */
public final class SelfPairActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView log;
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            boolean granted = checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                    == PackageManager.PERMISSION_GRANTED;
            String text = "WRITE_SECURE_SETTINGS granted: " + granted + "\n\n"
                    + SelfPairService.logText();
            // Only touch the view on change, so the window can go idle for UI automation.
            if (!text.contentEquals(log.getText())) {
                log.setText(text);
            }
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView steps = new TextView(this);
        steps.setText("1. Tap Start (allow notifications).\n"
                + "2. Open Developer options > Wireless debugging, turn it on.\n"
                + "3. Tap \"Pair device with pairing code\" and leave the dialog open.\n"
                + "4. Enter the code in the KeepADB notification.");
        root.addView(steps);

        Button start = new Button(this);
        start.setText("Start");
        start.setOnClickListener(v -> start(new Intent()));
        root.addView(start);

        Button settings = new Button(this);
        settings.setText("Open developer options");
        settings.setOnClickListener(v -> startActivity(
                new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)));
        root.addView(settings);

        log = new TextView(this);
        log.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(log);
        root.addView(scroll);
        setContentView(root);

        if (getIntent().hasExtra(SelfPairService.EXTRA_PAIR_PORT)) {
            start(getIntent());
        }
    }

    private void start(Intent extras) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        Intent intent = SelfPairService.startIntent(this);
        if (extras.getExtras() != null) {
            intent.putExtras(extras.getExtras());
        }
        startForegroundService(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }
}
