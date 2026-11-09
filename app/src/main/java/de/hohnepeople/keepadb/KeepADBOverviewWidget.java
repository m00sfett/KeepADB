package de.hohnepeople.keepadb;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.RemoteViews;

/**
 * #913: the overview widget. It shows Wireless Debugging (with the verified endpoint), the Wi-Fi
 * network, Keep-Alive with its reason and the webhook delivery, and offers one control: the
 * automatic webhook synchronization, through the same action path as the control widget.
 *
 * <p>Wireless Debugging and Keep-Alive are informational here: a tap on them opens the app, it
 * never toggles them ({@link KeepADBOverviewContent}).
 *
 * <p><b>Shared lifecycle.</b> The overview has no state, timer, polling or report loop of its own.
 * It renders the shared read-only projection ({@link KeepADBWidgetState}); every component that
 * changes the state already calls {@link KeepADBWidget#refreshAllState}, which draws the overview
 * widgets too, and the refresh job ({@link KeepADBWidgetRefreshJob}) watches the setting for both
 * kinds. Its per-widget option ("hide details") lives in the same per-instance store as the control
 * widget's settings ({@link KeepADBWidgetConfig}); widget ids are unique per host, so the entries
 * never collide.
 */
public class KeepADBOverviewWidget extends AppWidgetProvider {
    /** The declared default size (Android 11 without options): about 2x2. */
    static final int DEFAULT_WIDTH_DP = 152;

    /**
     * Placement and boot. Renders, keeps the setting watched and asks the endpoint coordinator for
     * one refresh, for the same reason as the control widget ({@link KeepADBWidget#onUpdate}).
     */
    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) render(context, mgr, id);
        KeepADBWidgetRefreshJob.schedule(context);
        KeepADBEndpointCoordinator.refresh(context);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager mgr, int id,
            Bundle newOptions) {
        render(context, mgr, id);
    }

    @Override
    public void onDeleted(Context context, int[] ids) {
        KeepADBWidgetConfig.delete(context, ids);
    }

    @Override
    public void onDisabled(Context context) {
        KeepADBWidget.lastWidgetOfAKindRemoved(context, KeepADBOverviewWidget.class);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (Intent.ACTION_LOCALE_CHANGED.equals(intent.getAction())) {
            // Draw only, in the new language; nothing is sent and no discovery starts.
            refreshAllState(context);
        }
    }

    /** Renders one instance from the read-only projection; sends nothing, starts nothing. */
    static void render(Context context, AppWidgetManager mgr, int id) {
        Context localized = KeepADBLocaleHelper.wrapContext(context);
        KeepADBWidgetConfig config = KeepADBWidgetConfig.load(context, id);
        KeepADBWidgetState projection = KeepADBWidgetState.project(context, config.hideDetails);
        float fontScale = context.getResources().getConfiguration().fontScale;
        Bundle options = mgr.getAppWidgetOptions(id);
        RemoteViews views;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            views = KeepADBOverviewViews.responsive(localized, projection, fontScale, options);
        } else {
            views = legacyViews(localized, projection, options, fontScale);
        }
        mgr.updateAppWidget(id, views);
    }

    /** Android 11: both orientations, the host picks the one that matches the screen. */
    static RemoteViews legacyViews(Context localized, KeepADBWidgetState projection,
            Bundle options, float fontScale) {
        RemoteViews landscape = KeepADBOverviewViews.forShape(localized, projection,
                KeepADBOverviewViews.choose(options, true, fontScale));
        RemoteViews portrait = KeepADBOverviewViews.forShape(localized, projection,
                KeepADBOverviewViews.choose(options, false, fontScale));
        return new RemoteViews(landscape, portrait);
    }

    /** Draws every placed overview widget; called from {@link KeepADBWidget#refreshAllState}. */
    static void refreshAllState(Context context) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(context);
        if (mgr == null) return;
        int[] ids = mgr.getAppWidgetIds(new ComponentName(context, KeepADBOverviewWidget.class));
        if (ids == null) return;
        for (int id : ids) render(context, mgr, id);
    }
}
