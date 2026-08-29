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
    private static final int HANDOVER_ACTION_REQUEST_CODE = 1;

    // #168: set when the MANUAL "Enable WLAN-ADB" action failed (e.g. missing
    // WRITE_SECURE_SETTINGS), so the notification shows a clear error instead of silently doing
    // nothing or implying success. Cleared as soon as the action button's own precondition
    // (connected, MANUAL mode, WLAN-ADB still off) no longer holds -- disconnect or a successful
    // enable both make the button (and with it the error) disappear together.
    private static volatile boolean lastHandoverActionFailed;

    private KeepADBUsbNotification() {}

    static void refresh(Context context, boolean connected) {
        Context appContext = context.getApplicationContext();
        if (connected) {
            KeepADBUsbProfile.Profile selected = KeepADBUsbProfile.getSelected(appContext);
            if (selected != null) {
                KeepADBRegisterClient.updateUsbEndpointAsync(appContext, selected);
            } else {
                KeepADBRegisterClient.markUsbInactiveAsync(appContext);
            }
        } else {
            KeepADBRegisterClient.markUsbInactiveAsync(appContext);
        }

        boolean handoverActionVisible = connected
                && KeepADBPreferences.USB_WLAN_HANDOVER_MODE_MANUAL.equals(
                        KeepADBPreferences.getUsbWlanHandoverMode(appContext))
                && !KeepADB.isEnabled(appContext);
        if (!handoverActionVisible) {
            lastHandoverActionFailed = false;
        }

        NotificationManager manager = appContext.getSystemService(NotificationManager.class);
        if (manager == null) return;
        ensureChannel(appContext, manager);

        if (!connected || !KeepADBUsbProfile.isNotificationEnabled(appContext)
                || !hasNotificationPermission(appContext)) {
            manager.cancel(NOTIFICATION_ID);
            return;
        }
        manager.notify(NOTIFICATION_ID, build(appContext, handoverActionVisible));
    }

    static boolean isLastHandoverActionFailed() {
        return lastHandoverActionFailed;
    }

    /** Result callback for the MANUAL "Enable WLAN-ADB" action (#168). USB is still connected at
     * this point (the button that triggered it is only shown while connected), so re-deriving the
     * notification with connected=true is safe and not treated as a fresh connect edge. */
    static void reportManualActionResult(Context context, boolean success) {
        lastHandoverActionFailed = !success;
        refresh(context, true);
    }

    static void cancel(Context context) {
        NotificationManager manager = context.getApplicationContext()
                .getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(NOTIFICATION_ID);
    }

    private static Notification build(Context context, boolean handoverActionVisible) {
        boolean profileNotificationEnabled = KeepADBUsbProfile.isProfileNotificationEnabled(context);
        String contentText;
        PendingIntent contentIntent;

        Notification.Builder builder = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_keepadb)
                .setContentTitle(context.getString(R.string.usb_notification_title))
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_STATUS);

        if (profileNotificationEnabled) {
            List<KeepADBUsbProfile.Profile> profiles = KeepADBUsbProfile.getProfiles(context);
            KeepADBUsbProfile.Profile selected = KeepADBUsbProfile.getSelected(context);
            String profileText = selected == null
                    ? context.getString(R.string.usb_notification_no_profile)
                    : selected.summary();
            contentText = (handoverActionVisible && lastHandoverActionFailed)
                    ? context.getString(R.string.usb_notification_handover_error)
                    : profileText;
            contentIntent = profileIntent(context, profiles.isEmpty() ? ACTION_CREATE : ACTION_SWITCH);
            builder.setContentText(contentText)
                    .setContentIntent(contentIntent);
            if (profiles.isEmpty()) {
                builder.addAction(action(context, R.string.usb_notification_create_profile, ACTION_CREATE));
            } else {
                builder.addAction(action(context, R.string.usb_notification_switch_profile, ACTION_SWITCH));
                builder.addAction(action(context, R.string.usb_notification_new_profile, ACTION_CREATE));
            }
        } else {
            contentText = (handoverActionVisible && lastHandoverActionFailed)
                    ? context.getString(R.string.usb_notification_handover_error)
                    : context.getString(R.string.usb_notification_title);
            Intent intent = new Intent(context, SettingsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            contentIntent = PendingIntent.getActivity(context, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.setContentText(contentText)
                    .setContentIntent(contentIntent);
        }

        if (handoverActionVisible) {
            builder.addAction(handoverAction(context));
        }
        return builder.build();
    }

    private static Notification.Action action(Context context, int title, String action) {
        return new Notification.Action.Builder(null, context.getString(title),
                profileIntent(context, action)).build();
    }

    private static Notification.Action handoverAction(Context context) {
        Intent intent = new Intent(context, KeepADBUsbReceiver.class)
                .setAction(KeepADBUsbReceiver.ACTION_HANDOVER_ENABLE);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, HANDOVER_ACTION_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder(null,
                context.getString(R.string.usb_notification_enable_wlan_handover), pendingIntent).build();
    }

    private static PendingIntent profileIntent(Context context, String action) {
        Intent intent = new Intent(context, SettingsActivity.class)
                .putExtra(EXTRA_PROFILE_ACTION, action)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(context, action.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Reset state for unit tests. */
    static void resetForTesting() {
        lastHandoverActionFailed = false;
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
