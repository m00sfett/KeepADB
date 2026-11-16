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
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.nsd.NsdManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import de.hohnepeople.keepadb.selfpair.SelfPairSession;

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
 */
public final class KeepADBSelfPairService extends Service {
    /** What the setup assistant shows; held in memory only, gone with the process. */
    enum Phase { IDLE, WAITING, DIALOG_FOUND, WORKING, WRONG_CODE, NO_DIALOG, FAILED, DONE }

    /** A snapshot of the run for the user interface. */
    static final class Status {
        final Phase phase;
        /** After {@link Phase#DONE}: the key's fingerprint as Android lists it; else null. */
        final String fingerprint;
        /** After {@link Phase#DONE}: whether Wireless debugging was switched off again. */
        final boolean switchedOff;

        Status(Phase phase, String fingerprint, boolean switchedOff) {
            this.phase = phase;
            this.fingerprint = fingerprint;
            this.switchedOff = switchedOff;
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
    private static final int NOTIFICATION_ID = 962;
    /** Nobody leaves a pairing dialog open this long; do not hold a foreground service for it. */
    private static final long IDLE_TIMEOUT_MS = 10 * 60 * 1000;

    private static volatile Status status = new Status(Phase.IDLE, null, false);
    private static final CopyOnWriteArraySet<Runnable> OBSERVERS = new CopyOnWriteArraySet<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Runnable idleTimeout = this::cancel;
    private SelfPairSession session;
    private boolean working;

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
        startForeground(NOTIFICATION_ID,
                notification(getString(R.string.selfpair_status_waiting), false),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        if (session != null) {
            // Already running: a second tap on the button changes nothing.
            return;
        }
        session = new SelfPairSession(getSystemService(NsdManager.class), getPackageName(),
                visible -> MAIN.post(() -> onDialogChanged(visible)));
        session.start();
        publish(new Status(Phase.WAITING, null, false));
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

    private void onCode(String code) {
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
                final Status outcome = conclude(app, finished);
                MAIN.post(() -> onFinished(finished, outcome));
            }
        });
    }

    /**
     * Decides by the permission itself and cleans up. Runs on the worker and needs nothing from
     * the service, so it also completes while the service is being destroyed. Returns the DONE
     * status if the permission is there, else null.
     */
    private static Status conclude(Context app, SelfPairSession.Result result) {
        if (app.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        boolean switchedOff = false;
        if (!KeepADBPreferences.isKeepAliveEnabled(app)) {
            // The user switched Wireless debugging on only for the pairing.
            KeepADB.setEnabled(app, false, KeepADB.SOURCE_SELF_PAIRING);
            switchedOff = Settings.Global.getInt(app.getContentResolver(),
                    "adb_wifi_enabled", -1) == 0;
        }
        return new Status(Phase.DONE, result == null ? null : result.fingerprint, switchedOff);
    }

    private void onFinished(SelfPairSession.Result result, Status done) {
        working = false;
        if (done != null) {
            publish(done);
            KeepADBWidget.refreshAll(this);
            KeepADBEndpointCoordinator.refresh(this);
            KeepADBService.sync(this);
            StringBuilder text = new StringBuilder(getString(R.string.selfpair_notification_done));
            if (done.switchedOff) {
                text.append('\n').append(getString(R.string.selfpair_done_off));
            }
            if (done.fingerprint != null) {
                text.append('\n').append(
                        getString(R.string.selfpair_done_removable, done.fingerprint));
            }
            finish(notification(text.toString(), false));
            return;
        }
        if (session == null) {
            // Cancelled or destroyed meanwhile; nothing left to show.
            return;
        }
        SelfPairSession.Failure failure = result == null ? null : result.failure;
        if (failure == SelfPairSession.Failure.WRONG_CODE) {
            show(Phase.WRONG_CODE);
        } else if (failure == SelfPairSession.Failure.NO_PAIRING_DIALOG) {
            show(Phase.NO_DIALOG);
        } else {
            show(Phase.FAILED);
        }
        MAIN.postDelayed(idleTimeout, IDLE_TIMEOUT_MS);
    }

    /** Publishes the phase and puts the matching text into the notification. */
    private void show(Phase phase) {
        publish(new Status(phase, null, false));
        boolean reply = phase == Phase.DIALOG_FOUND || phase == Phase.WRONG_CODE
                || phase == Phase.FAILED;
        getSystemService(NotificationManager.class)
                .notify(NOTIFICATION_ID, notification(getString(text(phase)), reply));
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
        if (withReply) {
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                            ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent reply = PendingIntent.getService(this, 1,
                    new Intent(this, KeepADBSelfPairService.class).setAction(ACTION_CODE), flags);
            builder.addAction(new Notification.Action.Builder(null,
                    getString(R.string.selfpair_enter_code), reply)
                    .addRemoteInput(new RemoteInput.Builder(EXTRA_CODE)
                            .setLabel(getString(R.string.selfpair_code_label)).build())
                    .build());
        }
        return builder.build();
    }

    private void cancel() {
        if (!working && status.isActive()) {
            publish(new Status(Phase.IDLE, null, false));
        }
        finish(null);
    }

    /** Ends the service; {@code last} stays in the shade, or the notification goes with it. */
    private void finish(Notification last) {
        MAIN.removeCallbacks(idleTimeout);
        if (session != null) {
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
        MAIN.removeCallbacks(idleTimeout);
        if (session != null) {
            session.stop();
            session = null;
        }
        // Interrupts waits; a running connection ends at its own deadline, and the worker's
        // finally block still checks the permission and cleans up.
        worker.shutdownNow();
        if (!working && status.isActive()) {
            publish(new Status(Phase.IDLE, null, false));
        }
        super.onDestroy();
    }
}
