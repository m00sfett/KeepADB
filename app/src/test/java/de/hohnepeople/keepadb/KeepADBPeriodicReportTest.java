package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlarmManager;
import android.app.Application;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlarmManager;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPendingIntent;
import org.robolectric.shadows.ShadowSystemClock;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * #976 (based on work by inaciopestana): 4-hour periodic webhook refresh while Keep-Alive
 * monitoring and Webhook Sync are active.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBPeriodicReportTest {

    @Rule
    public final KeepADBNetworkResetRule networkResetRule = new KeepADBNetworkResetRule();
    @Rule
    public final KeepADBRegisterClientResetRule registerResetRule =
            new KeepADBRegisterClientResetRule();

    private static final String URL = "http://register.example/register/dev1";
    private static final String HOST = "10.20.30.40";
    private static final int PORT = 41873;

    private Context context;
    private AlarmManager alarms;
    private KeepADBFakeHttpTransport transport;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS,
                android.Manifest.permission.POST_NOTIFICATIONS);
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("keepadb_diagnostics", Context.MODE_PRIVATE).edit().clear().commit();
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
        alarms = context.getSystemService(AlarmManager.class);
        KeepADBPeriodicReport.cancel(context);
    }

    @After
    public void tearDown() {
        KeepADBPeriodicReport.cancel(context);
        KeepADBRegisterClientResetRule.awaitIdle();
        ShadowLooper.idleMainLooper();
        KeepADBEndpointCoordinator.setReachabilityProbeForTesting(null);
        KeepADBEndpointCoordinator.resetForTesting();
        KeepADBReportHistory.clear(context);
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADB.resetForTesting();
    }

    private void enableKeepAliveAndWebhook() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBPreferences.setRegisterWebhookUrl(context, URL);
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        KeepADBEndpointCoordinator.seedEndpointForTesting(HOST, PORT, 1L);
    }

    private void settle() {
        KeepADBRegisterClientResetRule.awaitIdle();
        ShadowLooper.idleMainLooper();
        KeepADBRegisterClientResetRule.awaitIdle();
    }

    @Test
    public void ensureArmedRequiresKeepAliveWebhookAndValidUrl() {
        // Neither Keep-Alive nor Webhook Sync enabled.
        KeepADBPeriodicReport.ensureArmed(context);
        assertFalse(KeepADBPeriodicReport.isArmed(context));

        // Keep-Alive on, Webhook Sync still off -> no wakeup alarm scheduled.
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBPreferences.setRegisterWebhookUrl(context, URL);
        KeepADBPeriodicReport.ensureArmed(context);
        assertFalse(KeepADBPeriodicReport.isArmed(context));

        // Webhook Sync on, URL invalid -> still not armed.
        KeepADBPreferences.setRegisterWebhookUrl(context, "not-a-url");
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        KeepADBPeriodicReport.ensureArmed(context);
        assertFalse(KeepADBPeriodicReport.isArmed(context));

        // All preconditions met -> armed.
        KeepADBPreferences.setRegisterWebhookUrl(context, URL);
        KeepADBPeriodicReport.ensureArmed(context);
        assertTrue(KeepADBPeriodicReport.isArmed(context));
    }

    @Test
    public void armUsesInexactElapsedWakeupWithExplicitImmutableBroadcast() {
        enableKeepAliveAndWebhook();
        long before = SystemClock.elapsedRealtime();

        KeepADBPeriodicReport.arm(context);

        ShadowAlarmManager.ScheduledAlarm scheduled = shadowOf(alarms).peekNextScheduledAlarm();
        assertNotNull(scheduled);
        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, scheduled.getType());
        assertTrue(scheduled.isAllowWhileIdle());
        assertEquals(before + KeepADBPeriodicReport.INTERVAL_MS, scheduled.getTriggerAtMs());

        PendingIntent op = scheduled.operation;
        ShadowPendingIntent shadowOp = shadowOf(op);
        assertTrue(shadowOp.isBroadcastIntent());
        assertEquals(KeepADBPeriodicReport.REQUEST_CODE_PERIODIC_REPORT, shadowOp.getRequestCode());
        assertTrue((shadowOp.getFlags() & PendingIntent.FLAG_IMMUTABLE) != 0);
        Intent saved = shadowOp.getSavedIntent();
        assertEquals(KeepADBReceiver.ACTION_PERIODIC_REPORT, saved.getAction());
        assertNotNull(saved.getComponent());
        assertEquals(KeepADBReceiver.class.getName(), saved.getComponent().getClassName());
    }

    @Test
    public void ensureArmedDoesNotPushBackPendingAlarm() {
        enableKeepAliveAndWebhook();
        KeepADBPeriodicReport.ensureArmed(context);
        long firstDue = shadowOf(alarms).peekNextScheduledAlarm().getTriggerAtMs();

        ShadowSystemClock.advanceBy(Duration.ofHours(1));
        KeepADBPeriodicReport.ensureArmed(context);

        assertEquals(firstDue, shadowOf(alarms).peekNextScheduledAlarm().getTriggerAtMs());
    }

    @Test
    public void cancelRemovesAlarmAndPendingIntent() {
        enableKeepAliveAndWebhook();
        KeepADBPeriodicReport.arm(context);
        assertTrue(KeepADBPeriodicReport.isArmed(context));
        assertNotNull(shadowOf(alarms).peekNextScheduledAlarm());

        KeepADBPeriodicReport.cancel(context);

        assertFalse(KeepADBPeriodicReport.isArmed(context));
        assertNull(shadowOf(alarms).peekNextScheduledAlarm());
    }

    @Test
    public void periodicReportActionReArmsNextAlarmAndSendsReport() {
        enableKeepAliveAndWebhook();
        KeepADBPeriodicReport.arm(context);
        ShadowSystemClock.advanceBy(Duration.ofMillis(KeepADBPeriodicReport.INTERVAL_MS));
        long firedAt = SystemClock.elapsedRealtime();

        new KeepADBReceiver().onReceive(context,
                new Intent(context, KeepADBReceiver.class)
                        .setAction(KeepADBReceiver.ACTION_PERIODIC_REPORT));
        settle();

        assertEquals(1, transport.recordedRequests.size());
        KeepADBFakeHttpTransport.Request req = transport.recordedRequests.get(0);
        assertEquals("POST", req.method);
        assertEquals(URL, req.url);
        assertTrue(req.payload,
                req.payload.contains(HOST) && req.payload.contains(String.valueOf(PORT)));

        ShadowAlarmManager.ScheduledAlarm next = shadowOf(alarms).peekNextScheduledAlarm();
        assertNotNull(next);
        assertEquals(firedAt + KeepADBPeriodicReport.INTERVAL_MS, next.getTriggerAtMs());
        String export = KeepADBDiagnostics.export(context);
        assertTrue(export.contains("event=webhook_periodic source=alarm outcome=fired"));
    }

    @Test
    public void periodicReportActionWhenKeepAliveOffCancelsChainAndSendsNothing() {
        enableKeepAliveAndWebhook();
        KeepADBPeriodicReport.arm(context);
        KeepADBPreferences.setKeepAliveEnabled(context, false);

        AtomicBoolean finished = new AtomicBoolean(false);
        KeepADBRegisterClient.ReportNowResult result = KeepADBReceiver.handlePeriodicReportAction(
                context, () -> finished.set(true));
        settle();

        assertEquals(KeepADBRegisterClient.ReportNowResult.SYNC_OFF, result);
        assertTrue(finished.get());
        assertFalse(KeepADBPeriodicReport.isArmed(context));
        assertNull(shadowOf(alarms).peekNextScheduledAlarm());
        assertTrue(transport.recordedRequests.isEmpty());
    }

    @Test
    public void periodicReportActionWhenWebhookOffCancelsChainAndSendsNothing() {
        enableKeepAliveAndWebhook();
        KeepADBPeriodicReport.arm(context);
        KeepADBPreferences.setRegisterWebhookEnabled(context, false);

        AtomicBoolean finished = new AtomicBoolean(false);
        KeepADBRegisterClient.ReportNowResult result = KeepADBReceiver.handlePeriodicReportAction(
                context, () -> finished.set(true));
        settle();

        assertEquals(KeepADBRegisterClient.ReportNowResult.SYNC_OFF, result);
        assertTrue(finished.get());
        assertFalse(KeepADBPeriodicReport.isArmed(context));
        assertNull(shadowOf(alarms).peekNextScheduledAlarm());
        assertTrue(transport.recordedRequests.isEmpty());
    }

    @Test
    public void periodicReportActionWithMissingEndpointRefreshesAndReArms() {
        enableKeepAliveAndWebhook();
        KeepADBEndpointCoordinator.resetForTesting();

        AtomicBoolean finished = new AtomicBoolean(false);
        KeepADBRegisterClient.ReportNowResult result = KeepADBReceiver.handlePeriodicReportAction(
                context, () -> finished.set(true));
        settle();

        assertEquals(KeepADBRegisterClient.ReportNowResult.NO_ENDPOINT, result);
        assertTrue(finished.get());
        assertTrue(KeepADBPeriodicReport.isArmed(context));
    }

    @Test
    public void webhookSyncEnableAndDisableManagePeriodicAlarm() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        assertFalse(KeepADBPeriodicReport.isArmed(context));

        KeepADBWebhookSync.enable(context, URL);
        settle();
        assertTrue(KeepADBPeriodicReport.isArmed(context));

        KeepADBWebhookSync.disable(context);
        settle();
        assertFalse(KeepADBPeriodicReport.isArmed(context));
        assertNull(shadowOf(alarms).peekNextScheduledAlarm());
    }

    @Test
    public void serviceLifecycleArmsOnStartAndCancelsWhenStopped() {
        enableKeepAliveAndWebhook();
        ServiceController<KeepADBService> controller =
                Robolectric.buildService(KeepADBService.class,
                        new Intent(context, KeepADBService.class)).create().startCommand(0, 0);

        assertTrue("armed when service starts with Keep-Alive and Webhook Sync on",
                KeepADBPeriodicReport.isArmed(context));

        // Destroying while shouldRun() is still true (e.g. OS restart) preserves the schedule.
        controller.destroy();
        assertTrue("preserves alarm across OS restarts while still eligible",
                KeepADBPeriodicReport.isArmed(context));

        // Turning Keep-Alive off and syncing/stopping cancels the alarm immediately.
        KeepADBPreferences.setKeepAliveEnabled(context, false);
        KeepADBService.sync(context);
        assertFalse(KeepADBPeriodicReport.isArmed(context));
        assertNull(shadowOf(alarms).peekNextScheduledAlarm());
    }

    @Test
    public void serviceOnDestroyCancelsWhenNoLongerEligible() {
        enableKeepAliveAndWebhook();
        ServiceController<KeepADBService> controller =
                Robolectric.buildService(KeepADBService.class,
                        new Intent(context, KeepADBService.class)).create().startCommand(0, 0);
        assertTrue(KeepADBPeriodicReport.isArmed(context));

        KeepADBPreferences.setKeepAliveEnabled(context, false);
        controller.destroy();

        assertFalse(KeepADBPeriodicReport.isArmed(context));
    }

    @Test
    public void periodicReportResendsUnchangedEndpointEveryFourHours() {
        enableKeepAliveAndWebhook();
        // Initial report for HOST:PORT succeeds.
        KeepADBRegisterClient.updateEndpointAsync(context, HOST, PORT);
        settle();
        assertEquals(1, transport.recordedRequests.size());

        // Ordinary updateEndpointAsync is a no-op for the same unchanged endpoint.
        KeepADBRegisterClient.updateEndpointAsync(context, HOST, PORT);
        settle();
        assertEquals(1, transport.recordedRequests.size());

        // The 4-hour periodic alarm still re-sends it so expiring destinations stay fresh.
        KeepADBPeriodicReport.arm(context);
        ShadowSystemClock.advanceBy(Duration.ofMillis(KeepADBPeriodicReport.INTERVAL_MS));
        KeepADBReceiver.handlePeriodicReportAction(context, () -> {});
        settle();
        assertEquals(2, transport.recordedRequests.size());
        assertEquals("POST", transport.recordedRequests.get(1).method);
        assertEquals(URL, transport.recordedRequests.get(1).url);
    }

    @Test
    public void periodicReportJoinsInFlightPostInsteadOfSendingDuplicate()
            throws InterruptedException {
        enableKeepAliveAndWebhook();
        KeepADBPeriodicReport.arm(context);
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        transport.setRequestCallback(req -> {
            entered.countDown();
            try {
                release.await(3, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
        });

        KeepADBRegisterClient.updateEndpointAsync(context, HOST, PORT);
        assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS));

        AtomicBoolean finished = new AtomicBoolean(false);
        KeepADBRegisterClient.ReportNowResult result = KeepADBReceiver.handlePeriodicReportAction(
                context, () -> finished.set(true));
        assertEquals(KeepADBRegisterClient.ReportNowResult.JOINED, result);

        release.countDown();
        settle();
        assertTrue(finished.get());
        assertEquals("joined the in-flight POST instead of adding a second one",
                1, transport.recordedRequests.size());
        assertTrue(KeepADBPeriodicReport.isArmed(context));
    }

    @Test
    public void clearingWebhookFormCancelsPeriodicAlarm() {
        enableKeepAliveAndWebhook();
        KeepADBPeriodicReport.arm(context);
        assertTrue(KeepADBPeriodicReport.isArmed(context));

        android.app.Activity activity = Robolectric.buildActivity(android.app.Activity.class)
                .setup().get();
        activity.setContentView(R.layout.activity_settings);
        new KeepADBWebhookForm(activity, () -> {});
        activity.findViewById(R.id.settings_webhook_clear).performClick();
        settle();

        assertFalse(KeepADBPeriodicReport.isArmed(context));
        assertNull(shadowOf(alarms).peekNextScheduledAlarm());
    }
}
