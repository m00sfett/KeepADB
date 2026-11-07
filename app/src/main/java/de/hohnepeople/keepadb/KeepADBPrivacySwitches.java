package de.hohnepeople.keepadb;

import android.app.Activity;
import android.view.View;
import android.widget.Switch;

/**
 * #904: the three privacy switches as one reusable block ({@code view_privacy_switches.xml}): "app"
 * (every screen and dialog), "notifications" (every KeepADB notification) and "widgets" (every
 * widget and the Quick Settings tile). The Settings include the block, the setup assistant
 * inflates it onto its intro page, so both read and write the same three preferences and a change
 * reaches the affected surfaces at once.
 *
 * <p>All three are opt-in and off on a new installation. A switch hides data in its own area only;
 * the app switch is also the one the eye button of the title bars flips ({@link
 * KeepADBPrivacyToggle}).
 */
final class KeepADBPrivacySwitches {
    private KeepADBPrivacySwitches() {}

    /**
     * Wires the three switches inside {@code root} and shows their stored state.
     *
     * @param onAppChanged runs after the app switch changed so the host can redraw what it shows
     *     (may be null).
     */
    static void bind(Activity activity, View root, Runnable onAppChanged) {
        Switch app = root.findViewById(R.id.privacy_switch_app);
        Switch notifications = root.findViewById(R.id.privacy_switch_notifications);
        Switch widgets = root.findViewById(R.id.privacy_switch_widgets);

        app.setOnClickListener(v -> {
            boolean want = app.isChecked();
            KeepADBDiagnostics.event(activity, "user_action", "app", want ? "enable" : "disable",
                    "privacy_app_switch");
            KeepADBPreferences.setAppPrivacyEnabled(activity, want);
            // Turning it on masks this screen again, also after a "Show" on it.
            KeepADBPrivacy.concealAll(activity);
            KeepADBPrivacyToggle.update(activity);
            if (onAppChanged != null) onAppChanged.run();
        });
        notifications.setOnClickListener(v -> {
            boolean want = notifications.isChecked();
            KeepADBDiagnostics.event(activity, "user_action", "app", want ? "enable" : "disable",
                    "privacy_notification_switch");
            KeepADBPreferences.setNotificationPrivacyEnabled(activity, want);
            // Re-render a currently visible USB card and the persistent main notification right
            // away (#592, #597); a trust prompt already on screen keeps its text until it is
            // posted again.
            KeepADBUsbReceiver.refresh(activity);
            KeepADBEndpointCoordinator.refresh(activity);
            // A "new Wi-Fi" notice already posted with the name, the BSSID and buttons is
            // replaced (or removed) now, not at its next posting.
            if (want) KeepADBNetworkTrustPrompt.applyNotificationPrivacy(activity);
        });
        widgets.setOnClickListener(v -> {
            boolean want = widgets.isChecked();
            KeepADBDiagnostics.event(activity, "user_action", "app", want ? "enable" : "disable",
                    "privacy_widget_switch");
            KeepADBPreferences.setWidgetPrivacyEnabled(activity, want);
            KeepADBWidget.refreshAllState(activity);
            KeepADBTileService.requestRefresh(activity);
        });
        refresh(activity, root);
    }

    /** Shows the stored state of the three switches. */
    static void refresh(Activity activity, View root) {
        ((Switch) root.findViewById(R.id.privacy_switch_app))
                .setChecked(KeepADBPreferences.isAppPrivacyEnabled(activity));
        ((Switch) root.findViewById(R.id.privacy_switch_notifications))
                .setChecked(KeepADBPreferences.isNotificationPrivacyEnabled(activity));
        ((Switch) root.findViewById(R.id.privacy_switch_widgets))
                .setChecked(KeepADBPreferences.isWidgetPrivacyEnabled(activity));
    }
}
