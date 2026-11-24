package de.hohnepeople.keepadb;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The home screen. #1048: lifecycle, the main and Keep-Alive switches and the coordination of the
 * sections; the warning cards ({@link HomeWarningsController}), the state block ({@link
 * HomeStatusController}), the endpoint and transports ({@link HomeEndpointSection}), the webhook
 * panel ({@link HomeWebhookCard}) and the timer row ({@link KeepADBTimerSection}) draw themselves.
 */
public class MainActivity extends Activity {
    /**
     * #782: set by a single assistant step opened from a notification when it leaves for the home
     * screen: this start does not hand over to the full assistant (the next plain start does).
     */
    static final String EXTRA_SKIP_ASSISTANT_ONCE = "skip_assistant_once";
    /** #889: the feedback bar after closing a card goes away by itself, like a snackbar. */
    static final int WARNING_FEEDBACK_TIMEOUT_MS = HomeWarningsController.FEEDBACK_TIMEOUT_MS;

    private static final String STATE_LANGUAGE_PICKER = "main_language_picker_showing";
    private static final String STATE_CONNECT_HELP = "main_connect_help_showing";
    private AlertDialog activeLanguageSelectionDialog;
    private AlertDialog activeConnectHelpDialog;
    private KeepADBSettingsMenu settingsMenu;

    private Switch toggle;
    private Switch keepAliveToggle;
    private TextView keepAliveSubtext;
    /** #1048: the subtext's tap target, built once instead of on every refresh. */
    private int keepAliveSubtextRipple;
    private View.AccessibilityDelegate keepAliveSubtextButtonRole;
    /** #1000: the timer row below the state. */
    private KeepADBTimerSection timerSection;
    private HomeStatusController statusController;
    private HomeWarningsController warnings;
    private HomeEndpointSection endpointSection;
    private HomeWebhookCard webhookCard;
    // #761: set when onCreate handed over to the setup assistant; nothing of the screen exists then.
    private boolean handedOverToAssistant;
    private final RegisterStateNotifier.Listener webhookStateListener =
            this::refreshWebhookStatus;

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
        KeepADBPreferencesMigrations.removeObsoleteHomeCardKeys(this);
        setContentView(R.layout.activity_main);
        // #324: keep header and content clear of the system bars under forced edge-to-edge.
        KeepADBWindowInsets.apply(
                getWindow(), findViewById(R.id.header_bar), findViewById(R.id.content_scroll));
        toggle = findViewById(R.id.toggle);
        keepAliveToggle = findViewById(R.id.keep_alive_toggle);
        keepAliveSubtext = findViewById(R.id.keep_alive_subtext);
        findViewById(R.id.connect_help).setOnClickListener(v -> showConnectHelp());
        timerSection = new KeepADBTimerSection(this, this::refresh);
        statusController = new HomeStatusController(this, new HomeStatusController.Actions() {
            @Override
            public void retryEnable() {
                applyMainSwitchRequest(true, "retry");
            }

            @Override
            public void showToggleError(KeepADB.ToggleResult result) {
                showToggleErrorToast(result);
            }

            @Override
            public void refreshEverything() {
                refreshUiAndComponents();
            }
        });
        endpointSection = new HomeEndpointSection(this, this::refresh);
        webhookCard = new HomeWebhookCard(this);
        warnings = new HomeWarningsController(this, this::refresh, endpointSection::isSurfaceActive);
        settingsMenu = KeepADBSettingsMenu.forHome(this, findViewById(R.id.btn_open_settings));
        // #941: tap masks or shows this screen, long press opens the privacy switches.
        KeepADBPrivacyToggle.bind(this, this::refreshUiAndComponents);
        findViewById(R.id.btn_open_settings).setOnClickListener(v -> settingsMenu.show());
        warnings.restore(savedInstanceState);

        // #764: no setup cards here any more. What is missing is shown as a warning (see
        // HomeWarningsController) and the assistant asks for it, including the notification
        // permission, on a deliberate tap (#501).

