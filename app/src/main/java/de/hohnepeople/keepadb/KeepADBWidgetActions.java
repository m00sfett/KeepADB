package de.hohnepeople.keepadb;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/**
 * #912: the PendingIntents of the control widget. Every one is an explicit intent to a component of
 * this app and {@link PendingIntent#FLAG_IMMUTABLE}, so no other app can redirect or fill one in.
 *
 * <p>The controls do what the rest of the app does, through the same entry points: the Wireless
 * Debugging control is the widget's own toggle broadcast (the shared click semantics, permission
 * and trust handling of #318), Keep-Alive is the notification's action, and the webhook controls are
 * the dedicated actions of {@link KeepADBWebhookActions}. The widget implements none of them again.
 *
 * <p>Only the settings screen of one widget differs per instance, so only that PendingIntent is
 * keyed by the widget id (request code and data); the others act on app-wide state and are the
 * same for every widget on purpose.
 */
final class KeepADBWidgetActions {
    static final String ACTION_TOGGLE = "de.hohnepeople.keepadb.TOGGLE";

    // Distinct from the webhook actions (61-64), the notification's own requests and each other.
    private static final int REQUEST_CODE_TOGGLE_ADB = 0; // unchanged from the single-button widget
    private static final int REQUEST_CODE_TOGGLE_KEEP_ALIVE = 71;
    private static final int REQUEST_CODE_OPEN_APP = 72;
    private static final int REQUEST_CODE_OPEN_NETWORK = 73;
    private static final int REQUEST_CODE_CUSTOMIZE_BASE = 1000;

    private KeepADBWidgetActions() {}

    /** The target of a tap; one place decides which PendingIntent a {@link KeepADBWidgetContent.Click} is. */
    static PendingIntent forClick(Context context, KeepADBWidgetContent.Click click) {
        switch (click) {
            case TOGGLE_KEEP_ALIVE:
                return toggleKeepAlive(context);
            case SYNC_ENABLE:
                return KeepADBWebhookActions.setAutomaticSync(context, true);
            case SYNC_DISABLE:
                return KeepADBWebhookActions.setAutomaticSync(context, false);
            case SETUP_WEBHOOK:
                return KeepADBWebhookActions.setup(context);
            case OPEN_NETWORK:
                return openNetwork(context);
            case OPEN_APP:
                return openApp(context);
            case TOGGLE_ADB:
            default:
                return toggleAdb(context);
        }
    }

    /**
     * Switches Wireless Debugging. The same intent as the single-button widget used, so a widget
     * placed before this version keeps working through it.
     */
    static PendingIntent toggleAdb(Context context) {
        Context app = context.getApplicationContext();
        Intent intent = new Intent(app, KeepADBWidget.class).setAction(ACTION_TOGGLE);
        return PendingIntent.getBroadcast(app, REQUEST_CODE_TOGGLE_ADB, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** The notification's Keep-Alive action: flips the real setting, the service's guards stay. */
    static PendingIntent toggleKeepAlive(Context context) {
        Context app = context.getApplicationContext();
        Intent intent = new Intent(app, KeepADBReceiver.class)
                .setAction(KeepADBReceiver.ACTION_TOGGLE_KEEP_ALIVE);
        return PendingIntent.getBroadcast(app, REQUEST_CODE_TOGGLE_KEEP_ALIVE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** The home screen: the one-time setup, the full status and the reasons. */
    static PendingIntent openApp(Context context) {
        Context app = context.getApplicationContext();
        Intent intent = new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(app, REQUEST_CODE_OPEN_APP, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** The settings at the network card: where a Wi-Fi network is trusted or blocked. */
    static PendingIntent openNetwork(Context context) {
        Context app = context.getApplicationContext();
        Intent intent = new Intent(app, SettingsActivity.class)
                .putExtra(SettingsActivity.EXTRA_FOCUS_NETWORK, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(app, REQUEST_CODE_OPEN_NETWORK, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * The settings of this one widget. Keyed by the widget id (request code and data URI), so two
     * widgets never share or replace each other's PendingIntent.
     */
    static PendingIntent customize(Context context, int appWidgetId) {
        Context app = context.getApplicationContext();
        Intent intent = new Intent(app, KeepADBWidgetConfigActivity.class)
                .setAction(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                .setData(Uri.parse("keepadb://widget/" + appWidgetId + "/customize"))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(app, REQUEST_CODE_CUSTOMIZE_BASE + appWidgetId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
