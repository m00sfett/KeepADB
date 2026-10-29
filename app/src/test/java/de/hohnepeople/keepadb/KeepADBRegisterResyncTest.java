package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
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

/** #867: event-driven webhook resynchronization after Android network changes. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBRegisterResyncTest {

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
        KeepADBRegisterClient.setHttpTransport(transport);
        KeepADBRegisterClient.setCoordinatorEndpointForTesting(ENDPOINT);
    }

    @After
    public void tearDown() {
        transport.setRequestCallback(null);
        KeepADBRegisterClient.awaitIdleForTesting(3000);
        clearPreferences();
        KeepADBRegisterClient.resetForTesting();
        KeepADB.resetForTesting();
    }

    private void clearPreferences() {
        Context ctx = ApplicationProvider.getApplicationContext();
        ctx.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
    }

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

    private void registerSuccessfully() {
        KeepADBRegisterClient.updateEndpointAsync(context, HOST, PORT);
        assertTrue(KeepADBRegisterClient.awaitIdleForTesting(3000));
        assertEquals(ENDPOINT, KeepADBRegisterClient.getLastRegisteredEndpointForTesting());
        assertEquals(1, posts());
    }

    @Test
    public void networkEventReportsAnUnchangedEndpointAgain() {
        registerSuccessfully();
        // The unchanged endpoint alone is deduplicated ...
        KeepADBRegisterClient.updateEndpointAsync(context, HOST, PORT);
        assertTrue(KeepADBRegisterClient.awaitIdleForTesting(3000));
        assertEquals(1, posts());
        // ... a network event is not.
        KeepADBRegisterClient.requestResyncAsync(context);
        advance(KeepADBRegisterClient.RESYNC_DEBOUNCE_MS);
        assertEquals(2, posts());
    }

    @Test
    public void callbackBurstIsCoalescedIntoOneReport() {
        registerSuccessfully();
        for (int i = 0; i < 5; i++) {
            KeepADBRegisterClient.requestResyncAsync(context);
            advance(300L);
        }
        assertEquals("still settling", 1, posts());
        advance(KeepADBRegisterClient.RESYNC_DEBOUNCE_MS - 301L);
        assertEquals(1, posts());
        advance(1L);
        assertEquals("one report for the whole burst", 2, posts());
        advance(60_000L);
        assertEquals(2, posts());
    }

    @Test
    public void resyncJoinsARequestThatIsAlreadyInFlight() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        transport.setRequestCallback(request -> {
            if ("POST".equals(request.method)) {
                started.countDown();
                try {
                    release.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
            }
        });
        KeepADBRegisterClient.updateEndpointAsync(context, HOST, PORT);
        assertTrue(started.await(3, TimeUnit.SECONDS));
        try {
            KeepADBRegisterClient.requestResyncAsync(context);
            ShadowLooper.idleMainLooper(KeepADBRegisterClient.RESYNC_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
            assertFalse(KeepADBRegisterClient.hasPendingResyncForTesting());
        } finally {
            release.countDown();
        }
        assertTrue(KeepADBRegisterClient.awaitIdleForTesting(3000));
        assertEquals("no parallel request", 1, posts());
    }

    @Test
    public void resyncDoesNotPostponeOrDuplicateAScheduledRetry() {
        transport.setPostSuccess(false);
        KeepADBRegisterClient.updateEndpointAsync(context, HOST, PORT);
        assertTrue(KeepADBRegisterClient.awaitIdleForTesting(3000));
        assertTrue(KeepADBRegisterClient.hasPendingPostRetryForTesting());

        advance(1_000L);
        KeepADBRegisterClient.requestResyncAsync(context);
        advance(KeepADBRegisterClient.RESYNC_DEBOUNCE_MS);
        assertEquals("joins the scheduled retry", 1, posts());
        advance(2_000L);
        assertEquals("retry still 5s after the failure", 2, posts());
    }

    @Test
    public void resyncStartsANewSeriesAfterAnExhaustedOne() {
        transport.setPostSuccess(false);
        KeepADBPreferences.setWebhookRetryCount(context, 0);
        KeepADBRegisterClient.updateEndpointAsync(context, HOST, PORT);
        assertTrue(KeepADBRegisterClient.awaitIdleForTesting(3000));
        assertTrue(KeepADBRegisterClient.isSeriesExhaustedForTesting());

        KeepADBRegisterClient.requestResyncAsync(context);
        advance(KeepADBRegisterClient.RESYNC_DEBOUNCE_MS);
        assertEquals(2, posts());
    }

    @Test
    public void resyncNeedsEnabledSyncAndAnEndpoint() {
        KeepADBPreferences.setRegisterWebhookEnabled(context, false);
        KeepADBRegisterClient.requestResyncAsync(context);
        advance(KeepADBRegisterClient.RESYNC_DEBOUNCE_MS);
        assertEquals(0, posts());

        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        KeepADBRegisterClient.setCoordinatorEndpointForTesting("");
        KeepADBRegisterClient.requestResyncAsync(context);
        advance(KeepADBRegisterClient.RESYNC_DEBOUNCE_MS);
        assertEquals(0, posts());
    }

    @Test
    public void resyncDropsAnEndpointReadBeforeADisconnect() {
        registerSuccessfully();
        KeepADBRegisterClient.requestResyncAsync(context);
        // The disconnect lands between the endpoint read and the dispatch: model it by letting
        // the endpoint source bump the generation, as a concurrent markUnavailableAsync would.
        KeepADBRegisterClient.setCoordinatorEndpointForTesting(ENDPOINT);
        KeepADBRegisterClient.setBeforeResyncDispatchForTesting(
                KeepADBRegisterClient::bumpOpGenerationForTesting);
        advance(KeepADBRegisterClient.RESYNC_DEBOUNCE_MS);
        assertEquals("no stale report after the cleanup", 1, posts());
    }
}
