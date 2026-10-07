package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import java.lang.reflect.Field;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

/**
 * #734: the persistent notification's compact/expanded status, the Keep-Alive action and the
 * webhook result lines, including the refresh that must happen with no visible Activity.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBNotificationWebhookStatusTest {

    private static final String URL = "http://fake.url/register";
    private static final String HOST = "192.168.1.50";
    private static final int PORT = 39123;
    private static final Pattern CLOCK_WITH_SECONDS = Pattern.compile("\\d{1,2}:\\d{2}:\\d{2}");

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = org.robolectric.RuntimeEnvironment.getApplication();

    @Rule
    public final KeepADBRegisterClientResetRule registerClientResetRule =
            new KeepADBRegisterClientResetRule();

    @Before
    public void setUp() throws Exception {
        shadowOf((Application) context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
        KeepADBRegisterClient.resetHttpTransport();
        KeepADBRegisterClient.resetForTesting();
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(true));
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        setStatic("currentHost", HOST);
        setStatic("currentPort", PORT);
    }

    @After
    public void tearDown() throws Exception {
        KeepADBRegisterClient.clearRegisterStateListener();
        KeepADBRegisterClient.resetHttpTransport();
        KeepADBRegisterClient.resetForTesting();
        KeepADBEndpointCoordinator.resetForTesting();
        setStatic("currentHost", null);
        setStatic("currentPort", 0);
        setStatic("endpointListener", null);
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADB.resetForTesting();
    }

    // --- webhook lines ----------------------------------------------------------------------

    @Test
    public void successShowsExactTimeToTheSecond() {
        enableWebhook();
        KeepADBPreferences.setWebhookReportSnapshot(context, URL, HOST + ":" + PORT,
                KeepADBPreferences.WEBHOOK_STATUS_SUCCESS, true);

        String text = bigText(postCard());

        long at = KeepADBPreferences.getWebhookLastSuccessAt(context);
        assertTrue(text, text.contains(context.getString(R.string.notification_text_webhook_synced, format(at))));
        assertTrue("time must carry seconds: " + text, CLOCK_WITH_SECONDS.matcher(text).find());
    }

    @Test
    public void failureAfterSuccessShowsErrorAndOnlyLastSuccessfulSeparately() {
        enableWebhook();
        KeepADBPreferences.setWebhookReportSnapshot(context, URL, HOST + ":" + PORT,
                KeepADBPreferences.WEBHOOK_STATUS_SUCCESS, true);
        long okAt = KeepADBPreferences.getWebhookLastSuccessAt(context);
        KeepADBPreferences.setWebhookLastReportStatus(context, KeepADBPreferences.WEBHOOK_STATUS_FAILED);

        String text = bigText(postCard());

        assertTrue(text, text.contains(context.getString(R.string.notification_text_webhook_failed)));
        assertTrue(text, text.contains(context.getString(
                R.string.notification_text_webhook_last_success, format(okAt))));
        assertFalse("a failed attempt must never read as a sync: " + text,
                text.contains(context.getString(R.string.notification_text_webhook_synced, format(okAt))));
    }

    @Test
    public void existingInstallWithoutSuccessKeyFallsBackToReportedTimeWhenEndpointStored() {
        enableWebhook();
        long legacyAt = 1_700_000_000_000L;
        // Legacy state: no register_webhook_last_success_at, only the old shared timestamp.
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit()
                .putLong("register_webhook_last_reported", legacyAt).commit();
        assertFalse(context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE)
                .contains("register_webhook_last_success_at"));
        KeepADBPreferences.setWebhookLastReportedEndpoint(context, HOST + ":" + PORT);
        KeepADBPreferences.setWebhookLastReportStatus(context, KeepADBPreferences.WEBHOOK_STATUS_SUCCESS);

        assertEquals(legacyAt, KeepADBPreferences.getWebhookLastSuccessAt(context));
        String text = bigText(postCard());
        assertTrue(text, text.contains(context.getString(
                R.string.notification_text_webhook_synced, format(legacyAt))));
    }

    @Test
    public void failureWithoutAnySuccessClaimsNoSuccessAndNoTime() {
        enableWebhook();
        KeepADBPreferences.setWebhookLastReportStatus(context, KeepADBPreferences.WEBHOOK_STATUS_FAILED);

        String text = bigText(postCard());

        assertTrue(text, text.contains(context.getString(R.string.notification_text_webhook_failed)));
        assertFalse(text, text.contains(context.getString(R.string.notification_text_webhook_synced, "")));
        assertFalse(text, text.contains(context.getString(R.string.notification_text_webhook_last_success, "")));
        assertFalse("no time may appear without a success: " + text, CLOCK_WITH_SECONDS.matcher(text).find());
    }

    @Test
    public void deregistrationTimeIsNeverPresentedAsLastSuccess() {
        enableWebhook();
        // Only a deregistration stamped the shared timestamp; a later failure must not reuse it.
        KeepADBPreferences.setWebhookReportSnapshot(context, null, null,
                KeepADBPreferences.WEBHOOK_STATUS_DEREGISTERED, true);
        KeepADBPreferences.setWebhookLastReportStatus(context, KeepADBPreferences.WEBHOOK_STATUS_FAILED);

        String text = bigText(postCard());

        assertTrue(text, text.contains(context.getString(R.string.notification_text_webhook_failed)));
        assertFalse(text, CLOCK_WITH_SECONDS.matcher(text).find());
    }

    @Test
    public void enabledWithoutAnyAttemptClaimsNoSuccess() {
        enableWebhook();

        String text = bigText(postCard());

        assertTrue(text, text.contains(context.getString(R.string.notification_text_webhook_none_yet)));
        assertFalse(text, text.contains(context.getString(R.string.notification_text_webhook_synced, "")));
    }

    @Test
    public void disabledWebhookShowsNoWebhookLineEvenWithStoredResults() {
        KeepADBPreferences.setRegisterWebhookUrl(context, URL);
        KeepADBPreferences.setRegisterWebhookEnabled(context, false);
        KeepADBPreferences.setWebhookReportSnapshot(context, URL, HOST + ":" + PORT,
                KeepADBPreferences.WEBHOOK_STATUS_SUCCESS, true);

        String text = bigText(postCard());

        assertFalse(text, text.contains("Webhook"));
        assertTrue(text, text.contains(context.getString(R.string.notification_text_keepalive_on)));
    }

    // --- refresh from the callback, no Activity ---------------------------------------------

    @Test
    public void webhookResultUpdatesTheCardWithoutAnyVisibleActivityOrListener() throws Exception {
        enableWebhook();
        postCard();
        // No RegisterStateListener is registered: that is the state with the UI not visible.
        KeepADBFakeHttpTransport transport = new KeepADBFakeHttpTransport();
        KeepADBRegisterClient.setHttpTransport(transport);

        KeepADBRegisterClient.updateEndpointAsync(context, HOST, PORT);
        waitUntil(() -> {
            ShadowLooper.idleMainLooper();
            return bigText(currentNotification()).contains(
                    context.getString(R.string.notification_text_webhook_synced, ""));
        });

        KeepADBFakeHttpTransport failing = new KeepADBFakeHttpTransport();
        failing.setPostSuccess(false);
        KeepADBRegisterClient.setHttpTransport(failing);
        KeepADBRegisterClient.updateEndpointAsync(context, HOST, PORT + 1);
        waitUntil(() -> {
            ShadowLooper.idleMainLooper();
            return bigText(currentNotification()).contains(
                    context.getString(R.string.notification_text_webhook_failed));
        });
        assertTrue(bigText(currentNotification()).contains(
                context.getString(R.string.notification_text_webhook_last_success, "")));
    }

    @Test
    public void callbackNeverCreatesOrReplacesAnotherNotification() throws Exception {
        enableWebhook();
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        KeepADBFakeHttpTransport transport = new KeepADBFakeHttpTransport();
        KeepADBRegisterClient.setHttpTransport(transport);

        // Nothing posted: the result must not conjure a notification.
        KeepADBRegisterClient.updateEndpointAsync(context, HOST, PORT);
        KeepADBRegisterClient.awaitIdleForTesting(3000);
        ShadowLooper.idleMainLooper();
        assertNull(shadowOf(manager).getNotification(KeepADBNotification.NOTIFICATION_ID));

        // A placeholder is posted: it must stay a placeholder.
        KeepADBNotification.renderSearching(context);
        String searching = context.getString(R.string.notification_title_searching,
                context.getString(R.string.app_name));
        KeepADBRegisterClient.updateEndpointAsync(context, HOST, PORT + 1);
        KeepADBRegisterClient.awaitIdleForTesting(3000);
        ShadowLooper.idleMainLooper();
        assertEquals(searching, currentNotification().extras.getString(Notification.EXTRA_TITLE));

        // Without the notification permission the refresh is a quiet no-op.
        postCard();
        shadowOf((Application) context).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
        KeepADBNotification.refreshIfActive(context);
    }

    // --- privacy ----------------------------------------------------------------------------

    @Test
    public void detailsOffHidesPortAndIpAndPublicVersionCarriesNoWebhookOrEndpoint() {
        enableWebhook();
        KeepADBPreferences.setWebhookReportSnapshot(context, URL, HOST + ":" + PORT,
                KeepADBPreferences.WEBHOOK_STATUS_SUCCESS, true);
        assertFalse(KeepADBPreferences.isNotificationDetailsEnabled(context));

        Notification notification = postCard();

        KeepADBNotificationTextScan.assertMentionsNone(notification,
                String.valueOf(PORT), HOST, "fake.url");
        assertNotNull(notification.publicVersion);
        String publicText = String.valueOf(notification.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT));
        assertFalse(publicText, publicText.contains("Webhook"));
        assertNull("lock-screen copy must not offer actions",
                notification.publicVersion.actions);
    }

    @Test
    public void detailsOnShowsPortAndIpInCompactLine() {
        KeepADBPreferences.setNotificationDetailsEnabled(context, true);

        Notification notification = postCard();

        String compact = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString();
        assertTrue(compact.contains(String.valueOf(PORT)));
        assertTrue(compact.contains(HOST));
    }

    // --- Keep-Alive action ------------------------------------------------------------------

    @Test
    public void keepAliveActionChangesRealStateAndTheCardFollows() {
        Notification before = postCard();
        assertTrue(bigText(before).contains(context.getString(R.string.notification_text_keepalive_on)));
        Notification.Action action = actionWithTitle(before,
                context.getString(R.string.notification_action_keepalive_off));
        assertEquals(KeepADBReceiver.ACTION_TOGGLE_KEEP_ALIVE,
                shadowOf(action.actionIntent).getSavedIntent().getAction());

        new KeepADBReceiver().onReceive(context, new Intent(KeepADBReceiver.ACTION_TOGGLE_KEEP_ALIVE));

        assertFalse("the real setting must have flipped", KeepADBPreferences.isKeepAliveEnabled(context));
        Notification after = currentNotification();
        assertTrue(bigText(after).contains(context.getString(R.string.notification_text_keepalive_off)));
        assertNotNull(actionWithTitle(after, context.getString(R.string.notification_action_keepalive_on)));
        assertNotNull("the Wifi-ADB action stays",
                actionWithTitle(after, context.getString(R.string.notification_action_disable)));

        new KeepADBReceiver().onReceive(context, new Intent(KeepADBReceiver.ACTION_TOGGLE_KEEP_ALIVE));

        assertTrue(KeepADBPreferences.isKeepAliveEnabled(context));
        assertTrue(bigText(currentNotification()).contains(
                context.getString(R.string.notification_text_keepalive_on)));
    }

    // --- helpers ----------------------------------------------------------------------------

    private void enableWebhook() {
        KeepADBPreferences.setRegisterWebhookUrl(context, URL);
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
    }

    private Notification postCard() {
        KeepADBEndpointCoordinator.refresh(context);
        return currentNotification();
    }

    private Notification currentNotification() {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        Notification notification = shadowOf(manager).getNotification(KeepADBNotification.NOTIFICATION_ID);
        assertNotNull("notification must be posted", notification);
        return notification;
    }

    private static String bigText(Notification notification) {
        return String.valueOf(notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
    }

    private String format(long millis) {
        java.text.DateFormat format = java.text.DateFormat.getDateTimeInstance(
                java.text.DateFormat.MEDIUM, java.text.DateFormat.MEDIUM,
                context.getResources().getConfiguration().getLocales().get(0));
        return format.format(new java.util.Date(millis));
    }

    private static Notification.Action actionWithTitle(Notification notification, String title) {
        if (notification.actions != null) {
            for (Notification.Action action : notification.actions) {
                if (title.contentEquals(action.title)) return action;
            }
        }
        return null;
    }

    private static void waitUntil(Callable<Boolean> condition) throws Exception {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(condition.call())) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Condition timed out");
    }

    private static void setStatic(String fieldName, Object value) throws Exception {
        Field field = KeepADBEndpointCoordinator.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, value);
    }

    /**
     * #793: a test that calls the reset itself stays green if the {@code @Rule} field is deleted.
     * This pins that {@link KeepADBRegisterClientResetRule} is really applied to the class.
     */
    @Test
    public void theRegisterClientResetRuleIsAppliedToThisClass() {
        boolean applied = false;
        for (java.lang.reflect.Field field : KeepADBNotificationWebhookStatusTest.class.getFields()) {
            if (field.getType() == KeepADBRegisterClientResetRule.class
                    && field.isAnnotationPresent(org.junit.Rule.class)) {
                applied = true;
            }
        }
        org.junit.Assert.assertTrue(
                "KeepADBRegisterClientResetRule must be a public @Rule field", applied);
    }
}
