package de.hohnepeople.keepadb;

import static org.junit.Assert.assertNotNull;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.view.MenuItem;
import android.widget.PopupMenu;

import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPopupMenu;

/** Test helper: taps the eye of a title bar and picks one entry of its privacy menu (#904, Q9). */
final class KeepADBEyeMenuTestSupport {
    private KeepADBEyeMenuTestSupport() {}

    /** Opens the eye menu of {@code activity} and returns it. */
    static PopupMenu open(Activity activity) {
        activity.findViewById(R.id.btn_toggle_privacy_mode).performClick();
        PopupMenu popup = ShadowPopupMenu.getLatestPopupMenu();
        assertNotNull("the eye opens a menu", popup);
        return popup;
    }

    /** Opens the eye menu and chooses {@code itemId} ({@code KeepADBPrivacyToggle.ITEM_*}). */
    static void choose(Activity activity, int itemId) {
        PopupMenu popup = open(activity);
        MenuItem item = popup.getMenu().findItem(itemId);
        assertNotNull("the eye menu has item " + itemId, item);
        shadowOf(popup).getOnMenuItemClickListener().onMenuItemClick(item);
        ShadowLooper.idleMainLooper();
    }
}
