package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.KeyguardManager;
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
import org.robolectric.shadows.ShadowKeyguardManager;
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
    }

    @After
    public void tearDown() {
        prefs().edit().clear().commit();
        KeepADB.resetForTesting();
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(null);
    }

    // --- Raising and throttling -------------------------------------------------------------

    @Test
    public void aBlockOnAReadableAccessPointRaisesThePromptWithBothActions() {
        // #592: SSID/BSSID only appear in the prompt text after the opt-in.
        KeepADBPreferences.setNotificationDetailsEnabled(context, true);
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
        assertTrue("Prompt text must name the BSSID: " + text,
                text.contains(BSSID.toUpperCase(java.util.Locale.ROOT)));
        // #598: with details on, the content intent stays the plain Settings entry it always was
        // -- the in-app confirmation is only the details-off replacement for the allow action.
        Intent content = shadowOf(notification.contentIntent).getSavedIntent();
        assertEquals(SettingsActivity.class.getName(), content.getComponent().getClassName());
        assertNull(content.getAction());
        assertFalse(content.hasExtra(KeepADBNetworkTrustPrompt.EXTRA_BSSID));
    }

    @Test
    public void anUnreadableSsidUsesTheUppercaseBssidAsItsPromptLabel() {
        assertEquals("AA:BB:CC:DD:EE:01", KeepADBNetworkTrustPrompt.labelFor(
                new KeepADBNetworkIdentity(WifiManager.UNKNOWN_SSID, BSSID)));
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
     * #474: the BSSID-targeted counterpart must forget only the access point named, leaving any
     * other access point's throttle marker intact.
     */
    @Test
    public void clearPromptStateForOneBssidLeavesTheOthersThrottleMarkerIntact() {
        connectTo("Cafe-WLAN", BSSID);
        assertTrue(KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));
        connectTo("Hotel-WLAN", OTHER_BSSID);
        assertTrue(KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));

        KeepADBNetworkTrustPrompt.clearPromptState(context, BSSID);

        long now = System.currentTimeMillis();
        assertTrue("The trusted access point's marker must be gone",
                KeepADBNetworkTrustPrompt.shouldPrompt(context, BSSID, now));
        assertFalse("A different, still-untrusted access point's marker must survive",
                KeepADBNetworkTrustPrompt.shouldPrompt(context, OTHER_BSSID, now));
    }

    /**
     * #474: the regression this issue is about, end to end through the real entry point. Trusting
     * access point A from the main screen (or the notification) must not silently make the
     * currently-connected, still-untrusted access point B's own pending prompt notification
     * un-repeatable -- B was never trusted and must still get its own re-prompt after B's
     * suppression window would otherwise have already lapsed (simulated here by moving the clock
     * forward less than that window, which used to be irrelevant because the old global clear
     * wiped B's marker outright, making {@code shouldPrompt} return true regardless of elapsed
     * time).
     */
    @Test
    public void trustingOneAccessPointDoesNotClearAnotherAccessPointsPendingPromptState() {
        // B is the currently-connected, untrusted access point with a pending prompt.
        connectTo("Hotel-WLAN", OTHER_BSSID);
        assertTrue("B must be prompted once",
                KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));

        // The user instead trusts a different, historical access point A from the main screen.
        KeepADBReceiver.trustBssidAndAttemptConnect(context, BSSID, "Cafe-WLAN");

        // B's own throttle marker must still be exactly what it was: within the suppression
        // window, still throttled -- not wiped by A's trust action.
        long shortlyAfter = System.currentTimeMillis();
        assertFalse("B's pending prompt state must survive trusting a different access point",
                KeepADBNetworkTrustPrompt.shouldPrompt(context, OTHER_BSSID, shortlyAfter));
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

    /**
     * #460: an unreadable identity used to be swallowed silently. It must now surface its own
     * notification (not the allow/block prompt -- there is no BSSID to act on) while still never
     * entering the blocked-access-point history, which only makes sense for a real, matchable
     * network.
     */
    @Test
    public void anUnreadableIdentityRaisesItsOwnNotificationInsteadOfBeingSwallowed() {
        connectTo("Cafe-WLAN", KeepADBNetworkIdentity.REDACTED_BSSID);

        assertTrue(KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));

        Notification notification = postedPrompt();
        assertNotNull("The unreadable identity must raise its own notification", notification);
        assertEquals(0, notification.actions == null ? 0 : notification.actions.length);
        assertTrue(KeepADBBlockedNetworkHistory.getEntries(context).isEmpty());
    }

    /**
     * #643 (AK1): with ACCESS_BACKGROUND_LOCATION the identity is readable after a background
     * start (measurement "Nachtrag 5", API 30 to 36.1), so the real call path -- {@link
     * KeepADBNetworkTrustPrompt#onBlockedByUntrustedNetwork} and {@link
     * KeepADBTrustedNetwork#getBlockReason} -- must see an ordinary unlisted network: the
     * allow/block prompt and UNTRUSTED_NETWORK, never the identity-unavailable fallback.
     */
    @Test
    public void aReadableIdentityRaisesTheAllowBlockPromptAndNotTheIdentityUnavailableFallback() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        connectTo("Cafe-WLAN", BSSID);

        assertEquals(KeepADBTrustedNetwork.BlockReason.UNTRUSTED_NETWORK,
                KeepADBTrustedNetwork.getBlockReason(context));
        assertTrue(KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));

        Notification notification = postedPrompt();
        assertNotNull(notification);
        assertEquals(context.getString(R.string.network_prompt_title),
                String.valueOf(notification.extras.getCharSequence(Notification.EXTRA_TITLE)));
        assertEquals("The readable network belongs in the blocked history", 1,
                KeepADBBlockedNetworkHistory.getEntries(context).size());
    }

    /**
     * #643 (AK1), counterpart: without the background grant the identity stays masked and the
     * fallback (block reason and its own notification) must remain -- fail closed.
     */
    @Test
    public void aMaskedIdentityStillYieldsTheIdentityUnavailableFallbackInAllowlistMode() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        connectTo("Cafe-WLAN", KeepADBNetworkIdentity.REDACTED_BSSID);

        assertEquals(KeepADBTrustedNetwork.BlockReason.IDENTITY_UNAVAILABLE,
                KeepADBTrustedNetwork.getBlockReason(context));
        assertTrue(KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));

        Notification notification = postedPrompt();
        assertNotNull(notification);
        assertEquals(context.getString(R.string.network_prompt_identity_unavailable_title),
                String.valueOf(notification.extras.getCharSequence(Notification.EXTRA_TITLE)));
        assertTrue(KeepADBBlockedNetworkHistory.getEntries(context).isEmpty());
    }

    /**
     * Same throttle mechanism as the BSSID-keyed prompt (#460): repeated blocks on an unreadable
     * identity must not re-alert on every heartbeat.
     */
    @Test
    public void aSecondBlockOnAnUnreadableIdentityDoesNotNotifyAgain() {
        connectTo("Cafe-WLAN", KeepADBNetworkIdentity.REDACTED_BSSID);

        assertTrue(KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));
        assertFalse(KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));
        assertFalse(KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));
    }

    /**
     * #460: the click path differs by likely cause -- missing/denied ACCESS_FINE_LOCATION sends
     * the user to this app's system permission page rather than just to Settings, where they
     * would otherwise have to figure out the fix on their own.
     */
    @Test
    public void theIdentityUnavailableNotificationLinksToTheAppPermissionPageWhenPermissionIsMissing() {
        connectTo("Cafe-WLAN", KeepADBNetworkIdentity.REDACTED_BSSID);
        shadowOf((Application) context).denyPermissions(
                android.Manifest.permission.ACCESS_FINE_LOCATION);

        assertTrue(KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));

        Notification notification = postedPrompt();
        Intent target = shadowOf(notification.contentIntent).getSavedIntent();
        assertEquals(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                target.getAction());
        assertEquals("package:" + context.getPackageName(), target.getData().toString());
    }

    /**
     * When the permission IS granted, an unreadable identity means location services are off
     * (the other documented cause), so the click path must go to the system location toggle
     * instead of repeating the same app-permission screen that would show nothing to fix.
     */
    @Test
    public void theIdentityUnavailableNotificationLinksToLocationSettingsWhenPermissionIsGrantedButLocationIsOff() {
        connectTo("Cafe-WLAN", KeepADBNetworkIdentity.REDACTED_BSSID);
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.ACCESS_FINE_LOCATION);
        android.location.LocationManager locationManager =
                (android.location.LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        shadowOf(locationManager).setLocationEnabled(false);

        assertTrue(KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));

        Notification notification = postedPrompt();
        Intent target = shadowOf(notification.contentIntent).getSavedIntent();
        assertEquals(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS, target.getAction());
    }

    /**
     * #628: measured for #626 (see {@code docs/trusted-networks-measurement.md}, "Nachtrag 3") --
     * on API 33, when both permission and location service are fine and the identity is still
     * unavailable, the actual cause is a Keep-Alive service that was started from the background
     * (boot, a sticky restart, or a background {@code sync()}) and never received a While-in-Use
     * location grant for its foreground-service record. Neither the app-permission page nor the
     * location toggle fixes that -- only promoting the service through a foreground start does,
     * and only {@link MainActivity#onResume()} does that (it unconditionally calls {@link
     * KeepADBService#sync}). {@link SettingsActivity} does not call {@code sync()} on resume and
     * therefore cannot re-promote the service. So the click path must open {@link MainActivity},
     * not fall back to {@link SettingsActivity} as it used to.
     */
    @Test
    public void theIdentityUnavailableNotificationOpensMainActivityWhenPermissionAndLocationAreBothFine() {
        connectTo("Cafe-WLAN", KeepADBNetworkIdentity.REDACTED_BSSID);
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.ACCESS_FINE_LOCATION);
        android.location.LocationManager locationManager =
                (android.location.LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        shadowOf(locationManager).setLocationEnabled(true);

        assertTrue(KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));

        Notification notification = postedPrompt();
        Intent target = shadowOf(notification.contentIntent).getSavedIntent();
        assertEquals("The fix path must open MainActivity, whose onResume() promotes the "
                        + "service back to foreground via sync() -- SettingsActivity does not "
                        + "call sync() and cannot re-promote the service",
                MainActivity.class.getName(), target.getComponent().getClassName());
    }

    @Test
    public void raisingAndThrottlingThePromptNeverTrustsAnything() {
        // #492: allowlist mode is now an opt-in, so the "is this network trusted" assertion below
        // needs it stated -- otherwise MODE_ALL_WIFI trusts unconditionally and the assertion
        // would pass for the wrong reason.
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
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

    /**
     * #470: the main screen's per-access-point trust button calls {@link
     * KeepADBReceiver#trustBssidAndAttemptConnect} directly, not {@link
     * KeepADBReceiver#handleTrustNetworkAction} -- so this pins that entry point separately:
     * trusting a network from anywhere in the app must make an already-showing prompt disappear,
     * not only a tap on the notification's own "allow" action.
     */
    @Test
    public void trustingANetworkAnywhereInTheAppDismissesAnAlreadyPostedPrompt() {
        connectTo("Cafe-WLAN", BSSID);
        assertTrue(KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));
        assertNotNull(postedPrompt());

        KeepADBReceiver.trustBssidAndAttemptConnect(context, BSSID, "Cafe-WLAN");

        assertNull("Trusting a network anywhere in the app must dismiss the prompt", postedPrompt());
        assertEquals(1, KeepADBTrustedNetwork.getEntries(context).size());
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

    // --- #578: locked-screen authentication -------------------------------------------------

    /**
     * #578: trusting a network can re-enable Wireless Debugging, so the allow action must ask the
     * platform to reauthenticate the user before its PendingIntent fires when the notification is
     * reached from a locked screen. The block action stays ungated -- declining is the safe
     * direction (nothing is trusted either way) and gating it would only make it harder to get rid
     * of an unwanted prompt while locked.
     */
    @Test
    public void theAllowActionRequiresAuthenticationButTheBlockActionDoesNot() {
        // #598: the allow action only exists with connection details on.
        KeepADBPreferences.setNotificationDetailsEnabled(context, true);
        connectTo("Cafe-WLAN", BSSID);
        assertTrue(KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));

        Notification notification = postedPrompt();
        assertTrue("Allow must require authentication (API 31+)",
                notification.actions[0].isAuthenticationRequired());
        assertFalse("Block must stay ungated -- declining never trusts anything",
                notification.actions[1].isAuthenticationRequired());
    }

    /**
     * #578: the lock screen must not leak which access point is asking to be trusted. The default
     * visibility is already VISIBILITY_PRIVATE (unchanged here), but a publicVersion is required so
     * a device configured to show private notification content on the lock screen (as the s20
     * tested against #578 was) does not also show the label and BSSID there.
     */
    @Test
    public void thePublicVersionNamesNeitherTheLabelNorTheBssid() {
        KeepADBPreferences.setNotificationDetailsEnabled(context, true);
        connectTo("Cafe-WLAN", BSSID);
        assertTrue(KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));

        Notification notification = postedPrompt();
        assertNotNull("A publicVersion must be set for the lock screen", notification.publicVersion);
        String publicText = notification.publicVersion.extras.getString(Notification.EXTRA_TEXT);
        assertFalse("publicVersion must not name the SSID: " + publicText,
                publicText != null && publicText.contains("Cafe-WLAN"));
        assertFalse("publicVersion must not name the BSSID: " + publicText,
                publicText != null && publicText.contains(BSSID.toUpperCase(java.util.Locale.ROOT)));
    }

    /**
     * #592/#598: with the opt-in off (the default), neither the private prompt nor its
     * publicVersion may name the network or the BSSID anywhere visible -- Android shows the private
     * copy on the lock screen when sensitive content is allowed there. #598: and since the user
     * cannot see which network it is, there is no allow action either -- only block remains, and
     * the text points to the in-app confirmation instead.
     */
    @Test
    public void withDetailsOffThePromptNamesNeitherTheNetworkNorTheBssidAndHasNoAllowAction() {
        connectTo("Cafe-WLAN", BSSID);
        assertTrue(KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));

        Notification notification = postedPrompt();
        assertNotNull(notification);
        KeepADBNotificationTextScan.assertMentionsNone(notification, "Cafe-WLAN", BSSID,
                BSSID.toUpperCase(java.util.Locale.ROOT));
        String confirmText = context.getString(R.string.network_prompt_confirm_in_app_text);
        assertEquals(confirmText,
                notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString());
        assertEquals(confirmText,
                notification.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString());
        assertEquals("Only the block action may remain", 1, notification.actions.length);
        for (int i = 0; i < notification.actions.length; i++) {
            assertFalse("No action may trust a network the user cannot see",
                    KeepADBReceiver.ACTION_TRUST_NETWORK.equals(actionIntent(notification, i).getAction()));
        }
        assertEquals(KeepADBReceiver.ACTION_DISMISS_NETWORK_PROMPT,
                actionIntent(notification, 0).getAction());
    }

    /**
     * #598: the details-off tap opens SettingsActivity's confirmation for exactly the access point
     * the prompt was raised for. The intent needs its own action so it can never be matched --
     * and have its extras overwritten by FLAG_UPDATE_CURRENT -- by the plain SettingsActivity
     * PendingIntent the USB notification posts with the same request code 0.
     */
    @Test
    public void withDetailsOffTheContentIntentOpensTheInAppConfirmationForThePromptedBssid() {
        connectTo("Cafe-WLAN", BSSID);
        assertTrue(KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));

        Intent content = shadowOf(postedPrompt().contentIntent).getSavedIntent();
        assertEquals(SettingsActivity.class.getName(), content.getComponent().getClassName());
        assertEquals(KeepADBNetworkTrustPrompt.ACTION_CONFIRM_IN_APP, content.getAction());
        assertEquals(BSSID, content.getStringExtra(KeepADBNetworkTrustPrompt.EXTRA_BSSID));
        assertFalse("The label must come from the app's own record, not from the intent",
                content.hasExtra(KeepADBNetworkTrustPrompt.EXTRA_LABEL));
        assertFalse(content.filterEquals(new Intent(context, SettingsActivity.class)));
        assertTrue("Tapping the prompt must not trust anything by itself",
                KeepADBTrustedNetwork.getEntries(context).isEmpty());
    }

    /**
     * #598: the in-app confirmation only ever offers an access point the app itself recorded as
     * blocked, and names it with the recorded label; anything else resolves to "nothing to
     * confirm".
     */
    @Test
    public void pendingConfirmationOnlyResolvesBssidsTheAppItselfRecordedAsBlocked() {
        KeepADBBlockedNetworkHistory.record(context, identity("\"Cafe-WLAN\"", BSSID), 1L);

        KeepADBBlockedNetworkHistory.Entry entry =
                KeepADBNetworkTrustPrompt.pendingConfirmation(context, " AA:BB:CC:DD:EE:01 ");
        assertNotNull(entry);
        assertEquals(BSSID, entry.bssid);
        assertEquals("Cafe-WLAN", entry.label());

        for (String bssid : new String[] {null, "", "   ", OTHER_BSSID,
                KeepADBNetworkIdentity.REDACTED_BSSID, KeepADBNetworkIdentity.UNSET_BSSID}) {
            assertNull("Must not resolve: " + bssid,
                    KeepADBNetworkTrustPrompt.pendingConfirmation(context, bssid));
        }

        // Once trusted (from anywhere), the question is answered and no longer pending.
        KeepADBReceiver.trustBssidAndAttemptConnect(context, BSSID, "Cafe-WLAN");
        assertNull(KeepADBNetworkTrustPrompt.pendingConfirmation(context, BSSID));
    }

    /** #592: the re-post after a rejected locked tap honors the opt-in as well. */
    @Test
    public void withDetailsOffTheLockedReshowNamesNeitherTheNetworkNorTheBssid() {
        lockDevice();

        assertFalse(KeepADBReceiver.handleTrustNetworkAction(context, BSSID, "Cafe-WLAN"));

        Notification reshown = postedPrompt();
        assertNotNull(reshown);
        KeepADBNotificationTextScan.assertMentionsNone(reshown, "Cafe-WLAN", BSSID,
                BSSID.toUpperCase(java.util.Locale.ROOT));
    }

    @Test
    public void withDetailsOnThePromptNamesNetworkAndBssidInTextAndBigText() {
        KeepADBPreferences.setNotificationDetailsEnabled(context, true);
        connectTo("Cafe-WLAN", BSSID);
        assertTrue(KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));

        Notification notification = postedPrompt();
        String bigText = String.valueOf(notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        assertTrue(bigText, bigText.contains("Cafe-WLAN")
                && bigText.contains(BSSID.toUpperCase(java.util.Locale.ROOT)));
        KeepADBNotificationTextScan.assertMentionsNone(notification.publicVersion, "Cafe-WLAN", BSSID,
                BSSID.toUpperCase(java.util.Locale.ROOT));
    }

    /**
     * #578: defense in depth for the receiver, independent of API level and of whatever a given
     * OEM lock screen does with setAuthenticationRequired. If ACTION_TRUST_NETWORK still reaches
     * the receiver while KeyguardManager reports the device as locked, nothing may be trusted and
     * Wireless Debugging must not be turned on.
     */
    @Test
    public void handleTrustNetworkActionRefusesToTrustWhileTheDeviceIsLocked() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));
        lockDevice();

        assertFalse(KeepADBReceiver.handleTrustNetworkAction(context, BSSID, "Cafe-WLAN"));

        assertTrue("A locked device must never allowlist the access point",
                KeepADBTrustedNetwork.getEntries(context).isEmpty());
        assertFalse(KeepADB.isEnabled(context));
        String export = KeepADBDiagnostics.export(context);
        assertTrue("A diagnostics event must record the block",
                export.contains("event=user_action source=network_trust_prompt outcome=blocked "
                        + "detail=device_locked"));
    }

    /**
     * #578: "does the state heal itself once unlocked?" -- there is no unlock listener; instead
     * the receiver re-posts the exact same prompt so the question stays open and answerable. This
     * pins that the notification (with both actions and the original BSSID) is showing again right
     * after the rejected attempt, not merely that nothing was trusted.
     */
    @Test
    public void aRejectedLockedAttemptLeavesThePromptAvailableToAnswerAfterUnlocking() {
        // MODE_ALL_WIFI + the wifi override make isAutoEnableStillPermitted's trust check pass
        // under Robolectric for the post-unlock retry below; see
        // allowingAddsTheAccessPointToTheAllowlistAndEnablesWirelessDebugging for the same setup.
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        // #598: the re-offered allow action only exists with connection details on.
        KeepADBPreferences.setNotificationDetailsEnabled(context, true);
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));
        connectTo("Cafe-WLAN", BSSID);
        assertTrue(KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));
        lockDevice();
        // Model a lock screen that dismissed the notification on the action tap: only the
        // receiver's own re-post can bring the prompt back, so this pins reshow() itself rather
        // than the original notification merely never having been removed.
        KeepADBNetworkTrustPrompt.cancel(context);
        assertNull(postedPrompt());

        assertFalse(KeepADBReceiver.handleTrustNetworkAction(context, BSSID, "Cafe-WLAN"));
        Notification stillLocked = postedPrompt();
        assertNotNull("The prompt must remain/be re-offered while still locked", stillLocked);
        assertEquals(2, stillLocked.actions.length);
        assertEquals(BSSID,
                actionIntent(stillLocked, 0).getStringExtra(KeepADBNetworkTrustPrompt.EXTRA_BSSID));

        unlockDevice();
        assertTrue("Once unlocked, the same tap must succeed",
                KeepADBReceiver.handleTrustNetworkAction(context, BSSID, "Cafe-WLAN"));
        assertEquals(1, KeepADBTrustedNetwork.getEntries(context).size());
    }

    /**
     * Counter-test to the two above: an unlocked device must trust exactly as before -- the gate
     * must not become a "never trusts" regression.
     */
    @Test
    public void handleTrustNetworkActionStillTrustsNormallyWhenUnlocked() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));
        unlockDevice();

        assertTrue(KeepADBReceiver.handleTrustNetworkAction(context, BSSID, "Cafe-WLAN"));

        assertEquals(1, KeepADBTrustedNetwork.getEntries(context).size());
        assertTrue(KeepADB.isEnabled(context));
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

    private void lockDevice() {
        KeyguardManager keyguardManager = context.getSystemService(KeyguardManager.class);
        ShadowKeyguardManager shadow = shadowOf(keyguardManager);
        shadow.setIsDeviceLocked(true);
        shadow.setKeyguardLocked(true);
    }

    private void unlockDevice() {
        KeyguardManager keyguardManager = context.getSystemService(KeyguardManager.class);
        ShadowKeyguardManager shadow = shadowOf(keyguardManager);
        shadow.setIsDeviceLocked(false);
        shadow.setKeyguardLocked(false);
    }

    private android.content.SharedPreferences prefs() {
        return context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE);
    }
}
