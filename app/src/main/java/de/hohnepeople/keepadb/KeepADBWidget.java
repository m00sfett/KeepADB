package de.hohnepeople.keepadb;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.RemoteViews;
import android.widget.Toast;

public class KeepADBWidget extends AppWidgetProvider {
    private static final String ACTION_TOGGLE = "de.hohnepeople.keepadb.TOGGLE";
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) render(context, mgr, id, true);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        Context localizedContext = KeepADBLocaleHelper.wrapContext(context);
        if (ACTION_TOGGLE.equals(intent.getAction())) {
            KeepADBDiagnostics.event(context, "user_action", "widget", "toggle", "tap");
            KeepADB.State state = KeepADB.getState(context);
            boolean want = (state == KeepADB.State.OFF);
            if (!KeepADB.setEnabled(context, want, "widget")) {
                Toast.makeText(context,
                        localizedContext.getString(R.string.permission_error_toast, context.getPackageName()),
                        Toast.LENGTH_LONG).show();
            }
            KeepADBService.sync(context);
            refreshAll(context);
            KeepADBNotification.refresh(context);
            KeepADBTileService.requestRefresh(context);
        }
    }

    private void render(Context context, AppWidgetManager mgr, int id, boolean refreshNotification) {
        Context localizedContext = KeepADBLocaleHelper.wrapContext(context);
        KeepADB.State state = KeepADB.getState(context);
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_keepadb);
        String widgetText;
        switch (state) {
            case PERMISSION_MISSING:
                widgetText = localizedContext.getString(R.string.widget_text_permission_missing);
                break;
            case OFF:
                widgetText = localizedContext.getString(R.string.widget_text_off);
                break;
            case ENABLED_DISCONNECTED:
                widgetText = localizedContext.getString(R.string.widget_text_disconnected);
                break;
            case ENABLED_CONNECTED:
                int port = KeepADBNotification.getCurrentPort();
                if (port > 0) {
                    widgetText = localizedContext.getString(R.string.widget_text_connected_format, port);
                } else {
                    widgetText = localizedContext.getString(R.string.widget_text_on);
                }
                break;
            default:
                widgetText = localizedContext.getString(R.string.widget_text_off);
                break;
        }
        views.setTextViewText(R.id.widget_label, widgetText);
        views.setContentDescription(R.id.widget_label, widgetText);

        Intent i = new Intent(context, KeepADBWidget.class).setAction(ACTION_TOGGLE);
        PendingIntent pi = PendingIntent.getBroadcast(context, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_label, pi);

        mgr.updateAppWidget(id, views);
        if (refreshNotification) {
            KeepADBNotification.refresh(context);
        }
    }

    /** Aktualisiert alle platzierten Widgets (auch nach Änderung via App/Tile aufrufbar). */
    static void refreshAll(Context context) {
        refreshAll(context, true);
    }

    /** Renders the current state without starting another endpoint discovery pass. */
    static void refreshAllState(Context context) {
        refreshAll(context, false);
    }

    private static void refreshAll(Context context, boolean refreshNotification) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN_HANDLER.post(() -> refreshAll(context, refreshNotification));
            return;
        }
        AppWidgetManager mgr = AppWidgetManager.getInstance(context);
        if (mgr == null) return;
        ComponentName cn = new ComponentName(context, KeepADBWidget.class);
        int[] ids = mgr.getAppWidgetIds(cn);
        if (ids == null || ids.length == 0) return;
        KeepADBWidget provider = new KeepADBWidget();
        for (int id : ids) {
            provider.render(context, mgr, id, refreshNotification);
        }
    }
}
