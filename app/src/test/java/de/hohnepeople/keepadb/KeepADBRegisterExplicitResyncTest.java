package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.widget.Button;
import android.widget.EditText;
import androidx.test.core.app.ApplicationProvider;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

/** #869: an explicit user action restarts an exhausted report series; ordinary refreshes do not. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBRegisterExplicitResyncTest {

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
        KeepADBEndpointCoordinator.resetForTesting();
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

    private void exhaustSeries() {
        transport.setPostSuccess(false);
        KeepADBPreferences.setWebhookRetryCount(context, 0);
        KeepADBRegisterClient.updateEndpointAsync(context, HOST, PORT);
        assertTrue(KeepADBRegisterClient.awaitIdleForTesting(3000));
        assertTrue(KeepADBRegisterClient.isSeriesExhaustedForTesting());
        assertEquals(1, posts());
    }

    @Test
    public void explicitRequestRestartsAnExhaustedSeriesAtOnce() {
        exhaustSeries();
        transport.setPostSuccess(true);

        KeepADBRegisterClient.requestResyncNow(context);
        assertTrue(KeepADBRegisterClient.awaitIdleForTesting(3000));

        assertEquals("no debounce: reported right away", 2, posts());
        assertEquals(ENDPOINT, KeepADBRegisterClient.getLastRegisteredEndpointForTesting());
    }

    @Test
    public void ordinaryRefreshesKeepJoiningTheExhaustedSeries() {
        exhaustSeries();
        for (int i = 0; i < 5; i++) {
            KeepADBRegisterClient.updateEndpointAsync(context, HOST, PORT);
            advance(60_000L);
        }
        assertEquals("no polling from refreshes", 1, posts());
        assertTrue(KeepADBRegisterClient.isSeriesExhaustedForTesting());
    }

    @Test
    public void explicitRequestJoinsAScheduledRetryWithoutPostponingIt() {
        transport.setPostSuccess(false);
        KeepADBRegisterClient.updateEndpointAsync(context, HOST, PORT);
        assertTrue(KeepADBRegisterClient.awaitIdleForTesting(3000));
        assertTrue(KeepADBRegisterClient.hasPendingPostRetryForTesting());

        advance(1_000L);
        KeepADBRegisterClient.requestResyncNow(context);
        assertTrue(KeepADBRegisterClient.awaitIdleForTesting(3000));
        assertEquals("joins the scheduled retry", 1, posts());
        advance(4_000L);
        assertEquals("retry still 5s after the failure", 2, posts());
    }

    @Test
    public void explicitRequestJoinsARequestThatIsAlreadyInFlight() throws Exception {
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
            KeepADBRegisterClient.requestResyncNow(context);
        } finally {
            release.countDown();
        }
        assertTrue(KeepADBRegisterClient.awaitIdleForTesting(3000));
        assertEquals("no parallel request", 1, posts());
    }

    @Test
    public void explicitRequestReplacesAWaitingDebouncedResync() {
        exhaustSeries();
        KeepADBRegisterClient.requestResyncAsync(context);
        assertTrue(KeepADBRegisterClient.hasPendingResyncForTesting());

        KeepADBRegisterClient.requestResyncNow(context);
        assertTrue(KeepADBRegisterClient.awaitIdleForTesting(3000));
        assertFalse(KeepADBRegisterClient.hasPendingResyncForTesting());
        assertEquals(2, posts());

        advance(KeepADBRegisterClient.RESYNC_DEBOUNCE_MS);
        assertEquals("the debounced attempt does not follow", 2, posts());
    }

    @Test
    public void explicitRequestNeedsEnabledSyncAndAnEndpoint() {
        KeepADBPreferences.setRegisterWebhookEnabled(context, false);
        KeepADBRegisterClient.requestResyncNow(context);
        assertTrue(KeepADBRegisterClient.awaitIdleForTesting(3000));
        assertEquals(0, posts());

        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        KeepADBRegisterClient.setCoordinatorEndpointForTesting("");
        KeepADBRegisterClient.requestResyncNow(context);
        assertTrue(KeepADBRegisterClient.awaitIdleForTesting(3000));
        assertEquals(0, posts());
    }

    @Test
    public void savingTheUnchangedUrlRestartsAnExhaustedSeries() {
        exhaustSeries();
        transport.setPostSuccess(true);
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setContentView(R.layout.activity_settings);
        new KeepADBWebhookForm(activity, () -> { });
        EditText input = activity.findViewById(R.id.settings_webhook_url);
        Button save = activity.findViewById(R.id.settings_webhook_save);

        input.setText(URL);
        save.performClick();
        assertTrue(KeepADBRegisterClient.awaitIdleForTesting(3000));
        ShadowLooper.idleMainLooper();

        assertEquals("Save URL is the user's request to report now", 2, posts());
        assertEquals(ENDPOINT, KeepADBRegisterClient.getLastRegisteredEndpointForTesting());
    }
}
