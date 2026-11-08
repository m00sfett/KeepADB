package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.test.core.app.ApplicationProvider;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowBroadcastPendingResult;
import org.robolectric.shadows.ShadowLooper;

/**
 * #911 (Codex review R2): the opt-out turns the preference off at once and sends its DELETE
 * asynchronously. A widget's broadcast must therefore stay open until the withdrawal has been
 * attempted, and a withdrawal that the process did not survive, or that failed, must be retried at
 * the next start instead of being forgotten behind a switch that already says "off".
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBWebhookWithdrawalTest {

    @Rule
    public final KeepADBNetworkResetRule networkResetRule = new KeepADBNetworkResetRule();
    @Rule
    public final KeepADBRegisterClientResetRule registerResetRule = new KeepADBRegisterClientResetRule();

    private static final String URL = "http://register.example/register/dev1";
    private static final String URL_2 = "http://register.example/register/dev2";
    private static final String HOST = "10.20.30.40";
    private static final int PORT = 41873;
    private static final String ENDPOINT = "10.20.30.40:41873";

    private Context context;
    private KeepADBFakeHttpTransport transport;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADBPreferences.migratePrivacySplit(context);
        KeepADBReportHistory.clear(context);
        KeepADBEndpointCoordinator.resetForTesting();
        KeepADB.resetForTesting(context);
        KeepADB.setSurfaceRefresherForTesting(new KeepADBFakeSurfaceRefresher());
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(true));
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        KeepADBEndpointCoordinator.setReachabilityProbeForTesting((host, port, timeout) -> true);
        transport = new KeepADBFakeHttpTransport();
        KeepADBRegisterClient.setHttpTransport(transport);
        KeepADBPreferences.setRegisterWebhookUrl(context, URL);
        KeepADBEndpointCoordinator.seedEndpointForTesting(HOST, PORT, 1L);
    }

    @After
    public void tearDown() {
        transport.setRequestCallback(null);
        KeepADBRegisterClientResetRule.awaitIdle();
        KeepADBEndpointCoordinator.setReachabilityProbeForTesting(null);
        KeepADBEndpointCoordinator.resetForTesting();
        KeepADBReportHistory.clear(context);
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADB.resetForTesting();
    }

    // ---- helpers ------------------------------------------------------------------------------

    private void settle() {
        KeepADBRegisterClientResetRule.awaitIdle();
        ShadowLooper.idleMainLooper();
        KeepADBRegisterClientResetRule.awaitIdle();
    }

    private int count(String method) {
        int count = 0;
        for (KeepADBFakeHttpTransport.Request request : new ArrayList<>(transport.recordedRequests)) {
            if (method.equals(request.method)) count++;
        }
        return count;
    }

    private boolean syncEnabled() {
        return KeepADBPreferences.isRegisterWebhookEnabled(context);
    }

    /** Synchronization on and the cached endpoint reported and recorded. */
    private void registered() {
        KeepADBWebhookSync.setAutomaticSync(context, true);
        settle();
        assertEquals(1, count("POST"));
        assertEquals(ENDPOINT, KeepADBPreferences.getWebhookLastReportedEndpoint(context));
        transport.clearRequests();
    }

    /** Blocks requests of one method on the wire until released. */
    private final class Gate {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        Gate(String method) {
            transport.setRequestCallback(request -> {
                if (!method.equals(request.method)) return;
                started.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        void awaitStarted() throws InterruptedException {
            assertTrue(started.await(5, TimeUnit.SECONDS));
        }
    }

    /** A broadcast the way the system delivers it: with a pending result that goAsync() hands out. */
    private final class Delivered {
        final BroadcastReceiver.PendingResult pending;
        private final Future<?> finished;

        Delivered(String action) throws Exception {
            Method create = ShadowBroadcastPendingResult.class.getDeclaredMethod("create",
                    int.class, String.class, Bundle.class, boolean.class);
            create.setAccessible(true);
            pending = (BroadcastReceiver.PendingResult) create.invoke(null, 0, null, null, false);
            Object shadow = Shadow.extract(pending);
            finished = (Future<?>) shadow.getClass().getMethod("getFuture").invoke(shadow);

            KeepADBReceiver receiver = new KeepADBReceiver();
            Method set = BroadcastReceiver.class.getDeclaredMethod("setPendingResult",
                    BroadcastReceiver.PendingResult.class);
            set.setAccessible(true);
            set.invoke(receiver, pending);
            receiver.onReceive(context, new Intent(context, KeepADBReceiver.class).setAction(action));
        }

        boolean isFinished() {
            return finished.isDone();
        }
    }

    // ---- the broadcast stays open until the work is out -------------------------------------------

    @Test
    public void theDisableBroadcastStaysOpenUntilTheDeleteHasFinished() throws Exception {
        registered();
        Gate gate = new Gate("DELETE");

        Delivered tap = new Delivered(KeepADBReceiver.ACTION_WEBHOOK_SYNC_DISABLE);
        gate.awaitStarted();
        ShadowLooper.idleMainLooper();

        assertFalse("the DELETE is on the wire: the process must be kept alive", tap.isFinished());
        assertFalse("the preference already says off", syncEnabled());
        assertEquals("and the registration is still on record, which is what survives a death",
                URL, KeepADBPreferences.getWebhookLastReportedUrl(context));

        gate.release.countDown();
        settle();

        assertTrue("released once the withdrawal has been attempted", tap.isFinished());
        assertEquals(1, count("DELETE"));
        assertEquals(KeepADBPreferences.WEBHOOK_STATUS_DEREGISTERED,
                KeepADBPreferences.getWebhookLastReportStatus(context));
        assertNull("the record is gone with the withdrawal",
                KeepADBPreferences.getWebhookLastReportedUrl(context));
    }

    @Test
    public void theEnableBroadcastStaysOpenUntilTheFirstReportIsOut() throws Exception {
        Gate gate = new Gate("POST");

        Delivered tap = new Delivered(KeepADBReceiver.ACTION_WEBHOOK_SYNC_ENABLE);
        gate.awaitStarted();
        ShadowLooper.idleMainLooper();

        assertTrue(syncEnabled());
        assertFalse("the first report is on the wire", tap.isFinished());

        gate.release.countDown();
        settle();
        assertTrue(tap.isFinished());
        assertEquals(1, count("POST"));
    }

    @Test
    public void aTapThatQueuesNothingReleasesTheBroadcastAtOnce() throws Exception {
        KeepADBPreferences.setRegisterWebhookUrl(context, null);
        assertTrue("enable without a destination: nothing queued",
                new Delivered(KeepADBReceiver.ACTION_WEBHOOK_SYNC_ENABLE).isFinished());
        assertTrue("disable while off with nothing to withdraw",
                new Delivered(KeepADBReceiver.ACTION_WEBHOOK_SYNC_DISABLE).isFinished());
        assertEquals(0, transport.getRequestCount());
    }

    /** A hung DELETE does not hold the broadcast past its window, and it is released only once. */
    @Test
    public void aHungDeleteIsReleasedByTheHoldLimitAndOnlyOnce() throws Exception {
        registered();
        Gate gate = new Gate("DELETE");
        Delivered tap = new Delivered(KeepADBReceiver.ACTION_WEBHOOK_SYNC_DISABLE);
        gate.awaitStarted();
        assertFalse(tap.isFinished());

        ShadowLooper.idleMainLooper(KeepADBReceiver.REPORT_NOW_MAX_HOLD_MS, TimeUnit.MILLISECONDS);
        assertTrue("released by the hold limit", tap.isFinished());

        gate.release.countDown();
        settle(); // the late completion would finish a second time and the shadow rejects that
        assertTrue(tap.isFinished());
    }

    // ---- an interrupted or failed withdrawal is retried at the next start ----------------------------

    /** The process died after the preference went off and before the DELETE ran. */
    @Test
    public void aWithdrawalTheProcessDidNotSurviveIsSentAtTheNextStart() {
        registered();
        // What the preferences hold when the process dies right after "off": off, and the report
        // snapshot still naming the registration.
        KeepADBPreferences.setRegisterWebhookEnabled(context, false);
        assertEquals(KeepADBPreferences.WEBHOOK_STATUS_SUCCESS,
                KeepADBPreferences.getWebhookLastReportStatus(context));
        KeepADBRegisterClientResetRule.reset(); // a new process: no in-memory state at all
        KeepADBRegisterClient.setHttpTransport(transport);
        transport.clearRequests();

        KeepADBRegisterClient.resumeInterruptedWithdrawal(context);
        settle();

        assertEquals("the withdrawal is sent now", 1, count("DELETE"));
        assertEquals(URL, transport.getLastRequest().url);
        assertEquals(KeepADBPreferences.WEBHOOK_STATUS_DEREGISTERED,
                KeepADBPreferences.getWebhookLastReportStatus(context));
        assertNull(KeepADBPreferences.getWebhookLastReportedUrl(context));
        assertFalse("never switches anything on", syncEnabled());

        KeepADBRegisterClient.resumeInterruptedWithdrawal(context);
        settle();
        assertEquals("and once it is done there is nothing left to resume", 1, count("DELETE"));
    }

    /** What is withdrawn is what was registered, not what is saved now. */
    @Test
    public void theRecordedRegistrationIsWithdrawnNotTheCurrentlySavedUrl() {
        registered();
        KeepADBPreferences.setRegisterWebhookEnabled(context, false);
        KeepADBPreferences.setRegisterWebhookUrl(context, URL_2);
        KeepADBRegisterClientResetRule.reset();
        KeepADBRegisterClient.setHttpTransport(transport);
        transport.clearRequests();

        KeepADBRegisterClient.resumeInterruptedWithdrawal(context);
        settle();

        assertEquals(1, count("DELETE"));
        assertEquals(URL, transport.getLastRequest().url);
    }

    @Test
    public void aFailedWithdrawalStaysOnRecordAndIsRetriedAtTheNextStart() throws Exception {
        registered();
        transport.setDeleteSuccess(false);
        Delivered tap = new Delivered(KeepADBReceiver.ACTION_WEBHOOK_SYNC_DISABLE);
        settle();
        assertTrue(tap.isFinished());
        assertEquals(1, count("DELETE"));
        assertFalse(syncEnabled());
        assertEquals("the failed withdrawal is still on record", URL,
                KeepADBPreferences.getWebhookLastReportedUrl(context));
        assertEquals(KeepADBPreferences.WEBHOOK_STATUS_FAILED,
                KeepADBPreferences.getWebhookLastReportStatus(context));

        // The network is back at the next start.
        KeepADBRegisterClientResetRule.reset();
        KeepADBRegisterClient.setHttpTransport(transport);
        transport.setDeleteSuccess(true);
        transport.clearRequests();
        KeepADBRegisterClient.resumeInterruptedWithdrawal(context);
        settle();

        assertEquals(1, count("DELETE"));
        assertEquals(KeepADBPreferences.WEBHOOK_STATUS_DEREGISTERED,
                KeepADBPreferences.getWebhookLastReportStatus(context));
        assertNull(KeepADBPreferences.getWebhookLastReportedUrl(context));
    }

    /** Switching off again is the explicit retry; a later tap is not swallowed as "unchanged". */
    @Test
    public void switchingOffAgainRetriesAnOutstandingWithdrawal() {
        registered();
        transport.setDeleteSuccess(false);
        KeepADBWebhookSync.setAutomaticSync(context, false);
        settle();
        assertFalse(syncEnabled());
        transport.setDeleteSuccess(true);
        transport.clearRequests();

        assertEquals(KeepADBWebhookSync.Result.DISABLED,
                KeepADBWebhookSync.setAutomaticSync(context, false));
        settle();

        assertEquals("the second tap withdraws it", 1, count("DELETE"));
        assertNull(KeepADBPreferences.getWebhookLastReportedUrl(context));

        assertEquals("and with nothing left it is a no-op again",
                KeepADBWebhookSync.Result.UNCHANGED,
                KeepADBWebhookSync.setAutomaticSync(context, false));
        settle();
        assertEquals(1, count("DELETE"));
    }

    @Test
    public void nothingIsResumedWithoutAnOutstandingWithdrawalOrWithSyncOn() {
        KeepADBRegisterClient.resumeInterruptedWithdrawal(context);
        settle();
        assertEquals("fresh: nothing recorded", 0, transport.getRequestCount());

        registered();
        KeepADBRegisterClient.resumeInterruptedWithdrawal(context);
        settle();
        assertEquals("synchronization is on: the registration is live", 0, count("DELETE"));
        assertTrue(syncEnabled());
    }

    /** The start of the app is the trigger. */
    @Test
    public void theApplicationStartResumesAnInterruptedWithdrawal() {
        registered();
        KeepADBPreferences.setRegisterWebhookEnabled(context, false);
        KeepADBRegisterClientResetRule.reset();
        KeepADBRegisterClient.setHttpTransport(transport);
        transport.clearRequests();

        ((KeepADBApplication) RuntimeEnvironment.getApplication()).onCreate();
        settle();

        assertEquals(1, count("DELETE"));
        assertEquals(URL, transport.getLastRequest().url);
    }

    @Test
    public void theApplicationStartSendsNothingWhenNothingIsOutstanding() {
        ((KeepADBApplication) RuntimeEnvironment.getApplication()).onCreate();
        settle();

        assertEquals(0, transport.getRequestCount());
        assertFalse(syncEnabled());
    }

    @Test
    public void theResumeNamesNeitherTheUrlNorTheEndpointInTheDiagnostics() {
        registered();
        KeepADBPreferences.setRegisterWebhookEnabled(context, false);
        KeepADBRegisterClientResetRule.reset();
        KeepADBRegisterClient.setHttpTransport(transport);

        KeepADBRegisterClient.resumeInterruptedWithdrawal(context);
        settle();

        String diagnostics = KeepADBDiagnostics.export(context);
        String resumed = null;
        for (String line : diagnostics.split("\n")) {
            if (line.contains("reason=interrupted_withdrawal")) resumed = line;
        }
        assertTrue(diagnostics, resumed != null);
        assertFalse("no endpoint in the event: " + resumed, resumed.contains(HOST));
        assertFalse("no URL anywhere: " + diagnostics, diagnostics.contains("register.example"));
    }

    @Test
    public void aDeleteWithoutAnyOnDoneStillWorksForTheForm() {
        registered();
        AtomicInteger done = new AtomicInteger();

        KeepADBRegisterClient.unregisterAndDisableAsync(context, done::incrementAndGet);
        settle();

        assertEquals(1, done.get());
        assertEquals(1, count("DELETE"));
    }
}
