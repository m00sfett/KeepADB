package de.hohnepeople.keepadb;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * "At 200 % font size and 320 dp width nothing is cut off" (#761 criterion), as a check the steps
 * of #767 share: every visible view ends inside the screen and no text has an ellipsis or a line
 * cap. The check can fail: a view wider than the screen or a one-line text trips it.
 */
final class OnboardingLayoutAssertions {
    private OnboardingLayoutAssertions() {}

    static void assertFits(android.app.Activity activity, int widthDp, String where) {
        View root = activity.getWindow().getDecorView();
        int width = Math.round(widthDp * activity.getResources().getDisplayMetrics().density);
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(12000, View.MeasureSpec.AT_MOST));
        root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());
        assertInside(root, 0, width, where);
    }

    private static void assertInside(View view, int parentLeft, int width, String where) {
        if (view.getVisibility() != View.VISIBLE) return;
        int left = parentLeft + view.getLeft();
        assertTrue(where + ": " + view.getClass().getSimpleName() + " ends at "
                + (left + view.getWidth()) + " of " + width, left + view.getWidth() <= width + 1);
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            assertNull(where + ": no ellipsis in \"" + text.getText() + "\"", text.getEllipsize());
            assertTrue(where + ": no line cap in \"" + text.getText() + "\"",
                    text.getMaxLines() >= 100);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                assertInside(group.getChildAt(i), left - view.getScrollX(), width, where);
            }
        }
    }
}
