package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.location.LocationManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;

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
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowWifiInfo;

/**
 * #769: removing the old network settings (mode choice, observation option, legacy Wi-Fi-name
 * list, the three older list views) must not lose or change a value an existing installation
 * stored. The cleanup has no key migration on purpose: every key is either still read by the trust
 * logic (mode, name list and its switch, trust-by-name, blocks, entries) or is kept as it is (the
 * observation option, its history and the prevented history that the decision flow still reads).
 * What these tests pin, each with its other side:
 *
 * <ul>
 *   <li>a stored legacy installation (both ways: the wide "all networks" policy with names, and the
 *       strict allowlist) is byte for byte the same in the preferences after opening Settings, its
 *       Wi-Fi reactions, a rotation and the Networks list, and the trust decision for the same
 *       network is the same before and after;
 *   <li>the legacy name list still grants while its switch is on and stops granting when it is off,
 *       so the kept value is really read and not just left lying around;
 *   <li>the card no longer records the observation history, whatever the stored option says, while
 *       the recorder itself still works (so the test can fail).
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SettingsCleanupMigrationTest {

    private static final String MESH = "Mesh";
    private static final String HOME_AP = "aa:bb:cc:dd:ee:01";
    private static final String MESH_AP = "aa:bb:cc:dd:ee:02";
    private static final String BLOCKED_AP = "aa:bb:cc:dd:ee:03";

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = RuntimeEnvironment.getApplication();

    @Before
    public void setUp() {
        prefs().edit().clear().commit();
        shadowOf((Application) context).denyPermissions(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        shadowOf(context.getSystemService(LocationManager.class)).setLocationEnabled(true);
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
    }

    @After
    public void tearDown() {
        prefs().edit().clear().commit();
        KeepADB.resetForTesting();
    }

    /** Both directions: the wide legacy policy and the strict allowlist, each with every old value set. */
    @Test
    public void aStoredLegacyInstallationKeepsEveryValueAndItsDecisionAfterTheCleanup() {
        for (boolean wide : new boolean[] {true, false}) {
            seedLegacyInstallation(wide);
            connectTo(MESH, MESH_AP);
            Map<String, ?> before = everyPreference();
            KeepADBTrustedNetwork.Decision decisionBefore = KeepADBTrustedNetwork.evaluate(
                    context, new KeepADBNetworkIdentity(MESH, MESH_AP));
            KeepADBTrustedNetwork.ProtectionLevel levelBefore =
                    KeepADBTrustedNetwork.getProtectionLevel(context);
            assertFalse("The seed must hold every old key", before.isEmpty());
            assertTrue("The seed holds the name block", before.containsKey(KeepADBNetworkBlocklist.KEY_SSIDS));
            assertTrue("The seed holds an own name", before.keySet().stream()
                    .anyMatch(key -> key.startsWith("trusted_network_") && key.endsWith("_name")));

            ActivityController<SettingsActivity> settings =
                    Robolectric.buildActivity(SettingsActivity.class).setup();
            settings.get().findViewById(R.id.settings_network_beta_header).performClick();
            settings.pause().resume();
            settings.get().refresh();
            Bundle state = new Bundle();
            settings.saveInstanceState(state);
            settings.pause().stop().destroy();
            ShadowLooper.idleMainLooper();
            Robolectric.buildActivity(SettingsActivity.class).setup(state).pause().stop().destroy();
            Robolectric.buildActivity(NetworkListActivity.class,
                    NetworkListActivity.intent(context)).setup().pause().stop().destroy();

            String label = wide ? "wide legacy policy" : "strict allowlist";
            assertEquals(label + ": every stored value is unchanged", before, everyPreference());
            assertEquals(label + ": the decision for the same network is unchanged", decisionBefore,
                    KeepADBTrustedNetwork.evaluate(context, new KeepADBNetworkIdentity(MESH, MESH_AP)));
            assertEquals(label + ": the level reads the same", levelBefore,
                    KeepADBTrustedNetwork.getProtectionLevel(context));
        }
    }

    /** The kept name list is really read: it grants while its switch is on and not once it is off. */
    @Test
    public void theKeptNameListStillGrantsWhileItsSwitchIsOnAndNotWhenOff() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBTrustedNetwork.addSsid(context, MESH);
        KeepADBNetworkIdentity unknownNode = new KeepADBNetworkIdentity(MESH, MESH_AP);

        KeepADBTrustedNetwork.setSsidMatchingEnabled(context, true);
        assertEquals(KeepADBTrustedNetwork.Decision.TRUSTED_NAME,
                KeepADBTrustedNetwork.evaluate(context, unknownNode));
        assertEquals(KeepADBTrustedNetwork.ProtectionLevel.LEGACY_NAME_LIST,
                KeepADBTrustedNetwork.getProtectionLevel(context));
        // Opening Settings, which no longer shows the list, changes nothing about it.
        Robolectric.buildActivity(SettingsActivity.class).setup().pause().stop().destroy();
        assertEquals(KeepADBTrustedNetwork.Decision.TRUSTED_NAME,
                KeepADBTrustedNetwork.evaluate(context, unknownNode));

        KeepADBTrustedNetwork.setSsidMatchingEnabled(context, false);
        assertEquals(KeepADBTrustedNetwork.Decision.UNKNOWN_NETWORK,
                KeepADBTrustedNetwork.evaluate(context, unknownNode));
    }

    /** The card does not feed the observation history any more; the recorder itself still works. */
    @Test
    public void theCardRecordsNoObservationWhateverTheStoredOptionSays() {
        KeepADBPreferences.setWifiApsFeatureEnabled(context, true);
        connectTo(MESH, MESH_AP);

        ActivityController<SettingsActivity> settings =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        settings.get().findViewById(R.id.settings_network_beta_header).performClick();
        settings.pause().resume();
        settings.get().refresh();
        settings.pause().stop().destroy();

        assertTrue("Opening Settings records nothing", KeepADBBssidHistory
                .getRecentObservations(context).isEmpty());
        assertTrue("The stored option itself is kept", KeepADBPreferences.isWifiApsFeatureEnabled(context));

        // The other side: the recorder works, so an empty history above is the card's doing.
        KeepADBBssidHistory.recordObservation(context, MESH, MESH_AP, 5200);
        assertEquals(1, KeepADBBssidHistory.getRecentObservations(context).size());
    }

    private void seedLegacyInstallation(boolean wide) {
        prefs().edit().clear().commit();
        KeepADBTrustedNetwork.setMode(context, wide
                ? KeepADBTrustedNetwork.MODE_ALL_WIFI : KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBTrustedNetwork.setSsidMatchingEnabled(context, true);
        KeepADBTrustedNetwork.addSsid(context, MESH);
        KeepADBTrustedNetwork.addBssid(context, HOME_AP, "Home");
        KeepADBTrustedNetwork.setTrustByNameEnabled(context, !wide);
        KeepADBNetworkBlocklist.blockBssid(context, BLOCKED_AP);
        KeepADBNetworkBlocklist.blockSsid(context, "Gastnetz");
        // The own name of a saved access point (trusted_network_<id>_name) is stored data too.
        assertTrue(KeepADBTrustedNetwork.setCustomName(context,
                KeepADBTrustedNetwork.getEntries(context).get(0).id, "Kueche"));
        KeepADBPreferences.setWifiApsFeatureEnabled(context, true);
        KeepADBBssidHistory.recordObservation(context, MESH, MESH_AP, 5200);
        KeepADBBlockedNetworkHistory.record(context,
                new KeepADBNetworkIdentity("Cafe", BLOCKED_AP), 1_000L);
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

    private Map<String, ?> everyPreference() {
        return new TreeMap<>(prefs().getAll());
    }
}
