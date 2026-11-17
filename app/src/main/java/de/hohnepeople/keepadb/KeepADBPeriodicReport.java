package de.hohnepeople.keepadb;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * Reports the current ADB endpoint to the webhook every {@link #INTERVAL_MS}, on top of the
 * report that already follows every network change (#867).
 *
 * <p>Based on work by inaciopestana ({@code inaciopestana/KeepADB@bd9983bc}) (#976).
 *
 * <p>A scheduled report helps destinations that only keep messages for a limited time (for
 * example a public ntfy topic): without it, a stable network would leave the last message to
 * expire. The report uses the existing {@link KeepADBRegisterClient#reportNow} path: it re-sends
 * the verified Wi-Fi endpoint even when it is unchanged, joins a POST that is already running, and
 * does nothing while synchronization is off or Wi-Fi is down.
 *
 * <p>The timer is an inexact {@link AlarmManager} alarm (no extra permission), so it also fires
 * while the device sleeps. It is a chain of one-shot alarms: the receiver arms the next one each
 * time one fires, and the service or webhook-sync lifecycle arms or cancels it immediately so
 * devices without Webhook Sync or without Keep-Alive never pay for unused wakeup alarms.
 */
final class KeepADBPeriodicReport {
    /** Time between two scheduled reports. */
    static final long INTERVAL_MS = 4L * 60L * 60L * 1000L;

    static final int REQUEST_CODE_PERIODIC_REPORT = 60;

    private KeepADBPeriodicReport() {}

    /** True only when both Keep-Alive monitoring and a valid Webhook Sync configuration are active. */
    static boolean isEligible(Context context) {
        if (context == null) return false;
        Context app = context.getApplicationContext();
        return KeepADBService.shouldRun(app)
                && KeepADBPreferences.isRegisterWebhookEnabled(app)
                && KeepADBPreferences.isValidWebhookUrl(
                        KeepADBPreferences.getRegisterWebhookUrl(app));
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

    /** Arms the alarm {@link #INTERVAL_MS} from now when eligible, replacing any pending one. */
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
                SystemClock.elapsedRealtime() + INTERVAL_MS, intent);
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
