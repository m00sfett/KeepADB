package de.hohnepeople.keepadb;

import android.app.Activity;
import android.widget.ImageButton;

/**
 * #725: the privacy-mode eye button of the red title bars (main view, Settings, network lists).
 * Every bar includes {@code view_privacy_toggle.xml} and binds it here, so the click behavior and
 * the icon / content description come from one place and all of them read and write the one
 * {@link KeepADBPreferences#isPrivacyModeEnabled} setting (#482). Display-only: what the mode
 * hides is not decided here.
 */
final class KeepADBPrivacyToggle {
    private KeepADBPrivacyToggle() {
    }

    /**
     * Wires the button of {@code activity}. A tap flips the shared setting, updates the icon, runs
     * {@code redraw} so the page masks or unmasks at once, then refreshes the other surfaces.
     */
    static void bind(Activity activity, Runnable redraw) {
        ImageButton button = activity.findViewById(R.id.btn_toggle_privacy_mode);
        button.setOnClickListener(v -> {
            boolean want = !KeepADBPreferences.isPrivacyModeEnabled(activity);
            KeepADBDiagnostics.event(activity, "user_action", "app", want ? "enable" : "disable",
                    "privacy_mode_toggle");
            KeepADBPreferences.setPrivacyModeEnabled(activity, want);
            update(activity);
            redraw.run();
            KeepADBEndpointCoordinator.refresh(activity);
            KeepADBTileService.requestRefresh(activity);
        });
        update(activity);
    }

    /** Reflects the shared setting as eye / crossed-out eye, naming the action the next tap does. */
    static void update(Activity activity) {
        ImageButton button = activity.findViewById(R.id.btn_toggle_privacy_mode);
        boolean enabled = KeepADBPreferences.isPrivacyModeEnabled(activity);
        button.setImageResource(enabled ? R.drawable.ic_privacy_eye_off : R.drawable.ic_privacy_eye);
        button.setContentDescription(activity.getString(enabled
                ? R.string.privacy_toggle_disable_accessibility
                : R.string.privacy_toggle_enable_accessibility));
    }
}
