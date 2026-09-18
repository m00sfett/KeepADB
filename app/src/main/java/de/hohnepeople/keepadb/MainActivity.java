package de.hohnepeople.keepadb;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int NOTIFICATION_PERMISSION_REQUEST = 10;
    private static final String NOTIFICATION_PERMISSION_REQUESTED =
            "notification_permission_requested";
    // #459: separate request code from SettingsActivity's own
    // TRUSTED_NETWORK_LOCATION_PERMISSION_REQUEST (20) -- both activities request the same
    // permission independently, from their own onRequestPermissionsResult.
    private static final int LOCATION_PERMISSION_REQUEST = 30;
    private static final String LOCATION_PERMISSION_REQUESTED = "location_permission_requested";
    // #484: this activity's own request code for the trust-restriction toggle's location
    // permission flow, moved here from SettingsActivity (which used its own code 20) -- kept
    // distinct from LOCATION_PERMISSION_REQUEST above, which is a different onboarding flow.
    private static final int TRUSTED_NETWORK_LOCATION_PERMISSION_REQUEST = 31;
    private ImageButton privacyModeToggle;
    private Switch toggle;
    private Switch keepAliveToggle;
    private Switch hideNotificationToggle;
    private TextView hideNotificationSubtext;
    private TextView status;
    private TextView endpoint;
    private TextView webhookStatus;
    private View webhookStatusPanel;
    private View webhookSetupButton;
    private View setupPanel;
    private View notificationPermissionPanel;
    private View batteryOptimizationPanel;
    private View locationPermissionPanel;
    private View locationPermissionFallbackBody;
    private View trustAllNetworksButton;
    private View adviceBanner;
    private LinearLayout wifiApsCurrentRow;
    private LinearLayout wifiApsList;
    private TextView wifiApsEmpty;
    private TextView wifiApsToggle;
    private Switch wifiApsTrustedOnlyToggle;
    // #484: the "restrict Keep-Alive to trusted networks" mode toggle and its status text,
    // moved here from SettingsActivity.
    private TextView trustedNetworkStatus;
    private LinearLayout wifiSsidsSection;
    private LinearLayout wifiSsidsCurrentRow;
    private LinearLayout wifiSsidsList;
    private TextView wifiSsidsEmpty;
    // #485: whitelist management and block history, moved here from SettingsActivity.
    private AlertDialog activeBlockedNetworksDialog;
    // #468: whether the "recently observed" access points below the current connection are
    // shown in full. Deliberately in-memory only (not persisted) -- it is a display convenience
    // for the current screen visit, not a user setting; a fresh visit starts collapsed again.
    private boolean wifiApsExpanded;
    // #479: whether the access point list is filtered down to trusted-only entries. Same
    // in-memory-only rationale as wifiApsExpanded above -- a display convenience for this visit,
    // not a persisted user setting.
    private boolean wifiApsTrustedOnly;
    private boolean notificationPermissionRequestPending;
    private long endpointListenerGeneration;
    private boolean endpointSurfaceActive;
    // #483: last discovered endpoint, unmasked. Display text is derived from it on every render.
    private String lastEndpointHost;
    private int lastEndpointPort;

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(KeepADBLocaleHelper.wrapContext(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // #324: keep header and content clear of the system bars under forced edge-to-edge.
        KeepADBWindowInsets.apply(
                getWindow(), findViewById(R.id.header_bar), findViewById(R.id.content_scroll));
        privacyModeToggle = findViewById(R.id.btn_toggle_privacy_mode);
        privacyModeToggle.setOnClickListener(v -> {
            boolean want = !KeepADBPreferences.isPrivacyModeEnabled(this);
            KeepADBDiagnostics.event(this, "user_action", "app", want ? "enable" : "disable",
                    "privacy_mode_toggle");
            KeepADBPreferences.setPrivacyModeEnabled(this, want);
            updatePrivacyModeToggle();
            // #483: re-render the masked surfaces at once, without waiting for a discovery tick.
            renderEndpoint();
            refreshWebhookStatus();
            KeepADBNotification.refresh(this);
            KeepADBTileService.requestRefresh(this);
        });
        toggle = findViewById(R.id.toggle);
        keepAliveToggle = findViewById(R.id.keep_alive_toggle);
        hideNotificationToggle = findViewById(R.id.hide_notification_toggle);
        hideNotificationSubtext = findViewById(R.id.hide_notification_subtext);
        status = findViewById(R.id.status);
        endpoint = findViewById(R.id.endpoint);
        webhookStatus = findViewById(R.id.webhook_status);
        webhookStatusPanel = findViewById(R.id.webhook_status_panel);
        webhookSetupButton = findViewById(R.id.webhook_setup_button);
        webhookSetupButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, SettingsActivity.class);
            intent.putExtra(SettingsActivity.EXTRA_FOCUS_WEBHOOK, true);
            startActivity(intent);
        });
        setupPanel = findViewById(R.id.setup_panel);
        notificationPermissionPanel = findViewById(R.id.notification_permission_panel);
        batteryOptimizationPanel = findViewById(R.id.battery_optimization_panel);
        locationPermissionPanel = findViewById(R.id.location_permission_panel);
        locationPermissionFallbackBody = findViewById(R.id.location_permission_fallback_body);
        trustAllNetworksButton = findViewById(R.id.btn_trust_all_networks);
        adviceBanner = findViewById(R.id.advice_banner);
        wifiApsCurrentRow = findViewById(R.id.wifi_aps_current_row);
        wifiApsList = findViewById(R.id.wifi_aps_list);
        wifiApsEmpty = findViewById(R.id.wifi_aps_empty);
        wifiApsToggle = findViewById(R.id.wifi_aps_toggle);
        wifiApsToggle.setOnClickListener(v -> {
            wifiApsExpanded = !wifiApsExpanded;
            renderAccessPointOverview();
        });
        wifiApsTrustedOnlyToggle = findViewById(R.id.wifi_aps_trusted_only_toggle);
        wifiApsTrustedOnlyToggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            wifiApsTrustedOnly = isChecked;
            renderAccessPointOverview();
        });
        trustedNetworkStatus = findViewById(R.id.wifi_aps_trust_restriction_status);
        wifiSsidsSection = findViewById(R.id.wifi_ssids_section);
        wifiSsidsCurrentRow = findViewById(R.id.wifi_ssids_current_row);
        wifiSsidsList = findViewById(R.id.wifi_ssids_list);
        wifiSsidsEmpty = findViewById(R.id.wifi_ssids_empty);
        findViewById(R.id.wifi_aps_recently_blocked_button).setOnClickListener(v ->
                showBlockedNetworkDialog());
        findViewById(R.id.setup_refresh).setOnClickListener(v -> refreshUiAndComponents());
        findViewById(R.id.btn_open_settings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.btn_open_notification_settings).setOnClickListener(v ->
                openNotificationSettings());
        findViewById(R.id.btn_open_battery_settings).setOnClickListener(v ->
                KeepADBBatteryOptimization.openSettings(this));
        findViewById(R.id.btn_grant_location_permission).setOnClickListener(v -> {
            getPreferences(MODE_PRIVATE).edit()
                    .putBoolean(LOCATION_PERMISSION_REQUESTED, true).apply();
            // Requested together per Android's guidance for FINE: the system then offers the
            // user a precise/approximate choice in one dialog. Only a FINE grant is actually
            // usable for network identification (see onRequestPermissionsResult), matching
            // SettingsActivity's existing trusted-network permission flow.
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION},
                    LOCATION_PERMISSION_REQUEST);
        });
        trustAllNetworksButton.setOnClickListener(v -> {
            KeepADBTrustedNetwork.setMode(this, KeepADBTrustedNetwork.MODE_ALL_WIFI);
            Toast.makeText(this, R.string.location_permission_panel_fallback_toast,
                    Toast.LENGTH_SHORT).show();
            refreshUiAndComponents();
        });
        findViewById(R.id.btn_dismiss_advice_banner).setOnClickListener(v -> {
            KeepADBPreferences.setAdviceBannerVisible(this, false);
            updateAdviceBannerVisibility();
        });

        updateAdviceBannerVisibility();

        if (shouldRequestNotificationPermission()) {
            getPreferences(MODE_PRIVATE).edit()
                    .putBoolean(NOTIFICATION_PERMISSION_REQUESTED, true).apply();
            notificationPermissionRequestPending = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    NOTIFICATION_PERMISSION_REQUEST);
        }

        // OnClick fires only for user interaction, unlike OnCheckedChanged during refresh().
        toggle.setOnClickListener(v -> {
            // #318: the desired value comes from the shared click semantics in KeepADB, not from
            // the view's own checked state, so this switch, the tile and the widget request the
            // same thing for the same state. refresh() below re-renders the switch from the real
            // adb_wifi_enabled value either way.
            boolean want = KeepADB.desiredOnForClick(KeepADB.getState(this));
            KeepADBDiagnostics.event(this, "user_action", "app", want ? "enable" : "disable", "toggle");
            if (!KeepADB.setEnabled(this, want, "app")) {
                toggle.setChecked(!want);
                showToggleErrorToast();
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
                    showToggleErrorToast();
                }
            }
            KeepADBService.sync(this);
            KeepADBWidget.refreshAll(this);
            KeepADBNotification.refresh(this);
            refresh();
        });

        hideNotificationToggle.setOnClickListener(v -> {
            // #456: the switch shows positive framing ("persistent notification" ON = visible),
            // while the underlying preference and its accessor names stay hide-framed. Invert here.
            boolean wantVisible = hideNotificationToggle.isChecked();
            boolean wantHidden = !wantVisible;
            KeepADBDiagnostics.event(this, "user_action", "app", wantVisible ? "enable" : "disable", "hide_notification_toggle");
            KeepADBPreferences.setNotificationHidden(this, wantHidden);
            KeepADBNotification.refresh(this);
            Toast.makeText(this,
                    wantHidden ? R.string.settings_notification_hidden_toast : R.string.settings_notification_visible_toast,
                    Toast.LENGTH_SHORT).show();
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
        // Register before refreshing the notification: a cached endpoint can trigger a webhook
        // report during refresh(), and the completed background report must reach this screen.
        KeepADBRegisterClient.setRegisterStateListener(this::refreshWebhookStatus);
        // Keep-Alive is a persisted preference, but the foreground service backing it can die
        // (OEM battery optimization, process kill) without the preference changing. Only the
        // toggle's own click listener called sync() before; without it here, reopening the app
        // after such a kill never resurrects the service. See #106.
        KeepADBService.sync(this);
        // #224: re-read the preference on every resume, since it may have changed in
        // SettingsActivity while this activity was paused.
        if (KeepADBPreferences.isKeepDisplayOnEnabled(this)) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        // #226: re-read the preference on every resume, since it may have changed in
        // SettingsActivity while this activity was paused.
        updateAdviceBannerVisibility();
        // #461: record the current access point into the observation history once per resume,
        // not on every refresh() -- refresh() also runs on every endpoint-discovery tick while
        // this screen is visible, and recordObservation()'s own SSID-recency bookkeeping writes
        // to SharedPreferences on every call, not only when a genuinely new BSSID shows up.
        recordCurrentAccessPointObservation();
        refresh();
        KeepADBNotification.refresh(this);
        KeepADBUsbReceiver.refresh(this);
    }

    @Override
    protected void onPause() {
        // #224: explicit clear on top of Android's own release when the window loses visibility,
        // per the acceptance requirement that the flag is reliably released on leaving the app.
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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
        } else if (requestCode == LOCATION_PERMISSION_REQUEST) {
            // #459: refresh() re-derives the panel and its fallback section straight from
            // checkSelfPermission() and the LOCATION_PERMISSION_REQUESTED flag set on request --
            // no need to branch on grantResults here (matches SettingsActivity's own pattern).
            refresh();
        } else if (requestCode == TRUSTED_NETWORK_LOCATION_PERMISSION_REQUEST) {
            // grantResults can be shorter than permissions (even empty) if the request was
            // interrupted (e.g. the app was backgrounded while the system dialog was up), so
            // re-query the actual permission state instead of indexing into it.
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

    @Override
    protected void onDestroy() {
        // #485: mirrors SettingsActivity's own dialog-leak prevention for the dialogs moved here.
        if (activeBlockedNetworksDialog != null) {
            if (activeBlockedNetworksDialog.isShowing()) {
                activeBlockedNetworksDialog.dismiss();
            }
            activeBlockedNetworksDialog = null;
        }
        super.onDestroy();
    }

    private void refresh() {
        KeepADB.State appState = KeepADB.getState(this);
        boolean configured = (appState != KeepADB.State.PERMISSION_MISSING);
        // #318 (acceptance criterion 1): the main switch mirrors Settings.Global.adb_wifi_enabled
        // and nothing else. It is deliberately read straight from the gateway rather than derived
        // from appState, so no future state value can make the switch claim "on" while the system
        // setting is 0. "Keep-Alive is waiting" is a separate dimension and lives in the subtext.
        boolean on = configured && KeepADB.isEnabled(this);
        boolean notificationsDenied = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && !notificationPermissionRequestPending
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED;
        setupPanel.setVisibility(configured ? View.GONE : View.VISIBLE);
        notificationPermissionPanel.setVisibility(notificationsDenied ? View.VISIBLE : View.GONE);
        batteryOptimizationPanel.setVisibility(KeepADBBatteryOptimization.isExempt(this)
                ? View.GONE : View.VISIBLE);
        updateLocationPermissionPanel();
        toggle.setEnabled(configured);
        toggle.setChecked(on);
        if (!configured) {
            status.setText(getString(R.string.status_permission_missing));
        } else if (KeepADB.isTogglePending()) {
            // #318: a scheduled-but-not-yet-written toggle is its own visible state.
            status.setText(getString(R.string.status_pending));
        } else if (appState == KeepADB.State.OFF) {
            status.setText(getString(R.string.status_off));
        } else if (appState == KeepADB.State.OFF_KEEP_ALIVE_WAITING) {
            // #458: distinguish real "no Wi-Fi yet" waiting from Keep-Alive being blocked by an
            // untrusted network or an unreadable network identity, so the status card never looks
            // like KeepADB simply failed to notice a live Wi-Fi connection.
            KeepAliveWaitingDetail detail = resolveKeepAliveWaitingDetail(this);
            if (detail == KeepAliveWaitingDetail.BLOCKED_UNTRUSTED_NETWORK) {
                status.setText(getString(R.string.status_off_keep_alive_blocked_untrusted));
            } else if (detail == KeepAliveWaitingDetail.BLOCKED_IDENTITY_UNAVAILABLE) {
                status.setText(getString(R.string.status_off_keep_alive_blocked_identity_unavailable));
            } else {
                status.setText(getString(R.string.status_off_keep_alive_waiting));
            }
        } else if (appState == KeepADB.State.ENABLED_DISCONNECTED) {
            status.setText(getString(R.string.status_enabled_disconnected));
        } else {
            status.setText(getString(R.string.status_on));
        }
        keepAliveToggle.setEnabled(configured);
        keepAliveToggle.setChecked(KeepADBPreferences.isKeepAliveEnabled(this));
        hideNotificationToggle.setEnabled(configured);
        // #456: positive framing — checked means the notification stays visible.
        hideNotificationToggle.setChecked(!KeepADBPreferences.isNotificationHidden(this));
        boolean keepAliveActive = KeepADBPreferences.isKeepAliveEnabled(this);
        hideNotificationSubtext.setText(keepAliveActive
                ? R.string.settings_hide_notification_subtext_keepalive
                : R.string.settings_hide_notification_subtext);
        refreshWebhookStatus();
        renderAccessPointOverview();
        renderTrustedNetworkSection();
        renderTrustedSsidSection();
        updatePrivacyModeToggle();
    }

    /** #484: renders the trust-restriction status text, moved here from SettingsActivity's own
     * refresh(). #492: the switch itself moved back to SettingsActivity -- only the consequence of
     * the policy is reported here, next to the list it acts on. */
    private void renderTrustedNetworkSection() {
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
        // #485: the count is on the button itself so the blocked log is visible without opening
        // it -- "0" is the normal, reassuring state, not a reason to hide the entry point.
        Button blockedButton = findViewById(R.id.wifi_aps_recently_blocked_button);
        blockedButton.setText(getString(R.string.settings_trusted_network_blocked_button,
                KeepADBBlockedNetworkHistory.getEntries(this).size()));
    }

    /**
     * #492: renders the optional SSID allowlist in the same visual logic as the BSSID rows above
     * -- current value on top, one row per listed entry below, each with the same add/remove
     * action pair. The whole section is hidden unless its opt-in (SettingsActivity) is on, so the
     * weaker matching model is never present on screen for a user who did not choose it.
     *
     * <p>There is deliberately no add action for anything but the currently connected, fully
     * readable network: no free-text field and no "add from history". A name typed by hand or
     * picked from a stale observation is a name whose access point the user is not standing in
     * front of, and every such entry widens the allowance by more than the one network they meant.
     */
    private void renderTrustedSsidSection() {
        boolean enabled = KeepADBTrustedNetwork.isSsidMatchingEnabled(this);
        wifiSsidsSection.setVisibility(enabled ? View.VISIBLE : View.GONE);
        if (!enabled) return;

        wifiSsidsCurrentRow.removeAllViews();
        wifiSsidsList.removeAllViews();

        KeepADBNetworkIdentity identity = KeepADBNetworkIdentity.current(this);
        String currentSsid = identity.isKnown() ? identity.displaySsid() : null;
        if (currentSsid == null || currentSsid.isEmpty()) {
            // Fail-closed presentation to match the fail-closed policy: an unreadable identity
            // offers no add action at all, rather than an action that would store a placeholder.
            TextView unknown = new TextView(this);
            unknown.setText(R.string.wifi_ssids_current_unknown);
            unknown.setTextColor(getColor(R.color.night_muted));
            unknown.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
            wifiSsidsCurrentRow.addView(unknown);
        } else {
            wifiSsidsCurrentRow.addView(buildCurrentSsidRow(currentSsid));
        }

        List<KeepADBTrustedNetwork.SsidEntry> entries = KeepADBTrustedNetwork.getSsidEntries(this);
        for (KeepADBTrustedNetwork.SsidEntry entry : entries) {
            wifiSsidsList.addView(buildTrustedSsidRow(entry));
        }
        wifiSsidsEmpty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /** The "currently connected SSID" row: label plus an add action, or nothing to add when the
     * name is already listed (the listed row below carries the remove action). */
    private View buildCurrentSsidRow(String currentSsid) {
        boolean listed = KeepADBTrustedNetwork.findSsidEntryForCurrentNetwork(this) != null;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = new TextView(this);
        label.setText(getString(R.string.wifi_aps_current_badge) + " · " + currentSsid);
        label.setTextColor(getColor(R.color.night_text));
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        label.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.addView(label);

        if (!listed) {
            Button add = new Button(this);
            add.setBackgroundResource(R.drawable.bg_btn_primary);
            add.setMinHeight((int) (48 * getResources().getDisplayMetrics().density));
            add.setTextColor(getColor(R.color.title_yellow));
            add.setText(R.string.wifi_ssids_add_button);
            add.setContentDescription(getString(R.string.wifi_ssids_add_accessibility, currentSsid));
            add.setOnClickListener(v -> {
                KeepADBTrustedNetwork.SsidEntry added = KeepADBTrustedNetwork.addCurrentSsid(this);
                if (added == null) {
                    Toast.makeText(this, R.string.settings_trusted_network_add_failed_toast,
                            Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, getString(R.string.wifi_ssids_added_toast, added.ssid),
                            Toast.LENGTH_SHORT).show();
                }
                refreshUiAndComponents();
            });
            row.addView(add);
        }
        return row;
    }

    /** One listed-SSID row, with the remove action in the same warn style the untrust action of a
     * BSSID row uses. */
    private View buildTrustedSsidRow(KeepADBTrustedNetwork.SsidEntry entry) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int topMargin = (int) (12 * getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = topMargin;
        row.setLayoutParams(rowParams);

        TextView label = new TextView(this);
        label.setText(entry.ssid);
        label.setTextColor(getColor(R.color.night_text));
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        label.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Button remove = new Button(this);
        remove.setBackgroundResource(R.drawable.bg_btn_secondary);
        remove.setMinHeight((int) (48 * getResources().getDisplayMetrics().density));
        remove.setTextColor(getColor(R.color.text_yellow));
        remove.setText(R.string.wifi_ssids_remove_button);
        remove.setContentDescription(getString(R.string.wifi_ssids_remove_accessibility, entry.ssid));
        remove.setOnClickListener(v -> {
            if (KeepADBTrustedNetwork.removeSsid(this, entry.id)) {
                Toast.makeText(this, getString(R.string.wifi_ssids_removed_toast, entry.ssid),
                        Toast.LENGTH_SHORT).show();
            }
            refreshUiAndComponents();
        });

        row.addView(label);
        row.addView(remove);
        return row;
    }

    /**
     * #446 (transparency half), moved here from SettingsActivity for #485: lists the access
     * points on which automatic Keep-Alive re-enable was recently blocked, newest first, and lets
     * the user allow one after the fact. The allow button goes through {@link
     * KeepADBTrustedNetwork#addBssid} -- the same entry point as the manual add, the mesh
     * convenience and the notification's allow action -- so there is exactly one way an access
     * point can become trusted, and nothing in the blocked log itself ever grants trust.
     */
    private void showBlockedNetworkDialog() {
        List<KeepADBBlockedNetworkHistory.Entry> entries =
                KeepADBBlockedNetworkHistory.getEntries(this);
        if (entries.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.settings_trusted_network_blocked_title)
                    .setMessage(R.string.settings_trusted_network_blocked_empty_message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        rows.setPadding(padding, 0, padding, 0);
        final AlertDialog[] dialogHolder = new AlertDialog[1];
        // Newest first: the access point the user just failed to connect on is the one they came
        // here for, and getEntries() returns the log oldest-first.
        for (int i = entries.size() - 1; i >= 0; i--) {
            KeepADBBlockedNetworkHistory.Entry entry = entries.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout labelColumn = new LinearLayout(this);
            labelColumn.setOrientation(LinearLayout.VERTICAL);
            labelColumn.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            TextView label = new TextView(this);
            label.setText(entry.label());
            label.setTextColor(getColor(R.color.night_text));
            TextView detail = new TextView(this);
            detail.setText(getString(R.string.settings_trusted_network_blocked_detail,
                    entry.bssid,
                    android.text.format.DateUtils.getRelativeTimeSpanString(entry.lastSeenAt,
                            System.currentTimeMillis(),
                            android.text.format.DateUtils.MINUTE_IN_MILLIS).toString()));
            detail.setTextColor(getColor(R.color.night_muted));
            detail.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
            labelColumn.addView(label);
            labelColumn.addView(detail);
            Button allow = new Button(this);
            allow.setText(R.string.settings_trusted_network_blocked_allow_button);
            allow.setContentDescription(getString(
                    R.string.settings_trusted_network_blocked_allow_accessibility, entry.label()));
            allow.setOnClickListener(v -> {
                // #475: goes through the same trust-and-connect entry point this activity's
                // per-access-point trust button uses (#470) -- it already covers the allowlist
                // entry, dropping this BSSID from the blocked-network log, clearing only its
                // anti-spam marker (#474: this dialog can list several still-blocked access
                // points at once, so a global clear would wipe the history for the other rows
                // too) and cancelling the prompt notification, and on top of that immediately
                // attempts the connection that being untrusted was blocking, instead of leaving
                // the user to wait for Keep-Alive's next pass.
                KeepADBReceiver.trustBssidAndAttemptConnect(this, entry.bssid, entry.label());
                Toast.makeText(this,
                        getString(R.string.settings_trusted_network_added_toast, entry.label()),
                        Toast.LENGTH_SHORT).show();
                dialogHolder[0].dismiss();
                refresh();
            });
            row.addView(labelColumn);
            row.addView(allow);
            rows.addView(row);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(rows);
        dialogHolder[0] = new AlertDialog.Builder(this)
                .setTitle(R.string.settings_trusted_network_blocked_title)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        activeBlockedNetworksDialog = dialogHolder[0];
        dialogHolder[0].setOnDismissListener(d -> {
            if (activeBlockedNetworksDialog == d) {
                activeBlockedNetworksDialog = null;
            }
        });
        dialogHolder[0].show();
    }

    AlertDialog getActiveBlockedNetworksDialog() {
        return activeBlockedNetworksDialog;
    }

    /** #482: reflects {@link KeepADBPreferences#isPrivacyModeEnabled} as an eye / crossed-out-eye
     * icon with a content description naming the action the next tap performs (matching the
     * existing trust/untrust content description pattern), so both toggle states stay
     * distinguishable for sighted and screen-reader users alike. Display-only -- toggling this
     * never touches the real ADB transport or any persisted original value; the actual masking
     * of displayed network addresses is #483's job, reading the same preference. */
    private void updatePrivacyModeToggle() {
        boolean enabled = KeepADBPreferences.isPrivacyModeEnabled(this);
        privacyModeToggle.setImageResource(enabled ? R.drawable.ic_privacy_eye_off : R.drawable.ic_privacy_eye);
        privacyModeToggle.setContentDescription(getString(enabled
                ? R.string.privacy_toggle_disable_accessibility
                : R.string.privacy_toggle_enable_accessibility));
    }

    /** Records the currently connected access point into {@link KeepADBBssidHistory}, mirroring
     * the piggyback {@code SettingsActivity#refresh()} already does on its own identity read. */
    private void recordCurrentAccessPointObservation() {
        KeepADBNetworkIdentity identity = KeepADBNetworkIdentity.current(this);
        if (identity.isKnown()) {
            KeepADBBssidHistory.recordObservation(this, identity.displaySsid(), identity.bssid);
        }
    }

    /** #468: how many recently observed access points are shown below the current connection
     * before the list is collapsed behind a "show more" toggle. The current connection itself is
     * always visible regardless of this limit (acceptance criterion 2 -- connection state stays
     * comprehensible across expand/collapse) since it is rendered in its own row, never as part
     * of the collapsible list. */
    private static final int WIFI_APS_COLLAPSED_OTHERS = 5;

    /**
     * Renders the "Wi-Fi &amp; Access Points" card (#461, made collapsible for #468, focused on
     * SSID/BSSID with a trusted-only filter for #479): the currently connected access point
     * (SSID, BSSID) highlighted on top, and recently observed ones -- reusing {@link
     * KeepADBBssidHistory}'s existing mesh observation log -- listed below, each with a quick
     * trust toggle. By default only the first {@link #WIFI_APS_COLLAPSED_OTHERS} of those are
     * shown; a "show more"/"show less" toggle appears only when there is actually more to reveal,
     * so short lists and the empty state need no extra interaction (acceptance criterion 3). When
     * {@link #wifiApsTrustedOnly} is enabled, both the current connection and the "others" list
     * are filtered down to {@code trusted} items only.
     */
    private void renderAccessPointOverview() {
        List<KeepADBAccessPointOverview.ApItem> items = KeepADBAccessPointOverview.buildItems(this);

        wifiApsCurrentRow.removeAllViews();
        wifiApsList.removeAllViews();

        KeepADBAccessPointOverview.ApItem currentItem = null;
        List<KeepADBAccessPointOverview.ApItem> others = new ArrayList<>();
        for (KeepADBAccessPointOverview.ApItem item : items) {
            if (item.current) {
                currentItem = item;
            } else {
                others.add(item);
            }
        }

        // #479: the "trusted only" filter hides untrusted items from both the current-connection
        // row and the "others" list. A hidden current connection is deliberately left blank
        // (no view added) rather than reusing wifi_aps_current_unknown, which would misleadingly
        // claim the network can't be identified when it actually was, just filtered out.
        boolean currentHiddenByFilter = wifiApsTrustedOnly && currentItem != null && !currentItem.trusted;
        if (currentHiddenByFilter) {
            currentItem = null;
        }
        if (wifiApsTrustedOnly) {
            others.removeIf(item -> !item.trusted);
        }

        if (currentItem != null) {
            wifiApsCurrentRow.addView(buildAccessPointRow(currentItem, true));
        } else if (!currentHiddenByFilter) {
            TextView unknown = new TextView(this);
            unknown.setText(R.string.wifi_aps_current_unknown);
            unknown.setTextColor(getColor(R.color.night_muted));
            unknown.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
            wifiApsCurrentRow.addView(unknown);
        }

        boolean collapsible = others.size() > WIFI_APS_COLLAPSED_OTHERS;
        boolean expanded = wifiApsExpanded && collapsible;
        int visibleCount = expanded ? others.size() : Math.min(others.size(), WIFI_APS_COLLAPSED_OTHERS);
        for (int i = 0; i < visibleCount; i++) {
            wifiApsList.addView(buildAccessPointRow(others.get(i), false));
        }
        wifiApsEmpty.setVisibility(others.isEmpty() ? View.VISIBLE : View.GONE);

        if (collapsible) {
            wifiApsToggle.setVisibility(View.VISIBLE);
            wifiApsToggle.setText(expanded
                    ? getString(R.string.wifi_aps_show_less_button)
                    : getString(R.string.wifi_aps_show_more_button, others.size() - WIFI_APS_COLLAPSED_OTHERS));
        } else {
            // Nothing to hide -- no toggle, and a stale expanded flag from a previously longer
            // list must not resurface once the list shrinks back below the collapse threshold.
            wifiApsToggle.setVisibility(View.GONE);
            wifiApsExpanded = false;
        }
    }

    /** One row of the Wi-Fi & Access Points card: SSID/BSSID label (#479 -- primary visual focus,
     * no redundant trust/mesh status text) and a quick action button to toggle the trust
     * allowlist entry for that BSSID (#480 -- primary style for "trust", warn style for
     * "untrust"). */
    private View buildAccessPointRow(KeepADBAccessPointOverview.ApItem item, boolean highlightCurrent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        if (!highlightCurrent) {
            int topMargin = (int) (12 * getResources().getDisplayMetrics().density);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowParams.topMargin = topMargin;
            row.setLayoutParams(rowParams);
        }

        LinearLayout labelColumn = new LinearLayout(this);
        labelColumn.setOrientation(LinearLayout.VERTICAL);
        labelColumn.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView label = new TextView(this);
        String ssidLabel = (item.ssid == null || item.ssid.isEmpty())
                ? getString(R.string.wifi_aps_ssid_unknown) : item.ssid;
        label.setText(highlightCurrent
                ? getString(R.string.wifi_aps_current_badge) + " · " + ssidLabel
                : ssidLabel);
        label.setTextColor(getColor(R.color.night_text));
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);

        TextView bssidText = new TextView(this);
        bssidText.setText(item.bssid);
        bssidText.setTextColor(getColor(R.color.night_muted));
        bssidText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);

        labelColumn.addView(label);
        labelColumn.addView(bssidText);

        // #480: trust (add) uses the app's existing primary/affirmative button style, untrust
        // (remove) keeps the red-bordered secondary/warn style already used elsewhere (e.g.
        // Settings' "Clear" action) -- the same primary-vs-secondary distinction the app already
        // draws for its other action pairs, so state and action stay distinguishable at a glance.
        Button trustButton = new Button(this);
        trustButton.setBackgroundResource(
                item.trusted ? R.drawable.bg_btn_secondary : R.drawable.bg_btn_primary);
        trustButton.setMinHeight((int) (48 * getResources().getDisplayMetrics().density));
        trustButton.setTextColor(getColor(item.trusted ? R.color.text_yellow : R.color.title_yellow));
        trustButton.setText(item.trusted ? R.string.wifi_aps_untrust_button : R.string.wifi_aps_trust_button);
        trustButton.setContentDescription(getString(
                item.trusted ? R.string.wifi_aps_untrust_accessibility : R.string.wifi_aps_trust_accessibility,
                ssidLabel));
        trustButton.setOnClickListener(v -> toggleAccessPointTrust(item, ssidLabel));

        row.addView(labelColumn);
        row.addView(trustButton);
        return row;
    }

    /** Adds or removes {@code item}'s BSSID from the trust allowlist -- the same {@link
     * KeepADBTrustedNetwork} entry point Settings uses, so there is exactly one way an access
     * point can become trusted. */
    private void toggleAccessPointTrust(KeepADBAccessPointOverview.ApItem item, String label) {
        if (item.trusted) {
            KeepADBTrustedNetwork.Entry match = null;
            for (KeepADBTrustedNetwork.Entry entry : KeepADBTrustedNetwork.getEntries(this)) {
                if (entry.bssid.equalsIgnoreCase(item.bssid)) {
                    match = entry;
                    break;
                }
            }
            if (match != null && KeepADBTrustedNetwork.remove(this, match.id)) {
                Toast.makeText(this, getString(R.string.settings_trusted_network_removed_toast, match.label),
                        Toast.LENGTH_SHORT).show();
            }
        } else {
            KeepADBTrustedNetwork.Entry added = KeepADBTrustedNetwork.addBssid(this, item.bssid, label);
            if (added != null) {
                Toast.makeText(this, getString(R.string.settings_trusted_network_added_toast, added.label),
                        Toast.LENGTH_SHORT).show();
                // #470: trusting an access point from here must immediately attempt the
                // connection it was blocking -- the same way the notification's "allow" action
                // already does -- instead of requiring the user to dig through a pushdown
                // notification first. Also cancels/clears that notification if one is showing.
                boolean enabled = KeepADBReceiver.trustBssidAndAttemptConnect(this, item.bssid, added.label);
                if (!enabled && !hasSecureSettingsPermission()) {
                    showToggleErrorToast();
                }
                // #492: the mesh convenience moved here from SettingsActivity's removed
                // add-current-network button, which was the duplicate management surface this
                // card replaced. Offering it only for the access point the device is actually on
                // keeps it tied to a verified identity, as it was before.
                if (item.current) {
                    offerAdditionalMeshBssids();
                }
            }
        }
        refresh();
    }

    /**
     * After trusting the currently connected access point, offers to also add any other BSSIDs the
     * observation history (#266) has seen broadcasting the same SSID -- covers Wi-Fi mesh setups
     * (several access points, one SSID, different BSSIDs) without ever trusting *by* SSID:
     * declining still keeps only the just-added BSSID trusted, and accepting adds each additional
     * BSSID through the same {@link KeepADBTrustedNetwork} entry point as a normal manual add.
     *
     * <p>#492: moved here verbatim from SettingsActivity, whose add-current-network button was
     * removed as duplicate management of this card's own rows. This is deliberately still the
     * BSSID-by-BSSID offer and not a shortcut into the new SSID allowlist -- adding each real
     * access point keeps every entry BSSID-verified, which is exactly the property the separate
     * SSID opt-in gives up.
     */
    private void offerAdditionalMeshBssids() {
        KeepADBNetworkIdentity identity = KeepADBNetworkIdentity.current(this);
        if (!identity.isKnown()) return;
        String ssid = identity.displaySsid();
        if (ssid == null || ssid.isEmpty()) return;

        List<String> alreadyListed = new ArrayList<>();
        for (KeepADBTrustedNetwork.Entry listed : KeepADBTrustedNetwork.getEntries(this)) {
            alreadyListed.add(listed.bssid);
        }
        List<String> additional = KeepADBBssidHistory.getAdditionalBssids(this, ssid, alreadyListed);
        if (additional.isEmpty()) return;

        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_trusted_network_mesh_title)
                .setMessage(getString(R.string.settings_trusted_network_mesh_message, additional.size(), ssid))
                .setPositiveButton(R.string.settings_trusted_network_mesh_add_button, (dialog, which) -> {
                    // #475: same trust-and-connect entry point as the other trust actions
                    // (#470/#474) -- the device is only ever on one of these BSSIDs at a time, so
                    // at most one call here actually finds Wireless Debugging still off on the
                    // currently-connected access point and turns it on; the rest are no-ops
                    // beyond trusting the BSSID, same as a plain addBssid would have been.
                    for (String bssid : additional) {
                        KeepADBReceiver.trustBssidAndAttemptConnect(this, bssid, ssid);
                    }
                    Toast.makeText(this,
                            getString(R.string.settings_trusted_network_mesh_added_toast, additional.size()),
                            Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * #458: why {@link KeepADB.State#OFF_KEEP_ALIVE_WAITING} currently applies, for the status
     * card only. Deliberately not on {@link KeepADB} itself: {@code
     * KeepADBTrustedNetworkContractTest#keepAdbFacadeNeverReferencesTheAllowlist} pins the toggle
     * facade as never referencing {@link KeepADBTrustedNetwork} (issue #245) -- the trust check
     * belongs at the call site that wants to explain a decision, not inside the facade.
     */
    enum KeepAliveWaitingDetail {
        /** No Wi-Fi transport connected at all -- the literal "waiting for the network" case. */
        WIFI_DISCONNECTED,
        /** Wi-Fi is connected, but the network isn't on the trusted allowlist (#245). */
        BLOCKED_UNTRUSTED_NETWORK,
        /** Wi-Fi is connected, but its identity can't be read (missing Location permission). */
        BLOCKED_IDENTITY_UNAVAILABLE
    }

    /**
     * Only meaningful while {@link KeepADB#getState} returned
     * {@link KeepADB.State#OFF_KEEP_ALIVE_WAITING}. Reuses the same connectivity/trust checks the
     * automatic re-enable call sites already gate on ({@link KeepADBService#isWifiConnected} and
     * {@link KeepADBTrustedNetwork#getBlockReason}), so this can never disagree with why
     * Keep-Alive itself didn't re-enable.
     */
    static KeepAliveWaitingDetail resolveKeepAliveWaitingDetail(android.content.Context context) {
        if (!KeepADBService.isWifiConnected(context)) {
            return KeepAliveWaitingDetail.WIFI_DISCONNECTED;
        }
        KeepADBTrustedNetwork.BlockReason reason = KeepADBTrustedNetwork.getBlockReason(context);
        if (reason == KeepADBTrustedNetwork.BlockReason.IDENTITY_UNAVAILABLE) {
            return KeepAliveWaitingDetail.BLOCKED_IDENTITY_UNAVAILABLE;
        }
        if (reason == KeepADBTrustedNetwork.BlockReason.UNTRUSTED_NETWORK) {
            return KeepAliveWaitingDetail.BLOCKED_UNTRUSTED_NETWORK;
        }
        // Trusted (or all-Wi-Fi mode) but still waiting -- e.g. the debounce window hasn't fired
        // yet. Nothing wrong to explain; render the generic waiting text.
        return KeepAliveWaitingDetail.WIFI_DISCONNECTED;
    }

    /**
     * #459: the allowlist (#260 default) needs {@code ACCESS_FINE_LOCATION} to read the current
     * Wi-Fi network's identity at all -- without it, {@code identity_unavailable} blocks
     * automatic Keep-Alive re-enable forever, and until now the permission was only ever asked
     * for from {@link SettingsActivity}'s manual toggle, never on first run. Shown independent of
     * {@code configured}/WRITE_SECURE_SETTINGS, matching {@link #notificationPermissionPanel} and
     * {@link #batteryOptimizationPanel}'s own onboarding panels.
     */
    private void updateLocationPermissionPanel() {
        boolean locationGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean showPanel = KeepADBTrustedNetwork.isAllowlistMode(this) && !locationGranted;
        locationPermissionPanel.setVisibility(showPanel ? View.VISIBLE : View.GONE);
        if (!showPanel) return;
        // Acceptance criterion 3: once the user has been through the system dialog at least once
        // and is still without the permission (denied, including "don't ask again"), offer the
        // clean fallback of switching to "trust all Wi-Fi networks" instead of leaving them stuck.
        boolean previouslyRequested = getPreferences(MODE_PRIVATE)
                .getBoolean(LOCATION_PERMISSION_REQUESTED, false);
        locationPermissionFallbackBody.setVisibility(previouslyRequested ? View.VISIBLE : View.GONE);
        trustAllNetworksButton.setVisibility(previouslyRequested ? View.VISIBLE : View.GONE);
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
            webhookSetupButton.setVisibility(View.VISIBLE);
            return;
        }
        webhookSetupButton.setVisibility(View.GONE);
        String reportStatus = KeepADBPreferences.getWebhookLastReportStatus(this);
        long lastReportedAt = KeepADBPreferences.getWebhookLastReportedAt(this);
        String lastReported;
        if (KeepADBPreferences.WEBHOOK_STATUS_FAILED.equals(reportStatus)) {
            lastReported = getString(R.string.webhook_status_failed);
        } else if (lastReportedAt <= 0) {
            lastReported = getString(R.string.webhook_status_never);
        } else {
            java.util.Date date = new java.util.Date(lastReportedAt);
            java.text.DateFormat dateTimeFormat = java.text.DateFormat.getDateTimeInstance(
                    java.text.DateFormat.MEDIUM, java.text.DateFormat.MEDIUM,
                    getResources().getConfiguration().getLocales().get(0));
            lastReported = dateTimeFormat.format(date);
        }
        // #483: display only -- the stored endpoint stays as reported.
        String lastEndpoint = KeepADBPreferences.maskEndpointForDisplay(
                this, KeepADBPreferences.getWebhookLastReportedEndpoint(this));
        if (lastEndpoint == null || lastEndpoint.trim().isEmpty()) {
            // Distinguish "never reported anything yet" from "was reported, then successfully
            // deregistered" -- both leave no current endpoint, but reusing the same "none yet"
            // text for a just-completed deregistration reads as if one were still pending.
            lastEndpoint = KeepADBPreferences.WEBHOOK_STATUS_DEREGISTERED.equals(reportStatus)
                    ? getString(R.string.webhook_status_deregistered)
                    : getString(R.string.webhook_status_no_endpoint);
        }
        webhookStatus.setText(getString(R.string.webhook_status_hint,
                KeepADBPreferences.maskWebhookUrlForDisplay(this, url), lastEndpoint, lastReported));
        webhookStatusPanel.setVisibility(View.VISIBLE);
    }

    private void postEndpointAvailable(long listenerGeneration, String host, int port) {
        runOnUiThread(() -> {
            if (!isEndpointSurfaceActive(listenerGeneration)) return;
            lastEndpointHost = host;
            lastEndpointPort = port;
            renderEndpoint();
            refresh();
        });
    }

    private void postEndpointUnavailable(long listenerGeneration) {
        runOnUiThread(() -> {
            if (!isEndpointSurfaceActive(listenerGeneration)) return;
            lastEndpointHost = null;
            renderEndpoint();
            refresh();
        });
    }

    /**
     * #483: renders the last discovered endpoint through the privacy mask. Kept separate from the
     * discovery callbacks so toggling privacy re-renders immediately instead of waiting for the
     * next discovery tick. Display only -- {@code lastEndpointHost} holds the real host.
     */
    private void renderEndpoint() {
        if (lastEndpointHost == null) {
            endpoint.setText(KeepADB.isEnabled(MainActivity.this)
                    ? getString(R.string.endpoint_searching) : getString(R.string.endpoint_unavailable));
            return;
        }
        endpoint.setText(getString(R.string.endpoint_format,
                KeepADBPreferences.maskHostForDisplay(MainActivity.this, lastEndpointHost),
                lastEndpointPort));
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

    /**
     * #318: a failed toggle used to always blame a missing permission, even when the permission was
     * granted and the Settings.Global write itself was rejected (#309). Report the two causes
     * separately so a rejected write is visible instead of being disguised as a setup problem.
     */
    private void showToggleErrorToast() {
        if (!hasSecureSettingsPermission()) {
            Toast.makeText(this, getString(R.string.permission_error_toast, getPackageName()),
                    Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, getString(R.string.toggle_failed_toast), Toast.LENGTH_LONG).show();
    }

    private void openNotificationSettings() {
        Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
        intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        startActivity(intent);
    }

    private void updateAdviceBannerVisibility() {
        boolean visible = KeepADBPreferences.isAdviceBannerVisible(this);
        adviceBanner.setVisibility(visible ? View.VISIBLE : View.GONE);
    }
}
