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
import android.widget.Toast;

/**
 * Everything the force mode (#763) says outside its own screens: the line and the end action of the
 * persistent notification, the one-time expiry notice and the toast after an end.
 *
 * <p><b>Lock screen (F4):</b> nothing here is ever put into a public version. The persistent
 * notification keeps its neutral public version, and the expiry notice has its own neutral one (the
 * channel name only, no "force" and no protection level). Both texts below are private content,
 * with the limit {@link KeepADBNotification} documents: a user who lets Android show sensitive
 * notification content on the lock screen sees private versions there.
 *
 * <p><b>Channel (N5):</b> the expiry notice has its own channel {@link #CHANNEL_ID}. The endpoint
 * channel is the permanent silent status, the prompt channel asks about networks; neither fits, and
 * muting either must not mute this.
 *
 * <p><b>Request codes (N6):</b> the PendingIntents of the force mode use the block in {@link
 * KeepADBForceMode} (20 to 23), distinct from every other class's codes.
 */
final class KeepADBForceNotice {
    static final String CHANNEL_ID = "keepadb_security";
    static final int NOTIFICATION_ID = 4;

    private KeepADBForceNotice() {}

    // ---- While the mode is on -----------------------------------------------------------------------

    /**
     * The warning line of the persistent notification ("Force mode on until 14:30"), or null while
     * the mode is off. Reads the pure state, never performs the expiry transition: the notification
     * is rendered under the endpoint coordinator's monitor.
     */
    static String activeLine(Context context) {
        KeepADBForceMode.Status status = KeepADBForceMode.status(context);
        if (status == null) return null;
        Context localized = KeepADBLocaleHelper.wrapContext(context);
        return status.isUnlimited()
                ? localized.getString(R.string.force_notification_unlimited)
                : localized.getString(R.string.force_notification_until,
                        KeepADBForceMode.formatEnd(localized, status));
    }

    /** "End force mode": first action of the persistent notification, acts without a question. */
    static Notification.Action endAction(Context context) {
        Intent intent = new Intent(context, KeepADBReceiver.class)
                .setAction(KeepADBReceiver.ACTION_FORCE_END);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context,
                KeepADBForceMode.REQUEST_CODE_END, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder(null,
                KeepADBLocaleHelper.wrapContext(context).getString(R.string.force_action_end),
                pendingIntent).build();
    }

    /** "Force mode ended. Protection level back to: ..." after the user ended it. */
    static void showEndedToast(Context context) {
        try {
            Context localized = KeepADBLocaleHelper.wrapContext(context);
            Toast.makeText(context, localized.getString(R.string.force_ended_toast,
                    levelLabel(localized)), Toast.LENGTH_SHORT).show();
        } catch (RuntimeException ignored) {
            // A toast is a courtesy; a receiver without a looper or a UI must not fail on it.
        }
    }

    /** The stored protection level in the words of the presets, as the end and expiry texts name it. */
    static String levelLabel(Context localized) {
        switch (KeepADBTrustedNetwork.getProtectionLevel(localized)) {
            case BALANCED:
                return localized.getString(R.string.force_level_balanced);
            case LEGACY_ALL_WIFI:
                return localized.getString(R.string.force_level_legacy_all);
            case LEGACY_NAME_LIST:
                return localized.getString(R.string.force_level_legacy_names);
            case MAXIMUM_SECURITY:
            default:
                return localized.getString(R.string.force_level_maximum);
        }
    }

    // ---- The expiry notice -----------------------------------------------------------------------------

    /**
     * Posts the one-time expiry notice. No action that starts the force mode again: starting it
     * takes the dialog with its warnings. If Wireless Debugging is still on in a Wi-Fi network the
     * restored protection level does not trust, the notice says so and offers "Turn off now" (F6);
     * it never turns anything off by itself.
     *
     * @param safety the mode ended early for safety (its time could not be measured), not because
     *     the selected time was up (#773): the text says so.
     * @return true if a notification was posted (false without the notification permission).
     */
    static boolean postExpired(Context context, boolean safety) {
        Context app = context.getApplicationContext();
        NotificationManager manager = app.getSystemService(NotificationManager.class);
        if (manager == null || !hasNotificationPermission(app)) return false;
        Context localized = KeepADBLocaleHelper.wrapContext(app);
        ensureChannel(localized, manager);

        String text = localized.getString(
                safety ? R.string.force_expired_text_safety : R.string.force_expired_text,
                levelLabel(localized));
        boolean stillExposed = isStillExposed(app);
        CharSequence bigText = stillExposed
                ? text + "\n" + localized.getString(R.string.force_expired_still_on) : text;
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        Notification publicVersion = new Notification.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_keepadb)
                .setContentTitle(localized.getString(R.string.force_channel_name))
                .setCategory(Notification.CATEGORY_STATUS)
                .build();
        Notification.Builder builder = new Notification.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_keepadb)
                .setContentTitle(localized.getString(R.string.force_expired_title))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(bigText))
                .setContentIntent(PendingIntent.getActivity(app,
                        KeepADBForceMode.REQUEST_CODE_EXPIRED_CONTENT, focusForceIntent(app), flags))
                .setCategory(Notification.CATEGORY_STATUS)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setPublicVersion(publicVersion);
        if (stillExposed) {
            Intent turnOff = new Intent(app, KeepADBReceiver.class)
                    .setAction(KeepADBReceiver.ACTION_DISABLE);
            builder.addAction(new Notification.Action.Builder(null,
                    localized.getString(R.string.force_expired_action_off),
                    PendingIntent.getBroadcast(app, KeepADBForceMode.REQUEST_CODE_EXPIRED_TURN_OFF,
                            turnOff, flags)).build());
        }
        manager.notify(NOTIFICATION_ID, builder.build());
        return true;
    }

    static void cancelExpired(Context context) {
        NotificationManager manager = context.getApplicationContext()
                .getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(NOTIFICATION_ID);
    }

    /** Tap target of the notice: the force row in Settings, expanded and in view. */
    static Intent focusForceIntent(Context context) {
        return new Intent(context, SettingsActivity.class)
                .putExtra(SettingsActivity.EXTRA_FOCUS_FORCE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    /**
     * Whether Wireless Debugging is on in a Wi-Fi network the stored protection level would not
     * have switched it on in: the case F6 asks to report, not to correct. Evaluated after the mode
     * is gone, so it is the level's own verdict.
     */
    static boolean isStillExposed(Context app) {
        return Boolean.TRUE.equals(KeepADB.isEnabledOrNull(app, "force_expiry"))
                && KeepADBService.isWifiConnected(app)
                && !KeepADBTrustedNetwork.isCurrentNetworkTrusted(app);
    }

    private static void ensureChannel(Context localized, NotificationManager manager) {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                localized.getString(R.string.force_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(localized.getString(R.string.force_channel_desc));
        manager.createNotificationChannel(channel);
    }

    private static boolean hasNotificationPermission(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }
}
