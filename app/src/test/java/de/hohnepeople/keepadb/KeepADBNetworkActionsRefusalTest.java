package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

/**
 * #762 (review finding of E1): a trust that is refused because of a block says so instead of
 * reporting a generic failure. The message is pinned next to its control: a refusal that is no block
 * (a blank address) keeps the generic failure text (the mesh offer of #686 is gone, #788).
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

        KeepADBNetworkActions.allowAccessPoint(activity, CURRENT, SSID, null);
        assertEquals(context.getString(R.string.network_decision_trust_refused_toast), lastToast());
        assertTrue(KeepADBTrustedNetwork.getEntries(context).isEmpty());

        // A block on the name refuses it the same way, for another address of that name.
        ShadowToast.reset();
        KeepADBNetworkBlocklist.unblockBssid(context, CURRENT);
        KeepADBNetworkBlocklist.blockSsid(context, SSID);
        KeepADBNetworkActions.allowAccessPoint(activity, NODE_A, SSID, null);
        assertEquals(context.getString(R.string.network_decision_trust_refused_toast), lastToast());

        // Control: a refusal that is no block (nothing usable to store) is still the generic failure.
        ShadowToast.reset();
        KeepADBNetworkBlocklist.unblockSsid(context, SSID);
        KeepADBNetworkActions.allowAccessPoint(activity, " ", " ", null);
        assertEquals(context.getString(R.string.settings_trusted_network_add_failed_toast), lastToast());

        // Control: an unblocked network is allowed and says so.
        ShadowToast.reset();
        KeepADBNetworkActions.allowAccessPoint(activity, NODE_B, SSID, null);
        assertTrue(lastToast(), lastToast().contains(SSID));
        assertEquals(1, KeepADBTrustedNetwork.getEntries(context).size());
    }

    private Activity activity() {
        return Robolectric.buildActivity(NetworkListActivity.class,
                new Intent(context, NetworkListActivity.class)).setup().get();
    }

    private static String lastToast() {
        return ShadowToast.getTextOfLatestToast();
    }
}
