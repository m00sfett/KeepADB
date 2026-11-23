package de.hohnepeople.keepadb;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * #1051: the three ways to the system permission in the setup assistant's permissions step
 * (#961, #962), split out of {@link OnboardingPermissionsStep}: KeepADB grants it to itself on this
 * phone (primary, with its progress and result), the WebUSB setup page in a computer's browser,
 * and the adb command line over USB. Holds the fold and self-pairing state of the row and saves it
 * with the step; the step owns the row and redraws through {@link #render}.
 */
final class OnboardingSystemPermissionHelp {
    /** #961/#962: the browser-based setup way, first alternative below on-device pairing. */
    private boolean browserOpen;
    private boolean browserAutoOpenedForFailure;
    /** #961/#962: the manual USB command-line way, second alternative below browser setup. */
    private boolean computerOpen;
    /** #962: "Grant on this phone" was tapped and waits for the notification permission. */
    private boolean selfPairPending;
    private boolean selfPairNeedsNotifications;
    private boolean selfPairNeedsWifi;
    private static final String STATE_SELF_PAIR_PENDING =
            "onboarding_perm_self_pair_pending";
    private static final String STATE_BROWSER_OPEN =
            "onboarding_perm_browser_open";
    private static final String STATE_BROWSER_AUTO_OPENED =
            "onboarding_perm_browser_auto_opened";
    private static final String STATE_COMPUTER_OPEN =
            "onboarding_perm_computer_open";
    private static final String STATE_SELF_PAIR_NEEDS_NOTIFICATIONS =
            "onboarding_perm_self_pair_needs_notifications";
    private static final String STATE_SELF_PAIR_NEEDS_WIFI =
            "onboarding_perm_self_pair_needs_wifi";

    private boolean moreOpen;

    private final Runnable render;
    private Activity host;

    OnboardingSystemPermissionHelp(Runnable render) {
        this.render = render;
    }

    /** The activity the step was built in; set before the first row is drawn. */
    void attach(Activity activity) {
        host = activity;
    }

    void save(Bundle out) {
        out.putBoolean(STATE_SELF_PAIR_PENDING, selfPairPending);
        out.putBoolean(STATE_BROWSER_OPEN, browserOpen);
        out.putBoolean(STATE_BROWSER_AUTO_OPENED, browserAutoOpenedForFailure);
        out.putBoolean(STATE_COMPUTER_OPEN, computerOpen);
        out.putBoolean(STATE_SELF_PAIR_NEEDS_NOTIFICATIONS, selfPairNeedsNotifications);
        out.putBoolean(STATE_SELF_PAIR_NEEDS_WIFI, selfPairNeedsWifi);
    }

    void restore(Bundle in) {
        selfPairPending = in.getBoolean(STATE_SELF_PAIR_PENDING, false);
        browserOpen = in.getBoolean(STATE_BROWSER_OPEN, false);
        browserAutoOpenedForFailure = in.getBoolean(STATE_BROWSER_AUTO_OPENED, false);
        computerOpen = in.getBoolean(STATE_COMPUTER_OPEN, false);
        selfPairNeedsNotifications = in.getBoolean(STATE_SELF_PAIR_NEEDS_NOTIFICATIONS, false);
        selfPairNeedsWifi = in.getBoolean(STATE_SELF_PAIR_NEEDS_WIFI, false);
    }

    /**
     * #962: back from the notification permission (dialog or settings) while "Grant on this
     * phone" waits for it: start now, or, still without it, close this way and open the browser
     * setup instead. Does nothing when no start is waiting. The step redraws afterwards.
     */
    void continuePendingStart() {
        if (!selfPairPending) return;
        if (KeepADBSelfPairService.notificationsUsable(host)) {
            startSelfPair();
        } else {
            selfPairPending = false;
            selfPairNeedsNotifications = true;
            browserOpen = true;
        }
    }

    /**
     * Three ways to the system permission (#961, #962): KeepADB grants it to itself on this
     * phone (primary), or via the WebUSB setup page in a computer's browser, or manually over
     * USB with the adb command line. Browser setup opens by itself when on-device pairing
     * cannot work.
     */
    void addTo(LinearLayout row) {
        KeepADBSelfPairService.Status selfPair = KeepADBSelfPairService.status();
        if (selfPair.phase == KeepADBSelfPairService.Phase.FAILED) {
            if (!browserAutoOpenedForFailure) {
                browserOpen = true;
                browserAutoOpenedForFailure = true;
            }
        } else {
            browserAutoOpenedForFailure = false;
        }
        if (selfPair.isActive()) {
            addSelfPairProgress(row, selfPair);
        } else {
            row.addView(OnboardingActionSteps.body(host, host.getString(R.string.selfpair_intro)));
            addRemovableNotes(row, selfPair);
            if (selfPairNeedsWifi) {
                row.addView(OnboardingActionSteps.muted(host, host.getString(R.string.selfpair_need_wifi)));
            }
            if (selfPairNeedsNotifications) {
                row.addView(OnboardingActionSteps.muted(host, host.getString(R.string.selfpair_need_notifications)));
            }
            row.addView(OnboardingActionSteps.button(host, host.getString(R.string.selfpair_start_button), true,
                    v -> startSelfPair()));
        }
        row.addView(foldToggle(host.getString(R.string.onboarding_perm_browser_toggle),
                browserOpen, v -> {
                    browserOpen = !browserOpen;
                    render.run();
                }));
        if (browserOpen) addBrowserHelp(row);
        row.addView(foldToggle(host.getString(R.string.selfpair_computer_toggle),
                computerOpen, v -> {
                    computerOpen = !computerOpen;
                    render.run();
                }));
        if (computerOpen) addComputerHelp(row);
    }

    /** What the user does in Android's settings, and where the run stands. */
    private void addSelfPairProgress(LinearLayout row, KeepADBSelfPairService.Status status) {
        row.addView(OnboardingActionSteps.body(host, host.getString(R.string.selfpair_step_1)));
        row.addView(OnboardingActionSteps.button(host, host.getString(R.string.selfpair_open_developer_options), true,
                v -> openDeveloperOptions()));
        row.addView(OnboardingActionSteps.muted(host, host.getString(R.string.selfpair_developer_options_hint)));
        row.addView(OnboardingActionSteps.body(host, host.getString(R.string.selfpair_step_2)));
        row.addView(OnboardingActionSteps.body(host, host.getString(R.string.selfpair_step_3)));
        TextView state = OnboardingActionSteps.label(host, host.getString(KeepADBSelfPairService.text(status.phase)));
        state.setTextColor(host.getColor(R.color.text_yellow));
        state.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        row.addView(state);
        addRemovableNotes(row, status);
        row.addView(OnboardingActionSteps.button(host, host.getString(android.R.string.cancel), false,
                v -> KeepADBSelfPairService.cancel(host)));
    }

    /**
     * After a grant on the phone: what is left behind. Shown for as long as this process
     * remembers the run; the closing notification says the same and stays in the shade.
     */
    void addResult(LinearLayout row) {
        KeepADBSelfPairService.Status status = KeepADBSelfPairService.status();
        if (status.phase == KeepADBSelfPairService.Phase.DONE) {
            if (status.cleanup == KeepADBSelfPairService.Cleanup.SWITCHED_OFF) {
                row.addView(OnboardingActionSteps.muted(host, host.getString(R.string.selfpair_done_off)));
            } else if (status.cleanup == KeepADBSelfPairService.Cleanup.STILL_ON) {
                row.addView(OnboardingActionSteps.muted(host, host.getString(R.string.selfpair_done_still_on)));
            }
        }
        addRemovableNotes(row, status);
    }

    /** One line per key this process paired: each left an entry the user may remove. */
    private void addRemovableNotes(LinearLayout row, KeepADBSelfPairService.Status status) {
        for (String fingerprint : status.fingerprints) {
            row.addView(OnboardingActionSteps.muted(host, host.getString(R.string.selfpair_done_removable,
                    fingerprint)));
        }
    }

    private void startSelfPair() {
        selfPairNeedsWifi = !KeepADBService.isWifiConnected(host);
        if (selfPairNeedsWifi) {
            selfPairPending = false;
            render.run();
            return;
        }
        if (!KeepADBSelfPairService.notificationsUsable(host)) {
            if (selfPairPending) {
                // Asked already and still missing: this way is closed.
                selfPairPending = false;
                selfPairNeedsNotifications = true;
                browserOpen = true;
                render.run();
                return;
            }
            // The code comes in through a notification reply; ask right before it is needed.
            selfPairPending = true;
            if (OnboardingPermissions.hasNotificationPermission()
                    && OnboardingPermissions.isNotificationMissing(host)) {
                OnboardingPermissions.requestNotifications(host);
            } else {
                OnboardingPermissions.openAppDetails(host);
            }
            return;
        }
        selfPairPending = false;
        selfPairNeedsNotifications = false;
        KeepADBSelfPairService.start(host);
    }

    private void openDeveloperOptions() {
        try {
            host.startActivity(new android.content.Intent(
                    android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        } catch (android.content.ActivityNotFoundException e) {
            // The hint below the button says how to unlock them.
        }
    }

    private TextView foldToggle(String label, boolean open, View.OnClickListener listener) {
        return KeepADBUiKit.foldToggle(OnboardingActionSteps.body(host, label), host, label, open, listener);
    }

    /** #961: browser-based WebUSB setup page on a computer. No network call from the app. */
    private void addBrowserHelp(LinearLayout row) {
        row.addView(OnboardingActionSteps.body(host, host.getString(R.string.onboarding_perm_browser_body)));
        row.addView(command(host.getString(R.string.onboarding_perm_browser_url)));
        row.addView(OnboardingActionSteps.body(host, host.getString(R.string.onboarding_perm_browser_prereq)));
        row.addView(OnboardingActionSteps.button(host, host.getString(R.string.selfpair_open_developer_options), false,
                v -> openDeveloperOptions()));
        row.addView(OnboardingActionSteps.muted(host, host.getString(R.string.selfpair_developer_options_hint)));
        row.addView(OnboardingActionSteps.button(host, host.getString(R.string.setup_refresh), false,
                v -> refreshSystemPermission()));
    }

    /** The command for the computer, with the several-devices and problem help folded away. */
    private void addComputerHelp(LinearLayout row) {
        row.addView(OnboardingActionSteps.body(host, host.getString(R.string.onboarding_perm_system_body)));
        String grant = host.getString(R.string.setup_command, host.getPackageName());
        row.addView(command(grant));
        // #961: share sheet lets the user pass the command from phone to computer.
        row.addView(OnboardingActionSteps.button(host, host.getString(R.string.onboarding_share_command), false,
                v -> shareCommand(grant)));
        row.addView(OnboardingActionSteps.button(host, host.getString(R.string.setup_refresh), false,
                v -> refreshSystemPermission()));
        row.addView(foldToggle(host.getString(R.string.onboarding_perm_system_more),
                moreOpen, v -> {
                    moreOpen = !moreOpen;
                    render.run();
                }));
        if (!moreOpen) return;
        row.addView(OnboardingActionSteps.body(host, host.getString(R.string.setup_body)));
        row.addView(OnboardingActionSteps.label(host, host.getString(R.string.setup_multi_device_label)));
        row.addView(command(host.getString(R.string.setup_command_multi,
                host.getPackageName())));
        row.addView(OnboardingActionSteps.muted(host, host.getString(R.string.setup_multi_device_hint)));
        row.addView(OnboardingActionSteps.label(host, host.getString(R.string.setup_state_offline_title)));
        row.addView(OnboardingActionSteps.body(host, host.getString(R.string.setup_state_offline_body)));
        row.addView(OnboardingActionSteps.label(host, host.getString(R.string.setup_state_unauthorized_title)));
        row.addView(OnboardingActionSteps.body(host, host.getString(R.string.setup_state_unauthorized_body)));
    }

    private void refreshSystemPermission() {
        render.run();
        KeepADBWidget.refreshAll(host);
        KeepADBEndpointCoordinator.refresh(host);
    }

    private void shareCommand(String command) {
        android.content.Intent send = new android.content.Intent(
                android.content.Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(android.content.Intent.EXTRA_TEXT, command);
        android.content.Intent chooser = android.content.Intent.createChooser(send,
                host.getString(R.string.onboarding_share_command));
        try {
            host.startActivity(chooser);
        } catch (android.content.ActivityNotFoundException ignored) {
            // No share target installed; the command above remains selectable.
        }
    }

    private TextView command(String text) {
        TextView view = OnboardingActionSteps.text(host, text, 13, R.color.text_yellow, 4);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextIsSelectable(true);
        return view;
    }
}
