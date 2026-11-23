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
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Renders the Wireless Debugging endpoint notification.
 *
 * <p>#594: this class owns no endpoint state. {@link KeepADBEndpointCoordinator} owns the cached
 * endpoint, discovery, verification, retry and cancellation and calls the {@code render*} /
 * {@link #remove} methods below while holding its monitor; this class only turns that decision
 * into a posted (or cancelled) notification.
 */
final class KeepADBNotification {
    /**
     * #1000: the status channel, {@code IMPORTANCE_DEFAULT} without sound or vibration, so the
     * notification sits among the alerting ones near the top instead of in the silent section at
     * the bottom. A new id: Android does not let an app raise the importance of an existing channel.
     */
    static final String CHANNEL_ID = "keepadb_status";
    /** #1000: the red force-mode card; {@code IMPORTANCE_HIGH}, silent, one heads-up at the start. */
    static final String FORCE_CHANNEL_ID = "keepadb_force_active";
    /** #1000: the running timer; {@code IMPORTANCE_DEFAULT}, silent. Also its end notice. */
    static final String TIMER_CHANNEL_ID = "keepadb_timer";
    /** #1000: the former {@code IMPORTANCE_LOW} endpoint channel, deleted once it is unused. */
    static final String OBSOLETE_ENDPOINT_CHANNEL_ID = "keepadb_endpoint";
    static final int NOTIFICATION_ID = 1;
    /** #1000: the timer's own card while the force card holds the service notification. */
    static final int TIMER_NOTIFICATION_ID = 7;
    /**
     * #1000 (Codex review): the force episode ({@link KeepADBWarningState#FORCE_EPISODE}, new per
     * activation, kept by an extension) whose red card has already alerted. Every render sets
     * "only alert once"; the first render of a new episode lifts it so a second activation on the
     * same notification id gets its heads-up again. In memory: a new process alerts once more.
     */
    private static volatile String alertedForceEpisode;
    /** #956: the channel and notification id of the removed USB-ADB notification. */
    private static final String OBSOLETE_USB_CHANNEL_ID = "keepadb_usb";
    private static final int OBSOLETE_USB_NOTIFICATION_ID = 2;
    /** #759: the content tap of the "permission missing" card; its own code, see #603. */
    private static final int REQUEST_CODE_PERMISSION_STEP = 30;
    /** #734: marks the endpoint card, the only notification {@link #refreshIfActive} may redraw. */
    private static final String EXTRA_ENDPOINT_CARD = "de.hohnepeople.keepadb.EXTRA_ENDPOINT_CARD";

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
            // #759 phase 2: the tap leads to the permissions step of the assistant, at the row that
            // shows the command to run, not to the home screen's setup card.
            showPlaceholder(appContext, manager,
                    appContext.getString(R.string.notification_permission_missing_title, appContext.getString(R.string.app_name)),
                    appContext.getString(R.string.notification_permission_missing_text),
                    OnboardingActivity.notificationIntent(appContext,
                            KeepADBOnboarding.Step.PERMISSIONS,
                            OnboardingActionSteps.Permissions.ITEM_SYSTEM),
                    REQUEST_CODE_PERMISSION_STEP);
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
                appContext.getString(R.string.notification_text_searching),
                homeIntent(appContext), 0);
    }

    /** #445: renders "Wireless Debugging off, Keep-Alive waiting for it to come back". */
    static void renderDisabledKeepAliveWaiting(Context appContext) {
        NotificationManager manager = appContext.getSystemService(NotificationManager.class);
        if (manager == null) return;
        showPlaceholder(appContext, manager,
                appContext.getString(R.string.notification_title_disabled, appContext.getString(R.string.app_name)),
                appContext.getString(R.string.notification_text_disabled_keepalive_waiting),
                homeIntent(appContext), 0);
    }

    /**
     * #734: redraws the endpoint card after a webhook result, straight through this renderer with
     * the same notification id -- no service, no Activity. Does nothing unless the endpoint card
     * is currently posted, so it never creates a notification, resurrects one the user or the
     * service removed, or replaces a placeholder; also a no-op without POST_NOTIFICATIONS.
     */
    static void refreshIfActive(Context context) {
        if (context == null) return;
        Context appContext = KeepADBLocaleHelper.wrapContext(context.getApplicationContext());
        NotificationManager manager = appContext.getSystemService(NotificationManager.class);
        if (manager == null || !hasNotificationPermission(appContext)) return;
        synchronized (KeepADBEndpointCoordinator.class) {
            if (!isEndpointCardActive(manager)) return;
            KeepADBEndpointCoordinator.Snapshot snapshot = KeepADBEndpointCoordinator.snapshot();
            if (!snapshot.hasEndpoint()) return;
            KeepADBLockScreen.post(appContext, manager, NOTIFICATION_ID,
                    buildNotification(appContext, snapshot.host, snapshot.port));
        }
    }

    private static boolean isEndpointCardActive(NotificationManager manager) {
        try {
            for (StatusBarNotification active : manager.getActiveNotifications()) {
                if (active.getId() == NOTIFICATION_ID && active.getNotification() != null
                        && active.getNotification().extras != null
                        && active.getNotification().extras.getBoolean(EXTRA_ENDPOINT_CARD, false)) {
                    return true;
                }
            }
        } catch (RuntimeException ignored) {
        }
        return false;
    }

    /** Removes the endpoint notification. */
    static void remove(Context appContext) {
        NotificationManager manager = appContext.getSystemService(NotificationManager.class);
        if (manager == null) return;
        manager.cancel(NOTIFICATION_ID);
        manager.cancel(TIMER_NOTIFICATION_ID);
    }

    /**
     * #956: cancels a USB-ADB notification that is still showing from before the update and deletes
     * its channel, which would otherwise stay in the system's notification settings of the app.
     * Idempotent: once the channel is gone nothing is left to do, and nothing else is touched.
     */
    static void removeObsoleteUsbNotification(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || manager.getNotificationChannel(OBSOLETE_USB_CHANNEL_ID) == null) {
            return;
        }
        try {
            for (StatusBarNotification active : manager.getActiveNotifications()) {
                if (active.getId() == OBSOLETE_USB_NOTIFICATION_ID
                        && OBSOLETE_USB_CHANNEL_ID.equals(active.getNotification().getChannelId())) {
                    manager.cancel(OBSOLETE_USB_NOTIFICATION_ID);
                }
            }
        } catch (RuntimeException ignored) {
            // The channel deletion below removes its notifications as well.
        }
        manager.deleteNotificationChannel(OBSOLETE_USB_CHANNEL_ID);
    }

    private static void ensureChannel(Context context, NotificationManager manager) {
        manager.createNotificationChannel(silentChannel(CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                context.getString(R.string.notification_channel_desc),
                NotificationManager.IMPORTANCE_DEFAULT));
        manager.createNotificationChannel(silentChannel(FORCE_CHANNEL_ID,
                context.getString(R.string.notification_channel_force_name),
                context.getString(R.string.notification_channel_force_desc),
                NotificationManager.IMPORTANCE_HIGH));
        manager.createNotificationChannel(silentChannel(TIMER_CHANNEL_ID,
                context.getString(R.string.notification_channel_timer_name),
                context.getString(R.string.notification_channel_timer_desc),
                NotificationManager.IMPORTANCE_DEFAULT));
        removeObsoleteEndpointChannel(manager);
    }

    private static NotificationChannel silentChannel(String id, String name, String description,
                                                     int importance) {
        NotificationChannel channel = new NotificationChannel(id, name, importance);
        channel.setDescription(description);
        channel.setSound(null, null);
        channel.enableVibration(false);
        return channel;
    }

    /**
     * #1000: deletes the former endpoint channel. Android refuses to delete a channel that still
     * carries the foreground service's notification; the next render moves the notification to
     * the new channel, and the deletion succeeds on the render after that. User changes to the old
     * channel do not carry over (documented in the changelog).
     */
    static void removeObsoleteEndpointChannel(NotificationManager manager) {
        if (manager.getNotificationChannel(OBSOLETE_ENDPOINT_CHANNEL_ID) == null) return;
        try {
            manager.deleteNotificationChannel(OBSOLETE_ENDPOINT_CHANNEL_ID);
        } catch (RuntimeException stillInUse) {
            // The foreground service notification still sits on it; retried at the next render.
        }
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
        if (KeepADBPreferences.isNotificationHidden(context) && !KeepADBForceMode.isActive(context)
                && !KeepADBTimer.isActive(context)) {
            // #763: a running force mode keeps its warning visible, so the hide preference does
            // not apply while it is on.
            // #445: gate on shouldRun(), not isEnabled() -- the foreground service (and thus
            // Android's requirement for a notification) can still be running with Wireless
            // Debugging off (Keep-Alive waiting for it to come back). The old isEnabled()-only
            // check assumed the two always agreed, which is exactly what #445 disproves; a
            // hidden-notification user hitting that state would otherwise get a no-op cancel()
            // on the still-foreground notification instead of an update, leaving stale content.
            if (KeepADBService.shouldRun(context)) {
                Notification notification = buildNotification(context, host, port);
                KeepADBLockScreen.post(context, manager, NOTIFICATION_ID, notification);
                return;
            }
            manager.cancel(NOTIFICATION_ID);
            return;
        }
        Notification notification = buildNotification(context, host, port);
        KeepADBLockScreen.post(context, manager, NOTIFICATION_ID, notification);
        syncTimerCompanion(context, manager);
    }

    private static void showPlaceholder(Context context, NotificationManager manager, String title,
                                        String text, Intent target, int requestCode) {
        if (!hasNotificationPermission(context)) {
            return;
        }
        if (KeepADBPreferences.isNotificationHidden(context) && !KeepADBForceMode.isActive(context)
                && !KeepADBTimer.isActive(context)) {
            // #445: see the matching comment in show() -- shouldRun() is the correct gate here
            // too, for the same reason. #763: and so is the force mode exception.
            if (KeepADBService.shouldRun(context)) {
                Notification notification = buildPlaceholderNotification(context, title, text,
                        target, requestCode);
                KeepADBLockScreen.post(context, manager, NOTIFICATION_ID, notification);
                return;
            }
            manager.cancel(NOTIFICATION_ID);
            return;
        }
        Notification notification = buildPlaceholderNotification(context, title, text, target,
                requestCode);
        KeepADBLockScreen.post(context, manager, NOTIFICATION_ID, notification);
        syncTimerCompanion(context, manager);
    }

    private static Notification buildNotification(Context context, String host, int port) {
        String title = context.getString(R.string.notification_title_active, context.getString(R.string.app_name));
        // #597/#904: the port/IP endpoint string follows the notification privacy switch, the same
        // one the trust-prompt notification uses. While it is on, only the status is
        // shown; while it is off (the default) the text reads "Port <port> @ <ip>".
        CharSequence content = KeepADBPreferences.isNotificationPrivacyEnabled(context)
                ? context.getString(R.string.notification_text_active_hidden)
                : styledEndpointText(context, host, port);
        Intent intent = new Intent(context, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Bundle marker = new Bundle();
        marker.putBoolean(EXTRA_ENDPOINT_CARD, true);
        // #1000: the mode decides channel, colour, title and actions (see decorate()). Private
        // content only: publicVersion below stays neutral (F4). #1000: no "Copy" action any more;
        // the app copies the endpoint.
        Notification.Builder builder = new Notification.Builder(context, channelFor(context))
                .setSmallIcon(R.drawable.ic_keepadb)
                .setContentTitle(title)
                .setContentText(content)
                .setContentIntent(pendingIntent);
        if (!decorate(context, builder, expandedText(context, content), true)) {
            builder.setStyle(new Notification.BigTextStyle().bigText(expandedText(context, content)))
                    .addAction(disableAction(context)).addAction(keepAliveAction(context));
        }
        return builder
                .addExtras(marker)
                .setOngoing(true)
                .setShowWhen(false)
                // #597: publicVersion never carries the endpoint, regardless of the privacy
                // setting -- same rule #589/#592 already apply to the trust-prompt
                // notification. Android only shows it while sensitive lock-screen content is
                // redacted; with it allowed, the private content above (gated by the check just
                // above) is shown instead.
                .setPublicVersion(publicVersion(context, title))
                .build();
    }

    /** The channel of the service notification in the current mode (#1000). */
    static String channelFor(Context context) {
        if (KeepADBForceMode.isActive(context)) return FORCE_CHANNEL_ID;
        if (KeepADBTimer.isActive(context)) return TIMER_CHANNEL_ID;
        return CHANNEL_ID;
    }

    /**
     * #1000: turns the service notification into the force card or the timer card while one of
     * them runs. Reads only the pure states (this runs under the coordinator's monitor).
     *
     * <ul>
     *   <li><b>Force mode</b> (takes precedence): coloured red, "Force mode on – protection off",
     *       the warning first in the long text, a countdown to its end, and "Off now", "End Force
     *       mode" and "+1 h" (limited and below the cap; last, so One UI cuts it off first). A timer that runs as well gets its own
     *       card ({@link #syncTimerCompanion}).</li>
     *   <li><b>Timer</b>: neutral, the end time as header line and a countdown, "Off now" and
     *       "+1 h" (below the cap). On the endpoint card the title names the end.</li>
     * </ul>
     *
     * @return false in neither mode: the caller adds its normal style and actions.
     */
    private static boolean decorate(Context context, Notification.Builder builder,
                                    CharSequence body, boolean endpointCard) {
        KeepADBForceMode.Status force = KeepADBForceMode.status(context);
        KeepADBTimer.Status timer = KeepADBTimer.status(context);
        if (force == null && timer == null) return false;
        builder.setOnlyAlertOnce(true);
        if (force != null) {
            String episode = context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE)
                    .getString(KeepADBWarningState.FORCE_EPISODE, "");
            if (!episode.equals(alertedForceEpisode)) {
                alertedForceEpisode = episode;
                builder.setOnlyAlertOnce(false);
            }
            String forceLine = KeepADBForceNotice.activeLine(context);
            builder.setColorized(true)
                    .setColor(context.getColor(R.color.banner_red))
                    .setContentTitle(context.getString(R.string.force_notification_title))
                    .setSubText(forceLine)
                    .setStyle(new Notification.BigTextStyle().bigText(TextUtils.concat(
                            context.getString(R.string.force_notification_text), "\n",
                            forceLine, "\n", body)));
            if (!force.isUnlimited()) countdown(builder, force.endsAtWallMs);
            // One UI cuts off the last of three actions (#593): the convenience comes last.
            builder.addAction(offNowAction(context));
            builder.addAction(KeepADBForceNotice.endAction(context));
            if (force.canExtend()) builder.addAction(extendAction(context, true));
            return true;
        }
        String end = KeepADBForceMode.formatEnd(context, timer.endsAtWallMs);
        if (endpointCard) {
            builder.setContentTitle(context.getString(R.string.timer_notification_title_on, end));
        }
        builder.setSubText(context.getString(R.string.timer_notification_subtext, end))
                .setStyle(new Notification.BigTextStyle().bigText(body));
        countdown(builder, timer.endsAtWallMs);
        builder.addAction(offNowAction(context));
        if (timer.canExtend()) builder.addAction(extendAction(context, false));
        return true;
    }

    private static void countdown(Notification.Builder builder, long endsAtWallMs) {
        builder.setWhen(endsAtWallMs).setShowWhen(true).setUsesChronometer(true)
                .setChronometerCountDown(true);
    }

    /**
     * #1000: the timer's own card while the force card holds the service notification, cancelled
     * otherwise. Not the foreground notification, so it carries its own channel and ongoing flag.
     */
    private static void syncTimerCompanion(Context context, NotificationManager manager) {
        KeepADBTimer.Status timer = KeepADBTimer.status(context);
        if (timer == null || !KeepADBForceMode.isActive(context)) {
            manager.cancel(TIMER_NOTIFICATION_ID);
            return;
        }
        String end = KeepADBForceMode.formatEnd(context, timer.endsAtWallMs);
        Notification.Builder builder = new Notification.Builder(context, TIMER_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_keepadb)
                .setContentTitle(context.getString(R.string.timer_notification_companion_title, end))
                .setContentIntent(PendingIntent.getActivity(context, 0, homeIntent(context),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .addAction(offNowAction(context));
        countdown(builder, timer.endsAtWallMs);
        if (timer.canExtend()) builder.addAction(extendAction(context, false));
        KeepADBLockScreen.post(context, manager, TIMER_NOTIFICATION_ID, builder.build());
    }

    /**
     * #734: the expanded text = the compact line (details-gated, unchanged), the Keep-Alive status
     * and, only while webhook sync is on, the webhook lines. The additions carry no endpoint, host
     * or URL, so they need no extra privacy gate; they live in the private card only, never in
     * {@link #publicVersion}.
     */
    private static CharSequence expandedText(Context context, CharSequence compact) {
        List<CharSequence> lines = new ArrayList<>();
        lines.add(compact);
        // #1000: while a timer holds Wifi-ADB, "Keep-Alive: off" (the stored setting) would read as
        // if nothing kept it alive; the timer's own lines say what holds it.
        if (!KeepADBTimer.isActive(context)) {
            lines.add(context.getString(KeepADBPreferences.isKeepAliveEnabled(context)
                    ? R.string.notification_text_keepalive_on : R.string.notification_text_keepalive_off));
        }
        lines.addAll(webhookLines(context));
        CharSequence result = lines.get(0);
        for (int i = 1; i < lines.size(); i++) {
            result = TextUtils.concat(result, "\n", lines.get(i));
        }
        return result;
    }

    /**
     * #734: webhook status lines from the stored report data. Empty while sync is off or no URL is
     * set. The newest result decides: a success shows its time; a failure is stated as a failure
     * and an older success is only ever named as such ("last successful"), never as the failed
     * attempt's time; without any success none is claimed.
     */
    static List<String> webhookLines(Context context) {
        List<String> lines = new ArrayList<>();
        String url = KeepADBPreferences.getRegisterWebhookUrl(context);
        if (!KeepADBPreferences.isRegisterWebhookEnabled(context) || url == null || url.trim().isEmpty()) {
            return lines;
        }
        String status = WebhookReportPrefs.getWebhookLastReportStatus(context);
        long successAt = WebhookReportPrefs.getWebhookLastSuccessAt(context);
        boolean failed = WebhookReportPrefs.WEBHOOK_STATUS_FAILED.equals(status);
        if (failed) {
            lines.add(context.getString(R.string.notification_text_webhook_failed));
        }
        if (successAt <= 0L) {
            if (!failed) {
                lines.add(context.getString(R.string.notification_text_webhook_none_yet));
            }
        } else if (WebhookReportPrefs.WEBHOOK_STATUS_SUCCESS.equals(status)) {
            lines.add(context.getString(R.string.notification_text_webhook_synced, formatSeconds(context, successAt)));
        } else {
            lines.add(context.getString(R.string.notification_text_webhook_last_success,
                    formatSeconds(context, successAt)));
        }
        return lines;
    }

    private static String formatSeconds(Context context, long epochMillis) {
        java.text.DateFormat format = java.text.DateFormat.getDateTimeInstance(
                java.text.DateFormat.MEDIUM, java.text.DateFormat.MEDIUM,
                context.getResources().getConfiguration().getLocales().get(0));
        return format.format(new Date(epochMillis));
    }

    private static CharSequence styledEndpointText(Context context, String host, int port) {
        // The app privacy switch does not apply here: this notification has its own (#904).
        String content = KeepADBEndpointText.display(context, host, port);
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
        return buildPlaceholderNotification(context, title, text, homeIntent(context), 0);
    }

    /** The content tap of every card except "permission missing": the home screen (#759 table 3.1). */
    private static Intent homeIntent(Context context) {
        return new Intent(context, MainActivity.class);
    }

    private static Notification buildPlaceholderNotification(Context context, String title,
                                                             String text, Intent intent,
                                                             int requestCode) {
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        boolean permissionCard = context.getString(R.string.notification_permission_missing_title,
                context.getString(R.string.app_name)).equals(title);
        // #763/#1000: the same force or timer card as on the endpoint card, so it is there whatever
        // the state.
        Notification.Builder builder = new Notification.Builder(context, channelFor(context))
                .setSmallIcon(R.drawable.ic_keepadb)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setShowWhen(false);
        if (!decorate(context, builder, text, false)) {
            builder.setStyle(new Notification.BigTextStyle().bigText(text));
            // #582: an unreadable value omits the disable action, same as a confirmed "off" would
            // -- never offer to disable a state that isn't positively known to be "on".
            Boolean adbEnabledOrNull = KeepADB.isEnabledOrNull(context, "notification");
            if (adbEnabledOrNull != null && adbEnabledOrNull && !permissionCard) {
                builder.addAction(disableAction(context));
            }
        }
        return builder.build();
    }

    /**
     * #1000: "Off now" on the force and timer cards: the same manual off as "Turn off" (which also
     * ends a running timer and the force mode), labelled for the moment it is offered in.
     */
    static Notification.Action offNowAction(Context context) {
        Intent intent = new Intent(context, KeepADBReceiver.class)
                .setAction(KeepADBReceiver.ACTION_DISABLE);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context,
                KeepADBTimer.REQUEST_CODE_OFF_NOW, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder(null,
                context.getString(R.string.notification_action_off_now), pendingIntent).build();
    }

    /**
     * #1000: "+1 h" of the timer or the force mode. It lengthens the time Wifi-ADB stays on, so it
     * asks for an unlocked device (API 31+; the receiver checks again on every API level).
     */
    static Notification.Action extendAction(Context context, boolean force) {
        Intent intent = new Intent(context, KeepADBReceiver.class).setAction(force
                ? KeepADBReceiver.ACTION_FORCE_EXTEND : KeepADBReceiver.ACTION_TIMER_EXTEND);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context,
                force ? KeepADBForceMode.REQUEST_CODE_EXTEND : KeepADBTimer.REQUEST_CODE_EXTEND, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Action.Builder action = new Notification.Action.Builder(null,
                context.getString(R.string.notification_action_extend), pendingIntent);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) action.setAuthenticationRequired(true);
        return action.build();
    }

    /** #734: toggles Keep-Alive; labelled with what it will do, the status line states what is. */
    static Notification.Action keepAliveAction(Context context) {
        Intent intent = new Intent(context, KeepADBReceiver.class)
                .setAction(KeepADBReceiver.ACTION_TOGGLE_KEEP_ALIVE);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                1,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder(
                null,
                context.getString(KeepADBPreferences.isKeepAliveEnabled(context)
                        ? R.string.notification_action_keepalive_off : R.string.notification_action_keepalive_on),
                pendingIntent).build();
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
