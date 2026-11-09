package de.hohnepeople.keepadb;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.RemoteViews;
import android.widget.Toast;

/**
 * The control widget (#912): up to three controls (Wireless Debugging, Keep-Alive, automatic
 * webhook synchronization), more of them and more detail the larger the widget is. Which
 * ones, in which order and with which main action is configured per placed instance
 * ({@link KeepADBWidgetConfig}); a widget placed before that existed shows the defaults and keeps
 * its single Wireless Debugging toggle as the first control.
 *
 * <p><b>Rendering is read-only.</b> A render, a resize and a refresh after a state change only
 * read the projection ({@link KeepADBWidgetState}); none of them sends a report or starts a
 * discovery. The one place that asks the endpoint coordinator for work is {@link #onUpdate}, see
 * there.
 */
public class KeepADBWidget extends AppWidgetProvider {
    private static final String ACTION_TOGGLE = KeepADBWidgetActions.ACTION_TOGGLE;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    /**
     * Placement and boot (the system sends this for each; {@code updatePeriodMillis} is 0, so it
     * never repeats on a timer). It renders and then asks the endpoint coordinator for one refresh.
     *
     * <p>The refresh stays on purpose: after a restart {@link BootReceiver} starts the service (and
     * with it the coordinator) only when Keep-Alive is on. With Keep-Alive off nothing else runs
     * the coordinator then, so a placed widget is what makes the notification and the endpoint
     * discovery pick up an already enabled Wireless Debugging. It is one call per update, not one
     * per widget and not one per render, and an unchanged endpoint is not reported again (#866).
     * Resizing and state changes never get here.
     */
    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) render(context, mgr, id);
        // Watch the setting for the widget while no screen or service is running (see the job).
        KeepADBWidgetRefreshJob.schedule(context);
        KeepADBEndpointCoordinator.refresh(context);
    }

    /** The launcher resized the widget (Android 11: the only signal; 12+: harmless). Render only. */
    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager mgr, int id,
            Bundle newOptions) {
        render(context, mgr, id);
    }

    /** A removed instance takes its configuration with it and nothing else. */
    @Override
    public void onDeleted(Context context, int[] ids) {
        KeepADBWidgetConfig.delete(context, ids);
    }

    /** The last instance is gone: no entry can belong to a widget any more. */
    @Override
    public void onDisabled(Context context) {
        KeepADBWidgetRefreshJob.cancel(context);
        KeepADBWidgetConfig.clearAll(context);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (Intent.ACTION_LOCALE_CHANGED.equals(intent.getAction())) {
            // The system language changed while the widgets were drawn in the old one (#912).
            // Draw only; nothing is sent and no discovery starts.
            refreshAllState(context);
            return;
        }
        Context localizedContext = KeepADBLocaleHelper.wrapContext(context);
        if (ACTION_TOGGLE.equals(intent.getAction())) {
            KeepADBDiagnostics.event(context, "user_action", "widget", "toggle", "tap");
            KeepADB.State state = KeepADB.getState(context);
            // #318: shared click semantics with MainActivity's switch and the tile.
            boolean want = KeepADB.desiredOnForClick(state);
            KeepADB.ToggleResult result = KeepADB.setEnabled(context, want, "widget");
            if (!result.isSuccess()) {
                // #318: separate a missing permission from a rejected write instead of always
                // pointing at the one-time setup. #795: the cause comes from the toggle result.
                // #817: every permission cause (also a SecurityException) points at the grant.
                Toast.makeText(context,
                        result.isPermissionFailure()
                                ? localizedContext.getString(R.string.permission_error_toast,
                                        context.getPackageName())
                                : localizedContext.getString(R.string.toggle_failed_toast),
                        Toast.LENGTH_LONG).show();
            }
            KeepADBService.sync(context);
            refreshAll(context);
            KeepADBEndpointCoordinator.refresh(context);
            KeepADBTileService.requestRefresh(context);
        }
    }

    /**
     * Renders one instance from the read-only projection. Rendering sends nothing and starts no
     * discovery (#912): a resize, a state change and a re-render only read; the coordinator is
     * asked for a refresh by {@link #onUpdate} (placement, boot) and by the components that change
     * the state, never from here.
     */
    private void render(Context context, AppWidgetManager mgr, int id) {
        Context localizedContext = KeepADBLocaleHelper.wrapContext(context);
        KeepADBWidgetConfig config = KeepADBWidgetConfig.load(context, id);
        // #910/#904: one projection; the widget privacy switch decides first, the per-widget
        // option can only hide more.
        KeepADBWidgetState projection = KeepADBWidgetState.project(context, config.hideDetails);
        float fontScale = context.getResources().getConfiguration().fontScale;
        RemoteViews views;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+: the launcher picks the fitting size class itself while the user resizes.
            views = KeepADBWidgetViews.responsive(localizedContext, id, config, projection, fontScale);
        } else {
            views = legacyViews(localizedContext, id, config, projection,
                    mgr.getAppWidgetOptions(id), fontScale);
        }
        mgr.updateAppWidget(id, views);
    }

    /**
     * Android 11 has no sized entries, so both orientations are supplied and the host picks the
     * one that matches the screen. A rotation changes neither the options nor sends an update
     * (AOSP does not call onAppWidgetOptionsChanged when min and max stay the same), so a view
     * built for the current orientation only would stay as it was.
     */
    static RemoteViews legacyViews(Context localizedContext, int id, KeepADBWidgetConfig config,
            KeepADBWidgetState projection, Bundle options, float fontScale) {
        RemoteViews landscape = KeepADBWidgetViews.forSize(localizedContext, id, config,
                projection, KeepADBWidgetLayout.choose(options, true, fontScale));
        RemoteViews portrait = KeepADBWidgetViews.forSize(localizedContext, id, config,
                projection, KeepADBWidgetLayout.choose(options, false, fontScale));
        return new RemoteViews(landscape, portrait);
    }

    /**
     * Re-renders all placed widgets after a change through the app, the tile or a setting. Callers
     * that change the state ask the endpoint coordinator themselves; this only draws.
     */
    static void refreshAll(Context context) {
        refreshAllState(context);
    }

    /** Renders the current state without starting another endpoint discovery pass. */
    static void refreshAllState(Context context) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN_HANDLER.post(() -> refreshAllState(context));
            return;
        }
        AppWidgetManager mgr = AppWidgetManager.getInstance(context);
        if (mgr == null) return;
        ComponentName cn = new ComponentName(context, KeepADBWidget.class);
        int[] ids = mgr.getAppWidgetIds(cn);
        if (ids == null || ids.length == 0) return;
        KeepADBWidget provider = new KeepADBWidget();
        for (int id : ids) {
            provider.render(context, mgr, id);
        }
    }

    /** Re-renders one instance, for example after its configuration was saved. */
    static void refresh(Context context, int appWidgetId) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN_HANDLER.post(() -> refresh(context, appWidgetId));
            return;
        }
        AppWidgetManager mgr = AppWidgetManager.getInstance(context);
        if (mgr == null) return;
        new KeepADBWidget().render(context, mgr, appWidgetId);
    }
}
