package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.appwidget.AppWidgetManager;
import android.os.Bundle;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import de.hohnepeople.keepadb.KeepADBWidgetLayout.SizeClass;

/**
 * #912: the layout follows the actual size and the font scale. The thresholds grow with the scale
 * ({@code wide4 = w >= 250 fs}, {@code wide2 = w >= 140 fs}, {@code tall = h >= 145 fs}); in a
 * short widget the hero gets as many square buttons as fit beside 76 dp of room for it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBWidgetLayoutTest {

    /** Launcher cells as the reference matrix measures them: 72 or 152 wide, 92 or 196 high, 304 wide. */
    private static SizeClass cell(int widthDp, int heightDp, float fontScale) {
        return KeepADBWidgetLayout.choose(widthDp, heightDp, fontScale);
    }

    @Test
    public void theCellSizesSelectTheirClassesAtNormalFontSize() {
        assertEquals("1x1", SizeClass.ONE, cell(72, 92, 1f));
        assertEquals("2x1 is the hero and one square button", SizeClass.ROW1, cell(152, 92, 1f));
        assertEquals("2x2", SizeClass.GRID, cell(152, 196, 1f));
        assertEquals("4x1", SizeClass.STRIP, cell(304, 92, 1f));
        assertEquals("4x2", SizeClass.PANEL, cell(304, 196, 1f));
        assertEquals("1x2 has no room for more than the hero", SizeClass.ONE, cell(72, 196, 1f));
        assertEquals("3x1 fits the hero and two square buttons", SizeClass.ROW2, cell(228, 92, 1f));
        assertEquals("a wide short widget stays a strip however high the dp are",
                SizeClass.STRIP, cell(400, 100, 1f));
        assertEquals("a big widget stays the panel", SizeClass.PANEL, cell(500, 400, 1f));
    }

    @Test
    public void aLargerFontFallsBackToASmallerClass() {
        // Font 1.3 (the matrix rows): the thresholds are 182 / 325 dp wide and 189 dp high, and one
        // square button needs 171 dp, two need 228 dp.
        assertEquals("2x2 falls back to the stack", SizeClass.STACK, cell(152, 196, 1.3f));
        assertEquals("4x1 keeps the hero and two square buttons", SizeClass.ROW2, cell(304, 92, 1.3f));
        assertEquals("2x1 keeps only the hero, with its port", SizeClass.HERO, cell(152, 92, 1.3f));
        assertEquals(SizeClass.ONE, cell(72, 92, 1.3f));
        assertEquals("4x2 falls back to the grid", SizeClass.GRID, cell(304, 196, 1.3f));
        assertEquals(SizeClass.PANEL, cell(325, 189, 1.3f));
        assertEquals(SizeClass.GRID, cell(182, 189, 1.3f));
        assertEquals(SizeClass.STRIP, cell(325, 188, 1.3f));
        // Font 2.0: the grid needs 280 x 290 dp, the strip 500 dp.
        assertEquals(SizeClass.HERO, cell(152, 196, 2f));
        assertEquals(SizeClass.ROW2, cell(304, 92, 2f));
        assertEquals(SizeClass.STRIP, cell(500, 92, 2f));
        assertEquals(SizeClass.PANEL, cell(500, 290, 2f));
        assertEquals(SizeClass.STACK, cell(152, 290, 2f));
        assertEquals(SizeClass.GRID, cell(280, 290, 2f));
    }

    @Test
    public void tallAndNarrowArrangementsHaveTheirOwnLayouts() {
        assertEquals("narrow and tall: the hero over two square buttons", SizeClass.STACK, cell(114, 145, 1f));
        assertEquals(SizeClass.ONE, cell(109, 400, 1f));
        assertEquals(SizeClass.GRID, cell(140, 145, 1f));
        assertEquals(SizeClass.STACK, cell(139, 145, 1f));
        assertEquals("below 114 dp the two square buttons would be under 48 dp", SizeClass.ONE, cell(113, 145, 1f));
        assertEquals("tall but not wide: the compact classes", SizeClass.ROW1, cell(150, 144, 1f));
    }

    @Test
    public void everyThresholdIsExactlyItsBoundary() {
        // The hero shows its port from 140 dp; narrower it is the 1x1 shape.
        assertEquals(SizeClass.ONE, cell(139, 100, 1f));
        assertEquals(SizeClass.HERO, cell(140, 100, 1f));
        assertEquals(SizeClass.HERO, cell(142, 100, 1f));
        // One square button needs 10 + 76 + 57 = 143 dp, two need 200 dp.
        assertEquals(SizeClass.ROW1, cell(143, 100, 1f));
        assertEquals(SizeClass.ROW1, cell(199, 100, 1f));
        assertEquals(SizeClass.ROW2, cell(200, 100, 1f));
        assertEquals(SizeClass.ROW2, cell(249, 100, 1f));
        assertEquals(SizeClass.STRIP, cell(250, 100, 1f));
        assertEquals(SizeClass.STRIP, cell(250, 144, 1f));
        assertEquals(SizeClass.PANEL, cell(250, 145, 1f));
        assertEquals(SizeClass.GRID, cell(249, 145, 1f));
    }

    /**
     * What the Pixel launcher (API 34) reports for one to four cells: 68, 144, 220 and 296 dp wide,
     * 89 and 193 dp high (measured on the emulator, #912 round 3). The matrix's 152 dp for two cells
     * was too generous, which would have left the 2x1 widget without its square button.
     */
    @Test
    public void theSizesARealLauncherReportsSelectTheIntendedClasses() {
        assertEquals("1x1", SizeClass.ONE, cell(68, 89, 1f));
        assertEquals("2x1", SizeClass.ROW1, cell(144, 89, 1f));
        assertEquals("3x1", SizeClass.ROW2, cell(220, 89, 1f));
        assertEquals("4x1", SizeClass.STRIP, cell(296, 89, 1f));
        assertEquals("1x2", SizeClass.ONE, cell(68, 193, 1f));
        assertEquals("2x2", SizeClass.GRID, cell(144, 193, 1f));
        assertEquals("4x2", SizeClass.PANEL, cell(296, 193, 1f));
        // Font 1.3: 2x2 is the stack, 2x1 only the hero, 4x1 the hero and two square buttons.
        assertEquals(SizeClass.STACK, cell(144, 193, 1.3f));
        assertEquals(SizeClass.HERO, cell(144, 89, 1.3f));
        assertEquals(SizeClass.ROW2, cell(296, 89, 1.3f));
        assertEquals(SizeClass.GRID, cell(296, 193, 1.3f));
        // Font 2.0: the hero alone in a 2x2 and a 2x1, the hero with two buttons in a 4x1.
        assertEquals(SizeClass.HERO, cell(144, 193, 2f));
        assertEquals(SizeClass.HERO, cell(144, 89, 2f));
        assertEquals(SizeClass.ROW2, cell(296, 89, 2f));
    }

    @Test
    public void aFontScaleBelowOneNeverGrantsMoreRoom() {
        assertEquals(KeepADBWidgetLayout.choose(240, 56, 1f), KeepADBWidgetLayout.choose(240, 56, 0.85f));
        assertEquals(KeepADBWidgetLayout.choose(152, 196, 1f), KeepADBWidgetLayout.choose(152, 196, 0.5f));
    }

    @Test
    public void whatEachClassShowsAndWhereTheStatusAndTheGearAre() {
        assertEquals(1, SizeClass.ONE.actionCount());
        assertEquals(1, SizeClass.HERO.actionCount());
        assertEquals(2, SizeClass.ROW1.actionCount());
        assertEquals(3, SizeClass.ROW2.actionCount());
        assertEquals(3, SizeClass.STACK.actionCount());
        assertEquals(3, SizeClass.STRIP.actionCount());
        assertEquals(3, SizeClass.GRID.actionCount());
        assertEquals(3, SizeClass.PANEL.actionCount());
        for (SizeClass sizeClass : SizeClass.values()) {
            assertEquals(sizeClass.name(), sizeClass == SizeClass.PANEL, sizeClass.hasStatus);
            assertTrue(sizeClass.name(), sizeClass.actionCount() >= 1 && sizeClass.actionCount() <= 3);
        }
    }

    @Test
    public void withoutUsableOptionsTheDeclaredDefaultSizeApplies() {
        // A widget the launcher never reported on, such as one placed before this version: about a
        // 2x1 cell, so the hero and one square button, like the two controls it showed before.
        assertEquals(SizeClass.ROW1, KeepADBWidgetLayout.choose((Bundle) null, false, 1f));
        assertEquals(SizeClass.ROW1, KeepADBWidgetLayout.choose(new Bundle(), true, 1f));
    }

    @Test
    public void theReportedOptionsAreReadForThePortraitAndTheLandscapeOrientation() {
        Bundle options = new Bundle();
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 152);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 304);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 92);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 196);

        // Portrait: MIN_WIDTH wide, MAX_HEIGHT high. Landscape: MAX_WIDTH wide, MIN_HEIGHT high.
        assertEquals(SizeClass.GRID, KeepADBWidgetLayout.choose(options, false, 1f));
        assertEquals(SizeClass.STRIP, KeepADBWidgetLayout.choose(options, true, 1f));
    }
}
