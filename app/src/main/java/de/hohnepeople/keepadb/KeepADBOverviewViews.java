package de.hohnepeople.keepadb;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.util.SizeF;
import android.widget.RemoteViews;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * #913: builds the {@link RemoteViews} of the overview widget from the shared projection. Like
 * {@link KeepADBWidgetViews} it reads nothing itself and starts nothing, so building or resizing the
 * widget can neither send a report nor poll anything.
 *
 * <p>Two shapes: {@link Shape#COMPACT} (about 2x2 to 3 cells wide: one card with four rows, the
 * last-delivery line and the auto-sync pill) and {@link Shape#WIDE} (about 4 cells wide and more: the card with labelled rows,
 * beside it the webhook block and the pill). Both grow with the height. The design language is the
 * control widget's: dark surface, flat rounded tiles, a symbol variant and a word per state, the
 * yellow pill while synchronization is on, no borders.
 */
final class KeepADBOverviewViews {

    enum Shape {
        COMPACT(R.layout.widget_overview_compact, R.layout.widget_ov_row),
        WIDE(R.layout.widget_overview_wide, R.layout.widget_ov_row_labeled);

        final int rootRes;
        final int rowRes;

        Shape(int rootRes, int rowRes) {
            this.rootRes = rootRes;
            this.rowRes = rowRes;
        }
    }

    /** From this width (scaled with the font size) the labelled wide shape fits. */
    static final float WIDE_DP = 250f;

    private KeepADBOverviewViews() {}

    static Shape choose(float widthDp, float fontScale) {
        return widthDp >= WIDE_DP * Math.max(1f, fontScale) ? Shape.WIDE : Shape.COMPACT;
    }

