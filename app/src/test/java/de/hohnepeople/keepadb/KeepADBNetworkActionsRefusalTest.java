package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.shadows.ShadowWifiInfo;

/**
 * #762 (review finding of E1): a trust that is refused because of a block says so instead of
 * reporting a generic failure, and the mesh offer neither offers a blocked access point nor counts
 * a refused one as added. Each message is pinned next to its control: a refusal that is no block
 * (a blank address) keeps the generic failure text, and an offer without a block counts everything.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBNetworkActionsRefusalTest {

    private static final String SSID = "MeshHome";
    private static final String CURRENT = "aa:bb:cc:dd:ee:01";
    private static final String NODE_A = "aa:bb:cc:dd:ee:02";
    private static final String NODE_B = "aa:bb:cc:dd:ee:03";

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = RuntimeEnvironment.getApplication();

    @Before
    public void setUp() {
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS,
                android.Manifest.permission.POST_NOTIFICATIONS);
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADB.resetForTesting();
        ShadowToast.reset();
    }

    @After
    public void tearDown() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADB.resetForTesting();
    }

    @Test
    public void aGrantRefusedByABlockSaysTheNetworkIsBlockedAndAnyOtherFailureStaysGeneric() {
        Activity activity = activity();
        KeepADBNetworkBlocklist.blockBssid(context, CURRENT);

        assertNull(KeepADBNetworkActions.allowAccessPoint(activity, CURRENT, SSID, false, null));
        assertEquals(context.getString(R.string.network_decision_trust_refused_toast), lastToast());
        assertTrue(KeepADBTrustedNetwork.getEntries(context).isEmpty());

        // A block on the name refuses it the same way, for another address of that name.
        ShadowToast.reset();
        KeepADBNetworkBlocklist.unblockBssid(context, CURRENT);
        KeepADBNetworkBlocklist.blockSsid(context, SSID);
        KeepADBNetworkActions.allowAccessPoint(activity, NODE_A, SSID, false, null);
        assertEquals(context.getString(R.string.network_decision_trust_refused_toast), lastToast());

        // Control: a refusal that is no block (nothing usable to store) is still the generic failure.
        ShadowToast.reset();
        KeepADBNetworkBlocklist.unblockSsid(context, SSID);
        KeepADBNetworkActions.allowAccessPoint(activity, " ", " ", false, null);
        assertEquals(context.getString(R.string.settings_trusted_network_add_failed_toast), lastToast());

        // Control: an unblocked network is allowed and says so.
        ShadowToast.reset();
        KeepADBNetworkActions.allowAccessPoint(activity, NODE_B, SSID, false, null);
        assertTrue(lastToast(), lastToast().contains(SSID));
        assertEquals(1, KeepADBTrustedNetwork.getEntries(context).size());
    }

    @Test
    public void theMeshOfferLeavesOutABlockedAccessPointAndCountsOnlyWhatWasStored() {
        Activity activity = activity();
        connectTo(SSID, CURRENT);
        KeepADBBssidHistory.recordObservation(context, SSID, NODE_A);
        KeepADBBssidHistory.recordObservation(context, SSID, NODE_B);
        KeepADBNetworkBlocklist.blockBssid(context, NODE_B);

        AlertDialog offer = KeepADBNetworkActions.offerAdditionalMeshBssids(activity, null);
        assertNotNull(offer);
        offer.show();
        assertTrue("Only the access point that can be trusted is offered: " + dialogText(offer),
                dialogText(offer).contains("1"));
        offer.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        List<String> trusted = new ArrayList<>();
        for (KeepADBTrustedNetwork.Entry entry : KeepADBTrustedNetwork.getEntries(context)) {
            trusted.add(entry.bssid);
        }
        assertEquals("The blocked node must not be stored", List.of(NODE_A), trusted);
        assertEquals(context.getString(R.string.settings_trusted_network_mesh_added_toast, 1),
                lastToast());
    }

    @Test
    public void theMeshOfferIsOfferedForNothingWhenTheNameOrEveryNodeIsBlockedAndCountsAllOtherwise() {
        Activity activity = activity();
        connectTo(SSID, CURRENT);
        KeepADBBssidHistory.recordObservation(context, SSID, NODE_A);
        KeepADBBssidHistory.recordObservation(context, SSID, NODE_B);

        KeepADBNetworkBlocklist.blockSsid(context, SSID);
        assertNull("Every access point of a blocked name is blocked",
                KeepADBNetworkActions.offerAdditionalMeshBssids(activity, null));
        KeepADBNetworkBlocklist.unblockSsid(context, SSID);

        KeepADBNetworkBlocklist.blockBssid(context, NODE_A);
        KeepADBNetworkBlocklist.blockBssid(context, NODE_B);
        assertNull(KeepADBNetworkActions.offerAdditionalMeshBssids(activity, null));
        KeepADBNetworkBlocklist.unblockBssid(context, NODE_A);
        KeepADBNetworkBlocklist.unblockBssid(context, NODE_B);

        // Control: nothing blocked, both are offered and both counted.
        AlertDialog offer = KeepADBNetworkActions.offerAdditionalMeshBssids(activity, null);
        assertNotNull(offer);
        offer.show();
        offer.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertEquals(2, KeepADBTrustedNetwork.getEntries(context).size());
        assertEquals(context.getString(R.string.settings_trusted_network_mesh_added_toast, 2),
                lastToast());
    }

    private Activity activity() {
        return Robolectric.buildActivity(NetworkListActivity.class,
                new Intent(context, NetworkListActivity.class)).setup().get();
    }

    private static String lastToast() {
        return ShadowToast.getTextOfLatestToast();
    }

    private static String dialogText(AlertDialog dialog) {
        android.widget.TextView message = dialog.findViewById(android.R.id.message);
        return message == null ? "" : message.getText().toString();
    }

    private void connectTo(String ssid, String bssid) {
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = ShadowWifiInfo.newInstance();
        shadowOf(info).setSSID(ssid);
        shadowOf(info).setBSSID(bssid);
        shadowOf(wifiManager).setConnectionInfo(info);
    }
}
