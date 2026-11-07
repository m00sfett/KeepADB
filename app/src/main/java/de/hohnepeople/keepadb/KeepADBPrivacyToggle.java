package de.hohnepeople.keepadb;

import android.app.Activity;
import android.widget.ImageButton;

/**
 * #725: the eye button of the red title bars (main view, Settings, network lists, report history).
 * Every bar includes {@code view_privacy_toggle.xml} and binds it here, so the click behavior and
 * the icon / content description come from one place and all of them read and write the one
 * {@link KeepADBPreferences#isAppPrivacyEnabled} switch (#482, #904: the "app" one of the three
 * privacy switches; notifications and widgets have their own, set in the Settings and the
 * assistant). Display-only: what the switch hides is not decided here.
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
        if (button == null) return;
        button.setOnClickListener(v -> {
            boolean want = !KeepADBPreferences.isAppPrivacyEnabled(activity);
            KeepADBDiagnostics.event(activity, "user_action", "app", want ? "enable" : "disable",
                    "privacy_mode_toggle");
            KeepADBPreferences.setAppPrivacyEnabled(activity, want);
            // Turning the switch on masks this screen again, also after a "Show" on it.
            KeepADBPrivacy.concealAgain(activity);
            update(activity);
            redraw.run();
        });
        update(activity);
    }

    /** Reflects the shared setting as eye / crossed-out eye, naming the action the next tap does. */
    static void update(Activity activity) {
        ImageButton button = activity.findViewById(R.id.btn_toggle_privacy_mode);
        if (button == null) return;
        boolean enabled = KeepADBPreferences.isAppPrivacyEnabled(activity);
        button.setImageResource(enabled ? R.drawable.ic_privacy_eye_off : R.drawable.ic_privacy_eye);
        button.setContentDescription(activity.getString(enabled
                ? R.string.privacy_toggle_disable_accessibility
                : R.string.privacy_toggle_enable_accessibility));
    }
}
