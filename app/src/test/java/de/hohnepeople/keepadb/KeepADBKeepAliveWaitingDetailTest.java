package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Regression tests for #458: {@link KeepADB.State#OFF_KEEP_ALIVE_WAITING} used to render one
 * message no matter why Keep-Alive hadn't re-enabled yet, which made a genuinely connected but
 * untrusted (or unreadable) network look exactly like no Wi-Fi at all. {@link
 * MainActivity#resolveKeepAliveWaitingDetail(Context)} is the shared resolution the status card
 * renders from; these tests pin its three outcomes directly, independent of the view layer.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBKeepAliveWaitingDetailTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit();
        KeepADBTrustedNetwork.resetVerifiedTrustForTesting();
        KeepADB.resetForTesting();
    }

    @After
    public void tearDown() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit();
        KeepADBTrustedNetwork.resetVerifiedTrustForTesting();
        KeepADB.resetForTesting();
    }

    @Test
    public void noWifiAtAllIsReportedAsWifiDisconnected() {
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> false);

        assertEquals(MainActivity.KeepAliveWaitingDetail.WIFI_DISCONNECTED,
                MainActivity.resolveKeepAliveWaitingDetail(context));
    }

    @Test
    public void connectedToAnUnreadableNetworkIsReportedAsIdentityUnavailable() {
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        // Default Robolectric WifiManager connection info carries no BSSID, i.e. exactly the
        // "Location permission missing/denied" placeholder KeepADBNetworkIdentity#isKnown()
        // rejects -- no extra shadow setup needed to exercise this branch.
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);

        assertEquals(MainActivity.KeepAliveWaitingDetail.BLOCKED_IDENTITY_UNAVAILABLE,
                MainActivity.resolveKeepAliveWaitingDetail(context));
    }

    @Test
    public void connectedToAKnownButUnlistedNetworkIsReportedAsUntrusted() {
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        setConnectedBssid("aa:bb:cc:dd:ee:ff");
        // The allowlist stays empty, so this readable BSSID is known but not trusted.

        assertEquals(MainActivity.KeepAliveWaitingDetail.BLOCKED_UNTRUSTED_NETWORK,
                MainActivity.resolveKeepAliveWaitingDetail(context));
    }

    @Test
    public void connectedToATrustedNetworkFallsBackToTheGenericWaitingDetail() {
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        setConnectedBssid("aa:bb:cc:dd:ee:ff");
        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:ff", "Home");

        // Trusted networks don't stay in OFF_KEEP_ALIVE_WAITING in practice (Keep-Alive would
        // already be re-enabling), but the resolver must still degrade to the generic waiting
        // text rather than mislabel this transient window as a block.
        assertEquals(MainActivity.KeepAliveWaitingDetail.WIFI_DISCONNECTED,
                MainActivity.resolveKeepAliveWaitingDetail(context));
    }

    /**
     * #496: a trusted, connected network whose last automatic enable was accepted but never
     * actually flipped Wireless Debugging on must render as the new, explained
     * {@code BLOCKED_RECOVERY_BACKOFF} detail rather than silently falling back to the generic
     * waiting text this resolver used before the readback-mismatch backoff existed.
     */
    @Test
    public void connectedToATrustedNetworkWithAStaleReadbackIsReportedAsRecoveryBackoff() {
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        setConnectedBssid("aa:bb:cc:dd:ee:ff");
        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:ff", "Home");
        KeepADB.setGatewayForTesting(new KeepADBStuckOffSettingsGateway());

        assertTrue("precondition: the accepted-but-ineffective write must engage the backoff",
                KeepADB.setEnabled(context, true, "keep_alive_check"));
        // The automatic enable is debounced (#310); let the scheduled write actually land.
        android.os.SystemClock.sleep(1600);
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1600));
        assertTrue(KeepADB.isAutomaticEnableBackoffBlocked());

        assertEquals(MainActivity.KeepAliveWaitingDetail.BLOCKED_RECOVERY_BACKOFF,
                MainActivity.resolveKeepAliveWaitingDetail(context));
    }

    private void setConnectedBssid(String bssid) {
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = new WifiInfo.Builder().setBssid(bssid).build();
        shadowOf(wifiManager).setConnectionInfo(info);
    }
}
