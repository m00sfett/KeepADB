package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.wifi.WifiManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import de.hohnepeople.keepadb.KeepADBNetworkCardState.Connection;
import de.hohnepeople.keepadb.KeepADBNetworkCardState.Snapshot;

/**
 * #654/#655: the card explains the trust decision, it must never contradict it. In allowlist mode
 * the card shows a connection as allowed exactly when {@link KeepADBTrustedNetwork} would trust
 * it; both directions are asserted over every identity/list/switch combination, so an
 * explanation that says "allowed" for a network the policy blocks (or the reverse) fails here.
 * The inputs go through the production reader {@link KeepADBNetworkCardState#read}, so that
 * wiring is covered too.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBNetworkCardStateTrustAgreementTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = RuntimeEnvironment.getApplication();

    @Before
    public void setUp() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:01", "Home");
        KeepADBTrustedNetwork.addSsid(context, "Mesh");
    }

    @After
    public void tearDown() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
    }

    private KeepADBNetworkIdentity[] identities() {
        return new KeepADBNetworkIdentity[] {
                new KeepADBNetworkIdentity("\"Home\"", "AA:BB:CC:DD:EE:01"),
                new KeepADBNetworkIdentity("\"Mesh\"", "aa:bb:cc:dd:ee:02"),
                new KeepADBNetworkIdentity("\"mesh\"", "aa:bb:cc:dd:ee:02"),
                new KeepADBNetworkIdentity("\"Other\"", "aa:bb:cc:dd:ee:03"),
                new KeepADBNetworkIdentity(WifiManager.UNKNOWN_SSID, "aa:bb:cc:dd:ee:04"),
                new KeepADBNetworkIdentity("\"Mesh\"", KeepADBNetworkIdentity.REDACTED_BSSID),
                new KeepADBNetworkIdentity("\"Home\"", KeepADBNetworkIdentity.UNSET_BSSID),
                new KeepADBNetworkIdentity("\"Mesh\"", null),
                new KeepADBNetworkIdentity(null, null),
        };
    }

    private static boolean shownAsAllowed(Snapshot state) {
        return state.connection == Connection.ALLOWED_AP
                || state.connection == Connection.ALLOWED_NAME;
    }

    @Test
    public void theCardShowsAllowedExactlyWhenThePolicyTrusts() {
        int trusted = 0;
        int blocked = 0;
        for (boolean nameMatching : new boolean[] {false, true}) {
            KeepADBTrustedNetwork.setSsidMatchingEnabled(context, nameMatching);
            for (KeepADBNetworkIdentity identity : identities()) {
                Snapshot state = KeepADBNetworkCardState.derive(KeepADBNetworkCardState.read(
                        context, identity, true, true, true, false));
                boolean policy = KeepADBTrustedNetwork.isTrustedForTesting(context, identity);
                assertEquals("names=" + nameMatching + " ssid=" + identity.ssid
                        + " bssid=" + identity.bssid, policy, shownAsAllowed(state));
                if (policy) trusted++; else blocked++;
            }
        }
        assertTrue("The matrix must contain allowed cases, or the check proves nothing", trusted > 0);
        assertTrue("The matrix must contain blocked cases, or the check proves nothing", blocked > 0);
    }

    @Test
    public void allowedByNameIsReportedOnlyForTheExactNameWithMatchingOn() {
        KeepADBNetworkIdentity meshNode =
                new KeepADBNetworkIdentity("\"Mesh\"", "aa:bb:cc:dd:ee:02");

        KeepADBTrustedNetwork.setSsidMatchingEnabled(context, true);
        assertEquals(Connection.ALLOWED_NAME, KeepADBNetworkCardState.derive(
                KeepADBNetworkCardState.read(context, meshNode, true, true, true, false)).connection);

        KeepADBTrustedNetwork.setSsidMatchingEnabled(context, false);
        assertEquals(Connection.NOT_ALLOWED, KeepADBNetworkCardState.derive(
                KeepADBNetworkCardState.read(context, meshNode, true, true, true, false)).connection);

        KeepADBTrustedNetwork.setSsidMatchingEnabled(context, true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        Snapshot allWifi = KeepADBNetworkCardState.derive(
                KeepADBNetworkCardState.read(context, meshNode, true, true, true, false));
        assertFalse("A saved name has no effect while all networks are allowed",
                shownAsAllowed(allWifi));
        assertEquals(KeepADBNetworkCardState.NameMatching.NO_EFFECT, allWifi.nameMatching);
    }
}
