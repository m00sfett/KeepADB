package de.hohnepeople.keepadb;

import android.app.Activity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageButton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * #725: the eye button of the title bars (main view, Settings, network lists, report history).
 * Every bar includes {@code view_privacy_toggle.xml} and binds it here, so the behavior and the
 * icon / content description come from one place.
 *
 * <p>#941 (replacing the tap menu of #904):
 * <ul>
 *   <li>a <b>tap</b> toggles "Hide/Show on this screen": a temporary override of this screen only,
 *       until it is left (see {@link KeepADBPrivacy#setScreenMasked}); never stored;</li>
 *   <li>a <b>long press</b> opens the privacy menu ({@link KeepADBSettingsMenu}): the four stored
 *       switches (app, notifications, lock screen, widgets and tile) as check entries, in the order
 *       of the Privacy section, and a link to that section. The menu stays open after a check.</li>
 * </ul>
 * The icon shows what this screen currently does: a crossed-out eye while it masks, an open eye
 * while it shows. Display-only: what the switches hide is not decided here.
 */
final class KeepADBPrivacyToggle {
    static final int ITEM_SCREEN = 1;
    static final int ITEM_APP = 2;
    static final int ITEM_NOTIFICATIONS = 3;
    static final int ITEM_WIDGETS = 4;
    static final int ITEM_LOCKSCREEN = 5;
    static final int ITEM_PRIVACY_SETTINGS = 6;
    static final int ITEM_DIVIDER = -1;

    private KeepADBPrivacyToggle() {
    }

    /**
     * Wires the button of {@code activity}. After a tap or any menu choice the icon is updated
     * and {@code redraw} runs so the page masks or unmasks at once.
     */
    static void bind(Activity activity, Runnable redraw) {
        ImageButton button = activity.findViewById(R.id.btn_toggle_privacy_mode);
        if (button == null) return;
        button.setOnClickListener(v -> {
            apply(activity, ITEM_SCREEN);
            redraw.run();
        });
        button.setOnLongClickListener(v -> {
            showMenu(activity, button, redraw);
            return true;
        });
        // TalkBack names both gestures: the tap after what it does now, the long press as the menu.
        button.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_CLICK,
                        activity.getString(KeepADBPrivacy.isScreenMasked(activity)
                                ? R.string.privacy_menu_show_screen
                                : R.string.privacy_menu_hide_screen)));
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_LONG_CLICK,
                        activity.getString(R.string.privacy_eye_long_press_action)));
            }
        });
        // A menu still open when the bar leaves the window (screen closed) is dismissed with it.
        button.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                KeepADBSettingsMenu menu = menuOf(button);
                if (menu != null) menu.dismiss();
            }
        });
        update(activity);
    }

    /** The entries of the long-press menu, in the order of the Privacy section in Settings. */
    static List<KeepADBSettingsMenu.MenuItem> buildMenuItems() {
        List<KeepADBSettingsMenu.MenuItem> menu = new ArrayList<>();
        menu.add(KeepADBSettingsMenu.MenuItem.check(ITEM_APP, R.string.privacy_app_title));
        menu.add(KeepADBSettingsMenu.MenuItem.check(ITEM_NOTIFICATIONS,
                R.string.privacy_notifications_title));
        menu.add(KeepADBSettingsMenu.MenuItem.check(ITEM_LOCKSCREEN,
                R.string.privacy_lockscreen_title));
        menu.add(KeepADBSettingsMenu.MenuItem.check(ITEM_WIDGETS, R.string.privacy_widgets_title));
        menu.add(KeepADBSettingsMenu.MenuItem.divider(ITEM_DIVIDER));
        menu.add(KeepADBSettingsMenu.MenuItem.link(ITEM_PRIVACY_SETTINGS,
                R.string.privacy_menu_settings, R.drawable.ic_settings_privacy));
        return Collections.unmodifiableList(menu);
    }

    /** The long-press menu last opened from the eye of {@code activity}, or null. */
    static KeepADBSettingsMenu menuOf(Activity activity) {
        ImageButton button = activity.findViewById(R.id.btn_toggle_privacy_mode);
        return button == null ? null : menuOf(button);
    }

    private static KeepADBSettingsMenu menuOf(View button) {
        Object menu = button.getTag(R.id.privacy_menu);
        return menu instanceof KeepADBSettingsMenu ? (KeepADBSettingsMenu) menu : null;
    }

    private static void showMenu(Activity activity, ImageButton anchor, Runnable redraw) {
        KeepADBSettingsMenu menu = menuOf(anchor);
        if (menu == null) {
            menu = new KeepADBSettingsMenu(activity, anchor, buildMenuItems(),
                    new KeepADBSettingsMenu.Handler() {
                        @Override
                        public void onItem(KeepADBSettingsMenu shown,
                                KeepADBSettingsMenu.MenuItem item) {
                            if (item.id == ITEM_PRIVACY_SETTINGS) {
                                shown.dismiss();
                                openPrivacySettings(activity);
                                return;
                            }
                            apply(activity, item.id);
                            redraw.run();
                        }

                        @Override
                        public boolean isChecked(KeepADBSettingsMenu.MenuItem item) {
                            return isSwitchOn(activity, item.id);
                        }
                    });
            anchor.setTag(R.id.privacy_menu, menu);
        }
        menu.show();
    }

    /** On Settings the Privacy card opens in place; elsewhere Settings opens at it. */
    private static void openPrivacySettings(Activity activity) {
        if (activity instanceof SettingsActivity) {
            ((SettingsActivity) activity).focusPrivacyCard();
        } else {
            KeepADBSettingsMenu.openSettingsAt(activity, SettingsActivity.EXTRA_FOCUS_PRIVACY);
        }
    }

    /** The stored switch behind a check entry of the menu. */
    static boolean isSwitchOn(Activity activity, int itemId) {
        switch (itemId) {
            case ITEM_APP:
                return KeepADBPreferences.isAppPrivacyEnabled(activity);
            case ITEM_NOTIFICATIONS:
                return KeepADBPreferences.isNotificationPrivacyEnabled(activity);
            case ITEM_LOCKSCREEN:
                return KeepADBPreferences.isLockScreenPrivacyEnabled(activity);
            case ITEM_WIDGETS:
                return KeepADBPreferences.isWidgetPrivacyEnabled(activity);
            default:
                return false;
        }
    }

    /**
     * Carries out one choice: the tap ({@link #ITEM_SCREEN}) or a check entry of the long-press
     * menu; the caller redraws its page.
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
                KeepADBPrivacySwitches.setApp(activity, !isSwitchOn(activity, ITEM_APP));
                break;
            case ITEM_NOTIFICATIONS:
                KeepADBPrivacySwitches.setNotifications(activity,
                        !isSwitchOn(activity, ITEM_NOTIFICATIONS));
                break;
            case ITEM_LOCKSCREEN:
                KeepADBPrivacySwitches.setLockScreen(activity,
                        !isSwitchOn(activity, ITEM_LOCKSCREEN));
                break;
            case ITEM_WIDGETS:
                KeepADBPrivacySwitches.setWidgets(activity, !isSwitchOn(activity, ITEM_WIDGETS));
                break;
            default:
                return;
        }
        update(activity);
        KeepADBPrivacySwitches.refreshIfShown(activity);
    }

    /** Shows whether this screen masks (crossed-out eye) or shows (eye). */
    static void update(Activity activity) {
        ImageButton button = activity.findViewById(R.id.btn_toggle_privacy_mode);
        if (button == null) return;
        boolean masked = KeepADBPrivacy.isScreenMasked(activity);
        button.setImageResource(masked ? R.drawable.ic_privacy_eye_off : R.drawable.ic_privacy_eye);
        button.setContentDescription(activity.getString(masked
                ? R.string.privacy_eye_masked_state
                : R.string.privacy_eye_shown_state));
    }
}
