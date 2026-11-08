package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

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
 * #911: the widgets' switch for automatic synchronization goes through the same validated enable
 * and disable path as the settings form: it needs a valid stored URL, never invents one, keeps the
 * opt-out DELETE and refreshes the app and the widgets.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBWebhookSyncTest {

    @Rule
    public final KeepADBNetworkResetRule networkResetRule = new KeepADBNetworkResetRule();
    @Rule
    public final KeepADBRegisterClientResetRule registerResetRule = new KeepADBRegisterClientResetRule();

    private static final String URL = "http://register.example/register/dev1";
    private static final String HOST = "10.20.30.40";
    private static final int PORT = 41873;

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
        KeepADBEndpointCoordinator.setReachabilityProbeForTesting((host, port, timeout) -> true);
        transport = new KeepADBFakeHttpTransport();
        KeepADBRegisterClient.setHttpTransport(transport);
    }

    @After
    public void tearDown() {
        KeepADBRegisterClientResetRule.awaitIdle();
        KeepADBEndpointCoordinator.setReachabilityProbeForTesting(null);
        KeepADBEndpointCoordinator.resetForTesting();
        KeepADBReportHistory.clear(context);
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADB.resetForTesting();
    }

    private int requests(String method) {
        int count = 0;
        for (KeepADBFakeHttpTransport.Request request
                : new java.util.ArrayList<>(transport.recordedRequests)) {
            if (method.equals(request.method)) count++;
        }
        return count;
    }

    @Test
    public void enablingUsesTheStoredUrlAndReportsTheCachedEndpoint() {
        KeepADBPreferences.setRegisterWebhookUrl(context, URL);
        KeepADBEndpointCoordinator.seedEndpointForTesting(HOST, PORT, 1L);

        KeepADBWebhookSync.Result result = KeepADBWebhookSync.setAutomaticSync(context, true);
        KeepADBRegisterClientResetRule.awaitIdle();

        assertEquals(KeepADBWebhookSync.Result.ENABLED, result);
        assertTrue(KeepADBPreferences.isRegisterWebhookEnabled(context));
        assertEquals("the cached endpoint is reported through the established path", 1,
                requests("POST"));
        assertEquals(URL, transport.getLastRequest().url);
    }

    @Test
    public void enablingWithoutAUrlChangesNothingAndSendsNothing() {
        KeepADBEndpointCoordinator.seedEndpointForTesting(HOST, PORT, 1L);

        KeepADBWebhookSync.Result result = KeepADBWebhookSync.setAutomaticSync(context, true);
        KeepADBRegisterClientResetRule.awaitIdle();

        assertEquals(KeepADBWebhookSync.Result.NEEDS_SETUP, result);
        assertFalse("never switched on without a destination",
                KeepADBPreferences.isRegisterWebhookEnabled(context));
        assertEquals(0, transport.getRequestCount());
    }

    @Test
    public void enablingWithAnInvalidStoredUrlChangesNothingAndSendsNothing() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit()
                .putString("register_webhook_url", "ftp://register.example/x").commit();
        KeepADBEndpointCoordinator.seedEndpointForTesting(HOST, PORT, 1L);

        KeepADBWebhookSync.Result result = KeepADBWebhookSync.setAutomaticSync(context, true);
        KeepADBRegisterClientResetRule.awaitIdle();

        assertEquals(KeepADBWebhookSync.Result.NEEDS_SETUP, result);
        assertFalse(KeepADBPreferences.isRegisterWebhookEnabled(context));
        assertEquals(0, transport.getRequestCount());
    }

    /** The opt-out keeps its semantics: the registration is withdrawn, then the switch goes off. */
    @Test
    public void disablingWithdrawsTheRegistrationAndSwitchesOff() {
        KeepADBPreferences.setRegisterWebhookUrl(context, URL);
        KeepADBEndpointCoordinator.seedEndpointForTesting(HOST, PORT, 1L);
        KeepADBWebhookSync.setAutomaticSync(context, true);
        KeepADBRegisterClientResetRule.awaitIdle();
        assertEquals(1, requests("POST"));

        KeepADBWebhookSync.Result result = KeepADBWebhookSync.setAutomaticSync(context, false);
        KeepADBRegisterClientResetRule.awaitIdle();

        assertEquals(KeepADBWebhookSync.Result.DISABLED, result);
        assertFalse(KeepADBPreferences.isRegisterWebhookEnabled(context));
        assertEquals("DELETE at the registered URL", 1, requests("DELETE"));
        assertEquals(URL, transport.getLastRequest().url);
        assertEquals(KeepADBPreferences.WEBHOOK_STATUS_DEREGISTERED,
                KeepADBPreferences.getWebhookLastReportStatus(context));
    }

    /** A repeated tap asks for a target state, so it cannot undo itself and sends nothing new. */
    @Test
    public void askingForTheStateItAlreadyHasDoesNothing() {
        KeepADBPreferences.setRegisterWebhookUrl(context, URL);

        assertEquals(KeepADBWebhookSync.Result.UNCHANGED,
                KeepADBWebhookSync.setAutomaticSync(context, false));
        KeepADBRegisterClientResetRule.awaitIdle();
        assertEquals("already off: no DELETE", 0, transport.getRequestCount());

        KeepADBEndpointCoordinator.seedEndpointForTesting(HOST, PORT, 1L);
        KeepADBWebhookSync.setAutomaticSync(context, true);
        KeepADBRegisterClientResetRule.awaitIdle();
        int sent = transport.getRequestCount();

        assertEquals(KeepADBWebhookSync.Result.UNCHANGED,
                KeepADBWebhookSync.setAutomaticSync(context, true));
        KeepADBRegisterClientResetRule.awaitIdle();
        assertTrue(KeepADBPreferences.isRegisterWebhookEnabled(context));
        assertEquals("already on: nothing sent", sent, transport.getRequestCount());
    }

    /** The app screen, the notification and the widgets are told whatever the result was. */
    @Test
    public void everyResultRefreshesTheApp() {
        KeepADBEndpointCoordinator.seedEndpointForTesting(HOST, PORT, 1L);
        AtomicInteger refreshed = new AtomicInteger();
        KeepADBRegisterClient.setRegisterStateListener(refreshed::incrementAndGet);

        int before = refreshed.get();
        assertEquals(KeepADBWebhookSync.Result.NEEDS_SETUP,
                KeepADBWebhookSync.setAutomaticSync(context, true));
        ShadowLooper.idleMainLooper();
        assertTrue("needs setup", refreshed.get() > before);

        KeepADBPreferences.setRegisterWebhookUrl(context, URL);
        before = refreshed.get();
        assertEquals(KeepADBWebhookSync.Result.ENABLED,
                KeepADBWebhookSync.setAutomaticSync(context, true));
        KeepADBRegisterClientResetRule.awaitIdle();
        ShadowLooper.idleMainLooper();
        assertTrue("enabled", refreshed.get() > before);

        before = refreshed.get();
        assertEquals(KeepADBWebhookSync.Result.UNCHANGED,
                KeepADBWebhookSync.setAutomaticSync(context, true));
        ShadowLooper.idleMainLooper();
        assertTrue("unchanged", refreshed.get() > before);

        before = refreshed.get();
        assertEquals(KeepADBWebhookSync.Result.DISABLED,
                KeepADBWebhookSync.setAutomaticSync(context, false));
        KeepADBRegisterClientResetRule.awaitIdle();
        ShadowLooper.idleMainLooper();
        assertTrue("disabled", refreshed.get() > before);
    }
}
