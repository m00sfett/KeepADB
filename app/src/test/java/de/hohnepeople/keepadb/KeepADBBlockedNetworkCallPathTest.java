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
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
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
import org.robolectric.shadows.ShadowWifiInfo;

/**
 * #760: a block has to hold on the paths that actually act, not just in the model. Every test here
 * runs one of the production entry points that can switch Wireless Debugging on automatically or
 * ask the user about a network -- the service heartbeat, its content observer, its Wi-Fi callback,
 * the recovery pulse, the USB handover guard, the prompt, the in-app confirmation and the trust
 * actions -- against a real {@code WifiManager} shadow, and observes the outcome: the writes the
 * settings gateway received and the notification that was (not) posted.
 *
 * <p>Each blocked case has a control with the same setup minus the block, so that "nothing
 * happened" cannot be a vacuous result: the control shows that this setup does enable, or does ask.
 * Without the fix, every blocked case here is red.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBBlockedNetworkCallPathTest {

    private static final String SSID = "Cafe-WLAN";
    private static final String BSSID = "aa:bb:cc:dd:ee:01";
    private static final String OTHER_BSSID = "aa:bb:cc:dd:ee:02";

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = ApplicationProvider.getApplicationContext();
    private KeepADBFakeSettingsGateway gateway;

    @Before
    public void setUp() {
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.POST_NOTIFICATIONS,
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
        resetState();
        // Every other gate of an automatic enable is open: Keep-Alive on, intent on, Wi-Fi up,
        // Wireless Debugging currently off. Only the trust model decides.
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
        KeepADBEndpointCoordinator.resetForTesting();
        KeepADBEndpoint.resetForTesting();
        KeepADB.resetForTesting();
        KeepADBUsbHandover.resetForTesting();
        KeepADBRegisterClient.resetForTesting();
        context.getSystemService(NotificationManager.class).cancelAll();
    }

    // --- Service heartbeat (recheckAndEnable) -----------------------------------------------------

    @Test
    public void theHeartbeatNeitherEnablesNorAsksOnATrustedButBlockedAccessPoint() {
        KeepADBTrustedNetwork.addBssid(context, BSSID, SSID);
        KeepADBNetworkBlocklist.blockBssid(context, BSSID);
        connectTo(SSID, BSSID);

        heartbeat();

        assertNothingEnabledAndNobodyAsked("blocked access point");
        assertTrue("The decision, not another gate, stopped it: " + diagnostics(),
                diagnostics().contains("untrusted_network"));
    }

    @Test
    public void theHeartbeatNeitherEnablesNorAsksOnATrustedAccessPointWhoseNameIsBlocked() {
        KeepADBTrustedNetwork.addBssid(context, BSSID, SSID);
        KeepADBNetworkBlocklist.blockSsid(context, SSID);
        connectTo(SSID, BSSID);

        heartbeat();

        assertNothingEnabledAndNobodyAsked("blocked name");
        assertTrue(diagnostics().contains("untrusted_network"));
    }

    @Test
    public void theHeartbeatNeitherEnablesNorAsksOnABlockedNetworkUnderTheLegacyOpenPolicy() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        KeepADBNetworkBlocklist.blockSsid(context, SSID);
        connectTo(SSID, BSSID);

        heartbeat();

        assertNothingEnabledAndNobodyAsked("blocked name under the legacy open policy");
        assertTrue(diagnostics().contains("untrusted_network"));
    }

    @Test
    public void controlTheSameHeartbeatDoesEnableOnTheTrustedAccessPointWithoutABlock() {
        KeepADBTrustedNetwork.addBssid(context, BSSID, SSID);
        connectTo(SSID, BSSID);

        heartbeat();

        assertEquals("The setup must be one in which Keep-Alive really enables",
                Collections.singletonList(true), gateway.writes);
        assertNull(postedPrompt());
    }

    @Test
    public void controlTheLegacyOpenPolicyStillEnablesOnAnUnrelatedNetworkWhileOneIsBlocked() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        KeepADBNetworkBlocklist.blockSsid(context, "SomewhereElse");
        KeepADBNetworkBlocklist.blockBssid(context, OTHER_BSSID);
        connectTo(SSID, BSSID);

        heartbeat();

        assertEquals("A block must not switch Keep-Alive off for networks it does not name",
                Collections.singletonList(true), gateway.writes);
    }

    @Test
    public void controlAnUnknownNetworkIsAskedAboutAndNeverEnabled() {
        connectTo(SSID, BSSID);

        heartbeat();

        assertTrue("An unknown network does not enable", gateway.writes.isEmpty());
        assertNotNull("... and is asked about, which is what a block must suppress", postedPrompt());
        assertEquals(1, KeepADBBlockedNetworkHistory.getEntries(context).size());
    }

    // --- Content observer ---------------------------------------------------------------------------

    @Test
    public void theContentObserverNeitherEnablesNorAsksOnABlockedNetwork() {
        KeepADBTrustedNetwork.addBssid(context, BSSID, SSID);
        KeepADBNetworkBlocklist.blockBssid(context, BSSID);
        connectTo(SSID, BSSID);

        observerFires();

        assertNothingEnabledAndNobodyAsked("content observer");
        assertTrue(diagnostics().contains("untrusted_network"));
    }

    @Test
    public void controlTheContentObserverEnablesOnTheTrustedAccessPointWithoutABlock() {
        KeepADBTrustedNetwork.addBssid(context, BSSID, SSID);
        connectTo(SSID, BSSID);

        observerFires();

        assertTrue("The setup must be one in which the observer enables: " + gateway.writes,
                gateway.writes.contains(true));
    }

    @Test
    public void controlTheContentObserverAsksOnAnUnknownNetwork() {
        connectTo(SSID, BSSID);

        observerFires();

        assertTrue(gateway.writes.isEmpty());
        assertNotNull(postedPrompt());
    }

    // --- Roaming while Wireless Debugging is already on ----------------------------------------------

    @Test
    public void roamingOntoABlockedNetworkWhileActiveRaisesNoPrompt() {
        gateway = new KeepADBFakeSettingsGateway(true);
        KeepADB.setGatewayForTesting(gateway);
        KeepADBTrustedNetwork.addBssid(context, OTHER_BSSID, "Home");
        KeepADBNetworkBlocklist.blockBssid(context, BSSID);
        connectTo(SSID, BSSID);

        wifiAvailable();

        assertNull("A blocked network is never asked about, not even while active", postedPrompt());
        assertTrue(KeepADBBlockedNetworkHistory.getEntries(context).isEmpty());
    }

    @Test
    public void controlRoamingOntoAnUnknownNetworkWhileActiveStillRaisesThePrompt() {
        gateway = new KeepADBFakeSettingsGateway(true);
        KeepADB.setGatewayForTesting(gateway);
        KeepADBTrustedNetwork.addBssid(context, OTHER_BSSID, "Home");
        connectTo(SSID, BSSID);

        wifiAvailable();

        assertNotNull(postedPrompt());
    }

    // --- Explicit guards re-evaluated at write time ----------------------------------------------------

    @Test
    public void usbHandoverIngressBlocksAnUntrustedNetworkBeforeSchedulingAnEnable() {
        KeepADBPreferences.setUsbWlanHandoverMode(context,
                KeepADBPreferences.USB_WLAN_HANDOVER_MODE_AUTOMATIC);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBTrustedNetwork.addBssid(context, BSSID, SSID);
        KeepADBNetworkBlocklist.blockBssid(context, BSSID);
        connectTo(SSID, BSSID);

        KeepADBUsbHandover.onRawUsbBroadcast(context, true);

        assertTrue("a blocked Wi-Fi network must stop automatic USB handover before write",
                gateway.writes.isEmpty());
        assertTrue("the ingress gate must report the untrusted-network decision: " + diagnostics(),
                diagnostics().contains(
                        "event=usb_handover source=usb outcome=blocked detail=untrusted_network"));
    }

    @Test
    public void usbHandoverIngressStillEnablesOnTheSameTrustedNetwork() {
        KeepADBPreferences.setUsbWlanHandoverMode(context,
                KeepADBPreferences.USB_WLAN_HANDOVER_MODE_AUTOMATIC);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBTrustedNetwork.addBssid(context, BSSID, SSID);
        connectTo(SSID, BSSID);
        KeepADBFakeScheduler scheduler = new KeepADBFakeScheduler();
        scheduler.setClockMs(100_000);
        KeepADB.setSchedulerForTesting(scheduler);

        KeepADBUsbHandover.onRawUsbBroadcast(context, true);

        assertTrue("the trusted control network must allow automatic USB handover: " + gateway.writes,
                gateway.writes.contains(true));
    }

    @Test
    public void theDebouncedWriteGuardsDenyABlockedNetworkAndAllowTheSameNetworkOnceLifted() {
        KeepADBTrustedNetwork.addBssid(context, BSSID, SSID);
        KeepADBPreferences.setUsbWlanHandoverMode(context,
                KeepADBPreferences.USB_WLAN_HANDOVER_MODE_AUTOMATIC);
        connectTo(SSID, BSSID);
        assertTrue(KeepADBService.isAutoEnableStillPermitted(context));
        assertTrue(KeepADBService.isAutoEnableStillPermittedIgnoringBackoff(context));
        assertTrue(KeepADBUsbHandover.isAutoHandoverStillPermitted(context));

        KeepADBNetworkBlocklist.blockBssid(context, BSSID);

        assertFalse(KeepADBService.isAutoEnableStillPermitted(context));
        assertFalse("The explicit-confirmation variant must honour a block as well",
                KeepADBService.isAutoEnableStillPermittedIgnoringBackoff(context));
        assertFalse(KeepADBUsbHandover.isAutoHandoverStillPermitted(context));

        KeepADBNetworkBlocklist.unblockBssid(context, BSSID);
        assertTrue(KeepADBService.isAutoEnableStillPermitted(context));
        assertTrue(KeepADBUsbHandover.isAutoHandoverStillPermitted(context));
    }

    // --- Recovery pulse -----------------------------------------------------------------------------------

    @Test
    public void theRecoveryPulseIsNotSentOnABlockedNetwork() {
        KeepADBTrustedNetwork.addBssid(context, BSSID, SSID);
        KeepADBNetworkBlocklist.blockBssid(context, BSSID);
        connectTo(SSID, BSSID);

        List<Boolean> writes = runRecoveryPulse(null);

        assertTrue("A blocked network must not get a recovery pulse: " + writes, writes.isEmpty());
    }

    @Test
    public void controlTheRecoveryPulseIsSentOnTheTrustedAccessPointWithoutABlock() {
        KeepADBTrustedNetwork.addBssid(context, BSSID, SSID);
        connectTo(SSID, BSSID);

        assertEquals("The setup must be one in which the pulse runs both stages",
                Arrays.asList(false, true), runRecoveryPulse(null));
    }

    @Test
    public void aBlockAddedWhileThePulsePausesCancelsItsEnableStage() {
        KeepADBTrustedNetwork.addBssid(context, BSSID, SSID);
        connectTo(SSID, BSSID);

        List<Boolean> writes = runRecoveryPulse(() -> KeepADBNetworkBlocklist.blockBssid(context, BSSID));

        assertEquals("The re-check at the enable stage must honour the block: " + writes,
                Collections.singletonList(false), writes);
    }

    // --- Prompt --------------------------------------------------------------------------------------------------

    @Test
    public void thePromptStaysQuietAndRecordsNothingForABlockedNetwork() {
        KeepADBNetworkBlocklist.blockBssid(context, BSSID);
        connectTo(SSID, BSSID);

        assertFalse(KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));

        assertNull(postedPrompt());
        assertTrue("A blocked network is not 'recently prevented' either",
                KeepADBBlockedNetworkHistory.getEntries(context).isEmpty());
        assertTrue(diagnostics().contains("network_blocked"));
    }

    @Test
    public void thePromptStaysQuietForABlockedNameEvenWhenOnlyTheNameCouldBeRead() {
        KeepADBNetworkBlocklist.blockSsid(context, SSID);
        // The masked address would normally raise the "identity unavailable" notification.
        connectTo(SSID, KeepADBNetworkIdentity.REDACTED_BSSID);

        assertFalse(KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));

        assertNull("Blocked, not merely unreadable: no notification at all", postedPrompt());
    }

    @Test
    public void controlThePromptIsRaisedForAnUnknownNetworkAndTheUnreadableOne() {
        connectTo(SSID, BSSID);
        assertTrue(KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));
        assertNotNull(postedPrompt());

        context.getSystemService(NotificationManager.class).cancelAll();
        KeepADBNetworkTrustPrompt.clearPromptState(context);
        connectTo(SSID, KeepADBNetworkIdentity.REDACTED_BSSID);
        assertTrue("An unreadable network keeps its fix-it notification",
                KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));
        assertNotNull(postedPrompt());
    }

    @Test
    public void aBlockOnlyQuietsTheNetworkItNamesNotItsNeighbours() {
        KeepADBNetworkBlocklist.blockBssid(context, BSSID);
        KeepADBNetworkBlocklist.blockSsid(context, "SomewhereElse");

        connectTo(SSID, OTHER_BSSID);
        assertTrue("A neighbouring access point is still asked about",
                KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));
        assertNotNull(postedPrompt());
    }

    @Test
    public void aPromptRepostedAfterALockedDeviceTapIsNotShownForANetworkBlockedInTheMeantime() {
        connectTo(SSID, BSSID);
        assertTrue(KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));
        assertNotNull(postedPrompt());
        context.getSystemService(NotificationManager.class).cancelAll();

        // The user blocks the network, then taps the stale "allow" while the device is locked.
        KeepADBNetworkBlocklist.blockBssid(context, BSSID);
        lockDevice();
        assertFalse(KeepADBReceiver.handleTrustNetworkAction(context, BSSID, SSID));

        assertNull("The question is closed for a blocked network: no re-post", postedPrompt());
        assertTrue(KeepADBTrustedNetwork.getEntries(context).isEmpty());
    }

    @Test
    public void aPromptRepostedForANetworkWhoseNameIsBlockedIsNotShownEither() {
        // The re-post only knows the BSSID and the label (the SSID); a name block must hold there.
        KeepADBNetworkBlocklist.blockSsid(context, SSID);
        connectTo(SSID, BSSID);
        lockDevice();

        assertFalse(KeepADBReceiver.handleTrustNetworkAction(context, BSSID, SSID));

        assertNull(postedPrompt());
    }

    @Test
    public void controlAPromptRepostedAfterALockedDeviceTapIsShownForAnUnblockedNetwork() {
        connectTo(SSID, BSSID);
        lockDevice();
        assertFalse(KeepADBReceiver.handleTrustNetworkAction(context, BSSID, SSID));
        assertNotNull("The setup must be one in which the question is re-posted", postedPrompt());
    }

    @Test
    public void theDecisionOffersNothingForABlockedNetworkAndStillDoesForOthers() {
        KeepADBBlockedNetworkHistory.record(context, new KeepADBNetworkIdentity(SSID, BSSID), 1L);
        KeepADBBlockedNetworkHistory.record(context, new KeepADBNetworkIdentity("Other", OTHER_BSSID), 2L);
        assertEquals(KeepADBNetworkDecision.Status.PENDING,
                KeepADBNetworkDecision.resolve(context, BSSID).status);

        KeepADBNetworkBlocklist.blockBssid(context, BSSID);
        assertEquals("A stale prompt must not open a trust question for a blocked address",
                KeepADBNetworkDecision.Status.ALREADY_DECIDED,
                KeepADBNetworkDecision.resolve(context, BSSID).status);
        assertEquals("... and the other recorded network is unaffected",
                KeepADBNetworkDecision.Status.PENDING,
                KeepADBNetworkDecision.resolve(context, OTHER_BSSID).status);

        KeepADBNetworkBlocklist.unblockBssid(context, BSSID);
        KeepADBNetworkBlocklist.blockSsid(context, SSID);
        assertEquals("... nor for a blocked name, taken from the recorded entry",
                KeepADBNetworkDecision.Status.ALREADY_DECIDED,
                KeepADBNetworkDecision.resolve(context, BSSID).status);
        assertEquals(KeepADBNetworkDecision.Status.PENDING,
                KeepADBNetworkDecision.resolve(context, OTHER_BSSID).status);
    }

    // --- Trust actions never lift a block -----------------------------------------------------------------------------

    @Test
    public void aStaleTrustTapOnABlockedNetworkStoresNothingAndEnablesNothing() {
        KeepADBNetworkBlocklist.blockBssid(context, BSSID);
        connectTo(SSID, BSSID);

        assertFalse(KeepADBReceiver.handleTrustNetworkAction(context, BSSID, SSID));

        assertTrue("Trust must not be recorded under a block", KeepADBTrustedNetwork.getEntries(context).isEmpty());
        assertTrue(gateway.writes.isEmpty());
        assertTrue("The block must still be there", KeepADBNetworkBlocklist.isBssidBlocked(context, BSSID));
        // The refusal is reported as such, never as an allowed trust decision.
        assertTrue(diagnostics(), diagnostics()
                .contains("source=network_trust_prompt outcome=blocked detail=network_blocked"));
        assertFalse(diagnostics(), diagnostics().contains("source=network_trust_prompt outcome=allowed"));
    }

    @Test
    public void aStaleTrustTapForABlockedNameStoresNothingEither() {
        KeepADBNetworkBlocklist.blockSsid(context, SSID);
        connectTo(SSID, BSSID);

        assertFalse(KeepADBReceiver.handleTrustNetworkAction(context, BSSID, SSID));

        assertTrue(KeepADBTrustedNetwork.getEntries(context).isEmpty());
        assertTrue(gateway.writes.isEmpty());
    }

    @Test
    public void controlTheSameTrustTapOnAnUnblockedNetworkTrustsAndEnables() {
        connectTo(SSID, BSSID);

        assertTrue(KeepADBReceiver.handleTrustNetworkAction(context, BSSID, SSID));

        assertEquals(1, KeepADBTrustedNetwork.getEntries(context).size());
        assertEquals(Collections.singletonList(true), gateway.writes);
    }

    @Test
    public void everyListAndCardGrantRefusesABlockedNetworkAndOnlyThatOne() {
        KeepADBNetworkBlocklist.blockBssid(context, BSSID);
        KeepADBNetworkBlocklist.blockSsid(context, "Gast");

        assertNull(KeepADBReceiver.allowBssidOnly(context, BSSID, SSID));
        assertNull("A mesh offer for a blocked name is refused too",
                KeepADBReceiver.allowBssidOnly(context, "bb:bb:bb:bb:bb:01", "Gast"));
        assertTrue(KeepADBTrustedNetwork.getEntries(context).isEmpty());
        assertTrue(diagnostics(), diagnostics()
                .contains("source=network_allow outcome=blocked detail=network_blocked"));
        assertFalse("A refused grant must not be logged as allowed: " + diagnostics(),
                diagnostics().contains("source=network_allow outcome=allowed"));

        assertNotNull("A different access point is granted as before",
                KeepADBReceiver.allowBssidOnly(context, OTHER_BSSID, SSID));
        assertEquals(1, KeepADBTrustedNetwork.getEntries(context).size());

        // Lifting the block explicitly is what makes the grant possible again.
        KeepADBNetworkBlocklist.unblockBssid(context, BSSID);
        assertNotNull(KeepADBReceiver.allowBssidOnly(context, BSSID, SSID));
        assertEquals(2, KeepADBTrustedNetwork.getEntries(context).size());
    }

    // --- Helpers -------------------------------------------------------------------------------------------------------------

    private void assertNothingEnabledAndNobodyAsked(String what) {
        assertTrue("No enable for " + what + ", writes=" + gateway.writes, gateway.writes.isEmpty());
        assertNull("No prompt for " + what, postedPrompt());
        assertTrue("No 'recently prevented' record for " + what,
                KeepADBBlockedNetworkHistory.getEntries(context).isEmpty());
    }

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
        new KeepADBEndpoint(context, new KeepADBFakeNsdProbe(), scheduler).maybeSendRecoveryPulse(0L);
        ShadowLooper.idleMainLooper();
        return pulseGateway.writes;
    }

    private String diagnostics() {
        return KeepADBDiagnostics.export(context);
    }

    private Notification postedPrompt() {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        ShadowNotificationManager shadow = shadowOf(manager);
        return shadow.getNotification(KeepADBNetworkTrustPrompt.NOTIFICATION_ID);
    }

    private void lockDevice() {
        KeyguardManager keyguardManager = context.getSystemService(KeyguardManager.class);
        shadowOf(keyguardManager).setIsDeviceLocked(true);
        shadowOf(keyguardManager).setKeyguardLocked(true);
    }

    private void connectTo(String ssid, String bssid) {
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = ShadowWifiInfo.newInstance();
        shadowOf(info).setSSID(ssid);
        shadowOf(info).setBSSID(bssid);
        shadowOf(wifiManager).setConnectionInfo(info);
    }
}
