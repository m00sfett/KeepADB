package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Method;

/**
 * Issue #837: deterministic verification of WCAG 2.1 relative luminance contrast ratios
 * for bg_btn_primary in normal, focused, and pressed states across all button text colors
 * and button usages in KeepADB.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ButtonContrastTest {

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
    }

    /**
     * WCAG 2.1 relative luminance calculation:
     * https://www.w3.org/WAI/GL/wiki/Relative_luminance
     */
    public static double relativeLuminance(int color) {
        return 0.2126 * linearComponent(Color.red(color) / 255.0)
                + 0.7152 * linearComponent(Color.green(color) / 255.0)
                + 0.0722 * linearComponent(Color.blue(color) / 255.0);
    }

    private static double linearComponent(double component) {
        return component <= 0.04045 ? component / 12.92
                : Math.pow((component + 0.055) / 1.055, 2.4);
    }

    /**
     * WCAG 2.1 contrast ratio: (L1 + 0.05) / (L2 + 0.05) where L1 is the lighter color.
     */
    public static double contrastRatio(int first, int second) {
        double firstLum = relativeLuminance(first);
        double secondLum = relativeLuminance(second);
        double lighter = Math.max(firstLum, secondLum);
        double darker = Math.min(firstLum, secondLum);
        return (lighter + 0.05) / (darker + 0.05);
    }

    @Test
    public void relativeLuminanceKnownReferenceValues() {
        assertEquals(0.0, relativeLuminance(Color.BLACK), 0.0001);
        assertEquals(1.0, relativeLuminance(Color.WHITE), 0.0001);
        assertEquals(21.0, contrastRatio(Color.BLACK, Color.WHITE), 0.01);
    }

    @Test
    public void primaryButtonBackgroundColorsMeetWcagContrastRequirements() {
        int bannerRed = context.getColor(R.color.banner_red);
        int mutedRed = context.getColor(R.color.muted_red);
        int titleYellow = context.getColor(R.color.title_yellow);
        int nightText = context.getColor(R.color.night_text);
        int white = Color.WHITE;

        // Default state (banner_red background)
        double ratioDefaultYellow = contrastRatio(titleYellow, bannerRed);
        double ratioDefaultNight = contrastRatio(nightText, bannerRed);
        double ratioDefaultWhite = contrastRatio(white, bannerRed);

        assertTrue("title_yellow on default banner_red must meet >= 4.5:1 (was " + ratioDefaultYellow + ")",
                ratioDefaultYellow >= 4.5);
        assertTrue("night_text on default banner_red must meet >= 4.5:1 (was " + ratioDefaultNight + ")",
                ratioDefaultNight >= 4.5);
        assertTrue("white on default banner_red must meet >= 4.5:1 (was " + ratioDefaultWhite + ")",
                ratioDefaultWhite >= 4.5);

        // Focused state (muted_red background)
        double ratioFocusedYellow = contrastRatio(titleYellow, mutedRed);
        double ratioFocusedNight = contrastRatio(nightText, mutedRed);
        double ratioFocusedWhite = contrastRatio(white, mutedRed);

        assertTrue("title_yellow on focused muted_red must meet >= 4.5:1 (was " + ratioFocusedYellow + ")",
                ratioFocusedYellow >= 4.5);
        assertTrue("night_text on focused muted_red must meet >= 4.5:1 (was " + ratioFocusedNight + ")",
                ratioFocusedNight >= 4.5);
        assertTrue("white on focused muted_red must meet >= 4.5:1 (was " + ratioFocusedWhite + ")",
                ratioFocusedWhite >= 4.5);

        // Pressed state (muted_red background)
        double ratioPressedYellow = contrastRatio(titleYellow, mutedRed);
        double ratioPressedNight = contrastRatio(nightText, mutedRed);
        double ratioPressedWhite = contrastRatio(white, mutedRed);

        assertTrue("title_yellow on pressed muted_red must meet >= 4.5:1 (was " + ratioPressedYellow + ")",
                ratioPressedYellow >= 4.5);
        assertTrue("night_text on pressed muted_red must meet >= 4.5:1 (was " + ratioPressedNight + ")",
                ratioPressedNight >= 4.5);
        assertTrue("white on pressed muted_red must meet >= 4.5:1 (was " + ratioPressedWhite + ")",
                ratioPressedWhite >= 4.5);
    }

    @Test
    public void primaryButtonFocusIndicatorHasHighContrast() {
        int brightYellow = context.getColor(R.color.bright_yellow);
        int mutedRed = context.getColor(R.color.muted_red);
        int panel = context.getColor(R.color.panel);
        int ground = context.getColor(R.color.ground);

        // WCAG 2.1 Non-text contrast (1.4.11) requires >= 3.0:1 for focus indicators
        double ratioAgainstBg = contrastRatio(brightYellow, mutedRed);
        double ratioAgainstPanel = contrastRatio(brightYellow, panel);
        double ratioAgainstGround = contrastRatio(brightYellow, ground);

        assertTrue("Focus indicator stroke against button fill must meet >= 3.0:1 (was " + ratioAgainstBg + ")",
                ratioAgainstBg >= 3.0);
        assertTrue("Focus indicator stroke against panel surface must meet >= 3.0:1 (was " + ratioAgainstPanel + ")",
                ratioAgainstPanel >= 3.0);
        assertTrue("Focus indicator stroke against ground surface must meet >= 3.0:1 (was " + ratioAgainstGround + ")",
                ratioAgainstGround >= 3.0);
    }

    @Test
    public void primaryButtonDrawableStateResolvesExpectedColorsAndContrast() {
        Drawable drawable = context.getDrawable(R.drawable.bg_btn_primary);
        assertNotNull(drawable);
        assertTrue(drawable instanceof StateListDrawable);

        int titleYellow = context.getColor(R.color.title_yellow);
        int bannerRed = context.getColor(R.color.banner_red);
        int mutedRed = context.getColor(R.color.muted_red);

        // Default state
        drawable.setState(new int[]{});
        int defaultColor = extractSolidColor(drawable.getCurrent(), new int[]{});
        assertEquals(bannerRed, defaultColor);
        assertTrue(contrastRatio(titleYellow, defaultColor) >= 4.5);

        // Focused state
        int[] focusedState = new int[]{android.R.attr.state_focused};
        drawable.setState(focusedState);
        int focusedColor = extractSolidColor(drawable.getCurrent(), focusedState);
        assertEquals(mutedRed, focusedColor);
        assertTrue(contrastRatio(titleYellow, focusedColor) >= 4.5);

        // Pressed state
        int[] pressedState = new int[]{android.R.attr.state_pressed};
        drawable.setState(pressedState);
        int pressedColor = extractSolidColor(drawable.getCurrent(), pressedState);
        assertEquals(mutedRed, pressedColor);
        assertTrue(contrastRatio(titleYellow, pressedColor) >= 4.5);

        // Focused and pressed state
        int[] focusedPressedState = new int[]{android.R.attr.state_focused, android.R.attr.state_pressed};
        drawable.setState(focusedPressedState);
        int focusedPressedColor = extractSolidColor(drawable.getCurrent(), focusedPressedState);
        assertEquals(mutedRed, focusedPressedColor);
        assertTrue(contrastRatio(titleYellow, focusedPressedColor) >= 4.5);
    }

    @Test
    public void allLayoutButtonsUsingPrimaryBackgroundUseTitleYellowTextColor() {
        int titleYellow = context.getColor(R.color.title_yellow);
        LayoutInflater inflater = LayoutInflater.from(context);

        int[] layouts = {
                R.layout.activity_main,
                R.layout.activity_settings,
                R.layout.activity_onboarding,
                R.layout.dialog_force_mode,
                R.layout.view_force_panel,
                R.layout.view_home_warning,
                R.layout.view_network_decision,
                R.layout.view_webhook_form
        };

        for (int layoutId : layouts) {
            android.widget.FrameLayout container = new android.widget.FrameLayout(context);
            View root = inflater.inflate(layoutId, container, true);
            assertPrimaryButtonsHaveSafeContrast(root, titleYellow);
        }
    }

    @Test
    public void dynamicallyCreatedButtonsUseSafeTextColorForPrimaryBackground() throws Exception {
        int titleYellow = context.getColor(R.color.title_yellow);

        MainActivity main = Robolectric.buildActivity(MainActivity.class).setup().get();

        // NetworkListRenderer stackedButton
        NetworkListRenderer renderer = new NetworkListRenderer(main, new android.widget.LinearLayout(main), () -> {});
        Method stackedBtnMethod = NetworkListRenderer.class.getDeclaredMethod(
                "stackedButton", String.class, boolean.class, View.OnClickListener.class);
        stackedBtnMethod.setAccessible(true);

        Button primaryStacked = (Button) stackedBtnMethod.invoke(renderer, "Trust", true, null);
        assertNotNull(primaryStacked);
        assertEquals(titleYellow, primaryStacked.getCurrentTextColor());
        assertTrue(contrastRatio(primaryStacked.getCurrentTextColor(), context.getColor(R.color.banner_red)) >= 4.5);
        assertTrue(contrastRatio(primaryStacked.getCurrentTextColor(), context.getColor(R.color.muted_red)) >= 4.5);

        // OnboardingActionSteps actionButton
        Method actionBtnMethod = OnboardingActionSteps.class.getDeclaredMethod(
                "actionButton", android.app.Activity.class, String.class, boolean.class, View.OnClickListener.class);
        actionBtnMethod.setAccessible(true);

        Button primaryAction = (Button) actionBtnMethod.invoke(null, main, "Action", true, null);
        assertNotNull(primaryAction);
        assertEquals(titleYellow, primaryAction.getCurrentTextColor());
        assertTrue(contrastRatio(primaryAction.getCurrentTextColor(), context.getColor(R.color.banner_red)) >= 4.5);
        assertTrue(contrastRatio(primaryAction.getCurrentTextColor(), context.getColor(R.color.muted_red)) >= 4.5);
    }

    private void assertPrimaryButtonsHaveSafeContrast(View view, int expectedColor) {
        if (view instanceof Button) {
            Button button = (Button) view;
            Drawable bg = button.getBackground();
            if (bg instanceof StateListDrawable) {
                // If it resolves to banner_red in default state, it is bg_btn_primary
                bg.setState(new int[]{});
                int color = extractSolidColor(bg.getCurrent(), new int[]{});
                if (color == context.getColor(R.color.banner_red)) {
                    int textColor = button.getCurrentTextColor();
                    assertEquals("Button " + resourceName(button) + " must use title_yellow text color",
                            expectedColor, textColor);
                    assertTrue(contrastRatio(textColor, context.getColor(R.color.banner_red)) >= 4.5);
                    assertTrue(contrastRatio(textColor, context.getColor(R.color.muted_red)) >= 4.5);
                }
            }
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                assertPrimaryButtonsHaveSafeContrast(group.getChildAt(i), expectedColor);
            }
        }
    }

    private String resourceName(View view) {
        try {
            return context.getResources().getResourceEntryName(view.getId());
        } catch (Exception e) {
            return String.valueOf(view.getId());
        }
    }

    private int extractSolidColor(Drawable current, int[] state) {
        if (current instanceof ColorDrawable) {
            return ((ColorDrawable) current).getColor();
        }
        if (current instanceof GradientDrawable) {
            ColorStateList csl = ((GradientDrawable) current).getColor();
            if (csl != null) {
                return csl.getColorForState(state, csl.getDefaultColor());
            }
        }
        throw new IllegalArgumentException("Cannot extract solid color from drawable: " + current);
    }
}
