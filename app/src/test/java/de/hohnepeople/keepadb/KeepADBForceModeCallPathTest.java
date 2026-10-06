package de.hohnepeople.keepadb;

import static de.hohnepeople.keepadb.KeepADBForceTestSupport.BSSID;
import static de.hohnepeople.keepadb.KeepADBForceTestSupport.HOUR;
import static de.hohnepeople.keepadb.KeepADBForceTestSupport.SSID;
import static de.hohnepeople.keepadb.KeepADBForceTestSupport.connectTo;
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
import android.content.Intent;
import android.net.ConnectivityManager;
import android.os.Looper;
import android.provider.Settings;

import androidx.test.core.app.ApplicationProvider;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowConnectivityManager;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowNetwork;
import org.robolectric.shadows.ShadowNotificationManager;

/**
 * #763: the force mode has to win on the paths that actually act, not only in the model, and it has
 * to lose again at its deadline and when the user ends it. Every test runs one production entry
 * point that can switch Wireless Debugging on automatically -- the service heartbeat, its content
 * observer, its Wi-Fi callback, the recovery pulse, the USB handover guard, the write-time guard --
 * against a real {@code WifiManager} shadow and observes the writes the settings gateway received.
 *
 * <p>The invariant has two sides and both are pinned. Force on: a network that is blocked, unknown
 * or unreadable is switched on in. Force off (never started, expired, ended, or without Keep-Alive or
 * Wi-Fi, which it never replaces): the same setup is not. The block and trust writes and the prompt
 * are the other half: a running force mode must not let trust be added to a blocked network, and the
 * blocks are all still there when it is gone.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBForceModeCallPathTest {

    private static final String OTHER_BSSID = "aa:bb:cc:dd:ee:02";

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = ApplicationProvider.getApplicationContext();
    private KeepADBFakeSettingsGateway gateway;
    private KeepADBForceTestSupport.TestClock clock;

    @Before
    public void setUp() {
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.POST_NOTIFICATIONS,
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
        resetState();
        clock = new KeepADBForceTestSupport.TestClock();
        KeepADBForceMode.setClockForTesting(clock);
        // Every other gate of an automatic enable is open: Keep-Alive on, intent on, Wi-Fi up,
        // Wireless Debugging currently off. Only the trust model and the force mode decide.
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBPreferences.setLastDesiredOn(context, true);
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        KeepADBPreferences.setNotificationDetailsEnabled(context, true);
        gateway = new KeepADBFakeSettingsGateway(false);
        KeepADB.setGatewayForTesting(gateway);
    }

    @After
    public void tearDown() {
        resetState();
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(null);
    }

    private void resetState() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("keepadb_diagnostics", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADBForceMode.resetForTesting();
        KeepADBEndpointCoordinator.resetForTesting();
        KeepADBEndpoint.resetForTesting();
        KeepADB.resetForTesting();
        KeepADBRegisterClient.resetForTesting();
        context.getSystemService(NotificationManager.class).cancelAll();
    }

    private void startForce(KeepADBForceMode.Span span) {
        assertTrue(KeepADBForceMode.activate(context, span, span.isUnlimited()));
        // activate() switches Keep-Alive on only if it was off; the setup already did.
        assertTrue(KeepADBForceMode.isActive(context));
    }

    // --- Service heartbeat (recheckAndEnable) ----------------------------------------------------------

    @Test
    public void theHeartbeatEnablesOnATrustedButBlockedAccessPointOnlyWhileForceIsOn() {
        KeepADBTrustedNetwork.addBssid(context, BSSID, SSID);
        KeepADBNetworkBlocklist.blockBssid(context, BSSID);
        connectTo(context, SSID, BSSID);

        heartbeat();
        assertTrue("Control, no force: a block holds: " + gateway.writes, gateway.writes.isEmpty());

        startForce(KeepADBForceMode.Span.HOUR_1);
        heartbeat();
        assertEquals("Force overrides the block", Collections.singletonList(true), gateway.writes);
        assertNull("... without asking about the network", postedPrompt());
        assertTrue("The block is untouched", KeepADBNetworkBlocklist.isBssidBlocked(context, BSSID));
    }

    @Test
    public void theHeartbeatEnablesOnABlockedNameAndOnABlockedNetworkUnderTheLegacyPolicy() {
        KeepADBTrustedNetwork.addBssid(context, BSSID, SSID);
        KeepADBNetworkBlocklist.blockSsid(context, SSID);
        connectTo(context, SSID, BSSID);
        startForce(KeepADBForceMode.Span.HOURS_24);

        heartbeat();
        assertEquals(Collections.singletonList(true), gateway.writes);

        gateway = new KeepADBFakeSettingsGateway(false);
        KeepADB.setGatewayForTesting(gateway);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        heartbeat();
        assertEquals(Collections.singletonList(true), gateway.writes);
    }

    @Test
    public void theHeartbeatEnablesOnAnUnknownNetworkWithoutAskingOnlyWhileForceIsOn() {
        connectTo(context, SSID, BSSID);
        heartbeat();
        assertTrue("Control: an unknown network does not enable", gateway.writes.isEmpty());
        assertNotNull("Control: ... and is asked about", postedPrompt());
        context.getSystemService(NotificationManager.class).cancelAll();

        startForce(KeepADBForceMode.Span.DAYS_7);
        heartbeat();

        assertEquals(Collections.singletonList(true), gateway.writes);
        assertNull("Under force nobody has to be asked", postedPrompt());
    }

    @Test
    public void theHeartbeatEnablesWhenTheNetworkIdentityCannotBeReadOnlyWhileForceIsOn() {
        connectTo(context, SSID, KeepADBNetworkIdentity.REDACTED_BSSID);
        heartbeat();
        assertTrue("Control: an unreadable network pauses in the allowlist policy", gateway.writes.isEmpty());

        startForce(KeepADBForceMode.Span.HOUR_1);
        heartbeat();

        assertEquals("Force: whether the network is readable does not matter",
                Collections.singletonList(true), gateway.writes);
    }

    @Test
    public void theHeartbeatStopsEnablingExactlyAtTheDeadline() {
        KeepADBNetworkBlocklist.blockBssid(context, BSSID);
        connectTo(context, SSID, BSSID);
        startForce(KeepADBForceMode.Span.HOUR_1);
        clock.advance(HOUR - 1);
        heartbeat();
        assertEquals("One millisecond before the deadline it still enables",
                Collections.singletonList(true), gateway.writes);

        gateway = new KeepADBFakeSettingsGateway(false);
        KeepADB.setGatewayForTesting(gateway);
        clock.advance(1);
        heartbeat();

        assertTrue("At the deadline the block holds again: " + gateway.writes, gateway.writes.isEmpty());
        assertNull("... and a blocked network is still never asked about", postedPrompt());
    }

    @Test
    public void endingTheModeByHandBringsTheBlockBackAtOnce() {
        KeepADBTrustedNetwork.addBssid(context, BSSID, SSID);
        KeepADBNetworkBlocklist.blockBssid(context, BSSID);
        connectTo(context, SSID, BSSID);
        startForce(KeepADBForceMode.Span.DAYS_30);
        assertTrue(KeepADBForceMode.endNow(context));

        heartbeat();

        assertTrue(gateway.writes.isEmpty());
        assertEquals(KeepADBTrustedNetwork.BlockReason.UNTRUSTED_NETWORK,
                KeepADBTrustedNetwork.getBlockReason(context));
    }

    @Test
    public void forceNeverReplacesTheKeepAliveSettingOrTheWifiConnection() {
        KeepADBNetworkBlocklist.blockBssid(context, BSSID);
        connectTo(context, SSID, BSSID);
        startForce(KeepADBForceMode.Span.HOUR_1);

        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> false);
        heartbeat();
        assertTrue("No Wi-Fi: nothing to re-enable on, whatever the mode says: " + gateway.writes,
                gateway.writes.isEmpty());
        assertFalse(KeepADBService.isAutoEnableStillPermittedIgnoringBackoff(context));

        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        KeepADBPreferences.setKeepAliveEnabled(context, false);
        assertFalse("Keep-Alive off: the mode is not a second switch for automatic enabling",
                KeepADBService.isAutoEnableStillPermittedIgnoringBackoff(context));
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        assertTrue(KeepADBService.isAutoEnableStillPermittedIgnoringBackoff(context));
    }

    // --- Content observer ----------------------------------------------------------------------------------------

    @Test
    public void theContentObserverEnablesOnABlockedNetworkOnlyWhileForceIsOn() {
        KeepADBTrustedNetwork.addBssid(context, BSSID, SSID);
        KeepADBNetworkBlocklist.blockBssid(context, BSSID);
        connectTo(context, SSID, BSSID);

        observerFires();
        assertTrue("Control: " + gateway.writes, gateway.writes.isEmpty());

        startForce(KeepADBForceMode.Span.HOUR_1);
        observerFires();

        assertTrue("Force: the observer re-enables: " + gateway.writes, gateway.writes.contains(true));
    }

    // --- Wi-Fi callback while Wireless Debugging is on -----------------------------------------------------------

    @Test
    public void roamingOntoAnUntrustedNetworkWhileActiveRaisesNoPromptUnderForce() {
        gateway = new KeepADBFakeSettingsGateway(true);
        KeepADB.setGatewayForTesting(gateway);
        KeepADBTrustedNetwork.addBssid(context, OTHER_BSSID, "Home");
        connectTo(context, SSID, BSSID);

        wifiAvailable();
        assertNotNull("Control: without force the roam is asked about", postedPrompt());
        context.getSystemService(NotificationManager.class).cancelAll();
        KeepADBNetworkTrustPrompt.clearPromptState(context);
        KeepADBBlockedNetworkHistory.clear(context);

        startForce(KeepADBForceMode.Span.HOUR_1);
        wifiAvailable();

        assertNull("Under force the network counts as accepted: no question", postedPrompt());
    }

    // --- Explicit guards re-evaluated at write time -------------------------------------------------------------

    @Test
    public void theWriteTimeGuardsFollowTheModeIncludingItsExpiry() {
        KeepADBTrustedNetwork.addBssid(context, BSSID, SSID);
        KeepADBNetworkBlocklist.blockBssid(context, BSSID);
        KeepADBPreferences.setUsbWlanHandoverMode(context,
                KeepADBPreferences.USB_WLAN_HANDOVER_MODE_AUTOMATIC);
        connectTo(context, SSID, BSSID);
        assertFalse(KeepADBService.isAutoEnableStillPermitted(context));
        assertFalse(KeepADBService.isAutoEnableStillPermittedIgnoringBackoff(context));
        assertFalse(KeepADBUsbHandover.isAutoHandoverStillPermitted(context));

        startForce(KeepADBForceMode.Span.HOUR_1);
        assertTrue(KeepADBService.isAutoEnableStillPermitted(context));
        assertTrue(KeepADBService.isAutoEnableStillPermittedIgnoringBackoff(context));
        assertTrue("The USB handover is an automatic re-enable and follows the same overlay",
                KeepADBUsbHandover.isAutoHandoverStillPermitted(context));

        clock.advance(HOUR);
        assertFalse("The guard is exact at the deadline, no transition needed",
                KeepADBService.isAutoEnableStillPermitted(context));
        assertFalse(KeepADBUsbHandover.isAutoHandoverStillPermitted(context));
    }

    // --- Recovery pulse --------------------------------------------------------------------------------------------------

    @Test
    public void theRecoveryPulseRunsOnABlockedNetworkOnlyWhileForceIsOn() {
        KeepADBTrustedNetwork.addBssid(context, BSSID, SSID);
        KeepADBNetworkBlocklist.blockBssid(context, BSSID);
        connectTo(context, SSID, BSSID);
        assertTrue("Control: " + runRecoveryPulse(null), runRecoveryPulse(null).isEmpty());

        startForce(KeepADBForceMode.Span.HOUR_1);

        assertEquals(Arrays.asList(false, true), runRecoveryPulse(null));
    }

    @Test
    public void aForceModeThatExpiresWhileThePulsePausesCancelsItsEnableStage() {
        KeepADBNetworkBlocklist.blockBssid(context, BSSID);
        connectTo(context, SSID, BSSID);
        startForce(KeepADBForceMode.Span.HOUR_1);

        List<Boolean> writes = runRecoveryPulse(() -> clock.advance(HOUR));

        assertEquals("The re-check at the enable stage must see that the mode is over: " + writes,
                Collections.singletonList(false), writes);
    }

    // --- The other half: nothing the force mode does may touch blocks or trust ---------------------------------

    @Test
    public void whileForceIsOnTrustStillCannotBeAddedToABlockedNetworkAndAPromptStaysQuiet() {
        KeepADBNetworkBlocklist.blockBssid(context, BSSID);
        connectTo(context, SSID, BSSID);
        startForce(KeepADBForceMode.Span.HOUR_1);

        assertNull("The trust write path still refuses a blocked access point",
                KeepADBReceiver.allowBssidOnly(context, BSSID, SSID));
        assertFalse(KeepADBReceiver.handleTrustNetworkAction(context, BSSID, SSID));
        assertTrue(KeepADBTrustedNetwork.getEntries(context).isEmpty());
        assertFalse("The prompt still treats a blocked network as the user's answer",
                KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));
        assertNull(postedPrompt());
        assertTrue("evaluate() has no force overlay, only the gate does",
                KeepADBTrustedNetwork.evaluate(context, KeepADBNetworkIdentity.current(context)).isBlocked());
        assertEquals(KeepADBTrustedNetwork.Decision.FORCE_MODE, KeepADBTrustedNetwork.evaluateCurrent(context));
        assertEquals(KeepADBTrustedNetwork.BlockReason.NONE, KeepADBTrustedNetwork.getBlockReason(context));
    }

    @Test
    public void everyBlockIsStillThereAfterTheModeEndsByTimeAndByHand() {
        KeepADBNetworkBlocklist.blockBssid(context, BSSID);
        KeepADBNetworkBlocklist.blockSsid(context, "Blocked-Name");
        KeepADBTrustedNetwork.addBssid(context, OTHER_BSSID, "Home");

        startForce(KeepADBForceMode.Span.HOUR_1);
        clock.advance(2 * HOUR);
        KeepADBForceMode.finishIfExpired(context);
        startForce(KeepADBForceMode.Span.HOUR_1);
        KeepADBForceMode.endNow(context);

        assertTrue(KeepADBNetworkBlocklist.isBssidBlocked(context, BSSID));
        assertTrue(KeepADBNetworkBlocklist.isSsidBlocked(context, "Blocked-Name"));
        assertEquals(1, KeepADBTrustedNetwork.getEntries(context).size());
        connectTo(context, SSID, BSSID);
        assertEquals(KeepADBTrustedNetwork.Decision.BLOCKED_ACCESS_POINT,
                KeepADBTrustedNetwork.evaluateCurrent(context));
    }

    // --- Helpers --------------------------------------------------------------------------------------------------------------

    /** Starts the service and lets the 60 s heartbeat's re-check run once, like the ticker does. */
    private void heartbeat() {
        ServiceController<KeepADBService> controller = Robolectric.buildService(KeepADBService.class);
        try {
            controller.create();
            controller.get().onStartCommand(new Intent(context, KeepADBService.class), 0, 1);
            ShadowLooper.idleMainLooper();
            android.os.SystemClock.sleep(1600);
            controller.get().recheckAndEnable();
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1600));
        } finally {
            controller.destroy();
        }
    }

    /** Fires the service's Settings.Global observer for the (off) Wireless Debugging value. */
    private void observerFires() {
        ServiceController<KeepADBService> controller = Robolectric.buildService(KeepADBService.class);
        try {
            controller.create();
            controller.get().onStartCommand(new Intent(context, KeepADBService.class), 0, 1);
            ShadowLooper.idleMainLooper();
            android.os.SystemClock.sleep(1600);
            controller.get().getAdbContentObserverForTesting()
                    .onChange(false, Settings.Global.getUriFor(KeepADB.KEY));
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1600));
        } finally {
            controller.destroy();
        }
    }

    /** A Wi-Fi network becoming available while the service runs, as the system would report it. */
    private void wifiAvailable() {
        ServiceController<KeepADBService> controller = Robolectric.buildService(KeepADBService.class);
        try {
            controller.create();
            controller.get().onStartCommand(new Intent(context, KeepADBService.class), 0, 1);
            ShadowLooper.idleMainLooper();
            ShadowConnectivityManager shadowConnectivityManager =
                    shadowOf(context.getSystemService(ConnectivityManager.class));
            assertFalse("Precondition: the service must be observing Wi-Fi changes",
                    shadowConnectivityManager.getNetworkCallbacks().isEmpty());
            android.os.SystemClock.sleep(1600);
            for (ConnectivityManager.NetworkCallback callback
                    : shadowConnectivityManager.getNetworkCallbacks()) {
                callback.onAvailable(ShadowNetwork.newInstance(3001));
            }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1600));
        } finally {
            controller.destroy();
        }
    }

    /**
     * Runs the endpoint's recovery pulse against a gateway that currently reads "on" and returns the
     * writes it made. {@code duringPause} runs once while the pulse waits between its two stages.
     */
    private List<Boolean> runRecoveryPulse(Runnable duringPause) {
        KeepADBFakeSettingsGateway pulseGateway = new KeepADBFakeSettingsGateway(true);
        KeepADBFakeScheduler scheduler = new KeepADBFakeScheduler() {
            private boolean ran;

            @Override
            public void sleep(long delayMs) {
                super.sleep(delayMs);
                if (duringPause != null && !ran) {
                    ran = true;
                    duringPause.run();
                }
            }

            @Override
            public long elapsedRealtimeMs() {
                return 10 * KeepADB.TOGGLE_COOLDOWN_MS;
            }
        };
        KeepADB.setGatewayForTesting(pulseGateway);
        KeepADB.setSchedulerForTesting(scheduler);
        KeepADBEndpoint.resetForTesting();
        new KeepADBEndpoint(context, new KeepADBFakeNsdProbe(), scheduler).maybeSendRecoveryPulse(0L);
        ShadowLooper.idleMainLooper();
        return pulseGateway.writes;
    }

    private Notification postedPrompt() {
        ShadowNotificationManager shadow = shadowOf(context.getSystemService(NotificationManager.class));
        return shadow.getNotification(KeepADBNetworkTrustPrompt.NOTIFICATION_ID);
    }
}
