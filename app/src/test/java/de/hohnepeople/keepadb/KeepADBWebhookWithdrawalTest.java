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

    /**
     * A cold process that does not know its endpoint yet: the enable kicks the coordinator and the
     * broadcast ends once that is dispatched. Discovery is not awaited; the report is sent by the
     * next normal synchronization trigger, and synchronization stays on.
     */
    @Test
    public void aColdStartEnableFinishesAfterTheRefreshAndTheNextTriggerReports() throws Exception {
        KeepADBEndpointCoordinator.seedEndpointForTesting(null, 0, 0L);
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false)); // nothing to discover

        Delivered tap = new Delivered(KeepADBReceiver.ACTION_WEBHOOK_SYNC_ENABLE);
        settle();

        assertTrue("the broadcast does not wait for a discovery that may never come", tap.isFinished());
        assertTrue(syncEnabled());
        assertEquals(0, transport.getRequestCount());

        // The endpoint shows up later: the normal trigger of an enabled synchronization reports it.
        KeepADBEndpointCoordinator.seedEndpointForTesting(HOST, PORT, 2L);
        KeepADBRegisterClient.updateEndpointAsync(context, HOST, PORT);
        settle();
        assertEquals(1, count("POST"));
        assertTrue(syncEnabled());
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

    // ---- an outstanding withdrawal is only ever retried by the user -------------------------------

    /**
     * The DELETE failed (or the process died before it ran): the preference is off and the report
     * snapshot still names the registration. Nothing sends it again on its own.
     */
    @Test
    public void aFailedWithdrawalStaysOnRecordAndNothingSendsItAgainOnItsOwn() throws Exception {
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

        // A new process, the network back, hours and every trigger of the sender: still nothing.
        KeepADBRegisterClientResetRule.reset();
        KeepADBRegisterClient.setHttpTransport(transport);
        transport.setDeleteSuccess(true);
        transport.clearRequests();
        ((KeepADBApplication) RuntimeEnvironment.getApplication()).onCreate();
        KeepADBRegisterClient.requestResyncAsync(context);
        KeepADBRegisterClient.reattemptStaleEpisode(context);
        KeepADBRegisterClient.markUnavailableAsync(context);
        KeepADBEndpointCoordinator.seedEndpointForTesting(null, 0, 0L);
        ShadowLooper.idleMainLooper(6, TimeUnit.HOURS);
        settle();

        assertEquals("the app never sends a withdrawal on its own", 0, transport.getRequestCount());
        assertEquals("and the record is still there for the user's retry", URL,
                KeepADBPreferences.getWebhookLastReportedUrl(context));
        assertFalse(syncEnabled());
    }

    @Test
    public void theApplicationStartSendsNothingEvenWithAnOutstandingWithdrawal() {
        registered();
        KeepADBPreferences.setRegisterWebhookEnabled(context, false); // died right after "off"
        KeepADBRegisterClientResetRule.reset();
        KeepADBRegisterClient.setHttpTransport(transport);
        transport.clearRequests();

        ((KeepADBApplication) RuntimeEnvironment.getApplication()).onCreate();
        settle();

        assertEquals(0, transport.getRequestCount());
        assertEquals(URL, KeepADBPreferences.getWebhookLastReportedUrl(context));
    }

    /** Switching off again is the user's retry; a later tap is not swallowed as "unchanged". */
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
        assertEquals(URL, transport.getLastRequest().url);
        assertNull(KeepADBPreferences.getWebhookLastReportedUrl(context));

        assertEquals("and with nothing left it is a no-op again",
                KeepADBWebhookSync.Result.UNCHANGED,
                KeepADBWebhookSync.setAutomaticSync(context, false));
        settle();
        assertEquals(1, count("DELETE"));
    }

    /**
     * The retry goes to the saved destination only. If the URL was replaced or cleared since the
     * registration was recorded, the old destination is not contacted from this path.
     */
    @Test
    public void switchingOffAgainDoesNotContactADestinationThatIsNoLongerSaved() {
        registered();
        transport.setDeleteSuccess(false);
        KeepADBWebhookSync.setAutomaticSync(context, false);
        settle();
        transport.setDeleteSuccess(true);
        transport.clearRequests();

        KeepADBPreferences.setRegisterWebhookUrl(context, URL_2);
        assertEquals(KeepADBWebhookSync.Result.UNCHANGED,
                KeepADBWebhookSync.setAutomaticSync(context, false));
        settle();
        assertEquals("replaced URL: the old destination is left alone", 0,
                transport.getRequestCount());

        KeepADBPreferences.setRegisterWebhookUrl(context, null);
        assertEquals(KeepADBWebhookSync.Result.UNCHANGED,
                KeepADBWebhookSync.setAutomaticSync(context, false));
        settle();
        assertEquals("cleared URL: likewise", 0, transport.getRequestCount());
        assertFalse(syncEnabled());
    }

    @Test
    public void switchingOffAgainWithNothingRecordedSendsNothing() {
        assertEquals(KeepADBWebhookSync.Result.UNCHANGED,
                KeepADBWebhookSync.setAutomaticSync(context, false));
        settle();
        assertEquals(0, transport.getRequestCount());
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
