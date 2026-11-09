package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * #923: a POST that is in flight when a newer operation starts (opt-out, URL change, endpoint
 * loss) may still register at the receiver. Its completion is stale and records nothing, so
 * without the fix the later withdrawal only targets the last recorded registration and the new
 * destination stays registered although nothing in the app accounts for it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBRegisterInFlightWithdrawalTest {

    private static final String URL_A = "http://a.test/register/s20";
    private static final String URL_B = "http://b.test/register/s20";
    private static final String URL_C = "http://c.test/register/s20";
    private static final String OLD_ENDPOINT = "192.168.1.5:40000";

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = ApplicationProvider.getApplicationContext();
    private KeepADBFakeHttpTransport transport;

    @Before
    public void setUp() {
        clearPreferences();
        KeepADBRegisterClient.resetHttpTransport();
        KeepADBRegisterClient.resetForTesting();
        KeepADB.resetForTesting();
        transport = new KeepADBFakeHttpTransport();
        KeepADBRegisterClient.setHttpTransport(transport);
    }

    @After
    public void tearDown() {
        clearPreferences();
        KeepADBRegisterClient.resetHttpTransport();
        KeepADBRegisterClient.resetForTesting();
        KeepADB.resetForTesting();
    }

    private void clearPreferences() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
    }

    /** A registration at A exists; the saved URL is now B and sync is on. */
    private void registeredAtAWithBSaved() {
        KeepADBPreferences.setRegisterWebhookUrl(context, URL_B);
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        KeepADBPreferences.setWebhookLastReportedUrl(context, URL_A);
        KeepADBPreferences.setWebhookLastReportedEndpoint(context, OLD_ENDPOINT);
        KeepADBRegisterClient.setWlanStateForTesting(URL_A, OLD_ENDPOINT);
    }

    /** Runs {@code whileInFlight} once, on the register thread, while the first POST to B blocks. */
    private void duringFirstPostTo(String url, Runnable whileInFlight) {
        AtomicBoolean done = new AtomicBoolean();
        transport.setRequestCallback(req -> {
            if ("POST".equals(req.method) && url.equals(req.url) && !done.getAndSet(true)) {
                whileInFlight.run();
            }
        });
    }

    private void drain() {
        // The work queued from inside the callback lands behind the first drain marker.
        for (int i = 0; i < 4; i++) assertTrue(KeepADBRegisterClient.awaitIdleForTesting(3000));
    }

    private List<String> requests() {
        List<String> out = new ArrayList<>();
        synchronized (transport.recordedRequests) {
            for (KeepADBFakeHttpTransport.Request r : transport.recordedRequests) {
                out.add(r.method + " " + r.url);
            }
        }
        return out;
    }

    private static int lastIndexOf(List<String> list, String entry) {
        return list.lastIndexOf(entry);
    }

    @Test
    public void optOutDuringThePostToTheNewUrlWithdrawsBothDestinations() {
        registeredAtAWithBSaved();
        transport.setFailingUrl(URL_A); // the DELETE at A during the URL change fails
        duringFirstPostTo(URL_B, () -> {
            KeepADBPreferences.setRegisterWebhookEnabled(context, false);
            KeepADBRegisterClient.unregisterAndDisableAsync(context);
            transport.setFailingUrl(null);
        });

        KeepADBRegisterClient.updateEndpointAsync(context, "192.168.1.6", 41000);
        drain();

        List<String> sent = requests();
        int post = sent.indexOf("POST " + URL_B);
        assertTrue(sent.toString(), post >= 0);
        assertTrue("B must be withdrawn after its POST: " + sent,
                lastIndexOf(sent, "DELETE " + URL_B) > post);
        assertTrue("A must be withdrawn after the opt-out: " + sent,
                lastIndexOf(sent, "DELETE " + URL_A) > post);
        assertTrue(KeepADBPreferences.getPendingWebhookCleanupUrls(context).isEmpty());
        assertNull(KeepADBPreferences.getWebhookDispatchedUrl(context));
        assertNull(KeepADBRegisterClient.getLastRegisteredUrlForTesting());
    }

    @Test
    public void anUndeliveredWithdrawalOfTheNewUrlSurvivesProcessDeath() {
        registeredAtAWithBSaved();
        transport.setFailingUrl(URL_A);
        duringFirstPostTo(URL_B, () -> {
            KeepADBPreferences.setRegisterWebhookEnabled(context, false);
            KeepADBRegisterClient.unregisterAndDisableAsync(context);
            // From now on every DELETE fails (the POST in flight still succeeds).
            transport.setFailingUrl(null);
            transport.setDeleteSuccess(false);
        });

        KeepADBRegisterClient.updateEndpointAsync(context, "192.168.1.6", 41000);
        drain();
        assertTrue("B stays queued for withdrawal: "
                        + KeepADBPreferences.getPendingWebhookCleanupUrls(context),
                KeepADBPreferences.getPendingWebhookCleanupUrls(context).contains(URL_B));

        // Process death: the in-memory client state is gone, the preferences remain.
        KeepADBRegisterClient.resetForTesting();
        KeepADBRegisterClient.setHttpTransport(transport);
        transport.setRequestCallback(null);
        transport.setDeleteSuccess(true);
        transport.clearRequests();
        // After the retry backoff of the failed DELETE, within the entry's lifetime.
        KeepADBRegisterClient.setPendingCleanupNowForTesting(System.currentTimeMillis()
                + PendingCleanupRetryRepository.MAX_BACKOFF_MS + 1_000L);
        KeepADBRegisterClient.flushPendingCleanupsForTesting(context);

        assertTrue(requests().toString(), requests().contains("DELETE " + URL_B));
        assertTrue(KeepADBPreferences.getPendingWebhookCleanupUrls(context).isEmpty());
    }

    @Test
    public void aPostInterruptedByProcessDeathIsWithdrawnAtTheOptOut() {
        // The process died while the POST to B was in flight: only the dispatch marker remains.
        registeredAtAWithBSaved();
        KeepADBPreferences.setWebhookDispatchedUrl(context, URL_B);
        KeepADBPreferences.setRegisterWebhookEnabled(context, false);

        KeepADBRegisterClient.unregisterAndDisableAsync(context);
        drain();

        List<String> sent = requests();
        assertTrue(sent.toString(), sent.contains("DELETE " + URL_B));
        assertTrue(sent.toString(), sent.contains("DELETE " + URL_A));
        assertNull(KeepADBPreferences.getWebhookDispatchedUrl(context));
        assertTrue(KeepADBPreferences.getPendingWebhookCleanupUrls(context).isEmpty());
    }

    @Test
    public void aUrlChangeDuringThePostWithdrawsTheAbandonedUrl() {
        registeredAtAWithBSaved();
        duringFirstPostTo(URL_B, () -> {
            KeepADBPreferences.setRegisterWebhookUrl(context, URL_C);
            KeepADBRegisterClient.updateEndpointAsync(context, "192.168.1.6", 41000);
        });

        KeepADBRegisterClient.updateEndpointAsync(context, "192.168.1.6", 41000);
        drain();

        List<String> sent = requests();
        assertTrue(sent.toString(), sent.contains("POST " + URL_C));
        assertTrue("the abandoned B must be withdrawn: " + sent, sent.contains("DELETE " + URL_B));
        assertEquals(URL_C, KeepADBRegisterClient.getLastRegisteredUrlForTesting());
        assertTrue(KeepADBPreferences.getPendingWebhookCleanupUrls(context).isEmpty());
        assertNull(KeepADBPreferences.getWebhookDispatchedUrl(context));
    }

    @Test
    public void toggledOffAndOnAgainDuringThePostKeepsTheRegistrationAtB() {
        // Counter-check: when B is the target again, nothing withdraws it for good.
        registeredAtAWithBSaved();
        duringFirstPostTo(URL_B, () -> {
            KeepADBPreferences.setRegisterWebhookEnabled(context, false);
            KeepADBRegisterClient.unregisterAndDisableAsync(context);
            KeepADBPreferences.setRegisterWebhookEnabled(context, true);
            KeepADBRegisterClient.updateEndpointAsync(context, "192.168.1.6", 41000);
        });

        KeepADBRegisterClient.updateEndpointAsync(context, "192.168.1.6", 41000);
        drain();

        List<String> sent = requests();
        int lastPost = lastIndexOf(sent, "POST " + URL_B);
        assertTrue(sent.toString(), lastPost >= 0);
        assertTrue("no DELETE at B after its last POST: " + sent,
                lastIndexOf(sent, "DELETE " + URL_B) < lastPost);
        assertEquals(URL_B, KeepADBRegisterClient.getLastRegisteredUrlForTesting());
        assertTrue(KeepADBPreferences.getPendingWebhookCleanupUrls(context).isEmpty());
        assertNull(KeepADBPreferences.getWebhookDispatchedUrl(context));
    }

    @Test
    public void aPostTheServerRejectedLeavesNoMarkerAndQueuesNothing() {
        KeepADBPreferences.setRegisterWebhookUrl(context, URL_B);
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        transport.setPostResponseCode(500); // an HTTP answer: nothing was registered

        KeepADBRegisterClient.updateEndpointAsync(context, "192.168.1.6", 41000);
        drain();

        assertFalse(requests().isEmpty());
        assertNull(KeepADBPreferences.getWebhookDispatchedUrl(context));
        assertTrue(KeepADBPreferences.getPendingWebhookCleanupUrls(context).isEmpty());
    }

    @Test
    public void aPostWhoseAnswerWasLostIsWithdrawnAtTheOptOut() {
        // Review R1: the request reached B, then reading the answer failed (an I/O error, not an
        // HTTP answer); meanwhile the user opts out.
        registeredAtAWithBSaved();
        transport.setFailingUrl(URL_A);
        duringFirstPostTo(URL_B, () -> {
            transport.setPostSuccess(false); // this POST now ends with an I/O error
            KeepADBPreferences.setRegisterWebhookEnabled(context, false);
            KeepADBRegisterClient.unregisterAndDisableAsync(context);
            transport.setFailingUrl(null);
        });

        KeepADBRegisterClient.updateEndpointAsync(context, "192.168.1.6", 41000);
        drain();

        List<String> sent = requests();
        int post = sent.indexOf("POST " + URL_B);
        assertTrue(sent.toString(), post >= 0);
        assertTrue("B may hold the registration and must be withdrawn: " + sent,
                lastIndexOf(sent, "DELETE " + URL_B) > post);
        assertTrue(sent.toString(), lastIndexOf(sent, "DELETE " + URL_A) > post);
        assertNull(KeepADBPreferences.getWebhookDispatchedUrl(context));
        assertTrue(KeepADBPreferences.getPendingWebhookCleanupUrls(context).isEmpty());
    }

    @Test
    public void anEndpointLossDuringThePostKeepsTheEvidenceAndWithdrawsB() {
        // Review R1: sync stays on, so B is still the target when its stale POST succeeds; the
        // endpoint loss that superseded it is a withdrawal and must take B along.
        registeredAtAWithBSaved();
        transport.setFailingUrl(URL_A);
        duringFirstPostTo(URL_B, () -> KeepADBRegisterClient.markUnavailableAsync(context));

        KeepADBRegisterClient.updateEndpointAsync(context, "192.168.1.6", 41000);
        drain();

        List<String> sent = requests();
        int post = sent.indexOf("POST " + URL_B);
        assertTrue(sent.toString(), post >= 0);
        assertTrue("the endpoint loss withdraws B as well: " + sent,
                lastIndexOf(sent, "DELETE " + URL_B) > post);
        assertNull(KeepADBPreferences.getWebhookDispatchedUrl(context));
    }

    @Test
    public void aStaleSuccessWhileBIsStillTheTargetIsKeptUntilAnOptOutWithdrawsIt() {
        // Review R1: nothing is queued while B is the target, but the evidence survives, so an
        // opt-out much later (after a failed withdrawal of A, even after a restart) still finds B.
        registeredAtAWithBSaved();
        transport.setFailingUrl(URL_A);
        duringFirstPostTo(URL_B, KeepADBRegisterClient::bumpOpGenerationForTesting);

        KeepADBRegisterClient.updateEndpointAsync(context, "192.168.1.6", 41000);
        drain();
        assertEquals("the marker keeps B", URL_B, KeepADBPreferences.getWebhookDispatchedUrl(context));
        assertTrue("and nothing is evicted from the backlog",
                KeepADBPreferences.getPendingWebhookCleanupUrls(context).isEmpty());

        // Process death, then the opt-out.
        KeepADBRegisterClient.resetForTesting();
        KeepADBRegisterClient.setHttpTransport(transport);
        transport.setRequestCallback(null);
        transport.setFailingUrl(null);
        transport.clearRequests();
        KeepADBPreferences.setRegisterWebhookEnabled(context, false);
        KeepADBRegisterClient.unregisterAndDisableAsync(context);
        drain();

        List<String> sent = requests();
        assertTrue(sent.toString(), sent.contains("DELETE " + URL_B));
        assertTrue(sent.toString(), sent.contains("DELETE " + URL_A));
        assertNull(KeepADBPreferences.getWebhookDispatchedUrl(context));
    }

    @Test
    public void anEndpointLossAfterALostAnswerWithdrawsTheUnsettledDestination() {
        // Review R2: nothing is recorded, nothing is in flight, only the marker says B may hold
        // the registration; the endpoint loss must not return early.
        KeepADBPreferences.setRegisterWebhookUrl(context, URL_B);
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        transport.setPostSuccess(false); // the POST ends with an I/O error (answer lost)
        KeepADBRegisterClient.updateEndpointAsync(context, "192.168.1.6", 41000);
        drain();
        assertEquals(URL_B, KeepADBPreferences.getWebhookDispatchedUrl(context));

        transport.setPostSuccess(true);
        transport.clearRequests();
        KeepADBRegisterClient.markUnavailableAsync(context);
        drain();

        assertTrue(requests().toString(), requests().contains("DELETE " + URL_B));
        assertNull(KeepADBPreferences.getWebhookDispatchedUrl(context));
        assertTrue(KeepADBPreferences.getPendingWebhookCleanupUrls(context).isEmpty());
    }

    @Test
    public void anEndpointLossRetriesAWithdrawalThatFailedUntilItSucceeds() {
        // Review R3: the lost answer moves B into the queue at the first endpoint loss; that DELETE
        // fails, and a later endpoint loss (after the backoff) must still reach it.
        KeepADBPreferences.setRegisterWebhookUrl(context, URL_B);
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        transport.setPostSuccess(false); // answer lost
        KeepADBRegisterClient.updateEndpointAsync(context, "192.168.1.6", 41000);
        drain();

        transport.setPostSuccess(true);
        transport.setDeleteSuccess(false);
        KeepADBRegisterClient.setMarkUnavailableNowForTesting(1_000L);
        KeepADBRegisterClient.markUnavailableAsync(context);
        drain();
        assertNull(KeepADBPreferences.getWebhookDispatchedUrl(context));
        assertTrue("B stays queued after the failed DELETE",
                KeepADBPreferences.getPendingWebhookCleanupUrls(context).contains(URL_B));

        // Within the backoff a repeated endpoint loss sends nothing ...
        transport.clearRequests();
        KeepADBRegisterClient.markUnavailableAsync(context);
        drain();
        assertTrue(requests().toString(), requests().isEmpty());

        // ... after it, the withdrawal is attempted again and succeeds.
        transport.setDeleteSuccess(true);
        KeepADBRegisterClient.setMarkUnavailableNowForTesting(1_000L + 60L * 60L * 1000L);
        KeepADBRegisterClient.markUnavailableAsync(context);
        drain();
        assertTrue(requests().toString(), requests().contains("DELETE " + URL_B));
        assertTrue(KeepADBPreferences.getPendingWebhookCleanupUrls(context).isEmpty());
    }
}
