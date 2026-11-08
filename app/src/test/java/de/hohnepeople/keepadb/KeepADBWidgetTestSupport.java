package de.hohnepeople.keepadb;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.RemoteViews;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** #912: helpers to look at a rendered control widget the way a launcher would draw it. */
final class KeepADBWidgetTestSupport {

    private KeepADBWidgetTestSupport() {}

    /** Applies the views and lays them out in a box of the given size (1 dp = 1 px in tests). */
    static View applyAndMeasure(Context context, RemoteViews views, int widthDp, int heightDp) {
        FrameLayout parent = new FrameLayout(context);
        View root = views.apply(context, parent);
        measure(root, widthDp, heightDp);
        return root;
    }

    static void measure(View root, int widthDp, int heightDp) {
        root.measure(View.MeasureSpec.makeMeasureSpec(widthDp, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(heightDp, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());
    }

    /** The control tiles in reading order: first row left to right, then the second row. */
    static List<View> tiles(View root) {
        List<View> tiles = new ArrayList<>();
        collect(root.findViewById(R.id.widget_row_1), tiles);
        collect(root.findViewById(R.id.widget_row_2), tiles);
        return tiles;
    }

    private static void collect(View row, List<View> into) {
        if (!(row instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) row;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getId() == R.id.widget_tile) into.add(child);
        }
    }

    static String label(View tile) {
        return ((TextView) tile.findViewById(R.id.widget_tile_label)).getText().toString();
    }

    static String state(View tile) {
        return ((TextView) tile.findViewById(R.id.widget_tile_state)).getText().toString();
    }

    static boolean labelVisible(View tile) {
        return tile.findViewById(R.id.widget_tile_label).getVisibility() == View.VISIBLE;
    }

    static List<String> labels(View root) {
        List<String> labels = new ArrayList<>();
        for (View tile : tiles(root)) labels.add(label(tile));
        return labels;
    }

    static boolean detailsVisible(View root) {
        return root.findViewById(R.id.widget_details_row).getVisibility() == View.VISIBLE;
    }

    static String systemLine(View root) {
        return ((TextView) root.findViewById(R.id.widget_detail_system)).getText().toString();
    }

    static boolean webhookLineVisible(View root) {
        return root.findViewById(R.id.widget_detail_webhook).getVisibility() == View.VISIBLE;
    }

    static String webhookLine(View root) {
        return ((TextView) root.findViewById(R.id.widget_detail_webhook)).getText().toString();
    }

    /** All text a widget shows or speaks, for privacy scans. */
    static String allText(View root) {
        StringBuilder text = new StringBuilder();
        append(root, text);
        return text.toString();
    }

    private static void append(View view, StringBuilder into) {
        if (view.getContentDescription() != null) into.append(view.getContentDescription()).append('\n');
        if (view instanceof TextView) into.append(((TextView) view).getText()).append('\n');
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) append(group.getChildAt(i), into);
        }
    }
}
