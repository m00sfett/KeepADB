package de.hohnepeople.keepadb;

import android.appwidget.AppWidgetManager;
import android.os.Bundle;

/**
 * #912: which layout the control widget uses for the room it has. The room is the size the
 * launcher reports in dp, made stricter by the font scale {@code fs}: a larger font needs more room
 * for the same words, so the thresholds grow with the scale and the widget falls back to a smaller
 * class (fewer, readable controls) instead of squeezing three of them into the same width.
 *
 * <p>The main control (the "hero", {@link KeepADBWidgetConfig#primary}) is in every class; the
 * others are added as the room grows. Every class keeps its controls at 48 dp or more:
 * <ul>
 *   <li>{@link #ONE} the hero alone, with a short label (about 1x1).</li>
 *   <li>{@link #HERO} the hero alone with its port (narrow room for nothing else).</li>
 *   <li>{@link #ROW1} the hero and one square symbol button (about 2x1).</li>
 *   <li>{@link #ROW2} the hero and two square symbol buttons (wide, not yet 4x1).</li>
 *   <li>{@link #STACK} the hero on top, two square buttons below (narrow and tall).</li>
 *   <li>{@link #STRIP} about 4x1: the hero and two upright tiles.</li>
 *   <li>{@link #GRID} about 2x2: the hero and two pills under it.</li>
 *   <li>{@link #PANEL} about 4x2: the hero over two rows, two pills beside it and the status
 *       line with the settings gear.</li>
 * </ul>
 * What a size class shows is decided here and only here; the renderer follows it.
 */
final class KeepADBWidgetLayout {

    /**
     * The classes in the order of the room they need. {@link #minWidthDp} and {@link #minHeightDp}
     * are the room a class is keyed by on Android 12+ (the launcher shows the largest entry that
     * fits); {@link #choose(int, int, float)} yields the same class for every room.
     */
    enum SizeClass {
        ONE(0, false),
        HERO(0, false),
        ROW1(1, false),
        ROW2(2, false),
        STACK(2, false),
        STRIP(2, false),
        GRID(2, false),
        PANEL(2, true);

        /** Controls besides the hero. */
        final int otherControls;
        /** The status line under the controls, with the settings gear. */
        final boolean hasStatus;

        SizeClass(int otherControls, boolean hasStatus) {
            this.otherControls = otherControls;
            this.hasStatus = hasStatus;
        }

        /** Controls in the widget, the hero included. */
        int actionCount() {
            return 1 + otherControls;
        }

        boolean hasDetails() {
            return hasStatus;
        }

        /** The smallest width in dp the class is chosen for (see {@link #responsiveWidthDp}). */
        int minWidthDp(float fontScale) {
            float s = scaleOf(fontScale);
            switch (this) {
                case ONE:
                    return 1;
                case HERO:
                    return HERO_MIN_DP;
                case ROW1:
                    return squareRowMinWidth(1, s);
                case ROW2:
                    return squareRowMinWidth(2, s);
                case STACK:
                    return STACK_MIN_DP;
                case GRID:
                    return ceil(WIDE2_DP * s);
                case STRIP:
                case PANEL:
                default:
                    return ceil(WIDE4_DP * s);
            }
        }

        /**
         * The smallest height in dp. The one-row classes take whatever a row gives (their texts
         * shrink); each tall class needs the height of what it stacks: the frame, the gaps, the
         * fixed rows (48 dp pills, 52 dp buttons, the status line) and a hero that still holds its
         * symbol and port box, see the constants below. Below it the next smaller class is chosen.
         */
        int minHeightDp(float fontScale) {
            float s = scaleOf(fontScale);
            switch (this) {
                case STACK:
                    return ceil(ROOT_PAD_DP + GAP_DP + heroSHeight(s) + SQUARE_DP + GAP_DP);
                case GRID:
                    return ceil(ROOT_PAD_DP + GAP_DP + heroMHeight(s) + 2 * (PILL_DP + GAP_DP));
                case PANEL:
                    return ceil(ROOT_PAD_DP + Math.max(GAP_DP + heroLHeight(s), 2 * (PILL_DP + GAP_DP))
                            + statusHeight(s));
                default:
                    return 1;
            }
        }
    }

    /**
     * Declared size of the widget (widget_info.xml minWidth/minHeight is 110x56): what a legacy
     * instance the launcher never reported on is treated as. About a 2x1 cell, so the hero and one
     * square button, like the two controls that size showed before.
     */
    static final int DEFAULT_WIDTH_DP = 152;
    static final int DEFAULT_HEIGHT_DP = 92;

