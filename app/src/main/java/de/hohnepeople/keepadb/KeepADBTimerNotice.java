package de.hohnepeople.keepadb;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * #1000: the one-time notice at the end of a Wifi-ADB timer ("Wifi-ADB off – timer ended"). Silent,
 * dismissible, gone by itself after an hour; on the timer channel. It offers nothing that turns
 * Wifi-ADB on again: that is a deliberate "on" in the app, the tile or a widget.
 */
final class KeepADBTimerNotice {
    static final int NOTIFICATION_ID = 8;
    static final long TIMEOUT_MS = 60L * 60L * 1000L;

    /** The sink {@link KeepADBTimer} posts through; registered by the application. */
    static final KeepADBTimer.NoticeSink SINK = KeepADBTimerNotice::postExpired;

    private KeepADBTimerNotice() {}

    /**
     * @param reason {@link KeepADBTimer#REASON_WHILE_OFF}, {@link KeepADBTimer#REASON_SAFETY} or
     *     null for an ordinary end.
     * @return true if a notification was posted (false without the notification permission).
     */
    static boolean postExpired(Context context, String reason) {
        Context app = context.getApplicationContext();
        NotificationManager manager = app.getSystemService(NotificationManager.class);
        if (manager == null || !hasNotificationPermission(app)) return false;
        Context localized = KeepADBLocaleHelper.wrapContext(app);
        KeepADBNotification.prepareChannel(localized);
        int text;
        if (KeepADBTimer.REASON_WHILE_OFF.equals(reason)) {
            text = R.string.timer_expired_text_while_off;
        } else if (KeepADBTimer.REASON_SAFETY.equals(reason)) {
            text = R.string.timer_expired_text_safety;
        } else {
            text = R.string.timer_expired_text;
        }
        Intent home = new Intent(app, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        Notification notification = new Notification.Builder(app, KeepADBNotification.TIMER_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_keepadb)
                .setContentTitle(localized.getString(R.string.timer_expired_title))
                .setContentText(localized.getString(text))
                .setStyle(new Notification.BigTextStyle().bigText(localized.getString(text)))
                .setContentIntent(PendingIntent.getActivity(app,
                        KeepADBTimer.REQUEST_CODE_EXPIRED_CONTENT, home,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .setCategory(Notification.CATEGORY_STATUS)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setTimeoutAfter(TIMEOUT_MS)
                .build();
        KeepADBLockScreen.post(app, manager, NOTIFICATION_ID, notification);
        return true;
    }

    /** A new start makes an old end notice obsolete. */
    static void cancelExpired(Context context) {
        NotificationManager manager = context.getApplicationContext()
                .getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(NOTIFICATION_ID);
    }

    private static boolean hasNotificationPermission(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }
}