    /** Android 11: the width of the orientation's room from the classic options. */
    static Shape choose(Bundle options, boolean landscape, float fontScale) {
        int width = options == null ? 0 : options.getInt(landscape
                ? android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH
                : android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
        return choose(width > 0 ? width : KeepADBOverviewWidget.DEFAULT_WIDTH_DP, fontScale);
    }

    /** @param ctx a localized context */
    static RemoteViews forShape(Context ctx, KeepADBWidgetState projection, Shape shape) {
        KeepADBOverviewContent content = KeepADBOverviewContent.of(ctx, projection);
        RemoteViews views = new RemoteViews(ctx.getPackageName(), shape.rootRes);

        // A launcher re-applies new views with the same layout onto the old ones; empty first.
        views.removeAllViews(R.id.widget_ov_card);
        views.addView(R.id.widget_ov_card, row(ctx, shape, content.adb));
        views.addView(R.id.widget_ov_card, row(ctx, shape, content.wifi));
        views.addView(R.id.widget_ov_card, row(ctx, shape, content.keepAlive));
        String cardDescription = content.cardDescription(ctx);
        if (shape == Shape.COMPACT) {
            views.addView(R.id.widget_ov_card, row(ctx, shape, content.delivery));
            // The history stays visible on its own quieter line, apart from the current claim.
            RemoteViews note = new RemoteViews(ctx.getPackageName(), R.layout.widget_ov_note);
            note.setTextViewText(R.id.widget_ov_value, content.lastDelivery);
            views.addView(R.id.widget_ov_card, note);
            cardDescription = cardDescription + ". " + content.deliveryDescription(ctx);
        } else {
            views.setImageViewResource(R.id.widget_ov_delivery_icon, content.delivery.iconRes);
            views.setInt(R.id.widget_ov_delivery_icon, "setColorFilter",
                    ctx.getColor(iconOnDark(content.delivery.style)));
            views.setTextViewText(R.id.widget_ov_delivery_label, content.delivery.label);
            views.setTextViewText(R.id.widget_ov_delivery_value, content.delivery.value);
            views.setTextColor(R.id.widget_ov_delivery_value, ctx.getColor(
                    content.delivery.style == KeepADBWidgetContent.Style.ATTENTION
                            ? R.color.widget_warn_ink : R.color.widget_text));
            views.setInt(R.id.widget_ov_delivery, "setBackgroundResource",
                    content.delivery.style == KeepADBWidgetContent.Style.ATTENTION
                            ? R.drawable.bg_widget_tile_attention : R.drawable.bg_widget_tile_off);
            views.setTextViewText(R.id.widget_ov_delivery_last, content.lastDelivery);
            views.setContentDescription(R.id.widget_ov_delivery, content.deliveryDescription(ctx));
            views.setOnClickPendingIntent(R.id.widget_ov_delivery,
                    KeepADBWidgetActions.forClick(ctx, content.deliveryClick));
        }
        // The card is informational: its one tap opens the app (or the network card), never a toggle.
        views.setContentDescription(R.id.widget_ov_card, cardDescription);
        views.setOnClickPendingIntent(R.id.widget_ov_card,
                KeepADBWidgetActions.forClick(ctx, content.cardClick));

        views.removeAllViews(R.id.widget_ov_sync);
        views.addView(R.id.widget_ov_sync, KeepADBWidgetViews.control(ctx,
                KeepADBWidgetViews.Anatomy.PILL, content.sync));
        return views;
    }

    private static RemoteViews row(Context ctx, Shape shape, KeepADBOverviewContent.Row row) {
        RemoteViews views = new RemoteViews(ctx.getPackageName(), shape.rowRes);
        views.setImageViewResource(R.id.widget_ov_icon, row.iconRes);
        views.setInt(R.id.widget_ov_icon, "setColorFilter", ctx.getColor(iconOnDark(row.style)));
        if (shape == Shape.WIDE) views.setTextViewText(R.id.widget_ov_label, row.label);
        views.setTextViewText(R.id.widget_ov_value, row.value);
        views.setTextColor(R.id.widget_ov_value, ctx.getColor(
                row.style == KeepADBWidgetContent.Style.ATTENTION
                        ? R.color.widget_warn_icon : R.color.widget_text));
        return views;
    }

    /**
     * The symbol color on the dark card. The control widget's "on" ink is dark because it sits on
     * the yellow fill; here every row is on the dark surface, so "on" is the brand yellow and the
     * symbol variant (filled, outlined, struck through) still tells the state without color.
     */
    static int iconOnDark(KeepADBWidgetContent.Style style) {
        switch (style) {
            case ON:
                return R.color.widget_on;
            case ATTENTION:
                return R.color.widget_warn_icon;
            case BUSY:
                return R.color.widget_busy_icon;
            case OFF:
            default:
                return R.color.widget_off_icon;
        }
    }

    /**
     * Android 12+: one entry per size the launcher reports (the same keys as the control widget,
     * {@link KeepADBWidgetLayout#entryKeys}), each drawn in the shape of its key.
     */
    @android.annotation.TargetApi(Build.VERSION_CODES.S)
    static RemoteViews responsive(Context ctx, KeepADBWidgetState projection, float fontScale,
            Bundle options) {
        Map<SizeF, RemoteViews> entries = responsiveEntries(ctx, projection, fontScale, options);
        RemoteViews fallback = forShape(ctx, projection, choose(
                KeepADBWidgetLayout.reportedSizes(options).get(0).getWidth(), fontScale));
        return KeepADBWidgetViews.sizedOrFallback(entries, fallback);
    }

    /** The entries of {@link #responsive}: one per launcher-reported size, in the shape of its key. */
    static Map<SizeF, RemoteViews> responsiveEntries(Context ctx, KeepADBWidgetState projection,
            float fontScale, Bundle options) {
        Map<SizeF, RemoteViews> entries = new LinkedHashMap<>();
        for (SizeF key : KeepADBWidgetLayout.entryKeys(options)) {
            entries.put(key, forShape(ctx, projection, choose(key.getWidth(), fontScale)));
        }
        return entries;
    }
}
