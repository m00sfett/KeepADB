package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.core.app.ApplicationProvider;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

/** #866/#960: bounded, fixed retry series for a failed endpoint-report POST. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBRegisterRetryTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private static final String URL = "http://register.example/register/dev1";
    private static final String HOST = "192.168.1.10";
    private static final int PORT = 41000;
    private static final String ENDPOINT = "192.168.1.10:41000";

    private Context context;
    private KeepADBFakeHttpTransport transport;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        clearPreferences();
        KeepADBRegisterClient.resetForTesting();
        KeepADB.resetForTesting();
        KeepADBPreferences.setRegisterWebhookUrl(context, URL);
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        transport = new KeepADBFakeHttpTransport();
        transport.setPostSuccess(false);
        KeepADBRegisterClient.setHttpTransport(transport);
        KeepADBRegisterClient.setCoordinatorEndpointForTesting(ENDPOINT);
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

    /** Advances the main looper by {@code ms} and lets any request it dispatched finish. */
    private void advance(long ms) {
        ShadowLooper.idleMainLooper(ms, TimeUnit.MILLISECONDS);
        assertTrue(KeepADBRegisterClient.awaitIdleForTesting(3000));
    }

    private int posts() {
        int count = 0;
        for (KeepADBFakeHttpTransport.Request request : new java.util.ArrayList<>(transport.recordedRequests)) {
            if ("POST".equals(request.method)) count++;
        }
        return count;
    }

    private void reportOnce() {
        KeepADBRegisterClient.updateEndpointAsync(context, HOST, PORT);
        assertTrue(KeepADBRegisterClient.awaitIdleForTesting(3000));
    }

    @Test
    public void theFixedSeriesRetriesFourTimesWithItsDelaysThenStops() {
        reportOnce();
        assertEquals(1, posts());

        long[] delays = {5_000L, 15_000L, 30_000L, 60_000L};
        for (int i = 0; i < delays.length; i++) {
            advance(delays[i] - 1);
            assertEquals("retry " + (i + 1) + " must not run early", i + 1, posts());
            advance(1);
            assertEquals("retry " + (i + 1) + " must run on time", i + 2, posts());
        }

        advance(10L * 60L * 1000L);
        assertEquals("no fifth retry", 5, posts());
        assertFalse(KeepADBRegisterClient.hasPendingPostRetryForTesting());
        assertTrue(KeepADBRegisterClient.isSeriesExhaustedForTesting());
    }

    /**
     * #960: an installation that stored "fast" and one retry with an older version gets the fixed
     * series after the update, and the two keys are deleted at the first start. Red without the
     * cleanup: the keys stay in the preferences.
     */
    @Test
    public void storedProfileAndCountOfAnOlderVersionAreDeletedAndTheSeriesIsTheFixedOne() {
        SharedPreferences prefs = context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE);
        prefs.edit()
                .putString("register_webhook_retry_profile", "fast")
                .putInt("register_webhook_retry_count", 1)
                .commit();

        ((KeepADBApplication) context).onCreate();

        assertFalse(prefs.contains("register_webhook_retry_profile"));
        assertFalse(prefs.contains("register_webhook_retry_count"));
        assertTrue("the other settings stay", KeepADBPreferences.isRegisterWebhookEnabled(context));
        assertEquals(URL, KeepADBPreferences.getRegisterWebhookUrl(context));

        reportOnce();
        assertEquals(1, posts());
        advance(4_999L);
        assertEquals("not at the old fast delay of 2 s, nor early", 1, posts());
        advance(1L);
        assertEquals("first retry after 5 s", 2, posts());
        advance(15_000L);
        advance(30_000L);
        advance(60_000L);
        assertEquals("four retries, not one", 5, posts());
        advance(10L * 60L * 1000L);
        assertEquals(5, posts());
        assertTrue(KeepADBRegisterClient.isSeriesExhaustedForTesting());
    }

    @Test
    public void theDelaysOfTheSeriesAre5_15_30_60Seconds() {
        assertEquals(4, KeepADBPreferences.WEBHOOK_MAX_RETRIES);
        assertEquals(5_000L, KeepADBPreferences.webhookRetryDelayMs(1));
        assertEquals(15_000L, KeepADBPreferences.webhookRetryDelayMs(2));
        assertEquals(30_000L, KeepADBPreferences.webhookRetryDelayMs(3));
        assertEquals(60_000L, KeepADBPreferences.webhookRetryDelayMs(4));
    }

    @Test
    public void zeroRetriesSendsOnlyTheFirstAttempt() {
        KeepADBRegisterClient.setRetryBudgetForTesting(0);
        reportOnce();
        assertFalse(KeepADBRegisterClient.hasPendingPostRetryForTesting());
        advance(10L * 60L * 1000L);
        assertEquals(1, posts());
    }

    @Test
    public void successfulRetryEndsTheSeriesAndRecordsSuccess() {
        reportOnce();
        transport.setPostSuccess(true);
        advance(5_000L);
        assertEquals(2, posts());
        assertFalse(KeepADBRegisterClient.hasPendingPostRetryForTesting());
        assertEquals(0, KeepADBRegisterClient.getPostRetryNumberForTesting());
        assertEquals(KeepADBPreferences.WEBHOOK_STATUS_SUCCESS,
                KeepADBPreferences.getWebhookLastReportStatus(context));
        assertTrue(KeepADBPreferences.getWebhookLastSuccessAt(context) > 0L);
        advance(10L * 60L * 1000L);
        assertEquals(2, posts());
    }

    @Test
    public void failedAttemptsNeverStoreASuccessTime() {
        reportOnce();
        advance(5_000L);
        assertEquals(0L, KeepADBPreferences.getWebhookLastSuccessAt(context));
    }

    @Test
    public void repeatedCallsForTheSameEndpointNeitherAddRequestsNorPostponeTheRetry() {
        reportOnce();
        advance(3_000L);
        KeepADBRegisterClient.updateEndpointAsync(context, HOST, PORT);
        KeepADBRegisterClient.updateEndpointAsync(context, HOST, PORT);
        assertTrue(KeepADBRegisterClient.awaitIdleForTesting(3000));
        assertEquals("joins the series", 1, posts());
        advance(2_000L);
        assertEquals("retry still runs 5s after the failure", 2, posts());
    }

    @Test
    public void exhaustedSeriesIsNotRestartedByARefreshButByANewEndpoint() {
        KeepADBRegisterClient.setRetryBudgetForTesting(0);
        reportOnce();
        assertTrue(KeepADBRegisterClient.isSeriesExhaustedForTesting());
        KeepADBRegisterClient.updateEndpointAsync(context, HOST, PORT);
        assertTrue(KeepADBRegisterClient.awaitIdleForTesting(3000));
        assertEquals(1, posts());

        KeepADBRegisterClient.setCoordinatorEndpointForTesting("192.168.1.10:41001");
        KeepADBRegisterClient.updateEndpointAsync(context, HOST, PORT + 1);
        assertTrue(KeepADBRegisterClient.awaitIdleForTesting(3000));
        assertEquals(2, posts());
    }

    @Test
    public void disablingSyncMakesTheScheduledRetryObsolete() {
        reportOnce();
        KeepADBPreferences.setRegisterWebhookEnabled(context, false);
        advance(10L * 60L * 1000L);
        assertEquals(1, posts());
        assertFalse(KeepADBRegisterClient.hasPendingPostRetryForTesting());
    }

    @Test
    public void unregisteringCancelsTheScheduledRetry() {
        reportOnce();
        assertTrue(KeepADBRegisterClient.hasPendingPostRetryForTesting());
        KeepADBRegisterClient.unregisterAndDisableAsync(context);
        assertFalse(KeepADBRegisterClient.hasPendingPostRetryForTesting());
        assertTrue(KeepADBRegisterClient.awaitIdleForTesting(3000));
        advance(10L * 60L * 1000L);
        assertEquals(1, posts());
    }

    @Test
    public void changedUrlMakesTheScheduledRetryObsolete() {
        reportOnce();
        KeepADBPreferences.setRegisterWebhookUrl(context, "http://register.example/register/dev2");
        advance(10L * 60L * 1000L);
        assertEquals(1, posts());
    }

    @Test
    public void invalidatedEndpointMakesTheScheduledRetryObsolete() {
        reportOnce();
        KeepADBRegisterClient.setCoordinatorEndpointForTesting("");
        advance(10L * 60L * 1000L);
        assertEquals(1, posts());
        assertFalse(KeepADBRegisterClient.hasPendingPostRetryForTesting());
    }

    @Test
    public void changedEndpointMakesTheScheduledRetryObsolete() {
        reportOnce();
        KeepADBRegisterClient.setCoordinatorEndpointForTesting("192.168.1.10:41009");
        advance(10L * 60L * 1000L);
        assertEquals(1, posts());
    }

    @Test
    public void markUnavailableCancelsTheScheduledRetry() {
        reportOnce();
        KeepADBRegisterClient.markUnavailableAsync(context);
        assertFalse(KeepADBRegisterClient.hasPendingPostRetryForTesting());
        assertTrue(KeepADBRegisterClient.awaitIdleForTesting(3000));
        advance(10L * 60L * 1000L);
        assertEquals(1, posts());
    }

    @Test
    public void repeatedCallWhileThePostIsRunningJoinsIt() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        transport.setPostSuccess(true);
        transport.setRequestCallback(request -> {
            if ("POST".equals(request.method)) {
                started.countDown();
                try {
                    release.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
            }
        });
        try {
            KeepADBRegisterClient.updateEndpointAsync(context, HOST, PORT);
            assertTrue(started.await(3, TimeUnit.SECONDS));
            KeepADBRegisterClient.updateEndpointAsync(context, HOST, PORT);
            KeepADBRegisterClient.updateEndpointAsync(context, HOST, PORT);
        } finally {
            release.countDown();
        }
        assertTrue(KeepADBRegisterClient.awaitIdleForTesting(3000));
        transport.setRequestCallback(null);
        assertEquals("at most one POST queued or running", 1, posts());
        assertEquals(ENDPOINT, KeepADBRegisterClient.getLastRegisteredEndpointForTesting());
    }
}
