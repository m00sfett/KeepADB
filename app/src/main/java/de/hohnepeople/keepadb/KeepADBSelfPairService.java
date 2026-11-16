package de.hohnepeople.keepadb;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.net.nsd.NsdManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import de.hohnepeople.keepadb.selfpair.SelfPairSession;

import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lets KeepADB grant itself the system permission on the device, without a computer (#962). The
 * user opens Android's "Pair device with pairing code" dialog; this service keeps the process
 * alive meanwhile, takes the six-digit code through a notification reply (the dialog must stay
 * open, so the app cannot be in front), and hands it to a {@link SelfPairSession}.
 *
 * <p>The outcome is never taken from the session's word. After every attempt, however it ended
 * and even if the service is being destroyed, the permission itself is checked; if it is there
 * and Keep-Alive is off, Wireless debugging is switched off again, because the user switched it
 * on only for this.
 *
 * <p>Immediate cleanup across an unexpected process termination cannot be guaranteed by the
 * service itself. {@link #reconcile} checks for an uncleaned grant on the next process start
 * within one hour and switches Wireless debugging off if Keep-Alive is disabled.
 */
public final class KeepADBSelfPairService extends Service {
    /** What the setup assistant shows; held in memory only, gone with the process. */
    enum Phase { IDLE, WAITING, DIALOG_FOUND, WORKING, WRONG_CODE, NO_DIALOG, FAILED, DONE }

    /** What was done with Wireless debugging after a successful grant. */
    enum Cleanup { KEPT_ON_FOR_KEEP_ALIVE, SWITCHED_OFF, STILL_ON }

    /** A snapshot of the run for the user interface. */
    static final class Status {
        final Phase phase;
        /** The key's fingerprint once paired; carried across phases, null if not paired. */
        final String fingerprint;
        /** After {@link Phase#DONE}: what happened to Wireless debugging. */
        final Cleanup cleanup;

        Status(Phase phase, String fingerprint, Cleanup cleanup) {
            this.phase = phase;
            this.fingerprint = fingerprint;
            this.cleanup = cleanup;
        }

        boolean isActive() {
            return phase != Phase.IDLE && phase != Phase.DONE;
        }
    }

    private static final String TAG = "KeepADBSelfPair";
    private static final String ACTION_START = "de.hohnepeople.keepadb.selfpair.START";
    private static final String ACTION_CODE = "de.hohnepeople.keepadb.selfpair.CODE";
    private static final String ACTION_CANCEL = "de.hohnepeople.keepadb.selfpair.CANCEL";
    private static final String EXTRA_CODE = "code";
    private static final String CHANNEL_ID = "keepadb_selfpair";
    private static final String PREFS_NAME = "keepadb_selfpair";
    private static final String MARKER_CLEANUP_PENDING_AT = "cleanup_pending_at";
    private static final long RECONCILE_MAX_AGE_MS = 60 * 60 * 1000L;
    private static final int NOTIFICATION_ID = 962;
    /** Nobody leaves a pairing dialog open this long; do not hold a foreground service for it. */
    private static final long IDLE_TIMEOUT_MS = 10 * 60 * 1000;

    private static volatile Status status = new Status(Phase.IDLE, null, Cleanup.KEPT_ON_FOR_KEEP_ALIVE);
    private static final CopyOnWriteArraySet<Runnable> OBSERVERS = new CopyOnWriteArraySet<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Runnable idleTimeout = this::cancel;
    private SelfPairSession session;
    private boolean working;
    private boolean cancelRequested;
    private boolean destroyed;
    private String currentRunToken;
    private PendingIntent replyPendingIntent;

    static Status status() {
        return status;
    }

    /** {@code observer} runs on the main thread after every change; hold it only while visible. */
    static void observe(Runnable observer) {
        OBSERVERS.add(observer);
    }

    static void stopObserving(Runnable observer) {
        OBSERVERS.remove(observer);
    }

    static void start(Context context) {
        context.startForegroundService(
                new Intent(context, KeepADBSelfPairService.class).setAction(ACTION_START));
    }

    static void cancel(Context context) {
        if (status.isActive()) {
            context.startService(
                    new Intent(context, KeepADBSelfPairService.class).setAction(ACTION_CANCEL));
        }
    }

    static void publishForTesting(Status next) {
        publish(next);
    }

    static boolean notificationsUsable(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) {
            return false;
        }
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL_ID);
        if (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE) {
            return false;
        }
        return true;
    }

    static void reconcile(Context context) {
        if (status.isActive()) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (!prefs.contains(MARKER_CLEANUP_PENDING_AT)) {
            return;
        }
        long timestamp = prefs.getLong(MARKER_CLEANUP_PENDING_AT, 0);
        prefs.edit().remove(MARKER_CLEANUP_PENDING_AT).apply();
        long age = System.currentTimeMillis() - timestamp;
        if (timestamp > 0 && age >= 0 && age < RECONCILE_MAX_AGE_MS
                && context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                        == PackageManager.PERMISSION_GRANTED
                && !KeepADBPreferences.isKeepAliveEnabled(context)) {
            KeepADB.setEnabled(context.getApplicationContext(), false, KeepADB.SOURCE_SELF_PAIRING);
        }
    }

    private static void setCleanupMarker(Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(MARKER_CLEANUP_PENDING_AT, System.currentTimeMillis())
                .apply();
    }

    static void clearCleanupMarker(Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(MARKER_CLEANUP_PENDING_AT)
                .apply();
    }

    private static void publish(Status next) {
        status = next;
        for (Runnable observer : OBSERVERS) {
            MAIN.post(observer);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_START.equals(action)) {
            begin();
        } else if (ACTION_CODE.equals(action) && session != null) {
            Uri data = intent.getData();
            if (data == null || currentRunToken == null
                    || !("keepadb-selfpair:" + currentRunToken).equals(data.toString())) {
                Log.w(TAG, "ignoring ACTION_CODE with stale or invalid token: " + data);
                return START_NOT_STICKY;
            }
            Bundle reply = RemoteInput.getResultsFromIntent(intent);
            CharSequence code = reply == null ? null : reply.getCharSequence(EXTRA_CODE);
            onCode(code == null ? "" : code.toString().trim());
        } else {
            cancel();
        }
        return START_NOT_STICKY;
    }

    private void begin() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                getString(R.string.selfpair_channel_name), NotificationManager.IMPORTANCE_HIGH));
        if (session != null) {
            startForeground(NOTIFICATION_ID,
                    notificationForPhase(status.phase, status.fingerprint),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            return;
        }
        cancelRequested = false;
        currentRunToken = UUID.randomUUID().toString();
        startForeground(NOTIFICATION_ID,
                notificationForPhase(Phase.WAITING, null),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        session = new SelfPairSession(getSystemService(NsdManager.class), getPackageName(),
                visible -> MAIN.post(() -> onDialogChanged(visible)));
        session.start();
        publish(new Status(Phase.WAITING, null, Cleanup.KEPT_ON_FOR_KEEP_ALIVE));
        MAIN.postDelayed(idleTimeout, IDLE_TIMEOUT_MS);
    }

    private void onDialogChanged(boolean visible) {
        if (session == null || working) {
            return;
        }
        Phase phase = status.phase;
        if (visible && (phase == Phase.WAITING || phase == Phase.NO_DIALOG)) {
            show(Phase.DIALOG_FOUND);
        } else if (!visible && phase == Phase.DIALOG_FOUND) {
            show(Phase.WAITING);
        }
    }

    void onCode(String code) {
        if (working) {
            return;
        }
        if (!SelfPairSession.isCode(code)) {
            show(Phase.WRONG_CODE);
            return;
        }
        working = true;
        MAIN.removeCallbacks(idleTimeout);
        show(Phase.WORKING);
        final SelfPairSession running = session;
        final Context app = getApplicationContext();
        setCleanupMarker(app);
        worker.execute(() -> {
            SelfPairSession.Result result = null;
            try {
                result = running.run(code);
            } catch (InterruptedException e) {
                // The service is going away; the check below still runs.
            } catch (RuntimeException e) {
                Log.w(TAG, "self-pairing failed unexpectedly", e);
            } finally {
                final SelfPairSession.Result finished = result;
                final String fp = (finished != null && finished.fingerprint != null)
                        ? finished.fingerprint : running.pairedFingerprint();
                Status outcome = null;
                try {
                    outcome = conclude(app, fp);
                } catch (RuntimeException e) {
                    Log.w(TAG, "self-pairing cleanup failed", e);
                } finally {
                    final Status done = outcome;
                    MAIN.post(() -> onFinished(finished, done, fp));
                }
            }
        });
    }

    /**
     * Decides by the permission itself and cleans up. Runs on the worker and needs nothing from
     * the service, so it also completes while the service is being destroyed. Returns the DONE
     * status if the permission is there, else null.
     */
    static Status conclude(Context app, String fingerprint) {
        try {
            if (app.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                    != PackageManager.PERMISSION_GRANTED) {
                return null;
            }
            Cleanup cleanup;
            if (KeepADBPreferences.isKeepAliveEnabled(app)) {
                cleanup = Cleanup.KEPT_ON_FOR_KEEP_ALIVE;
            } else {
                KeepADB.ToggleResult result = KeepADB.setEnabled(app, false, KeepADB.SOURCE_SELF_PAIRING);
                boolean setOk = result != null && result.isSuccess();
                Boolean enabled = KeepADB.isEnabledOrNull(app, KeepADB.SOURCE_SELF_PAIRING);
                cleanup = (setOk && Boolean.FALSE.equals(enabled))
                        ? Cleanup.SWITCHED_OFF : Cleanup.STILL_ON;
            }
            return new Status(Phase.DONE, fingerprint, cleanup);
        } finally {
            clearCleanupMarker(app);
        }
    }

    private void onFinished(SelfPairSession.Result result, Status done, String pairedFingerprint) {
        working = false;
        final Context app = getApplicationContext();
        if (done != null) {
            publish(done);
            KeepADBWidget.refreshAll(app);
            KeepADBEndpointCoordinator.refresh(app);
            KeepADBService.sync(app);
            Notification last = buildCompletionNotification(app, done);
            if (destroyed) {
                if (replyPendingIntent != null) {
                    replyPendingIntent.cancel();
                    replyPendingIntent = null;
                }
                currentRunToken = null;
                if (session != null) {
                    session.cancel();
                    session.stop();
                    session = null;
                }
                NotificationManager nm = app.getSystemService(NotificationManager.class);
                if (nm != null) {
                    nm.notify(NOTIFICATION_ID, last);
                }
            } else {
                finish(last);
            }
            return;
        }

        if (pairedFingerprint != null) {
            MAIN.postDelayed(() -> {
                Status delayedDone = conclude(app, pairedFingerprint);
                if (delayedDone != null) {
                    publish(delayedDone);
                    KeepADBWidget.refreshAll(app);
                    KeepADBEndpointCoordinator.refresh(app);
                    KeepADBService.sync(app);
                    NotificationManager nm = app.getSystemService(NotificationManager.class);
                    if (nm != null) {
                        nm.notify(NOTIFICATION_ID, buildCompletionNotification(app, delayedDone));
                    }
                }
            }, 3_000L);
        }

        if (cancelRequested || destroyed || session == null) {
            publish(new Status(Phase.IDLE, pairedFingerprint, Cleanup.KEPT_ON_FOR_KEEP_ALIVE));
            if (!destroyed) {
                finish(null);
            } else {
                if (replyPendingIntent != null) {
                    replyPendingIntent.cancel();
                    replyPendingIntent = null;
                }
                currentRunToken = null;
                if (session != null) {
                    session.cancel();
                    session.stop();
                    session = null;
                }
            }
            return;
        }

        SelfPairSession.Failure failure = result == null ? null : result.failure;
        if (failure == SelfPairSession.Failure.WRONG_CODE) {
            show(Phase.WRONG_CODE, pairedFingerprint);
        } else if (failure == SelfPairSession.Failure.NO_PAIRING_DIALOG) {
            show(Phase.NO_DIALOG, pairedFingerprint);
        } else {
            show(Phase.FAILED, pairedFingerprint);
        }
        MAIN.postDelayed(idleTimeout, IDLE_TIMEOUT_MS);
    }

    private void show(Phase phase) {
        show(phase, status.fingerprint);
    }

    /** Publishes the phase and puts the matching text into the notification. */
    private void show(Phase phase, String fingerprint) {
        publish(new Status(phase, fingerprint, Cleanup.KEPT_ON_FOR_KEEP_ALIVE));
        getSystemService(NotificationManager.class)
                .notify(NOTIFICATION_ID, notificationForPhase(phase, fingerprint));
    }

    static int text(Phase phase) {
        switch (phase) {
            case DIALOG_FOUND:
                return R.string.selfpair_status_found;
            case WORKING:
                return R.string.selfpair_status_working;
            case WRONG_CODE:
                return R.string.selfpair_status_wrong_code;
            case NO_DIALOG:
                return R.string.selfpair_status_no_dialog;
            case FAILED:
                return R.string.selfpair_status_failed;
            case DONE:
                return R.string.selfpair_notification_done;
            default:
                return R.string.selfpair_status_waiting;
        }
    }

    private Notification notificationForPhase(Phase phase, String fingerprint) {
        boolean reply = phase == Phase.DIALOG_FOUND || phase == Phase.WRONG_CODE
                || phase == Phase.FAILED;
        StringBuilder sb = new StringBuilder(getString(text(phase)));
        if (fingerprint != null) {
            sb.append('\n').append(getString(R.string.selfpair_done_removable, fingerprint));
        }
        return notification(sb.toString(), reply);
    }

    private Notification notification(String text, boolean withReply) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                OnboardingActivity.notificationIntent(this, KeepADBOnboarding.Step.PERMISSIONS,
                        OnboardingActionSteps.Permissions.ITEM_SYSTEM),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_keepadb)
                .setContentTitle(getString(R.string.selfpair_notification_title))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open);
        if (withReply && currentRunToken != null) {
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                            ? PendingIntent.FLAG_MUTABLE : 0);
            Intent codeIntent = new Intent(this, KeepADBSelfPairService.class)
                    .setAction(ACTION_CODE)
                    .setData(Uri.parse("keepadb-selfpair:" + currentRunToken));
            replyPendingIntent = PendingIntent.getService(this, 1, codeIntent, flags);
            builder.addAction(new Notification.Action.Builder(null,
                    getString(R.string.selfpair_enter_code), replyPendingIntent)
                    .addRemoteInput(new RemoteInput.Builder(EXTRA_CODE)
                            .setLabel(getString(R.string.selfpair_code_label)).build())
                    .build());
        }
        return builder.build();
    }

    static Notification buildCompletionNotification(Context context, Status done) {
        StringBuilder text = new StringBuilder(context.getString(R.string.selfpair_notification_done));
        if (done.cleanup == Cleanup.SWITCHED_OFF) {
            text.append('\n').append(context.getString(R.string.selfpair_done_off));
        } else if (done.cleanup == Cleanup.STILL_ON) {
            text.append('\n').append(context.getString(R.string.selfpair_done_still_on));
        }
        if (done.fingerprint != null) {
            text.append('\n').append(
                    context.getString(R.string.selfpair_done_removable, done.fingerprint));
        }
        PendingIntent open = PendingIntent.getActivity(context, 0,
                OnboardingActivity.notificationIntent(context, KeepADBOnboarding.Step.PERMISSIONS,
                        OnboardingActionSteps.Permissions.ITEM_SYSTEM),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_keepadb)
                .setContentTitle(context.getString(R.string.selfpair_notification_title))
                .setContentText(text.toString())
                .setStyle(new Notification.BigTextStyle().bigText(text.toString()))
                .setContentIntent(open)
                .build();
    }

    private void cancel() {
        if (working) {
            cancelRequested = true;
            if (session != null) {
                session.cancel();
            }
            return;
        }
        if (status.isActive()) {
            publish(new Status(Phase.IDLE, null, Cleanup.KEPT_ON_FOR_KEEP_ALIVE));
        }
        finish(null);
    }

    /** Ends the service; {@code last} stays in the shade, or the notification goes with it. */
    private void finish(Notification last) {
        MAIN.removeCallbacks(idleTimeout);
        if (replyPendingIntent != null) {
            replyPendingIntent.cancel();
            replyPendingIntent = null;
        }
        currentRunToken = null;
        if (session != null) {
            session.cancel();
            session.stop();
            session = null;
        }
        if (last == null) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(STOP_FOREGROUND_DETACH);
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, last);
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        MAIN.removeCallbacks(idleTimeout);
        if (session != null) {
            session.cancel();
            session.stop();
            session = null;
        }
        if (replyPendingIntent != null) {
            replyPendingIntent.cancel();
            replyPendingIntent = null;
        }
        currentRunToken = null;
        // Interrupts waits; a running connection ends at its own deadline, and the worker's
        // finally block still checks the permission and cleans up.
        worker.shutdownNow();
        if (!working && status.isActive()) {
            publish(new Status(Phase.IDLE, null, Cleanup.KEPT_ON_FOR_KEEP_ALIVE));
        }
        super.onDestroy();
    }
}
