package de.hohnepeople.keepadb;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StyleSpan;

/**
 * Renders the Wireless Debugging endpoint notification.
 *
 * <p>#594: this class owns no endpoint state. {@link KeepADBEndpointCoordinator} owns the cached
 * endpoint, discovery, verification, retry and cancellation and calls the {@code render*} /
 * {@link #remove} methods below while holding its monitor; this class only turns that decision
 * into a posted (or cancelled) notification.
 */
final class KeepADBNotification {
    static final String CHANNEL_ID = "keepadb_endpoint";
    static final int NOTIFICATION_ID = 1;

    private KeepADBNotification() {}

    static Notification getServiceNotification(Context context) {
        Context appContext = KeepADBLocaleHelper.wrapContext(context.getApplicationContext());
        NotificationManager manager = appContext.getSystemService(NotificationManager.class);
        if (manager != null) {
            ensureChannel(appContext, manager);
        }
        KeepADBEndpointCoordinator.Snapshot snapshot = KeepADBEndpointCoordinator.snapshot();
        if (snapshot.hasEndpoint()) {
            return buildNotification(appContext, snapshot.host, snapshot.port);
        } else {
            return buildPlaceholderNotification(appContext,
                    appContext.getString(R.string.notification_title_searching, appContext.getString(R.string.app_name)),
                    appContext.getString(R.string.notification_text_searching));
        }
    }

    static void showPermissionMissing(Context context) {
        // #594: serialized with the coordinator's own rendering (same monitor as before the split,
        // when both lived in this class), so two notify() calls can never interleave.
        synchronized (KeepADBEndpointCoordinator.class) {
            Context appContext = KeepADBLocaleHelper.wrapContext(context.getApplicationContext());
            NotificationManager manager = appContext.getSystemService(NotificationManager.class);
            if (manager == null) return;
            ensureChannel(appContext, manager);
            showPlaceholder(appContext, manager,
                    appContext.getString(R.string.notification_permission_missing_title, appContext.getString(R.string.app_name)),
                    appContext.getString(R.string.notification_permission_missing_text));
        }
    }

    /** Whether a {@link NotificationManager} is available to render into at all. */
    static boolean canRender(Context appContext) {
        return appContext.getSystemService(NotificationManager.class) != null;
    }

    /** Ensures the endpoint channel exists; {@code false} when no {@link NotificationManager} exists. */
    static boolean prepareChannel(Context appContext) {
        NotificationManager manager = appContext.getSystemService(NotificationManager.class);
        if (manager == null) return false;
        ensureChannel(appContext, manager);
        return true;
    }

    /** Renders the active endpoint {@code host:port}. */
    static void renderEndpoint(Context appContext, String host, int port) {
        NotificationManager manager = appContext.getSystemService(NotificationManager.class);
        if (manager == null) return;
        show(appContext, manager, host, port);
    }

    /** Renders the "searching for the endpoint" placeholder. */
    static void renderSearching(Context appContext) {
        NotificationManager manager = appContext.getSystemService(NotificationManager.class);
        if (manager == null) return;
        showPlaceholder(appContext, manager,
                appContext.getString(R.string.notification_title_searching, appContext.getString(R.string.app_name)),
                appContext.getString(R.string.notification_text_searching));
    }

    /** #445: renders "Wireless Debugging off, Keep-Alive waiting for it to come back". */
    static void renderDisabledKeepAliveWaiting(Context appContext) {
        NotificationManager manager = appContext.getSystemService(NotificationManager.class);
        if (manager == null) return;
        showPlaceholder(appContext, manager,
                appContext.getString(R.string.notification_title_disabled, appContext.getString(R.string.app_name)),
                appContext.getString(R.string.notification_text_disabled_keepalive_waiting));
    }

    /** Removes the endpoint notification. */
    static void remove(Context appContext) {
        NotificationManager manager = appContext.getSystemService(NotificationManager.class);
        if (manager == null) return;
        manager.cancel(NOTIFICATION_ID);
    }

    private static void ensureChannel(Context context, NotificationManager manager) {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(context.getString(R.string.notification_channel_desc));
        manager.createNotificationChannel(channel);
    }

