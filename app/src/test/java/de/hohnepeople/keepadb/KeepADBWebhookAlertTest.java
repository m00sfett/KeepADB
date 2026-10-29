package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
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
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowNotificationManager;

/** #868: at most two warnings per continuous failure episode, with truthful freshness wording. */
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

    /** Moves the alert clock and the main looper together. */
    private void advance(long ms) {
        now += ms;
        KeepADBWebhookAlert.setNowForTesting(now);
        ShadowLooper.idleMainLooper(ms, TimeUnit.MILLISECONDS);
        assertTrue(KeepADBRegisterClient.awaitIdleForTesting(3000));
        ShadowLooper.idleMainLooper();
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

    @Test
    public void freshnessTextNamesOnlyWhatWasObserved() {
        Context english = KeepADBLocaleHelper.wrapContext(context);
        String scope = english.getString(R.string.webhook_alert_text_scope);

        String never = KeepADBWebhookAlert.text(english);
        assertTrue(never.startsWith(english.getString(R.string.webhook_alert_text_never)));
        assertTrue(never.endsWith(scope));

        KeepADBPreferences.setWebhookReportSnapshot(context, URL, "192.168.1.10:41000",
                KeepADBPreferences.WEBHOOK_STATUS_SUCCESS, true);
        String since = KeepADBWebhookAlert.text(english);
        String marked = english.getString(R.string.webhook_alert_text_since, "\u0000");
        assertTrue(since, since.startsWith(marked.substring(0, marked.indexOf('\u0000'))));
        assertFalse(since.startsWith(english.getString(R.string.webhook_alert_text_never)));

        KeepADBPreferences.setWebhookFreshnessWarningEnabled(context, false);
        String failed = KeepADBWebhookAlert.text(english);
        assertTrue(failed.startsWith(english.getString(R.string.webhook_alert_text_failed)));
        assertTrue(failed.endsWith(scope));

        for (String text : new String[] {never, since, failed}) {
            String lower = text.toLowerCase(Locale.ROOT);
            assertFalse("must not claim an outage: " + text,
                    lower.contains("is down") && !lower.contains("whether"));
            assertFalse("must not claim unreachability: " + text, lower.contains("unreachable"));
        }
    }

    @Test
    public void freshnessWarningDefaultsToOn() {
        assertTrue(KeepADBPreferences.isWebhookFreshnessWarningEnabled(context));
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
}
