package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Looper;

import androidx.test.core.app.ApplicationProvider;

import java.lang.reflect.Field;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAppWidgetManager;

/**
 * #910: the system half of the shared widget state projection: Wireless Debugging, Keep-Alive and
 * the endpoint, plus the privacy behaviour and the guarantee that projecting only reads. The
 * webhook delivery half is in {@link KeepADBWidgetStateDeliveryTest}.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBWidgetStateTest {

    @Rule
    public final KeepADBNetworkResetRule networkResetRule = new KeepADBNetworkResetRule();
    @Rule
    public final KeepADBRegisterClientResetRule registerResetRule = new KeepADBRegisterClientResetRule();

    private static final String HOST = "10.20.30.40";
    private static final int PORT = 41873;
    private static final String SSID = "HomeNetX";
    private static final String BSSID = "aa:bb:cc:dd:ee:ff";
    private static final String URL = "http://register.example/register/dev1";

    private Context context;
    private KeepADBFakeSettingsGateway gateway;

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
        gateway = new KeepADBFakeSettingsGateway(false);
        KeepADB.setGatewayForTesting(gateway);
    }

    @After
    public void tearDown() {
        KeepADBRegisterClientResetRule.awaitIdle();
        KeepADBEndpointCoordinator.resetForTesting();
        KeepADBReportHistory.clear(context);
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADB.resetForTesting();
    }

    private void wifi(boolean connected) {
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> connected);
    }

    private void connectedTo(String ssid, String bssid) {
        WifiManager manager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        shadowOf(manager).setConnectionInfo(
                new WifiInfo.Builder().setSsid(ssid.getBytes()).setBssid(bssid).build());
    }

    private KeepADBWidgetState project() {
        return KeepADBWidgetState.project(context);
    }

    // ---- Wireless Debugging ---------------------------------------------------------------

    @Test
    public void wirelessDebuggingOffAndOnAreDistinct() {
        wifi(true);
        gateway = new KeepADBFakeSettingsGateway(false);
        KeepADB.setGatewayForTesting(gateway);
        assertEquals(KeepADBWidgetState.Adb.OFF, project().adb);

        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(true));
        assertEquals(KeepADBWidgetState.Adb.ON, project().adb);
        assertEquals(KeepADBWidgetState.Reason.NONE, project().adbFailure);
    }

    /** A toggle in its debounce window is pending, not the stale value in either direction. */
    @Test
    public void aToggleInItsDebounceWindowIsPending() {
        KeepADBFakeScheduler scheduler = new KeepADBFakeScheduler();
        KeepADB.setSchedulerForTesting(scheduler);
        assertEquals(KeepADBWidgetState.Adb.OFF, project().adb);

        KeepADB.setEnabled(context, true, "content_observer");
        assertTrue(KeepADB.isTogglePending());
        assertFalse("nothing was written yet", KeepADB.isEnabled(context));
        assertEquals(KeepADBWidgetState.Adb.PENDING, project().adb);

        scheduler.advanceBy(KeepADB.TOGGLE_COOLDOWN_MS);
        assertEquals(KeepADBWidgetState.Adb.ON, project().adb);
    }

    @Test
    public void aMissingGrantAndAnUnreadableSettingAreToldApart() {
        shadowOf((Application) context).denyPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
        KeepADBWidgetState missing = project();
        assertEquals(KeepADBWidgetState.Adb.PERMISSION_FAILURE, missing.adb);
        assertEquals(KeepADBWidgetState.Reason.PERMISSION_NOT_GRANTED, missing.adbFailure);

        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
        KeepADB.setGatewayForTesting(new KeepADBSettingsGateway() {
            @Override
            public boolean isEnabled(Context ctx) {
                throw new SecurityException("read refused by the platform");
            }

            @Override
            public boolean write(Context appContext, boolean on) {
                return false;
            }
        });
        KeepADBWidgetState unreadable = project();
        assertEquals(KeepADBWidgetState.Adb.PERMISSION_FAILURE, unreadable.adb);
        assertEquals("with the grant present the hint is not the one-time grant",
                KeepADBWidgetState.Reason.SETTING_UNREADABLE, unreadable.adbFailure);
    }

    // ---- Keep-Alive -----------------------------------------------------------------------

    @Test
    public void keepAliveIsDisabledActiveOrWaitingWithAReason() {
        wifi(true);
        assertEquals(KeepADBWidgetState.KeepAlive.DISABLED, project().keepAlive);

        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(true));
        KeepADBWidgetState active = project();
        assertEquals(KeepADBWidgetState.KeepAlive.ACTIVE, active.keepAlive);
        assertEquals(KeepADBWidgetState.Reason.NONE, active.keepAliveReason);

        // Off, intent on, every Wi-Fi trusted, no backoff: resolves by itself.
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));
        KeepADB.recordExplicitIntent(context, true);
        KeepADBWidgetState waiting = project();
        assertEquals(KeepADBWidgetState.KeepAlive.WAITING, waiting.keepAlive);
        assertEquals(KeepADBWidgetState.Reason.NONE, waiting.keepAliveReason);

        wifi(false);
        KeepADBWidgetState noWifi = project();
        assertEquals(KeepADBWidgetState.KeepAlive.WAITING, noWifi.keepAlive);
        assertEquals(KeepADBWidgetState.Reason.NO_WIFI, noWifi.keepAliveReason);
    }

    @Test
    public void keepAliveOnAfterAnExplicitOffIsBlockedByTheUser() {
        wifi(true);
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADB.recordExplicitIntent(context, false);

        KeepADBWidgetState state = project();
        assertEquals(KeepADBWidgetState.KeepAlive.BLOCKED, state.keepAlive);
        assertEquals(KeepADBWidgetState.Reason.MANUAL_OFF, state.keepAliveReason);
        assertEquals("a manual off is an off, not a Keep-Alive wait",
                KeepADB.State.OFF, state.systemState);
    }

    @Test
    public void keepAliveBlockedByTheNetworkNamesTheCause() {
        wifi(true);
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADB.recordExplicitIntent(context, true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);

        // No readable BSSID: the identity is unavailable.
        KeepADBWidgetState identity = project();
        assertEquals(KeepADBWidgetState.KeepAlive.BLOCKED, identity.keepAlive);
        assertEquals(KeepADBWidgetState.Reason.NETWORK_IDENTITY_UNAVAILABLE, identity.keepAliveReason);

        // A readable but unlisted access point: untrusted.
        connectedTo(SSID, BSSID);
        KeepADBWidgetState untrusted = project();
        assertEquals(KeepADBWidgetState.KeepAlive.BLOCKED, untrusted.keepAlive);
        assertEquals(KeepADBWidgetState.Reason.UNTRUSTED_NETWORK, untrusted.keepAliveReason);

        // Trusting it unblocks.
        KeepADBTrustedNetwork.addBssid(context, BSSID, "Home");
        assertEquals(KeepADBWidgetState.KeepAlive.WAITING, project().keepAlive);
    }

    @Test
    public void keepAlivePausedByTheRecoveryBackoffIsWaitingWithThatReason() {
        wifi(true);
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADB.recordExplicitIntent(context, true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        KeepADB.setGatewayForTesting(new KeepADBStuckOffSettingsGateway());
        assertTrue(KeepADB.setEnabled(context, true, "keep_alive_check").isSuccess());
        android.os.SystemClock.sleep(1600);
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1600));
        assertTrue("precondition: the accepted-but-ineffective write engaged the backoff",
                KeepADB.isAutomaticEnableBackoffBlocked());

        KeepADBWidgetState state = project();
        assertEquals(KeepADBWidgetState.KeepAlive.WAITING, state.keepAlive);
        assertEquals(KeepADBWidgetState.Reason.RECOVERY_BACKOFF, state.keepAliveReason);
    }

    @Test
    public void keepAliveWithoutTheGrantIsBlockedWithThePermissionHint() {
        shadowOf((Application) context).denyPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
        KeepADBPreferences.setKeepAliveEnabled(context, true);

        KeepADBWidgetState state = project();
        assertEquals(KeepADBWidgetState.KeepAlive.BLOCKED, state.keepAlive);
        assertEquals(KeepADBWidgetState.Reason.PERMISSION_NOT_GRANTED, state.keepAliveReason);
    }

    /**
     * The projection explains a Keep-Alive wait with the same predicates as the status card, so the
     * widgets can never name a different cause than the app does (#458, #496).
     */
    @Test
    public void keepAliveReasonAgreesWithTheStatusCardResolver() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADB.recordExplicitIntent(context, true);

        wifi(false);
        assertEquals(MainActivity.KeepAliveWaitingDetail.WIFI_DISCONNECTED,
                MainActivity.resolveKeepAliveWaitingDetail(context));
        assertEquals(KeepADBWidgetState.Reason.NO_WIFI, project().keepAliveReason);

        wifi(true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        assertEquals(MainActivity.KeepAliveWaitingDetail.BLOCKED_IDENTITY_UNAVAILABLE,
                MainActivity.resolveKeepAliveWaitingDetail(context));
        assertEquals(KeepADBWidgetState.Reason.NETWORK_IDENTITY_UNAVAILABLE,
                project().keepAliveReason);

        connectedTo(SSID, BSSID);
        assertEquals(MainActivity.KeepAliveWaitingDetail.BLOCKED_UNTRUSTED_NETWORK,
                MainActivity.resolveKeepAliveWaitingDetail(context));
        assertEquals(KeepADBWidgetState.Reason.UNTRUSTED_NETWORK, project().keepAliveReason);
    }

    // ---- Endpoint ------------------------------------------------------------------------

    @Test
    public void theEndpointIsAvailableWhateverWirelessDebuggingReads() {
        wifi(true);
        KeepADBEndpointCoordinator.seedEndpointForTesting(HOST, PORT, 1234L);

        // Off: availability does not depend on the toggle read (the coordinator clears it by itself).
        KeepADBWidgetState off = project();
        assertEquals(KeepADBWidgetState.Adb.OFF, off.adb);
        assertEquals(KeepADBWidgetState.EndpointAvailability.AVAILABLE, off.endpoint);
        assertEquals(HOST, off.endpointHost);
        assertEquals(PORT, off.endpointPort);
        assertEquals(1234L, off.endpointVerifiedAtMs);

        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(true));
        KeepADBWidgetState on = project();
        assertEquals(KeepADBWidgetState.EndpointAvailability.AVAILABLE, on.endpoint);
        assertEquals(KeepADB.State.ENABLED_CONNECTED, on.systemState);
    }

    @Test
    public void aMissingEndpointNamesItsKnowableCause() {
        wifi(true);
        assertEquals(KeepADBWidgetState.EndpointAvailability.ADB_OFF, project().endpoint);

        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(true));
        assertEquals(KeepADBWidgetState.EndpointAvailability.SEARCHING, project().endpoint);

        wifi(false);
        assertEquals(KeepADBWidgetState.EndpointAvailability.NO_WIFI, project().endpoint);

        shadowOf((Application) context).denyPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
        assertEquals(KeepADBWidgetState.EndpointAvailability.UNKNOWN, project().endpoint);
    }

    /** A cached address is not believed once Wi-Fi is gone, like {@link KeepADB#getState}. */
    @Test
    public void aCachedEndpointIsNotAvailableWithoutWifi() {
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(true));
        wifi(false);
        KeepADBEndpointCoordinator.seedEndpointForTesting(HOST, PORT, 1L);

        KeepADBWidgetState state = project();
        assertEquals(KeepADBWidgetState.EndpointAvailability.NO_WIFI, state.endpoint);
        assertNull(state.endpointHost);
        assertEquals(0, state.endpointPort);
        assertEquals(0L, state.endpointVerifiedAtMs);
    }

    // ---- Privacy -------------------------------------------------------------------------

    @Test
    public void theWidgetSwitchWithholdsTheAddressButNotTheAvailability() {
        wifi(true);
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(true));
        KeepADBEndpointCoordinator.seedEndpointForTesting(HOST, PORT, 99L);

        KeepADBWidgetState shown = project();
        assertFalse(shown.detailsHidden);
        assertEquals(HOST, shown.endpointHost);
        assertEquals(PORT, shown.endpointPort);

        KeepADBPreferences.setWidgetPrivacyEnabled(context, true);
        KeepADBWidgetState hidden = project();
        assertTrue(hidden.detailsHidden);
        assertNull(hidden.endpointHost);
        assertEquals(0, hidden.endpointPort);
        assertEquals("availability is state, not detail",
                KeepADBWidgetState.EndpointAvailability.AVAILABLE, hidden.endpoint);
        assertEquals(KeepADB.State.ENABLED_CONNECTED, hidden.systemState);
    }

    /** Only the widget switch reaches the projection; the app and notification switches do not. */
    @Test
    public void theOtherTwoPrivacySwitchesDoNotReachTheProjection() {
        wifi(true);
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(true));
        KeepADBEndpointCoordinator.seedEndpointForTesting(HOST, PORT, 99L);
        KeepADBPreferences.setAppPrivacyEnabled(context, true);
        KeepADBPreferences.setNotificationPrivacyEnabled(context, true);
        assertFalse(KeepADBPreferences.isWidgetPrivacyEnabled(context));

        KeepADBWidgetState state = project();
        assertFalse(state.detailsHidden);
        assertEquals(HOST, state.endpointHost);
    }

    /**
     * The per-widget option can only hide more. The middle row is the precedence: with the global
     * switch on, a widget that did not ask for hiding still gets none of the details.
     */
    @Test
    public void thePerWidgetOptionIsStricterAndNeverLiftsTheGlobalSwitch() {
        wifi(true);
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(true));
        KeepADBEndpointCoordinator.seedEndpointForTesting(HOST, PORT, 99L);

        // switch off, option off: shown
        assertEquals(HOST, KeepADBWidgetState.project(context, false).endpointHost);
        // switch off, option on: hidden by the option
        assertNull(KeepADBWidgetState.project(context, true).endpointHost);

        KeepADBPreferences.setWidgetPrivacyEnabled(context, true);
        // switch on, option off: the switch takes precedence
        assertNull(KeepADBWidgetState.project(context, false).endpointHost);
        assertEquals(0, KeepADBWidgetState.project(context, false).endpointPort);
        // switch on, option on: hidden
        assertNull(KeepADBWidgetState.project(context, true).endpointHost);
    }

    /**
     * Nothing identifying is anywhere in a hidden projection, whichever field it would land in:
     * every string and port-like field of the projection and of its webhook half is scanned for the
     * host, the port, the Wi-Fi name, the BSSID and the webhook URL. The first half (details shown)
     * proves the scan can see the host and the port at all.
     */
    @Test
    public void aHiddenProjectionCarriesNoAddressNameOrUrlInAnyField() throws Exception {
        wifi(true);
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(true));
        connectedTo(SSID, BSSID);
        KeepADBEndpointCoordinator.seedEndpointForTesting(HOST, PORT, 99L);
        KeepADBPreferences.setRegisterWebhookUrl(context, URL);
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        KeepADBPreferences.setKeepAliveEnabled(context, true);

        String[] secrets = {HOST, SSID, BSSID, "register.example", "dev1"};
        assertTrue("control: the scan finds the host while details are shown",
                scan(project(), secrets, PORT));

        KeepADBPreferences.setWidgetPrivacyEnabled(context, true);
        assertFalse(scan(project(), secrets, PORT));
        assertFalse(scan(KeepADBWidgetState.project(context, true), secrets, PORT));
    }

    private static boolean scan(Object projected, String[] secrets, int port) throws Exception {
        boolean found = false;
        for (Field field : projected.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            Object value = field.get(projected);
            if (value == null) continue;
            if (value instanceof KeepADBWidgetState.Webhook) {
                found |= scan(value, secrets, port);
            } else if (value instanceof Integer) {
                found |= ((Integer) value) == port;
            } else if (value instanceof String || value instanceof Enum) {
                for (String secret : secrets) {
                    found |= String.valueOf(value).contains(secret);
                }
            }
        }
        return found;
    }

    // ---- Reading only --------------------------------------------------------------------

    /**
     * Projecting and re-rendering state send nothing and start no discovery or timer, even with
     * the webhook on and an endpoint that was never reported -- the one case in which a refresh
     * through the coordinator would send a POST.
     */
    @Test
    public void projectingAndRenderingStateNeitherSendNorStartDiscovery() {
        KeepADBFakeHttpTransport transport = new KeepADBFakeHttpTransport();
        KeepADBRegisterClient.setHttpTransport(transport);
        KeepADBPreferences.setRegisterWebhookUrl(context, URL);
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);

        // Placing the widget runs the provider's own first update (legacy, with the coordinator
        // refresh), so it is placed while Wireless Debugging is off and nothing can start.
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ShadowAppWidgetManager shadowManager = shadowOf(manager);
        int widgetId = shadowManager.createWidget(KeepADBWidget.class, R.layout.widget_keepadb);
        assertNotNull(shadowManager.getViewFor(widgetId));
        KeepADBRegisterClientResetRule.awaitIdle();
        transport.clearRequests();

        wifi(true);
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(true));
        KeepADBEndpointCoordinator.seedEndpointForTesting(HOST, PORT, 99L);

        for (int i = 0; i < 3; i++) {
            project();
            KeepADBWidget.refreshAllState(context);
        }
        shadowOf(Looper.getMainLooper()).idle();
        KeepADBRegisterClientResetRule.awaitIdle();

        assertEquals("no report", 0, transport.getRequestCount());
        assertEquals(KeepADBRegisterClient.Activity.IDLE, KeepADBRegisterClient.activity());
        assertFalse("no discovery", KeepADBEndpointCoordinator.hasActiveDiscoveryAttemptForTesting());
        assertEquals("the endpoint stays unreported", KeepADBWidgetState.Delivery.NOT_CURRENT,
                project().webhook.delivery);
    }
}
