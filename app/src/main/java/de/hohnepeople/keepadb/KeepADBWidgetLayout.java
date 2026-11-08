package de.hohnepeople.keepadb;

import android.appwidget.AppWidgetManager;
import android.os.Bundle;

/**
 * #912: which layout the control widget uses for the room it has. The room is the size the
 * launcher reports in dp, made stricter by the font scale: a larger font needs more room for the
 * same label, so every threshold grows with the scale and the widget falls back to a smaller
 * layout (fewer, readable controls) instead of squeezing three of them into the same width.
 *
 * <p>The classes are approximate cell counts, not launcher cells: the thresholds follow the
 * launchers' usual {@code 70 * cells - 30} dp. Every class keeps the tiles at 48 dp or more:
 * <ul>
 *   <li>{@link #SINGLE} about 1x1: the main action alone.</li>
 *   <li>{@link #STACK} narrow and tall (1x2 and up): two actions on top of each other.</li>
 *   <li>{@link #DUO} about 2x1: two actions side by side.</li>
 *   <li>{@link #GRID} about 2x2: all three actions, two in the first row.</li>
 *   <li>{@link #GRID_STATUS} taller than 2x2: the grid plus the status box.</li>
 *   <li>{@link #STRIP} about 4x1: the three actions in a row.</li>
 *   <li>{@link #PANEL} about 4x2: the three actions plus the status box and the settings gear.</li>
 * </ul>
 * What a size class shows is decided here and only here; the renderer follows it.
 */
final class KeepADBWidgetLayout {

    /** One tile column per control; width and height are the smallest room the class is meant for. */
    enum SizeClass {
        SINGLE(1, 0, 0, 0, 0),
        STACK(1, 1, 0, 0, 110),
        DUO(2, 0, 0, 110, 0),
        GRID(2, 1, 0, 110, 110),
        GRID_STATUS(2, 1, 2, 110, 170),
        STRIP(3, 0, 0, 250, 0),
        PANEL(3, 0, 2, 250, 110);

        /** Tiles in the first and in the second row. */
        final int firstRowTiles;
        final int secondRowTiles;
        /**
         * Status lines below the tiles (0: none; otherwise the endpoint or the reason, then the
         * webhook state). Tiles of a class with a status box keep their flat height.
         */
        final int detailLines;
        /** The smallest room in dp the class is chosen for, before the font scale. */
        final int minWidthDp;
        final int minHeightDp;

        SizeClass(int firstRowTiles, int secondRowTiles, int detailLines, int minWidthDp,
                int minHeightDp) {
            this.firstRowTiles = firstRowTiles;
            this.secondRowTiles = secondRowTiles;
            this.detailLines = detailLines;
            this.minWidthDp = minWidthDp;
            this.minHeightDp = minHeightDp;
        }

        int actionCount() {
            return firstRowTiles + secondRowTiles;
        }

        boolean hasDetails() {
            return detailLines > 0;
        }
    }

    /** Declared size of the widget (widget_info.xml minWidth/minHeight): the legacy 2x1-ish default. */
    static final int DEFAULT_WIDTH_DP = 110;
    static final int DEFAULT_HEIGHT_DP = 56;

    /** Above this font scale two text lines no longer fit a 48 dp tile; the label then gives way. */
    static final float MAX_FONT_SCALE_WITH_LABELS = 1.5f;

    private static final int WIDE_DP = 250;
    private static final int TWO_COLUMNS_DP = 110;
    private static final int TWO_ROWS_DP = 110;
    private static final int STATUS_DP = 170;

    private KeepADBWidgetLayout() {}

    /** The font scale as a divisor: a scale below 1 never grants more room than there is. */
    private static float scaleOf(float fontScale) {
        return fontScale > 1f ? fontScale : 1f;
    }

    static SizeClass choose(int widthDp, int heightDp, float fontScale) {
        float scale = scaleOf(fontScale);
        boolean wide = widthDp >= WIDE_DP * scale;
        boolean twoColumns = widthDp >= TWO_COLUMNS_DP * scale;
        boolean twoRows = heightDp >= TWO_ROWS_DP * scale;
        boolean status = heightDp >= STATUS_DP * scale;
        if (wide) return twoRows ? SizeClass.PANEL : SizeClass.STRIP;
        if (twoColumns) {
            if (!twoRows) return SizeClass.DUO;
            return status ? SizeClass.GRID_STATUS : SizeClass.GRID;
        }
        return twoRows ? SizeClass.STACK : SizeClass.SINGLE;
    }

    /**
     * The class for the options the launcher reports. In portrait the widget is {@code MIN_WIDTH}
     * wide and {@code MAX_HEIGHT} high, in landscape {@code MAX_WIDTH} wide and {@code MIN_HEIGHT}
     * high. Without usable options (a legacy instance the launcher never reported on) the declared
     * default size applies.
     */
    static SizeClass choose(Bundle options, boolean landscape, float fontScale) {
        int width = 0;
        int height = 0;
        if (options != null) {
            width = options.getInt(landscape ? AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH
                    : AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
            height = options.getInt(landscape ? AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT
                    : AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0);
        }
        if (width <= 0 || height <= 0) {
            width = DEFAULT_WIDTH_DP;
            height = DEFAULT_HEIGHT_DP;
        }
        return choose(width, height, fontScale);
    }

    /** The room in dp a responsive (Android 12+) entry for this class is keyed by. */
    static float responsiveWidthDp(SizeClass sizeClass, float fontScale) {
        return Math.max(1f, sizeClass.minWidthDp * scaleOf(fontScale));
    }

    static float responsiveHeightDp(SizeClass sizeClass, float fontScale) {
        return Math.max(1f, sizeClass.minHeightDp * scaleOf(fontScale));
    }

    /** Whether the tiles show their label above the state; otherwise the state stands alone. */
    static boolean showLabels(float fontScale) {
        return fontScale <= MAX_FONT_SCALE_WITH_LABELS;
    }
}
