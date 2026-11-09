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
 * ({@code wide4 = w >= 250 fs}, {@code wide2 = w >= 136 fs}); in a short widget the hero gets as
 * many square buttons as fit beside 70 dp x fs of room for it. A tall class (stack, grid, panel)
 * is chosen only when everything it stacks fits: the frame, the gaps, the 48 dp pills, the 52 dp
 * buttons, the status line and a hero that holds its symbol and port box. Otherwise the next smaller
 * class applies.
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

    /** The content heights of the tall classes: what each one stacks, at font 1.0, 1.3 and 2.0. */
    @Test
    public void theTallClassesNeedTheHeightOfWhatTheyStack() {
        // Stack: 5 root + 5 slot + hero (16 + 26 + 4 + 21 fs) + 57 for the buttons.
        assertEquals(134, SizeClass.STACK.minHeightDp(1f));
        assertEquals(141, SizeClass.STACK.minHeightDp(1.3f));
        assertEquals(155, SizeClass.STACK.minHeightDp(2f));
        // Grid: 5 root + 5 slot + hero (12 + 24 + 4 + 28 fs) + two pills of 48 + 5.
        assertEquals(184, SizeClass.GRID.minHeightDp(1f));
        assertEquals(193, SizeClass.GRID.minHeightDp(1.3f));
        assertEquals(220, SizeClass.GRID.minHeightDp(2f));
        // Panel: 5 root + the hero over two rows (24 + 34 + texts + port box) + the status line.
        assertEquals(176, SizeClass.PANEL.minHeightDp(1f));
        assertEquals(194, SizeClass.PANEL.minHeightDp(1.3f));
        assertEquals(244, SizeClass.PANEL.minHeightDp(2f));
        for (float fs : new float[] {1f, 1.3f, 2f}) {
            // Whatever the scale: two 48 dp pills with their gaps (106 dp) plus the frame fit.
            assertTrue(SizeClass.GRID.minHeightDp(fs) >= 5 + 5 + 48 + 106);
            assertTrue(SizeClass.PANEL.minHeightDp(fs) >= 5 + 106 + 48);
            assertTrue(SizeClass.STACK.minHeightDp(fs) >= 5 + 5 + 48 + 57);
        }
    }

    /**
     * Review finding: at font 1.0 and 152 x 140 dp both paths chose the grid, whose two fixed pills
     * and gaps left about 24 dp for the hero (symbol and port clipped, touch target under 48 dp).
     * The grid now needs 184 dp; a room that cannot hold it gets the stack.
     */
    @Test
    public void aRoomThatCannotHoldTheContentOfAClassGetsTheNextSmallerOne() {
        assertEquals("152 x 140", SizeClass.STACK, cell(152, 140, 1f));
        assertEquals(SizeClass.STACK, cell(152, 183, 1f));
        assertEquals(SizeClass.GRID, cell(152, 184, 1f));
        assertEquals("the panel (176 dp) falls back to the stack, wide as it is", SizeClass.STACK,
                cell(250, 175, 1f));
        assertEquals(SizeClass.PANEL, cell(250, 176, 1f));
        assertEquals(SizeClass.STACK, cell(250, 133 + 1, 1f));
        assertEquals(SizeClass.STRIP, cell(250, 133, 1f));
        assertEquals(SizeClass.STACK, cell(249, 183, 1f));
        assertEquals(SizeClass.GRID, cell(249, 184, 1f));
    }

    @Test
    public void aLargerFontFallsBackToASmallerClass() {
        // Font 1.3: widths 177 / 325 dp; one square button needs 158 dp, two need 215 dp; the stack
        // needs 141 dp, the grid 193 dp, the panel 194 dp.
        assertEquals("2x2 falls back to the stack", SizeClass.STACK, cell(152, 196, 1.3f));
        assertEquals("4x1 keeps the hero and two square buttons", SizeClass.ROW2, cell(304, 92, 1.3f));
        assertEquals("2x1 keeps only the hero, with its port", SizeClass.HERO, cell(152, 92, 1.3f));
        assertEquals(SizeClass.ONE, cell(72, 92, 1.3f));
        assertEquals("4x2 falls back to the grid", SizeClass.GRID, cell(304, 196, 1.3f));
        assertEquals(SizeClass.PANEL, cell(325, 194, 1.3f));
        assertEquals("one dp short of the panel", SizeClass.GRID, cell(325, 193, 1.3f));
        assertEquals(SizeClass.GRID, cell(177, 193, 1.3f));
        assertEquals(SizeClass.STACK, cell(177, 192, 1.3f));
        assertEquals(SizeClass.STRIP, cell(325, 140, 1.3f));
        assertEquals(SizeClass.STACK, cell(325, 141, 1.3f));
        // Font 2.0: the grid needs 272 x 220 dp, the panel 500 x 244, the stack 155 dp, the strip
        // 500 dp wide, one square button 207 dp and two 264 dp.
        assertEquals("a 2x2 is the stack", SizeClass.STACK, cell(152, 196, 2f));
        assertEquals("a 4x2 is the stack too: 193 dp are not enough for the grid", SizeClass.STACK,
                cell(304, 196, 2f));
        assertEquals(SizeClass.ROW2, cell(304, 92, 2f));
        assertEquals(SizeClass.STRIP, cell(500, 92, 2f));
        assertEquals(SizeClass.PANEL, cell(500, 244, 2f));
        assertEquals(SizeClass.GRID, cell(500, 243, 2f));
        assertEquals(SizeClass.STACK, cell(500, 219, 2f));
        assertEquals(SizeClass.GRID, cell(272, 220, 2f));
        assertEquals(SizeClass.HERO, cell(152, 154, 2f));
    }

    @Test
    public void tallAndNarrowArrangementsHaveTheirOwnLayouts() {
        assertEquals("narrow and tall: the hero over two square buttons", SizeClass.STACK, cell(114, 140, 1f));
        assertEquals("below 114 dp the two square buttons would be under 48 dp", SizeClass.ONE, cell(113, 140, 1f));
        assertEquals(SizeClass.ONE, cell(109, 400, 1f));
        assertEquals(SizeClass.GRID, cell(136, 184, 1f));
        assertEquals(SizeClass.STACK, cell(135, 400, 1f));
        assertEquals("not tall enough for the stack: the compact classes", SizeClass.ROW1, cell(150, 133, 1f));
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
        assertEquals(SizeClass.STRIP, cell(250, 133, 1f));
        assertEquals(SizeClass.STACK, cell(250, 134, 1f));
        assertEquals(SizeClass.PANEL, cell(250, 176, 1f));
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
            boolean tall = l[5] >= 193;   // two rows of the Android 14 launcher
            assertEquals(which + " 1x1", SizeClass.ONE, cell(l[0], l[4], 1f));
            assertEquals(which + " 2x1", SizeClass.ROW1, cell(l[1], l[4], 1f));
            assertEquals(which + " 3x1", SizeClass.ROW2, cell(l[2], l[4], 1f));
            assertEquals(which + " 4x1", SizeClass.STRIP, cell(l[3], l[4], 1f));
            assertEquals(which + " 1x2", SizeClass.ONE, cell(l[0], l[5], 1f));
            // 2x2: the grid needs 184 dp. Android 14 has 193, Android 11 only 176: the stack.
            assertEquals(which + " 2x2", tall ? SizeClass.GRID : SizeClass.STACK, cell(l[1], l[5], 1f));
            assertEquals(which + " 4x2", SizeClass.PANEL, cell(l[3], l[5], 1f));
            // Font 1.3: 2x1 only the hero, 4x1 the hero and two square buttons, 2x2 the stack; 4x2
            // the grid on Android 14 (193 dp), the stack on Android 11 (176 dp, grid needs 193).
            assertEquals(which + " 2x1 @1.3", SizeClass.HERO, cell(l[1], l[4], 1.3f));
            assertEquals(which + " 4x1 @1.3", SizeClass.ROW2, cell(l[3], l[4], 1.3f));
            assertEquals(which + " 2x2 @1.3", SizeClass.STACK, cell(l[1], l[5], 1.3f));
            assertEquals(which + " 4x2 @1.3", tall ? SizeClass.GRID : SizeClass.STACK,
                    cell(l[3], l[5], 1.3f));
            // Font 2.0: the stack needs 155 dp, so 2x2 and 4x2 are the stack on both.
            assertEquals(which + " 2x1 @2", SizeClass.HERO, cell(l[1], l[4], 2f));
            assertEquals(which + " 4x1 @2", SizeClass.ROW2, cell(l[3], l[4], 2f));
            assertEquals(which + " 2x2 @2", SizeClass.STACK, cell(l[1], l[5], 2f));
            assertEquals(which + " 4x2 @2", SizeClass.STACK, cell(l[3], l[5], 2f));
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

    @Test
    public void theReportedSizesComeFromTheListThenTheClassicOptionsThenTheDefault() {
        Bundle list = new Bundle();
        java.util.ArrayList<android.util.SizeF> sizes = new java.util.ArrayList<>();
        sizes.add(new android.util.SizeF(144, 193));
        sizes.add(new android.util.SizeF(296, 89));
        sizes.add(new android.util.SizeF(144, 193));   // a duplicate
        sizes.add(new android.util.SizeF(0, 50));      // unusable
        list.putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, sizes);
        list.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 10);   // ignored: the list wins
        list.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 10);
        assertEquals(java.util.Arrays.asList(new android.util.SizeF(144, 193), new android.util.SizeF(296, 89)),
                KeepADBWidgetLayout.reportedSizes(list));

        Bundle classic = new Bundle();
        classic.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 137);
        classic.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 176);
        classic.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 291);
        classic.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 80);
        assertEquals("portrait is min width x max height, landscape max width x min height",
                java.util.Arrays.asList(new android.util.SizeF(137, 176), new android.util.SizeF(291, 80)),
                KeepADBWidgetLayout.reportedSizes(classic));
        classic.putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES,
                new java.util.ArrayList<android.util.SizeF>());
        assertEquals("an empty list is no list", 2, KeepADBWidgetLayout.reportedSizes(classic).size());

        android.util.SizeF fallback = new android.util.SizeF(KeepADBWidgetLayout.DEFAULT_WIDTH_DP,
                KeepADBWidgetLayout.DEFAULT_HEIGHT_DP);
        assertEquals(java.util.Arrays.asList(fallback), KeepADBWidgetLayout.reportedSizes(null));
        assertEquals(java.util.Arrays.asList(fallback), KeepADBWidgetLayout.reportedSizes(new Bundle()));
    }

    @Test
    public void aReportedSizeIsRoundedDownAndNeverGetsMoreRoomThanItHas() {
        assertEquals(SizeClass.HERO, KeepADBWidgetLayout.choose(new android.util.SizeF(136.9f, 89f), 1f));
        assertEquals(SizeClass.ROW1, KeepADBWidgetLayout.choose(new android.util.SizeF(137f, 89f), 1f));
        assertEquals(SizeClass.ROW1, KeepADBWidgetLayout.choose(new android.util.SizeF(193.9f, 89f), 1f));
        assertEquals("the size the review measured: 304 x 140 dp is the stack",
                SizeClass.STACK, KeepADBWidgetLayout.choose(new android.util.SizeF(304f, 140f), 1f));
    }
}
