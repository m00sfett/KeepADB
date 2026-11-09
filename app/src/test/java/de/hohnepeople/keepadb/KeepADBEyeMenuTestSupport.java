package de.hohnepeople.keepadb;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;

import org.robolectric.shadows.ShadowLooper;

/**
 * Test helper for the eye of a title bar (#941): a tap toggles this screen, a long press opens
 * the privacy menu with the stored switches.
 */
final class KeepADBEyeMenuTestSupport {
    private KeepADBEyeMenuTestSupport() {}

    /** Long-presses the eye of {@code activity} and returns the privacy menu it opened. */
    static KeepADBSettingsMenu open(Activity activity) {
        assertTrue("the eye handles the long press",
                activity.findViewById(R.id.btn_toggle_privacy_mode).performLongClick());
        KeepADBSettingsMenu menu = KeepADBPrivacyToggle.menuOf(activity);
        assertNotNull("the long press opens the privacy menu", menu);
        assertTrue("the privacy menu is showing", menu.isShowing());
        return menu;
    }

    /**
     * Carries out {@code itemId} ({@code KeepADBPrivacyToggle.ITEM_*}): {@code ITEM_SCREEN} is the
     * tap on the eye, every other choice an entry of the long-press menu.
     */
    static void choose(Activity activity, int itemId) {
        if (itemId == KeepADBPrivacyToggle.ITEM_SCREEN) {
            activity.findViewById(R.id.btn_toggle_privacy_mode).performClick();
        } else {
            KeepADBSettingsMenu menu = open(activity);
            KeepADBSettingsMenu.MenuItem item = find(menu, itemId);
            menu.onMenuItemClicked(item);
        }
        ShadowLooper.idleMainLooper();
    }

    /** The entry {@code itemId} of {@code menu}. */
    static KeepADBSettingsMenu.MenuItem find(KeepADBSettingsMenu menu, int itemId) {
        for (KeepADBSettingsMenu.MenuItem item : menu.getItems()) {
            if (item.id == itemId) return item;
        }
        throw new AssertionError("the privacy menu has item " + itemId);
    }
}
