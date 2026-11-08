package de.hohnepeople.keepadb;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;

/**
 * #912: keeps a placed widget in step with Wireless Debugging while no KeepADB screen or service is
 * running. The only observers of {@code adb_wifi_enabled} live in {@link KeepADBService} (Keep-Alive
 * on) and {@link MainActivity} (open), so with both gone a change made elsewhere (Developer options,
 * {@code adb shell settings put}) left the widget showing the old state until the app was opened.
 *
 * <p>The platform watches the setting for this app: {@link JobInfo.Builder#addTriggerContentUri} runs
 * the job when the setting changes, in a process that is started for it, and costs nothing while it
 * does not. The job only re-renders the placed widgets from the read-only projection; it sends
 * nothing, starts no discovery and does not poll. A content-triggered job runs once, so it schedules
 * itself again. {@link KeepADBWidget} schedules it when a widget is placed or the device started
 * (its {@code onUpdate}) and cancels it with the last widget.
 *
 * <p>Limit: a force-stopped app has no scheduled jobs until it is started again (by the user, a
 * widget update or a reboot); until then Android shows the last drawn state.
 */
public class KeepADBWidgetRefreshJob extends JobService {
    static final int JOB_ID = 912;
    /** Bursts of writes (a pulse writes off and on) are coalesced into one render. */
    static final long UPDATE_DELAY_MS = 500L;
    static final long MAX_DELAY_MS = 3000L;

    /** Starts watching the setting; scheduling again replaces the pending job. */
    static void schedule(Context context) {
        Context app = context.getApplicationContext();
        JobScheduler scheduler = app.getSystemService(JobScheduler.class);
        if (scheduler == null) return;
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(app, KeepADBWidgetRefreshJob.class))
                .addTriggerContentUri(new JobInfo.TriggerContentUri(
                        Settings.Global.getUriFor(KeepADB.KEY),
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                .setTriggerContentUpdateDelay(UPDATE_DELAY_MS)
                .setTriggerContentMaxDelay(MAX_DELAY_MS)
                .build();
        try {
            scheduler.schedule(job);
        } catch (RuntimeException ignored) {
            // A refused schedule leaves the widget as it was before this job existed.
        }
    }

    static void cancel(Context context) {
        JobScheduler scheduler = context.getApplicationContext().getSystemService(JobScheduler.class);
        if (scheduler != null) scheduler.cancel(JOB_ID);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        // Read-only: the widgets are drawn from the projection, nothing is sent or discovered.
        KeepADBWidget.refreshAllState(this);
        // The trigger fires once; watch again for the next change.
        schedule(this);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
