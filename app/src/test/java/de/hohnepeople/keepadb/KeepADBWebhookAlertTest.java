package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import androidx.test.core.app.ApplicationProvider;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlarmManager;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowNotificationManager;

/**
 * #868: at most two warnings per continuous failure episode; #893: text, actions and tap target.
 * #870: the next check is an allow-while-idle alarm, and only a posted alert counts.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBWebhookAlertTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private static final String URL = "http://register.example/register/dev1";
    private static final String HOST = "192.168.1.10";
    private static final int PORT = 41000;
    private static final long START = 1_800_000_000_000L;

    private Context context;
    private KeepADBFakeHttpTransport transport;
    private ShadowNotificationManager notifications;
    private long now;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        ShadowAlarmManager.reset();
        Shadows.shadowOf((Application) context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        clearPreferences();
        KeepADBRegisterClient.resetForTesting();
        KeepADB.resetForTesting();
        KeepADBPreferences.setRegisterWebhookUrl(context, URL);
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        // No retries: each test drives the failures it needs, so the timing is the episode's only.
        KeepADBPreferences.setWebhookRetryCount(context, 0);
        transport = new KeepADBFakeHttpTransport();
        transport.setPostSuccess(false);
        KeepADBRegisterClient.setHttpTransport(transport);
        KeepADBRegisterClient.setCoordinatorEndpointForTesting("192.168.1.10:41000");
        now = START;
        KeepADBWebhookAlert.setNowForTesting(now);
        notifications = Shadows.shadowOf(context.getSystemService(NotificationManager.class));
    }

    @After
    public void tearDown() {
        KeepADBRegisterClient.awaitIdleForTesting(3000);
        clearPreferences();
        KeepADBRegisterClient.resetForTesting();
        KeepADB.resetForTesting();
    }

    private void clearPreferences() {
        Context ctx = ApplicationProvider.getApplicationContext();
        ctx.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
    }

    /** Moves the alert clock and the main looper together and delivers a due alert alarm. */
    private void advance(long ms) {
        now += ms;
        KeepADBWebhookAlert.setNowForTesting(now);
        ShadowLooper.idleMainLooper(ms, TimeUnit.MILLISECONDS);
        assertTrue(KeepADBRegisterClient.awaitIdleForTesting(3000));
        ShadowLooper.idleMainLooper();
        ShadowAlarmManager.ScheduledAlarm alarm = nextAlarm();
        if (alarm != null && alarm.getTriggerAtMs() <= SystemClock.elapsedRealtime()) {
            fireAlarm();
        }
    }

    private ShadowAlarmManager.ScheduledAlarm nextAlarm() {
        return Shadows.shadowOf(context.getSystemService(AlarmManager.class)).peekNextScheduledAlarm();
    }

    /** What AlarmManager delivers: the receiver, on the main thread, without any posted callback. */
    private void fireAlarm() {
        new KeepADBReceiver().onReceive(context,
                new Intent(KeepADBReceiver.ACTION_WEBHOOK_ALERT_CHECK));
    }

    /** A failed report for a new endpoint each time, so none of them joins an exhausted series. */
    private int port = PORT;

    private void failReport() {
        KeepADBRegisterClient.setCoordinatorEndpointForTesting(HOST + ":" + port);
        KeepADBRegisterClient.updateEndpointAsync(context, HOST, port++);
        assertTrue(KeepADBRegisterClient.awaitIdleForTesting(3000));
        ShadowLooper.idleMainLooper();
    }

    private void succeedReport() {
        transport.setPostSuccess(true);
        failReport();
        transport.setPostSuccess(false);
    }

    private Notification alert() {
        return notifications.getNotification(KeepADBWebhookAlert.NOTIFICATION_ID);
    }

    private int alertsShown() {
        return KeepADBPreferences.getWebhookAlertsShown(context);
    }

    @Test
    public void firstWarningAfterOneMinuteAndOneReminderAfterAnHour() {
        failReport();
        assertNull("no alert for a single fresh failure", alert());

        advance(KeepADBWebhookAlert.FIRST_ALERT_AFTER_MS - 1);
        assertNull(alert());
        advance(1);
        assertNotNull("first warning after one minute", alert());
        assertEquals(1, alertsShown());

        advance(KeepADBWebhookAlert.REMINDER_AFTER_MS - KeepADBWebhookAlert.FIRST_ALERT_AFTER_MS - 1);
        assertEquals(1, alertsShown());
        advance(1);
        assertEquals("reminder after one hour", 2, alertsShown());
    }

    @Test
    public void staysQuietAfterTheReminderUntilASuccess() {
        failReport();
        advance(KeepADBWebhookAlert.REMINDER_AFTER_MS);
        assertEquals(1, notifications.size());
        int shown = alertsShown();
        assertTrue(shown <= KeepADBWebhookAlert.MAX_ALERTS_PER_EPISODE);

        context.getSystemService(NotificationManager.class).cancelAll();
        for (int i = 0; i < 5; i++) {
            failReport();
            advance(KeepADBWebhookAlert.REMINDER_AFTER_MS);
        }
        assertNull("no third alert in the same episode", alert());
        assertEquals(shown, alertsShown());
    }

    @Test
    public void reusesOneNotificationForTheEpisode() {
        failReport();
        advance(KeepADBWebhookAlert.FIRST_ALERT_AFTER_MS);
        advance(KeepADBWebhookAlert.REMINDER_AFTER_MS);
        assertEquals("one notification id, updated in place", 1, notifications.size());
    }

    @Test
    public void successEndsTheEpisodeAndANewFailureStartsAFreshOne() {
        failReport();
        advance(KeepADBWebhookAlert.REMINDER_AFTER_MS);
        assertNotNull(alert());

        succeedReport();
        assertNull("success removes the warning", alert());
        assertEquals(0L, KeepADBPreferences.getWebhookFailureSince(context));

        failReport();
        advance(KeepADBWebhookAlert.FIRST_ALERT_AFTER_MS);
        assertNotNull("a new episode warns again", alert());
        assertEquals(1, alertsShown());
    }

    @Test
    public void disablingSyncEndsTheEpisodeWithoutAWarning() {
        failReport();
        KeepADBRegisterClient.unregisterAndDisableAsync(context);
        KeepADBPreferences.setRegisterWebhookEnabled(context, false);
        assertTrue(KeepADBRegisterClient.awaitIdleForTesting(3000));
        advance(KeepADBWebhookAlert.REMINDER_AFTER_MS);
        assertNull(alert());
        assertEquals(0L, KeepADBPreferences.getWebhookFailureSince(context));
    }

    /** #893: the text explains state, timing and next step without claiming an outage. */
    @Test
    public void textNamesTheDisruptionTheLastSuccessAndTheNextStep() {
        Context english = KeepADBLocaleHelper.wrapContext(context);
        KeepADBPreferences.setWebhookFailureEpisode(context, START, 0);

        String never = KeepADBWebhookAlert.text(english);
        assertTrue(never.startsWith(english.getString(R.string.webhook_alert_text_intro)));
        assertTrue(never, never.contains("no successful report yet"));
        assertTrue(never.contains(english.getString(R.string.webhook_alert_text_recovery)));
        assertTrue(never.endsWith(english.getString(R.string.webhook_alert_text_vpn)));

        KeepADBPreferences.setWebhookReportSnapshot(context, URL, "192.168.1.10:41000",
                KeepADBPreferences.WEBHOOK_STATUS_SUCCESS, true);
        String since = KeepADBWebhookAlert.text(english);
        assertTrue(since, since.contains("Disrupted since ") && since.contains(" · last reported "));

        for (String text : new String[] {never, since}) {
            String lower = text.toLowerCase(Locale.ROOT);
            assertFalse("must not claim an outage: " + text, lower.contains("is down"));
            assertFalse("must not claim unreachability: " + text, lower.contains("unreachable"));
            assertFalse("must name no endpoint: " + text, text.contains(HOST));
        }
    }

    /** #893: system time, two actions, a tap that keeps the alert and opens the history. */
    @Test
    public void alertShowsItsTimeKeepsOnTapAndOffersBothActions() {
        failReport();
        advance(KeepADBWebhookAlert.FIRST_ALERT_AFTER_MS);
        Notification posted = alert();
        assertNotNull(posted);
        assertTrue("the arrival time is shown", posted.extras.getBoolean(Notification.EXTRA_SHOW_WHEN));
        assertEquals("a tap must not dismiss a problem that is still there", 0,
                posted.flags & Notification.FLAG_AUTO_CANCEL);
        assertEquals(2, posted.actions.length);
        Intent tap = Shadows.shadowOf(posted.contentIntent).getSavedIntent();
        assertEquals(ReportHistoryActivity.class.getName(), tap.getComponent().getClassName());
        Intent tips = Shadows.shadowOf(posted.actions[0].actionIntent).getSavedIntent();
        assertTrue(tips.getBooleanExtra(SettingsActivity.EXTRA_FOCUS_WEBHOOK_VPN_HINT, false));
        Intent reportNow = Shadows.shadowOf(posted.actions[1].actionIntent).getSavedIntent();
        assertEquals(KeepADBReceiver.ACTION_WEBHOOK_REPORT_NOW, reportNow.getAction());
    }

    /** #893 (Codex review): a retry waiting for its timer is sent at once, not joined. */
    @Test
    public void reportNowSendsAScheduledRetryImmediately() {
        KeepADBPreferences.setWebhookRetryCount(context, 4);
        failReport();
        int before = transport.recordedRequests.size();

        new KeepADBReceiver().onReceive(context, new Intent(KeepADBReceiver.ACTION_WEBHOOK_REPORT_NOW));
        ShadowLooper.idleMainLooper();
        assertTrue(KeepADBRegisterClient.awaitIdleForTesting(3000));

        assertEquals("the pending retry goes out without waiting for its delay", before + 1,
                transport.recordedRequests.size());
    }

    /** #893 (Codex review): a debounced resync waiting next to the retry adds no second POST. */
    @Test
    public void reportNowDropsAWaitingDebouncedResync() {
        KeepADBPreferences.setWebhookRetryCount(context, 4);
        failReport();
        KeepADBRegisterClient.requestResyncAsync(context);
        int before = transport.recordedRequests.size();
        transport.setPostSuccess(true);

        new KeepADBReceiver().onReceive(context, new Intent(KeepADBReceiver.ACTION_WEBHOOK_REPORT_NOW));
        ShadowLooper.idleMainLooper();
        assertTrue(KeepADBRegisterClient.awaitIdleForTesting(3000));
        ShadowLooper.idleMainLooper(60, TimeUnit.SECONDS);
        assertTrue(KeepADBRegisterClient.awaitIdleForTesting(3000));

        assertEquals("one tap, one request", before + 1, transport.recordedRequests.size());
    }

    /** #893: "Report now" sends one report, and its success clears the alert. */
    @Test
    public void reportNowSendsOneReportAndItsSuccessClearsTheAlert() {
        failReport();
        advance(KeepADBWebhookAlert.FIRST_ALERT_AFTER_MS);
        assertNotNull(alert());
        int before = transport.recordedRequests.size();
        transport.setPostSuccess(true);

        new KeepADBReceiver().onReceive(context, new Intent(KeepADBReceiver.ACTION_WEBHOOK_REPORT_NOW));
        assertTrue(KeepADBRegisterClient.awaitIdleForTesting(3000));
        ShadowLooper.idleMainLooper();

        assertEquals("exactly one request", before + 1, transport.recordedRequests.size());
        assertNull("success removes the warning", alert());
    }

    @Test
    public void lostEndpointEndsTheEpisode() {
        failReport();
        KeepADBRegisterClient.markUnavailableAsync(context);
        assertTrue(KeepADBRegisterClient.awaitIdleForTesting(3000));
        ShadowLooper.idleMainLooper();
        assertEquals(0L, KeepADBPreferences.getWebhookFailureSince(context));
        advance(KeepADBWebhookAlert.REMINDER_AFTER_MS);
        assertNull(alert());
    }

    @Test
    public void obsoleteRetryWithoutEndpointEndsTheEpisode() {
        KeepADBPreferences.setWebhookRetryCount(context, 4);
        failReport();
        assertTrue(KeepADBRegisterClient.hasPendingPostRetryForTesting());
        KeepADBRegisterClient.setCoordinatorEndpointForTesting("");
        advance(5_000L);
        assertEquals(0L, KeepADBPreferences.getWebhookFailureSince(context));
        advance(KeepADBWebhookAlert.REMINDER_AFTER_MS);
        assertNull(alert());
    }

    @Test
    public void clockMovedBackRestartsTheEpisodeInsteadOfStayingSilent() {
        failReport();
        now -= 10L * 60L * 1000L;
        KeepADBWebhookAlert.setNowForTesting(now);
        failReport();
        assertEquals(now, KeepADBPreferences.getWebhookFailureSince(context));
        advance(KeepADBWebhookAlert.FIRST_ALERT_AFTER_MS);
        assertNotNull(alert());
    }

    @Test
    public void nextCheckIsAnAllowWhileIdleAlarmOnElapsedRealtime() {
        long elapsedAtFailure = SystemClock.elapsedRealtime();
        failReport();
        ShadowAlarmManager.ScheduledAlarm alarm = nextAlarm();
        assertNotNull("the first warning is armed as an alarm", alarm);
        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, alarm.getType());
        assertTrue(alarm.isAllowWhileIdle());
        assertEquals(elapsedAtFailure + KeepADBWebhookAlert.FIRST_ALERT_AFTER_MS, alarm.getTriggerAtMs());

        advance(KeepADBWebhookAlert.FIRST_ALERT_AFTER_MS);
        assertEquals("then the reminder", SystemClock.elapsedRealtime()
                + KeepADBWebhookAlert.REMINDER_AFTER_MS - KeepADBWebhookAlert.FIRST_ALERT_AFTER_MS,
                nextAlarm().getTriggerAtMs());

        advance(KeepADBWebhookAlert.REMINDER_AFTER_MS);
        assertNull("nothing armed after the last alert of the episode", nextAlarm());
    }

    @Test
    public void reminderArrivesAfterDeepSleepWithoutTheMainLooper() {
        failReport();
        advance(KeepADBWebhookAlert.FIRST_ALERT_AFTER_MS);
        assertEquals(1, alertsShown());

        // Deep sleep: wall clock and elapsed realtime move on, uptime and the main looper do not.
        now += KeepADBWebhookAlert.REMINDER_AFTER_MS;
        KeepADBWebhookAlert.setNowForTesting(now);
        fireAlarm();

        assertEquals("the alarm alone delivers the reminder", 2, alertsShown());
    }

    @Test
    public void alarmAfterProcessDeathEvaluatesThePersistedEpisode() {
        failReport();
        // Process death: every in-memory field is gone, the episode survives in preferences.
        KeepADBRegisterClient.resetForTesting();
        KeepADBWebhookAlert.setNowForTesting(now + KeepADBWebhookAlert.FIRST_ALERT_AFTER_MS);
        fireAlarm();
        assertNotNull(alert());
        assertEquals(1, alertsShown());
    }

    @Test
    public void missingNotificationPermissionDoesNotConsumeTheAlertBudget() {
        Shadows.shadowOf((Application) context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        failReport();
        advance(KeepADBWebhookAlert.FIRST_ALERT_AFTER_MS);
        assertNull(alert());
        assertEquals("nothing posted, nothing counted", 0, alertsShown());
        ShadowAlarmManager.ScheduledAlarm alarm = nextAlarm();
        assertNotNull("no immediate re-check loop: the reminder is the next level", alarm);
        assertTrue(alarm.getTriggerAtMs() > SystemClock.elapsedRealtime());

        Shadows.shadowOf((Application) context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        KeepADBWebhookAlert.onAppResumed(context);
        ShadowLooper.idleMainLooper();

        assertNotNull("granted later in the same episode, the warning is shown", alert());
        assertEquals(1, alertsShown());
    }

    @Test
    public void permissionGrantedAfterTheReminderShowsOneAlert() {
        Shadows.shadowOf((Application) context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        failReport();
        advance(KeepADBWebhookAlert.FIRST_ALERT_AFTER_MS);
        advance(KeepADBWebhookAlert.REMINDER_AFTER_MS);
        assertEquals(0, alertsShown());
        assertNull("both levels are due, no timer is left", nextAlarm());

        Shadows.shadowOf((Application) context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        KeepADBWebhookAlert.onAppResumed(context);
        ShadowLooper.idleMainLooper();

        assertEquals(1, notifications.size());
        assertEquals(KeepADBWebhookAlert.MAX_ALERTS_PER_EPISODE, alertsShown());
    }

    @Test
    public void successCancelsTheArmedAlarm() {
        failReport();
        assertNotNull(nextAlarm());
        succeedReport();
        ShadowLooper.idleMainLooper();
        assertNull(nextAlarm());
    }
}
