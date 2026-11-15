package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * #963: where the action button of a warning row goes. The decision itself is a pure function
 * ({@link KeepADBWarningRow#stackAction}, tested with injected line counts); the measure pass is
 * tested with a title that reports a line count from its natural width, because the test runtime
 * does not measure real text.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBWarningRowLayoutTest {

    private final Context context = RuntimeEnvironment.getApplication();
    private final float density = context.getResources().getDisplayMetrics().density;

    private int dp(int value) {
        return Math.round(value * density);
    }

    @Test
    public void theActionMovesBelowTheTitleOnlyWhenTheTitleNeedsMoreThanTwoLines() {
        assertFalse(KeepADBWarningRow.stackAction(1, true));
        assertFalse(KeepADBWarningRow.stackAction(2, true));
        assertTrue(KeepADBWarningRow.stackAction(3, true));
        assertTrue(KeepADBWarningRow.stackAction(5, true));
        assertFalse("without an action there is nothing to move", KeepADBWarningRow.stackAction(5, false));
    }

    @Test
    public void aShortTitleKeepsTheActionOnTheSameLineBesideTheClose() {
        Rows rows = new Rows(dp(100));

        rows.row.measure(View.MeasureSpec.makeMeasureSpec(dp(328), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        rows.row.layout(0, 0, rows.row.getMeasuredWidth(), rows.row.getMeasuredHeight());

        assertFalse(rows.row.isActionStacked());
        assertEquals("one line of at least 48dp", dp(48), rows.row.getMeasuredHeight());
        assertEquals("the action ends where the close button starts",
                rows.close.getLeft() - dp(8), rows.action.getRight());
        assertEquals("the close button is at the end edge", dp(328), rows.close.getRight());
        assertTrue("the title is left of the action", rows.title.getRight() <= rows.action.getLeft());
    }

    @Test
    public void aTitleThatNeedsThreeLinesNextToTheActionPutsTheActionOnItsOwnLine() {
        // 330dp of text: next to the action (328 - 36 - 108 - 56 = 128dp) it needs three lines,
        // with the whole width left of the close button (244dp) two.
        Rows rows = new Rows(dp(330));

        rows.row.measure(View.MeasureSpec.makeMeasureSpec(dp(328), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        rows.row.layout(0, 0, rows.row.getMeasuredWidth(), rows.row.getMeasuredHeight());

        assertTrue(rows.row.isActionStacked());
        assertEquals("the title is not squeezed: two lines, none cut", 2, rows.title.getLineCount());
        assertEquals("start-aligned with the title", rows.title.getLeft(), rows.action.getLeft());
        assertTrue("below the title", rows.action.getTop() >= rows.title.getBottom());
        assertEquals("the close button stays at the end of the first line", dp(328), rows.close.getRight());
        assertEquals(0, rows.close.getTop());
        assertTrue("the row grew to hold the action",
                rows.row.getMeasuredHeight() >= rows.action.getBottom());
    }

    @Test
    public void aTitleOfTwoLinesNextToTheActionStaysInline() {
        // 200dp of text needs two lines in the 128dp left next to the action: still inline.
        Rows rows = new Rows(dp(200));

        rows.row.measure(View.MeasureSpec.makeMeasureSpec(dp(328), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));

        assertFalse(rows.row.isActionStacked());
        assertEquals(2, rows.title.getLineCount());
    }

    @Test
    public void aHiddenActionNeverStacks() {
        Rows rows = new Rows(dp(900));
        rows.action.setVisibility(View.GONE);

        rows.row.measure(View.MeasureSpec.makeMeasureSpec(dp(328), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));

        assertFalse(rows.row.isActionStacked());
    }

    /** #963: an explanation sits below the title line; stacked it sits between title and action. */
    @Test
    public void anOpenExplanationSitsBetweenTheTitleAndAStackedAction() {
        Rows rows = new Rows(dp(330));
        View details = rows.addDetails(dp(40));

        rows.row.measure(View.MeasureSpec.makeMeasureSpec(dp(328), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        rows.row.layout(0, 0, rows.row.getMeasuredWidth(), rows.row.getMeasuredHeight());

        assertTrue(rows.row.isActionStacked());
        assertTrue("below the title", details.getTop() >= rows.title.getBottom());
        assertTrue("above the action", details.getBottom() <= rows.action.getTop());
        assertEquals("aligned with the title text", rows.title.getLeft(), details.getLeft());
        assertEquals("the action keeps its start alignment", rows.title.getLeft(), rows.action.getLeft());
        assertTrue("the row holds all three", rows.row.getMeasuredHeight() >= rows.action.getBottom());
        assertEquals(0, rows.close.getTop());
    }

    @Test
    public void anOpenExplanationSitsBelowTheHeaderWhenTheActionIsInline() {
        Rows rows = new Rows(dp(100));
        View details = rows.addDetails(dp(40));

        rows.row.measure(View.MeasureSpec.makeMeasureSpec(dp(328), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        rows.row.layout(0, 0, rows.row.getMeasuredWidth(), rows.row.getMeasuredHeight());

        assertFalse(rows.row.isActionStacked());
        assertEquals("the header is the same one line as without the explanation",
                dp(48), rows.close.getBottom());
        assertEquals(dp(48), details.getTop());
        assertEquals(dp(48) + dp(40), rows.row.getMeasuredHeight());
        assertEquals("the action ends where the close button starts",
                rows.close.getLeft() - dp(8), rows.action.getRight());
    }

    @Test
    public void aClosedExplanationChangesNothingInEitherLayout() {
        for (int natural : new int[] {dp(100), dp(330)}) {
            Rows without = new Rows(natural);
            Rows closed = new Rows(natural);
            View details = closed.addDetails(dp(40));
            details.setVisibility(View.GONE);
            int width = View.MeasureSpec.makeMeasureSpec(dp(328), View.MeasureSpec.EXACTLY);
            int height = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
            without.row.measure(width, height);
            without.row.layout(0, 0, without.row.getMeasuredWidth(), without.row.getMeasuredHeight());
            closed.row.measure(width, height);
            closed.row.layout(0, 0, closed.row.getMeasuredWidth(), closed.row.getMeasuredHeight());

            assertEquals(without.row.getMeasuredHeight(), closed.row.getMeasuredHeight());
            assertEquals(without.action.getTop(), closed.action.getTop());
            assertEquals(without.action.getLeft(), closed.action.getLeft());
            assertEquals(without.row.isActionStacked(), closed.row.isActionStacked());
        }
    }

    @Test
    public void aTapOnTheExplanationIsNoTapOnTheRow() {
        Rows rows = new Rows(dp(330));
        View details = rows.addDetails(dp(40));
        rows.row.measure(View.MeasureSpec.makeMeasureSpec(dp(328), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        rows.row.layout(0, 0, rows.row.getMeasuredWidth(), rows.row.getMeasuredHeight());

        assertFalse("the explanation is not a toggle",
                down(rows.row, dp(40), details.getTop() + details.getHeight() / 2f));
        assertTrue("the header is", down(rows.row, dp(10), 2f));
    }

    /** Whether the row consumed a press at that point (a press it refuses falls through). */
    private static boolean down(View view, float x, float y) {
        long now = android.os.SystemClock.uptimeMillis();
        boolean handled = view.dispatchTouchEvent(android.view.MotionEvent.obtain(now, now,
                android.view.MotionEvent.ACTION_DOWN, x, y, 0));
        view.dispatchTouchEvent(android.view.MotionEvent.obtain(now, now + 20,
                android.view.MotionEvent.ACTION_CANCEL, x, y, 0));
        return handled;
    }

    @Test
    public void aRightToLeftRowIsTheMirrorImage() {
        Rows rows = new Rows(dp(100));
        rows.row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        rows.row.measure(View.MeasureSpec.makeMeasureSpec(dp(328), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        rows.row.layout(0, 0, rows.row.getMeasuredWidth(), rows.row.getMeasuredHeight());

        assertEquals("the close button is at the left", 0, rows.close.getLeft());
        assertEquals("the icon is at the right", dp(328), rows.icon.getRight());
        assertTrue(rows.action.getLeft() > rows.close.getRight());
    }

    /** A row of four children as view_home_warning.xml has them. */
    private final class Rows {
        final KeepADBWarningRow row = new KeepADBWarningRow(context);
        final View icon = new View(context);
        final LinesTextView title;
        final View action = new View(context);
        final View close = new View(context);

        Rows(int titleNaturalWidth) {
            title = new LinesTextView(context, titleNaturalWidth);
            ViewGroup.MarginLayoutParams iconParams = new ViewGroup.MarginLayoutParams(dp(24), dp(24));
            iconParams.setMarginEnd(dp(12));
            row.addView(icon, iconParams);
            row.addView(title, new ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            ViewGroup.MarginLayoutParams actionParams = new ViewGroup.MarginLayoutParams(dp(100), dp(48));
            actionParams.setMarginStart(dp(8));
            row.addView(action, actionParams);
            ViewGroup.MarginLayoutParams closeParams = new ViewGroup.MarginLayoutParams(dp(48), dp(48));
            closeParams.setMarginStart(dp(8));
            row.addView(close, closeParams);
            row.setMinimumHeight(dp(48));
        }

        /** The explanation as view_home_warning.xml has it: a fifth child with 36dp and 8dp margins. */
        View addDetails(int height) {
            View details = new View(context);
            ViewGroup.MarginLayoutParams params = new ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, height);
            params.setMarginStart(dp(36));
            params.setMarginEnd(dp(8));
            row.addView(details, params);
            return details;
        }
    }

    /**
     * A title of a given natural width in one line: it needs ceil(natural / room) lines in a
     * narrower room, 20dp each, which is what a wrapping TextView does with real text.
     */
    private static final class LinesTextView extends TextView {
        private final int naturalWidth;
        private int lines = 1;

        LinesTextView(Context context, int naturalWidth) {
            super(context);
            this.naturalWidth = naturalWidth;
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int room = View.MeasureSpec.getSize(widthSpec);
            lines = Math.max(1, (int) Math.ceil(naturalWidth / (double) Math.max(1, room)));
            int width = Math.min(naturalWidth, room);
            int lineHeight = Math.round(20 * getResources().getDisplayMetrics().density);
            setMeasuredDimension(width, lines * lineHeight);
        }

        @Override
        public int getLineCount() {
            return lines;
        }
    }
}
