package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.Application;
import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.widget.Button;

import androidx.test.core.app.ApplicationProvider;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlarmManager;
import org.robolectric.shadows.ShadowLooper;

/**
 * #911: "Report now" with automatic synchronization off is one deliberate, scoped report. The
 * tests pin the boundary between that one-time permission and the persistent opt-in: the
 * preference is false before, during and after; nothing is retried, repeated or left running;
 * duplicate taps never send in parallel; and anything the tap did not cover (another destination,
 * endpoint or state, a newer operation) is obsolete and sends nothing.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBOneShotReportTest {

    @Rule
    public final KeepADBNetworkResetRule networkResetRule = new KeepADBNetworkResetRule();
    @Rule
    public final KeepADBRegisterClientResetRule registerResetRule = new KeepADBRegisterClientResetRule();

    private static final String URL = "http://register.example/register/dev1";
    private static final String URL_2 = "http://register.example/register/dev2";
    private static final String HOST = "10.20.30.40";
    private static final int PORT = 41873;
    private static final String ENDPOINT = "10.20.30.40:41873";
    private static final String HOST_B = "10.20.30.41";
    private static final int PORT_B = 42001;

    private Context context;
    private KeepADBFakeHttpTransport transport;
    private KeepADBFakeSettingsGateway gateway;
    private final AtomicBoolean wifi = new AtomicBoolean(true);
    private final AtomicBoolean reachable = new AtomicBoolean(true);
    /** Runs on the register executor while the endpoint is probed, i.e. while the report is queued. */
    private volatile Runnable duringProbe;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
        ShadowAlarmManager.reset();
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADBPreferences.migratePrivacySplit(context);
        KeepADBReportHistory.clear(context);
        KeepADBEndpointCoordinator.resetForTesting();
        KeepADB.resetForTesting(context);
        KeepADB.setSurfaceRefresherForTesting(new KeepADBFakeSurfaceRefresher());
        gateway = new KeepADBFakeSettingsGateway(true);
        KeepADB.setGatewayForTesting(gateway);
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(wifi::get);
        KeepADBRegisterClient.setEndpointProbeForTesting(endpoint -> {
            Runnable hook = duringProbe;
            if (hook != null) hook.run();
            return reachable.get();
        });
        transport = new KeepADBFakeHttpTransport();
        KeepADBRegisterClient.setHttpTransport(transport);
        // Saved destination and a verified endpoint; synchronization stays off (the default).
        KeepADBPreferences.setRegisterWebhookUrl(context, URL);
        KeepADBEndpointCoordinator.seedEndpointForTesting(HOST, PORT, 1L);
    }

    @After
    public void tearDown() {
        transport.setRequestCallback(null);
        duringProbe = null;
        KeepADBRegisterClientResetRule.awaitIdle();
        KeepADBEndpointCoordinator.resetForTesting();
        KeepADBReportHistory.clear(context);
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADB.resetForTesting();
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** Drains the register executor, the main-thread hand-offs and the executor again. */
    private void settle() {
        KeepADBRegisterClientResetRule.awaitIdle();
        ShadowLooper.idleMainLooper();
        KeepADBRegisterClientResetRule.awaitIdle();
    }

    private int count(String method) {
        int count = 0;
        for (KeepADBFakeHttpTransport.Request request : new ArrayList<>(transport.recordedRequests)) {
            if (method.equals(request.method)) count++;
        }
        return count;
    }

    private int posts() {
        return count("POST");
    }

    private KeepADBWidgetState.Webhook webhook() {
        return KeepADBWidgetState.project(context).webhook;
    }

    private boolean syncEnabled() {
        return KeepADBPreferences.isRegisterWebhookEnabled(context);
    }

    private void connectedTo(String ssid, String bssid) {
        WifiManager manager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        shadowOf(manager).setConnectionInfo(
                new WifiInfo.Builder().setSsid(ssid.getBytes()).setBssid(bssid).build());
    }

    /** Blocks the first POST on the wire until released. */
    private final class Gate {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        Gate() {
            transport.setRequestCallback(request -> {
                if (!"POST".equals(request.method)) return;
                started.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        void awaitStarted() throws InterruptedException {
            assertTrue(started.await(5, TimeUnit.SECONDS));
        }
    }

    // ---- the one-shot: authorization boundary -------------------------------------------------

    @Test
    public void reportsTheCurrentEndpointOnceAndTheSwitchStaysOffThroughout() {
        assertFalse("off before", syncEnabled());
        AtomicBoolean enabledOnTheWire = new AtomicBoolean(true);
        transport.setRequestCallback(request -> {
            if ("POST".equals(request.method)) enabledOnTheWire.set(syncEnabled());
        });

        KeepADBRegisterClient.ReportNowResult result = KeepADBRegisterClient.reportNow(context, null);
        settle();

        assertEquals(KeepADBRegisterClient.ReportNowResult.STARTED, result);
        assertFalse("off while the request is on the wire", enabledOnTheWire.get());
        assertFalse("off after a success", syncEnabled());
        assertEquals("exactly one request", 1, transport.getRequestCount());
        assertEquals(URL, transport.getLastRequest().url);
        assertTrue(transport.getLastRequest().payload, transport.getLastRequest().payload.contains(ENDPOINT));
        assertTrue("Wireless Debugging is never switched", gateway.writes.isEmpty());
        assertEquals(KeepADBPreferences.WEBHOOK_STATUS_SUCCESS,
                KeepADBPreferences.getWebhookLastReportStatus(context));
        assertEquals(ENDPOINT, KeepADBPreferences.getWebhookLastReportedEndpoint(context));
    }

    @Test
    public void aFailedOneShotIsNotRetriedAndStartsNoFailureEpisode() {
        transport.setPostSuccess(false);
        // The retry budget a regular series would use; a one-shot must not use any of it.
        KeepADBPreferences.setWebhookRetryCount(context, 4);
        AtomicBoolean enabledOnTheWire = new AtomicBoolean(true);
        transport.setRequestCallback(request -> enabledOnTheWire.set(syncEnabled()));

        KeepADBRegisterClient.reportNow(context, null);
        settle();

        assertFalse(enabledOnTheWire.get());
        assertFalse("off after a failure", syncEnabled());
        assertEquals(KeepADBPreferences.WEBHOOK_STATUS_FAILED,
                KeepADBPreferences.getWebhookLastReportStatus(context));
        assertFalse("no retry timer", KeepADBRegisterClient.hasPendingPostRetryForTesting());
        assertEquals("no retry series", 0, KeepADBRegisterClient.getPostRetryNumberForTesting());
        assertEquals("no failure episode", 0L, KeepADBPreferences.getWebhookFailureSince(context));
        assertNull("no alert check alarm armed", nextAlarm());

        // Hours later, with every kind of trigger a regular series reacts to: still one request.
        for (int i = 0; i < 30; i++) {
            ShadowLooper.idleMainLooper(60, TimeUnit.MINUTES);
            KeepADBRegisterClient.reattemptStaleEpisode(context);
            KeepADBRegisterClient.requestResyncAsync(context);
        }
        settle();
        assertEquals("never retried, no heartbeat re-attempt", 1, posts());
        assertEquals(KeepADBWidgetState.Activity.IDLE, webhook().activity);
        assertFalse(syncEnabled());
    }

    @Test
    public void aSuccessfulOneShotLeavesNoBackgroundReportingBehind() {
        KeepADBRegisterClient.reportNow(context, null);
        settle();
        assertEquals(1, posts());

        // A new endpoint, a lost endpoint, a network change, the heartbeat, hours of time: with
        // synchronization off none of them sends anything, not even the DELETE of a lost endpoint.
        KeepADBEndpointCoordinator.seedEndpointForTesting(HOST_B, PORT_B, 2L);
        KeepADBRegisterClient.updateEndpointAsync(context, HOST_B, PORT_B);
        KeepADBRegisterClient.requestResyncAsync(context);
        KeepADBRegisterClient.reattemptStaleEpisode(context);
        ShadowLooper.idleMainLooper(6, TimeUnit.HOURS);
        KeepADBEndpointCoordinator.seedEndpointForTesting(null, 0, 0L);
        KeepADBRegisterClient.markUnavailableAsync(context);
        ShadowLooper.idleMainLooper(6, TimeUnit.HOURS);
        settle();

        assertEquals("only the deliberate report", 1, transport.getRequestCount());
        assertFalse(syncEnabled());
    }

    @Test
    public void anUntrustedNetworkDoesNotStopTheDeliberateReport() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        connectedTo("HomeNetX", "aa:bb:cc:dd:ee:ff");
        assertEquals("precondition: this network is not trusted",
                KeepADBTrustedNetwork.BlockReason.UNTRUSTED_NETWORK,
                KeepADBTrustedNetwork.getBlockReason(context));

        KeepADBRegisterClient.ReportNowResult result = KeepADBRegisterClient.reportNow(context, null);
        settle();

        assertEquals(KeepADBRegisterClient.ReportNowResult.STARTED, result);
        assertEquals("reported although the network is untrusted", 1, posts());
        assertFalse(syncEnabled());
        assertTrue("and it did not switch anything on", gateway.writes.isEmpty());
    }

    // ---- no valid destination, no valid endpoint ------------------------------------------------

    @Test
    public void withoutAUrlNothingIsSentAndTheCauseIsReturnedAndLogged() {
        KeepADBPreferences.setRegisterWebhookUrl(context, null);
        AtomicInteger done = new AtomicInteger();

        KeepADBRegisterClient.ReportNowResult result =
                KeepADBRegisterClient.reportNow(context, done::incrementAndGet);
        settle();

        assertEquals(KeepADBRegisterClient.ReportNowResult.NEEDS_SETUP, result);
        assertEquals(0, transport.getRequestCount());
        assertEquals("the caller is released at once", 1, done.get());
        assertFalse(syncEnabled());
        assertTrue(gateway.writes.isEmpty());
        assertTrue(KeepADBDiagnostics.export(context),
                KeepADBDiagnostics.export(context).contains("reason=needs_setup"));
    }

    @Test
    public void anInvalidSavedUrlSendsNothingEither() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit()
                .putString("register_webhook_url", "ftp://register.example/x").commit();

        assertEquals(KeepADBRegisterClient.ReportNowResult.NEEDS_SETUP,
                KeepADBRegisterClient.reportNow(context, null));
        settle();

        assertEquals(0, transport.getRequestCount());
        assertFalse(syncEnabled());
    }

    @Test
    public void withoutAVerifiedEndpointNothingIsSentAndWirelessDebuggingStaysAsItIs() {
        KeepADBEndpointCoordinator.seedEndpointForTesting(null, 0, 0L);
        gateway = new KeepADBFakeSettingsGateway(false); // Wireless Debugging is off
        KeepADB.setGatewayForTesting(gateway);
        AtomicInteger done = new AtomicInteger();

        KeepADBRegisterClient.ReportNowResult result =
                KeepADBRegisterClient.reportNow(context, done::incrementAndGet);
        settle();

        assertEquals(KeepADBRegisterClient.ReportNowResult.NO_ENDPOINT, result);
        assertEquals(0, transport.getRequestCount());
        assertEquals(1, done.get());
        assertTrue("never enables Wireless Debugging to get an endpoint", gateway.writes.isEmpty());
        assertFalse(syncEnabled());
        assertTrue(KeepADBDiagnostics.export(context).contains("reason=no_endpoint"));
    }

    @Test
    public void aCachedEndpointIsNotReusedWithoutWifi() {
        wifi.set(false);

        assertEquals(KeepADBRegisterClient.ReportNowResult.NO_ENDPOINT,
                KeepADBRegisterClient.reportNow(context, null));
        settle();

        assertEquals("a stale address is not reported", 0, transport.getRequestCount());
    }

    @Test
    public void anEndpointThatNoLongerAnswersIsNotReportedAndTheNextTapWorks() {
        reachable.set(false);

        KeepADBRegisterClient.reportNow(context, null);
        settle();

        assertEquals("the port rotated without notice: nothing is sent", 0, transport.getRequestCount());
        assertEquals("the slot is free again", KeepADBWidgetState.Activity.IDLE, webhook().activity);
        assertTrue(KeepADBDiagnostics.export(context).contains("reason=endpoint_unreachable"));

        reachable.set(true);
        assertEquals(KeepADBRegisterClient.ReportNowResult.STARTED,
                KeepADBRegisterClient.reportNow(context, null));
        settle();
        assertEquals(1, posts());
    }

    // ---- duplicate taps ---------------------------------------------------------------------------

    @Test
    public void repeatedTapsWhileAReportIsOnTheWireJoinItAndNeverSendInParallel() throws Exception {
        Gate gate = new Gate();
        AtomicInteger done = new AtomicInteger();

        assertEquals(KeepADBRegisterClient.ReportNowResult.STARTED,
                KeepADBRegisterClient.reportNow(context, done::incrementAndGet));
        gate.awaitStarted();
        assertEquals(KeepADBRegisterClient.ReportNowResult.JOINED,
                KeepADBRegisterClient.reportNow(context, done::incrementAndGet));
        assertEquals(KeepADBRegisterClient.ReportNowResult.JOINED,
                KeepADBRegisterClient.reportNow(context, done::incrementAndGet));
        ShadowLooper.idleMainLooper();
        assertEquals("nobody is released while the request is on the wire", 0, done.get());

        gate.release.countDown();
        settle();

        assertEquals("one tap burst, one request", 1, posts());
        assertEquals("every tap is released once the attempt has finished", 3, done.get());
        assertFalse(syncEnabled());
    }

    @Test
    public void aTapAfterTheReportFinishedIsANewDeliberateReport() {
        KeepADBRegisterClient.reportNow(context, null);
        settle();
        KeepADBRegisterClient.reportNow(context, null);
        settle();

        assertEquals("sequential taps are sequential, deliberate reports", 2, posts());
        assertFalse(syncEnabled());
    }

    // ---- scope captured at the tap, revalidated at dispatch ---------------------------------------

    @Test
    public void aDestinationChangedWhileQueuedSendsNothingToEither() {
        duringProbe = () -> KeepADBPreferences.setRegisterWebhookUrl(context, URL_2);

        KeepADBRegisterClient.reportNow(context, null);
        settle();

        assertEquals("the tap covered the old destination only", 0, transport.getRequestCount());
        assertEquals("the slot is free", KeepADBWidgetState.Activity.IDLE, webhook().activity);
        assertFalse(syncEnabled());
        assertTrue(KeepADBDiagnostics.export(context).contains("reason=destination_changed"));
    }

    @Test
    public void anEndpointLostWhileQueuedSendsNothing() {
        duringProbe = () -> KeepADBEndpointCoordinator.seedEndpointForTesting(null, 0, 0L);

        KeepADBRegisterClient.reportNow(context, null);
        settle();

        assertEquals(0, transport.getRequestCount());
        assertEquals(KeepADBWidgetState.Activity.IDLE, webhook().activity);
        assertTrue(KeepADBDiagnostics.export(context).contains("reason=endpoint_lost"));
    }

    @Test
    public void anEndpointReplacedWhileQueuedSendsNothingForTheOldAddress() {
        duringProbe = () -> KeepADBEndpointCoordinator.seedEndpointForTesting(HOST_B, PORT_B, 2L);

        KeepADBRegisterClient.reportNow(context, null);
        settle();

        assertEquals("the tap covered the address it saw, which is gone", 0,
                transport.getRequestCount());
        assertTrue(KeepADBDiagnostics.export(context).contains("reason=endpoint_changed"));
    }

    @Test
    public void synchronizationSwitchedOnWhileQueuedEndsTheOneShotWithoutARequest() {
        duringProbe = () -> KeepADBPreferences.setRegisterWebhookEnabled(context, true);

        KeepADBRegisterClient.reportNow(context, null);
        settle();

        assertEquals("the series machinery owns reporting now", 0, transport.getRequestCount());
        assertTrue(KeepADBDiagnostics.export(context).contains("reason=sync_enabled"));
    }

    /** A newer operation makes queued work obsolete, whatever the preferences say. */
    @Test
    public void aNewerOperationMakesAQueuedOneShotObsolete() throws Exception {
        // Occupies the register executor: an unregister DELETE that is on the wire.
        CountDownLatch deleteStarted = new CountDownLatch(1);
        CountDownLatch deleteRelease = new CountDownLatch(1);
        transport.setRequestCallback(request -> {
            if (!"DELETE".equals(request.method)) return;
            deleteStarted.countDown();
            try {
                deleteRelease.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        KeepADBRegisterClient.unregisterAndDisableAsync(context);
        assertTrue(deleteStarted.await(5, TimeUnit.SECONDS));

        assertEquals(KeepADBRegisterClient.ReportNowResult.STARTED,
                KeepADBRegisterClient.reportNow(context, null)); // queued behind the DELETE
        KeepADBRegisterClient.bumpOpGenerationForTesting();       // some newer operation
        deleteRelease.countDown();
        settle();

        assertEquals("the DELETE only", 1, transport.getRequestCount());
        assertEquals(0, posts());
    }

    // ---- completions of obsolete work ------------------------------------------------------------

    /**
     * The destination changes while the report is on the wire. The acceptance belongs to the old
     * destination: it is not shown as a delivery to the one configured now.
     */
    @Test
    public void aCompletionAfterTheDestinationChangedIsNoSuccessForTheNewOne() throws Exception {
        Gate gate = new Gate();
        KeepADBRegisterClient.reportNow(context, null);
        gate.awaitStarted();

        KeepADBPreferences.setRegisterWebhookUrl(context, URL_2);
        gate.release.countDown();
        settle();

        KeepADBWidgetState.Webhook state = webhook();
        assertEquals(KeepADBWidgetState.Delivery.NOT_CURRENT, state.delivery);
        assertEquals("no success is shown for the new destination", 0L, state.lastSuccessAtMs);
        assertEquals("the registration that happened is on record for the cleanup", URL,
                KeepADBRegisterClient.getLastRegisteredUrlForTesting());
        assertEquals(1, posts());
        assertFalse(syncEnabled());
    }

    /** The endpoint is lost while its report is on the wire: nothing is claimed for what is gone. */
    @Test
    public void aCompletionAfterTheEndpointWasLostClaimsNothingAndSendsNoDelete() throws Exception {
        Gate gate = new Gate();
        KeepADBRegisterClient.reportNow(context, null);
        gate.awaitStarted();

        KeepADBEndpointCoordinator.seedEndpointForTesting(null, 0, 0L);
        KeepADBRegisterClient.markUnavailableAsync(context); // what the coordinator does on a loss
        gate.release.countDown();
        settle();

        assertEquals(KeepADBWidgetState.Delivery.UNKNOWN, webhook().delivery);
        assertEquals("a one-shot is not withdrawn in the background", 0, count("DELETE"));
        assertEquals(1, transport.getRequestCount());
    }

    /**
     * A newer operation (here: synchronization switched on with its own report for another
     * endpoint) supersedes the one-shot while it is on the wire. Its late answer records nothing
     * and must not end the "sending" of the newer report.
     */
    @Test
    public void aSupersededCompletionRecordsNothingAndKeepsTheNewerReportSending() throws Exception {
        CountDownLatch[] started = {new CountDownLatch(1), new CountDownLatch(1)};
        CountDownLatch[] release = {new CountDownLatch(1), new CountDownLatch(1)};
        AtomicInteger n = new AtomicInteger();
        transport.setRequestCallback(request -> {
            if (!"POST".equals(request.method)) return;
            int i = n.getAndIncrement();
            if (i > 1) return;
            started[i].countDown();
            try {
                release[i].await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        KeepADBRegisterClient.reportNow(context, null);
        assertTrue(started[0].await(5, TimeUnit.SECONDS));

        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        KeepADBEndpointCoordinator.seedEndpointForTesting(HOST_B, PORT_B, 2L);
        KeepADBRegisterClient.updateEndpointAsync(context, HOST_B, PORT_B);
        release[0].countDown();
        assertTrue(started[1].await(5, TimeUnit.SECONDS));

        assertEquals("the stale completion must not end the newer report's sending",
                KeepADBWidgetState.Activity.SENDING, webhook().activity);
        assertEquals("and it recorded nothing", KeepADBWidgetState.Outcome.NONE,
                webhook().latestOutcome);
        assertNull(KeepADBRegisterClient.getLastRegisteredUrlForTesting());

        release[1].countDown();
        settle();
        assertEquals(KeepADBWidgetState.Delivery.CURRENT, webhook().delivery);
        assertEquals(ENDPOINT.replace(HOST, HOST_B).replace(String.valueOf(PORT), String.valueOf(PORT_B)),
                KeepADBRegisterClient.getLastRegisteredEndpointForTesting());
    }

    // ---- cleanup semantics --------------------------------------------------------------------------

    /**
     * The opt-out keeps working on a deliberate report: switching synchronization on finds the
     * endpoint already reported, and switching it off withdraws the registration.
     */
    @Test
    public void aDeliberateReportIsWithdrawnByTheRegularOptOut() {
        KeepADBRegisterClient.reportNow(context, null);
        settle();
        assertEquals(1, posts());

        KeepADBWebhookSync.setAutomaticSync(context, true);
        settle();
        assertEquals("already reported: switching on sends nothing new", 1, posts());
        assertEquals(KeepADBWidgetState.Delivery.CURRENT, webhook().delivery);

        KeepADBWebhookSync.setAutomaticSync(context, false);
        settle();
        assertEquals("DELETE at the URL that was reported", 1, count("DELETE"));
        assertEquals(URL, transport.getLastRequest().url);
        assertEquals(KeepADBPreferences.WEBHOOK_STATUS_DEREGISTERED,
                KeepADBPreferences.getWebhookLastReportStatus(context));
    }

    /** A new destination with synchronization on withdraws the deliberate report at the old one. */
    @Test
    public void aNewDestinationWithSyncOnWithdrawsTheDeliberateReportAtTheOldOne() {
        KeepADBRegisterClient.reportNow(context, null);
        settle();

        KeepADBPreferences.setRegisterWebhookUrl(context, URL_2);
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        KeepADBRegisterClient.updateEndpointAsync(context, HOST, PORT);
        settle();

        assertEquals("DELETE at the old URL, then the POST to the new one", 1, count("DELETE"));
        assertEquals(2, posts());
        assertEquals(URL_2, transport.getLastRequest().url);
    }

    /**
     * A registration at another destination is on record: the one-shot keeps it as a pending
     * cleanup for the regular register activity, and sends nothing to that destination itself.
     */
    @Test
    public void aRegistrationAtAnotherDestinationBecomesAPendingCleanupNotARequest() {
        KeepADBRegisterClient.setWlanStateForTesting(URL_2, "10.0.0.9:1234");

        KeepADBRegisterClient.reportNow(context, null);
        settle();

        assertEquals("one request, to the saved destination only", 1, transport.getRequestCount());
        assertEquals(URL, transport.getLastRequest().url);
        assertTrue(KeepADBPreferences.getPendingWebhookCleanupUrls(context).contains(URL_2));
        assertEquals(URL, KeepADBRegisterClient.getLastRegisteredUrlForTesting());
    }

    /** Cleanups of other destinations are the regular register activity's, never the one-shot's. */
    @Test
    public void theOneShotDoesNotFlushPendingCleanups() {
        KeepADBPreferences.addPendingWebhookCleanupUrl(context, "http://old.example/register/x");

        KeepADBRegisterClient.reportNow(context, null);
        settle();

        assertEquals(1, transport.getRequestCount());
        assertEquals(0, count("DELETE"));
        assertTrue(KeepADBPreferences.getPendingWebhookCleanupUrls(context)
                .contains("http://old.example/register/x"));
    }

    @Test
    public void aSuccessDropsAPendingCleanupOfTheDestinationItJustRegistered() {
        KeepADBPreferences.addPendingWebhookCleanupUrl(context, URL);

        KeepADBRegisterClient.reportNow(context, null);
        settle();

        assertTrue("a stale DELETE must not follow the registration",
                KeepADBPreferences.getPendingWebhookCleanupUrls(context).isEmpty());
    }

    /** The registration of an earlier process is on record in the preferences, not in memory. */
    @Test
    public void aRegistrationRecordedByAnEarlierProcessIsKeptAsAPendingCleanupToo() {
        KeepADBPreferences.setWebhookReportSnapshot(context, URL_2, "10.0.0.9:1234",
                KeepADBPreferences.WEBHOOK_STATUS_SUCCESS, true);
        // resetForTesting() left the in-memory record uninitialized, like a fresh process.

        KeepADBRegisterClient.reportNow(context, null);
        settle();

        assertEquals(1, transport.getRequestCount());
        assertTrue(KeepADBPreferences.getPendingWebhookCleanupUrls(context).contains(URL_2));
    }

    /** An unexpected failure must not leave every later tap joining a report that never ends. */
    @Test
    public void anUnexpectedFailureFreesTheSlotForTheNextTap() {
        KeepADBRegisterClient.setEndpointProbeForTesting(endpoint -> {
            throw new IllegalStateException("probe blew up");
        });

        assertEquals(KeepADBRegisterClient.ReportNowResult.STARTED,
                KeepADBRegisterClient.reportNow(context, null));
        settle();
        assertEquals(0, transport.getRequestCount());
        assertEquals(KeepADBWidgetState.Activity.IDLE, webhook().activity);

        KeepADBRegisterClient.setEndpointProbeForTesting(endpoint -> true);
        assertEquals("not a join of something that is long over",
                KeepADBRegisterClient.ReportNowResult.STARTED,
                KeepADBRegisterClient.reportNow(context, null));
        settle();
        assertEquals(1, posts());
    }

    /** A retry left over from a series that was switched off by hand does not outlive the tap. */
    @Test
    public void aLeftoverRetryOfAnEarlierSeriesIsDroppedByTheOneShot() {
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        transport.setPostSuccess(false);
        KeepADBRegisterClient.updateEndpointAsync(context, HOST, PORT);
        settle();
        assertTrue(KeepADBRegisterClient.hasPendingPostRetryForTesting());
        int before = transport.getRequestCount();
        KeepADBPreferences.setRegisterWebhookEnabled(context, false); // off, nothing unregistered

        KeepADBRegisterClient.reportNow(context, null);
        settle();

        assertFalse("the one-shot owns the slot alone", KeepADBRegisterClient.hasPendingPostRetryForTesting());
        ShadowLooper.idleMainLooper(1, TimeUnit.HOURS);
        settle();
        assertEquals("one request by the tap, none by the old series", before + 1,
                transport.getRequestCount());
    }

    private Button clearButton() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setContentView(R.layout.activity_settings);
        new KeepADBWebhookForm(activity, () -> { });
        return activity.findViewById(R.id.settings_webhook_clear);
    }

    /**
     * Clearing the webhook with synchronization off withdraws what a deliberate report left
     * registered, so the URL is not dropped while the entry and a copy of the URL stay behind.
     */
    @Test
    public void clearingTheWebhookWithdrawsWhatTheOneShotRegistered() {
        KeepADBRegisterClient.reportNow(context, null);
        settle();
        assertEquals(1, posts());
        assertFalse(syncEnabled());
        Button clear = clearButton();

        clear.performClick();
        settle();

        assertEquals("DELETE at the URL that was cleared", 1, count("DELETE"));
        assertEquals(URL, transport.getLastRequest().url);
        assertNull(KeepADBPreferences.getRegisterWebhookUrl(context));
        assertNull("no copy of the cleared URL is kept", KeepADBPreferences.getWebhookLastReportedUrl(context));
        assertFalse(syncEnabled());
    }

    @Test
    public void clearingWhenNothingIsRegisteredSendsNothing() {
        Button clear = clearButton();

        clear.performClick();
        settle();

        assertEquals(0, transport.getRequestCount());
        assertNull(KeepADBPreferences.getRegisterWebhookUrl(context));
    }

    // ---- the shared state shows the operation -------------------------------------------------------

    @Test
    public void theSharedStateShowsTheOneShotFromSendingToDelivered() throws Exception {
        KeepADBWidgetState.Webhook before = webhook();
        assertEquals(KeepADBWidgetState.Sync.OFF, before.sync);
        assertEquals(KeepADBWidgetState.Activity.IDLE, before.activity);
        assertEquals(KeepADBWidgetState.Delivery.NOT_CURRENT, before.delivery);

        Gate gate = new Gate();
        KeepADBRegisterClient.reportNow(context, null);
        gate.awaitStarted();
        KeepADBWidgetState.Webhook during = webhook();
        assertEquals(KeepADBWidgetState.Activity.SENDING, during.activity);
        assertEquals("sending is not delivered", KeepADBWidgetState.Delivery.NOT_CURRENT,
                during.delivery);
        assertEquals(KeepADBWidgetState.Sync.OFF, during.sync);

        gate.release.countDown();
        settle();
        KeepADBWidgetState.Webhook after = webhook();
        assertEquals(KeepADBWidgetState.Activity.IDLE, after.activity);
        assertEquals(KeepADBWidgetState.Outcome.SUCCESS, after.latestOutcome);
        assertEquals(KeepADBWidgetState.Delivery.CURRENT, after.delivery);
        assertTrue(after.lastSuccessAtMs > 0L);
        assertEquals("a deliberate report does not turn the sync on", KeepADBWidgetState.Sync.OFF,
                after.sync);
    }

    @Test
    public void theSharedStateShowsAFailedOneShotAsFailedNotAsWaitingForARetry() {
        transport.setPostSuccess(false);

        KeepADBRegisterClient.reportNow(context, null);
        settle();

        KeepADBWidgetState.Webhook state = webhook();
        assertEquals(KeepADBWidgetState.Outcome.FAILED, state.latestOutcome);
        assertTrue(state.lastFailureAtMs > 0L);
        assertEquals("no retry is waiting", KeepADBWidgetState.Activity.IDLE, state.activity);
        assertEquals(KeepADBWidgetState.Delivery.NOT_CURRENT, state.delivery);
    }

    // ---- the destination stays out of every record the one-shot writes -------------------------------

    @Test
    public void noRecordOfAnyOutcomeNamesTheDestination() {
        KeepADBRegisterClient.reportNow(context, null);        // success
        settle();
        transport.setPostSuccess(false);
        KeepADBRegisterClient.reportNow(context, null);        // failure
        settle();
        KeepADBPreferences.setRegisterWebhookUrl(context, null);
        KeepADBRegisterClient.reportNow(context, null);        // refused
        settle();

        String diagnostics = KeepADBDiagnostics.export(context);
        assertTrue(diagnostics, diagnostics.contains("manual=true"));
        assertFalse(diagnostics, diagnostics.contains("register.example"));
        assertFalse(diagnostics, diagnostics.contains("dev1"));
        for (KeepADBReportHistory.Entry entry : KeepADBReportHistory.entries(context)) {
            assertFalse(entry.outcome, entry.outcome.contains("register.example"));
        }
    }

    // ---- automatic synchronization on: the established behavior -------------------------------------

    @Test
    public void withSyncOnTheTapReportsAgainAndTheSwitchStaysOn() {
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        KeepADBRegisterClient.updateEndpointAsync(context, HOST, PORT);
        settle();
        assertEquals(1, posts());

        assertEquals(KeepADBRegisterClient.ReportNowResult.STARTED,
                KeepADBRegisterClient.reportNow(context, null));
        settle();

        assertEquals("an explicit report is sent even for an unchanged endpoint", 2, posts());
        assertTrue(syncEnabled());
    }

    @Test
    public void withSyncOnTheTapJoinsARunningReportInsteadOfSendingInParallel() throws Exception {
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        Gate gate = new Gate();
        KeepADBRegisterClient.updateEndpointAsync(context, HOST, PORT);
        gate.awaitStarted();

        assertEquals(KeepADBRegisterClient.ReportNowResult.JOINED,
                KeepADBRegisterClient.reportNow(context, null));
        gate.release.countDown();
        settle();

        assertEquals(1, posts());
    }

    @Test
    public void withSyncOnACachedEndpointIsNotReusedWithoutWifiEither() {
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        wifi.set(false);

        assertEquals(KeepADBRegisterClient.ReportNowResult.NO_ENDPOINT,
                KeepADBRegisterClient.reportNow(context, null));
        settle();

        assertEquals(0, transport.getRequestCount());
    }

    @Test
    public void withSyncOnAnInvalidDestinationOrNoEndpointSendsNothingEither() {
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        KeepADBEndpointCoordinator.seedEndpointForTesting(null, 0, 0L);
        assertEquals(KeepADBRegisterClient.ReportNowResult.NO_ENDPOINT,
                KeepADBRegisterClient.reportNow(context, null));

        KeepADBEndpointCoordinator.seedEndpointForTesting(HOST, PORT, 1L);
        KeepADBPreferences.setRegisterWebhookUrl(context, null);
        assertEquals(KeepADBRegisterClient.ReportNowResult.NEEDS_SETUP,
                KeepADBRegisterClient.reportNow(context, null));
        settle();

        assertEquals(0, transport.getRequestCount());
    }

    private Object nextAlarm() {
        return shadowOf(context.getSystemService(AlarmManager.class)).peekNextScheduledAlarm();
    }
}
