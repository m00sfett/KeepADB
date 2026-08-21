package de.moos.wifiadb;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import android.widget.Toast;

public class AdbWifiWidget extends AppWidgetProvider {
    private static final String ACTION_TOGGLE = "de.moos.wifiadb.TOGGLE";

    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) render(context, mgr, id);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (ACTION_TOGGLE.equals(intent.getAction())) {
            boolean want = !AdbWifi.isEnabled(context);
            if (!AdbWifi.setEnabled(context, want)) {
                Toast.makeText(context,
                        "Keine Berechtigung. Am PC einmalig ausführen:\n"
                                + "adb shell pm grant " + context.getPackageName()
                                + " android.permission.WRITE_SECURE_SETTINGS",
                        Toast.LENGTH_LONG).show();
            } else if (!want) {
                AdbWifiPreferences.setKeepAliveEnabled(context, false);
                AdbWifiService.stop(context);
            }
            refreshAll(context);
            AdbWifiNotification.refresh(context);
        }
    }

    private void render(Context context, AppWidgetManager mgr, int id) {
        boolean on = AdbWifi.isEnabled(context);
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_adbwifi);
        views.setTextViewText(R.id.widget_label, on ? "WLAN-ADB: AN" : "WLAN-ADB: AUS");

        Intent i = new Intent(context, AdbWifiWidget.class).setAction(ACTION_TOGGLE);
        PendingIntent pi = PendingIntent.getBroadcast(context, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_label, pi);

        mgr.updateAppWidget(id, views);
        AdbWifiNotification.refresh(context);
    }

    /** Aktualisiert alle platzierten Widgets (auch nach Änderung via App/Tile aufrufbar). */
    static void refreshAll(Context context) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(context);
        ComponentName cn = new ComponentName(context, AdbWifiWidget.class);
        int[] ids = mgr.getAppWidgetIds(cn);
        new AdbWifiWidget().onUpdate(context, mgr, ids);
    }
}
