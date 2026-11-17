package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;

import androidx.test.core.app.ApplicationProvider;

import de.hohnepeople.keepadb.KeepADBSelfPairService.Cleanup;
import de.hohnepeople.keepadb.KeepADBSelfPairService.Phase;
import de.hohnepeople.keepadb.selfpair.SelfPairSession;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * #962: the life of a self-pairing run. The session is replaced by a script and the worker by
 * an executor the test steps by hand, so the interleavings a review found (cancel while
 * working, destruction with a queued or a running attempt, a late result of an old instance,
 * the delayed re-check) are reproduced exactly instead of hoped for.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBSelfPairServiceTest {
    private static final String START = "de.hohnepeople.keepadb.selfpair.START";
    private static final String CODE = "de.hohnepeople.keepadb.selfpair.CODE";
    private static final String CANCEL = "de.hohnepeople.keepadb.selfpair.CANCEL";

    private Context context;
    private KeepADBFakeSettingsGateway gateway;
    private ManualExecutor executor;
    private final List<FakeRunner> runners = new ArrayList<>();
    private final List<ServiceController<KeepADBSelfPairService>> controllers = new ArrayList<>();
    /** What the next scripted attempt does; replaced per test. */
    private Script script = (runner, onPaired) ->
            new SelfPairSession.Result(SelfPairSession.Failure.WRONG_CODE, null);

    private interface Script {
        SelfPairSession.Result run(FakeRunner runner, SelfPairSession.PairedListener onPaired);
    }

    private final class FakeRunner implements KeepADBSelfPairService.Runner {
        final SelfPairSession.Listener dialog;
        boolean cancelled;
        boolean stopped;
        int runs;

        FakeRunner(SelfPairSession.Listener dialog) {
            this.dialog = dialog;
        }

        @Override
        public void start() {}

        @Override
        public void stop() {
            stopped = true;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public SelfPairSession.Result run(String code, SelfPairSession.PairedListener onPaired) {
            runs++;
            return script.run(this, onPaired);
        }
    }

    /** Runs nothing by itself. {@code begin} marks the head task as started, as a thread would. */
    private static final class ManualExecutor extends AbstractExecutorService {
        final ArrayDeque<Runnable> queued = new ArrayDeque<>();
        Runnable started;
        boolean shutdown;

        @Override
        public void execute(Runnable command) {
            if (shutdown) throw new java.util.concurrent.RejectedExecutionException();
            queued.add(command);
        }

        /** The worker thread picked the task up; shutdownNow can no longer take it back. */
        void begin() {
            started = queued.poll();
        }

        void finishStarted() {
            Runnable task = started;
            started = null;
            task.run();
        }

        void runAll() {
            if (started != null) finishStarted();
            while (!queued.isEmpty()) queued.poll().run();
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            List<Runnable> never = new ArrayList<>(queued);
            queued.clear();
            return never;
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown && queued.isEmpty() && started == null;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return isTerminated();
        }
    }

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("keepadb_selfpair", Context.MODE_PRIVATE)
                .edit().clear().commit();
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit();
        KeepADB.resetForTesting();
        gateway = new KeepADBFakeSettingsGateway(true);
        KeepADB.setGatewayForTesting(gateway);
        grant(false);
        executor = new ManualExecutor();
        KeepADBSelfPairService.resetForTesting();
        KeepADBSelfPairService.setSeamsForTesting((ctx, dialog) -> {
            FakeRunner runner = new FakeRunner(dialog);
            runners.add(runner);
            return runner;
        }, executor);
    }

    @After
    public void tearDown() {
        for (ServiceController<KeepADBSelfPairService> controller : controllers) {
            try {
                controller.destroy();
            } catch (RuntimeException e) {
                // already destroyed by the test
            }
        }
        KeepADBSelfPairService.resetForTesting();
        context.getSharedPreferences("keepadb_selfpair", Context.MODE_PRIVATE)
                .edit().clear().commit();
        KeepADB.resetForTesting();
    }

    private void grant(boolean granted) {
        if (granted) {
            shadowOf((Application) context)
                    .grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS);
        } else {
            shadowOf((Application) context)
                    .denyPermissions(Manifest.permission.WRITE_SECURE_SETTINGS);
        }
    }

    private ServiceController<KeepADBSelfPairService> start() {
        ServiceController<KeepADBSelfPairService> controller = Robolectric.buildService(
                KeepADBSelfPairService.class, intent(START)).create().startCommand(0, 0);
        controllers.add(controller);
        return controller;
    }

    private Intent intent(String action) {
        return new Intent(context, KeepADBSelfPairService.class).setAction(action);
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static Phase phase() {
        return KeepADBSelfPairService.status().phase;
    }

    /** A scripted attempt that pairs (so it claims the cleanup) and then ends as told. */
    private Script pairsThen(String fingerprint, SelfPairSession.Failure failure, boolean grants) {
        return (runner, onPaired) -> {
            onPaired.onPaired(fingerprint);
            if (grants) grant(true);
            return new SelfPairSession.Result(failure, fingerprint);
        };
    }

    // ---- The plain ways through -----------------------------------------------------------------

    @Test
    public void aGrantEndsTheRunSwitchesWifiAdbOffAndNamesTheKey() {
        script = pairsThen("AA:01", null, true);
        ServiceController<KeepADBSelfPairService> controller = start();
        controller.get().onCode("123456");
        assertEquals(Phase.WORKING, phase());
        executor.runAll();
        idle();

        KeepADBSelfPairService.Status status = KeepADBSelfPairService.status();
        assertEquals(Phase.DONE, status.phase);
        assertEquals(Cleanup.SWITCHED_OFF, status.cleanup);
        assertEquals("AA:01", status.fingerprint);
        assertFalse(gateway.isEnabled(context));
        assertFalse(KeepADBSelfPairCleanup.hasClaim(context));
        assertTrue(shadowOf(controller.get()).isStoppedBySelf());
    }

    @Test
    public void aWrongCodeLeavesTheRunOpenAndClaimsNothing() {
        ServiceController<KeepADBSelfPairService> controller = start();
        controller.get().onCode("123456");
        executor.runAll();
        idle();

        assertEquals(Phase.WRONG_CODE, phase());
        assertFalse("never paired, so nothing to clean up later",
                KeepADBSelfPairCleanup.hasClaim(context));
        assertFalse(shadowOf(controller.get()).isStoppedBySelf());
    }

    @Test
    public void cancelWhileIdleEndsTheRun() {
        ServiceController<KeepADBSelfPairService> controller = start();
        assertEquals(Phase.WAITING, phase());
        controller.get().onStartCommand(intent(CANCEL), 0, 1);

        assertEquals(Phase.IDLE, phase());
        assertTrue(runners.get(0).stopped);
        assertTrue(shadowOf(controller.get()).isStoppedBySelf());
    }

    // ---- Cancel and destruction against a working attempt ---------------------------------------

    @Test
    public void cancelWhileWorkingKeepsTheServiceUntilTheAttemptReported() {
        script = (runner, onPaired) -> new SelfPairSession.Result(
                SelfPairSession.Failure.CANCELLED, null);
        ServiceController<KeepADBSelfPairService> controller = start();
        controller.get().onCode("123456");
        executor.begin();
        controller.get().onStartCommand(intent(CANCEL), 0, 1);

        assertTrue("the session is told to stop", runners.get(0).cancelled);
        assertEquals("still working: the grant may already be under way", Phase.WORKING, phase());
        assertFalse(shadowOf(controller.get()).isStoppedBySelf());

        executor.finishStarted();
        idle();
        assertEquals(Phase.IDLE, phase());
        assertTrue(shadowOf(controller.get()).isStoppedBySelf());
    }

    @Test
    public void aGrantThatLandsDespiteCancelIsStillReportedAndCleanedUp() {
        script = pairsThen("AA:02", SelfPairSession.Failure.CANCELLED, true);
        ServiceController<KeepADBSelfPairService> controller = start();
        controller.get().onCode("123456");
        executor.begin();
        controller.get().onStartCommand(intent(CANCEL), 0, 1);
        executor.finishStarted();
        idle();

        assertEquals(Phase.DONE, phase());
        assertEquals(Cleanup.SWITCHED_OFF, KeepADBSelfPairService.status().cleanup);
        assertFalse(gateway.isEnabled(context));
    }

    @Test
    public void destructionWithAQueuedAttemptEndsItInsteadOfLeavingItWorking() {
        ServiceController<KeepADBSelfPairService> controller = start();
        controller.get().onCode("123456");
        assertEquals(Phase.WORKING, phase());
        // The worker never picked the task up; shutdownNow hands it back unrun.
        controller.destroy();
        idle();

        assertEquals(Phase.IDLE, phase());
        assertEquals(0, runners.get(0).runs);
    }

    @Test
    public void destructionWithARunningAttemptStillSettlesItsGrant() {
        script = pairsThen("AA:03", SelfPairSession.Failure.GRANT_UNCONFIRMED, true);
        ServiceController<KeepADBSelfPairService> controller = start();
        controller.get().onCode("123456");
        executor.begin();
        controller.destroy();
        assertEquals("no terminal state before the attempt reported", Phase.WORKING, phase());

        executor.finishStarted();
        idle();
        assertEquals(Phase.DONE, phase());
        assertFalse(gateway.isEnabled(context));
        assertFalse(KeepADBSelfPairCleanup.hasClaim(context));
    }

    @Test
    public void anAttemptOfADestroyedInstanceCannotOverwriteANewerRun() {
        ServiceController<KeepADBSelfPairService> first = start();
        first.get().onCode("123456");
        executor.begin();
        Runnable oldAttempt = executor.started;
        executor.started = null;
        first.destroy();

        // A new run starts and owns the status from here on.
        executor = new ManualExecutor();
        KeepADBSelfPairService.setSeamsForTesting((ctx, dialog) -> {
            FakeRunner runner = new FakeRunner(dialog);
            runners.add(runner);
            return runner;
        }, executor);
        ServiceController<KeepADBSelfPairService> second = start();
        assertEquals(Phase.WAITING, phase());

        // Now the old attempt reports: no permission, nothing to say.
        oldAttempt.run();
        idle();
        assertEquals("the newer run's state stands", Phase.WAITING, phase());
        assertFalse("and the newer run goes on", shadowOf(second.get()).isStoppedBySelf());
        assertFalse(runners.get(1).stopped);
    }

    // ---- The delayed re-check -------------------------------------------------------------------

    @Test
    public void aLateGrantIsNoticedByTheDelayedCheck() {
        script = pairsThen("AA:04", SelfPairSession.Failure.GRANT_UNCONFIRMED, false);
        ServiceController<KeepADBSelfPairService> controller = start();
        controller.get().onCode("123456");
        executor.runAll();
        idle();
        assertEquals(Phase.FAILED, phase());
        assertTrue("paired, so the cleanup is claimed", KeepADBSelfPairCleanup.hasClaim(context));

        grant(true);
        shadowOf(Looper.getMainLooper())
                .idleFor(Duration.ofMillis(KeepADBSelfPairService.LATE_CHECK_MS));
        assertEquals(Phase.DONE, phase());
        assertFalse(gateway.isEnabled(context));
        assertTrue(shadowOf(controller.get()).isStoppedBySelf());
    }

    @Test
    public void theDelayedCheckDiesWithTheRun() {
        script = pairsThen("AA:05", SelfPairSession.Failure.GRANT_UNCONFIRMED, false);
        ServiceController<KeepADBSelfPairService> controller = start();
        controller.get().onCode("123456");
        executor.runAll();
        idle();
        controller.get().onStartCommand(intent(CANCEL), 0, 1);
        assertEquals(Phase.IDLE, phase());

        // Later the user grants from a computer and leaves Wifi-ADB on, on purpose.
        grant(true);
        KeepADB.setEnabled(context, true, KeepADB.SOURCE_APP);
        gateway.writes.clear();
        shadowOf(Looper.getMainLooper())
                .idleFor(Duration.ofMillis(KeepADBSelfPairService.LATE_CHECK_MS * 2));

        assertEquals("the ended run says nothing any more", Phase.IDLE, phase());
        assertTrue(gateway.isEnabled(context));
        assertTrue("and switches nothing", gateway.writes.isEmpty());
    }

    @Test
    public void aRetryTakesOverFromTheDelayedCheckAndKeepsTheClaim() {
        script = pairsThen("AA:06", SelfPairSession.Failure.GRANT_UNCONFIRMED, false);
        ServiceController<KeepADBSelfPairService> controller = start();
        controller.get().onCode("123456");
        executor.runAll();
        idle();
        assertEquals(Phase.FAILED, phase());

        // The user enters the code again before the delayed check is due, and the grant of the
        // first attempt lands while the second is still under way.
        script = pairsThen("AA:06b", null, false);
        controller.get().onCode("123456");
        assertEquals(Phase.WORKING, phase());
        grant(true);
        shadowOf(Looper.getMainLooper())
                .idleFor(Duration.ofMillis(KeepADBSelfPairService.LATE_CHECK_MS * 2));

        assertEquals("the check of the first attempt does not end the second", Phase.WORKING,
                phase());
        assertTrue("nor does it settle the claim behind its back",
                KeepADBSelfPairCleanup.hasClaim(context));
        assertTrue(gateway.isEnabled(context));
        assertFalse(shadowOf(controller.get()).isStoppedBySelf());

        // The second attempt reports and closes the run itself.
        executor.runAll();
        idle();
        assertEquals(Phase.DONE, phase());
        assertFalse(gateway.isEnabled(context));
        assertFalse(KeepADBSelfPairCleanup.hasClaim(context));
    }

    // ---- What stays visible ---------------------------------------------------------------------

    @Test
    public void thePairedKeyStaysNamedAfterAFailedRunIsCancelled() {
        script = pairsThen("AA:07", SelfPairSession.Failure.GRANT_REFUSED, false);
        ServiceController<KeepADBSelfPairService> controller = start();
        controller.get().onCode("123456");
        executor.runAll();
        idle();
        controller.get().onStartCommand(intent(CANCEL), 0, 1);

        assertEquals(Phase.IDLE, phase());
        assertEquals("the entry it left under paired devices is still named",
                java.util.Collections.singletonList("AA:07"),
                KeepADBSelfPairService.status().fingerprints);
    }

    @Test
    public void everyKeyOfSeveralAttemptsIsNamed() {
        script = pairsThen("AA:08", SelfPairSession.Failure.GRANT_REFUSED, false);
        ServiceController<KeepADBSelfPairService> controller = start();
        controller.get().onCode("123456");
        executor.runAll();
        idle();
        script = pairsThen("AA:09", null, true);
        controller.get().onCode("123456");
        executor.runAll();
        idle();

        assertEquals(java.util.Arrays.asList("AA:08", "AA:09"),
                KeepADBSelfPairService.status().fingerprints);
        assertEquals("AA:09", KeepADBSelfPairService.status().fingerprint);
    }

    // ---- The reply belongs to its run -----------------------------------------------------------

    @Test
    public void aCodeWithAnotherRunsTokenNeverReachesTheSession() {
        ServiceController<KeepADBSelfPairService> controller = start();
        runners.get(0).dialog.onPairingDialogChanged(true);
        idle();
        assertEquals(Phase.DIALOG_FOUND, phase());

        Intent foreign = reply(Uri.parse("keepadb-selfpair:someone-elses-run"), "123456");
        controller.get().onStartCommand(foreign, 0, 1);
        assertEquals("a valid code under a foreign token starts nothing", Phase.DIALOG_FOUND,
                phase());
        assertTrue(executor.queued.isEmpty());

        // The same code under this run's own token is accepted.
        Notification shown = shadowOf(context.getSystemService(NotificationManager.class))
                .getNotification(962);
        Uri own = shadowOf(shown.actions[0].actionIntent).getSavedIntent().getData();
        controller.get().onStartCommand(reply(own, "123456"), 0, 2);
        assertEquals(Phase.WORKING, phase());
        assertEquals(1, executor.queued.size());
    }

    private Intent reply(Uri token, String code) {
        Intent intent = intent(CODE).setData(token);
        Bundle results = new Bundle();
        results.putCharSequence("code", code);
        RemoteInput.addResultsToIntent(
                new RemoteInput[] {new RemoteInput.Builder("code").build()}, intent, results);
        return intent;
    }

    @Test
    public void aSecondStartKeepsTheReplyAction() {
        ServiceController<KeepADBSelfPairService> controller = start();
        runners.get(0).dialog.onPairingDialogChanged(true);
        idle();
        controller.get().onStartCommand(intent(START), 0, 1);

        Notification shown = shadowOf(controller.get()).getLastForegroundNotification();
        assertNotNull(shown);
        assertEquals(1, shown.actions.length);
        assertNotNull(shown.actions[0].getRemoteInputs());
        assertEquals("no second session", 1, runners.size());
    }

    // ---- Notifications --------------------------------------------------------------------------

    @Test
    public void aBlockedChannelMakesNotificationsUnusable() {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        assertTrue("before the channel exists", KeepADBSelfPairService.notificationsUsable(context));

        manager.createNotificationChannel(new NotificationChannel("keepadb_selfpair", "x",
                NotificationManager.IMPORTANCE_NONE));
        assertFalse(KeepADBSelfPairService.notificationsUsable(context));

        manager.deleteNotificationChannel("keepadb_selfpair");
        shadowOf(manager).setNotificationsEnabled(false);
        assertFalse(KeepADBSelfPairService.notificationsUsable(context));
    }

    @Test
    public void theClosingNotificationSaysWhenWifiAdbCouldNotBeSwitchedOff() {
        script = pairsThen("AA:10", null, true);
        gateway.setWriteSuccess(false);
        ServiceController<KeepADBSelfPairService> controller = start();
        controller.get().onCode("123456");
        executor.runAll();
        idle();

        assertEquals(Cleanup.STILL_ON, KeepADBSelfPairService.status().cleanup);
        Notification last = shadowOf(context.getSystemService(NotificationManager.class))
                .getNotification(962);
        String text = last.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString();
        assertTrue(text, text.contains(context.getString(R.string.selfpair_done_still_on)));
        assertTrue("the claim stays for the next start", KeepADBSelfPairCleanup.hasClaim(context));
        assertNull("no reply action on a closing notification", last.actions);
    }
}
