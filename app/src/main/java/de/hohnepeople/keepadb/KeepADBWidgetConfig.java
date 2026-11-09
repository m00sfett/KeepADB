package de.hohnepeople.keepadb;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * #912: the settings of one placed control widget, kept per {@code appWidgetId}: which of the three
 * actions is the main one, in which order the actions appear, and whether this widget hides the
 * connection details.
 *
 * <p><b>Per instance, nothing global.</b> The values live in their own preference file, keyed by
 * the widget id, so they never touch {@code keepadb_prefs} (the app's settings) and one widget can
 * never change another. {@link #delete} removes an instance's entry when the widget is removed. A
 * widget without an entry (every widget placed before this version) simply reads {@link
 * #defaults()}: the order ADB, Keep-Alive, webhook synchronization, ADB as the main
 * action, details visible. A stored entry that still names the retired Report now action keeps
 * decoding: the token is ignored, and a main action that was Report now falls back to ADB.
 *
 * <p><b>Main action and order.</b> The main action is the big control of every layout and the only
 * one a small widget shows. The order fixes the sequence of the others: {@link #others} takes them
 * in that sequence, so a control never jumps when the widget grows, one only appears.
 *
 * <p><b>Details.</b> {@link #hideDetails} is the per-widget option; it can only hide more. The
 * global widget privacy switch decides first (see {@link KeepADBWidgetState}).
 */
final class KeepADBWidgetConfig {

    /** The three controls, in their default order. {@link #id} is the stored token. */
    enum Action {
        ADB("adb"),
        KEEP_ALIVE("keep_alive"),
        WEBHOOK_SYNC("webhook_sync");

        final String id;

        Action(String id) {
            this.id = id;
        }

        static Action fromId(String id) {
            for (Action action : values()) {
                if (action.id.equals(id)) return action;
            }
            return null;
        }
    }

    private static final String PREFS_NAME = "keepadb_widget_config";
    private static final String ENTRY_PREFIX = "widget.";
    private static final List<Action> DEFAULT_ORDER =
            Collections.unmodifiableList(Arrays.asList(Action.values()));

    final Action primary;
    /** Always a permutation of all three actions. */
    final List<Action> order;
    final boolean hideDetails;

    private KeepADBWidgetConfig(Action primary, List<Action> order, boolean hideDetails) {
        this.primary = primary;
        this.order = order;
        this.hideDetails = hideDetails;
    }

    /** What a widget without a stored entry (a legacy instance, a new one) shows. */
    static KeepADBWidgetConfig defaults() {
        return new KeepADBWidgetConfig(Action.ADB, DEFAULT_ORDER, false);
    }

    /**
     * Normalizes any input to a valid configuration: a null main action is ADB, duplicates and nulls
     * in the order are dropped, and actions missing from the order are appended in default order.
     */
    static KeepADBWidgetConfig of(Action primary, List<Action> order, boolean hideDetails) {
        List<Action> normalized = new ArrayList<>();
        if (order != null) {
            for (Action action : order) {
                if (action != null && !normalized.contains(action)) normalized.add(action);
            }
        }
        for (Action action : DEFAULT_ORDER) {
            if (!normalized.contains(action)) normalized.add(action);
        }
        return new KeepADBWidgetConfig(primary == null ? Action.ADB : primary,
                Collections.unmodifiableList(normalized), hideDetails);
    }

    /**
     * The controls that sit beside the main one in a layout with room for {@code count} of them:
     * the first {@code count} actions of {@link #order} without the main action, in the order's
     * sequence. The main action is not part of the result; it is always the big control.
     */
    List<Action> others(int count) {
        List<Action> others = new ArrayList<>();
        for (Action action : order) {
            if (others.size() >= Math.max(0, count)) break;
            if (action != primary) others.add(action);
        }
        return others;
    }

    // -- persistence ---------------------------------------------------------------------------

    static KeepADBWidgetConfig load(Context context, int appWidgetId) {
        String stored = prefs(context).getString(key(appWidgetId), null);
        return stored == null ? defaults() : decode(stored);
    }

    static void save(Context context, int appWidgetId, KeepADBWidgetConfig config) {
        prefs(context).edit().putString(key(appWidgetId), encode(config)).commit();
    }

    /** Whether this instance has a stored entry (false for every widget placed before #912). */
    static boolean isStored(Context context, int appWidgetId) {
        return prefs(context).contains(key(appWidgetId));
    }

    /** Removes the entries of the given instances and nothing else. */
    static void delete(Context context, int[] appWidgetIds) {
        if (appWidgetIds == null || appWidgetIds.length == 0) return;
        SharedPreferences.Editor editor = prefs(context).edit();
        for (int id : appWidgetIds) editor.remove(key(id));
        editor.commit();
    }

    /** Removes every entry; called when the last widget is gone. */
    static void clearAll(Context context) {
        prefs(context).edit().clear().commit();
    }

    static String encode(KeepADBWidgetConfig config) {
        StringBuilder order = new StringBuilder();
        for (Action action : config.order) {
            if (order.length() > 0) order.append(',');
            order.append(action.id);
        }
        return config.primary.id + ";" + order + ";" + (config.hideDetails ? "1" : "0");
    }

    /** Tolerant: anything unreadable falls back to the default for that part. */
    static KeepADBWidgetConfig decode(String stored) {
        String[] parts = stored == null ? new String[0] : stored.split(";", -1);
        Action primary = parts.length > 0 ? Action.fromId(parts[0]) : null;
        List<Action> order = new ArrayList<>();
        if (parts.length > 1) {
            for (String token : parts[1].split(",")) order.add(Action.fromId(token));
        }
        boolean hide = parts.length > 2 && "1".equals(parts[2]);
        return of(primary, order, hide);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static String key(int appWidgetId) {
        return ENTRY_PREFIX + appWidgetId;
    }
}