        // OnClick fires only for user interaction, unlike OnCheckedChanged during refresh().
        toggle.setOnClickListener(v -> requestMainSwitchChange());

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
        if (savedInstanceState != null && savedInstanceState.getBoolean(STATE_CONNECT_HELP, false)) {
            showConnectHelp();
        }
    }

    /** #952: how to connect a new computer; the dialog survives a rotation. */
    private void showConnectHelp() {
        if (activeConnectHelpDialog != null && activeConnectHelpDialog.isShowing()) return;
        AlertDialog help = KeepADBConnectHelp.show(this);
        activeConnectHelpDialog = help;
        help.setOnDismissListener(d -> {
            if (activeConnectHelpDialog == d) activeConnectHelpDialog = null;
        });
    }

    /**
     * The user asks the main switch to change: its click, and (#957) the "Retry" button of the home
     * status, which therefore runs exactly the same path.
     */
    private void requestMainSwitchChange() {
        // #318: the desired value comes from the shared click semantics in KeepADB, not from
        // the view's own checked state, so this switch, the tile and the widget request the
        // same thing for the same state. refresh() below re-renders the switch from the real
        // adb_wifi_enabled value either way.
        applyMainSwitchRequest(KeepADB.desiredOnForToggle(KeepADB.getState(this)), "toggle");
    }

    /** The write and the follow-up refreshes the main switch and the "Retry" action share. */
    private void applyMainSwitchRequest(boolean want, String source) {
        KeepADBDiagnostics.event(this, "user_action", "app", want ? "enable" : "disable", source);
        KeepADB.ToggleResult result = KeepADB.setEnabled(this, want, "app");
        if (!result.isSuccess()) {
            toggle.setChecked(!want);
            showToggleErrorToast(result);
        }
        KeepADBService.sync(this);
        refreshUiAndComponents();
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
        if (warnings != null) warnings.save(outState);
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_LANGUAGE_PICKER,
                activeLanguageSelectionDialog != null && activeLanguageSelectionDialog.isShowing());
        outState.putBoolean(STATE_CONNECT_HELP,
                activeConnectHelpDialog != null && activeConnectHelpDialog.isShowing());
    }

    @Override
    protected void onDestroy() {
        if (warnings != null) warnings.destroy();
        if (settingsMenu != null) {
            settingsMenu.dismiss();
            settingsMenu = null;
        }
        if (activeLanguageSelectionDialog != null) activeLanguageSelectionDialog.dismiss();
        activeLanguageSelectionDialog = null;
        if (statusController != null) statusController.destroy();
        if (activeConnectHelpDialog != null) activeConnectHelpDialog.dismiss();
        activeConnectHelpDialog = null;
        super.onDestroy();
    }

    KeepADBSettingsMenu getSettingsMenu() {
        return settingsMenu;
    }

    /** Test hook (#1047): the mute choice while it is open, or null. */
    AlertDialog getActiveWarningMuteDialog() {
        return warnings.activeMuteDialog();
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
        KeepADBPrivacyToggle.update(this);
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
        final long listenerGeneration = endpointSection.resume();
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
        RegisterStateNotifier.setListener(webhookStateListener);
        // #870: a webhook alert that could not be posted (no notification permission yet) is shown
        // once the app is back in front, e.g. after the permission was granted.
        KeepADBWebhookAlert.onAppResumed(this);
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
        // #763: finish an expired force mode before drawing, and redraw when it starts or ends.
        KeepADBForceMode.finishIfExpired(this);
        KeepADBForceMode.setStateListener(this::refresh);
        // #1000: the same for the timer, plus the redraw of its time left.
        KeepADBTimer.finishIfExpired(this);
        KeepADBTimer.setStateListener(this::refresh);
        timerSection.resume();
        refresh();
        KeepADBEndpointCoordinator.refresh(this);
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
        KeepADBForceMode.clearStateListener();
        KeepADBTimer.clearStateListener();
        timerSection.pause();
        // #538: the endpoint callbacks and an in-flight transport snapshot no longer draw.
        endpointSection.pause();
        KeepADBEndpointCoordinator.clearEndpointListener();
        RegisterStateNotifier.clearListener(webhookStateListener);
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
     * #791 (UX concept 5.3): the Keep-Alive line names the protection level that decides where it
     * switches on. While the force mode is on the force card already says "every network", and the
     * stored level would be misleading, so the line stays as it was.
     */
    private void renderKeepAliveSubtext() {
        String base = getString(R.string.keep_alive_subtext);
        boolean force = KeepADBForceMode.isActive(this);
        String text = force ? base
                : base + "\n" + getString(R.string.networks_level, KeepADBForceNotice.levelLabel(this));
        if (!android.text.TextUtils.equals(keepAliveSubtext.getText(), text)) keepAliveSubtext.setText(text);
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
            keepAliveSubtextRipple = 0;
        } else if (keepAliveSubtextRipple == 0) {
            // A tap target of at least 48 dp with press feedback and the chevron of the other
            // rows that lead on (UX concept 5.3, point 1; touch targets from 48 dp). #1048: set up
            // once per change into this state, not on every refresh.
            android.util.TypedValue ripple = new android.util.TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
            keepAliveSubtextRipple = ripple.resourceId;
            keepAliveSubtext.setBackgroundResource(keepAliveSubtextRipple);
            keepAliveSubtext.setMinHeight((int) (48 * getResources().getDisplayMetrics().density));
            keepAliveSubtext.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            keepAliveSubtext.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0,
                    R.drawable.ic_chevron_right, 0);
            keepAliveSubtext.setFocusable(true);
            // #805: TalkBack announces a button, not a plain text; touch behaviour is unchanged.
            if (keepAliveSubtextButtonRole == null) {
                keepAliveSubtextButtonRole = new View.AccessibilityDelegate() {
                    @Override
                    public void onInitializeAccessibilityNodeInfo(View host,
                            android.view.accessibility.AccessibilityNodeInfo info) {
                        super.onInitializeAccessibilityNodeInfo(host, info);
                        info.setClassName(android.widget.Button.class.getName());
                    }
                };
            }
            keepAliveSubtext.setAccessibilityDelegate(keepAliveSubtextButtonRole);
            // #959: the line leads to the protection level, now chosen on the Protection page.
            keepAliveSubtext.setOnClickListener(v -> startActivity(
                    ProtectionActivity.levelIntent(this)));
        }
    }

    private void refresh() {
        warnings.renderForceWarning();
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
        warnings.render();
        toggle.setEnabled(configured);
        toggle.setChecked(on);
        if (!configured) {
            // #791: without the permission nothing is known about the endpoint; an address found
            // earlier must not stay on the screen.
            endpointSection.forget();
        }
        statusController.render(appState, configured);
        timerSection.render(configured, on, appState == KeepADB.State.OFF_KEEP_ALIVE_WAITING);
        keepAliveToggle.setEnabled(configured);
        keepAliveToggle.setChecked(KeepADBPreferences.isKeepAliveEnabled(this));
        renderKeepAliveSubtext();
        refreshWebhookStatus();
        renderTransportOverview();
    }

    private void refreshWebhookStatus() {
        webhookCard.render();
    }

    private void postEndpointAvailable(long listenerGeneration, String host, int port) {
        endpointSection.postAvailable(listenerGeneration, host, port);
    }

    private void postEndpointUnavailable(long listenerGeneration) {
        endpointSection.postUnavailable(listenerGeneration);
    }

    /** #538: the verified transports below the endpoint, computed off the main thread. */
    private void renderTransportOverview() {
        endpointSection.renderTransportOverview();
    }

    void refreshUiAndComponents() {
        refresh();
        KeepADBWidget.refreshAll(this);
        KeepADBEndpointCoordinator.refresh(this);
        KeepADBTileService.requestRefresh(this);
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
}
