package de.hohnepeople.keepadb;

import android.app.Activity;
import android.content.Intent;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int NOTIFICATION_PERMISSION_REQUEST = 10;
    private static final String NOTIFICATION_PERMISSION_REQUESTED =
            "notification_permission_requested";
    private ImageButton privacyModeToggle;
    private Switch toggle;
    private Switch keepAliveToggle;
    private Switch hideNotificationToggle;
    private TextView hideNotificationSubtext;
    private TextView status;
    private TextView endpoint;
    private TextView tailscaleStatus;

    // #538: container for additional verified transports (Tailscale/VPN, USB) beyond the WLAN/LAN
    // endpoint already shown by `endpoint` above; populated dynamically by renderTransportOverview().
    private ViewGroup transportOverviewPanel;
    private long transportOverviewGeneration;
    private TextView webhookStatus;
    private TextView webhookTailnetHint;
    private View webhookStatusPanel;
    private View webhookSetupButton;
    private View setupPanel;
    private View notificationPermissionPanel;
    private Button notificationPermissionActionButton;
    private View batteryOptimizationPanel;
    private View adviceBanner;
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
        tailscaleStatus = findViewById(R.id.tailscale_status);

        transportOverviewPanel = findViewById(R.id.transport_overview_panel);
        webhookStatus = findViewById(R.id.webhook_status);
        webhookTailnetHint = findViewById(R.id.webhook_tailnet_hint);
        webhookStatusPanel = findViewById(R.id.webhook_status_panel);
        webhookSetupButton = findViewById(R.id.webhook_setup_button);
        webhookSetupButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, SettingsActivity.class);
            intent.putExtra(SettingsActivity.EXTRA_FOCUS_WEBHOOK, true);
            startActivity(intent);
        });
        setupPanel = findViewById(R.id.setup_panel);
        ((TextView) findViewById(R.id.setup_command)).setText(
                getString(R.string.setup_command, getPackageName()));
        ((TextView) findViewById(R.id.setup_command_multi)).setText(
                getString(R.string.setup_command_multi, getPackageName()));
        notificationPermissionPanel = findViewById(R.id.notification_permission_panel);
        notificationPermissionActionButton = findViewById(R.id.btn_open_notification_settings);
        notificationPermissionActionButton.setOnClickListener(v -> onNotificationPermissionActionClick());
        findViewById(R.id.btn_dismiss_notification_permission_panel).setOnClickListener(v -> {
            KeepADBPreferences.setNotificationPermissionPanelVisible(this, false);
            refresh();
        });
        batteryOptimizationPanel = findViewById(R.id.battery_optimization_panel);
        adviceBanner = findViewById(R.id.advice_banner);
        findViewById(R.id.setup_refresh).setOnClickListener(v -> refreshUiAndComponents());
        findViewById(R.id.btn_open_settings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.btn_open_battery_settings).setOnClickListener(v ->
                KeepADBBatteryOptimization.openSettings(this));
        findViewById(R.id.btn_dismiss_advice_banner).setOnClickListener(v -> {
            KeepADBPreferences.setAdviceBannerVisible(this, false);
            updateAdviceBannerVisibility();
        });
        findViewById(R.id.btn_dismiss_battery_optimization_panel).setOnClickListener(v -> {
            KeepADBPreferences.setBatteryOptimizationPanelVisible(this, false);
            refresh();
        });

        updateAdviceBannerVisibility();

        // #501: no more cold-start system prompt here -- notificationPermissionPanel explains the
        // benefit in place and only triggers the request (or the settings fallback) on a deliberate
        // tap, via onNotificationPermissionActionClick().

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
        // #538: invalidates any in-flight renderTransportOverview() async result so a snapshot
        // computed for a now-paused screen never applies after the fact (mirrors the
        // endpointListenerGeneration guard above).
        transportOverviewGeneration++;
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
            refresh();
            KeepADBNotification.refresh(this);
        }
    }

    private void refresh() {
        KeepADB.State appState = KeepADB.getState(this);
        boolean configured = (appState != KeepADB.State.PERMISSION_MISSING);
        // #318 (acceptance criterion 1): the main switch mirrors Settings.Global.adb_wifi_enabled
        // and nothing else. It is deliberately read straight from the gateway rather than derived
        // from appState, so no future state value can make the switch claim "on" while the system
        // setting is 0. "Keep-Alive is waiting" is a separate dimension and lives in the subtext.
        boolean on = configured && KeepADB.isEnabled(this);
        // #501: shown any time POST_NOTIFICATIONS isn't granted yet -- before the first request as
        // much as after a denial -- so the in-context explanation always precedes the system
        // prompt instead of only following a prior refusal.
        boolean notificationPermissionMissing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED;
        boolean notificationPermissionPanelVisible = notificationPermissionMissing
                && KeepADBPreferences.isNotificationPermissionPanelVisible(this);
        setupPanel.setVisibility(configured ? View.GONE : View.VISIBLE);
        notificationPermissionPanel.setVisibility(
                notificationPermissionPanelVisible ? View.VISIBLE : View.GONE);
        if (notificationPermissionPanelVisible) {
            updateNotificationPermissionPanel();
        }
        // #502: shown only while the system exemption is still missing AND the user has not
        // dismissed the panel. A granted exemption always wins, regardless of dismiss state --
        // matches the acceptance criterion that the panel stays hidden once battery optimization
        // is actually disabled for the app.
        boolean batteryOptimizationPanelVisible = !KeepADBBatteryOptimization.isExempt(this)
                && KeepADBPreferences.isBatteryOptimizationPanelVisible(this);
        batteryOptimizationPanel.setVisibility(
                batteryOptimizationPanelVisible ? View.VISIBLE : View.GONE);
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
            } else if (detail == KeepAliveWaitingDetail.BLOCKED_RECOVERY_BACKOFF) {
                // #496: an automatic enable's write was accepted but its readback never flipped
                // on -- most commonly Android's own Wireless Debugging "always allow on this
                // network" dialog was never confirmed. Automatic retries are paused; the switch
                // itself is still the sanctioned manual retry (see KeepADB#setEnabled).
                status.setText(getString(R.string.status_off_keep_alive_blocked_recovery_backoff));
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
        renderTailscaleStatus();
        updatePrivacyModeToggle();
        renderTransportOverview();
    }

    /**
     * #537: optional, purely local Tailscale status in the existing network/endpoint view.
     * Display-only -- reading it never touches {@link KeepADB}'s toggle state, Keep-Alive, or the
     * endpoint/transport discovery above, and an active Tailscale interface is never treated as
     * an ADB endpoint. Hidden entirely rather than shown as neutral/empty when Tailscale isn't
     * installed, per the acceptance criterion that a device without Tailscale stays quiet.
     *
     * <p>#548: Tailscale remains a debug-only diagnostic while its status/transport detection can
     * still disagree with itself (a Tailscale status of "not active" next to a verified
     * Tailscale/VPN transport row). A release build never shows this card at all, regardless of
     * detection state.
     */
    private void renderTailscaleStatus() {
        if (!KeepADBBuildFlags.isDebugBuild(this)) {
            tailscaleStatus.setVisibility(View.GONE);
            return;
        }
        KeepADBTailscaleStatus.Status tailscale = KeepADBTailscaleStatus.detect(this);
        if (tailscale == KeepADBTailscaleStatus.Status.NOT_INSTALLED) {
            tailscaleStatus.setVisibility(View.GONE);
            return;
        }
        int textRes;
        if (tailscale == KeepADBTailscaleStatus.Status.ACTIVE) {
            textRes = R.string.tailscale_status_active;
        } else if (tailscale == KeepADBTailscaleStatus.Status.INACTIVE) {
            textRes = R.string.tailscale_status_inactive;
        } else {
            textRes = R.string.tailscale_status_unknown;
        }
        tailscaleStatus.setText(textRes);
        tailscaleStatus.setVisibility(View.VISIBLE);
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
        BLOCKED_IDENTITY_UNAVAILABLE,
        /**
         * #496: Wi-Fi is connected and trusted, but the last automatic enable's write was
         * accepted while its readback stayed off -- most commonly because Android's own
         * "always allow Wireless Debugging on this network" pairing dialog was never confirmed.
         * Automatic retries are paused; see {@link KeepADBRecoveryBackoff}.
         */
        BLOCKED_RECOVERY_BACKOFF
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
        // #496: trusted network, but the automatic re-enable backoff is currently blocking
        // further attempts after an accepted-write-ineffective-readback cycle.
        if (KeepADB.isAutomaticEnableBackoffBlocked()) {
            return KeepAliveWaitingDetail.BLOCKED_RECOVERY_BACKOFF;
        }
        // Trusted (or all-Wi-Fi mode) but still waiting -- e.g. the debounce window hasn't fired
        // yet. Nothing wrong to explain; render the generic waiting text.
        return KeepAliveWaitingDetail.WIFI_DISCONNECTED;
    }



    /**
     * #501: the panel's button is context-sensitive. Before the first request (or while the system
     * would still show its own rationale flow), it triggers {@code requestPermissions} directly --
     * this panel already is the rationale. Once the system has permanently denied further prompts
     * ({@code shouldShowRequestPermissionRationale} false after a prior request), it instead opens
     * the app's notification settings, the only remaining way to grant the permission.
     */
    private void onNotificationPermissionActionClick() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        boolean previouslyRequested = getPreferences(MODE_PRIVATE)
                .getBoolean(NOTIFICATION_PERMISSION_REQUESTED, false);
        boolean permanentlyDenied = previouslyRequested
                && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS);
        if (permanentlyDenied) {
            openNotificationSettings();
            return;
        }
        getPreferences(MODE_PRIVATE).edit()
                .putBoolean(NOTIFICATION_PERMISSION_REQUESTED, true).apply();
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                NOTIFICATION_PERMISSION_REQUEST);
    }

    /** Renders the panel button's label to match {@link #onNotificationPermissionActionClick}'s
     * decision, so the visible action always matches what a tap will actually do. */
    private void updateNotificationPermissionPanel() {
        boolean previouslyRequested = getPreferences(MODE_PRIVATE)
                .getBoolean(NOTIFICATION_PERMISSION_REQUESTED, false);
        boolean permanentlyDenied = previouslyRequested
                && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS);
        notificationPermissionActionButton.setText(permanentlyDenied
                ? R.string.notification_permission_settings_button
                : R.string.notification_permission_request_button);
    }

    private void refreshWebhookStatus() {
        String url = KeepADBPreferences.getRegisterWebhookUrl(this);
        boolean enabled = KeepADBPreferences.isRegisterWebhookEnabled(this);
        if (!enabled || url == null || url.trim().isEmpty()) {
            webhookStatusPanel.setVisibility(View.GONE);
            webhookTailnetHint.setVisibility(View.GONE);
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

        // #561: only a possible-cause hint, never a diagnosis -- only shown while the last report
        // is still failing (cleared again the moment a later attempt reports success) and only for
        // a configured URL that looks tailnet-bound per KeepADBTailnetHeuristic. KeepADB never
        // inspects, starts or configures Tailscale/VPN state itself; see #537/#548.
        boolean showTailnetHint = KeepADBPreferences.WEBHOOK_STATUS_FAILED.equals(reportStatus)
                && KeepADBTailnetHeuristic.looksLikeTailnetTarget(url);
        webhookTailnetHint.setVisibility(showTailnetHint ? View.VISIBLE : View.GONE);
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

    /**
     * #538: kicks off a background {@link KeepADBTransportOverview} snapshot and applies it once
     * it lands, matching the generation-token pattern {@link #postEndpointAvailable} already uses
     * so a snapshot started for an older refresh (or a since-paused screen) never overwrites a
     * newer one. Off the main thread because {@link KeepADBVpnTransport#verifyAdbReachable} can
     * perform a blocking socket connect.
     */
    private void renderTransportOverview() {
        final long token = ++transportOverviewGeneration;
        KeepADBTransportOverview.currentAsync(this, snapshot -> runOnUiThread(() -> {
            if (!isTransportOverviewSurfaceActive(token)) return;
            applyTransportOverview(snapshot);
        }));
    }

    /** Analogous to {@link #isEndpointSurfaceActive(long)}, but against {@link
     * #transportOverviewGeneration} -- the two counters are bumped independently (a privacy-mode
     * toggle re-renders the endpoint surface without touching the transport overview, and vice
     * versa is not currently possible, but keeping them separate avoids coupling the two). */
    private boolean isTransportOverviewSurfaceActive(long token) {
        return endpointSurfaceActive
                && token == transportOverviewGeneration
                && !isFinishing()
                && !isDestroyed();
    }

    /**
     * Renders {@code snapshot} into {@link #transportOverviewPanel}: one row per verified
     * transport beyond WLAN/LAN (which the pre-existing {@link #endpoint} text above already
     * covers). The panel stays hidden (matching its pre-#538 absence) whenever there is nothing
     * beyond the WLAN/LAN case to show, so the common single-WLAN scenario renders exactly as
     * before.
     *
     * <p>A merely active Tailscale/VPN interface deliberately produces no row here. Saying
     * "Tailscale is up" is #537's job, and that status card is the app's single answer to it;
     * a second line built from this class' own, differently derived detection (CGNAT range plus
     * ADB socket probe) could contradict it. This panel therefore only ever speaks about
     * transports whose ADB reachability {@link KeepADBTransportOverview} actually verified --
     * which is exactly what it adds over #537's status.
     *
     * <p>#548: a Tailscale/VPN row is additionally suppressed on a release build, same as the
     * #537 status card above -- Tailscale stays a debug-only diagnostic until its detection
     * semantics are corrected in a separate follow-up.
     */
    private void applyTransportOverview(KeepADBTransportOverview.Snapshot snapshot) {
        transportOverviewPanel.removeAllViews();
        boolean wlanPrimary = false;
        boolean debugBuild = KeepADBBuildFlags.isDebugBuild(this);
        for (KeepADBTransportEndpoint transport : snapshot.transports) {
            if (transport.type == KeepADBTransportEndpoint.Type.WLAN_LAN) {
                wlanPrimary = transport.primary;
                continue;
            }
            if (transport.type == KeepADBTransportEndpoint.Type.TAILSCALE_VPN && !debugBuild) {
                continue;
            }
            transportOverviewPanel.addView(buildTransportRow(transport));
        }
        transportOverviewPanel.setVisibility(
                transportOverviewPanel.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        updateEndpointPrimaryAccessibility(wlanPrimary);
    }

    /** One dynamically added transport row: "<label>: <value> · last checked <time>", masked
     * through the same privacy toggle as the WLAN/LAN endpoint text, with an extra "primary
     * transport" content description on the transport currently holding {@link
     * KeepADBTransportEndpoint#primary}. */
    private TextView buildTransportRow(KeepADBTransportEndpoint transport) {
        String label = getString(transport.type == KeepADBTransportEndpoint.Type.TAILSCALE_VPN
                ? R.string.transport_tailscale_label : R.string.transport_usb_label);
        String value = transport.hasNetworkEndpoint()
                ? KeepADBPreferences.maskEndpointForDisplay(this,
                        KeepADBEndpoint.formatEndpoint(transport.host, transport.port))
                : getString(R.string.transport_usb_active_value);
        java.text.DateFormat dateTimeFormat = java.text.DateFormat.getDateTimeInstance(
                java.text.DateFormat.MEDIUM, java.text.DateFormat.MEDIUM,
                getResources().getConfiguration().getLocales().get(0));
        String lastChecked = dateTimeFormat.format(new java.util.Date(transport.verifiedAtMs));
        String text = getString(R.string.transport_row_format, label, value, lastChecked);
        TextView row = buildStatusRow(text);
        if (transport.primary) {
            row.setContentDescription(getString(R.string.transport_primary_accessibility_format, text));
        }
        return row;
    }

    private TextView buildStatusRow(String text) {
        TextView row = new TextView(this);
        row.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setTypeface(android.graphics.Typeface.MONOSPACE);
        row.setTextColor(getColor(R.color.night_muted));
        row.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        row.setText(text);
        return row;
    }

    /** #538: marks the pre-existing WLAN/LAN {@link #endpoint} text as the primary transport for
     * screen readers without changing its visible text (pinned by existing endpoint-format
     * tests) -- additive on top of whatever {@link #renderEndpoint()} already set. */
    private void updateEndpointPrimaryAccessibility(boolean wlanPrimary) {
        if (wlanPrimary) {
            endpoint.setContentDescription(
                    getString(R.string.transport_primary_accessibility_format, endpoint.getText()));
        } else {
            endpoint.setContentDescription(null);
        }
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
