package de.hohnepeople.keepadb;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import android.widget.Toast;

public class KeepADBWidget extends AppWidgetProvider {
    private static final String ACTION_TOGGLE = "de.hohnepeople.keepadb.TOGGLE";

    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) render(context, mgr, id);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        Context localizedContext = KeepADBLocaleHelper.wrapContext(context);
        if (ACTION_TOGGLE.equals(intent.getAction())) {
            KeepADBDiagnostics.event(context, "user_action", "widget", "toggle", "tap");
            boolean want = !KeepADB.isEnabled(context);
            if (!KeepADB.setEnabled(context, want, "widget")) {
                Toast.makeText(context,
                        localizedContext.getString(R.string.permission_error_toast, context.getPackageName()),
                        Toast.LENGTH_LONG).show();
            }
            KeepADBService.sync(context);
            refreshAll(context);
            KeepADBNotification.refresh(context);
        }
    }

    private void render(Context context, AppWidgetManager mgr, int id) {
        Context localizedContext = KeepADBLocaleHelper.wrapContext(context);
        boolean on = KeepADB.isEnabled(context);
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_keepadb);
        String widgetText = on ? localizedContext.getString(R.string.widget_text_on)
                : localizedContext.getString(R.string.widget_text_off);
        views.setTextViewText(R.id.widget_label, widgetText);
        views.setContentDescription(R.id.widget_label, widgetText);

        Intent i = new Intent(context, KeepADBWidget.class).setAction(ACTION_TOGGLE);
        PendingIntent pi = PendingIntent.getBroadcast(context, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_label, pi);

        mgr.updateAppWidget(id, views);
        KeepADBNotification.refresh(context);
    }

    /** Aktualisiert alle platzierten Widgets (auch nach Änderung via App/Tile aufrufbar). */
    static void refreshAll(Context context) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(context);
        if (mgr == null) return;
        ComponentName cn = new ComponentName(context, KeepADBWidget.class);
        int[] ids = mgr.getAppWidgetIds(cn);
        if (ids == null || ids.length == 0) return;
        new KeepADBWidget().onUpdate(context, mgr, ids);
    }
}
