package de.hohnepeople.keepadb;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.List;

/** Owns the opt-in notification for an active USB-ADB connection. */
final class KeepADBUsbNotification {
    static final String CHANNEL_ID = "keepadb_usb";
    static final int NOTIFICATION_ID = 2;
    static final String EXTRA_PROFILE_ACTION = "profile_action";
    static final String ACTION_CREATE = "create";
    static final String ACTION_SWITCH = "switch";

    private KeepADBUsbNotification() {}

    static void refresh(Context context, boolean connected) {
        Context appContext = context.getApplicationContext();
        NotificationManager manager = appContext.getSystemService(NotificationManager.class);
        if (manager == null) return;
        ensureChannel(appContext, manager);

        if (connected) {
            KeepADBUsbProfile.Profile selected = KeepADBUsbProfile.getSelected(appContext);
            if (selected != null) {
                KeepADBRegisterClient.updateUsbEndpointAsync(appContext, selected);
            }
        } else {
            KeepADBRegisterClient.markUsbInactiveAsync(appContext);
        }

        if (!connected || !KeepADBUsbProfile.isNotificationEnabled(appContext)
                || !hasNotificationPermission(appContext)) {
            manager.cancel(NOTIFICATION_ID);
            return;
        }
        manager.notify(NOTIFICATION_ID, build(appContext));
    }

    static void cancel(Context context) {
        NotificationManager manager = context.getApplicationContext()
                .getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(NOTIFICATION_ID);
    }

    private static Notification build(Context context) {
        List<KeepADBUsbProfile.Profile> profiles = KeepADBUsbProfile.getProfiles(context);
        KeepADBUsbProfile.Profile selected = KeepADBUsbProfile.getSelected(context);
        String profileText = selected == null
                ? context.getString(R.string.usb_notification_no_profile)
                : selected.summary();
        Notification.Builder builder = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_keepadb)
                .setContentTitle(context.getString(R.string.usb_notification_title))
                .setContentText(profileText)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .setContentIntent(profileIntent(context, profiles.isEmpty() ? ACTION_CREATE : ACTION_SWITCH));
        if (profiles.isEmpty()) {
            builder.addAction(action(context, R.string.usb_notification_create_profile, ACTION_CREATE));
        } else {
            builder.addAction(action(context, R.string.usb_notification_switch_profile, ACTION_SWITCH));
            builder.addAction(action(context, R.string.usb_notification_new_profile, ACTION_CREATE));
        }
        return builder.build();
    }

    private static Notification.Action action(Context context, int title, String action) {
        return new Notification.Action.Builder(null, context.getString(title),
                profileIntent(context, action)).build();
    }

    private static PendingIntent profileIntent(Context context, String action) {
        Intent intent = new Intent(context, SettingsActivity.class)
                .putExtra(EXTRA_PROFILE_ACTION, action)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(context, action.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void ensureChannel(Context context, NotificationManager manager) {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.usb_notification_channel_name), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(context.getString(R.string.usb_notification_channel_desc));
        manager.createNotificationChannel(channel);
    }

    private static boolean hasNotificationPermission(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }
}
