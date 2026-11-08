package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

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
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

/**
 * #911: "Report now" is a manual push into the normal flow of an enabled synchronization, and
 * nothing else. With synchronization off it does nothing and changes nothing; with it on it joins a
 * running POST, sends a waiting retry at once and drops a waiting debounced resync, so one tap
 * never sends two requests and the normal flow simply goes on afterwards.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBReportNowTest {

    @Rule
    public final KeepADBNetworkResetRule networkResetRule = new KeepADBNetworkResetRule();
    @Rule
    public final KeepADBRegisterClientResetRule registerResetRule = new KeepADBRegisterClientResetRule();

    private static final String URL = "http://register.example/register/dev1";
    private static final String HOST = "10.20.30.40";
    private static final int PORT = 41873;
    private static final String ENDPOINT = "10.20.30.40:41873";

    private Context context;
    private KeepADBFakeHttpTransport transport;
    private KeepADBFakeSettingsGateway gateway;
    private final AtomicBoolean wifi = new AtomicBoolean(true);

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
        gateway = new KeepADBFakeSettingsGateway(true);
        KeepADB.setGatewayForTesting(gateway);
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(wifi::get);
        transport = new KeepADBFakeHttpTransport();
        KeepADBRegisterClient.setHttpTransport(transport);
        // Saved destination and a verified endpoint; synchronization is off (the default).
        KeepADBPreferences.setRegisterWebhookUrl(context, URL);
        KeepADBEndpointCoordinator.seedEndpointForTesting(HOST, PORT, 1L);
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

    // ---- helpers ------------------------------------------------------------------------------

    /** Drains the register executor, the main-thread hand-offs and the executor again. */
    private void settle() {
        KeepADBRegisterClientResetRule.awaitIdle();
        ShadowLooper.idleMainLooper();
        KeepADBRegisterClientResetRule.awaitIdle();
    }

    private int posts() {
        int count = 0;
        for (KeepADBFakeHttpTransport.Request request : new ArrayList<>(transport.recordedRequests)) {
            if ("POST".equals(request.method)) count++;
        }
        return count;
    }

    private boolean syncEnabled() {
        return KeepADBPreferences.isRegisterWebhookEnabled(context);
    }

    private KeepADBWidgetState.Webhook webhook() {
        return KeepADBWidgetState.project(context).webhook;
    }

    private void syncOn() {
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
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

    // ---- synchronization off: nothing happens -----------------------------------------------------

    @Test
    public void withSyncOffItSendsNothingAndChangesNothing() {
        assertFalse("off before", syncEnabled());
        AtomicInteger done = new AtomicInteger();

        KeepADBRegisterClient.ReportNowResult result =
                KeepADBRegisterClient.reportNow(context, done::incrementAndGet);
        settle();

        assertEquals(KeepADBRegisterClient.ReportNowResult.SYNC_OFF, result);
        assertEquals("no request at all", 0, transport.getRequestCount());
        assertFalse("the tap never switches synchronization on", syncEnabled());
        assertEquals("the caller is released at once", 1, done.get());
        assertTrue("Wireless Debugging is never switched", gateway.writes.isEmpty());
        assertEquals("nothing is remembered as reported", KeepADBPreferences.WEBHOOK_STATUS_NEVER,
                KeepADBPreferences.getWebhookLastReportStatus(context));
        assertEquals(KeepADBWidgetState.Activity.IDLE, webhook().activity);
        assertEquals(KeepADBWidgetState.Outcome.NONE, webhook().latestOutcome);
        assertTrue(KeepADBDiagnostics.export(context),
                KeepADBDiagnostics.export(context).contains("reason=sync_off"));
    }

    @Test
    public void withSyncOffRepeatedTapsAndHoursLaterStillNothingHappens() {
        for (int i = 0; i < 5; i++) {
            assertEquals(KeepADBRegisterClient.ReportNowResult.SYNC_OFF,
                    KeepADBRegisterClient.reportNow(context, null));
        }
        ShadowLooper.idleMainLooper(6, TimeUnit.HOURS);
        settle();

        assertEquals(0, transport.getRequestCount());
        assertFalse(syncEnabled());
        assertFalse(KeepADBRegisterClient.hasPendingPostRetryForTesting());
    }

    /** Without a destination the setup is named first, whatever the switch says. */
    @Test
    public void withoutAValidDestinationTheSetupIsNamedBeforeTheSwitch() {
        KeepADBPreferences.setRegisterWebhookUrl(context, null);
        assertEquals(KeepADBRegisterClient.ReportNowResult.NEEDS_SETUP,
                KeepADBRegisterClient.reportNow(context, null));

        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit()
                .putString("register_webhook_url", "ftp://register.example/x").commit();
        assertEquals("a saved but invalid URL is no destination",
                KeepADBRegisterClient.ReportNowResult.NEEDS_SETUP,
                KeepADBRegisterClient.reportNow(context, null));

        syncOn();
        assertEquals(KeepADBRegisterClient.ReportNowResult.NEEDS_SETUP,
                KeepADBRegisterClient.reportNow(context, null));
        settle();

        assertEquals(0, transport.getRequestCount());
        assertTrue(KeepADBDiagnostics.export(context).contains("reason=needs_setup"));
    }

    // ---- synchronization on: a push into the normal flow --------------------------------------------

    @Test
    public void withSyncOnTheTapReportsTheCurrentEndpointAtOnce() {
        syncOn();
        AtomicInteger done = new AtomicInteger();

        KeepADBRegisterClient.ReportNowResult result =
                KeepADBRegisterClient.reportNow(context, done::incrementAndGet);
        settle();

        assertEquals(KeepADBRegisterClient.ReportNowResult.STARTED, result);
        assertEquals(1, posts());
        assertEquals(URL, transport.getLastRequest().url);
        assertTrue(transport.getLastRequest().payload, transport.getLastRequest().payload.contains(ENDPOINT));
        assertEquals(1, done.get());
        assertTrue("the switch stays on", syncEnabled());
        assertEquals(KeepADBPreferences.WEBHOOK_STATUS_SUCCESS,
                KeepADBPreferences.getWebhookLastReportStatus(context));
        assertTrue("Wireless Debugging is never switched", gateway.writes.isEmpty());
    }

    @Test
    public void withSyncOnAnExplicitReportIsSentEvenForAnUnchangedEndpoint() {
        syncOn();
        KeepADBRegisterClient.updateEndpointAsync(context, HOST, PORT);
        settle();
        assertEquals(1, posts());

        assertEquals(KeepADBRegisterClient.ReportNowResult.STARTED,
                KeepADBRegisterClient.reportNow(context, null));
        settle();

        assertEquals(2, posts());
    }

    @Test
    public void withSyncOnRepeatedTapsWhileAReportIsOnTheWireJoinItAndNeverSendInParallel()
            throws Exception {
        syncOn();
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
        assertTrue(syncEnabled());
    }

    @Test
    public void withSyncOnATapJoinsAReportThatTheAutomaticFlowIsSending() throws Exception {
        syncOn();
        Gate gate = new Gate();
        KeepADBRegisterClient.updateEndpointAsync(context, HOST, PORT); // the automatic report
        gate.awaitStarted();

        assertEquals(KeepADBRegisterClient.ReportNowResult.JOINED,
                KeepADBRegisterClient.reportNow(context, null));
        gate.release.countDown();
        settle();

        assertEquals(1, posts());
    }

    /** A retry that is only waiting for its timer is sent at once, and the series goes on normally. */
    @Test
    public void withSyncOnAWaitingRetryIsSentAtOnceInsteadOfAddingAnotherRequest() {
        syncOn();
        KeepADBPreferences.setWebhookRetryCount(context, 4);
        transport.setPostSuccess(false);
        KeepADBRegisterClient.updateEndpointAsync(context, HOST, PORT);
        settle();
        assertEquals(1, posts());
        assertTrue("a retry is waiting", KeepADBRegisterClient.hasPendingPostRetryForTesting());
        transport.setPostSuccess(true);

        assertEquals(KeepADBRegisterClient.ReportNowResult.STARTED,
                KeepADBRegisterClient.reportNow(context, null));
        settle();

        assertEquals("the waiting retry went out now; nothing was added", 2, posts());
        assertFalse("and its success ended the series", KeepADBRegisterClient.hasPendingPostRetryForTesting());
        ShadowLooper.idleMainLooper(1, TimeUnit.HOURS);
        settle();
        assertEquals("nothing follows", 2, posts());
        assertEquals(KeepADBWidgetState.Delivery.CURRENT, webhook().delivery);
    }

    @Test
    public void withSyncOnAWaitingDebouncedResyncIsDroppedSoOneTapSendsOneRequest() {
        syncOn();
        KeepADBRegisterClient.requestResyncAsync(context);
        assertTrue(KeepADBRegisterClient.hasPendingResyncForTesting());

        KeepADBRegisterClient.reportNow(context, null);
        settle();
        ShadowLooper.idleMainLooper(KeepADBRegisterClient.RESYNC_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        settle();

        assertEquals(1, posts());
    }

    @Test
    public void withSyncOnAFailedReportContinuesInTheNormalRetryFlow() {
        syncOn();
        KeepADBPreferences.setWebhookRetryCount(context, 2);
        transport.setPostSuccess(false);

        KeepADBRegisterClient.reportNow(context, null);
        settle();

        assertEquals(KeepADBPreferences.WEBHOOK_STATUS_FAILED,
                KeepADBPreferences.getWebhookLastReportStatus(context));
        assertTrue("the normal retry series follows the manual push",
                KeepADBRegisterClient.hasPendingPostRetryForTesting());
        assertTrue("and so does the normal failure episode",
                KeepADBPreferences.getWebhookFailureSince(context) > 0L);
        assertTrue(syncEnabled());
    }

    @Test
    public void withSyncOnAnUntrustedNetworkDoesNotStopTheReport() {
        syncOn();
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        connectedTo("HomeNetX", "aa:bb:cc:dd:ee:ff");
        assertEquals("precondition: this network is not trusted",
                KeepADBTrustedNetwork.BlockReason.UNTRUSTED_NETWORK,
                KeepADBTrustedNetwork.getBlockReason(context));

        assertEquals(KeepADBRegisterClient.ReportNowResult.STARTED,
                KeepADBRegisterClient.reportNow(context, null));
        settle();

        assertEquals("reported although the network is untrusted", 1, posts());
        assertTrue("and it did not switch anything on", gateway.writes.isEmpty());
    }

    // ---- no verified endpoint -----------------------------------------------------------------------

    @Test
    public void withoutAVerifiedEndpointNothingIsSentAndWirelessDebuggingStaysAsItIs() {
        syncOn();
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
        assertTrue(KeepADBDiagnostics.export(context).contains("reason=no_endpoint"));
    }

    @Test
    public void aCachedEndpointIsNotReusedWithoutWifi() {
        syncOn();
        wifi.set(false);

        assertEquals(KeepADBRegisterClient.ReportNowResult.NO_ENDPOINT,
                KeepADBRegisterClient.reportNow(context, null));
        settle();

        assertEquals("a stale address is not reported", 0, transport.getRequestCount());
    }

    // ---- the destination stays out of every record -------------------------------------------------

    @Test
    public void noRecordOfAnyOutcomeNamesTheDestination() {
        KeepADBRegisterClient.reportNow(context, null);       // sync off
        syncOn();
        KeepADBRegisterClient.reportNow(context, null);       // success
        settle();
        transport.setPostSuccess(false);
        KeepADBRegisterClient.reportNow(context, null);       // failure
        settle();
        wifi.set(false);
        KeepADBRegisterClient.reportNow(context, null);       // refused
        settle();

        String diagnostics = KeepADBDiagnostics.export(context);
        assertTrue(diagnostics, diagnostics.contains("manual=true"));
        assertFalse(diagnostics, diagnostics.contains("register.example"));
        assertFalse(diagnostics, diagnostics.contains("dev1"));
        for (KeepADBReportHistory.Entry entry : KeepADBReportHistory.entries(context)) {
            assertFalse(entry.outcome, entry.outcome.contains("register.example"));
        }
    }
}