    private static boolean hasNotificationPermission(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static void show(Context context, NotificationManager manager, String host, int port) {
        if (!hasNotificationPermission(context)) {
            KeepADBDiagnostics.event(context, "notification_removed", "notification", "skipped", "permission_missing");
            return;
        }
        if (KeepADBPreferences.isNotificationHidden(context)) {
            // #445: gate on shouldRun(), not isEnabled() -- the foreground service (and thus
            // Android's requirement for a notification) can still be running with Wireless
            // Debugging off (Keep-Alive waiting for it to come back). The old isEnabled()-only
            // check assumed the two always agreed, which is exactly what #445 disproves; a
            // hidden-notification user hitting that state would otherwise get a no-op cancel()
            // on the still-foreground notification instead of an update, leaving stale content.
            if (KeepADBService.shouldRun(context)) {
                Notification notification = buildNotification(context, host, port);
                manager.notify(NOTIFICATION_ID, notification);
                return;
            }
            manager.cancel(NOTIFICATION_ID);
            return;
        }
        Notification notification = buildNotification(context, host, port);
        manager.notify(NOTIFICATION_ID, notification);
    }

    private static void showPlaceholder(Context context, NotificationManager manager, String title, String text) {
        if (!hasNotificationPermission(context)) {
            return;
        }
        if (KeepADBPreferences.isNotificationHidden(context)) {
            // #445: see the matching comment in show() -- shouldRun() is the correct gate here
            // too, for the same reason.
            if (KeepADBService.shouldRun(context)) {
                Notification notification = buildPlaceholderNotification(context, title, text);
                manager.notify(NOTIFICATION_ID, notification);
                return;
            }
            manager.cancel(NOTIFICATION_ID);
            return;
        }
        Notification notification = buildPlaceholderNotification(context, title, text);
        manager.notify(NOTIFICATION_ID, notification);
    }

    private static Notification buildNotification(Context context, String host, int port) {
        String title = context.getString(R.string.notification_title_active, context.getString(R.string.app_name));
        // #597: the port/IP endpoint string is opt-in, gated behind the same
        // notification_details_enabled preference #592 introduced for the USB and trust-prompt
        // notifications. Off (the default) shows only the status; on reproduces the previous,
        // always-shown "Port <port> @ <ip>" text.
        CharSequence content = KeepADBPreferences.isNotificationDetailsEnabled(context)
                ? styledEndpointText(context, host, port)
                : context.getString(R.string.notification_text_active_hidden);
        Intent intent = new Intent(context, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_keepadb)
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(new Notification.BigTextStyle().bigText(content))
                .setContentIntent(pendingIntent)
                .addAction(disableAction(context))
                .setOngoing(true)
                .setShowWhen(false)
                // #597: publicVersion never carries the endpoint, regardless of the details
                // setting -- same rule #589/#592 already apply to the USB and trust-prompt
                // notifications. Android only shows it while sensitive lock-screen content is
                // redacted; with it allowed, the private content above (gated by the check just
                // above) is shown instead.
                .setPublicVersion(publicVersion(context, title))
                .build();
    }

    private static CharSequence styledEndpointText(Context context, String host, int port) {
        String displayHost = KeepADBPreferences.maskHostForDisplay(context, host);
        if (displayHost != null && displayHost.contains(":") && !displayHost.startsWith("[")) {
            displayHost = "[" + displayHost + "]";
        }
        String content = context.getString(R.string.notification_text_active, port, displayHost);
        SpannableString styled = new SpannableString(content);
        int portStart = content.indexOf(String.valueOf(port));
        if (portStart >= 0) {
            styled.setSpan(new StyleSpan(Typeface.BOLD), portStart, portStart + String.valueOf(port).length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return styled;
    }

    /**
     * #597: the lock-screen copy Android shows while it redacts sensitive notification content
     * there (with sensitive content allowed, the private notification is shown instead). Reuses {@code notification_text_active_hidden} for the text --
     * the same neutral fallback {@link #buildNotification} itself shows as contentText whenever
     * details are off -- so the port/IP endpoint can never reach the lock screen through
     * publicVersion, even while the opt-in is on and the private card carries it. No content
     * intent and no actions: a glance at a locked screen must not be able to trigger anything.
     */
    private static Notification publicVersion(Context context, String title) {
        return new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_keepadb)
                .setContentTitle(title)
                .setContentText(context.getString(R.string.notification_text_active_hidden))
                .setCategory(Notification.CATEGORY_STATUS)
                .build();
    }

    private static Notification buildPlaceholderNotification(Context context, String title, String text) {
        Intent intent = new Intent(context, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_keepadb)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setShowWhen(false);
        // #582: an unreadable value omits the disable action, same as a confirmed "off" would --
        // never offer to disable a state that isn't positively known to be "on".
        Boolean adbEnabledOrNull = KeepADB.isEnabledOrNull(context, "notification");
        if (adbEnabledOrNull != null && adbEnabledOrNull
                && !context.getString(R.string.notification_permission_missing_title, context.getString(R.string.app_name)).equals(title)) {
            builder.addAction(disableAction(context));
        }
        return builder.build();
    }

    static Notification.Action disableAction(Context context) {
        Intent intent = new Intent(context, KeepADBReceiver.class)
                .setAction(KeepADBReceiver.ACTION_DISABLE);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder(
                null,
                context.getString(R.string.notification_action_disable),
                pendingIntent).build();
    }
}
