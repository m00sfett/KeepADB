package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowConnectivityManager;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowNetwork;
import org.robolectric.shadows.ShadowWifiInfo;

/**
 * #625: with {@link KeepADBService} running -- its Wi-Fi {@code NetworkCallback} registered, i.e.
 * exactly the situation the removed SSID continuity cache (#270/#354/#620) was written for -- a
 * masked reading of the current network is never trusted, not even right after the same SSID was
 * verified trusted with a readable BSSID, and not after any {@code onAvailable}/{@code onLost}
 * callback or the callback's unregistration. Goes through the production entry points
 * ({@link KeepADBTrustedNetwork#isCurrentNetworkTrusted} and {@link
 * KeepADBTrustedNetwork#getBlockReason}) against the real {@code WifiManager} shadow.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBTrustedNetworkFailClosedServiceTest {

    private static final String HOME_BSSID = "aa:bb:cc:dd:ee:ff";

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = RuntimeEnvironment.getApplication();

    @Before
    public void setUp() {
        shadowOf((Application) context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
        resetState();
    }

    @After
    public void tearDown() {
        resetState();
    }

    private void resetState() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("keepadb_diagnostics", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADBEndpointCoordinator.resetForTesting();
        KeepADB.resetForTesting();
        KeepADBRegisterClient.resetForTesting();
    }

    @Test
    public void maskedCurrentNetworkFailsClosedWhileTheServiceObservesTheConnection() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBTrustedNetwork.addBssid(context, HOME_BSSID, "Home");
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBPreferences.setLastDesiredOn(context, true);
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(true));
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        connectTo("Home", HOME_BSSID);

        ConnectivityManager connectivityManager = context.getSystemService(ConnectivityManager.class);
        ShadowConnectivityManager shadowConnectivityManager = shadowOf(connectivityManager);
        ServiceController<KeepADBService> controller = Robolectric.buildService(KeepADBService.class);
        try {
            controller.create();
            controller.get().onStartCommand(new Intent(context, KeepADBService.class), 0, 1);
            ShadowLooper.idleMainLooper();
            assertFalse("Precondition: the service must be observing Wi-Fi changes",
                    shadowConnectivityManager.getNetworkCallbacks().isEmpty());

            assertVerifiedThenMaskedFailsClosed("while the callback is registered");

            Network newNetwork = ShadowNetwork.newInstance(3001);
            for (ConnectivityManager.NetworkCallback callback
                    : shadowConnectivityManager.getNetworkCallbacks()) {
                callback.onAvailable(newNetwork);
            }
            ShadowLooper.idleMainLooper();
            assertVerifiedThenMaskedFailsClosed("after onAvailable");

            for (ConnectivityManager.NetworkCallback callback
                    : shadowConnectivityManager.getNetworkCallbacks()) {
                callback.onLost(newNetwork);
            }
            ShadowLooper.idleMainLooper();
            assertVerifiedThenMaskedFailsClosed("after onLost");
        } finally {
            controller.destroy();
        }
        ShadowLooper.idleMainLooper();
        assertVerifiedThenMaskedFailsClosed("after the callback was unregistered");
    }

    private void assertVerifiedThenMaskedFailsClosed(String phase) {
        connectTo("Home", HOME_BSSID);
        assertTrue("Readable listed BSSID must be trusted " + phase,
                KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
        assertEquals(KeepADBTrustedNetwork.BlockReason.NONE,
                KeepADBTrustedNetwork.getBlockReason(context));

        connectTo("Home", KeepADBNetworkIdentity.REDACTED_BSSID);
        assertFalse("Masked reading of a just-verified SSID must fail closed " + phase,
                KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
        assertEquals("Masked reading must be reported as an unavailable identity " + phase,
                KeepADBTrustedNetwork.BlockReason.IDENTITY_UNAVAILABLE,
                KeepADBTrustedNetwork.getBlockReason(context));
    }

    private void connectTo(String ssid, String bssid) {
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = ShadowWifiInfo.newInstance();
        shadowOf(info).setSSID(ssid);
        shadowOf(info).setBSSID(bssid);
        shadowOf(wifiManager).setConnectionInfo(info);
    }
}
