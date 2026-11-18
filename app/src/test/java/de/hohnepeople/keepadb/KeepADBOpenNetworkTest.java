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
import android.content.SharedPreferences;

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

/**
 * #997: Wifi-ADB is turned off, and not recovered, in an open Wi-Fi or behind a captive portal,
 * trusted access points included. Covers the pure rule, its place in the trust evaluation (force
 * mode above it, every list below it), the shutdown with and without the untrusted-Wi-Fi switch,
 * the manual exemption and the default of new and existing installations.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBOpenNetworkTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private static final String HOME = "aa:bb:cc:00:00:01";
    private static final String KEY = "open_network_shutdown_enabled";

    private final Context context = ApplicationProvider.getApplicationContext();
    private KeepADBFakeSettingsGateway gateway;
    private KeepADBFakeScheduler scheduler;
    private final FakeProbe probe = new FakeProbe();

    private static final class FakeProbe implements KeepADBOpenNetwork.Probe {
        Integer tier = KeepADBNetworkSecurity.TIER_PERSONAL;
        boolean captive;

        @Override
        public Integer securityTier(Context context) {
            return tier;
        }

        @Override
        public boolean captivePortal(Context context) {
            return captive;
        }
    }

    @Before
    public void setUp() {
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.POST_NOTIFICATIONS,
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
        prefs().edit().clear().commit();
        KeepADB.resetForTesting();
        KeepADBForceMode.resetForTesting();
        KeepADBForceMode.setClockForTesting(new KeepADBForceTestSupport.TestClock());
        gateway = new KeepADBFakeSettingsGateway(true);
        KeepADB.setGatewayForTesting(gateway);
        scheduler = new KeepADBFakeScheduler();
        KeepADBUntrustedShutdown.setSchedulerForTesting(scheduler);
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        KeepADBOpenNetwork.setProbeForTesting(probe);
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBPreferences.setLastDesiredOn(context, true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBTrustedNetwork.addBssid(context, HOME, "Home");
        KeepADBForceTestSupport.connectTo(context, "Home", HOME);
        KeepADBPreferences.setOpenNetworkShutdownEnabled(context, true);
    }

    @After
    public void tearDown() {
        KeepADBOpenNetwork.setProbeForTesting(null);
        KeepADBUntrustedShutdown.resetForTesting();
        KeepADBForceMode.resetForTesting();
        KeepADB.resetForTesting();
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(null);
        prefs().edit().clear().commit();
    }

    // --- The pure rule -------------------------------------------------------------------------------

    @Test
    public void onlyPositiveEvidenceCounts() {
        assertEquals(KeepADBOpenNetwork.Reason.OPEN,
                KeepADBOpenNetwork.of(KeepADBNetworkSecurity.TIER_OPEN, false));
        assertEquals(KeepADBOpenNetwork.Reason.CAPTIVE_PORTAL,
                KeepADBOpenNetwork.of(KeepADBNetworkSecurity.TIER_PERSONAL, true));
        assertEquals("Android 11: no tier, but the captive portal still counts",
                KeepADBOpenNetwork.Reason.CAPTIVE_PORTAL, KeepADBOpenNetwork.of(null, true));
        assertEquals(KeepADBOpenNetwork.Reason.NONE, KeepADBOpenNetwork.of(null, false));
        assertEquals("an unknown type is no evidence of an open network",
                KeepADBOpenNetwork.Reason.NONE,
                KeepADBOpenNetwork.of(KeepADBNetworkSecurity.TIER_UNKNOWN, false));
        assertEquals(KeepADBOpenNetwork.Reason.NONE,
                KeepADBOpenNetwork.of(KeepADBNetworkSecurity.TIER_ENTERPRISE, false));
    }

    @Test
    public void openTypesMapToTheOpenTier() {
        assertEquals(KeepADBNetworkSecurity.TIER_OPEN, KeepADBNetworkSecurity.tierOfType(0)); // OPEN
        assertEquals(KeepADBNetworkSecurity.TIER_OPEN, KeepADBNetworkSecurity.tierOfType(1)); // WEP
        assertEquals(KeepADBNetworkSecurity.TIER_OPEN, KeepADBNetworkSecurity.tierOfType(6)); // OWE
    }

    // --- The trust evaluation ------------------------------------------------------------------------

    @Test
    public void anOpenTrustedAccessPointAllowsNoAutomaticEnable() {
        probe.tier = KeepADBNetworkSecurity.TIER_OPEN;

        assertEquals(KeepADBTrustedNetwork.Decision.OPEN_NETWORK,
                KeepADBTrustedNetwork.evaluateCurrent(context));
        assertFalse(KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
        assertEquals(KeepADBTrustedNetwork.BlockReason.OPEN_NETWORK,
                KeepADBTrustedNetwork.getBlockReason(context));
    }

    @Test
    public void aCaptivePortalBeatsTheLegacyAllNetworksSetting() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        probe.captive = true;

        assertEquals(KeepADBTrustedNetwork.Decision.CAPTIVE_PORTAL,
                KeepADBTrustedNetwork.evaluateCurrent(context));
    }

    @Test
    public void forceModeOverridesTheRule() {
        probe.tier = KeepADBNetworkSecurity.TIER_OPEN;
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOURS_24, false));

        assertEquals(KeepADBTrustedNetwork.Decision.FORCE_MODE,
                KeepADBTrustedNetwork.evaluateCurrent(context));
    }

    @Test
    public void withTheRuleOffAnOpenTrustedAccessPointStaysTrusted() {
        KeepADBPreferences.setOpenNetworkShutdownEnabled(context, false);
        probe.tier = KeepADBNetworkSecurity.TIER_OPEN;
        probe.captive = true;

        assertEquals(KeepADBTrustedNetwork.Decision.TRUSTED_ACCESS_POINT,
                KeepADBTrustedNetwork.evaluateCurrent(context));
    }

    @Test
    public void theListsDoNotSeeTheRule() {
        probe.tier = KeepADBNetworkSecurity.TIER_OPEN;

        assertEquals("the Networks list and prompts judge the access point, not the connection",
                KeepADBTrustedNetwork.Decision.TRUSTED_ACCESS_POINT,
                KeepADBTrustedNetwork.evaluate(context, new KeepADBNetworkIdentity("Home", HOME)));
    }

    // --- The shutdown --------------------------------------------------------------------------------

    @Test
    public void anOpenTrustedAccessPointSwitchesOffWithoutTheUntrustedSwitch() {
        assertFalse(KeepADBPreferences.isUntrustedShutdownEnabled(context));
        probe.tier = KeepADBNetworkSecurity.TIER_OPEN;

        KeepADBUntrustedShutdown.evaluate(context, "test");

        assertEquals(Collections.singletonList(false), gateway.writes);
        assertTrue("Keep-Alive stays armed: the rule is not the user's off",
                KeepADBPreferences.getLastDesiredOn(context));
        assertFalse(KeepADB.wasLastExplicitIntentOff(context));
        Notification notification = posted();
        assertNotNull(notification);
        assertEquals(context.getString(R.string.open_network_off_text),
                notification.extras.getString(Notification.EXTRA_TEXT));
    }

    @Test
    public void aCaptivePortalSwitchesOff() {
        probe.captive = true;

        KeepADBUntrustedShutdown.evaluate(context, "test");

        assertEquals(Collections.singletonList(false), gateway.writes);
    }

    @Test
    public void aProtectedTrustedAccessPointStaysOn() {
        KeepADBUntrustedShutdown.evaluate(context, "test");

        assertEquals(Collections.emptyList(), gateway.writes);
        assertNull(posted());
    }

    @Test
    public void withTheRuleOffNothingIsWritten() {
        KeepADBPreferences.setOpenNetworkShutdownEnabled(context, false);
        probe.tier = KeepADBNetworkSecurity.TIER_OPEN;

        KeepADBUntrustedShutdown.evaluate(context, "test");

        assertEquals(Collections.emptyList(), gateway.writes);
    }

    @Test
    public void withoutKeepAliveNothingIsWritten() {
        KeepADBPreferences.setKeepAliveEnabled(context, false);
        probe.tier = KeepADBNetworkSecurity.TIER_OPEN;

        KeepADBUntrustedShutdown.evaluate(context, "test");

        assertEquals(Collections.emptyList(), gateway.writes);
    }

    @Test
    public void forceModeSuspendsTheShutdown() {
        probe.tier = KeepADBNetworkSecurity.TIER_OPEN;
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOURS_24, false));

        KeepADBUntrustedShutdown.evaluate(context, "test");

        assertEquals(Collections.emptyList(), gateway.writes);
    }

    @Test
    public void aManualEnableAtTheAccessPointStaysOn() {
        KeepADB.setGatewayForTesting(gateway = new KeepADBFakeSettingsGateway(false));
        probe.tier = KeepADBNetworkSecurity.TIER_OPEN;

        KeepADB.setEnabled(context, true, KeepADB.SOURCE_APP);
        KeepADBUntrustedShutdown.evaluate(context, "test");

        assertEquals(Collections.singletonList(true), gateway.writes);
    }

    @Test
    public void aManualEnableOnAnUnreadableIdentityStaysOnInAnOpenWifi() {
        KeepADB.setGatewayForTesting(gateway = new KeepADBFakeSettingsGateway(false));
        KeepADBForceTestSupport.connectTo(context, "", null);
        probe.tier = KeepADBNetworkSecurity.TIER_OPEN;

        KeepADB.setEnabled(context, true, KeepADB.SOURCE_APP);
        KeepADBUntrustedShutdown.evaluate(context, "test");

        assertEquals(KeepADBUntrustedShutdown.MANUAL_UNREADABLE,
                KeepADBPreferences.getUntrustedShutdownManualBssid(context));
        assertEquals(Collections.singletonList(true), gateway.writes);
    }

    @Test
    public void theUnreadableExemptionDoesNotCoverTheUntrustedRule() {
        KeepADBPreferences.setUntrustedShutdownManualBssid(context,
                KeepADBUntrustedShutdown.MANUAL_UNREADABLE, 0L);

        assertTrue(KeepADBUntrustedShutdown.isManuallyExempt(context,
                KeepADBTrustedNetwork.Decision.OPEN_NETWORK));
        assertFalse(KeepADBUntrustedShutdown.isManuallyExempt(context,
                KeepADBTrustedNetwork.Decision.IDENTITY_UNAVAILABLE));
        assertFalse(KeepADBUntrustedShutdown.isManuallyExempt(context));
    }

    @Test
    public void anOpenAccessPointAfterAProtectedOneEndsTheSession() {
        String cafe = "aa:bb:cc:00:00:09";
        KeepADBForceTestSupport.connectTo(context, "Cafe", cafe);
        probe.tier = KeepADBNetworkSecurity.TIER_OPEN;
        KeepADBPreferences.setUntrustedShutdownManualBssid(context, HOME, 0L);

        KeepADBUntrustedShutdown.evaluate(context, "test");

        assertEquals("an exemption at Home does not carry to another access point",
                Collections.singletonList(false), gateway.writes);
    }

    // --- The default ---------------------------------------------------------------------------------

    @Test
    public void aNewInstallationGetsTheRuleOn() {
        prefs().edit().clear().commit();

        KeepADBPreferences.migrateOpenNetworkShutdown(context);

        assertTrue(KeepADBPreferences.isOpenNetworkShutdownEnabled(context));
    }

    @Test
    public void anExistingInstallationKeepsTheRuleOff() {
        prefs().edit().clear().putBoolean("keep_alive_enabled", true).commit();

        KeepADBPreferences.migrateOpenNetworkShutdown(context);

        assertFalse(KeepADBPreferences.isOpenNetworkShutdownEnabled(context));
        assertTrue("the decision is stored, so it never flips later", prefs().contains(KEY));
    }

    @Test
    public void theMigrationKeepsAStoredChoice() {
        prefs().edit().clear().putBoolean(KEY, false).commit();
        KeepADBPreferences.migrateOpenNetworkShutdown(context);
        assertFalse(KeepADBPreferences.isOpenNetworkShutdownEnabled(context));

        prefs().edit().clear().putBoolean("keep_alive_enabled", true).putBoolean(KEY, true).commit();
        KeepADBPreferences.migrateOpenNetworkShutdown(context);
        assertTrue(KeepADBPreferences.isOpenNetworkShutdownEnabled(context));
    }

    @Test
    public void thePrivacySplitStillSeesANewInstallationAfterTheDefault() {
        prefs().edit().clear().commit();

        KeepADBPreferences.migrateOpenNetworkShutdown(context);
        KeepADBPreferences.migratePrivacySplit(context);

        assertFalse("a new installation keeps all privacy switches off",
                KeepADBPreferences.isNotificationPrivacyEnabled(context));
        assertFalse(KeepADBPreferences.isWidgetPrivacyEnabled(context));
        assertEquals(new java.util.TreeSet<>(Arrays.asList(KEY, "privacy_split_initialized")),
                new java.util.TreeSet<>(prefs().getAll().keySet()));
    }

    @Test
    public void theAssistantStillSeesANewInstallationAfterTheDefault() {
        prefs().edit().clear().commit();

        KeepADBPreferences.migrateOpenNetworkShutdown(context);
        KeepADBPreferences.migratePrivacySplit(context);

        assertFalse("the stored default is no sign of an existing installation",
                KeepADBOnboarding.isExistingInstall(context));
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE);
    }

    private Notification posted() {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        return shadowOf(manager).getNotification(KeepADBUntrustedShutdown.NOTIFICATION_ID);
    }
}
