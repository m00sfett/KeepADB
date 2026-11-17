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
 * #976 (based on work by inaciopestana): opt-in configurable periodic webhook refresh while
 * Keep-Alive monitoring and Webhook Sync are active.
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
        KeepADBPreferences.setPeriodicWebhookReportEnabled(context, true);
        KeepADBEndpointCoordinator.seedEndpointForTesting(HOST, PORT, 1L);
    }

    private void settle() {
        KeepADBRegisterClientResetRule.awaitIdle();
        ShadowLooper.idleMainLooper();
        KeepADBRegisterClientResetRule.awaitIdle();
    }

    @Test
    public void ensureArmedRequiresKeepAliveWebhookPeriodicAndValidUrl() {
        // Defaults to periodic OFF even when Keep-Alive and Webhook Sync are configured.
        assertFalse(KeepADBPreferences.isPeriodicWebhookReportEnabled(context));
        assertEquals(KeepADBPeriodicReport.Interval.HOURS_4,
                KeepADBPreferences.getPeriodicWebhookInterval(context));

        // Neither Keep-Alive, Webhook Sync, nor Periodic Refresh enabled.
        KeepADBPeriodicReport.ensureArmed(context);
        assertFalse(KeepADBPeriodicReport.isArmed(context));

        // Keep-Alive on, Webhook Sync still off -> no wakeup alarm scheduled.
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBPreferences.setRegisterWebhookUrl(context, URL);
        KeepADBPeriodicReport.ensureArmed(context);
        assertFalse(KeepADBPeriodicReport.isArmed(context));

        // Keep-Alive + Webhook Sync on, Periodic Refresh still off (default) -> not armed.
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        KeepADBPeriodicReport.ensureArmed(context);
        assertFalse(KeepADBPeriodicReport.isArmed(context));

        // Periodic Refresh on, URL invalid -> still not armed.
        KeepADBPreferences.setPeriodicWebhookReportEnabled(context, true);
        KeepADBPreferences.setRegisterWebhookUrl(context, "not-a-url");
        KeepADBPeriodicReport.ensureArmed(context);
        assertFalse(KeepADBPeriodicReport.isArmed(context));

        // All preconditions met -> armed.
        KeepADBPreferences.setRegisterWebhookUrl(context, URL);
        KeepADBPeriodicReport.ensureArmed(context);
        assertTrue(KeepADBPeriodicReport.isArmed(context));
    }

    @Test
    public void intervalPresetsUseExpectedDurationsAndSubtractOneMinuteFromHourlyValues() {
        assertEquals(15L * 60_000L, KeepADBPeriodicReport.Interval.MIN_15.durationMs);
        assertEquals(30L * 60_000L, KeepADBPeriodicReport.Interval.MIN_30.durationMs);
        assertEquals(59L * 60_000L, KeepADBPeriodicReport.Interval.HOUR_1.durationMs);
        assertEquals(239L * 60_000L, KeepADBPeriodicReport.Interval.HOURS_4.durationMs);
        assertEquals(719L * 60_000L, KeepADBPeriodicReport.Interval.HOURS_12.durationMs);
        assertEquals(1439L * 60_000L, KeepADBPeriodicReport.Interval.HOURS_24.durationMs);
        assertEquals(KeepADBPeriodicReport.Interval.HOURS_4, KeepADBPeriodicReport.Interval.DEFAULT);
        assertEquals(KeepADBPeriodicReport.Interval.DEFAULT,
                KeepADBPeriodicReport.Interval.fromId(null));
        assertEquals(KeepADBPeriodicReport.Interval.DEFAULT,
                KeepADBPeriodicReport.Interval.fromId("unknown"));
        assertEquals(KeepADBPeriodicReport.Interval.MIN_15,
                KeepADBPeriodicReport.Interval.fromId("15m"));
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
        assertEquals(before + KeepADBPeriodicReport.Interval.DEFAULT.durationMs,
                scheduled.getTriggerAtMs());

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
        ShadowSystemClock.advanceBy(
                Duration.ofMillis(KeepADBPeriodicReport.Interval.DEFAULT.durationMs));
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
        assertEquals(firedAt + KeepADBPeriodicReport.Interval.DEFAULT.durationMs,
                next.getTriggerAtMs());
        String export = KeepADBDiagnostics.export(context);
        assertTrue(export.contains("event=webhook_periodic source=alarm outcome=fired"));
    }

    @Test
    public void periodicReportActionWhenPeriodicOffCancelsChainAndSendsNothing() {
        enableKeepAliveAndWebhook();
        KeepADBPeriodicReport.arm(context);
        KeepADBPreferences.setPeriodicWebhookReportEnabled(context, false);

        AtomicBoolean finished = new AtomicBoolean(false);
        KeepADBRegisterClient.ReportNowResult result = KeepADBReceiver.handlePeriodicReportAction(
                context, () -> finished.set(true));
        settle();

        assertEquals(KeepADBRegisterClient.ReportNowResult.SYNC_OFF, result);
        assertTrue(finished.get());
        assertFalse(KeepADBPeriodicReport.isArmed(context));
        assertNull(shadowOf(alarms).peekNextScheduledAlarm());
        assertTrue(transport.recordedRequests.isEmpty());
        assertTrue(KeepADBDiagnostics.export(context).contains("periodic_off"));
    }

    @Test
    public void rescheduleReplacesPendingAlarmWithSelectedInterval() {
        enableKeepAliveAndWebhook();
        KeepADBPeriodicReport.ensureArmed(context);
        long start = SystemClock.elapsedRealtime();
        assertEquals(start + KeepADBPeriodicReport.Interval.HOURS_4.durationMs,
                shadowOf(alarms).peekNextScheduledAlarm().getTriggerAtMs());

        KeepADBPreferences.setPeriodicWebhookInterval(
                context, KeepADBPeriodicReport.Interval.MIN_15);
        KeepADBPeriodicReport.reschedule(context);
        assertEquals(start + KeepADBPeriodicReport.Interval.MIN_15.durationMs,
                shadowOf(alarms).peekNextScheduledAlarm().getTriggerAtMs());
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

        // Periodic refresh is opt-in (default off): enabling Webhook Sync alone does not arm it.
        KeepADBWebhookSync.enable(context, URL);
        settle();
        assertFalse(KeepADBPeriodicReport.isArmed(context));

        KeepADBPreferences.setPeriodicWebhookReportEnabled(context, true);
        KeepADBPeriodicReport.sync(context);
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
    public void periodicReportResendsUnchangedEndpointAtConfiguredInterval() {
        enableKeepAliveAndWebhook();
        // Initial report for HOST:PORT succeeds.
        KeepADBRegisterClient.updateEndpointAsync(context, HOST, PORT);
        settle();
        assertEquals(1, transport.recordedRequests.size());

        // Ordinary updateEndpointAsync is a no-op for the same unchanged endpoint.
        KeepADBRegisterClient.updateEndpointAsync(context, HOST, PORT);
        settle();
        assertEquals(1, transport.recordedRequests.size());

        // The periodic alarm still re-sends it so expiring destinations stay fresh.
        KeepADBPeriodicReport.arm(context);
        ShadowSystemClock.advanceBy(
                Duration.ofMillis(KeepADBPeriodicReport.Interval.DEFAULT.durationMs));
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
