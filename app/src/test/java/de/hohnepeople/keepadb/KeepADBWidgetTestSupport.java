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

    private static final int[] SLOTS = {R.id.widget_slot_hero, R.id.widget_slot_a, R.id.widget_slot_b};

    /** The controls in reading order: the main one first, then the others; only visible ones. */
    static List<View> tiles(View root) {
        List<View> tiles = new ArrayList<>();
        for (int slot : SLOTS) {
            View container = root.findViewById(slot);
            if (!(container instanceof ViewGroup) || container.getVisibility() != View.VISIBLE) continue;
            ViewGroup group = (ViewGroup) container;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child.getId() == R.id.widget_tile) tiles.add(child);
            }
        }
        return tiles;
    }

    static View hero(View root) {
        List<View> tiles = tiles(root);
        return tiles.isEmpty() ? null : tiles.get(0);
    }

    /** What TalkBack reads for the control: "name: state[, port ...]". */
    static String description(View tile) {
        CharSequence description = tile.getContentDescription();
        return description == null ? "" : description.toString();
    }

    private static String visibleText(View tile, int id) {
        View view = tile.findViewById(id);
        if (!(view instanceof TextView) || view.getVisibility() != View.VISIBLE) return null;
        return ((TextView) view).getText().toString();
    }

    /** The label text on the control, or null where the shape has none (hero S, square). */
    static String label(View tile) {
        return visibleText(tile, R.id.widget_tile_label);
    }

    /** The state word on the control, or null where it is not shown (square, hero with port). */
    static String state(View tile) {
        return visibleText(tile, R.id.widget_tile_state);
    }

    /** The port (or the dots) on the control, or null while none is shown. */
    static String port(View tile) {
        return visibleText(tile, R.id.widget_tile_port);
    }

    /**
     * The full state word of the Wireless Debugging control (the main one in the default
     * configuration) as TalkBack reads it after the name, without the port.
     */
    static String heroStateWord(Context context, View root) {
        View hero = hero(root);
        if (hero == null) throw new AssertionError("no control");
        String description = description(hero);
        String prefix = context.getString(R.string.toggle_label) + ": ";
        if (!description.startsWith(prefix)) throw new AssertionError(description);
        String state = description.substring(prefix.length());
        int port = state.indexOf(", port ");
        return port >= 0 ? state.substring(0, port) : state;
    }

    /** Which control a tile is, from the full name TalkBack starts with. */
    static KeepADBWidgetConfig.Action actionOf(Context context, View tile) {
        for (KeepADBWidgetConfig.Action action : KeepADBWidgetConfig.Action.values()) {
            if (description(tile).startsWith(KeepADBWidgetContent.fullName(context, action))) {
                return action;
            }
        }
        throw new AssertionError("unknown control: " + description(tile));
    }

    static List<KeepADBWidgetConfig.Action> actions(Context context, View root) {
        List<KeepADBWidgetConfig.Action> actions = new ArrayList<>();
        for (View tile : tiles(root)) actions.add(actionOf(context, tile));
        return actions;
    }

    /** Only the 4x2 layout has the status line. */
    static boolean statusVisible(View root) {
        View row = root.findViewById(R.id.widget_status_row);
        return row != null && row.getVisibility() == View.VISIBLE;
    }

    static String statusText(View root) {
        View view = root.findViewById(R.id.widget_status_text);
        return view instanceof TextView ? ((TextView) view).getText().toString() : null;
    }

    static String statusDescription(View root) {
        View view = root.findViewById(R.id.widget_status_text);
        return view == null || view.getContentDescription() == null ? ""
                : view.getContentDescription().toString();
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
