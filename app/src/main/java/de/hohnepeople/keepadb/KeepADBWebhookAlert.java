package de.hohnepeople.keepadb;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * #868: warns about a continuous period without a successful webhook report. The first failed
 * report opens an episode; the first warning follows one minute later, one reminder after an hour,
 * and then nothing until a successful report ends the episode. Both use the same notification, so
 * the user never collects a stream of them.
 *
 * <p>The state follows report outcomes and the stored time of the last success only -- no network
 * probe, extra request or polling. The next due check is one inexact
 * {@link AlarmManager#setAndAllowWhileIdle} alarm (#870): unlike a main-thread timer on uptime, it
 * also runs after deep sleep or Doze and after a process death, and it only re-reads the persisted
 * episode. Being inexact, it may arrive a little late; the text names no exact time. The text says
 * what was observed and never claims that the network or the endpoint is down.
 *
 * <p>An alert counts against the episode only once it was actually posted (#870): without the
 * notification permission nothing is counted, and the next evaluation -- a report outcome or the
 * app coming to the foreground -- shows the alert that is due by then.
 *
 * <p>All entry points post to the main thread: they are called from the register executor while
 * {@link KeepADBRegisterClient}'s monitor is held.
 */
final class KeepADBWebhookAlert {
    static final String CHANNEL_ID = "keepadb_webhook";
    static final int NOTIFICATION_ID = 5;
    static final long FIRST_ALERT_AFTER_MS = 60_000L;
    static final long REMINDER_AFTER_MS = 60L * 60L * 1000L;
    static final int MAX_ALERTS_PER_EPISODE = 2;
    private static final int REQUEST_CODE_CHECK_ALARM = 5;

    private static Handler mainHandler;
    private static volatile Long nowForTesting;

    private KeepADBWebhookAlert() {}

    private static synchronized Handler main() {
        if (mainHandler == null) {
            mainHandler = new Handler(Looper.getMainLooper());
        }
        return mainHandler;
    }

    static void onReportFailed(Context context) {
        final Context app = context.getApplicationContext();
        main().post(() -> {
            if (KeepADBPreferences.getWebhookFailureSince(app) <= 0L) {
                KeepADBPreferences.setWebhookFailureEpisode(app, now(), 0);
            }
            evaluate(app);
        });
    }

    /** A successful report, deregistration, disabled sync or a lost endpoint ends the episode. */
    static void endEpisode(Context context) {
        final Context app = context.getApplicationContext();
        main().post(() -> endEpisodeNow(app));
    }

    /**
     * #870: the app came to the foreground. Shows an alert that is due but could not be posted
     * before, for example because the notification permission has been granted since.
     */
    static void onAppResumed(Context context) {
        final Context app = context.getApplicationContext();
        main().post(() -> evaluate(app));
    }

    private static void endEpisodeNow(Context app) {
        cancelCheck(app);
        if (KeepADBPreferences.getWebhookFailureSince(app) > 0L) {
            KeepADBPreferences.setWebhookFailureEpisode(app, 0L, 0);
        }
        NotificationManager manager = app.getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(NOTIFICATION_ID);
    }

    /** Shows the alert that is due, if any, and arms the alarm for the next one. */
    static void evaluate(Context app) {
        cancelCheck(app);
        if (!KeepADBPreferences.isRegisterWebhookEnabled(app)) {
            endEpisodeNow(app);
            return;
        }
        long since = KeepADBPreferences.getWebhookFailureSince(app);
        if (since <= 0L) return;
        long elapsed = now() - since;
        if (elapsed < 0L) {
            // Wall clock moved back: restart the episode from now instead of never alerting.
            since = now();
            elapsed = 0L;
            KeepADBPreferences.setWebhookFailureEpisode(app, since,
                    KeepADBPreferences.getWebhookAlertsShown(app));
        }
        int shown = KeepADBPreferences.getWebhookAlertsShown(app);
        int due = elapsed >= REMINDER_AFTER_MS ? 2 : elapsed >= FIRST_ALERT_AFTER_MS ? 1 : 0;
        if (due > shown && shown < MAX_ALERTS_PER_EPISODE && show(app)) {
            shown = due;
            KeepADBPreferences.setWebhookFailureEpisode(app, since, shown);
        }
        // The next alert level after both what was shown and what is already due: an alert that
        // could not be posted is retried by the next evaluation, never by an immediate timer.
        int next = Math.max(shown, due) + 1;
        if (next <= MAX_ALERTS_PER_EPISODE) {
            long nextAt = next == 1 ? FIRST_ALERT_AFTER_MS : REMINDER_AFTER_MS;
            scheduleCheck(app, nextAt - elapsed);
        }
    }

    private static void scheduleCheck(Context app, long delayMs) {
        AlarmManager alarms = app.getSystemService(AlarmManager.class);
        if (alarms == null) return;
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + Math.max(0L, delayMs), checkIntent(app));
    }

    private static void cancelCheck(Context app) {
        AlarmManager alarms = app.getSystemService(AlarmManager.class);
        if (alarms != null) alarms.cancel(checkIntent(app));
    }

    private static PendingIntent checkIntent(Context app) {
        Intent intent = new Intent(app, KeepADBReceiver.class)
                .setAction(KeepADBReceiver.ACTION_WEBHOOK_ALERT_CHECK);
        return PendingIntent.getBroadcast(app, REQUEST_CODE_CHECK_ALARM, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * The warning text. With the freshness warning on it names the last successful report (or
     * that none was seen); off, it only states that reports failed. Either way it ends with the
     * scope note, so it never reads as a verdict on the network or the server.
     */
    static String text(Context context) {
        String first;
        if (KeepADBPreferences.isWebhookFreshnessWarningEnabled(context)) {
            long successAt = KeepADBPreferences.getWebhookLastSuccessAt(context);
            first = successAt > 0L
                    ? context.getString(R.string.webhook_alert_text_since, formatTime(context, successAt))
                    : context.getString(R.string.webhook_alert_text_never);
        } else {
            first = context.getString(R.string.webhook_alert_text_failed);
        }
        return first + "\n" + context.getString(R.string.webhook_alert_text_scope);
    }

    private static String formatTime(Context context, long epochMillis) {
        java.text.DateFormat format = java.text.DateFormat.getDateTimeInstance(
                java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT,
                context.getResources().getConfiguration().getLocales().get(0));
        return format.format(new java.util.Date(epochMillis));
    }

    /** @return true if the alert was posted; false without a manager or notification permission. */
    private static boolean show(Context app) {
        Context localized = KeepADBLocaleHelper.wrapContext(app);
        NotificationManager manager = localized.getSystemService(NotificationManager.class);
        if (manager == null || !hasNotificationPermission(localized)) return false;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                localized.getString(R.string.webhook_alert_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(localized.getString(R.string.webhook_alert_channel_desc));
        manager.createNotificationChannel(channel);

        String title = localized.getString(R.string.webhook_alert_title);
        String text = text(localized);
        PendingIntent open = PendingIntent.getActivity(localized, NOTIFICATION_ID,
                new Intent(localized, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(localized, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_keepadb)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                // The scope note and the time carry no endpoint or URL; the public version still
                // keeps the lock screen to the title.
                .setPublicVersion(new Notification.Builder(localized, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_keepadb)
                        .setContentTitle(title)
                        .build())
                .build();
        manager.notify(NOTIFICATION_ID, notification);
        return true;
    }

    private static boolean hasNotificationPermission(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static long now() {
        Long testNow = nowForTesting;
        return testNow != null ? testNow : System.currentTimeMillis();
    }

    static void setNowForTesting(Long now) {
        nowForTesting = now;
    }

    static synchronized void resetForTesting() {
        nowForTesting = null;
        mainHandler = null;
    }
}
