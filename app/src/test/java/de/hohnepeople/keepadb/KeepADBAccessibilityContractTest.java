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
        assertMinSize(settings.findViewById(R.id.settings_website_link));
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
    }

    @Test
    public void importantAccessibilityTextResolvesOnInflatedViews() {
        View main = inflate(R.layout.activity_main);
        View settings = inflate(R.layout.activity_settings);
        assertHasText(main.findViewById(R.id.advice_banner));
        assertHasText(settings.findViewById(R.id.settings_notification_panel));
        assertTrue(settings.findViewById(R.id.settings_trusted_network_status).getAccessibilityLiveRegion() > 0);
    }

    private View inflate(int layout) { return LayoutInflater.from(context).inflate(layout, null, false); }

    private void assertMinSize(View view) {
        assertNotNull(view);
        view.measure(View.MeasureSpec.makeMeasureSpec(48, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(48, View.MeasureSpec.AT_MOST));
        int layoutWidth = view.getLayoutParams() == null ? 0 : view.getLayoutParams().width;
        int layoutHeight = view.getLayoutParams() == null ? 0 : view.getLayoutParams().height;
        assertTrue(view.getMinimumWidth() >= 48 || view.getMeasuredWidth() >= 48 || layoutWidth >= 48);
        assertTrue(view.getMinimumHeight() >= 48 || view.getMeasuredHeight() >= 48 || layoutHeight >= 48);
    }

    private void assertHasText(View view) {
        assertNotNull(view);
        if (view instanceof TextView && ((TextView) view).getText().length() > 0) return;
        assertTrue(view instanceof ViewGroup);
        for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++) {
            try {
                assertHasText(((ViewGroup) view).getChildAt(index));
                return;
            } catch (AssertionError ignored) {
                // Continue looking through the inflated hierarchy.
            }
        }
        throw new AssertionError("No text-bearing view in inflated hierarchy");
    }
}
