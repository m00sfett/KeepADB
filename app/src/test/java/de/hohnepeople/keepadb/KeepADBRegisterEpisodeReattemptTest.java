package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

/**
 * #881: while a failure episode lasts and the retry series was given up on, the heartbeat sends
 * one report at most every 15 minutes -- a single attempt, not a new series, and nothing at all
 * without an episode.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBRegisterEpisodeReattemptTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private static final String URL = "http://register.example/register/dev1";
    private static final String HOST = "192.168.1.10";
    private static final int PORT = 41000;
    private static final long INTERVAL = KeepADBRegisterClient.EPISODE_REATTEMPT_INTERVAL_MS;

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
        KeepADBRegisterClient.setHttpTransport(transport);
        KeepADBRegisterClient.setCoordinatorEndpointForTesting(HOST + ":" + PORT);
        KeepADBRegisterClient.setEndpointProbeForTesting(endpoint -> true);
    }

    @After
    public void tearDown() {
        KeepADBRegisterClient.awaitIdleForTesting(3000);
        KeepADBEndpointCoordinator.resetForTesting();
        clearPreferences();
        KeepADBRegisterClient.resetForTesting();
        KeepADB.resetForTesting();
    }

    private void clearPreferences() {
        ApplicationProvider.getApplicationContext()
                .getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
    }

    private void advance(long ms) {
        ShadowLooper.idleMainLooper(ms, TimeUnit.MILLISECONDS);
        assertTrue(KeepADBRegisterClient.awaitIdleForTesting(3000));
        ShadowLooper.idleMainLooper();
    }

    private int posts() {
        int count = 0;
        for (KeepADBFakeHttpTransport.Request request : new java.util.ArrayList<>(transport.recordedRequests)) {
            if ("POST".equals(request.method)) count++;
        }
        return count;
    }

    private void heartbeat() {
        KeepADBRegisterClient.reattemptStaleEpisode(context);
        assertTrue(KeepADBRegisterClient.awaitIdleForTesting(3000));
        ShadowLooper.idleMainLooper();
    }

    /** One failed report with no retries left: an episode whose series is given up on. */
    private void giveUp() {
        transport.setPostSuccess(false);
        KeepADBPreferences.setWebhookRetryCount(context, 0);
        KeepADBRegisterClient.updateEndpointAsync(context, HOST, PORT);
        advance(0);
        assertTrue(KeepADBRegisterClient.isSeriesExhaustedForTesting());
        assertTrue(KeepADBPreferences.getWebhookFailureSince(context) > 0L);
        assertEquals(1, posts());
    }

    @Test
    public void nothingBeforeFifteenMinutesThenOneAttempt() {
        giveUp();
        advance(INTERVAL - 60_000L);
        heartbeat();
        assertEquals("not before the interval", 1, posts());

        advance(60_000L);
        heartbeat();
        assertEquals("one re-attempt after 15 min", 2, posts());
    }

    @Test
    public void aFailedReattemptIsASingleAttemptNotANewSeries() {
        KeepADBPreferences.setWebhookRetryCount(context, 4);
        transport.setPostSuccess(false);
        KeepADBRegisterClient.updateEndpointAsync(context, HOST, PORT);
        for (int i = 0; i < 6; i++) advance(60_000L);
        assertTrue(KeepADBRegisterClient.isSeriesExhaustedForTesting());
        int afterSeries = posts();

        advance(INTERVAL);
        heartbeat();
        for (int i = 0; i < 3; i++) advance(60_000L);
        heartbeat();

        assertEquals("exactly one request per interval", afterSeries + 1, posts());
        assertTrue(KeepADBRegisterClient.isSeriesExhaustedForTesting());
    }

    @Test
    public void aSuccessfulReattemptEndsTheEpisodeAndTheReattempts() {
        giveUp();
        transport.setPostSuccess(true);
        advance(INTERVAL);
        heartbeat();
        assertEquals(2, posts());
        assertEquals(0L, KeepADBPreferences.getWebhookFailureSince(context));

        advance(INTERVAL);
        heartbeat();
        assertEquals("no episode, no re-attempt", 2, posts());
    }

    /** Codex review: an endpoint that no longer answers is not reported by a re-attempt. */
    @Test
    public void unreachableCachedEndpointIsNotReported() {
        giveUp();
        KeepADBRegisterClient.setEndpointProbeForTesting(endpoint -> false);

        advance(INTERVAL);
        heartbeat();
        assertEquals("no POST of a stale endpoint", 1, posts());
        assertTrue("the episode stays given up on", KeepADBRegisterClient.isSeriesExhaustedForTesting());

        KeepADBRegisterClient.setEndpointProbeForTesting(endpoint -> true);
        advance(INTERVAL);
        heartbeat();
        assertEquals("the next interval tries again", 2, posts());
    }

    @Test
    public void withoutAnEpisodeOrWithSyncOffNothingIsSent() {
        KeepADBRegisterClient.updateEndpointAsync(context, HOST, PORT);
        advance(0);
        assertEquals(1, posts());
        advance(INTERVAL);
        heartbeat();
        assertEquals("a healthy webhook is never polled", 1, posts());

        transport.setPostSuccess(false);
        KeepADBPreferences.setWebhookRetryCount(context, 0);
        KeepADBRegisterClient.requestResyncNow(context);
        advance(0);
        assertEquals(2, posts());
        assertTrue(KeepADBPreferences.getWebhookFailureSince(context) > 0L);
        KeepADBPreferences.setRegisterWebhookEnabled(context, false);
        advance(INTERVAL);
        heartbeat();
        assertEquals(2, posts());
    }
}
