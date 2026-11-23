package de.hohnepeople.keepadb;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * Reports the current ADB endpoint to the webhook on a user-selected schedule, on top of the
 * report that already follows every network change (#867).
 *
 * <p>Based on work by inaciopestana ({@code inaciopestana/KeepADB@bd9983bc}) (#976).
 *
 * <p>A scheduled report is opt-in (off by default; when enabled, defaults to {@link Interval#DEFAULT})
 * and helps destinations that only keep messages for a limited time (for example a public ntfy
 * topic): without it, a stable network would leave the last message to expire. Hour-based options
 * subtract one minute (59 m, 3 h 59 m, 11 h 59 m, 23 h 59 m) so refreshes arrive slightly ahead of
 * external hourly expiry windows. The report uses the existing {@link KeepADBRegisterClient#reportNow}
 * path: it re-sends the verified Wi-Fi endpoint even when it is unchanged, joins a POST that is
 * already running, and does nothing while synchronization is off or Wi-Fi is down.
 *
 * <p>The timer is an inexact {@link AlarmManager} alarm (no extra permission), so it also fires
 * while the device sleeps. It is a chain of one-shot alarms: the receiver arms the next one each
 * time one fires, and the service, settings form, or webhook-sync lifecycle arms or cancels it
 * immediately so devices without Periodic Refresh, Webhook Sync, or Keep-Alive never pay for
 * unused wakeup alarms.
 */
final class KeepADBPeriodicReport {
    /** Fixed interval presets offered by the settings switch (#976). */
    enum Interval {
        MIN_15("15m", 15L * 60_000L),
        MIN_30("30m", 30L * 60_000L),
        HOUR_1("1h", 59L * 60_000L),
        HOURS_4("4h", (4L * 60L - 1L) * 60_000L),
        HOURS_12("12h", (12L * 60L - 1L) * 60_000L),
        HOURS_24("24h", (24L * 60L - 1L) * 60_000L);

        static final Interval DEFAULT = HOURS_4;

        final String id;
        final long durationMs;

        Interval(String id, long durationMs) {
            this.id = id;
            this.durationMs = durationMs;
        }

        static Interval fromId(String id) {
            if (id != null) {
                for (Interval candidate : values()) {
                    if (candidate.id.equals(id)) return candidate;
                }
            }
            return DEFAULT;
        }
    }

    static final int REQUEST_CODE_PERIODIC_REPORT = 60;

    private KeepADBPeriodicReport() {}

    /** True only when Keep-Alive, Webhook Sync, valid URL, and Periodic Refresh are all active. */
    static boolean isEligible(Context context) {
        if (context == null) return false;
        Context app = context.getApplicationContext();
        return KeepADBService.shouldRun(app)
                && KeepADBPreferences.isRegisterWebhookEnabled(app)
                && KeepADBPreferences.isPeriodicWebhookReportEnabled(app)
                && WebhookUrlCodec.isValid(
                        KeepADBPreferences.getRegisterWebhookUrl(app));
    }

    /** Configured delay between scheduled reports in milliseconds. */
    static long intervalMs(Context context) {
        if (context == null) return Interval.DEFAULT.durationMs;
        return KeepADBPreferences.getPeriodicWebhookInterval(
                context.getApplicationContext()).durationMs;
    }

    /** Ensures the alarm is armed iff {@link #isEligible(Context)} holds. */
    static void sync(Context context) {
        if (context == null) return;
        if (isEligible(context)) {
            ensureArmed(context);
        } else {
            cancel(context);
        }
    }

    /** Replaces any pending alarm so an interval change takes effect immediately when eligible. */
    static void reschedule(Context context) {
        if (context == null) return;
        cancel(context);
        sync(context);
    }

    /**
     * Arms the next alarm unless one is already pending, so a service restart does not push the
     * due time back. If no longer eligible, any pending alarm is cancelled instead.
     */
    static void ensureArmed(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        if (!isEligible(app)) {
            cancel(app);
            return;
        }
        if (pendingIntent(app, PendingIntent.FLAG_NO_CREATE) != null) return;
        arm(app);
    }

    /** Arms the alarm {@link #intervalMs(Context)} from now when eligible, replacing any pending one. */
    static void arm(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        if (!isEligible(app)) {
            cancel(app);
            return;
        }
        AlarmManager alarms = app.getSystemService(AlarmManager.class);
        PendingIntent intent = pendingIntent(app, PendingIntent.FLAG_UPDATE_CURRENT);
        if (alarms == null || intent == null) return;
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + intervalMs(app), intent);
    }

    /** Ends the chain: cancels the pending alarm and its PendingIntent. */
    static void cancel(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        PendingIntent intent = pendingIntent(app, PendingIntent.FLAG_NO_CREATE);
        if (intent == null) return;
        AlarmManager alarms = app.getSystemService(AlarmManager.class);
        if (alarms != null) alarms.cancel(intent);
        intent.cancel();
    }

    static boolean isArmed(Context context) {
        if (context == null) return false;
        return pendingIntent(context.getApplicationContext(), PendingIntent.FLAG_NO_CREATE) != null;
    }

    private static PendingIntent pendingIntent(Context app, int creationFlag) {
        Intent intent = new Intent(app, KeepADBReceiver.class)
                .setAction(KeepADBReceiver.ACTION_PERIODIC_REPORT);
        return PendingIntent.getBroadcast(app, REQUEST_CODE_PERIODIC_REPORT, intent,
                creationFlag | PendingIntent.FLAG_IMMUTABLE);
    }
}
