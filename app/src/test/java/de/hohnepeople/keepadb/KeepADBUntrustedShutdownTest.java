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

import java.util.Arrays;
import java.util.Collections;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowWifiInfo;

/**
 * #864: the opt-in shutdown of Wireless Debugging on an untrusted Wi-Fi. Covers both directions
 * of every rule: the cases that must switch off, and the ones that must leave a session alone.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBUntrustedShutdownTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private static final String HOME = "aa:bb:cc:00:00:01";
    private static final String CAFE = "aa:bb:cc:00:00:02";
    private static final String CAFE_OTHER_AP = "aa:bb:cc:00:00:03";

    private final Context context = ApplicationProvider.getApplicationContext();
    private KeepADBFakeSettingsGateway gateway;
    private KeepADBFakeScheduler scheduler;

    @Before
    public void setUp() {
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.POST_NOTIFICATIONS,
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADB.resetForTesting();
        KeepADBForceMode.resetForTesting();
        KeepADBForceMode.setClockForTesting(new KeepADBForceTestSupport.TestClock());
        gateway = new KeepADBFakeSettingsGateway(true);
        KeepADB.setGatewayForTesting(gateway);
        scheduler = new KeepADBFakeScheduler();
        KeepADBUntrustedShutdown.setSchedulerForTesting(scheduler);
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBPreferences.setLastDesiredOn(context, true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBTrustedNetwork.addBssid(context, HOME, "Home");
        KeepADBPreferences.setUntrustedShutdownEnabled(context, true);
    }

    @After
    public void tearDown() {
        KeepADBUntrustedShutdown.resetForTesting();
        KeepADBForceMode.resetForTesting();
        KeepADB.resetForTesting();
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(null);
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test
    public void offByDefault_anUntrustedNetworkOnlyKeepsTheOldBehavior() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit()
                .remove("untrusted_shutdown_enabled").commit();
        connectTo("Cafe", CAFE);

        KeepADBUntrustedShutdown.evaluate(context, "test");

        assertEquals(Collections.emptyList(), gateway.writes);
        assertNull(posted());
    }

    @Test
    public void untrustedReadableNetwork_switchesOffWithoutRecordingAUserOff() {
        connectTo("Cafe", CAFE);

        KeepADBUntrustedShutdown.evaluate(context, "test");

        assertEquals(Collections.singletonList(false), gateway.writes);
        assertFalse(gateway.isEnabled(context));
        assertTrue("Keep-Alive must stay armed: the policy is not the user's off",
                KeepADBPreferences.getLastDesiredOn(context));
        assertFalse(KeepADB.wasLastExplicitIntentOff(context));
        assertFalse(KeepADB.consumeUserDisabled());
        Notification notification = posted();
        assertNotNull(notification);
        assertEquals(context.getString(R.string.untrusted_shutdown_off_title),
                notification.extras.getString(Notification.EXTRA_TITLE));
    }

    @Test
    public void blockedAccessPoint_switchesOff() {
        connectTo("Home", HOME);
        KeepADBNetworkBlocklist.blockBssid(context, HOME);

        KeepADBUntrustedShutdown.evaluate(context, "test");

        assertEquals(Collections.singletonList(false), gateway.writes);
    }

    @Test
    public void trustedNetwork_staysOn() {
        connectTo("Home", HOME);

        KeepADBUntrustedShutdown.evaluate(context, "test");

        assertEquals(Collections.emptyList(), gateway.writes);
    }

    @Test
    public void withoutKeepAliveOrWifiOrWhenAlreadyOff_nothingIsWritten() {
        connectTo("Cafe", CAFE);
        KeepADBPreferences.setKeepAliveEnabled(context, false);
        KeepADBUntrustedShutdown.evaluate(context, "test");
        KeepADBPreferences.setKeepAliveEnabled(context, true);

        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> false);
        KeepADBUntrustedShutdown.evaluate(context, "test");
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);

        KeepADB.setGatewayForTesting(gateway = new KeepADBFakeSettingsGateway(false));
        KeepADBUntrustedShutdown.evaluate(context, "test");

        assertEquals(Collections.emptyList(), gateway.writes);
    }

    @Test
    public void forceModeSuspendsThePolicy() {
        connectTo("Cafe", CAFE);
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));

        KeepADBUntrustedShutdown.evaluate(context, "test");

        assertFalse("force mode must not be undone by the policy", gateway.writes.contains(false));
    }

    @Test
    public void manualEnableStaysOnInThatWifiUntilTheAccessPointChanges() {
        KeepADB.setGatewayForTesting(gateway = new KeepADBFakeSettingsGateway(false));
        connectTo("Cafe", CAFE);
        assertEquals(KeepADB.ToggleResult.APPLIED, KeepADB.setEnabled(context, true, KeepADB.SOURCE_APP));

        KeepADBUntrustedShutdown.evaluate(context, "test");
        assertEquals("the manual session stays on", Collections.singletonList(true), gateway.writes);

        connectTo("Cafe", CAFE_OTHER_AP);
        KeepADBUntrustedShutdown.evaluate(context, "test");
        assertEquals(Arrays.asList(true, false), gateway.writes);
    }

    @Test
    public void endedSessionEndsTheManualExemption() {
        KeepADB.setGatewayForTesting(gateway = new KeepADBFakeSettingsGateway(false));
        connectTo("Cafe", CAFE);
        KeepADB.setEnabled(context, true, KeepADB.SOURCE_APP);
        assertTrue(KeepADBUntrustedShutdown.isManuallyExempt(context));

        KeepADBUntrustedShutdown.onSessionEnded(context);

        assertFalse(KeepADBUntrustedShutdown.isManuallyExempt(context));
        KeepADBUntrustedShutdown.evaluate(context, "test");
        assertEquals(Arrays.asList(true, false), gateway.writes);
    }

    @Test
    public void automaticEnableGrantsNoExemption() {
        KeepADB.setGatewayForTesting(gateway = new KeepADBFakeSettingsGateway(false));
        connectTo("Cafe", CAFE);
        KeepADB.setEnabled(context, true, "keep_alive_check");
        scheduler.advanceBy(10_000L);

        assertFalse(KeepADBUntrustedShutdown.isManuallyExempt(context));
    }

    @Test
    public void unreadableIdentity_isConfirmedBeforeSwitchingOff() {
        connectTo("", null);

        KeepADBUntrustedShutdown.evaluate(context, "test");
        assertEquals("no immediate write on a single unreadable read",
                Collections.emptyList(), gateway.writes);
        scheduler.advanceBy(KeepADBNetworkTrustPrompt.IDENTITY_RECHECK_DELAYS_MS[0]);
        assertEquals(Collections.emptyList(), gateway.writes);
        scheduler.advanceBy(KeepADBNetworkTrustPrompt.IDENTITY_RECHECK_DELAYS_MS[1]);

        assertEquals(Collections.singletonList(false), gateway.writes);
    }

    @Test
    public void unreadableIdentityThatTurnsTrusted_staysOn() {
        connectTo("", null);
        KeepADBUntrustedShutdown.evaluate(context, "test");
        scheduler.advanceBy(KeepADBNetworkTrustPrompt.IDENTITY_RECHECK_DELAYS_MS[0]);

        connectTo("Home", HOME);
        scheduler.advanceBy(KeepADBNetworkTrustPrompt.IDENTITY_RECHECK_DELAYS_MS[1]);

        assertEquals(Collections.emptyList(), gateway.writes);
    }

    @Test
    public void staleTrigger_isAbortedByTheGuardInsideTheLock() {
        connectTo("Cafe", CAFE);

        KeepADB.ToggleResult result = KeepADB.applyPolicyOff(context, "test", app -> false);

        assertEquals(KeepADB.ToggleResult.GUARD_ABORTED, result);
        assertEquals(Collections.emptyList(), gateway.writes);
    }

    @Test
    public void failedWrite_postsTheFailureNotice() {
        connectTo("Cafe", CAFE);
        gateway.setWriteSuccess(false);

        KeepADBUntrustedShutdown.evaluate(context, "test");

        Notification notification = posted();
        assertNotNull(notification);
        assertEquals(context.getString(R.string.untrusted_shutdown_failed_title),
                notification.extras.getString(Notification.EXTRA_TITLE));
    }

    /** Codex review: a trusted access point in between must not revive the exemption later. */
    @Test
    public void exemptionEndsOnAnyOtherAccessPointAndDoesNotReviveOnReturn() {
        KeepADB.setGatewayForTesting(gateway = new KeepADBFakeSettingsGateway(false));
        connectTo("Cafe", CAFE);
        KeepADB.setEnabled(context, true, KeepADB.SOURCE_APP);

        connectTo("Home", HOME);
        KeepADBUntrustedShutdown.evaluate(context, "test");
        assertEquals(Collections.singletonList(true), gateway.writes);

        connectTo("Cafe", CAFE);
        KeepADBUntrustedShutdown.evaluate(context, "test");
        assertEquals(Arrays.asList(true, false), gateway.writes);
    }

    /** Codex review: an unreadable identity cannot prove the same access point. */
    @Test
    public void manualEnableOnAnUnreadableIdentityGrantsNoExemption() {
        KeepADB.setGatewayForTesting(gateway = new KeepADBFakeSettingsGateway(false));
        connectTo("", null);
        KeepADB.setEnabled(context, true, KeepADB.SOURCE_APP);
        assertFalse(KeepADBUntrustedShutdown.isManuallyExempt(context));

        KeepADBUntrustedShutdown.evaluate(context, "test");
        for (long delay : KeepADBNetworkTrustPrompt.IDENTITY_RECHECK_DELAYS_MS) scheduler.advanceBy(delay);

        assertEquals(Arrays.asList(true, false), gateway.writes);
    }

    /** A brief unreadable phase (a roam back to the same access point) keeps the exemption. */
    @Test
    public void briefUnreadablePhaseOnTheSameAccessPointKeepsTheExemption() {
        KeepADB.setGatewayForTesting(gateway = new KeepADBFakeSettingsGateway(false));
        connectTo("Cafe", CAFE);
        KeepADB.setEnabled(context, true, KeepADB.SOURCE_APP);

        connectTo("", null);
        assertFalse("no exemption while the identity is unreadable",
                KeepADBUntrustedShutdown.isManuallyExempt(context));
        KeepADBUntrustedShutdown.evaluate(context, "test");
        connectTo("Cafe", CAFE);
        for (long delay : KeepADBNetworkTrustPrompt.IDENTITY_RECHECK_DELAYS_MS) scheduler.advanceBy(delay);

        assertEquals(Collections.singletonList(true), gateway.writes);
    }

    /** Codex review: an accepted write that leaves debugging on is a failure, not "turned off". */
    @Test
    public void acceptedButIneffectiveWrite_reportsTheFailure() {
        KeepADB.setGatewayForTesting(new KeepADBSettingsGateway() {
            @Override
            public boolean isEnabled(Context context) {
                return true;
            }

            @Override
            public boolean write(Context appContext, boolean on) {
                return true;
            }
        });
        connectTo("Cafe", CAFE);

        KeepADBUntrustedShutdown.evaluate(context, "test");

        assertEquals(context.getString(R.string.untrusted_shutdown_failed_title),
                posted().extras.getString(Notification.EXTRA_TITLE));
    }

    /** Codex review round 2: an unreadable readback after the write is no confirmed "off". */
    @Test
    public void unreadableReadbackAfterTheWrite_reportsTheFailure() {
        boolean[] written = {false};
        KeepADB.setGatewayForTesting(new KeepADBSettingsGateway() {
            @Override
            public boolean isEnabled(Context context) {
                if (written[0]) throw new SecurityException("read restricted");
                return true;
            }

            @Override
            public boolean write(Context appContext, boolean on) {
                written[0] = true;
                return true;
            }
        });
        connectTo("Cafe", CAFE);

        KeepADBUntrustedShutdown.evaluate(context, "test");

        assertTrue(written[0]);
        assertEquals(context.getString(R.string.untrusted_shutdown_failed_title),
                posted().extras.getString(Notification.EXTRA_TITLE));
    }

    /**
     * Codex review round 2: an identity that turns unreadable between the readable decision and
     * the guarded write gets the confirmation period, not an immediate shutdown.
     */
    @Test
    public void identityTurningUnreadableBeforeTheWrite_isConfirmedFirst() {
        int[] reads = {0};
        KeepADBFakeSettingsGateway inner = new KeepADBFakeSettingsGateway(true);
        KeepADB.setGatewayForTesting(new KeepADBSettingsGateway() {
            @Override
            public boolean isEnabled(Context context) {
                // The second read is the guard's: the access point drops out right before it.
                if (++reads[0] == 2) connectTo("", null);
                return inner.isEnabled(context);
            }

            @Override
            public boolean write(Context appContext, boolean on) {
                return inner.write(appContext, on);
            }
        });
        connectTo("Cafe", CAFE);

        KeepADBUntrustedShutdown.evaluate(context, "test");
        assertEquals("no immediate write", Collections.emptyList(), inner.writes);

        for (long delay : KeepADBNetworkTrustPrompt.IDENTITY_RECHECK_DELAYS_MS) scheduler.advanceBy(delay);
        assertEquals(Collections.singletonList(false), inner.writes);
    }

    /** Codex review round 3: a service restart ends an exemption recorded before the gap. */
    @Test
    public void serviceRestartEndsAnOlderExemption() {
        KeepADB.setGatewayForTesting(gateway = new KeepADBFakeSettingsGateway(false));
        connectTo("Cafe", CAFE);
        KeepADB.setEnabled(context, true, KeepADB.SOURCE_APP);
        scheduler.advanceBy(KeepADBUntrustedShutdown.OBSERVATION_RESUME_GRACE_MS + 1);

        KeepADBUntrustedShutdown.onObservationResumed(context);

        assertFalse(KeepADBUntrustedShutdown.isManuallyExempt(context));
        KeepADBUntrustedShutdown.evaluate(context, "test");
        assertEquals(Arrays.asList(true, false), gateway.writes);
    }

    /** The tap that starts the service keeps its own, fresh exemption. */
    @Test
    public void serviceStartRightAfterTheManualTapKeepsTheExemption() {
        KeepADB.setGatewayForTesting(gateway = new KeepADBFakeSettingsGateway(false));
        connectTo("Cafe", CAFE);
        KeepADB.setEnabled(context, true, KeepADB.SOURCE_APP);
        scheduler.advanceBy(1_000L);

        KeepADBUntrustedShutdown.onObservationResumed(context);

        assertTrue(KeepADBUntrustedShutdown.isManuallyExempt(context));
    }

    private void connectTo(String ssid, String bssid) {
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = ShadowWifiInfo.newInstance();
        shadowOf(info).setSSID(ssid);
        shadowOf(info).setBSSID(bssid);
        shadowOf(wifiManager).setConnectionInfo(info);
    }

    private Notification posted() {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        return shadowOf(manager).getNotification(KeepADBUntrustedShutdown.NOTIFICATION_ID);
    }
}
