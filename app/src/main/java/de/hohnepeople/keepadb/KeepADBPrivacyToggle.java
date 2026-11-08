package de.hohnepeople.keepadb;

import android.app.Activity;
import android.view.Menu;
import android.widget.ImageButton;
import android.widget.PopupMenu;

/**
 * #725: the eye button of the red title bars (Settings, network lists, report history). Every bar
 * includes {@code view_privacy_toggle.xml} and binds it here, so the click behavior and the icon /
 * content description come from one place.
 *
 * <p>#904 (Q9 of the PR #917 review): a tap no longer flips the stored app switch. It opens a small
 * menu with
 * <ul>
 *   <li>"Show on this screen" or "Hide on this screen": a temporary override of this screen only,
 *       until it is left (see {@link KeepADBPrivacy#setScreenMasked}); never stored;</li>
 *   <li>the three stored privacy switches (app, notifications, widgets) as check items, the same
 *       ones as in the Settings and on the first page of the setup assistant.</li>
 * </ul>
 * The icon shows what this screen currently does (Q12): a crossed-out eye while it masks, an
 * open eye while it shows. Display-only: what the switches hide is not decided here.
 */
final class KeepADBPrivacyToggle {
    static final int ITEM_SCREEN = 1;
    static final int ITEM_APP = 2;
    static final int ITEM_NOTIFICATIONS = 3;
    static final int ITEM_WIDGETS = 4;

    private KeepADBPrivacyToggle() {
    }

    /**
     * Wires the button of {@code activity}. A tap opens the privacy menu; after any choice the
     * icon is updated and {@code redraw} runs so the page masks or unmasks at once.
     */
    static void bind(Activity activity, Runnable redraw) {
        ImageButton button = activity.findViewById(R.id.btn_toggle_privacy_mode);
        if (button == null) return;
        button.setOnClickListener(v -> showMenu(activity, button, redraw));
        update(activity);
    }

    private static void showMenu(Activity activity, ImageButton anchor, Runnable redraw) {
        PopupMenu popup = new PopupMenu(activity, anchor);
        Menu menu = popup.getMenu();
        boolean masked = KeepADBPrivacy.isScreenMasked(activity);
        menu.add(Menu.NONE, ITEM_SCREEN, 0, masked
                ? R.string.privacy_menu_show_screen : R.string.privacy_menu_hide_screen);
        menu.add(Menu.NONE, ITEM_APP, 1, R.string.privacy_app_title).setCheckable(true)
                .setChecked(KeepADBPreferences.isAppPrivacyEnabled(activity));
        menu.add(Menu.NONE, ITEM_NOTIFICATIONS, 2, R.string.privacy_notifications_title)
                .setCheckable(true)
                .setChecked(KeepADBPreferences.isNotificationPrivacyEnabled(activity));
        menu.add(Menu.NONE, ITEM_WIDGETS, 3, R.string.privacy_widgets_title).setCheckable(true)
                .setChecked(KeepADBPreferences.isWidgetPrivacyEnabled(activity));
        popup.setOnMenuItemClickListener(item -> {
            apply(activity, item.getItemId());
            redraw.run();
            return true;
        });
        popup.show();
    }

    /**
     * Carries out one choice of the privacy menu (the eye menu here, the header menu of the home
     * screen through {@link KeepADBSettingsMenu}); the caller redraws its page.
     */
    static void apply(Activity activity, int itemId) {
        switch (itemId) {
            case ITEM_SCREEN: {
                boolean hide = !KeepADBPrivacy.isScreenMasked(activity);
                KeepADBDiagnostics.event(activity, "user_action", "app",
                        hide ? "hide" : "show", "privacy_screen_override");
                KeepADBPrivacy.setScreenMasked(activity, hide);
                break;
            }
            case ITEM_APP:
                KeepADBPrivacySwitches.setApp(activity,
                        !KeepADBPreferences.isAppPrivacyEnabled(activity));
                break;
            case ITEM_NOTIFICATIONS:
                KeepADBPrivacySwitches.setNotifications(activity,
                        !KeepADBPreferences.isNotificationPrivacyEnabled(activity));
                break;
            case ITEM_WIDGETS:
                KeepADBPrivacySwitches.setWidgets(activity,
                        !KeepADBPreferences.isWidgetPrivacyEnabled(activity));
                break;
            default:
                return;
        }
        update(activity);
        KeepADBPrivacySwitches.refreshIfShown(activity);
    }

    /** Shows whether this screen masks (crossed-out eye) or shows (eye); the tap opens the menu. */
    static void update(Activity activity) {
        ImageButton button = activity.findViewById(R.id.btn_toggle_privacy_mode);
        if (button == null) return;
        boolean masked = KeepADBPrivacy.isScreenMasked(activity);
        button.setImageResource(masked ? R.drawable.ic_privacy_eye_off : R.drawable.ic_privacy_eye);
        button.setContentDescription(activity.getString(masked
                ? R.string.privacy_eye_masked_accessibility
                : R.string.privacy_eye_shown_accessibility));
    }
}
