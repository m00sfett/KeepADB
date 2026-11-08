package de.hohnepeople.keepadb;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
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
 * <p>The design is "hero and rail": the main action ({@link KeepADBWidgetConfig#primary}) is a
 * large yellow-gradient tile while it is on, the others are flat pills, upright tiles or square
 * symbol buttons depending on the room. Fill, symbol variant and a word carry the state, never the
 * color alone; there are no borders. {@link KeepADBWidgetLayout} decides what a class shows, this
 * class draws it.
 *
 * <p>On Android 12+ ({@link #responsive}) the launcher gets one view per size class and picks the
 * fitting one itself while the user resizes, without calling the app. Android 11 has no such API;
 * there {@link #forSize} builds the one class the reported size selects, and the provider rebuilds
 * it when the options change.
 */
final class KeepADBWidgetViews {

    /** The shapes a control takes; each is one layout with the same ids where it has the part. */
    enum Anatomy {
        /** About 1x1: symbol, short label, short state; no port. */
        ONE(R.layout.widget_ctl_one),
        /** Compact hero: symbol, then the port or the short state word. */
        HERO_S(R.layout.widget_ctl_hero_s),
        /** 2x2 hero: symbol and label in a row, then the large port or the full state word. */
        HERO_M(R.layout.widget_ctl_hero_m),
        /** 4x2 hero over two rows: symbol, then label, state word and the large port. */
        HERO_L(R.layout.widget_ctl_hero_l),
        /** Horizontal pill: symbol, label and state word. */
        PILL(R.layout.widget_ctl_pill),
        /** Upright tile: symbol, label and state word, centered. */
        TILE_V(R.layout.widget_ctl_tile_v),
        /** Square symbol button, no text. */
        SQUARE(R.layout.widget_ctl_square);

        final int layoutRes;

        Anatomy(int layoutRes) {
            this.layoutRes = layoutRes;
        }

        boolean isHero() {
            return this == ONE || this == HERO_S || this == HERO_M || this == HERO_L;
        }
    }

    private KeepADBWidgetViews() {}

    /**
     * The widget for one size class.
     *
     * @param ctx a localized context; its strings and the package are used
     */
    static RemoteViews forSize(Context ctx, int appWidgetId, KeepADBWidgetConfig config,
            KeepADBWidgetState projection, KeepADBWidgetLayout.SizeClass sizeClass) {
        RemoteViews views = new RemoteViews(ctx.getPackageName(), rootLayout(sizeClass));
        List<KeepADBWidgetConfig.Action> others = config.others(sizeClass.otherControls);
        Anatomy hero = heroAnatomy(sizeClass);
        Anatomy secondary = secondaryAnatomy(sizeClass);

        // A launcher re-applies a new RemoteViews with the same layout onto the view it already
        // has (and, on Android 12+, switches between the size classes the same way). Added
        // controls would pile up there, so the slots are emptied first.
        views.removeAllViews(R.id.widget_slot_hero);
        views.addView(R.id.widget_slot_hero,
                control(ctx, hero, KeepADBWidgetContent.tile(ctx, config.primary, projection)));
        int[] slots = {R.id.widget_slot_a, R.id.widget_slot_b};
        for (int i = 0; i < slots.length; i++) {
            boolean used = secondary != null && i < others.size();
            views.removeAllViews(slots[i]);
            if (used) {
                views.addView(slots[i], control(ctx, secondary,
                        KeepADBWidgetContent.tile(ctx, others.get(i), projection)));
            }
            views.setViewVisibility(slots[i], used ? View.VISIBLE : View.GONE);
        }

        if (sizeClass.hasStatus) {
            KeepADBWidgetContent.Details content = KeepADBWidgetContent.details(ctx, projection);
            views.setTextViewText(R.id.widget_status_text, statusText(ctx, content));
            views.setContentDescription(R.id.widget_status_text, content.description());
            views.setOnClickPendingIntent(R.id.widget_status_text,
                    KeepADBWidgetActions.forClick(ctx, content.click));
            // The settings gear exists only here (a 48 dp touch target of its own); elsewhere the
            // settings are the launcher's reconfigure.
            views.setOnClickPendingIntent(R.id.widget_customize,
                    KeepADBWidgetActions.customize(ctx, appWidgetId));
        }
        return views;
    }

    private static int rootLayout(KeepADBWidgetLayout.SizeClass sizeClass) {
        switch (sizeClass) {
            case STACK:
                return R.layout.widget_root_stack;
            case STRIP:
                return R.layout.widget_root_strip;
            case GRID:
                return R.layout.widget_root_grid;
            case PANEL:
                return R.layout.widget_root_panel;
            case ONE:
            case HERO:
            case ROW1:
            case ROW2:
            default:
                return R.layout.widget_keepadb;
        }
    }

    static Anatomy heroAnatomy(KeepADBWidgetLayout.SizeClass sizeClass) {
        switch (sizeClass) {
            case ONE:
                return Anatomy.ONE;
            case GRID:
                return Anatomy.HERO_M;
            case PANEL:
                return Anatomy.HERO_L;
            case HERO:
            case ROW1:
            case ROW2:
            case STACK:
            case STRIP:
            default:
                return Anatomy.HERO_S;
        }
    }

    /** What the other controls look like in a class; null when it has none. */
    static Anatomy secondaryAnatomy(KeepADBWidgetLayout.SizeClass sizeClass) {
        switch (sizeClass) {
            case ROW1:
            case ROW2:
            case STACK:
                return Anatomy.SQUARE;
            case STRIP:
                return Anatomy.TILE_V;
            case GRID:
            case PANEL:
                return Anatomy.PILL;
            case ONE:
            case HERO:
            default:
                return null;
        }
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
        Map<SizeF, RemoteViews> sizes = new HashMap<>();
        for (KeepADBWidgetLayout.SizeClass sizeClass : KeepADBWidgetLayout.SizeClass.values()) {
            sizes.put(new SizeF(KeepADBWidgetLayout.responsiveWidthDp(sizeClass, fontScale),
                            KeepADBWidgetLayout.responsiveHeightDp(sizeClass, fontScale)),
                    forSize(ctx, appWidgetId, config, projection, sizeClass));
        }
        return new RemoteViews(sizes);
    }

    // -- one control ---------------------------------------------------------------------------

    /** One control in the given shape: fill, symbol and its color, texts, description and tap. */
    static RemoteViews control(Context ctx, Anatomy anatomy, KeepADBWidgetContent.Tile content) {
        RemoteViews tile = new RemoteViews(ctx.getPackageName(), anatomy.layoutRes);
        int ink = ctx.getColor(inkOf(content.style));
        int soft = ctx.getColor(softInkOf(content.style));
        tile.setInt(R.id.widget_tile, "setBackgroundResource",
                background(content.style, anatomy.isHero()));
        tile.setImageViewResource(R.id.widget_tile_icon, content.iconRes);
        tile.setInt(R.id.widget_tile_icon, "setColorFilter", ctx.getColor(iconOf(content.style)));

        switch (anatomy) {
            case ONE:
                tile.setTextViewText(R.id.widget_tile_label, content.shortLabel);
                tile.setTextViewText(R.id.widget_tile_state, content.shortState);
                text(tile, R.id.widget_tile_label, ink);
                text(tile, R.id.widget_tile_state, soft);
                break;
            case HERO_S:
                // The port stands alone under the symbol; without one the short state word does.
                port(tile, content.port, ink);
                if (content.port == null) {
                    tile.setTextViewText(R.id.widget_tile_state, content.shortState);
                    text(tile, R.id.widget_tile_state, ink);
                    tile.setViewVisibility(R.id.widget_tile_state, View.VISIBLE);
                } else {
                    tile.setViewVisibility(R.id.widget_tile_state, View.GONE);
                }
                break;
            case HERO_M:
                tile.setTextViewText(R.id.widget_tile_label, content.label);
                text(tile, R.id.widget_tile_label, ink);
                port(tile, content.port, ink);
                if (content.port == null) {
                    tile.setTextViewText(R.id.widget_tile_state, content.state);
                    text(tile, R.id.widget_tile_state, ink);
                    tile.setViewVisibility(R.id.widget_tile_state, View.VISIBLE);
                } else {
                    tile.setViewVisibility(R.id.widget_tile_state, View.GONE);
                }
                break;
            case HERO_L:
            case PILL:
            case TILE_V:
                tile.setTextViewText(R.id.widget_tile_label, content.label);
                tile.setTextViewText(R.id.widget_tile_state, content.state);
                text(tile, R.id.widget_tile_label, ink);
                text(tile, R.id.widget_tile_state, soft);
                if (anatomy == Anatomy.HERO_L) port(tile, content.port, ink);
                break;
            case SQUARE:
            default:
                break;
        }
        // Whatever is visible, TalkBack reads the full name, the state and the port from here.
        tile.setContentDescription(R.id.widget_tile, content.description);
        tile.setOnClickPendingIntent(R.id.widget_tile,
                KeepADBWidgetActions.forClick(ctx, content.click));
        return tile;
    }

    private static void text(RemoteViews tile, int id, int color) {
        tile.setTextColor(id, color);
    }

    private static void port(RemoteViews tile, String port, int color) {
        if (port == null) {
            tile.setViewVisibility(R.id.widget_tile_port, View.GONE);
            tile.setTextViewText(R.id.widget_tile_port, "");
            return;
        }
        tile.setViewVisibility(R.id.widget_tile_port, View.VISIBLE);
        tile.setTextViewText(R.id.widget_tile_port, port);
        tile.setTextColor(R.id.widget_tile_port, color);
    }

    private static int background(KeepADBWidgetContent.Style style, boolean hero) {
        switch (style) {
            case ON:
                return hero ? R.drawable.bg_widget_hero_on : R.drawable.bg_widget_pill_on;
            case BUSY:
                return R.drawable.bg_widget_tile_busy;
            case ATTENTION:
                return R.drawable.bg_widget_tile_attention;
            case OFF:
            default:
                return R.drawable.bg_widget_tile_off;
        }
    }

    /** Text and symbol colors per fill; the pairs are checked in KeepADBWidgetColorsTest. */
    static int inkOf(KeepADBWidgetContent.Style style) {
        switch (style) {
            case ON:
                return R.color.widget_ink;
            case ATTENTION:
                return R.color.widget_warn_ink;
            case BUSY:
            case OFF:
            default:
                return R.color.widget_text;
        }
    }

    static int softInkOf(KeepADBWidgetContent.Style style) {
        switch (style) {
            case ON:
                return R.color.widget_ink_soft;
            case ATTENTION:
                return R.color.widget_warn_ink_soft;
            case BUSY:
            case OFF:
            default:
                return R.color.widget_text_soft;
        }
    }

    static int iconOf(KeepADBWidgetContent.Style style) {
        switch (style) {
            case ON:
                return R.color.widget_ink;
            case ATTENTION:
                return R.color.widget_warn_icon;
            case BUSY:
                return R.color.widget_busy_icon;
            case OFF:
            default:
                return R.color.widget_off_icon;
        }
    }

    /** The one status line: the endpoint (or hidden notice, or reason) stronger, then the webhook. */
    private static CharSequence statusText(Context ctx, KeepADBWidgetContent.Details content) {
        SpannableStringBuilder text = new SpannableStringBuilder(content.lead);
        if (content.leadIsData) {
            text.setSpan(new ForegroundColorSpan(ctx.getColor(R.color.widget_text)), 0,
                    text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new StyleSpan(Typeface.BOLD), 0, text.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        text.append(" · ").append(content.webhook);
        return text;
    }
}