    /**
     * Width from which two columns or four columns of controls fit, before the font scale. The
     * reference matrix says 140; the launchers report 137 (Android 11) and 144 (Android 14) dp for
     * two cells, so 136 keeps a 2x2 a grid on both.
     */
    private static final float WIDE2_DP = 136f;
    private static final float WIDE4_DP = 250f;
    /**
     * Narrow and tall: the hero over two square buttons needs this width, not scaled. 114 dp is
     * 10 (frame) + 5 (gap) + 2 x 48 plus one dp for pixel rounding: the two buttons stay at the 48 dp
     * touch size. (The reference
     * matrix says 110; at 110 they would be 47.5 dp wide.)
     */
    private static final int STACK_MIN_DP = 114;
    /**
     * The hero alone shows its port from this width (below it the short label, see ONE). 130 is
     * below the 137 dp the Android 11 launcher reports for two cells (the matrix says 140).
     */
    private static final int HERO_MIN_DP = 130;
    /** A square symbol button, a pill, the gap around a control and the frame of the widget. */
    private static final int SQUARE_DP = 52;
    private static final int PILL_DP = 48;
    /** Both margins of a slot (2 x 2.5 dp); a row or column of slots adds this per slot. */
    private static final int GAP_DP = 5;
    /** Root padding on both sides (2 x 2.5 dp), top and bottom or left and right. */
    private static final int ROOT_PAD_DP = 5;
    /** The frame across a row: the root padding plus the margins of the slot at each end. */
    private static final int FRAME_DP = 10;
    /** Line height of a text, as a multiple of its size (no font padding). */
    private static final float LINE = 1.2f;
    /**
     * The hero keeps at least this much room (before the font scale) next to the square buttons.
     * The reference matrix says 80, which assumes 152 dp for two cells. The launchers report 137
     * (Android 11) and 144 (Android 14) dp, so 80 would leave a 2x1 without its square button.
     * With 70 a 2x1 has one (the port still fits: it shrinks to 10 sp before it is cut) and a 3x1
     * (214 or 220 dp) has two.
     */
    private static final float HERO_ROOM_DP = 70f;

    // -- what the tall classes stack (dp at font scale 1; texts and boxes in sp grow with the scale)

    /** A line of text of the given size in sp at the font scale. */
    private static float line(float sp, float scale) {
        return sp * LINE * scale;
    }

    /**
     * The compact hero (widget_ctl_hero_s): 8 dp padding twice, the 26 dp symbol, 4 dp, and the
     * port box of 21 sp. At least the 48 dp a control keeps.
     */
    private static float heroSHeight(float scale) {
        return Math.max(PILL_DP, 16 + 26 + 4 + 21 * scale);
    }

    /**
     * The 2x2 hero (widget_ctl_hero_m): 6 dp padding twice, the row of the 24 dp symbol and the
     * 13 sp label, 4 dp, and the port box of 28 sp.
     */
    private static float heroMHeight(float scale) {
        return Math.max(PILL_DP, 12 + Math.max(24, line(13, scale)) + 4 + 28 * scale);
    }

    /**
     * The 4x2 hero (widget_ctl_hero_l): 12 dp padding twice, the 34 dp symbol, and under it the
     * 13 sp label, the 12 sp state word, 2 dp and the port box of 28 sp.
     */
    private static float heroLHeight(float scale) {
        return Math.max(PILL_DP, 24 + 34 + line(13, scale) + line(12, scale) + 2 + 28 * scale);
    }

    /** The status line of the 4x2: two lines of 12 sp, at least the 48 dp of the gear. */
    private static float statusHeight(float scale) {
        return Math.max(PILL_DP, 2 * line(12, scale));
    }

    private KeepADBWidgetLayout() {}

    /** The font scale as a divisor: a scale below 1 never grants more room than there is. */
    private static float scaleOf(float fontScale) {
        return fontScale > 1f ? fontScale : 1f;
    }

    private static int ceil(float value) {
        return (int) Math.ceil(value - 0.0001f);
    }

    /** The width from which {@code squares} square buttons fit beside a hero of {@link #HERO_ROOM_DP}. */
    private static int squareRowMinWidth(int squares, float scale) {
        return ceil(FRAME_DP + HERO_ROOM_DP * scale + (SQUARE_DP + GAP_DP) * squares);
    }

    /**
     * The class for a room: the largest class whose width and whole content height fit, falling
     * back one class at a time (panel, grid, stack, then the one-row classes by width). Every
     * comparison is against the same whole-dp keys the Android 12+ entries use, so the launcher's
     * best fit and this choice cannot disagree on a boundary.
     */
    static SizeClass choose(int widthDp, int heightDp, float fontScale) {
        if (fits(SizeClass.PANEL, widthDp, heightDp, fontScale)) return SizeClass.PANEL;
        if (fits(SizeClass.GRID, widthDp, heightDp, fontScale)) return SizeClass.GRID;
        if (fits(SizeClass.STACK, widthDp, heightDp, fontScale)) return SizeClass.STACK;
        if (fits(SizeClass.STRIP, widthDp, heightDp, fontScale)) return SizeClass.STRIP;
        if (fits(SizeClass.ROW2, widthDp, heightDp, fontScale)) return SizeClass.ROW2;
        if (fits(SizeClass.ROW1, widthDp, heightDp, fontScale)) return SizeClass.ROW1;
        return fits(SizeClass.HERO, widthDp, heightDp, fontScale) ? SizeClass.HERO : SizeClass.ONE;
    }

    private static boolean fits(SizeClass sizeClass, int widthDp, int heightDp, float fontScale) {
        return widthDp >= sizeClass.minWidthDp(fontScale)
                && heightDp >= sizeClass.minHeightDp(fontScale);
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
        return Math.max(1f, sizeClass.minWidthDp(fontScale));
    }

    static float responsiveHeightDp(SizeClass sizeClass, float fontScale) {
        return Math.max(1f, sizeClass.minHeightDp(fontScale));
    }
}
