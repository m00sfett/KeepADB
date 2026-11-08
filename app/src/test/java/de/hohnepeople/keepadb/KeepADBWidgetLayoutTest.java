package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.appwidget.AppWidgetManager;
import android.os.Bundle;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import de.hohnepeople.keepadb.KeepADBWidgetLayout.SizeClass;

/**
 * #912: the layout follows the actual size and the font scale, including tall and narrow
 * arrangements; the thresholds are the launchers' usual {@code 70 * cells - 30} dp.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBWidgetLayoutTest {

    private static SizeClass at(int widthDp, int heightDp) {
        return KeepADBWidgetLayout.choose(widthDp, heightDp, 1f);
    }

    @Test
    public void theApproximateCellSizesSelectTheirClasses() {
        assertEquals("1x1", SizeClass.SINGLE, at(40, 40));
        assertEquals("1x1 on a roomier launcher", SizeClass.SINGLE, at(73, 85));
        assertEquals("2x1", SizeClass.DUO, at(110, 40));
        assertEquals("3x1 has no class of its own", SizeClass.DUO, at(180, 56));
        assertEquals("2x2", SizeClass.GRID, at(110, 110));
        assertEquals("2x2 on a roomier launcher", SizeClass.GRID, at(160, 140));
        assertEquals("4x1", SizeClass.STRIP, at(250, 40));
        assertEquals("4x1 on a roomier launcher", SizeClass.STRIP, at(300, 90));
        assertEquals("4x2", SizeClass.PANEL, at(250, 110));
        assertEquals("5x4", SizeClass.PANEL, at(320, 250));
    }

    @Test
    public void tallAndNarrowArrangementsHaveTheirOwnLayouts() {
        assertEquals("1x2", SizeClass.STACK, at(40, 110));
        assertEquals("1x4", SizeClass.STACK, at(56, 250));
        assertEquals("2x3 adds a status line to the grid", SizeClass.GRID_STATUS, at(110, 180));
        assertEquals("2x4", SizeClass.GRID_STATUS, at(150, 250));
        assertEquals("wide but one row stays a strip however high the dp are",
                SizeClass.STRIP, at(400, 100));
    }

    @Test
    public void everyThresholdIsExactlyItsBoundary() {
        assertEquals(SizeClass.SINGLE, at(109, 109));
        assertEquals(SizeClass.DUO, at(110, 109));
        assertEquals(SizeClass.STACK, at(109, 110));
        assertEquals(SizeClass.GRID, at(110, 110));
        assertEquals(SizeClass.GRID, at(249, 169));
        assertEquals(SizeClass.GRID_STATUS, at(249, 170));
        assertEquals(SizeClass.STRIP, at(250, 109));
        assertEquals(SizeClass.PANEL, at(250, 110));
    }

    @Test
    public void aLargerFontNeedsMoreRoomForTheSameLayout() {
        // 4x1 at normal size, but not once the font is 1.3 times larger: the strip would squeeze
        // four labels into the same width.
        assertEquals(SizeClass.STRIP, KeepADBWidgetLayout.choose(260, 56, 1f));
        assertEquals(SizeClass.DUO, KeepADBWidgetLayout.choose(260, 56, 1.3f));
        assertEquals(SizeClass.STRIP, KeepADBWidgetLayout.choose(260, 56, 1.0f));
        // The same widget needs 250 * 1.3 = 325 dp for the strip.
        assertEquals(SizeClass.STRIP, KeepADBWidgetLayout.choose(330, 56, 1.3f));
        // 2x2 keeps its grid only with the room its larger text needs.
        assertEquals(SizeClass.GRID, KeepADBWidgetLayout.choose(150, 150, 1f));
        assertEquals(SizeClass.SINGLE, KeepADBWidgetLayout.choose(150, 150, 1.5f));
        assertEquals(SizeClass.DUO, KeepADBWidgetLayout.choose(170, 100, 1.5f));
    }

    @Test
    public void aFontScaleBelowOneNeverGrantsMoreRoom() {
        assertEquals(SizeClass.DUO, KeepADBWidgetLayout.choose(240, 56, 0.85f));
        assertEquals(KeepADBWidgetLayout.choose(240, 56, 1f),
                KeepADBWidgetLayout.choose(240, 56, 0.85f));
    }

    @Test
    public void labelsGiveWayToTheStateOnlyAtLargeFonts() {
        assertTrue(KeepADBWidgetLayout.showLabels(1f));
        assertTrue(KeepADBWidgetLayout.showLabels(1.5f));
        assertFalse(KeepADBWidgetLayout.showLabels(1.51f));
        assertFalse(KeepADBWidgetLayout.showLabels(2f));
    }

    @Test
    public void theReportedOptionsAreReadForThePortraitAndTheLandscapeOrientation() {
        Bundle options = new Bundle();
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 110);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 250);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 56);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 130);

        // Portrait: MIN_WIDTH wide, MAX_HEIGHT high. Landscape: MAX_WIDTH wide, MIN_HEIGHT high.
        assertEquals(SizeClass.GRID, KeepADBWidgetLayout.choose(options, false, 1f));
        assertEquals(SizeClass.STRIP, KeepADBWidgetLayout.choose(options, true, 1f));
    }

    @Test
    public void withoutUsableOptionsTheDeclaredDefaultSizeApplies() {
        // A widget the launcher never reported on, such as one placed before this version.
        assertEquals(SizeClass.DUO, KeepADBWidgetLayout.choose((Bundle) null, false, 1f));
        assertEquals(SizeClass.DUO, KeepADBWidgetLayout.choose(new Bundle(), true, 1f));
    }

    @Test
    public void everyClassShowsAtMostFourActionsAndTheDetailsComeLast() {
        for (SizeClass sizeClass : SizeClass.values()) {
            assertTrue(sizeClass.name(), sizeClass.actionCount() >= 1 && sizeClass.actionCount() <= 4);
        }
        assertEquals(1, SizeClass.SINGLE.actionCount());
        assertEquals(2, SizeClass.DUO.actionCount());
        assertEquals(2, SizeClass.STACK.actionCount());
        assertEquals(4, SizeClass.GRID.actionCount());
        assertEquals(4, SizeClass.STRIP.actionCount());
        assertEquals(4, SizeClass.PANEL.actionCount());
        assertFalse(SizeClass.GRID.hasDetails());
        assertFalse(SizeClass.STRIP.hasDetails());
        assertEquals(2, SizeClass.GRID_STATUS.detailLines);
        assertEquals(2, SizeClass.PANEL.detailLines);
    }
}
