package de.hohnepeople.keepadb;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.LinearLayout;

/** Keeps an assistant task readable while the ScrollView still owns the whole viewport. */
public final class OnboardingPageLayout extends LinearLayout {
    private int maximumWidthDp = 640;

    public OnboardingPageLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    void setComparisonPage(boolean comparison) {
        maximumWidthDp = comparison ? 840 : 640;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int maximum = Math.round(maximumWidthDp * getResources().getDisplayMetrics().density);
        int mode = MeasureSpec.getMode(widthMeasureSpec);
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (mode == MeasureSpec.UNSPECIFIED || width > maximum) {
            widthMeasureSpec = MeasureSpec.makeMeasureSpec(maximum, MeasureSpec.EXACTLY);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
