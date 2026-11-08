package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

/**
 * #910: the webhook half of the shared widget state projection. Deterministic scenarios on the real
 * report client with a fake transport: an endpoint that changes while a report completes, a
 * destination that changes, a failure after a success, a process that is recreated and an endpoint
 * that is lost. Each pins the currentness guard of the claim "delivered to the current endpoint".
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBWidgetStateDeliveryTest {

    @Rule
    public final KeepADBNetworkResetRule networkResetRule = new KeepADBNetworkResetRule();
    @Rule
    public final KeepADBRegisterClientResetRule registerResetRule = new KeepADBRegisterClientResetRule();

    private static final String URL_1 = "http://register.example/register/dev1";
    private static final String URL_2 = "http://register.example/register/dev2";
    private static final String HOST_A = "10.20.30.40";
    private static final int PORT_A = 41873;
    private static final String HOST_B = "10.20.30.41";
    private static final int PORT_B = 42001;

    private Context context;
    private KeepADBFakeHttpTransport transport;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADBPreferences.migratePrivacySplit(context);
        KeepADBReportHistory.clear(context);
        KeepADBEndpointCoordinator.resetForTesting();
        KeepADB.resetForTesting(context);
        KeepADB.setSurfaceRefresherForTesting(new KeepADBFakeSurfaceRefresher());
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(true));
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        transport = new KeepADBFakeHttpTransport();
        KeepADBRegisterClient.setHttpTransport(transport);
        KeepADBPreferences.setRegisterWebhookUrl(context, URL_1);
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
    }

    @After
    public void tearDown() {
        transport.setRequestCallback(null);
        KeepADBRegisterClientResetRule.awaitIdle();
        KeepADBEndpointCoordinator.resetForTesting();
        KeepADBReportHistory.clear(context);
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADB.resetForTesting();
    }

    private KeepADBWidgetState.Webhook webhook() {
        return KeepADBWidgetState.project(context).webhook;
    }

    private void discover(String host, int port) {
        KeepADBEndpointCoordinator.seedEndpointForTesting(host, port, 1L);
    }

    /** The endpoint is found and its report is accepted. */
    private void reportAccepted(String host, int port) {
        discover(host, port);
        KeepADBRegisterClient.updateEndpointAsync(context, host, port);
        KeepADBRegisterClientResetRule.awaitIdle();
    }

    private void advance(long ms) {
        ShadowLooper.idleMainLooper(ms, TimeUnit.MILLISECONDS);
        KeepADBRegisterClientResetRule.awaitIdle();
    }

    /** Blocks the n-th POST (1-based) until released; counts started POSTs. */
    private static final class Gate {
        final AtomicInteger posts = new AtomicInteger();
        final CountDownLatch[] started = {new CountDownLatch(1), new CountDownLatch(1)};
        final CountDownLatch[] release = {new CountDownLatch(1), new CountDownLatch(1)};

        void install(KeepADBFakeHttpTransport transport) {
            transport.setRequestCallback(request -> {
                if (!"POST".equals(request.method)) return;
                int n = posts.incrementAndGet();
                if (n > 2) return;
                started[n - 1].countDown();
                try {
                    release[n - 1].await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
    }

    // ---- Sync configuration ----------------------------------------------------------------

    @Test
    public void syncIsUnconfiguredOffOrOn() {
        KeepADBPreferences.setRegisterWebhookUrl(context, null);
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        assertEquals("enabled without a destination is not configured",
                KeepADBWidgetState.Sync.UNCONFIGURED, webhook().sync);

        KeepADBPreferences.setRegisterWebhookEnabled(context, false);
        assertEquals(KeepADBWidgetState.Sync.UNCONFIGURED, webhook().sync);

        KeepADBPreferences.setRegisterWebhookUrl(context, URL_1);
        assertEquals(KeepADBWidgetState.Sync.OFF, webhook().sync);

        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        assertEquals(KeepADBWidgetState.Sync.ON, webhook().sync);
    }

    @Test
    public void withoutAnyReportNothingIsClaimed() {
        discover(HOST_A, PORT_A);
        KeepADBWidgetState.Webhook state = webhook();
        assertEquals(KeepADBWidgetState.Outcome.NONE, state.latestOutcome);
        assertEquals(KeepADBWidgetState.Delivery.NOT_CURRENT, state.delivery);
        assertEquals(0L, state.lastSuccessAtMs);
        assertEquals(0L, state.lastFailureAtMs);
        assertEquals(KeepADBWidgetState.Activity.IDLE, state.activity);
    }

    // ---- Success tied to the current endpoint and destination --------------------------------

    @Test
    public void anAcceptedReportIsTheCurrentDeliveryOfExactlyItsEndpoint() {
        reportAccepted(HOST_A, PORT_A);
        KeepADBWidgetState.Webhook state = webhook();
        assertEquals(KeepADBWidgetState.Delivery.CURRENT, state.delivery);
        assertEquals(KeepADBWidgetState.Outcome.SUCCESS, state.latestOutcome);
        assertTrue(state.lastSuccessAtMs > 0L);
        assertEquals(KeepADBWidgetState.Activity.IDLE, state.activity);

        // The endpoint changes: the success is history, not the current delivery.
        discover(HOST_B, PORT_B);
        KeepADBWidgetState.Webhook changed = webhook();
        assertEquals(KeepADBWidgetState.Delivery.NOT_CURRENT, changed.delivery);
        assertEquals("the historical success is kept", state.lastSuccessAtMs, changed.lastSuccessAtMs);

        // A changes back to what was delivered: the evidence matches again.
        discover(HOST_A, PORT_A);
        assertEquals(KeepADBWidgetState.Delivery.CURRENT, webhook().delivery);
    }

    /**
     * Endpoint A -> B while A's report completes. A's answer arrives after B superseded it: it must
     * not be recorded, must not end the "sending" of B, and the delivery to B is claimed only once
     * B's own report is accepted.
     */
    @Test
    public void aReportThatCompletesAfterTheEndpointChangedNeitherClaimsNorHidesTheNewOne()
            throws Exception {
        Gate gate = new Gate();
        gate.install(transport);

        discover(HOST_A, PORT_A);
        KeepADBRegisterClient.updateEndpointAsync(context, HOST_A, PORT_A);
        assertTrue(gate.started[0].await(5, TimeUnit.SECONDS));
        assertEquals(KeepADBWidgetState.Activity.SENDING, webhook().activity);
        assertEquals(KeepADBWidgetState.Delivery.NOT_CURRENT, webhook().delivery);

        // B appears while A's POST is still on the wire.
        discover(HOST_B, PORT_B);
        KeepADBRegisterClient.updateEndpointAsync(context, HOST_B, PORT_B);

        gate.release[0].countDown();
        assertTrue(gate.started[1].await(5, TimeUnit.SECONDS));
        // A's answer is in, B's POST is on the wire.
        KeepADBWidgetState.Webhook midway = webhook();
        assertEquals("a superseded completion must not end B's sending",
                KeepADBWidgetState.Activity.SENDING, midway.activity);
        assertEquals("nothing is delivered to B yet", KeepADBWidgetState.Delivery.NOT_CURRENT,
                midway.delivery);
        assertEquals("a superseded completion is not recorded",
                KeepADBWidgetState.Outcome.NONE, midway.latestOutcome);
        assertEquals(0L, midway.lastSuccessAtMs);

        gate.release[1].countDown();
        KeepADBRegisterClientResetRule.awaitIdle();
        KeepADBWidgetState.Webhook done = webhook();
        assertEquals(KeepADBWidgetState.Delivery.CURRENT, done.delivery);
        assertEquals(KeepADBWidgetState.Activity.IDLE, done.activity);
        assertTrue(done.lastSuccessAtMs > 0L);
    }

    /**
     * The destination changes while a report to the old one is on the wire and is accepted after
     * the change: that acceptance belongs to the old destination and is not a delivery to the one
     * configured now.
     */
    @Test
    public void aReportAcceptedByTheOldDestinationIsNotADeliveryToTheNewOne() throws Exception {
        Gate gate = new Gate();
        gate.install(transport);
        discover(HOST_A, PORT_A);
        KeepADBRegisterClient.updateEndpointAsync(context, HOST_A, PORT_A);
        assertTrue(gate.started[0].await(5, TimeUnit.SECONDS));

        KeepADBPreferences.setRegisterWebhookUrl(context, URL_2);
        gate.release[0].countDown();
        KeepADBRegisterClientResetRule.awaitIdle();

        KeepADBWidgetState.Webhook state = webhook();
        assertEquals("the stored report names the old destination",
                KeepADBWidgetState.Delivery.NOT_CURRENT, state.delivery);
        assertEquals("a success of another destination is not shown for this one",
                0L, state.lastSuccessAtMs);

        // Back to the destination that did accept it: the evidence matches again.
        KeepADBPreferences.setRegisterWebhookUrl(context, URL_1);
        assertEquals(KeepADBWidgetState.Delivery.CURRENT, webhook().delivery);
    }

    @Test
    public void changingTheDestinationEndsTheClaimUntilTheNewOneAcceptsAReport() {
        reportAccepted(HOST_A, PORT_A);
        assertEquals(KeepADBWidgetState.Delivery.CURRENT, webhook().delivery);

        KeepADBPreferences.setRegisterWebhookUrl(context, URL_2);
        assertEquals(KeepADBWidgetState.Delivery.NOT_CURRENT, webhook().delivery);

        KeepADBRegisterClient.updateEndpointAsync(context, HOST_A, PORT_A);
        KeepADBRegisterClientResetRule.awaitIdle();
        KeepADBWidgetState.Webhook state = webhook();
        assertEquals(KeepADBWidgetState.Delivery.CURRENT, state.delivery);
        assertTrue(state.lastSuccessAtMs > 0L);
    }

    // ---- Failure after success ------------------------------------------------------------

    @Test
    public void aFailureAfterASuccessKeepsTheHistoryAndEndsTheClaim() {
        reportAccepted(HOST_A, PORT_A);
        long success = webhook().lastSuccessAtMs;
        assertTrue(success > 0L);

        transport.setPostSuccess(false);
        KeepADBRegisterClient.requestResyncNow(context);
        KeepADBRegisterClientResetRule.awaitIdle();

        KeepADBWidgetState.Webhook failed = webhook();
        assertEquals(KeepADBWidgetState.Outcome.FAILED, failed.latestOutcome);
        assertEquals("a failure after a success is not the current delivery",
                KeepADBWidgetState.Delivery.NOT_CURRENT, failed.delivery);
        assertEquals("the earlier success stays as history", success, failed.lastSuccessAtMs);
        assertTrue("the failure has a time", failed.lastFailureAtMs > 0L);
        assertEquals(KeepADBWidgetState.Activity.RETRY_WAITING, failed.activity);

        // The retry succeeds: the claim is back, derived from the new evidence.
        transport.setPostSuccess(true);
        advance(5_000L);
        KeepADBWidgetState.Webhook recovered = webhook();
        assertEquals(KeepADBWidgetState.Delivery.CURRENT, recovered.delivery);
        assertEquals(KeepADBWidgetState.Activity.IDLE, recovered.activity);
        assertEquals(KeepADBWidgetState.Outcome.SUCCESS, recovered.latestOutcome);
    }

    @Test
    public void aFirstFailureHasNoSuccessToShow() {
        transport.setPostSuccess(false);
        discover(HOST_A, PORT_A);
        KeepADBRegisterClient.updateEndpointAsync(context, HOST_A, PORT_A);
        KeepADBRegisterClientResetRule.awaitIdle();

        KeepADBWidgetState.Webhook state = webhook();
        assertEquals(KeepADBWidgetState.Outcome.FAILED, state.latestOutcome);
        assertEquals(0L, state.lastSuccessAtMs);
        assertEquals(KeepADBWidgetState.Delivery.NOT_CURRENT, state.delivery);
    }

    // ---- Process recreation and endpoint loss -----------------------------------------------

    /**
     * A new process starts without an endpoint and without any report in flight. What was stored
     * stays visible as history; nothing is claimed for an endpoint that is not known yet. When the
     * same endpoint is found again the stored evidence (the sender's own record, which the client
     * also dedupes against) applies; a different one is not covered.
     */
    @Test
    public void aRecreatedProcessClaimsNothingUntilItKnowsTheEndpointAgain() {
        reportAccepted(HOST_A, PORT_A);
        long success = webhook().lastSuccessAtMs;
        assertTrue(success > 0L);

        KeepADBRegisterClientResetRule.reset();
        KeepADBEndpointCoordinator.resetForTesting();

        KeepADBWidgetState restarted = KeepADBWidgetState.project(context);
        assertEquals(KeepADBWidgetState.EndpointAvailability.SEARCHING, restarted.endpoint);
        assertEquals("no endpoint, no claim", KeepADBWidgetState.Delivery.UNKNOWN,
                restarted.webhook.delivery);
        assertEquals(KeepADBWidgetState.Activity.IDLE, restarted.webhook.activity);
        assertEquals("the history survives", success, restarted.webhook.lastSuccessAtMs);
        assertEquals(KeepADBWidgetState.Outcome.SUCCESS, restarted.webhook.latestOutcome);

        discover(HOST_B, PORT_B);
        assertEquals(KeepADBWidgetState.Delivery.NOT_CURRENT, webhook().delivery);
        discover(HOST_A, PORT_A);
        assertEquals(KeepADBWidgetState.Delivery.CURRENT, webhook().delivery);
    }

    @Test
    public void aLostEndpointMakesTheDeliveryUnknownAndKeepsTheHistory() {
        reportAccepted(HOST_A, PORT_A);
        long success = webhook().lastSuccessAtMs;

        KeepADBEndpointCoordinator.seedEndpointForTesting(null, 0, 0L);
        KeepADBWidgetState lost = KeepADBWidgetState.project(context);
        assertEquals(KeepADBWidgetState.Delivery.UNKNOWN, lost.webhook.delivery);
        assertEquals(success, lost.webhook.lastSuccessAtMs);
        assertEquals(0L, lost.endpointVerifiedAtMs);
    }

    @Test
    public void aDeregistrationAfterTheLossIsNotCurrentWhenTheSameEndpointReturns() {
        reportAccepted(HOST_A, PORT_A);

        KeepADBEndpointCoordinator.seedEndpointForTesting(null, 0, 0L);
        KeepADBRegisterClient.markUnavailableAsync(context);
        KeepADBRegisterClientResetRule.awaitIdle();
        assertEquals(KeepADBWidgetState.Outcome.DEREGISTERED, webhook().latestOutcome);

        discover(HOST_A, PORT_A);
        KeepADBWidgetState.Webhook returned = webhook();
        assertEquals("the receiver was told the endpoint is gone",
                KeepADBWidgetState.Delivery.NOT_CURRENT, returned.delivery);
    }

    // ---- The guard itself, table-driven ----------------------------------------------------

    @Test
    public void deliveryIsClaimedOnlyWhenEveryPartOfTheEvidenceMatches() {
        String a = "10.20.30.40:41873";
        String b = "10.20.30.41:42001";
        KeepADBWidgetState.Outcome ok = KeepADBWidgetState.Outcome.SUCCESS;

        assertEquals(KeepADBWidgetState.Delivery.CURRENT,
                KeepADBWidgetState.deliveryOf(a, URL_1, ok, URL_1, a));
        assertEquals("whitespace around the stored values is not a difference",
                KeepADBWidgetState.Delivery.CURRENT,
                KeepADBWidgetState.deliveryOf(a, URL_1, ok, " " + URL_1 + " ", a + " "));

        assertEquals("another endpoint", KeepADBWidgetState.Delivery.NOT_CURRENT,
                KeepADBWidgetState.deliveryOf(b, URL_1, ok, URL_1, a));
        assertEquals("another destination", KeepADBWidgetState.Delivery.NOT_CURRENT,
                KeepADBWidgetState.deliveryOf(a, URL_2, ok, URL_1, a));
        assertEquals("no destination configured", KeepADBWidgetState.Delivery.NOT_CURRENT,
                KeepADBWidgetState.deliveryOf(a, null, ok, URL_1, a));
        assertEquals("no stored destination", KeepADBWidgetState.Delivery.NOT_CURRENT,
                KeepADBWidgetState.deliveryOf(a, URL_1, ok, null, a));
        assertEquals("no stored endpoint", KeepADBWidgetState.Delivery.NOT_CURRENT,
                KeepADBWidgetState.deliveryOf(a, URL_1, ok, URL_1, null));
        for (KeepADBWidgetState.Outcome other : new KeepADBWidgetState.Outcome[] {
                KeepADBWidgetState.Outcome.NONE, KeepADBWidgetState.Outcome.FAILED,
                KeepADBWidgetState.Outcome.DEREGISTERED}) {
            assertEquals("newest outcome " + other, KeepADBWidgetState.Delivery.NOT_CURRENT,
                    KeepADBWidgetState.deliveryOf(a, URL_1, other, URL_1, a));
        }
        assertEquals("no current endpoint", KeepADBWidgetState.Delivery.UNKNOWN,
                KeepADBWidgetState.deliveryOf(null, URL_1, ok, URL_1, a));
        assertFalse(KeepADBWidgetState.Delivery.CURRENT
                == KeepADBWidgetState.deliveryOf(null, URL_1, ok, URL_1, a));
    }
}
