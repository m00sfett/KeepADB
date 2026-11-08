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
 * ({@code wide4 = w >= 250 fs}, {@code wide2 = w >= 136 fs}, {@code tall = h >= 140 fs}); in a
 * short widget the hero gets as many square buttons as fit beside 70 dp of room for it.
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
        // Font 1.3 (the matrix rows): the thresholds are 177 / 325 dp wide and 182 dp high, one
        // square button needs 158 dp, two need 215 dp.
        assertEquals("2x2 falls back to the stack", SizeClass.STACK, cell(152, 196, 1.3f));
        assertEquals("4x1 keeps the hero and two square buttons", SizeClass.ROW2, cell(304, 92, 1.3f));
        assertEquals("2x1 keeps only the hero, with its port", SizeClass.HERO, cell(152, 92, 1.3f));
        assertEquals(SizeClass.ONE, cell(72, 92, 1.3f));
        assertEquals("4x2 falls back to the grid", SizeClass.GRID, cell(304, 196, 1.3f));
        assertEquals(SizeClass.PANEL, cell(325, 182, 1.3f));
        assertEquals(SizeClass.GRID, cell(177, 182, 1.3f));
        assertEquals(SizeClass.STRIP, cell(325, 181, 1.3f));
        // Font 2.0: the grid needs 272 x 280 dp, the strip 500 dp, one square button 207 dp.
        assertEquals(SizeClass.HERO, cell(152, 196, 2f));
        assertEquals(SizeClass.ROW2, cell(304, 92, 2f));
        assertEquals(SizeClass.STRIP, cell(500, 92, 2f));
        assertEquals(SizeClass.PANEL, cell(500, 280, 2f));
        assertEquals(SizeClass.STACK, cell(152, 280, 2f));
        assertEquals(SizeClass.GRID, cell(272, 280, 2f));
    }

    @Test
    public void tallAndNarrowArrangementsHaveTheirOwnLayouts() {
        assertEquals("narrow and tall: the hero over two square buttons", SizeClass.STACK, cell(114, 140, 1f));
        assertEquals("below 114 dp the two square buttons would be under 48 dp", SizeClass.ONE, cell(113, 140, 1f));
        assertEquals(SizeClass.ONE, cell(109, 400, 1f));
        assertEquals(SizeClass.GRID, cell(136, 140, 1f));
        assertEquals(SizeClass.STACK, cell(135, 140, 1f));
        assertEquals("tall but not wide: the compact classes", SizeClass.ROW1, cell(150, 139, 1f));
    }

    @Test
    public void everyThresholdIsExactlyItsBoundary() {
        // The hero shows its port from 130 dp; narrower it is the 1x1 shape.
        assertEquals(SizeClass.ONE, cell(129, 100, 1f));
        assertEquals(SizeClass.HERO, cell(130, 100, 1f));
        assertEquals(SizeClass.HERO, cell(136, 100, 1f));
        // One square button needs 10 + 70 + 57 = 137 dp, two need 194 dp.
        assertEquals(SizeClass.ROW1, cell(137, 100, 1f));
        assertEquals(SizeClass.ROW1, cell(193, 100, 1f));
        assertEquals(SizeClass.ROW2, cell(194, 100, 1f));
        assertEquals(SizeClass.ROW2, cell(249, 100, 1f));
        assertEquals(SizeClass.STRIP, cell(250, 100, 1f));
        assertEquals(SizeClass.STRIP, cell(250, 139, 1f));
        assertEquals(SizeClass.PANEL, cell(250, 140, 1f));
        assertEquals(SizeClass.GRID, cell(249, 140, 1f));
    }

    /**
     * What the launchers report for one to four cells (portrait: minimum width, maximum height),
     * measured on the emulators (#912 round 3). Android 14 (Pixel Launcher): 68, 144, 220, 296 dp
     * wide, 89 and 193 dp high. Android 11: 60, 137, 214, 291 dp wide, 80 and 176 dp high. The
     * matrix's 152 and 196 dp were more generous than either.
     */
    @Test
    public void theSizesRealLaunchersReportSelectTheIntendedClasses() {
        int[][] launchers = {
                {68, 144, 220, 296, 89, 193},
                {60, 137, 214, 291, 80, 176}};
        for (int[] l : launchers) {
            String which = "cells " + l[1];
            assertEquals(which + " 1x1", SizeClass.ONE, cell(l[0], l[4], 1f));
            assertEquals(which + " 2x1", SizeClass.ROW1, cell(l[1], l[4], 1f));
            assertEquals(which + " 3x1", SizeClass.ROW2, cell(l[2], l[4], 1f));
            assertEquals(which + " 4x1", SizeClass.STRIP, cell(l[3], l[4], 1f));
            assertEquals(which + " 1x2", SizeClass.ONE, cell(l[0], l[5], 1f));
            assertEquals(which + " 2x2", SizeClass.GRID, cell(l[1], l[5], 1f));
            assertEquals(which + " 4x2", SizeClass.PANEL, cell(l[3], l[5], 1f));
            // Font 1.3: 2x1 only the hero, 4x1 the hero and two square buttons; with 182 dp for
            // two rows (Android 14) 2x2 is the stack and 4x2 the grid, with 176 dp (Android 11)
            // the hero of the grid would be too short for its text, so both stay one row.
            assertEquals(which + " 2x1 @1.3", SizeClass.HERO, cell(l[1], l[4], 1.3f));
            assertEquals(which + " 4x1 @1.3", SizeClass.ROW2, cell(l[3], l[4], 1.3f));
            boolean roomy = l[5] >= 182;
            assertEquals(which + " 2x2 @1.3", roomy ? SizeClass.STACK : SizeClass.HERO,
                    cell(l[1], l[5], 1.3f));
            assertEquals(which + " 4x2 @1.3", roomy ? SizeClass.GRID : SizeClass.ROW2,
                    cell(l[3], l[5], 1.3f));
            // Font 2.0: the hero alone in a 2x2 and a 2x1, the hero with two buttons in a 4x1.
            assertEquals(which + " 2x2 @2", SizeClass.HERO, cell(l[1], l[5], 2f));
            assertEquals(which + " 2x1 @2", SizeClass.HERO, cell(l[1], l[4], 2f));
            assertEquals(which + " 4x1 @2", SizeClass.ROW2, cell(l[3], l[4], 2f));
        }
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
