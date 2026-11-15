package de.hohnepeople.keepadb;

import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * #963: the collapsed row of a home warning: icon, title with a small chevron, the action button
 * and the close button on one line of at least 48dp. A tap on the row (not on one of the two
 * buttons) opens and closes the explanation below it; this view only draws the row and reports
 * the tap, the card around it owns the explanation.
 *
 * <p>Its four children come in this order: icon, title ({@link TextView}), action, close. The
 * title takes what is left and may use two lines next to the action. When it would need more
 * (large font, long translation) the action moves to its own line below the title, start-aligned
 * with it, instead of squeezing or clipping the title. That is decided in {@link #onMeasure}
 * from the measured line count of the title: the title is measured once next to the action and,
 * only if {@link #stackAction} says so, once more with the full width. Nothing is posted and no
 * layout is requested from inside the pass, so there is no layout loop.
 *
 * <p>TalkBack: the row is one focus target announced as a button, its label is the title (see
 * {@link #setTitle}) and its state "Collapsed" or "Expanded" (the {@code card_state_*} strings);
 * the action and close buttons stay separate focus targets.
 */
public class KeepADBWarningRow extends ViewGroup {
    /** Lines the title may use next to the action button before the action moves below it. */
    static final int MAX_INLINE_TITLE_LINES = 2;
    /** Gap between the title and the action when the action sits below it, in dp. */
    private static final int STACKED_GAP_DP = 4;

    private boolean stacked;
    private boolean expanded;
    private boolean expandable = true;

    public KeepADBWarningRow(Context context) {
        this(context, null);
    }

    public KeepADBWarningRow(Context context, AttributeSet attrs) {
        super(context, attrs);
        setClickable(true);
        setFocusable(true);
        renderState();
    }

    /**
     * The decision: the action goes below the title when the title needs more than two lines
     * next to it. Pure, so it is testable with injected line counts where the test runtime cannot
     * measure text.
     */
    static boolean stackAction(int titleLinesNextToAction, boolean actionShown) {
        return actionShown && titleLinesNextToAction > MAX_INLINE_TITLE_LINES;
    }

    /** Whether the last measure pass put the action below the title. */
    boolean isActionStacked() {
        return stacked;
    }

    boolean isExpanded() {
        return expanded;
    }

    /**
     * Shows the title and uses {@code description} as the row's label, so a title with a styled
     * second line ("Active until 14:30") reads as plain text.
     */
    void setTitle(CharSequence text, CharSequence description) {
        TextView title = title();
        if (!TextUtils.equals(title.getText(), text)) title.setText(text);
        if (!TextUtils.equals(getContentDescription(), description)) setContentDescription(description);
    }

    void setExpanded(boolean value) {
        if (expanded == value) return;
        expanded = value;
        renderState();
    }

    /**
     * A row that does not open anything (the list of security reasons reuses the card): no
     * chevron, no tap target, no state.
     */
    void setExpandable(boolean value) {
        expandable = value;
        setClickable(value);
        setFocusable(value);
        renderState();
    }

    private void renderState() {
        if (getChildCount() > 1) {
            TextView title = title();
            title.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0,
                    !expandable ? 0 : expanded ? R.drawable.ic_expand_less : R.drawable.ic_expand_more, 0);
        }
        setStateDescription(!expandable ? null : getContext().getString(expanded
                ? R.string.card_state_expanded : R.string.card_state_collapsed));
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        renderState();
    }

    @Override
    public CharSequence getAccessibilityClassName() {
        return android.widget.Button.class.getName();
    }

    private TextView title() {
        return (TextView) getChildAt(1);
    }

    // ---- Measure and layout -----------------------------------------------------------------------

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        View icon = getChildAt(0);
        TextView title = title();
        View action = getChildAt(2);
        View close = getChildAt(3);
        boolean actionShown = action.getVisibility() != GONE;

        int widthMode = MeasureSpec.getMode(widthSpec);
        int width = MeasureSpec.getSize(widthSpec);
        int available = widthMode == MeasureSpec.UNSPECIFIED ? dp(1000)
                : Math.max(0, width - getPaddingLeft() - getPaddingRight());

        measureFree(icon, available);
        measureFree(close, available);
        if (actionShown) measureFree(action, available);
        int iconWidth = outerWidth(icon);
        int closeWidth = outerWidth(close);
        int actionWidth = actionShown ? outerWidth(action) : 0;

        measureTitle(title, available - iconWidth - closeWidth - actionWidth);
        stacked = stackAction(title.getLineCount(), actionShown);
        if (stacked) measureTitle(title, available - iconWidth - closeWidth);

        int inner;
        if (stacked) {
            int first = Math.max(getSuggestedMinimumHeight(),
                    Math.max(outerHeight(icon), Math.max(outerHeight(title), outerHeight(close))));
            inner = first + dp(STACKED_GAP_DP) + outerHeight(action);
        } else {
            inner = Math.max(getSuggestedMinimumHeight(), Math.max(outerHeight(icon),
                    Math.max(outerHeight(title), Math.max(outerHeight(close),
                            actionShown ? outerHeight(action) : 0))));
        }
        int measuredWidth = widthMode == MeasureSpec.EXACTLY ? width
                : Math.min(width, available + getPaddingLeft() + getPaddingRight());
        setMeasuredDimension(measuredWidth,
                resolveSize(inner + getPaddingTop() + getPaddingBottom(), heightSpec));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        View icon = getChildAt(0);
        TextView title = title();
        View action = getChildAt(2);
        View close = getChildAt(3);
        boolean actionShown = action.getVisibility() != GONE;
        int total = right - left;
        int innerTop = getPaddingTop();
        int innerHeight = bottom - top - getPaddingTop() - getPaddingBottom();
        int start = getPaddingLeft();
        int end = total - getPaddingRight();

        // Everything below is positioned from the start edge (or the end edge) in a left-to-right
        // frame; place() mirrors it for a right-to-left layout.
        int closeX = end - outerWidth(close);
        int titleX = start + outerWidth(icon);
        if (!stacked) {
            place(icon, start, innerTop + centered(innerHeight, outerHeight(icon)), total);
            place(title, titleX, innerTop + centered(innerHeight, outerHeight(title)), total);
            if (actionShown) {
                int actionX = closeX - outerWidth(action);
                place(action, actionX, innerTop + centered(innerHeight, outerHeight(action)), total);
            }
            place(close, closeX, innerTop + centered(innerHeight, outerHeight(close)), total);
        } else {
            int first = innerHeight - outerHeight(action) - dp(STACKED_GAP_DP);
            int titleY = innerTop + centered(first, outerHeight(title));
            // The icon sits on the first line of the title, the close button at the top.
            int iconY = titleY + Math.max(0, (title.getLineHeight() - icon.getMeasuredHeight()) / 2);
            place(icon, start, iconY, total);
            place(title, titleX, titleY, total);
            place(close, closeX, innerTop, total);
            // Below the title, start-aligned with its text (the action's own start margin is for
            // the inline position next to the close button and does not apply here).
            MarginLayoutParams titleParams = (MarginLayoutParams) title.getLayoutParams();
            MarginLayoutParams actionParams = (MarginLayoutParams) action.getLayoutParams();
            place(action, titleX + titleParams.getMarginStart() - actionParams.getMarginStart(),
                    innerTop + first + dp(STACKED_GAP_DP), total);
        }
    }

    private void measureFree(View child, int available) {
        MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
        int widthSpec = getChildMeasureSpec(
                MeasureSpec.makeMeasureSpec(Math.max(0, available - lp.getMarginStart() - lp.getMarginEnd()),
                        MeasureSpec.AT_MOST), 0, lp.width);
        int heightSpec = getChildMeasureSpec(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                0, lp.height);
        child.measure(widthSpec, heightSpec);
    }

    private void measureTitle(TextView title, int available) {
        MarginLayoutParams lp = (MarginLayoutParams) title.getLayoutParams();
        int room = Math.max(0, available - lp.getMarginStart() - lp.getMarginEnd());
        title.measure(MeasureSpec.makeMeasureSpec(room, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
    }

    private void place(View child, int logicalStart, int top, int total) {
        MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
        int width = child.getMeasuredWidth();
        int x = logicalStart + lp.getMarginStart();
        int y = top + lp.topMargin;
        if (getLayoutDirection() == LAYOUT_DIRECTION_RTL) x = total - x - width;
        child.layout(x, y, x + width, y + child.getMeasuredHeight());
    }

    private static int centered(int room, int size) {
        return Math.max(0, (room - size) / 2);
    }

    private static int outerWidth(View child) {
        MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
        return child.getMeasuredWidth() + lp.getMarginStart() + lp.getMarginEnd();
    }

    private static int outerHeight(View child) {
        MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
        return child.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new MarginLayoutParams(getContext(), attrs);
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return new MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }

    @Override
    protected LayoutParams generateLayoutParams(LayoutParams p) {
        return new MarginLayoutParams(p);
    }

    @Override
    protected boolean checkLayoutParams(LayoutParams p) {
        return p instanceof MarginLayoutParams;
    }
}
