package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Runtime accessibility contracts backed by Robolectric-inflated Android views. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBAccessibilityContractTest {
    private final Context context = ApplicationProvider.getApplicationContext();

    @Test
    public void interactiveViewsExposeAtLeast48DpTouchTargets() {
        View main = inflate(R.layout.activity_main);
        View settings = inflate(R.layout.activity_settings);
        View widget = inflate(R.layout.widget_keepadb);
        assertMinSize(main.findViewById(R.id.btn_open_settings));
        assertMinSize(main.findViewById(R.id.btn_dismiss_advice_banner));
        assertMinSize(settings.findViewById(R.id.settings_website_link));
        assertMinSize(settings.findViewById(R.id.btn_back));
        assertMinSize(widget.findViewById(R.id.widget_label));
    }

    @Test
    public void settingsBackButtonUsesAnAutoMirroredDrawable() {
        View settings = inflate(R.layout.activity_settings);
        View back = settings.findViewById(R.id.btn_back);
        Drawable drawable = ((android.widget.ImageButton) back).getDrawable();
        assertNotNull(drawable);
        assertTrue(drawable.isAutoMirrored());
        assertEquals(context.getString(R.string.back), back.getContentDescription());
        assertNotNull(((android.widget.ImageView) settings.findViewById(R.id.btn_back)).getDrawable());
    }

    @Test
    public void importantAccessibilityTextResolvesOnInflatedViews() {
        View main = inflate(R.layout.activity_main);
        View settings = inflate(R.layout.activity_settings);
        assertHasText(main.findViewById(R.id.advice_banner), context.getString(R.string.advice_banner_title));
        assertHasText(main.findViewById(R.id.advice_banner), context.getString(R.string.advice_banner_text));
        assertHasText(settings.findViewById(R.id.settings_notification_panel),
                context.getString(R.string.settings_section_notification));
        assertPoliteLiveRegion(main.findViewById(R.id.status));
        assertPoliteLiveRegion(main.findViewById(R.id.webhook_status));
        assertPoliteLiveRegion(settings.findViewById(R.id.settings_webhook_error));
        assertPoliteLiveRegion(settings.findViewById(R.id.settings_webhook_cleartext_warning));
        assertPoliteLiveRegion(settings.findViewById(R.id.settings_trusted_network_status));
    }

    private View inflate(int layout) { return LayoutInflater.from(context).inflate(layout, null, false); }

    private void assertMinSize(View view) {
        assertNotNull(view);
        int minimum = (int) (48 * context.getResources().getDisplayMetrics().density + 0.5f);
        int layoutWidth = view.getLayoutParams() == null ? 0 : view.getLayoutParams().width;
        int layoutHeight = view.getLayoutParams() == null ? 0 : view.getLayoutParams().height;
        assertTrue("View must declare or reserve a 48dp width", view.getMinimumWidth() >= minimum
                || layoutWidth >= minimum);
        assertTrue("View must declare or reserve a 48dp height", view.getMinimumHeight() >= minimum
                || layoutHeight >= minimum);
    }

    private void assertHasText(View view, CharSequence expected) {
        assertNotNull(view);
        if (view instanceof TextView && expected.equals(((TextView) view).getText())) {
            return;
        }
        if (view instanceof ViewGroup) {
            for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++) {
                try {
                    assertHasText(((ViewGroup) view).getChildAt(index), expected);
                    return;
                } catch (AssertionError ignored) {
                    // Continue looking through the inflated hierarchy.
                }
            }
        }
        throw new AssertionError("Expected text not found in inflated hierarchy: " + expected);
    }

    private void assertPoliteLiveRegion(View view) {
        assertNotNull(view);
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, view.getAccessibilityLiveRegion());
    }
}
