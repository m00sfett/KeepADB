package de.hohnepeople.keepadb;

import android.app.Activity;
import android.view.View;
import android.widget.Switch;

/**
 * #904: the three privacy switches (and, #920, the lock-screen option under the notification
 * switch) as one reusable block ({@code view_privacy_switches.xml}): "app"
 * (every screen and dialog), "notifications" (every KeepADB notification) and "widgets" (every
 * widget and the Quick Settings tile). The Settings include the block, the setup assistant
 * inflates it onto its intro page, so both read and write the same three preferences and a change
 * reaches the affected surfaces at once.
 *
 * <p>All three are opt-in and off on a new installation. A switch hides data in its own area only;
 * the eye menu of the title bars and the header menu offer the same three switches ({@link
 * KeepADBPrivacyToggle}, {@link KeepADBSettingsMenu}) through the setters below.
 */
final class KeepADBPrivacySwitches {
    private KeepADBPrivacySwitches() {}

    /**
     * Wires the three switches inside {@code root} and shows their stored state.
     *
     * @param onAppChanged runs after any of the three switches changed (each ends the screen's
     *     reveals and eye-menu override) so the host can redraw what it shows (may be null).
     */
    static void bind(Activity activity, View root, Runnable onAppChanged) {
        Switch app = root.findViewById(R.id.privacy_switch_app);
        Switch notifications = root.findViewById(R.id.privacy_switch_notifications);
        Switch widgets = root.findViewById(R.id.privacy_switch_widgets);
        Switch lockScreen = root.findViewById(R.id.privacy_switch_lockscreen);

        app.setOnClickListener(v -> {
            setApp(activity, app.isChecked());
            if (onAppChanged != null) onAppChanged.run();
        });
        // Every switch change ends this screen's reveals and eye-menu override, so the host redraws.
        notifications.setOnClickListener(v -> {
            setNotifications(activity, notifications.isChecked());
            if (onAppChanged != null) onAppChanged.run();
        });
        lockScreen.setOnClickListener(v -> {
            setLockScreen(activity, lockScreen.isChecked());
            if (onAppChanged != null) onAppChanged.run();
        });
        widgets.setOnClickListener(v -> {
            setWidgets(activity, widgets.isChecked());
            if (onAppChanged != null) onAppChanged.run();
        });
        refresh(activity, root);
    }

    /**
     * Stores the app switch. Any change ends the reveals and the eye-menu override of this screen,
     * so turning it on masks the screen again also after a "Show"; the caller redraws.
     */
    static void setApp(Activity activity, boolean want) {
        KeepADBDiagnostics.event(activity, "user_action", "app", want ? "enable" : "disable",
                "privacy_app_switch");
        KeepADBPreferences.setAppPrivacyEnabled(activity, want);
        KeepADBPrivacy.concealAll(activity);
        KeepADBPrivacyToggle.update(activity);
    }

    /** Stores the notification switch and re-renders the notifications it affects at once. */
    static void setNotifications(Activity activity, boolean want) {
        KeepADBDiagnostics.event(activity, "user_action", "app", want ? "enable" : "disable",
                "privacy_notification_switch");
        KeepADBPreferences.setNotificationPrivacyEnabled(activity, want);
        KeepADBPrivacy.concealAll(activity);
        KeepADBPrivacyToggle.update(activity);
        // Re-render a currently visible USB card and the persistent main notification right
        // away (#592, #597).
        KeepADBUsbReceiver.refresh(activity);
        KeepADBEndpointCoordinator.refresh(activity);
        // A "new Wi-Fi" notice already posted with the name, the BSSID and buttons is removed now,
        // not at its next posting.
        if (want) KeepADBNetworkTrustPrompt.applyNotificationPrivacy(activity);
    }

    /**
     * #920: stores the lock-screen option and re-posts the visible notifications with it. It is a
     * standing preference, not a quick reveal, so the eye menu does not offer it.
     */
    static void setLockScreen(Activity activity, boolean want) {
        KeepADBDiagnostics.event(activity, "user_action", "app", want ? "enable" : "disable",
                "privacy_lockscreen_switch");
        KeepADBLockScreen.setEnabled(activity, want);
        // The owners re-render the notifications that can carry details (#904's pattern): the
        // main card and the USB card. A posted trust notice with the network name and its buttons
        // is removed, as #904 does; it comes back in the neutral form. Notifications without
        // network details (webhook warning, Force Mode and shutdown notices) follow at their next
        // posting.
        KeepADBUsbReceiver.refresh(activity);
        KeepADBEndpointCoordinator.refresh(activity);
        if (want) KeepADBNetworkTrustPrompt.applyNotificationPrivacy(activity);
    }

    /** Stores the widget switch and redraws every widget and the tile. */
    static void setWidgets(Activity activity, boolean want) {
        KeepADBDiagnostics.event(activity, "user_action", "app", want ? "enable" : "disable",
                "privacy_widget_switch");
        KeepADBPreferences.setWidgetPrivacyEnabled(activity, want);
        KeepADBPrivacy.concealAll(activity);
        KeepADBPrivacyToggle.update(activity);
        KeepADBWidget.refreshAllState(activity);
        KeepADBTileService.requestRefresh(activity);
    }

    /** Shows the stored state of the three switches if {@code activity} shows the block. */
    static void refreshIfShown(Activity activity) {
        View root = activity.findViewById(R.id.privacy_switches);
        if (root != null) refresh(activity, root);
    }

    /** Shows the stored state of the three switches. */
    static void refresh(Activity activity, View root) {
        ((Switch) root.findViewById(R.id.privacy_switch_app))
                .setChecked(KeepADBPreferences.isAppPrivacyEnabled(activity));
        ((Switch) root.findViewById(R.id.privacy_switch_notifications))
                .setChecked(KeepADBPreferences.isNotificationPrivacyEnabled(activity));
        ((Switch) root.findViewById(R.id.privacy_switch_widgets))
                .setChecked(KeepADBPreferences.isWidgetPrivacyEnabled(activity));
        ((Switch) root.findViewById(R.id.privacy_switch_lockscreen))
                .setChecked(KeepADBPreferences.isLockScreenPrivacyEnabled(activity));
    }
}
