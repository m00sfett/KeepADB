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
import android.net.Uri;
import android.net.nsd.NsdManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import de.hohnepeople.keepadb.selfpair.SelfPairSession;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Lets KeepADB grant itself the system permission on the device, without a computer (#962). The
 * user opens Android's "Pair device with pairing code" dialog; this service keeps the process
 * alive meanwhile, takes the six-digit code through a notification reply (the dialog must stay
 * open, so the app cannot be in front), and hands it to a {@link SelfPairSession}.
 *
 * <p>The outcome is never taken from the session's word. After every attempt, however it ended
 * and even if the service is being destroyed, {@link KeepADBSelfPairCleanup#settle} checks the
 * permission itself and, where this run holds the claim, switches Wifi-ADB off again.
 *
 * <p><b>Ownership.</b> One start of the service is a <em>run</em> with a random id. Everything
 * that can arrive late is bound to it: the reply intent carries the id, the cleanup claim names
 * it, the delayed re-check is a callback of this instance that is removed when the run ends, and
 * the shared {@link #status()} is written only by the instance that owns it. An instance that
 * was destroyed while its worker was still busy therefore finishes its own cleanup, but cannot
 * overwrite the state of a newer run or end it.
 *
 * <p>All fields of an instance are confined to the main thread. The worker only runs the session
 * and the cleanup, and posts its result back.
 */
public final class KeepADBSelfPairService extends Service {
    /** What the setup assistant shows; held in memory only, gone with the process. */
    enum Phase { IDLE, WAITING, DIALOG_FOUND, WORKING, WRONG_CODE, NO_DIALOG, FAILED, DONE }

    /** What happened to Wifi-ADB after a successful grant. */
    enum Cleanup {
        /** Left as it is: Keep-Alive is on, or the switch was not this run's to make. */
        LEFT_ON,
        SWITCHED_OFF,
        /** Switching off was tried and could not be confirmed; the user has to do it. */
        STILL_ON
    }

    /** A snapshot of the run for the user interface. */
    static final class Status {
        final Phase phase;
        /** Fingerprint of the key paired last, as Android lists it; null if none was paired. */
        final String fingerprint;
        /**
         * Every key this process has paired, oldest first. Each left an entry "KeepADB" under
         * Android's paired devices, so each is named until the process ends, whatever the phase.
         */
        final List<String> fingerprints;
        /** After {@link Phase#DONE}: what happened to Wifi-ADB. */
        final Cleanup cleanup;

        Status(Phase phase, String fingerprint, Cleanup cleanup) {
            this(phase, cleanup, fingerprint == null
                    ? Collections.<String>emptyList()
                    : Collections.singletonList(fingerprint));
        }

        Status(Phase phase, Cleanup cleanup, List<String> fingerprints) {
            this.phase = phase;
            this.fingerprints = Collections.unmodifiableList(new ArrayList<>(fingerprints));
            this.fingerprint = this.fingerprints.isEmpty()
                    ? null : this.fingerprints.get(this.fingerprints.size() - 1);
            this.cleanup = cleanup;
        }

        boolean isActive() {
            return phase != Phase.IDLE && phase != Phase.DONE;
        }
    }

    /** The part of {@link SelfPairSession} the service uses; a seam for tests. */
    interface Runner {
        void start();

        void stop();

        void cancel();

        SelfPairSession.Result run(String code, SelfPairSession.PairedListener onPaired)
                throws InterruptedException;
    }

    /** Creates the runner of a run; {@code onDialogChanged} may be called on any thread. */
    interface RunnerFactory {
        Runner create(Context context, SelfPairSession.Listener onDialogChanged);
    }

    private static final String TAG = "KeepADBSelfPair";
    private static final String ACTION_START = "de.hohnepeople.keepadb.selfpair.START";
    private static final String ACTION_CODE = "de.hohnepeople.keepadb.selfpair.CODE";
    private static final String ACTION_CANCEL = "de.hohnepeople.keepadb.selfpair.CANCEL";
    private static final String EXTRA_CODE = "code";
    private static final String CHANNEL_ID = "keepadb_selfpair";
    private static final String TOKEN_SCHEME = "keepadb-selfpair:";
    private static final String GROUP = "keepadb_selfpair";
    private static final int NOTIFICATION_ID = 962;
    /** Nobody leaves a pairing dialog open this long; do not hold a foreground service for it. */
    private static final long IDLE_TIMEOUT_MS = 10 * 60 * 1000;
    /** adbd may run the grant after the connection broke; look once more after this pause. */
    static final long LATE_CHECK_MS = 3_000;

    private static final RunnerFactory REAL_RUNNERS = (context, onDialogChanged) -> {
        final SelfPairSession session = new SelfPairSession(
                context.getSystemService(NsdManager.class), context.getPackageName(),
                onDialogChanged);
        return new Runner() {
            @Override
            public void start() {
                session.start();
            }

            @Override
            public void stop() {
                session.stop();
            }

            @Override
            public void cancel() {
                session.cancel();
            }

            @Override
            public SelfPairSession.Result run(String code, SelfPairSession.PairedListener paired)
                    throws InterruptedException {
                return session.run(code, paired);
            }
        };
    };

    private static volatile Status status = new Status(Phase.IDLE, null, Cleanup.LEFT_ON);
    private static final CopyOnWriteArraySet<Runnable> OBSERVERS = new CopyOnWriteArraySet<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Keys paired in this process; guarded by itself. */
    private static final LinkedHashSet<String> PAIRED = new LinkedHashSet<>();
    /** The instance whose run the shared status describes; main thread only. */
    private static KeepADBSelfPairService owner;
    private static RunnerFactory runnerFactory = REAL_RUNNERS;
    private static ExecutorService executorForTesting;

    private final Runnable idleTimeout = this::cancel;
    private final Runnable lateCheck = this::checkForLateGrant;
    private ExecutorService worker;
    private Runner runner;
    private String runId;
    private PendingIntent replyPendingIntent;
    private boolean working;
    private boolean cancelRequested;
    private boolean destroyed;

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

    /** The code is entered in a notification reply: both the app and this channel must show. */
    static boolean notificationsUsable(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) {
            return false;
        }
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL_ID);
        return channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    static void publishForTesting(Status next) {
        publish(next);
    }

    /** Tests: fake sessions and a controlled executor; {@code null} restores the real ones. */
    static void setSeamsForTesting(RunnerFactory runners, ExecutorService executor) {
        runnerFactory = runners == null ? REAL_RUNNERS : runners;
        executorForTesting = executor;
    }

    static void resetForTesting() {
        setSeamsForTesting(null, null);
        owner = null;
        synchronized (PAIRED) {
            PAIRED.clear();
        }
        status = new Status(Phase.IDLE, null, Cleanup.LEFT_ON);
    }

    private static void publish(Status next) {
        status = next;
        for (Runnable observer : OBSERVERS) {
            MAIN.post(observer);
        }
    }

    private static List<String> pairedFingerprints() {
        synchronized (PAIRED) {
            return new ArrayList<>(PAIRED);
        }
    }

    /** Called on the worker the moment a key is registered with the device. */
    private static void rememberPaired(String fingerprint) {
        if (fingerprint == null) return;
        synchronized (PAIRED) {
            // No limit: each needs the user to open a pairing dialog and type a code, and each
            // left an entry that has to stay nameable.
            PAIRED.remove(fingerprint);
            PAIRED.add(fingerprint);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        worker = executorForTesting != null
                ? executorForTesting : Executors.newSingleThreadExecutor();
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
        } else if (ACTION_CODE.equals(action) && runner != null) {
            Uri data = intent.getData();
            if (data == null || runId == null || !(TOKEN_SCHEME + runId).equals(data.toString())) {
                // A reply from a notification of an earlier run; it has no say in this one.
                Log.w(TAG, "ignoring a code that does not belong to this run");
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
        if (runner != null) {
            // Already running: a second tap on the button changes nothing, and the notification
            // keeps what the current phase offers.
            startForeground(NOTIFICATION_ID, notificationForPhase(status.phase),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            return;
        }
        owner = this;
        runId = UUID.randomUUID().toString();
        KeepADBSelfPairCleanup.authorize(runId);
        cancelRequested = false;
        startForeground(NOTIFICATION_ID, notificationForPhase(Phase.WAITING),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        final String run = runId;
        runner = runnerFactory.create(this,
                visible -> MAIN.post(() -> onDialogChanged(run, visible)));
        runner.start();
        publishOwned(Phase.WAITING, Cleanup.LEFT_ON);
        MAIN.postDelayed(idleTimeout, IDLE_TIMEOUT_MS);
    }

    private void onDialogChanged(String run, boolean visible) {
        if (runner == null || working || !run.equals(runId)) {
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
        if (working || runner == null) {
            return;
        }
        if (!SelfPairSession.isCode(code)) {
            show(Phase.WRONG_CODE);
            return;
        }
        working = true;
        MAIN.removeCallbacks(idleTimeout);
        MAIN.removeCallbacks(lateCheck);
        show(Phase.WORKING);
        final Runner running = runner;
        final String run = runId;
        final Context app = getApplicationContext();
        try {
            worker.execute(() -> attempt(app, running, run, code));
        } catch (RejectedExecutionException e) {
            // The executor is shutting down: this attempt never starts, so it ends here.
            onFinished(run, null, KeepADBSelfPairCleanup.Outcome.NOT_GRANTED);
        }
    }

    /**
     * One attempt, on the worker. Touches no field of the service: whatever it needs was handed
     * in, and its result goes back through the main thread under the id of its run.
     */
    private void attempt(Context app, Runner running, String run, String code) {
        SelfPairSession.Result result = null;
        try {
            result = running.run(code, fingerprint -> {
                rememberPaired(fingerprint);
                // From here on the grant may be sent: the cleanup needs an owner on disk first.
                // No owner on disk, or a run that is no longer current: no grant. A user who
                // decided otherwise meanwhile still gets the permission, just no cleanup.
                KeepADBSelfPairCleanup.Claim claim = KeepADBSelfPairCleanup.claim(app, run);
                return claim == KeepADBSelfPairCleanup.Claim.HELD
                        || claim == KeepADBSelfPairCleanup.Claim.SUPERSEDED;
            });
        } catch (InterruptedException e) {
            // The service is going away; the check below still runs.
        } catch (RuntimeException e) {
            Log.w(TAG, "self-pairing failed unexpectedly", e);
        } finally {
            final SelfPairSession.Result finished = result;
            final KeepADBSelfPairCleanup.Outcome outcome =
                    KeepADBSelfPairCleanup.settle(app, run);
            MAIN.post(() -> onFinished(run, finished, outcome));
        }
    }

    /** The terminal transition of an attempt; every accepted attempt reaches it exactly once. */
    private void onFinished(String run, SelfPairSession.Result result,
                            KeepADBSelfPairCleanup.Outcome outcome) {
        if (!run.equals(runId) && !destroyed) {
            // A leftover of an earlier run of this instance; that run was already closed.
            return;
        }
        working = false;
        if (isGranted(outcome)) {
            complete(outcome);
            return;
        }
        if (cancelRequested || destroyed || runner == null) {
            publishOwned(Phase.IDLE, Cleanup.LEFT_ON);
            if (destroyed) {
                release();
                relinquish();
            } else {
                finish(null);
            }
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
        if (result != null && result.fingerprint != null) {
            // Paired, so the command may have been sent; give a late grant one more look.
            MAIN.postDelayed(lateCheck, LATE_CHECK_MS);
        }
    }

    /**
     * Runs only while this run is alive and idle; a new attempt or the end of the run removes
     * it. The check itself reads preferences and may write a setting, so it goes to the worker
     * and comes back under the id of its run.
     */
    private void checkForLateGrant() {
        if (destroyed || working || runner == null) {
            return;
        }
        final String run = runId;
        final Context app = getApplicationContext();
        try {
            worker.execute(() -> {
                final KeepADBSelfPairCleanup.Outcome outcome =
                        KeepADBSelfPairCleanup.settle(app, run);
                MAIN.post(() -> onLateOutcome(run, outcome));
            });
        } catch (RejectedExecutionException e) {
            // The service is going away; the claim stays for the next start.
        }
    }

    private void onLateOutcome(String run, KeepADBSelfPairCleanup.Outcome outcome) {
        if (destroyed || working || runner == null || !run.equals(runId)) {
            // The run ended or moved on meanwhile; whatever was settled stays settled.
            return;
        }
        if (isGranted(outcome)) {
            complete(outcome);
        }
    }

    private boolean isGranted(KeepADBSelfPairCleanup.Outcome outcome) {
        if (outcome == KeepADBSelfPairCleanup.Outcome.UNKNOWN) {
            try {
                return checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                        == PackageManager.PERMISSION_GRANTED;
            } catch (RuntimeException e) {
                return false;
            }
        }
        return outcome != KeepADBSelfPairCleanup.Outcome.NOT_GRANTED;
    }

    /** The permission is there: say so, bring the other surfaces up to date, end the run. */
    private void complete(KeepADBSelfPairCleanup.Outcome outcome) {
        Cleanup cleanup;
        if (outcome == KeepADBSelfPairCleanup.Outcome.SWITCHED_OFF) {
            cleanup = Cleanup.SWITCHED_OFF;
        } else if (outcome == KeepADBSelfPairCleanup.Outcome.LEFT_ON) {
            cleanup = Cleanup.LEFT_ON;
        } else {
            cleanup = Cleanup.STILL_ON;
        }
        final Context app = getApplicationContext();
        final Status done = new Status(Phase.DONE, cleanup, pairedFingerprints());
        if (owner == this) {
            publish(done);
        } else if (owner != null) {
            // A newer run owns the state. The permission it is after has arrived; let it see.
            MAIN.post(owner.lateCheck);
        } else {
            publish(done);
        }
        try {
            KeepADBWidget.refreshAll(app);
            KeepADBEndpointCoordinator.refresh(app);
            KeepADBService.sync(app);
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot refresh after the grant", e);
        }
        if (owner != null && owner != this) {
            release();
            return;
        }
        Notification last = completionNotification(app, done);
        if (destroyed) {
            release();
            relinquish();
            NotificationManager manager = app.getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.notify(NOTIFICATION_ID, last);
            }
        } else {
            finish(last);
        }
    }

    /** A destroyed instance that has reported its last attempt owns nothing any more. */
    private void relinquish() {
        if (owner == this) {
            owner = null;
        }
    }

    /** Writes the shared status, but only for the run it belongs to. */
    private void publishOwned(Phase phase, Cleanup cleanup) {
        if (owner == this) {
            publish(new Status(phase, cleanup, pairedFingerprints()));
        }
    }

    /** Publishes the phase and puts the matching text into the notification. */
    private void show(Phase phase) {
        publishOwned(phase, Cleanup.LEFT_ON);
        if (!destroyed) {
            getSystemService(NotificationManager.class)
                    .notify(NOTIFICATION_ID, notificationForPhase(phase));
        }
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

    private Notification notificationForPhase(Phase phase) {
        boolean reply = phase == Phase.DIALOG_FOUND || phase == Phase.WRONG_CODE
                || phase == Phase.FAILED;
        StringBuilder text = new StringBuilder(getString(text(phase)));
        appendRemovable(this, text, pairedFingerprints());
        Notification.Builder builder = builder(this, text.toString());
        if (reply && runId != null) {
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                            ? PendingIntent.FLAG_MUTABLE : 0);
            Intent codeIntent = new Intent(this, KeepADBSelfPairService.class)
                    .setAction(ACTION_CODE)
                    .setData(Uri.parse(TOKEN_SCHEME + runId));
            replyPendingIntent = PendingIntent.getService(this, 1, codeIntent, flags);
            builder.addAction(new Notification.Action.Builder(null,
                    getString(R.string.selfpair_enter_code), replyPendingIntent)
                    .addRemoteInput(new RemoteInput.Builder(EXTRA_CODE)
                            .setLabel(getString(R.string.selfpair_code_label)).build())
                    .build());
        }
        return builder.build();
    }

    static Notification completionNotification(Context context, Status done) {
        StringBuilder text = new StringBuilder(context.getString(R.string.selfpair_notification_done));
        if (done.cleanup == Cleanup.SWITCHED_OFF) {
            text.append('\n').append(context.getString(R.string.selfpair_done_off));
        } else if (done.cleanup == Cleanup.STILL_ON) {
            text.append('\n').append(context.getString(R.string.selfpair_done_still_on));
        }
        appendRemovable(context, text, done.fingerprints);
        return builder(context, text.toString()).build();
    }

    private static void appendRemovable(Context context, StringBuilder text,
                                        List<String> fingerprints) {
        for (String fingerprint : fingerprints) {
            text.append('\n').append(
                    context.getString(R.string.selfpair_done_removable, fingerprint));
        }
    }

    private static Notification.Builder builder(Context context, String text) {
        PendingIntent open = PendingIntent.getActivity(context, 0,
                OnboardingActivity.notificationIntent(context,
                        KeepADBOnboarding.Step.PERMISSIONS,
                        OnboardingActionSteps.Permissions.ITEM_SYSTEM),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_keepadb)
                .setContentTitle(context.getString(R.string.selfpair_notification_title))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                // A group of its own: left ungrouped, Android bundles it with KeepADB's other
                // notifications and the reply action ends up two taps deep (seen on Android 16).
                .setGroup(GROUP)
                .setContentIntent(open);
    }

    private void cancel() {
        if (working) {
            // The attempt may already have sent the grant. Stop it at its next step, but keep
            // the service in front until it has reported; onFinished ends the run.
            cancelRequested = true;
            if (runner != null) {
                runner.cancel();
            }
            return;
        }
        publishOwned(Phase.IDLE, Cleanup.LEFT_ON);
        finish(null);
    }

    /** Lets go of everything the run holds; the service itself may already be gone. */
    private void release() {
        MAIN.removeCallbacks(idleTimeout);
        MAIN.removeCallbacks(lateCheck);
        if (replyPendingIntent != null) {
            replyPendingIntent.cancel();
            replyPendingIntent = null;
        }
        runId = null;
        if (runner != null) {
            runner.cancel();
            runner.stop();
            runner = null;
        }
    }

    /** Ends the run and the service; {@code last} stays in the shade, or nothing does. */
    private void finish(Notification last) {
        release();
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
        final String run = runId;
        MAIN.removeCallbacks(idleTimeout);
        MAIN.removeCallbacks(lateCheck);
        if (replyPendingIntent != null) {
            replyPendingIntent.cancel();
            replyPendingIntent = null;
        }
        if (runner != null) {
            runner.cancel();
            runner.stop();
        }
        // Interrupts waits; a running connection ends at its own deadline, and the attempt's
        // finally block still settles the cleanup and reports back.
        List<Runnable> neverStarted = worker.shutdownNow();
        if (working && !neverStarted.isEmpty()) {
            // The accepted attempt was still queued and will never run: end it here.
            working = false;
        }
        if (!working) {
            if (status.isActive()) {
                // A finished run keeps its closing state (DONE and what became of Wifi-ADB);
                // only a run that was still open is closed here.
                publishOwned(Phase.IDLE, Cleanup.LEFT_ON);
            }
            runner = null;
            runId = null;
            if (owner == this) {
                owner = null;
            }
        } else {
            // Keep the id: the attempt reports under it, and onFinished closes the run.
            runId = run;
        }
        super.onDestroy();
    }
}
