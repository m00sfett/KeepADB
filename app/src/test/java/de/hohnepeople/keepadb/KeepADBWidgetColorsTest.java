package de.hohnepeople.keepadb;

import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * #912: every text and symbol color of the control widget has at least 4.5:1 contrast (WCAG AA)
 * against the fill it is drawn on, in every state, including the whole hero gradient.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBWidgetColorsTest {

    private final Context context = ApplicationProvider.getApplicationContext();

    private static double channel(int value) {
        double c = value / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    static double luminance(int color) {
        return 0.2126 * channel((color >> 16) & 0xFF) + 0.7152 * channel((color >> 8) & 0xFF)
                + 0.0722 * channel(color & 0xFF);
    }

    static double contrast(int a, int b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private void atLeastAa(String what, int foreground, int... fills) {
        for (int fill : fills) {
            double ratio = contrast(foreground, fill);
            assertTrue(what + " on #" + Integer.toHexString(fill) + " is " + ratio + ":1",
                    ratio >= 4.5);
        }
    }

    private int color(int res) {
        return context.getColor(res);
    }

    @Test
    public void everyStateKeepsItsTextAndSymbolReadableOnItsFill() {
        int[] gradient = {color(R.color.widget_hero_start), color(R.color.widget_hero_center),
                color(R.color.widget_hero_end)};
        int on = color(R.color.widget_on);
        atLeastAa("ink on the hero gradient", color(R.color.widget_ink), gradient);
        atLeastAa("soft ink on the hero gradient", color(R.color.widget_ink_soft), gradient);
        atLeastAa("ink on the flat yellow", color(R.color.widget_ink), on);
        atLeastAa("soft ink on the flat yellow", color(R.color.widget_ink_soft), on);

        int off = color(R.color.widget_off);
        atLeastAa("text on off", color(R.color.widget_text), off);
        atLeastAa("soft text on off", color(R.color.widget_text_soft), off);
        atLeastAa("symbol on off", color(R.color.widget_off_icon), off);

        int busy = color(R.color.widget_busy);
        atLeastAa("text on busy", color(R.color.widget_text), busy);
        atLeastAa("soft text on busy", color(R.color.widget_text_soft), busy);
        atLeastAa("symbol on busy", color(R.color.widget_busy_icon), busy);

        int warn = color(R.color.widget_warn);
        atLeastAa("text on attention", color(R.color.widget_warn_ink), warn);
        atLeastAa("soft text on attention", color(R.color.widget_warn_ink_soft), warn);
        atLeastAa("symbol on attention", color(R.color.widget_warn_icon), warn);

        int root = color(R.color.widget_root);
        atLeastAa("status text on the surface", color(R.color.widget_status_text), root);
        atLeastAa("strong status text on the surface", color(R.color.widget_text), root);
        atLeastAa("gear on its round button", color(R.color.widget_off_icon), off);
    }

    @Test
    public void everyStylePairsItsInkWithItsFillThroughTheRenderer() {
        for (KeepADBWidgetContent.Style style : KeepADBWidgetContent.Style.values()) {
            int ink = color(KeepADBWidgetViews.inkOf(style));
            int soft = color(KeepADBWidgetViews.softInkOf(style));
            int icon = color(KeepADBWidgetViews.iconOf(style));
            switch (style) {
                case ON:
                    atLeastAa(style + " ink", ink, color(R.color.widget_hero_end), color(R.color.widget_on));
                    atLeastAa(style + " soft", soft, color(R.color.widget_hero_end));
                    atLeastAa(style + " icon", icon, color(R.color.widget_hero_end));
                    break;
                case ATTENTION:
                    atLeastAa(style + " ink", ink, color(R.color.widget_warn));
                    atLeastAa(style + " soft", soft, color(R.color.widget_warn));
                    atLeastAa(style + " icon", icon, color(R.color.widget_warn));
                    break;
                case BUSY:
                    atLeastAa(style + " ink", ink, color(R.color.widget_busy));
                    atLeastAa(style + " soft", soft, color(R.color.widget_busy));
                    atLeastAa(style + " icon", icon, color(R.color.widget_busy));
                    break;
                case OFF:
                default:
                    atLeastAa(style + " ink", ink, color(R.color.widget_off));
                    atLeastAa(style + " soft", soft, color(R.color.widget_off));
                    atLeastAa(style + " icon", icon, color(R.color.widget_off));
                    break;
            }
        }
    }
}
