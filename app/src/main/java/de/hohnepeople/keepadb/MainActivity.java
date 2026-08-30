package de.hohnepeople.keepadb;

import android.app.Activity;
import android.content.Intent;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int NOTIFICATION_PERMISSION_REQUEST = 10;
    private static final String NOTIFICATION_PERMISSION_REQUESTED =
            "notification_permission_requested";
    private Switch toggle;
    private Switch keepAliveToggle;
    private TextView status;
    private TextView endpoint;
    private TextView webhookStatus;
    private View webhookStatusPanel;
    private View setupPanel;
    private View notificationPermissionPanel;
    private View batteryOptimizationPanel;
    private boolean notificationPermissionRequestPending;
    private long endpointListenerGeneration;
    private boolean endpointSurfaceActive;

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(KeepADBLocaleHelper.wrapContext(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        toggle = findViewById(R.id.toggle);
        keepAliveToggle = findViewById(R.id.keep_alive_toggle);
        status = findViewById(R.id.status);
        endpoint = findViewById(R.id.endpoint);
        webhookStatus = findViewById(R.id.webhook_status);
        webhookStatusPanel = findViewById(R.id.webhook_status_panel);
        setupPanel = findViewById(R.id.setup_panel);
        notificationPermissionPanel = findViewById(R.id.notification_permission_panel);
        batteryOptimizationPanel = findViewById(R.id.battery_optimization_panel);
        findViewById(R.id.setup_refresh).setOnClickListener(v -> refreshUiAndComponents());
        findViewById(R.id.btn_open_settings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.btn_open_notification_settings).setOnClickListener(v ->
                openNotificationSettings());
        findViewById(R.id.btn_open_battery_settings).setOnClickListener(v ->
                KeepADBBatteryOptimization.openSettings(this));

        if (shouldRequestNotificationPermission()) {
            getPreferences(MODE_PRIVATE).edit()
                    .putBoolean(NOTIFICATION_PERMISSION_REQUESTED, true).apply();
            notificationPermissionRequestPending = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    NOTIFICATION_PERMISSION_REQUEST);
        }

        // OnClick fires only for user interaction, unlike OnCheckedChanged during refresh().
        toggle.setOnClickListener(v -> {
            boolean want = toggle.isChecked();
            KeepADBDiagnostics.event(this, "user_action", "app", want ? "enable" : "disable", "toggle");
            if (!KeepADB.setEnabled(this, want, "app")) {
                toggle.setChecked(!want);
                showPermissionErrorToast();
            }
            KeepADBService.sync(this);
            refreshUiAndComponents();
        });

        keepAliveToggle.setOnClickListener(v -> {
            boolean wantKeepAlive = keepAliveToggle.isChecked();
            KeepADBDiagnostics.event(this, "user_action", "app", wantKeepAlive ? "enable" : "disable", "keep_alive_toggle");
            KeepADBPreferences.setKeepAliveEnabled(this, wantKeepAlive);
            if (wantKeepAlive && KeepADBService.isWifiConnected(this) && !KeepADB.isEnabled(this)) {
                if (!KeepADB.setEnabled(this, true, "app")) {
                    showPermissionErrorToast();
                }
            }
            KeepADBService.sync(this);
            KeepADBWidget.refreshAll(this);
            KeepADBNotification.refresh(this);
            refresh();
        });
    }

    private android.database.ContentObserver adbContentObserver;

    @Override
    protected void onResume() {
        super.onResume();
        if (!KeepADBLocaleHelper.isSelectedLanguageApplied(this)) {
            recreate();
            return;
        }
        if (adbContentObserver == null) {
            adbContentObserver = new android.database.ContentObserver(new android.os.Handler(android.os.Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    super.onChange(selfChange);
                    refresh();
                    KeepADBNotification.refresh(MainActivity.this);
                }
            };
            try {
                getContentResolver().registerContentObserver(
                        android.provider.Settings.Global.getUriFor(KeepADB.KEY),
                        false,
                        adbContentObserver);
            } catch (Exception ignored) {
            }
        }
        final long listenerGeneration = ++endpointListenerGeneration;
        endpointSurfaceActive = true;
        KeepADBNotification.setEndpointListener(new KeepADBNotification.EndpointListener() {
            @Override
            public void onEndpoint(String host, int port) {
                postEndpointAvailable(listenerGeneration, host, port);
            }

            @Override
            public void onUnavailable() {
                postEndpointUnavailable(listenerGeneration);
            }
        });
        // Keep-Alive is a persisted preference, but the foreground service backing it can die
        // (OEM battery optimization, process kill) without the preference changing. Only the
        // toggle's own click listener called sync() before; without it here, reopening the app
        // after such a kill never resurrects the service. See #106.
        KeepADBService.sync(this);
        refresh();
        KeepADBNotification.refresh(this);
        KeepADBUsbReceiver.refresh(this);
        // The webhook POST/DELETE round-trip runs on a background thread well after refresh()
        // above returns, so the displayed status would otherwise stay stale until the next
        // unrelated refresh() call (#118).
        KeepADBRegisterClient.setRegisterStateListener(this::refreshWebhookStatus);
    }

    @Override
    protected void onPause() {
        endpointSurfaceActive = false;
        endpointListenerGeneration++;
        KeepADBNotification.clearEndpointListener();
        KeepADBRegisterClient.clearRegisterStateListener();
        if (adbContentObserver != null) {
            try {
                getContentResolver().unregisterContentObserver(adbContentObserver);
            } catch (Exception ignored) {
            }
            adbContentObserver = null;
        }
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST) {
            notificationPermissionRequestPending = false;
            refresh();
            KeepADBNotification.refresh(this);
        }
    }

    private void refresh() {
        KeepADB.State appState = KeepADB.getState(this);
        boolean configured = (appState != KeepADB.State.PERMISSION_MISSING);
        boolean on = (appState == KeepADB.State.ENABLED_CONNECTED || appState == KeepADB.State.ENABLED_DISCONNECTED);
        boolean notificationsDenied = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && !notificationPermissionRequestPending
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED;
        setupPanel.setVisibility(configured ? View.GONE : View.VISIBLE);
        notificationPermissionPanel.setVisibility(notificationsDenied ? View.VISIBLE : View.GONE);
        batteryOptimizationPanel.setVisibility(KeepADBBatteryOptimization.isExempt(this)
                ? View.GONE : View.VISIBLE);
        toggle.setEnabled(configured);
        toggle.setChecked(on);
        if (!configured) {
            status.setText(getString(R.string.status_permission_missing));
        } else if (appState == KeepADB.State.OFF) {
            status.setText(getString(R.string.status_off));
        } else if (appState == KeepADB.State.ENABLED_DISCONNECTED) {
            status.setText(getString(R.string.status_enabled_disconnected));
        } else {
            status.setText(getString(R.string.status_on));
        }
        keepAliveToggle.setEnabled(configured);
        keepAliveToggle.setChecked(KeepADBPreferences.isKeepAliveEnabled(this));
        refreshWebhookStatus();
    }

    private boolean shouldRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        return !getPreferences(MODE_PRIVATE).getBoolean(NOTIFICATION_PERMISSION_REQUESTED, false)
                && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS);
    }

    private void refreshWebhookStatus() {
        String url = KeepADBPreferences.getRegisterWebhookUrl(this);
        boolean enabled = KeepADBPreferences.isRegisterWebhookEnabled(this);
        if (!enabled || url == null || url.trim().isEmpty()) {
            webhookStatusPanel.setVisibility(View.GONE);
            return;
        }
        long lastReportedAt = KeepADBPreferences.getWebhookLastReportedAt(this);
        String lastReported;
        if (lastReportedAt <= 0) {
            lastReported = getString(R.string.webhook_status_never);
        } else {
            java.util.Date date = new java.util.Date(lastReportedAt);
            java.text.DateFormat dateTimeFormat = java.text.DateFormat.getDateTimeInstance(
                    java.text.DateFormat.MEDIUM, java.text.DateFormat.MEDIUM,
                    getResources().getConfiguration().getLocales().get(0));
            lastReported = dateTimeFormat.format(date);
        }
        String lastEndpoint = KeepADBPreferences.getWebhookLastReportedEndpoint(this);
        if (lastEndpoint == null || lastEndpoint.trim().isEmpty()) {
            // Distinguish "never reported anything yet" from "was reported, then successfully
            // deregistered" -- both leave no current endpoint, but reusing the same "none yet"
            // text for a just-completed deregistration reads as if one were still pending.
            lastEndpoint = lastReportedAt > 0
                    ? getString(R.string.webhook_status_deregistered)
                    : getString(R.string.webhook_status_no_endpoint);
        }
        webhookStatus.setText(getString(R.string.webhook_status_hint, url, lastEndpoint, lastReported));
        webhookStatusPanel.setVisibility(View.VISIBLE);
    }

    private void postEndpointAvailable(long listenerGeneration, String host, int port) {
        runOnUiThread(() -> {
            if (!isEndpointSurfaceActive(listenerGeneration)) return;
            endpoint.setText(getString(R.string.endpoint_format, host, port));
            refresh();
        });
    }

    private void postEndpointUnavailable(long listenerGeneration) {
        runOnUiThread(() -> {
            if (!isEndpointSurfaceActive(listenerGeneration)) return;
            endpoint.setText(KeepADB.isEnabled(MainActivity.this)
                    ? getString(R.string.endpoint_searching) : getString(R.string.endpoint_unavailable));
            refresh();
        });
    }

    private boolean isEndpointSurfaceActive(long listenerGeneration) {
        return endpointSurfaceActive
                && listenerGeneration == endpointListenerGeneration
                && !isFinishing()
                && !isDestroyed();
    }

    private boolean hasSecureSettingsPermission() {
        return checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void refreshUiAndComponents() {
        refresh();
        KeepADBWidget.refreshAll(this);
        KeepADBNotification.refresh(this);
        KeepADBTileService.requestRefresh(this);
        KeepADBUsbReceiver.refresh(this);
    }

    private void showPermissionErrorToast() {
        Toast.makeText(this, getString(R.string.permission_error_toast, getPackageName()),
                Toast.LENGTH_LONG).show();
    }

    private void openNotificationSettings() {
        Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
        intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        startActivity(intent);
    }
}
