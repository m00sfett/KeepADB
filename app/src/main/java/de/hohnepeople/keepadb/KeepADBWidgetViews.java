package de.hohnepeople.keepadb;

import android.content.Context;
import android.os.Build;
import android.util.SizeF;
import android.view.View;
import android.widget.RemoteViews;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * #912: builds the {@link RemoteViews} of the control widget from a configuration, the shared
 * projection and a size class. It reads nothing itself and starts nothing: the caller hands in the
 * values, so building (and so resizing) a widget can neither send a report nor poll anything.
 *
 * <p>On Android 12+ ({@link #responsive}) the launcher gets one view per size class and picks the
 * fitting one itself while the user resizes, without calling the app. Android 11 has no such API;
 * there {@link #forSize} builds the one class the reported size selects, and the provider rebuilds
 * it when the options change.
 */
final class KeepADBWidgetViews {

    private KeepADBWidgetViews() {}

    /**
     * The widget for one size class.
     *
     * @param ctx a localized context; its strings and the package are used
     * @param showLabels false at large font scales, where the label gives way to the state
     */
    static RemoteViews forSize(Context ctx, int appWidgetId, KeepADBWidgetConfig config,
            KeepADBWidgetState projection, KeepADBWidgetLayout.SizeClass sizeClass,
            boolean showLabels) {
        RemoteViews views = new RemoteViews(ctx.getPackageName(), R.layout.widget_keepadb);
        List<KeepADBWidgetConfig.Action> actions = config.shown(sizeClass.actionCount());

        // A launcher re-applies a new RemoteViews with the same layout onto the view it already
        // has (and, on Android 12+, switches between the size classes the same way). Added tiles
        // would pile up there, so the rows are emptied first.
        views.removeAllViews(R.id.widget_row_1);
        views.removeAllViews(R.id.widget_row_2);

        int index = 0;
        views.setViewVisibility(R.id.widget_row_2,
                sizeClass.secondRowTiles > 0 ? View.VISIBLE : View.GONE);
        for (int i = 0; i < sizeClass.firstRowTiles && index < actions.size(); i++, index++) {
            views.addView(R.id.widget_row_1,
                    tile(ctx, actions.get(index), projection, showLabels));
        }
        for (int i = 0; i < sizeClass.secondRowTiles && index < actions.size(); i++, index++) {
            views.addView(R.id.widget_row_2,
                    tile(ctx, actions.get(index), projection, showLabels));
        }

        boolean details = sizeClass.hasDetails();
        views.setViewVisibility(R.id.widget_details_row, details ? View.VISIBLE : View.GONE);
        if (details) {
            KeepADBWidgetContent.Details content = KeepADBWidgetContent.details(ctx, projection);
            views.setTextViewText(R.id.widget_detail_system, content.system);
            boolean webhookLine = sizeClass.detailLines > 1;
            views.setViewVisibility(R.id.widget_detail_webhook,
                    webhookLine ? View.VISIBLE : View.GONE);
            if (webhookLine) views.setTextViewText(R.id.widget_detail_webhook, content.webhook);
            views.setContentDescription(R.id.widget_details, webhookLine
                    ? content.system + ". " + content.webhook : content.system);
            views.setOnClickPendingIntent(R.id.widget_details,
                    KeepADBWidgetActions.forClick(ctx, content.click));
            views.setOnClickPendingIntent(R.id.widget_customize,
                    KeepADBWidgetActions.customize(ctx, appWidgetId));
        }
        return views;
    }

    /**
     * Android 12+: one entry per size class, keyed by the room the class is meant for (made larger
     * by the font scale, so a larger font keeps the launcher from choosing a class that no longer
     * fits). The launcher shows the largest entry that fits.
     */
    static RemoteViews responsive(Context ctx, int appWidgetId, KeepADBWidgetConfig config,
            KeepADBWidgetState projection, float fontScale) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            throw new IllegalStateException("responsive RemoteViews need Android 12");
        }
        boolean showLabels = KeepADBWidgetLayout.showLabels(fontScale);
        Map<SizeF, RemoteViews> sizes = new HashMap<>();
        for (KeepADBWidgetLayout.SizeClass sizeClass : KeepADBWidgetLayout.SizeClass.values()) {
            sizes.put(new SizeF(KeepADBWidgetLayout.responsiveWidthDp(sizeClass, fontScale),
                            KeepADBWidgetLayout.responsiveHeightDp(sizeClass, fontScale)),
                    forSize(ctx, appWidgetId, config, projection, sizeClass, showLabels));
        }
        return new RemoteViews(sizes);
    }

    private static RemoteViews tile(Context ctx, KeepADBWidgetConfig.Action action,
            KeepADBWidgetState projection, boolean showLabels) {
        KeepADBWidgetContent.Tile content = KeepADBWidgetContent.tile(ctx, action, projection);
        RemoteViews tile = new RemoteViews(ctx.getPackageName(), R.layout.widget_tile);
        tile.setTextViewText(R.id.widget_tile_label, content.label);
        tile.setTextViewText(R.id.widget_tile_state, content.state);
        tile.setViewVisibility(R.id.widget_tile_label, showLabels ? View.VISIBLE : View.GONE);
        tile.setInt(R.id.widget_tile, "setBackgroundResource", background(content.style));
        // The two text views are part of the tile's own description; TalkBack reads that one.
        tile.setContentDescription(R.id.widget_tile, content.description);
        tile.setOnClickPendingIntent(R.id.widget_tile,
                KeepADBWidgetActions.forClick(ctx, content.click));
        return tile;
    }

    private static int background(KeepADBWidgetContent.Style style) {
        switch (style) {
            case ON:
                return R.drawable.bg_widget_tile_on;
            case ATTENTION:
                return R.drawable.bg_widget_tile_attention;
            case OFF:
            default:
                return R.drawable.bg_widget_tile_off;
        }
    }
}
