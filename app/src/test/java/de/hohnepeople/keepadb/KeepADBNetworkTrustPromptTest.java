package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import androidx.test.core.app.ApplicationProvider;

import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowNotificationManager;
import org.robolectric.shadows.ShadowWifiInfo;

/**
 * Behavior of the untrusted-network prompt (#446): when it is raised, when it must stay quiet,
 * and what its two action buttons actually do.
 *
 * <p>The load-bearing property is that the prompt never fails open. The allow action is the only
 * path from here into {@link KeepADBTrustedNetwork}, and declining, throttling or cancelling must
 * leave the allowlist untouched -- each of those is asserted separately below, because a prompt
 * that silently trusted a network would defeat the whole allowlist introduced in #245.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBNetworkTrustPromptTest {

    private static final String BSSID = "aa:bb:cc:dd:ee:01";
    private static final String OTHER_BSSID = "aa:bb:cc:dd:ee:02";

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = ApplicationProvider.getApplicationContext();

    @Before
    public void setUp() {
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.POST_NOTIFICATIONS,
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
        prefs().edit().clear().commit();
        KeepADB.resetForTesting();
        KeepADBTrustedNetwork.resetVerifiedTrustForTesting();
    }

    @After
    public void tearDown() {
        prefs().edit().clear().commit();
        KeepADB.resetForTesting();
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(null);
        KeepADBTrustedNetwork.resetVerifiedTrustForTesting();
    }

    // --- Raising and throttling -------------------------------------------------------------

    @Test
    public void aBlockOnAReadableAccessPointRaisesThePromptWithBothActions() {
        connectTo("Cafe-WLAN", BSSID);

        assertTrue(KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));

        Notification notification = postedPrompt();
        assertNotNull("The block must raise a prompt notification", notification);
        assertEquals(2, notification.actions.length);
        assertEquals(KeepADBReceiver.ACTION_TRUST_NETWORK, actionIntent(notification, 0).getAction());
        assertEquals(KeepADBReceiver.ACTION_DISMISS_NETWORK_PROMPT,
                actionIntent(notification, 1).getAction());
        assertEquals(BSSID,
                actionIntent(notification, 0).getStringExtra(KeepADBNetworkTrustPrompt.EXTRA_BSSID));
        // The SSID is what the user recognizes; the BSSID disambiguates a mesh access point.
        String text = notification.extras.getString(Notification.EXTRA_TEXT);
        assertTrue("Prompt text must name the SSID: " + text, text.contains("Cafe-WLAN"));
        assertTrue("Prompt text must name the BSSID: " + text, text.contains(BSSID));
    }

    @Test
    public void thePromptUsesItsOwnAudibleChannelSeparateFromTheSilentServiceChannel() {
        connectTo("Cafe-WLAN", BSSID);
        KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context);

        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel =
                manager.getNotificationChannel(KeepADBNetworkTrustPrompt.CHANNEL_ID);
        assertNotNull(channel);
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.getImportance());
        assertFalse("A question must not share the ongoing service notification's id",
                KeepADBNetworkTrustPrompt.NOTIFICATION_ID == KeepADBNotification.NOTIFICATION_ID);
    }

    @Test
    public void theBlockedAccessPointIsRecordedForTheSettingsTransparencyList() {
        connectTo("Cafe-WLAN", BSSID);
        KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context);

        List<KeepADBBlockedNetworkHistory.Entry> entries =
                KeepADBBlockedNetworkHistory.getEntries(context);
        assertEquals(1, entries.size());
        assertEquals(BSSID, entries.get(0).bssid);
    }

    /**
     * The regression this throttle exists for: both block sites are reached repeatedly (the 60s
     * heartbeat and every {@code adb_wifi_enabled} change), so an unthrottled prompt would
     * re-alert the user every minute for as long as they stay on the untrusted network.
     */
    @Test
    public void aSecondBlockOnTheSameAccessPointDoesNotPromptAgain() {
        connectTo("Cafe-WLAN", BSSID);

        assertTrue(KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));
        assertFalse(KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));
        assertFalse(KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));
    }

    @Test
    public void movingToADifferentAccessPointPromptsAgain() {
        connectTo("Cafe-WLAN", BSSID);
        assertTrue(KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));

        connectTo("Hotel-WLAN", OTHER_BSSID);
        assertTrue(KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));
    }

    /**
     * Issue #450: flapping between two untrusted APs (e.g. mesh edge or weak signal) must not
     * re-fire the prompt on every roam back and forth.
     */
    @Test
    public void flappingBetweenTwoUntrustedAccessPointsDoesNotRefirePrompt() {
        connectTo("Cafe-WLAN", BSSID);
        assertTrue("First visit to Cafe-WLAN must prompt",
                KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));

        connectTo("Hotel-WLAN", OTHER_BSSID);
        assertTrue("First visit to Hotel-WLAN must prompt",
                KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));

        // Roam back to Cafe-WLAN within the throttle interval
        connectTo("Cafe-WLAN", BSSID);
        assertFalse("Roam back to Cafe-WLAN must be throttled, not re-prompt",
                KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));

        // Roam back to Hotel-WLAN within the throttle interval
        connectTo("Hotel-WLAN", OTHER_BSSID);
        assertFalse("Roam back to Hotel-WLAN must be throttled, not re-prompt",
                KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));

        // A third, genuinely new untrusted AP must prompt
        String thirdBssid = "aa:bb:cc:dd:ee:03";
        connectTo("Airport-WLAN", thirdBssid);
        assertTrue("A third unseen AP must still prompt",
                KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));
    }

    @Test
    public void promptHistoryEvictsOldestWhenCapacityExceeded() {
        for (int i = 0; i < KeepADBNetworkTrustPrompt.MAX_PROMPTED_BSSIDS; i++) {
            String bssid = String.format("aa:bb:cc:dd:ee:%02x", i);
            connectTo("Test-WLAN-" + i, bssid);
            assertTrue(KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));
        }

        // All entered BSSIDs should currently be throttled
        long checkNow = System.currentTimeMillis();
        for (int i = 0; i < KeepADBNetworkTrustPrompt.MAX_PROMPTED_BSSIDS; i++) {
            String bssid = String.format("aa:bb:cc:dd:ee:%02x", i);
            assertFalse("BSSID " + bssid + " must be throttled",
                    KeepADBNetworkTrustPrompt.shouldPrompt(context, bssid, checkNow));
        }

        // Adding one more should evict the oldest (index 0)
        String newBssid = "aa:bb:cc:dd:ee:ff";
        connectTo("New-WLAN", newBssid);
        assertTrue(KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));

        long afterEvictNow = System.currentTimeMillis();
        String oldestBssid = String.format("aa:bb:cc:dd:ee:%02x", 0);
        assertTrue("Oldest BSSID must have been evicted and should prompt again",
                KeepADBNetworkTrustPrompt.shouldPrompt(context, oldestBssid, afterEvictNow));
        assertFalse("Newly added BSSID must be throttled",
                KeepADBNetworkTrustPrompt.shouldPrompt(context, newBssid, afterEvictNow));
    }

    @Test
    public void clearPromptStateClearsAllPromptedBssids() {
        connectTo("Cafe-WLAN", BSSID);
        assertTrue(KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));
        connectTo("Hotel-WLAN", OTHER_BSSID);
        assertTrue(KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));

        KeepADBNetworkTrustPrompt.clearPromptState(context);

        long now = System.currentTimeMillis();
        assertTrue(KeepADBNetworkTrustPrompt.shouldPrompt(context, BSSID, now));
        assertTrue(KeepADBNetworkTrustPrompt.shouldPrompt(context, OTHER_BSSID, now));
    }

    /**
     * The counter-question to "does the gate ever fire?": does the suppression ever stop? A
     * throttle that no user action clears would be a permanently switched-off alarm for exactly
     * the failure this issue is about, so it must expire on its own.
     */
    @Test
    public void theSuppressionExpiresAndDoesNotSurviveAClockMovedBackwards() {
        connectTo("Cafe-WLAN", BSSID);
        KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context);
        // The marker is stamped with wall-clock time by the production path above, so the
        // reference point here has to be the same clock.
        long now = System.currentTimeMillis();

        assertFalse(KeepADBNetworkTrustPrompt.shouldPrompt(context, BSSID, now));
        assertTrue(KeepADBNetworkTrustPrompt.shouldPrompt(context, BSSID,
                now + KeepADBNetworkTrustPrompt.PROMPT_REPEAT_INTERVAL_MS));
        assertTrue("A backwards clock must not suppress forever",
                KeepADBNetworkTrustPrompt.shouldPrompt(context, BSSID, 0L));
        assertFalse(KeepADBNetworkTrustPrompt.shouldPrompt(context, "  ", now));
    }

    @Test
    public void anUnreadableIdentityNeitherPromptsNorGetsRecorded() {
        connectTo("Cafe-WLAN", KeepADBNetworkIdentity.REDACTED_BSSID);

        assertFalse(KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));
        assertNull(postedPrompt());
        assertTrue(KeepADBBlockedNetworkHistory.getEntries(context).isEmpty());
    }

    @Test
    public void raisingAndThrottlingThePromptNeverTrustsAnything() {
        connectTo("Cafe-WLAN", BSSID);
        KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context);
        KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context);
        KeepADBNetworkTrustPrompt.cancel(context);

        assertTrue(KeepADBTrustedNetwork.getEntries(context).isEmpty());
        assertFalse(KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
    }

    // --- The action buttons -----------------------------------------------------------------

    @Test
    public void allowingAddsTheAccessPointToTheAllowlistAndEnablesWirelessDebugging() {
        // MODE_ALL_WIFI makes isAutoEnableStillPermitted's trust check pass under Robolectric,
        // whose WifiManager identity the allowlist branch cannot be driven through; the point of
        // this test is the handler's own behavior once the guard says yes.
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));
        KeepADBBlockedNetworkHistory.record(context, identity("Cafe-WLAN", BSSID), 1L);

        assertTrue(KeepADBReceiver.handleTrustNetworkAction(context, BSSID, "Cafe-WLAN"));

        List<KeepADBTrustedNetwork.Entry> entries = KeepADBTrustedNetwork.getEntries(context);
        assertEquals(1, entries.size());
        assertEquals(BSSID, entries.get(0).bssid);
        assertEquals("Cafe-WLAN", entries.get(0).label);
        assertTrue("Wireless Debugging must be on after an explicit allow",
                KeepADB.isEnabled(context));
        // The access point is answered, so it leaves the "recently blocked" list.
        assertTrue(KeepADBBlockedNetworkHistory.getEntries(context).isEmpty());
        assertNull("The answered prompt must go away", postedPrompt());
    }

    /**
     * A notification can be tapped arbitrarily late. Allowing the access point the user actually
     * saw must never turn Wireless Debugging on wherever the device happens to be by then.
     */
    @Test
    public void allowingStillAllowlistsButDoesNotEnableWhenTheGuardNoLongerPermitsIt() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> false);
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));

        assertFalse(KeepADBReceiver.handleTrustNetworkAction(context, BSSID, "Cafe-WLAN"));

        assertEquals(1, KeepADBTrustedNetwork.getEntries(context).size());
        assertFalse(KeepADB.isEnabled(context));
    }

    @Test
    public void allowingRefusesAPlaceholderBssidInsteadOfAllowlistingAFailOpenWildcard() {
        for (String bssid : new String[] {null, "", "   ",
                KeepADBNetworkIdentity.REDACTED_BSSID, KeepADBNetworkIdentity.UNSET_BSSID}) {
            assertFalse(KeepADBReceiver.handleTrustNetworkAction(context, bssid, "Cafe-WLAN"));
            assertTrue("Must not allowlist placeholder BSSID: " + bssid,
                    KeepADBTrustedNetwork.getEntries(context).isEmpty());
        }
    }

    @Test
    public void decliningTrustsNothingAndOnlyRemovesThePrompt() {
        connectTo("Cafe-WLAN", BSSID);
        KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context);
        assertNotNull(postedPrompt());

        KeepADBReceiver.handleDismissNetworkPromptAction(context);

        assertNull(postedPrompt());
        assertTrue(KeepADBTrustedNetwork.getEntries(context).isEmpty());
        assertFalse(KeepADB.isEnabled(context));
        // Declining is anti-spam only, so the access point stays visible in Settings, where the
        // user can still allow it later.
        assertEquals(1, KeepADBBlockedNetworkHistory.getEntries(context).size());
        // ... and it does not prompt again right away.
        assertFalse(KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));
    }

    @Test
    public void theReceiverRoutesBothActionsAndIgnoresEverythingElse() {
        KeepADBReceiver receiver = new KeepADBReceiver();
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));

        receiver.onReceive(context, new Intent("com.example.UNKNOWN"));
        assertTrue(KeepADBTrustedNetwork.getEntries(context).isEmpty());

        receiver.onReceive(context, new Intent(KeepADBReceiver.ACTION_TRUST_NETWORK)
                .putExtra(KeepADBNetworkTrustPrompt.EXTRA_BSSID, BSSID)
                .putExtra(KeepADBNetworkTrustPrompt.EXTRA_LABEL, "Cafe-WLAN"));
        assertEquals(1, KeepADBTrustedNetwork.getEntries(context).size());

        receiver.onReceive(context, new Intent(KeepADBReceiver.ACTION_DISMISS_NETWORK_PROMPT));
        assertEquals(1, KeepADBTrustedNetwork.getEntries(context).size());
    }

    // --- helpers ----------------------------------------------------------------------------

    private static KeepADBNetworkIdentity identity(String ssid, String bssid) {
        return new KeepADBNetworkIdentity(ssid, bssid);
    }

    private void connectTo(String ssid, String bssid) {
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = ShadowWifiInfo.newInstance();
        shadowOf(info).setSSID(ssid);
        shadowOf(info).setBSSID(bssid);
        shadowOf(wifiManager).setConnectionInfo(info);
    }

    private Notification postedPrompt() {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        ShadowNotificationManager shadow = shadowOf(manager);
        return shadow.getNotification(KeepADBNetworkTrustPrompt.NOTIFICATION_ID);
    }

    private Intent actionIntent(Notification notification, int index) {
        return shadowOf(notification.actions[index].actionIntent).getSavedIntent();
    }

    private android.content.SharedPreferences prefs() {
        return context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE);
    }
}
