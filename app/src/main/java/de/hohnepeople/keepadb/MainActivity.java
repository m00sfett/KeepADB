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
    private static final String STATE_CONNECT_HELP = "main_connect_help_showing";
    private AlertDialog activeLanguageSelectionDialog;
    private AlertDialog activeConnectHelpDialog;
    private KeepADBSettingsMenu settingsMenu;

    private Switch toggle;
    private Switch keepAliveToggle;
    private TextView keepAliveSubtext;
    private TextView status;
    private View statusReasonRow;
    private TextView statusReason;
    private View statusReasonInfo;
    private Button statusAction;
    /** #1000: the timer row below the state. */
    private KeepADBTimerSection timerSection;
    private AlertDialog activeStatusDetailsDialog;
    /** #1047: the mute choice, dismissed with the activity so it cannot leak its window. */
    private AlertDialog activeWarningMuteDialog;
    private View endpointRow;
    private View endpointCopyIcon;
    private TextView endpoint;
    // #761: set when onCreate handed over to the setup assistant; nothing of the screen exists then.
    private boolean handedOverToAssistant;

    // #538: container for additional verified transports (VPN) beyond the WLAN/LAN
    // endpoint already shown by `endpoint` above; populated dynamically by renderTransportOverview().
    private ViewGroup transportOverviewPanel;
    private long transportOverviewGeneration;
    private TextView webhookStatus;
    private View webhookStatusPanel;
    private Button webhookStatusAction;
    private View webhookStatusDetails;
    private TextView webhookUrlValue;
    private TextView webhookEndpointValue;
    private TextView webhookLastReported;
    private View webhookUrlCopy;
    private View webhookEndpointCopy;
    /** #1036 review: the real values behind the rows as rendered; a tap copies exactly these. */
    private String webhookUrlCopyValue;
    private String webhookEndpointCopyValue;
    private final RegisterStateNotifier.Listener webhookStateListener =
            this::refreshWebhookStatus;
    // #764: the warning cards (W1, W3, W4, W5); the force card (W2) is below.
    private KeepADBWarningState.Snapshot warningSnapshot;
    private KeepADBWarningState.Snapshot dismissedSnapshot;
    private KeepADBWarningState.Card dismissedCard;
    /** #889: the feedback bar after closing a card goes away by itself, like a snackbar. */
    static final int WARNING_FEEDBACK_TIMEOUT_MS = 10_000;
    private final Runnable expireWarningFeedback = () -> {
        dismissedCard = null;
        renderWarningFeedback();
    };
    private boolean returningFromWarnings;
    private View warningSystem;
    private View warningLessSecure;
    private View warningPaused;
    private View warningLimited;
    // #763: permanent warning card while the force mode is on.
    private View forceWarningPanel;
    private TextView forceWarningText;
    /**
     * #963: the warnings whose explanation is open. A warning that is not shown is not in the set,
     * so one that goes away and comes back starts collapsed; the set survives the periodic refresh
     * (nothing there touches it) and a rotation ({@link #STATE_EXPANDED_WARNINGS}).
     */
    private final java.util.EnumSet<KeepADBWarningState.Card> expandedWarnings =
            java.util.EnumSet.noneOf(KeepADBWarningState.Card.class);
    private static final String STATE_EXPANDED_WARNINGS = "warnings_expanded";
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
        KeepADBPreferencesMigrations.removeObsoleteHomeCardKeys(this);
        setContentView(R.layout.activity_main);
        // #324: keep header and content clear of the system bars under forced edge-to-edge.
        KeepADBWindowInsets.apply(
                getWindow(), findViewById(R.id.header_bar), findViewById(R.id.content_scroll));
        toggle = findViewById(R.id.toggle);
        keepAliveToggle = findViewById(R.id.keep_alive_toggle);
        keepAliveSubtext = findViewById(R.id.keep_alive_subtext);
        status = findViewById(R.id.status);
        statusReasonRow = findViewById(R.id.status_reason_row);
        statusReason = findViewById(R.id.status_reason);
        statusReasonInfo = findViewById(R.id.status_reason_info);
        statusAction = findViewById(R.id.status_action);
        endpointRow = findViewById(R.id.endpoint_row);
        endpointCopyIcon = findViewById(R.id.endpoint_copy_icon);
        endpoint = findViewById(R.id.endpoint);
        findViewById(R.id.connect_help).setOnClickListener(v -> showConnectHelp());
        timerSection = new KeepADBTimerSection(this, this::refresh);

        transportOverviewPanel = findViewById(R.id.transport_overview_panel);
        webhookStatus = findViewById(R.id.webhook_status);
        webhookStatusPanel = findViewById(R.id.webhook_status_panel);
        webhookStatusAction = findViewById(R.id.webhook_status_action);
        webhookStatusDetails = findViewById(R.id.webhook_status_details);
        webhookUrlValue = findViewById(R.id.webhook_url_value);
        webhookEndpointValue = findViewById(R.id.webhook_endpoint_value);
        webhookLastReported = findViewById(R.id.webhook_last_reported);
        webhookUrlCopy = findViewById(R.id.webhook_url_copy);
        webhookEndpointCopy = findViewById(R.id.webhook_endpoint_copy);
        webhookUrlCopy.setOnClickListener(v -> KeepADBWebhookStatusText.copy(this,
                KeepADBWebhookStatusText.URL_CLIP_LABEL, webhookUrlCopyValue, "webhook_url"));
        webhookEndpointCopy.setOnClickListener(v -> KeepADBWebhookStatusText.copy(this,
                KeepADBWebhookStatusText.ENDPOINT_CLIP_LABEL, webhookEndpointCopyValue,
                "webhook_endpoint"));
        webhookStatusAction.setOnClickListener(v -> openWebhookSettings());
        warningSystem = findViewById(R.id.warning_system);
        warningLessSecure = findViewById(R.id.warning_less_secure);
        warningPaused = findViewById(R.id.warning_paused);
        warningLimited = findViewById(R.id.warning_limited);
        // #906: swiping the feedback bar away ends it early, like its timeout; the card stays closed.
        ((KeepADBSwipeDismissLayout) findViewById(R.id.warning_feedback))
                .setOnDismissListener(expireWarningFeedback::run);
        forceWarningPanel = findViewById(R.id.force_warning_panel);
        forceWarningText = findViewById(R.id.force_warning_text);
        findViewById(R.id.btn_force_end).setOnClickListener(v -> {
            if (KeepADBForceMode.endNow(this)) KeepADBForceNotice.showEndedToast(this);
            refresh();
        });
        findViewById(R.id.btn_force_settings).setOnClickListener(v -> {
            // #959: the force row is on the Protection page.
            startActivity(ProtectionActivity.forceIntent(this));
        });
        settingsMenu = KeepADBSettingsMenu.forHome(this, findViewById(R.id.btn_open_settings));
        // #941: tap masks or shows this screen, long press opens the privacy switches.
        KeepADBPrivacyToggle.bind(this, this::refreshUiAndComponents);
        findViewById(R.id.btn_open_settings).setOnClickListener(v -> settingsMenu.show());

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
            restoreExpandedWarnings(savedInstanceState);
            returningFromWarnings = savedInstanceState.getBoolean("warnings_return_to_triangle");
            if (dismissedCard != null) armWarningFeedbackTimeout();
        }

        // #764: no setup cards here any more. What is missing is shown as a warning (see
        // renderWarnings) and the assistant asks for it, including the notification permission,
        // on a deliberate tap (#501).

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
        if (dismissedCard != null) {
            outState.putString("warnings_closed_card", dismissedCard.name());
            java.util.ArrayList<String> reasons = new java.util.ArrayList<>();
            for (KeepADBWarningState.Reason reason : dismissedSnapshot.reasons(dismissedCard)) reasons.add(reason.name());
            outState.putStringArrayList("warnings_closed_reasons", reasons);
            outState.putString("warnings_closed_episode", dismissedSnapshot.forceEpisode);
            Bundle episodes = new Bundle();
            for (KeepADBWarningState.Reason reason : dismissedSnapshot.reasons(dismissedCard)) {
                episodes.putString(reason.name(), dismissedSnapshot.reasonEpisodes.get(reason));
            }
            outState.putBundle("warnings_closed_reason_episodes", episodes);
        }

        outState.putBoolean("warnings_return_to_triangle", returningFromWarnings);
        java.util.ArrayList<String> expanded = new java.util.ArrayList<>();
        for (KeepADBWarningState.Card card : expandedWarnings) expanded.add(card.name());
        outState.putStringArrayList(STATE_EXPANDED_WARNINGS, expanded);
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_LANGUAGE_PICKER,
                activeLanguageSelectionDialog != null && activeLanguageSelectionDialog.isShowing());
        outState.putBoolean(STATE_CONNECT_HELP,
                activeConnectHelpDialog != null && activeConnectHelpDialog.isShowing());
    }

    @Override
    protected void onDestroy() {
        View feedbackBar = findViewById(R.id.warning_feedback);
        if (feedbackBar != null) feedbackBar.removeCallbacks(expireWarningFeedback);
        // #906: a swipe-out animation still running must not dismiss anything after destruction.
        if (feedbackBar instanceof KeepADBSwipeDismissLayout) ((KeepADBSwipeDismissLayout) feedbackBar).resetSwipe();
        if (settingsMenu != null) {
            settingsMenu.dismiss();
            settingsMenu = null;
        }
        if (activeLanguageSelectionDialog != null) activeLanguageSelectionDialog.dismiss();
        activeLanguageSelectionDialog = null;
        if (activeStatusDetailsDialog != null) activeStatusDetailsDialog.dismiss();
        activeStatusDetailsDialog = null;
        if (activeConnectHelpDialog != null) activeConnectHelpDialog.dismiss();
        activeConnectHelpDialog = null;
        if (activeWarningMuteDialog != null) activeWarningMuteDialog.dismiss();
        activeWarningMuteDialog = null;
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
        endpointSurfaceActive = false;
        KeepADBForceMode.clearStateListener();
        KeepADBTimer.clearStateListener();
        timerSection.pause();
        endpointListenerGeneration++;
        // #538: invalidates any in-flight renderTransportOverview() async result so a snapshot
        // computed for a now-paused screen never applies after the fact (mirrors the
        // endpointListenerGeneration guard above).
        transportOverviewGeneration++;
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
        // #963: the remaining time is state, not explanation: it stays in the collapsed row.
        String status = force.isUnlimited() ? getString(R.string.force_status_unlimited)
                : getString(R.string.force_status_until, KeepADBForceMode.formatEnd(this, force));
        String title = getString(R.string.force_card_title);
        android.text.SpannableStringBuilder line = new android.text.SpannableStringBuilder(title)
                .append('\n').append(status);
        int from = title.length() + 1;
        line.setSpan(new android.text.style.RelativeSizeSpan(0.8f), from, line.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        line.setSpan(new android.text.style.ForegroundColorSpan(getColor(R.color.night_muted)),
                from, line.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ((KeepADBWarningRow) forceWarningPanel.findViewById(R.id.warning_row))
                .setTitle(line, title + ", " + status);
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
            // #959: the line leads to the protection level, now chosen on the Protection page.
            keepAliveSubtext.setOnClickListener(v -> startActivity(
                    ProtectionActivity.levelIntent(this)));
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
        if (!configured) {
            // #791: without the permission nothing is known about the endpoint; an address found
            // earlier must not stay on the screen.
            lastEndpointHost = null;
            renderEndpoint();
        }
        renderHomeStatus(resolveHomeStatus(appState, configured));
        timerSection.render(configured, on, appState == KeepADB.State.OFF_KEEP_ALIVE_WAITING);
        keepAliveToggle.setEnabled(configured);
        keepAliveToggle.setChecked(KeepADBPreferences.isKeepAliveEnabled(this));
        renderKeepAliveSubtext();
        refreshWebhookStatus();
        renderTransportOverview();
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
        /** #997: Wi-Fi is connected, but it is open or behind a captive portal. */
        BLOCKED_OPEN_NETWORK,
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
        if (reason == KeepADBTrustedNetwork.BlockReason.OPEN_NETWORK) {
            return KeepAliveWaitingDetail.BLOCKED_OPEN_NETWORK;
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
     * #957: reads what {@link KeepADBHomeStatus} needs from the platform and the network rules; the
     * mapping itself is pure. The reads happen only where they matter, as before.
     */
    private KeepADBHomeStatus.Model resolveHomeStatus(KeepADB.State appState, boolean configured) {
        boolean pending = configured && KeepADB.isTogglePending();
        KeepAliveWaitingDetail waiting = null;
        KeepADBHomeStatus.Network network = KeepADBHomeStatus.Network.NONE;
        if (!configured || pending) {
            // The state alone says it.
        } else if (appState == KeepADB.State.OFF_KEEP_ALIVE_WAITING) {
            // #458: distinguish real "no Wi-Fi yet" waiting from Keep-Alive being blocked by an
            // untrusted network or an unreadable network identity, so the status never looks
            // like KeepADB simply failed to notice a live Wi-Fi connection.
            waiting = resolveKeepAliveWaitingDetail(this);
            if (waiting == KeepAliveWaitingDetail.BLOCKED_UNTRUSTED_NETWORK) {
                network = currentNetworkIsBlocked()
                        ? KeepADBHomeStatus.Network.BLOCKED : KeepADBHomeStatus.Network.UNDECIDED;
            }
        } else if (appState == KeepADB.State.ENABLED_DISCONNECTED
                || appState == KeepADB.State.ENABLED_CONNECTED) {
            // #949: while Wireless Debugging is on, Keep-Alive is enabled and the device sits on a
            // Wi-Fi that is not trusted (or blocked), the status says so and leads to the decision
            // (or the list). Without it, joining a public Wi-Fi with debugging already on left
            // Home without any way in.
            if (KeepADBPreferences.isKeepAliveEnabled(this)
                    && KeepADBTrustedNetwork.getBlockReason(this)
                            == KeepADBTrustedNetwork.BlockReason.UNTRUSTED_NETWORK) {
                network = currentNetworkIsBlocked()
                        ? KeepADBHomeStatus.Network.BLOCKED : KeepADBHomeStatus.Network.UNDECIDED;
            }
        }
        return KeepADBHomeStatus.of(appState, pending, waiting, network,
                KeepADBService.isWifiConnected(this));
    }

    /** #790: a network the user blocked is not "not trusted yet". */
    private boolean currentNetworkIsBlocked() {
        return KeepADBTrustedNetwork.evaluate(this, KeepADBNetworkIdentity.current(this)).isBlocked();
    }

    /**
     * #957: state line, reason line and action button. The state and the reason sit in one polite
     * live region (see view_home_status.xml), so a change of either is announced as one message;
     * text that did not change is not set again, so the periodic refresh stays silent. The reason
     * is a tap target only where a long explanation exists, and then says so: an info mark, the
     * role of a button and "Show details" as its click label for TalkBack.
     */
    private void renderHomeStatus(KeepADBHomeStatus.Model model) {
        setTextIfChanged(status, getString(model.stateRes));
        if (!model.hasReason()) {
            statusReasonRow.setVisibility(View.GONE);
            clearStatusReasonDetails();
        } else {
            statusReasonRow.setVisibility(View.VISIBLE);
            setTextIfChanged(statusReason, getString(model.reasonRes));
            if (model.hasExplanation()) {
                bindStatusReasonDetails(model.explanationRes);
            } else {
                clearStatusReasonDetails();
            }
        }
        if (model.action == KeepADBHomeStatus.Action.NONE) {
            statusAction.setVisibility(View.GONE);
            statusAction.setOnClickListener(null);
        } else {
            statusAction.setText(model.action.labelRes);
            statusAction.setVisibility(View.VISIBLE);
            statusAction.setOnClickListener(v -> runStatusAction(model.action));
        }
    }

    private static void setTextIfChanged(TextView view, CharSequence text) {
        if (!android.text.TextUtils.equals(view.getText(), text)) view.setText(text);
    }

    private void bindStatusReasonDetails(int explanationRes) {
        statusReasonInfo.setVisibility(View.VISIBLE);
        statusReasonRow.setMinimumHeight((int) (48 * getResources().getDisplayMetrics().density));
        statusReasonRow.setClickable(true);
        statusReasonRow.setFocusable(true);
        statusReasonRow.setOnClickListener(v -> showStatusDetails(explanationRes));
        statusReasonRow.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host,
                    android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
                info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK,
                        getString(R.string.home_reason_details_action)));
            }
        });
    }

    private void clearStatusReasonDetails() {
        statusReasonInfo.setVisibility(View.GONE);
        statusReasonRow.setMinimumHeight(0);
        statusReasonRow.setOnClickListener(null);
        statusReasonRow.setClickable(false);
        statusReasonRow.setFocusable(false);
        statusReasonRow.setAccessibilityDelegate(null);
    }

    /** The long sentence of the reason, as before the split, in a plain dialog with one button. */
    private void showStatusDetails(int explanationRes) {
        if (activeStatusDetailsDialog != null) activeStatusDetailsDialog.dismiss();
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setMessage(explanationRes)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog.setOnDismissListener(d -> {
            if (activeStatusDetailsDialog == d) activeStatusDetailsDialog = null;
        });
        activeStatusDetailsDialog = dialog;
        dialog.show();
    }

    /**
     * The targets of the action button: the decision or the Networks list as the status line led
     * before (#764, #790), "Fix" where the "Keep-Alive paused" warning leads, "Retry" the enable path of
     * the main switch and "Instructions" where the permission warning leads.
     */
    private void runStatusAction(KeepADBHomeStatus.Action action) {
        switch (action) {
            case DECIDE:
            case NETWORKS:
                startActivity(statusEntryIntent());
                break;
            case FIX:
                startActivity(KeepADBNetworkTrustPrompt.identityUnavailableFixIntent(this));
                break;
            case RETRY:
                // Retry only ever enables: the state may have flipped to on since it was drawn, and
                // the switch's click semantics would then switch it off.
                applyMainSwitchRequest(true, "retry");
                break;
            case INSTRUCTIONS:
                startActivity(KeepADBHomeWarnings.systemIntent(this));
                break;
            case RESTART:
                // #1027: a plain manual off and, after a short pause, a manual on.
                KeepADB.ToggleResult restart = KeepADBRestart.restart(this);
                if (!restart.isSuccess()) showToggleErrorToast(restart);
                refreshUiAndComponents();
                break;
            default:
                break;
        }
    }

    /**
     * #764 (UX concept 5.3): "this Wi-Fi isn't trusted" is the shortest way from "why does it not
     * switch on?" to the answer, so its action opens the trust decision for the access point the
     * device is on. It only carries the BSSID as a selector, like the notification does; the
     * dialog resolves everything else itself. #790: where the network is already decided
     * (blocked, or trusted in the meantime) there is no question left, so the tap opens the
     * Networks list instead, whose top card is the current network. Read when tapped, so a
     * decision in between counts.
     */
    private Intent statusEntryIntent() {
        KeepADBNetworkIdentity identity = KeepADBNetworkIdentity.current(this);
        KeepADBTrustedNetwork.Decision decision = KeepADBTrustedNetwork.evaluate(this, identity);
        boolean decided = decision.isBlocked()
                || decision == KeepADBTrustedNetwork.Decision.TRUSTED_ACCESS_POINT
                || decision == KeepADBTrustedNetwork.Decision.TRUSTED_NAME;
        // #949: an undecided network the app has no record of (no prompt was raised for it yet)
        // would only end in "expired"; the list's current card asks the same question.
        boolean askable = !decided && KeepADBNetworkDecision.resolve(this, identity.bssid).status
                == KeepADBNetworkDecision.Status.PENDING;
        return askable ? KeepADBNetworkTrustPrompt.decisionIntent(this, identity.bssid)
                : NetworkListActivity.intent(this, identity.bssid);
    }

    /**
     * #764: the warning cards. The force card (W2) is drawn by {@link #renderForceWarning}; the
     * others come from {@link KeepADBHomeWarnings}, each leading to the assistant's matching step.
     */
    private void renderWarnings() {
        warningSnapshot = KeepADBWarningState.observe(this);
        bindWarning(warningSystem, KeepADBWarningState.Card.SYSTEM,
                R.string.home_warning_system_title, getString(R.string.home_warning_system_text),
                R.string.home_warning_action_guide,
                () -> startActivity(KeepADBHomeWarnings.systemIntent(this)));
        bindWarning(warningLessSecure, KeepADBWarningState.Card.LESS_SECURE,
                R.string.home_warning_less_secure_title,
                warningReasonText(KeepADBWarningState.Card.LESS_SECURE),
                R.string.home_warning_action_review,
                () -> startActivity(KeepADBWarningState.reviewIntent(this,
                        warningSnapshot.reasons(KeepADBWarningState.Card.LESS_SECURE).iterator().next())));
        bindWarning(warningPaused, KeepADBWarningState.Card.PAUSED,
                R.string.home_warning_paused_title, getString(R.string.home_warning_paused_text),
                R.string.home_warning_action_fix,
                () -> startActivity(KeepADBNetworkTrustPrompt.identityUnavailableFixIntent(this)));
        bindWarning(warningLimited, KeepADBWarningState.Card.LIMITED,
                R.string.home_warning_limited_title,
                warningReasonText(KeepADBWarningState.Card.LIMITED),
                R.string.home_warning_action_fix,
                () -> startActivity(KeepADBWarningState.reviewIntent(this,
                        warningSnapshot.reasons(KeepADBWarningState.Card.LIMITED).iterator().next())));
        forceWarningPanel.setVisibility(warningSnapshot.visible.contains(KeepADBWarningState.Card.FORCE)
                ? View.VISIBLE : View.GONE);
        bindWarningExpansion(forceWarningPanel, KeepADBWarningState.Card.FORCE);
        bindWarningDismiss(warningSystem, KeepADBWarningState.Card.SYSTEM, R.string.home_warning_system_title);
        bindWarningDismiss(warningLessSecure, KeepADBWarningState.Card.LESS_SECURE, R.string.home_warning_less_secure_title);
        bindWarningDismiss(warningPaused, KeepADBWarningState.Card.PAUSED, R.string.home_warning_paused_title);
        bindWarningDismiss(warningLimited, KeepADBWarningState.Card.LIMITED, R.string.home_warning_limited_title);
        bindWarningDismiss(forceWarningPanel, KeepADBWarningState.Card.FORCE, R.string.force_card_title);
        View triangle = findViewById(R.id.btn_security_warnings);
        int count = warningSnapshot.security().size();
        triangle.setVisibility(count == 0 ? View.GONE : View.VISIBLE);
        triangle.setContentDescription(getResources().getQuantityString(R.plurals.warnings_count, count, count));
        renderWarningFeedback();
        if (returningFromWarnings && endpointSurfaceActive) {
            returningFromWarnings = false;
            View target = count == 0 ? findViewById(R.id.btn_open_settings) : triangle;
            target.post(() -> focusWarningView(target));
        }
    }

    private String warningReasonText(KeepADBWarningState.Card card) {
        java.util.List<String> labels = new java.util.ArrayList<>();
        if (card == KeepADBWarningState.Card.LIMITED) {
            labels.add(getString(R.string.home_warning_limited_text));
        }
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
            // #906: a swipe of the previous bar still animating must not dismiss this new one.
            ((KeepADBSwipeDismissLayout) findViewById(R.id.warning_feedback)).resetSwipe();
            dismissedCard = kind;
            dismissedSnapshot = shown;
            renderWarnings();
            focusWarningView(findViewById(R.id.warning_feedback_text));
            armWarningFeedbackTimeout();
        });
    }

    /**
     * #889: (re)starts the timeout of the feedback bar. Android lengthens it for users who asked
     * for more time to react (accessibility setting), as for any message with controls.
     */
    private void armWarningFeedbackTimeout() {
        View bar = findViewById(R.id.warning_feedback);
        bar.removeCallbacks(expireWarningFeedback);
        android.view.accessibility.AccessibilityManager accessibility =
                getSystemService(android.view.accessibility.AccessibilityManager.class);
        int timeout = accessibility == null ? WARNING_FEEDBACK_TIMEOUT_MS
                : accessibility.getRecommendedTimeoutMillis(WARNING_FEEDBACK_TIMEOUT_MS,
                        android.view.accessibility.AccessibilityManager.FLAG_CONTENT_TEXT
                                | android.view.accessibility.AccessibilityManager.FLAG_CONTENT_CONTROLS);
        bar.postDelayed(expireWarningFeedback, timeout);
    }

    private static void focusWarningView(View view) {
        view.setFocusable(true);
        // Preserve normal first-tap activation after returning keyboard/accessibility focus.
        view.requestFocusFromTouch();
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
        if (dismissedCard == null) {
            findViewById(R.id.warning_feedback).removeCallbacks(expireWarningFeedback);
            // #906: put a swiped-away bar back in place while hidden, never during a drag.
            ((KeepADBSwipeDismissLayout) findViewById(R.id.warning_feedback)).resetSwipe();
            return;
        }
        TextView feedback = findViewById(R.id.warning_feedback_text);
        String text = getString(R.string.warnings_closed, getString(warningTitle(dismissedCard)));
        if (!android.text.TextUtils.equals(feedback.getText(), text)) feedback.setText(text);
        findViewById(R.id.warning_mute).setVisibility(mutableClosedReasons().isEmpty() ? View.GONE : View.VISIBLE);
    }

    private java.util.List<KeepADBWarningState.Reason> mutableClosedReasons() {
        java.util.List<KeepADBWarningState.Reason> reasons = new java.util.ArrayList<>();
        if (dismissedCard == null) return reasons;
        for (KeepADBWarningState.Reason reason : dismissedSnapshot.reasons(dismissedCard)) {
            if (reason.mutable && warningSnapshot.active.contains(reason) && !warningSnapshot.muted.contains(reason)
                    && KeepADBWarningState.sameEpisode(dismissedSnapshot, warningSnapshot, reason)) {
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
        // #905: a custom view gets no dialog inset of its own; use the title's 24 dp so the list
        // and the primary button keep the same margin as the title and the Cancel button.
        int inset = (int) (24 * getResources().getDisplayMetrics().density);
        content.setPadding(inset, 0, inset, 0);
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
        // #896: the app's primary button (muted while nothing is selected), not the platform default.
        Button apply = new Button(this, null, 0, R.style.Widget_KeepADB_Button_Primary);
        apply.setText(R.string.warnings_mute_selected);
        apply.setEnabled(false);
        android.widget.LinearLayout.LayoutParams params = new android.widget.LinearLayout.LayoutParams(-2, -2);
        params.topMargin = (int) (16 * getResources().getDisplayMetrics().density);
        params.gravity = android.view.Gravity.END;
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
        if (activeWarningMuteDialog != null) activeWarningMuteDialog.dismiss();
        activeWarningMuteDialog = dialog;
        dialog.setOnDismissListener(d -> {
            if (activeWarningMuteDialog == d) activeWarningMuteDialog = null;
        });
        dialog.show();
    }

    /** Test hook (#1047): the mute choice while it is open, or null. */
    AlertDialog getActiveWarningMuteDialog() {
        return activeWarningMuteDialog;
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
            java.util.Map<KeepADBWarningState.Reason, String> episodes = new java.util.EnumMap<>(KeepADBWarningState.Reason.class);
            Bundle savedEpisodes = saved.getBundle("warnings_closed_reason_episodes");
            if (savedEpisodes != null) for (KeepADBWarningState.Reason reason : reasons) {
                String episode = savedEpisodes.getString(reason.name());
                if (episode != null) episodes.put(reason, episode);
            }
            dismissedSnapshot = new KeepADBWarningState.Snapshot(reasons,
                    java.util.EnumSet.noneOf(KeepADBWarningState.Reason.class),
                    java.util.EnumSet.noneOf(KeepADBWarningState.Card.class), saved.getString("warnings_closed_episode", ""), episodes);
        } catch (IllegalArgumentException invalid) { dismissedCard = null; }
    }

    private void bindWarning(View card, KeepADBWarningState.Card kind, int titleRes, String text,
            int actionRes, Runnable onAction) {
        boolean visible = warningSnapshot.visible.contains(kind);
        card.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) {
            // #963: a warning that goes away and returns starts collapsed.
            expandedWarnings.remove(kind);
            return;
        }
        String title = getString(titleRes);
        ((KeepADBWarningRow) card.findViewById(R.id.warning_row)).setTitle(title, title);
        TextView body = card.findViewById(R.id.home_warning_text);
        if (!android.text.TextUtils.equals(body.getText(), text)) body.setText(text);
        Button action = card.findViewById(R.id.home_warning_action);
        action.setText(actionRes);
        action.setOnClickListener(v -> onAction.run());
        bindWarningExpansion(card, kind);
    }

    /**
     * #963: a tap on the row (not on its buttons) opens or closes the explanation below it. The
     * state lives in {@link #expandedWarnings}, so a refresh that binds the card again keeps it.
     */
    private void bindWarningExpansion(View card, KeepADBWarningState.Card kind) {
        if (card.getVisibility() != View.VISIBLE) {
            expandedWarnings.remove(kind);
            return;
        }
        KeepADBWarningRow row = card.findViewById(R.id.warning_row);
        row.setOnClickListener(v -> {
            if (!expandedWarnings.remove(kind)) expandedWarnings.add(kind);
            showWarningExpansion(card, kind);
        });
        showWarningExpansion(card, kind);
    }

    private void showWarningExpansion(View card, KeepADBWarningState.Card kind) {
        boolean expanded = expandedWarnings.contains(kind);
        ((KeepADBWarningRow) card.findViewById(R.id.warning_row)).setExpanded(expanded);
        card.findViewById(R.id.warning_details).setVisibility(expanded ? View.VISIBLE : View.GONE);
    }

    private void restoreExpandedWarnings(Bundle saved) {
        java.util.ArrayList<String> names = saved.getStringArrayList(STATE_EXPANDED_WARNINGS);
        if (names == null) return;
        for (String name : names) {
            try {
                expandedWarnings.add(KeepADBWarningState.Card.valueOf(name));
            } catch (IllegalArgumentException ignored) {
                // A name from another version: that warning simply starts collapsed.
            }
        }
    }

    private void refreshWebhookStatus() {
        String url = KeepADBPreferences.getRegisterWebhookUrl(this);
        boolean enabled = KeepADBPreferences.isRegisterWebhookEnabled(this);
        // #981: unconfigured or off stays visible as a short hint with a way to the settings.
        if (!WebhookUrlCodec.isValid(url)) {
            showWebhookSetupHint(R.string.webhook_status_unconfigured,
                    R.string.webhook_status_action_setup);
            return;
        }
        if (!enabled) {
            showWebhookSetupHint(R.string.webhook_status_disabled,
                    R.string.webhook_status_action_open);
            return;
        }
        webhookStatusAction.setVisibility(View.GONE);
        String reportStatus = WebhookReportPrefs.getWebhookLastReportStatus(this);
        String lastReported = KeepADBWebhookStatusText.lastReported(this,
                java.text.DateFormat.MEDIUM, java.text.DateFormat.MEDIUM);
        // #483: display only -- the stored endpoint stays as reported.
        String storedEndpoint = WebhookReportPrefs.getWebhookLastReportedEndpoint(this);
        String lastEndpoint = KeepADBPrivacy.maskEndpointForDisplay(this, storedEndpoint);
        boolean deregistered = WebhookReportPrefs.WEBHOOK_STATUS_DEREGISTERED.equals(reportStatus);
        if (deregistered || lastEndpoint == null || lastEndpoint.trim().isEmpty()) {
            // Distinguish "never reported anything yet" from "was reported, then successfully
            // deregistered" -- both leave no current endpoint, but reusing the same "none yet"
            // text for a just-completed deregistration reads as if one were still pending.
            // #1036 review: a deregistered state shows no endpoint, even a leftover one.
            lastEndpoint = deregistered
                    ? getString(R.string.webhook_status_deregistered)
                    : getString(R.string.webhook_status_no_endpoint);
            storedEndpoint = null;
        }
        // #1036: one row per fact; the copy buttons copy the real values (read when tapped).
        webhookStatus.setVisibility(View.GONE);
        webhookUrlValue.setText(getString(R.string.webhook_status_url,
                KeepADBPrivacy.maskWebhookUrlForDisplay(this, url)));
        webhookEndpointValue.setText(getString(R.string.webhook_status_endpoint, lastEndpoint));
        webhookLastReported.setText(getString(R.string.webhook_status_last, lastReported));
        webhookUrlCopyValue = KeepADBWebhookStatusText.copyableUrl(url);
        webhookEndpointCopyValue = KeepADBWebhookStatusText.copyableEndpoint(storedEndpoint);
        webhookUrlCopy.setVisibility(webhookUrlCopyValue != null ? View.VISIBLE : View.GONE);
        webhookEndpointCopy.setVisibility(
                webhookEndpointCopyValue != null ? View.VISIBLE : View.GONE);
        webhookStatusDetails.setVisibility(View.VISIBLE);
        webhookStatusPanel.setVisibility(View.VISIBLE);
    }

    private void showWebhookSetupHint(int textRes, int actionRes) {
        webhookUrlCopyValue = null;
        webhookEndpointCopyValue = null;
        webhookStatusDetails.setVisibility(View.GONE);
        webhookStatus.setVisibility(View.VISIBLE);
        webhookStatus.setText(textRes);
        webhookStatusAction.setText(actionRes);
        webhookStatusAction.setVisibility(View.VISIBLE);
        webhookStatusPanel.setVisibility(View.VISIBLE);
    }

    /** #981: the Webhook card of Settings, expanded and in view (same as the widget's setup). */
    private void openWebhookSettings() {
        startActivity(new Intent(this, SettingsActivity.class)
                .putExtra(SettingsActivity.EXTRA_FOCUS_WEBHOOK, true));
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
            // #957: no address yet is a status, not a value: secondary color, normal size, and
            // nothing to copy (#951).
            endpoint.setTextColor(getColor(R.color.night_muted));
            endpoint.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
            clearEndpointCopy();
            return;
        }
        endpoint.setText(KeepADBEndpointText.display(MainActivity.this,
                KeepADBPrivacy.maskHostForDisplay(MainActivity.this, lastEndpointHost),
                lastEndpointPort));
        // #957: the address is what people came for: primary color and larger.
        endpoint.setTextColor(getColor(R.color.night_text));
        endpoint.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18);
        bindEndpointCopy();
    }

    /**
     * #951: a tap on the row copies {@code adb connect <host>:<port>}. The real host is used
     * whatever the row shows (a tap is an explicit action); {@code lastEndpointHost} is read when
     * tapped, so a newer endpoint wins over the one the row was bound with.
     */
    private void bindEndpointCopy() {
        endpointCopyIcon.setVisibility(View.VISIBLE);
        endpointRow.setMinimumHeight(dp(48));
        endpointRow.setClickable(true);
        endpointRow.setFocusable(true);
        endpointRow.setOnClickListener(v -> {
            if (lastEndpointHost != null) {
                KeepADBEndpointText.copyAdbConnect(this, lastEndpointHost, lastEndpointPort, "app");
            }
        });
        setCopyClickLabel(endpointRow);
    }

    private void clearEndpointCopy() {
        endpointCopyIcon.setVisibility(View.GONE);
        endpointRow.setMinimumHeight(0);
        endpointRow.setOnClickListener(null);
        endpointRow.setClickable(false);
        endpointRow.setFocusable(false);
        endpointRow.setAccessibilityDelegate(null);
    }

    /**
     * TalkBack announces the row as what a tap does, "copy adb connect command", through the click
     * action's label; the content description is left alone.
     */
    private void setCopyClickLabel(View row) {
        row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host,
                    android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK,
                        getString(R.string.endpoint_copy_action)));
            }
        });
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
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
     * <p>A merely active VPN interface deliberately produces no row here: this panel only ever
     * speaks about transports whose ADB reachability {@link KeepADBTransportOverview} actually
     * verified. KeepADB does not detect, name or advise on any particular VPN product (#877).
     *
     * <p>#548: a VPN row stays suppressed on a release build; it is a debug-only diagnostic.
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
            if (transport.type == KeepADBTransportEndpoint.Type.VPN && !debugBuild) {
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
        String label = getString(R.string.transport_vpn_label);
        String value = KeepADBEndpointText.display(this,
                KeepADBPrivacy.maskHostForDisplay(this, transport.host), transport.port);
        java.text.DateFormat dateTimeFormat = java.text.DateFormat.getDateTimeInstance(
                java.text.DateFormat.MEDIUM, java.text.DateFormat.MEDIUM,
                getResources().getConfiguration().getLocales().get(0));
        String lastChecked = dateTimeFormat.format(new java.util.Date(transport.verifiedAtMs));
        String text = getString(R.string.transport_row_format, label, value, lastChecked);
        TextView row = buildStatusRow(text);
        if (transport.primary) {
            row.setContentDescription(getString(R.string.transport_primary_accessibility_format, text));
        }
        // #951: a verified transport row copies its adb connect command like the WLAN/LAN row.
        android.util.TypedValue ripple = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
        row.setBackgroundResource(ripple.resourceId);
        row.setMinHeight(dp(48));
        row.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        row.setCompoundDrawablePadding(dp(8));
        row.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_copy, 0);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> KeepADBEndpointText.copyAdbConnect(this, transport.host,
                transport.port, "app"));
        setCopyClickLabel(row);
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
