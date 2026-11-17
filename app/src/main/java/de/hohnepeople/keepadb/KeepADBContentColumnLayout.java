package de.hohnepeople.keepadb;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.LinearLayout;

/**
 * Centered content column capped at {@code R.dimen.main_content_max_width} on wide screens / tablets,
 * keeping full width on phones (#975).
 */
public final class KeepADBContentColumnLayout extends LinearLayout {

    public KeepADBContentColumnLayout(Context context) {
        this(context, null);
    }

    public KeepADBContentColumnLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int maximum = getResources().getDimensionPixelSize(R.dimen.main_content_max_width);
        int mode = MeasureSpec.getMode(widthMeasureSpec);
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (mode == MeasureSpec.UNSPECIFIED || width > maximum) {
            widthMeasureSpec = MeasureSpec.makeMeasureSpec(maximum, MeasureSpec.EXACTLY);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
