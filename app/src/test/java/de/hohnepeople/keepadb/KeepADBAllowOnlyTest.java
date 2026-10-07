package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowWifiInfo;

/**
 * #654: {@link KeepADBReceiver#allowBssidOnly} is the path every Settings allow action takes. It
 * grants exactly one allowlist entry and clears the question that entry answers; it never writes
 * Wireless Debugging itself. The contrast test runs the former trust-and-connect path on the very
 * same setup to prove that the setup is one in which an enable really would have happened -- so
 * "nothing was written" cannot be a vacuous result.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBAllowOnlyTest {

    private static final String BSSID = "aa:bb:cc:dd:ee:01";
    private static final String OTHER_BSSID = "aa:bb:cc:dd:ee:02";

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = ApplicationProvider.getApplicationContext();
    private KeepADBFakeSettingsGateway gateway;

    @Before
    public void setUp() {
        prefs().edit().clear().commit();
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.POST_NOTIFICATIONS,
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
        KeepADB.resetForTesting();
        // Every gate of an automatic enable is open: Keep-Alive on, Wi-Fi connected, all
        // networks accepted, Wireless Debugging currently off.
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        gateway = new KeepADBFakeSettingsGateway(false);
        KeepADB.setGatewayForTesting(gateway);
        connectTo("Cafe-WLAN", BSSID);
    }

    @After
    public void tearDown() {
        prefs().edit().clear().commit();
        KeepADB.resetForTesting();
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(null);
    }

    @Test
    public void theSetupIsOneInWhichTheFormerPathReallySwitchesWirelessDebuggingOn() {
        assertTrue("Control: trust-and-connect enables here",
                KeepADBReceiver.trustBssidAndAttemptConnect(context, BSSID, "Cafe-WLAN").enabled);
        assertEquals(1, gateway.writes.size());
        assertTrue(KeepADB.isEnabled(context));
    }

    @Test
    public void allowingGrantsTheEntryAndWritesNothing() {
        KeepADBTrustedNetwork.Entry entry =
                KeepADBReceiver.allowBssidOnly(context, BSSID, "Cafe-WLAN");

        assertNotNull(entry);
        assertEquals(BSSID, entry.bssid);
        assertEquals("Cafe-WLAN", entry.label);
        assertEquals(1, KeepADBTrustedNetwork.getEntries(context).size());
        assertTrue("Allowing starts no enable action: " + gateway.writes, gateway.writes.isEmpty());
        assertFalse(KeepADB.isEnabled(context));
    }

    @Test
    public void allowingClearsTheQuestionItAnswersAndOnlyThat() {
        assertTrue(KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));
        assertNotNull(postedPrompt());
        connectTo("Hotel-WLAN", OTHER_BSSID);
        assertTrue(KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));
        long now = System.currentTimeMillis();
        assertFalse(KeepADBNetworkTrustPrompt.shouldPrompt(context, BSSID, now));
        assertFalse(KeepADBNetworkTrustPrompt.shouldPrompt(context, OTHER_BSSID, now));

        KeepADBReceiver.allowBssidOnly(context, BSSID, "Cafe-WLAN");

        assertNull("The prompt of the allowed access point goes away", postedPrompt());
        assertTrue("Its own marker is cleared",
                KeepADBNetworkTrustPrompt.shouldPrompt(context, BSSID, now));
        assertFalse("Another access point's anti-spam marker stays (#474)",
                KeepADBNetworkTrustPrompt.shouldPrompt(context, OTHER_BSSID, now));
        java.util.List<KeepADBBlockedNetworkHistory.Entry> history =
                KeepADBBlockedNetworkHistory.getEntries(context);
        assertEquals("Only the allowed access point leaves the history", 1, history.size());
        assertEquals(OTHER_BSSID, history.get(0).bssid);
    }

    @Test
    public void allowingTwiceKeepsOneEntryAndNeverTouchesTheNameOrModeSettings() {
        KeepADBTrustedNetwork.setSsidMatchingEnabled(context, true);
        KeepADBTrustedNetwork.addSsid(context, "Mesh");

        KeepADBReceiver.allowBssidOnly(context, BSSID, "Cafe-WLAN");
        KeepADBReceiver.allowBssidOnly(context, BSSID.toUpperCase(java.util.Locale.ROOT), "Cafe-WLAN");

        assertEquals("The same access point is one entry", 1,
                KeepADBTrustedNetwork.getEntries(context).size());
        assertEquals(KeepADBTrustedNetwork.MODE_ALL_WIFI, KeepADBTrustedNetwork.getMode(context));
        assertTrue(KeepADBTrustedNetwork.isSsidMatchingEnabled(context));
        assertEquals(1, KeepADBTrustedNetwork.getSsidEntries(context).size());
    }

    @Test
    public void aBlankBssidStoresNothing() {
        assertNull(KeepADBReceiver.allowBssidOnly(context, null, "x"));
        assertNull(KeepADBReceiver.allowBssidOnly(context, "  ", "x"));
        assertTrue(KeepADBTrustedNetwork.getEntries(context).isEmpty());
        assertTrue(gateway.writes.isEmpty());
    }

    // --- helpers ------------------------------------------------------------------------------

    private Notification postedPrompt() {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        return shadowOf(manager).getNotification(KeepADBNetworkTrustPrompt.NOTIFICATION_ID);
    }

    private void connectTo(String ssid, String bssid) {
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = ShadowWifiInfo.newInstance();
        shadowOf(info).setSSID(ssid);
        shadowOf(info).setBSSID(bssid);
        shadowOf(wifiManager).setConnectionInfo(info);
    }

    private android.content.SharedPreferences prefs() {
        return context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE);
    }
}
