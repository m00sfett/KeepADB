package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import java.util.Map;
import java.util.TreeMap;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowWifiInfo;

import de.hohnepeople.keepadb.KeepADBNetworkList.Group;
import de.hohnepeople.keepadb.KeepADBNetworkList.Snapshot;

/**
 * #778: the old observation history is discarded once on update and nothing else is touched.
 * #788: the readers of that history are gone, so a leftover history changes nothing that is shown.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBBssidHistoryDiscardTest {

    private static final String MESH = "Mesh";
    private static final String HOME_AP = "aa:bb:cc:dd:ee:01";
    private static final String MESH_AP = "aa:bb:cc:dd:ee:02";
    private static final String BLOCKED_AP = "aa:bb:cc:dd:ee:03";

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = RuntimeEnvironment.getApplication();

    @Before
    public void setUp() {
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
        prefs().edit().clear().commit();
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
    }

    @After
    public void tearDown() {
        prefs().edit().clear().commit();
        KeepADB.resetForTesting();
    }

    @Test
    public void theHistoryIsGoneAfterTheDiscardAndEveryOtherKeyIsExactlyAsBefore() {
        seedInstallation();
        Map<String, Object> before = everyPreference();
        Map<String, Object> expected = new TreeMap<>(before);
        expected.keySet().removeIf(key -> key.startsWith("bssid_history_"));
        assertTrue("The seed holds history keys", before.size() > expected.size());
        assertTrue(before.containsKey(KeepADBPreferences.KEY_WIFI_APS_FEATURE_ENABLED));

        assertFalse(historyKeys().isEmpty());
        assertTrue(KeepADBBssidHistory.discardLegacyOnce(context));

        assertTrue("No history key is left", historyKeys().isEmpty());
        Map<String, Object> after = everyPreference();
        assertEquals(Boolean.TRUE, after.remove(KeepADBBssidHistory.KEY_LEGACY_DISCARDED));
        assertEquals("Every key that is not history is untouched, value for value", expected, after);
    }

    @Test
    public void theDiscardRunsExactlyOnce() {
        seedInstallation();
        assertTrue(KeepADBBssidHistory.discardLegacyOnce(context));

        // Something recorded later is no legacy data: the second run must leave it alone.
        prefs().edit().putString("bssid_history_9_ssid", MESH).commit();
        assertFalse(KeepADBBssidHistory.discardLegacyOnce(context));

        assertEquals(MESH, prefs().getString("bssid_history_9_ssid", null));
    }

    @Test
    public void aFreshInstallationOnlyGetsTheMarkerAndIsNotTakenForAnExistingOne() {
        assertTrue(prefs().getAll().isEmpty());

        assertTrue(KeepADBBssidHistory.discardLegacyOnce(context));

        assertEquals(1, prefs().getAll().size());
        assertFalse("The marker is bookkeeping, not an existing setting",
                KeepADBOnboarding.isExistingInstall(context));
    }

    @Test
    public void anExistingInstallationStaysExistingWhetherTheDiscardRanBeforeTheCheckOrAfter() {
        for (boolean discardFirst : new boolean[] {true, false}) {
            prefs().edit().clear().commit();
            KeepADBTrustedNetwork.addBssid(context, HOME_AP, "Home");
            if (discardFirst) KeepADBBssidHistory.discardLegacyOnce(context);

            assertTrue("discardFirst=" + discardFirst, KeepADBOnboarding.isExistingInstall(context));
            KeepADBBssidHistory.discardLegacyOnce(context);
            assertTrue(KeepADBOnboarding.isExistingInstall(context));
        }
    }

    @Test
    public void theUpdateReceiverDiscardsAndSoDoesTheHomeScreen() {
        seedInstallation();
        new BootReceiver().onReceive(context, new Intent(Intent.ACTION_MY_PACKAGE_REPLACED));
        assertTrue(prefs().getBoolean(KeepADBBssidHistory.KEY_LEGACY_DISCARDED, false));
        assertTrue(historyKeys().isEmpty());

        prefs().edit().clear().commit();
        seedInstallation();
        KeepADBOnboarding.setAutoStartEnabledForTesting(false);
        Robolectric.buildActivity(MainActivity.class).setup().pause().stop().destroy();
        assertTrue(prefs().getBoolean(KeepADBBssidHistory.KEY_LEGACY_DISCARDED, false));
        assertTrue(historyKeys().isEmpty());
    }

    @Test
    public void theBlockedAccessPointHasNoNameAfterTheDiscard() {
        seedInstallation();
        KeepADBBssidHistory.discardLegacyOnce(context);
        connectTo(MESH, MESH_AP);

        // The blocked access point without a trust entry has no name any more.
        Snapshot snapshot = KeepADBNetworkList.build(context,
                new KeepADBNetworkIdentity(MESH, MESH_AP), true);
        Group unnamed = null;
        for (Group group : snapshot.groups) {
            if (group.ssid == null) unnamed = group;
        }
        assertTrue("The blocked access point is in the group of unknown names",
                unnamed != null && unnamed.rows.size() == 1
                        && BLOCKED_AP.equalsIgnoreCase(unnamed.rows.get(0).bssid));
    }

    @Test
    public void aLeftoverHistoryNamesNothingBecauseNoReaderIsLeft() {
        seedInstallation();

        Snapshot snapshot = KeepADBNetworkList.build(context,
                new KeepADBNetworkIdentity(MESH, MESH_AP), true);

        Group unnamed = null;
        for (Group group : snapshot.groups) {
            assertFalse("The history must not name the access point", "Cafe".equals(group.ssid));
            if (group.ssid == null) unnamed = group;
        }
        assertTrue("The blocked access point stays in the group of unknown names",
                unnamed != null && unnamed.rows.size() == 1
                        && BLOCKED_AP.equalsIgnoreCase(unnamed.rows.get(0).bssid));
    }

    private void seedInstallation() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBTrustedNetwork.setSsidMatchingEnabled(context, true);
        KeepADBTrustedNetwork.addSsid(context, MESH);
        KeepADBTrustedNetwork.addBssid(context, HOME_AP, "Home");
        KeepADBTrustedNetwork.setTrustByNameEnabled(context, true);
        KeepADBNetworkBlocklist.blockBssid(context, BLOCKED_AP);
        KeepADBNetworkBlocklist.blockSsid(context, "Gastnetz");
        assertTrue(KeepADBTrustedNetwork.setCustomName(context,
                KeepADBTrustedNetwork.getEntries(context).get(0).id, "Kueche"));
        prefs().edit().putBoolean(KeepADBPreferences.KEY_WIFI_APS_FEATURE_ENABLED, true).commit();
        KeepADBPreferences.setOnboardingCompletedVersion(context, 1);
        KeepADBBlockedNetworkHistory.record(context,
                new KeepADBNetworkIdentity("Cafe", BLOCKED_AP), 1_000L);
        prefs().edit().putBoolean(KeepADBPreferences.KEY_ONBOARDING_EXISTING_INSTALL, true).commit();
        // The history as the former writer stored it: ids, one name and address list per id, bands.
        prefs().edit()
                .putInt("bssid_history_next_id", 3)
                .putString("bssid_history_ssid_ids", "1,2")
                .putString("bssid_history_1_ssid", MESH)
                .putString("bssid_history_1_bssids", MESH_AP)
                .putString("bssid_history_1_bands", MESH_AP.toUpperCase() + "=2")
                .putString("bssid_history_2_ssid", "Cafe")
                .putString("bssid_history_2_bssids", BLOCKED_AP)
                .putString("bssid_history_2_bands", BLOCKED_AP.toUpperCase() + "=1")
                .commit();
    }

    private java.util.Set<String> historyKeys() {
        java.util.Set<String> keys = new java.util.TreeSet<>();
        for (String key : prefs().getAll().keySet()) {
            if (key.startsWith("bssid_history_")) keys.add(key);
        }
        return keys;
    }

    private void connectTo(String ssid, String bssid) {
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = ShadowWifiInfo.newInstance();
        shadowOf(info).setSSID(ssid);
        shadowOf(info).setBSSID(bssid);
        shadowOf(wifiManager).setConnectionInfo(info);
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE);
    }

    private Map<String, Object> everyPreference() {
        return new TreeMap<>(prefs().getAll());
    }
}
