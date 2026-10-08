package de.hohnepeople.keepadb;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    /**
     * #782: set by a single assistant step opened from a notification when it leaves for the home
     * screen: this start does not hand over to the full assistant (the next plain start does).
     */
    static final String EXTRA_SKIP_ASSISTANT_ONCE = "skip_assistant_once";

    private static final String STATE_LANGUAGE_PICKER = "main_language_picker_showing";
    private AlertDialog activeLanguageSelectionDialog;
    private KeepADBSettingsMenu settingsMenu;

    private Switch toggle;
    private Switch keepAliveToggle;
    private TextView keepAliveSubtext;
    private TextView status;
    private TextView endpoint;
    private TextView tailscaleStatus;
    // #761: set when onCreate handed over to the setup assistant; nothing of the screen exists then.
    private boolean handedOverToAssistant;

    // #538: container for additional verified transports (Tailscale/VPN, USB) beyond the WLAN/LAN
    // endpoint already shown by `endpoint` above; populated dynamically by renderTransportOverview().
    private ViewGroup transportOverviewPanel;
    private long transportOverviewGeneration;
    private TextView webhookStatus;
    private TextView webhookTailnetHint;
    private View webhookStatusPanel;
    private View adviceBanner;
    // #764: the warning cards (W1, W3, W4, W5); the force card (W2) is below.
    private KeepADBWarningState.Snapshot warningSnapshot;
    private KeepADBWarningState.Snapshot dismissedSnapshot;
    private KeepADBWarningState.Card dismissedCard;
    private boolean returningFromWarnings;
    private View warningSystem;
    private View warningLessSecure;
    private View warningPaused;
    private View warningLimited;
    // #763: permanent warning card while the force mode is on.
    private View forceWarningPanel;
    private TextView forceWarningText;
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
        // #761: the first time (and again after a newer assistant version), the setup assistant
        // takes the place of the home screen; it opens the home screen when it is closed. The
        // existing/new decision is stored here, before anything else on this screen writes.
        if (!getIntent().getBooleanExtra(EXTRA_SKIP_ASSISTANT_ONCE, false)
                && KeepADBOnboarding.shouldAutoStart(this)) {
            KeepADBOnboarding.isExistingInstall(this);
            startActivity(OnboardingActivity.autoStartIntent(this));
            handedOverToAssistant = true;
            finish();
            return;
        }
        // #778: also after the hand-over check (the marker is ignored by it either way).
        KeepADBBssidHistory.discardLegacyOnce(this);
        // #764: the dismiss flags of the removed home cards are of no use any more. Only after the
        // hand-over check above (it freezes the existing/new decision these keys would feed).
        KeepADBPreferences.removeObsoleteHomeCardKeys(this);
        setContentView(R.layout.activity_main);
        // #324: keep header and content clear of the system bars under forced edge-to-edge.
        KeepADBWindowInsets.apply(
                getWindow(), findViewById(R.id.header_bar), findViewById(R.id.content_scroll));
        toggle = findViewById(R.id.toggle);
        keepAliveToggle = findViewById(R.id.keep_alive_toggle);
        keepAliveSubtext = findViewById(R.id.keep_alive_subtext);
        status = findViewById(R.id.status);
        endpoint = findViewById(R.id.endpoint);
        tailscaleStatus = findViewById(R.id.tailscale_status);

        transportOverviewPanel = findViewById(R.id.transport_overview_panel);
        webhookStatus = findViewById(R.id.webhook_status);
        webhookTailnetHint = findViewById(R.id.webhook_tailnet_hint);
        webhookStatusPanel = findViewById(R.id.webhook_status_panel);
        warningSystem = findViewById(R.id.warning_system);
        warningLessSecure = findViewById(R.id.warning_less_secure);
        warningPaused = findViewById(R.id.warning_paused);
        warningLimited = findViewById(R.id.warning_limited);
        adviceBanner = findViewById(R.id.advice_banner);
        forceWarningPanel = findViewById(R.id.force_warning_panel);
        forceWarningText = findViewById(R.id.force_warning_text);
        findViewById(R.id.btn_force_end).setOnClickListener(v -> {
            if (KeepADBForceMode.endNow(this)) KeepADBForceNotice.showEndedToast(this);
            refresh();
        });
        findViewById(R.id.btn_force_settings).setOnClickListener(v -> {
            Intent intent = new Intent(this, SettingsActivity.class);
            intent.putExtra(SettingsActivity.EXTRA_FOCUS_FORCE, true);
            startActivity(intent);
        });
        settingsMenu = new KeepADBSettingsMenu(this, findViewById(R.id.btn_open_settings));
        findViewById(R.id.btn_open_settings).setOnClickListener(v -> settingsMenu.show());
        findViewById(R.id.btn_dismiss_advice_banner).setOnClickListener(v -> {
            KeepADBPreferences.setAdviceBannerVisible(this, false);
            updateAdviceBannerVisibility();
        });

        findViewById(R.id.btn_security_warnings).setOnClickListener(v -> {
            returningFromWarnings = true;
            startActivity(new Intent(this, WarningsActivity.class));
        });
        findViewById(R.id.warning_undo).setOnClickListener(v -> {
            if (dismissedCard != null && KeepADBWarningState.undo(this, dismissedCard, dismissedSnapshot)) {
                KeepADBWarningState.Card restored = dismissedCard;
                dismissedCard = null;
                renderWarnings();
                View card = warningCardView(restored);
                if (card.getVisibility() == View.VISIBLE) focusWarningView(card);
                else focusWarningView(findViewById(R.id.btn_open_settings));
            }
        });
        findViewById(R.id.warning_mute).setOnClickListener(v -> chooseWarningMutes());
        if (savedInstanceState != null) {
            restoreWarningFeedback(savedInstanceState);
            returningFromWarnings = savedInstanceState.getBoolean("warnings_return_to_triangle");
        }
        updateAdviceBannerVisibility();

        // #764: no setup cards here any more. What is missing is shown as a warning (see
        // renderWarnings) and the assistant asks for it, including the notification permission,
        // on a deliberate tap (#501).

        // OnClick fires only for user interaction, unlike OnCheckedChanged during refresh().
        toggle.setOnClickListener(v -> {
            // #318: the desired value comes from the shared click semantics in KeepADB, not from
            // the view's own checked state, so this switch, the tile and the widget request the
            // same thing for the same state. refresh() below re-renders the switch from the real
            // adb_wifi_enabled value either way.
            boolean want = KeepADB.desiredOnForClick(KeepADB.getState(this));
            KeepADBDiagnostics.event(this, "user_action", "app", want ? "enable" : "disable", "toggle");
            KeepADB.ToggleResult result = KeepADB.setEnabled(this, want, "app");
            if (!result.isSuccess()) {
                toggle.setChecked(!want);
                showToggleErrorToast(result);
            }
            KeepADBService.sync(this);
            refreshUiAndComponents();
        });

        keepAliveToggle.setOnClickListener(v -> {
            boolean wantKeepAlive = keepAliveToggle.isChecked();
            KeepADBDiagnostics.event(this, "user_action", "app", wantKeepAlive ? "enable" : "disable", "keep_alive_toggle");
            KeepADBPreferences.setKeepAliveEnabled(this, wantKeepAlive);
            // #582: an unreadable current state must not be treated as "off, so enable it" -- that
            // is exactly the automatic write this branch exists to gate. Only a positively known
            // "off" triggers it; an unknown read simply skips this immediate enable, same as the
            // other Keep-Alive/service paths that make automatic writes.
            Boolean adbEnabledOrNull = KeepADB.isEnabledOrNull(this, "app");
            if (wantKeepAlive && KeepADBService.isWifiConnected(this)
                    && adbEnabledOrNull != null && !adbEnabledOrNull) {
                // #577: "Keep-Alive ON" means "keep it alive wherever that's permitted", not
                // "switch it on right here regardless of trust" -- so this immediate enable now
                // shares the automatic-enable guard the service itself re-checks
                // (KeepADBService#isAutoEnableStillPermittedIgnoringBackoff: Keep-Alive, Wi-Fi and
                // trust, minus the #496 backoff, see #680 below) instead of a second, parallel
                // trust check. On an untrusted network this falls through to the same trust-prompt path
                // the automatic recheck already uses, rather than writing immediately. The main
                // switch, tile and widget are untouched and keep writing without this gate (#245).
                // #680: deliberately switching Keep-Alive on is a user intent, so it overrides an
                // active #496 recovery backoff (which only throttles automatic retries), like the
                // trust prompt's "allow" (#670); Keep-Alive, Wi-Fi and trust must still hold.
                if (KeepADBService.isAutoEnableStillPermittedIgnoringBackoff(this)) {
                    KeepADB.ToggleResult result = KeepADB.setEnabled(this, true, "app");
                    if (!result.isSuccess()) {
                        showToggleErrorToast(result);
                    }
                } else if (!KeepADBTrustedNetwork.isCurrentNetworkTrusted(this)) {
                    KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(this);
                }
            }
            KeepADBService.sync(this);
            KeepADBWidget.refreshAll(this);
            KeepADBEndpointCoordinator.refresh(this);
            refresh();
        });

        if (savedInstanceState != null && savedInstanceState.getBoolean(STATE_LANGUAGE_PICKER, false)) {
            showLanguageSelectionDialog();
        }
    }

    void showLanguageSelectionDialog() {
        if (activeLanguageSelectionDialog != null && activeLanguageSelectionDialog.isShowing()) return;
        AlertDialog picker = KeepADBLanguagePicker.create(this);
        activeLanguageSelectionDialog = picker;
        picker.setOnDismissListener(dialog -> {
            if (activeLanguageSelectionDialog == dialog) activeLanguageSelectionDialog = null;
        });
        picker.show();
    }

    void openSetupAssistant() {
        startActivity(OnboardingActivity.fullIntent(this));
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (dismissedCard != null) {
            outState.putString("warnings_closed_card", dismissedCard.name());
            java.util.ArrayList<String> reasons = new java.util.ArrayList<>();
            for (KeepADBWarningState.Reason reason : dismissedSnapshot.reasons(dismissedCard)) reasons.add(reason.name());
            outState.putStringArrayList("warnings_closed_reasons", reasons);
            outState.putString("warnings_closed_episode", dismissedSnapshot.forceEpisode);
        }

        outState.putBoolean("warnings_return_to_triangle", returningFromWarnings);
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_LANGUAGE_PICKER,
                activeLanguageSelectionDialog != null && activeLanguageSelectionDialog.isShowing());
    }

    @Override
    protected void onDestroy() {
        if (settingsMenu != null) {
            settingsMenu.dismiss();
            settingsMenu = null;
        }
        if (activeLanguageSelectionDialog != null) activeLanguageSelectionDialog.dismiss();
        activeLanguageSelectionDialog = null;
        super.onDestroy();
    }

    KeepADBSettingsMenu getSettingsMenu() {
        return settingsMenu;
    }

    private android.database.ContentObserver adbContentObserver;

    @Override
    protected void onResume() {
        super.onResume();
        if (handedOverToAssistant) return;
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
                    KeepADBEndpointCoordinator.refresh(MainActivity.this);
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
        KeepADBEndpointCoordinator.setEndpointListener(new KeepADBEndpointCoordinator.EndpointListener() {
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
        // #763: finish an expired force mode before drawing, and redraw when it starts or ends.
        KeepADBForceMode.finishIfExpired(this);
        KeepADBForceMode.setStateListener(this::refresh);
        refresh();
        KeepADBEndpointCoordinator.refresh(this);
        KeepADBUsbReceiver.refresh(this);
    }

    @Override
    protected void onPause() {
        if (handedOverToAssistant) {
            super.onPause();
            return;
        }
        // #224: explicit clear on top of Android's own release when the window loses visibility,
        // per the acceptance requirement that the flag is reliably released on leaving the app.
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        endpointSurfaceActive = false;
        KeepADBForceMode.clearStateListener();
        endpointListenerGeneration++;
        // #538: invalidates any in-flight renderTransportOverview() async result so a snapshot
        // computed for a now-paused screen never applies after the fact (mirrors the
        // endpointListenerGeneration guard above).
        transportOverviewGeneration++;
        KeepADBEndpointCoordinator.clearEndpointListener();
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

    /**
     * #763: the warning card while the force mode is on, with its end ("until 14:30" within today,
     * otherwise with the date) or "no end time". Read from the pure state, so it is gone at the
     * deadline even before the expiry transition ran.
     */
    private void renderForceWarning() {
        KeepADBForceMode.Status force = KeepADBForceMode.status(this);
        forceWarningPanel.setVisibility(force == null ? View.GONE : View.VISIBLE);
        if (force == null) return;
        String text = force.isUnlimited() ? getString(R.string.force_card_text_unlimited)
                : getString(R.string.force_card_text_until, KeepADBForceMode.formatEnd(this, force));
        if (!android.text.TextUtils.equals(forceWarningText.getText(), text)) forceWarningText.setText(text);
    }

    /**
     * #791 (UX concept 5.3): the Keep-Alive line names the protection level that decides where it
     * switches on. While the force mode is on the force card already says "every network", and the
     * stored level would be misleading, so the line stays as it was.
     */
    private void renderKeepAliveSubtext() {
        String base = getString(R.string.keep_alive_subtext);
        boolean force = KeepADBForceMode.isActive(this);
        keepAliveSubtext.setText(force ? base
                : base + "\n" + getString(R.string.networks_level,
                        KeepADBForceNotice.levelLabel(this)));
        // #801: the line that names the level leads to where the level is shown (UX concept 5.3,
        // point 1). While the force mode is on the line names no level and is no tap target.
        if (force) {
            keepAliveSubtext.setOnClickListener(null);
            keepAliveSubtext.setClickable(false);
            keepAliveSubtext.setFocusable(false);
            keepAliveSubtext.setMinHeight(0);
            keepAliveSubtext.setGravity(Gravity.TOP | Gravity.START);
            keepAliveSubtext.setBackground(null);
            keepAliveSubtext.setCompoundDrawablesRelative(null, null, null, null);
            keepAliveSubtext.setAccessibilityDelegate(null);
        } else {
            // A tap target of at least 48 dp with press feedback and the chevron of the other
            // rows that lead on (UX concept 5.3, point 1; touch targets from 48 dp).
            android.util.TypedValue ripple = new android.util.TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
            keepAliveSubtext.setBackgroundResource(ripple.resourceId);
            keepAliveSubtext.setMinHeight((int) (48 * getResources().getDisplayMetrics().density));
            keepAliveSubtext.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            keepAliveSubtext.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0,
                    R.drawable.ic_chevron_right, 0);
            keepAliveSubtext.setFocusable(true);
            // #805: TalkBack announces a button, not a plain text; touch behaviour is unchanged.
            keepAliveSubtext.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override
                public void onInitializeAccessibilityNodeInfo(View host,
                        android.view.accessibility.AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName(android.widget.Button.class.getName());
                }
            });
            keepAliveSubtext.setOnClickListener(v -> startActivity(
                    new Intent(this, SettingsActivity.class)
                            .putExtra(SettingsActivity.EXTRA_FOCUS_NETWORK, true)));
        }
    }

    private void refresh() {
        renderForceWarning();
        KeepADB.State appState = KeepADB.getState(this);
        boolean configured = (appState != KeepADB.State.PERMISSION_MISSING);
        // #318 (acceptance criterion 1): the main switch mirrors Settings.Global.adb_wifi_enabled
        // and nothing else. It is deliberately read straight from the gateway rather than derived
        // from appState, so no future state value can make the switch claim "on" while the system
        // setting is 0. "Keep-Alive is waiting" is a separate dimension and lives in the subtext.
        // #582: an unreadable value is displayed as "off" -- never a false "on" -- same as
        // PERMISSION_MISSING above (getState() already applies that fallback for appState itself;
        // this second, independent read needs its own).
        Boolean adbEnabledOrNull = KeepADB.isEnabledOrNull(this, "app");
        boolean on = configured && adbEnabledOrNull != null && adbEnabledOrNull;
        renderWarnings();
        toggle.setEnabled(configured);
        toggle.setChecked(on);
        clearStatusDecisionEntry();
        if (!configured) {
            // #791: without the permission nothing is known about the endpoint; an address found
            // earlier must not stay on the screen.
            lastEndpointHost = null;
            renderEndpoint();
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
                // #790: a network the user blocked is not "not trusted yet": say so, and lead to
                // the list where the block can be lifted instead of to a question with no answer.
                boolean blocked = KeepADBTrustedNetwork.evaluate(this,
                        KeepADBNetworkIdentity.current(this)).isBlocked();
                status.setText(getString(blocked
                                ? R.string.status_off_keep_alive_blocked_by_user
                                : R.string.status_off_keep_alive_blocked_untrusted)
                        + "\n" + getString(blocked ? R.string.status_tap_to_open_list
                                : R.string.status_tap_to_decide));
                makeStatusDecisionEntry();
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
        renderKeepAliveSubtext();
        refreshWebhookStatus();
        renderTailscaleStatus();
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
     * #764 (UX concept 5.3): the status line "this Wi-Fi isn't trusted" is the shortest way from
     * "why does it not switch on?" to the answer, so it opens the trust decision for the access
     * point the device is on. It only carries the BSSID as a selector, like the notification does;
     * the dialog resolves everything else itself. #790: where the network is already decided
     * (blocked, or trusted in the meantime) there is no question left, so the tap opens the
     * Networks list instead, whose top card is the current network.
     */
    private void makeStatusDecisionEntry() {
        status.setBackgroundResource(R.drawable.bg_card_clickable);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        status.setPadding(pad, pad, pad, pad);
        status.setMinHeight((int) (48 * getResources().getDisplayMetrics().density));
        status.setClickable(true);
        status.setFocusable(true);
        status.setOnClickListener(v -> startActivity(statusEntryIntent()));
    }

    /** Where the tap on the status line leads, read when tapped so a decision in between counts. */
    private Intent statusEntryIntent() {
        KeepADBNetworkIdentity identity = KeepADBNetworkIdentity.current(this);
        KeepADBTrustedNetwork.Decision decision = KeepADBTrustedNetwork.evaluate(this, identity);
        boolean decided = decision.isBlocked()
                || decision == KeepADBTrustedNetwork.Decision.TRUSTED_ACCESS_POINT
                || decision == KeepADBTrustedNetwork.Decision.TRUSTED_NAME;
        return decided ? NetworkListActivity.intent(this, identity.bssid)
                : KeepADBNetworkTrustPrompt.decisionIntent(this, identity.bssid);
    }

    private void clearStatusDecisionEntry() {
        status.setOnClickListener(null);
        status.setClickable(false);
        status.setFocusable(false);
        status.setBackground(null);
        status.setPadding(0, 0, 0, 0);
        status.setMinHeight(0);
    }

    /**
     * #764: the warning cards. The force card (W2) is drawn by {@link #renderForceWarning}; the
     * others come from {@link KeepADBHomeWarnings}, each leading to the assistant's matching step.
     */
    private void renderWarnings() {
        warningSnapshot = KeepADBWarningState.observe(this);
        bindWarning(warningSystem, warningSnapshot.visible.contains(KeepADBWarningState.Card.SYSTEM),
                R.string.home_warning_system_title, getString(R.string.home_warning_system_text),
                R.string.home_warning_action_guide,
                () -> startActivity(KeepADBHomeWarnings.systemIntent(this)));
        bindWarning(warningLessSecure, warningSnapshot.visible.contains(KeepADBWarningState.Card.LESS_SECURE),
                R.string.home_warning_less_secure_title,
                warningReasonText(KeepADBWarningState.Card.LESS_SECURE),
                R.string.home_warning_action_review,
                () -> startActivity(KeepADBWarningState.reviewIntent(this,
                        warningSnapshot.reasons(KeepADBWarningState.Card.LESS_SECURE).iterator().next())));
        bindWarning(warningPaused, warningSnapshot.visible.contains(KeepADBWarningState.Card.PAUSED),
                R.string.home_warning_paused_title, getString(R.string.home_warning_paused_text),
                R.string.home_warning_action_fix,
                () -> startActivity(KeepADBNetworkTrustPrompt.identityUnavailableFixIntent(this)));
        bindWarning(warningLimited, warningSnapshot.visible.contains(KeepADBWarningState.Card.LIMITED),
                R.string.home_warning_limited_title,
                warningReasonText(KeepADBWarningState.Card.LIMITED),
                R.string.home_warning_action_fix,
                () -> startActivity(KeepADBWarningState.reviewIntent(this,
                        warningSnapshot.reasons(KeepADBWarningState.Card.LIMITED).iterator().next())));
        forceWarningPanel.setVisibility(warningSnapshot.visible.contains(KeepADBWarningState.Card.FORCE)
                ? View.VISIBLE : View.GONE);
        bindWarningDismiss(warningSystem, KeepADBWarningState.Card.SYSTEM, R.string.home_warning_system_title);
        bindWarningDismiss(warningLessSecure, KeepADBWarningState.Card.LESS_SECURE, R.string.home_warning_less_secure_title);
        bindWarningDismiss(warningPaused, KeepADBWarningState.Card.PAUSED, R.string.home_warning_paused_title);
        bindWarningDismiss(warningLimited, KeepADBWarningState.Card.LIMITED, R.string.home_warning_limited_title);
        bindWarningDismiss(forceWarningPanel, KeepADBWarningState.Card.FORCE, R.string.force_card_title);
        View triangle = findViewById(R.id.btn_security_warnings);
        int count = warningSnapshot.security().size();
        triangle.setVisibility(count == 0 ? View.GONE : View.VISIBLE);
        triangle.setContentDescription(getString(R.string.warnings_count, count));
        renderWarningFeedback();
        if (returningFromWarnings && endpointSurfaceActive) {
            returningFromWarnings = false;
            View target = count == 0 ? findViewById(R.id.btn_open_settings) : triangle;
            target.post(() -> focusWarningView(target));
        }
    }

    private String warningReasonText(KeepADBWarningState.Card card) {
        java.util.List<String> labels = new java.util.ArrayList<>();
        for (KeepADBWarningState.Reason reason : warningSnapshot.reasons(card)) labels.add(getString(reason.label));
        return android.text.TextUtils.join("\n", labels);
    }

    private void bindWarningDismiss(View card, KeepADBWarningState.Card kind, int title) {
        View close = card.findViewById(kind == KeepADBWarningState.Card.FORCE
                ? R.id.force_warning_dismiss : R.id.home_warning_dismiss);
        close.setContentDescription(getString(R.string.warnings_close, getString(title)));
        KeepADBWarningState.Snapshot shown = warningSnapshot;
        close.setOnClickListener(v -> {
            if (!KeepADBWarningState.dismiss(this, kind, shown)) return;
            dismissedCard = kind;
            dismissedSnapshot = shown;
            renderWarnings();
            focusWarningView(findViewById(R.id.warning_feedback_text));
        });
    }

    private static void focusWarningView(View view) {
        view.setFocusableInTouchMode(true);
        view.requestFocus();
        view.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
    }

    private View warningCardView(KeepADBWarningState.Card card) {
        switch (card) {
            case SYSTEM: return warningSystem;
            case FORCE: return forceWarningPanel;
            case LESS_SECURE: return warningLessSecure;
            case PAUSED: return warningPaused;
            default: return warningLimited;
        }
    }

    private int warningTitle(KeepADBWarningState.Card card) {
        switch (card) {
            case SYSTEM: return R.string.home_warning_system_title;
            case FORCE: return R.string.force_card_title;
            case LESS_SECURE: return R.string.home_warning_less_secure_title;
            case PAUSED: return R.string.home_warning_paused_title;
            default: return R.string.home_warning_limited_title;
        }
    }

    private void renderWarningFeedback() {
        findViewById(R.id.warning_feedback).setVisibility(dismissedCard == null ? View.GONE : View.VISIBLE);
        if (dismissedCard == null) return;
        TextView feedback = findViewById(R.id.warning_feedback_text);
        String text = getString(R.string.warnings_closed, getString(warningTitle(dismissedCard)));
        if (!android.text.TextUtils.equals(feedback.getText(), text)) feedback.setText(text);
        findViewById(R.id.warning_mute).setVisibility(mutableClosedReasons().isEmpty() ? View.GONE : View.VISIBLE);
    }

    private java.util.List<KeepADBWarningState.Reason> mutableClosedReasons() {
        java.util.List<KeepADBWarningState.Reason> reasons = new java.util.ArrayList<>();
        if (dismissedCard == null) return reasons;
        for (KeepADBWarningState.Reason reason : dismissedSnapshot.reasons(dismissedCard)) {
            if (reason.mutable && warningSnapshot.active.contains(reason) && !warningSnapshot.muted.contains(reason)) {
                reasons.add(reason);
            }
        }
        return reasons;
    }

    private void chooseWarningMutes() {
        java.util.List<KeepADBWarningState.Reason> reasons = mutableClosedReasons();
        if (reasons.isEmpty()) return;
        if (reasons.size() == 1) {
            muteClosedReason(reasons.get(0));
            renderWarnings();
            return;
        }
        // A scrolling native list, with no default selections, can grow with the font size.
        android.widget.LinearLayout content = new android.widget.LinearLayout(this);
        content.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(content);
        java.util.List<android.widget.CheckBox> choices = new java.util.ArrayList<>();
        for (KeepADBWarningState.Reason reason : reasons) {
            android.widget.CheckBox choice = new android.widget.CheckBox(this);
            choice.setText(reason.label);
            choice.setMinHeight((int) (48 * getResources().getDisplayMetrics().density));
            android.widget.LinearLayout.LayoutParams params = new android.widget.LinearLayout.LayoutParams(-1, -2);
            params.topMargin = (int) (8 * getResources().getDisplayMetrics().density);
            content.addView(choice, params);
            choices.add(choice);
        }
        Button apply = new Button(this);
        apply.setText(R.string.warnings_mute_selected);
        apply.setMinHeight((int) (48 * getResources().getDisplayMetrics().density));
        apply.setEnabled(false);
        android.widget.LinearLayout.LayoutParams params = new android.widget.LinearLayout.LayoutParams(-1, -2);
        params.topMargin = (int) (8 * getResources().getDisplayMetrics().density);
        content.addView(apply, params);
        for (android.widget.CheckBox choice : choices) choice.setOnCheckedChangeListener((button, checked) -> {
            boolean any = false;
            for (android.widget.CheckBox option : choices) any |= option.isChecked();
            apply.setEnabled(any);
        });
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.warnings_mute)
                .setView(scroll).setNegativeButton(android.R.string.cancel, null).create();
        apply.setOnClickListener(v -> {
            for (int i = 0; i < reasons.size(); i++) if (choices.get(i).isChecked()) muteClosedReason(reasons.get(i));
            dialog.dismiss();
            renderWarnings();
            focusWarningView(findViewById(R.id.warning_feedback_text));
        });
        dialog.show();
    }

    private void muteClosedReason(KeepADBWarningState.Reason reason) {
        KeepADBWarningState.muteClosed(this, reason, dismissedCard, dismissedSnapshot);
    }

    private void restoreWarningFeedback(Bundle saved) {
        String name = saved.getString("warnings_closed_card");
        if (name == null) return;
        try {
            dismissedCard = KeepADBWarningState.Card.valueOf(name);
            java.util.Set<KeepADBWarningState.Reason> reasons = java.util.EnumSet.noneOf(KeepADBWarningState.Reason.class);
            java.util.ArrayList<String> names = saved.getStringArrayList("warnings_closed_reasons");
            if (names != null) for (String reason : names) reasons.add(KeepADBWarningState.Reason.valueOf(reason));
            dismissedSnapshot = new KeepADBWarningState.Snapshot(reasons,
                    java.util.EnumSet.noneOf(KeepADBWarningState.Reason.class),
                    java.util.EnumSet.noneOf(KeepADBWarningState.Card.class), saved.getString("warnings_closed_episode", ""));
        } catch (IllegalArgumentException invalid) { dismissedCard = null; }
    }

    private void bindWarning(View card, boolean visible, int titleRes, String text, int actionRes,
            Runnable onAction) {
        card.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) return;
        ((TextView) card.findViewById(R.id.home_warning_title)).setText(titleRes);
        TextView body = card.findViewById(R.id.home_warning_text);
        if (!android.text.TextUtils.equals(body.getText(), text)) body.setText(text);
        Button action = card.findViewById(R.id.home_warning_action);
        action.setText(actionRes);
        action.setOnClickListener(v -> onAction.run());
    }

    private void refreshWebhookStatus() {
        String url = KeepADBPreferences.getRegisterWebhookUrl(this);
        boolean enabled = KeepADBPreferences.isRegisterWebhookEnabled(this);
        if (!enabled || url == null || url.trim().isEmpty()) {
            webhookStatusPanel.setVisibility(View.GONE);
            webhookTailnetHint.setVisibility(View.GONE);
            return;
        }
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
            // #582: an unreadable value reads as "unavailable", not "searching" -- displaying
            // "searching" would imply wireless debugging is confirmed on, which an unknown read
            // does not establish.
            Boolean adbEnabledOrNull = KeepADB.isEnabledOrNull(MainActivity.this, "app");
            // #791: without the system permission nothing is being searched.
            boolean searching = adbEnabledOrNull != null && adbEnabledOrNull
                    && KeepADB.getState(MainActivity.this) != KeepADB.State.PERMISSION_MISSING;
            endpoint.setText(searching
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

    void refreshUiAndComponents() {
        refresh();
        KeepADBWidget.refreshAll(this);
        KeepADBEndpointCoordinator.refresh(this);
        KeepADBTileService.requestRefresh(this);
        KeepADBUsbReceiver.refresh(this);
    }

    /**
     * #318: a failed toggle used to always blame a missing permission, even when the permission was
     * granted and the Settings.Global write itself was rejected (#309). Report the two causes
     * separately so a rejected write is visible instead of being disguised as a setup problem.
     */
    private void showToggleErrorToast(KeepADB.ToggleResult result) {
        // #795: the cause comes from the toggle result instead of a second permission read.
        // #817: every permission cause (also a SecurityException) points at the grant.
        if (result.isPermissionFailure()) {
            Toast.makeText(this, getString(R.string.permission_error_toast, getPackageName()),
                    Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, getString(R.string.toggle_failed_toast), Toast.LENGTH_LONG).show();
    }

    private void updateAdviceBannerVisibility() {
        boolean visible = KeepADBPreferences.isAdviceBannerVisible(this);
        adviceBanner.setVisibility(visible ? View.VISIBLE : View.GONE);
    }
}
