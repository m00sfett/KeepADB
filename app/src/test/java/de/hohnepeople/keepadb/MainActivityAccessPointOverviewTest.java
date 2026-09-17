package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

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
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowWifiInfo;

/**
 * On-device rendering tests for the "Wi-Fi &amp; Access Points" card on {@link MainActivity}
 * (#461): the current access point's highlight, the recently-observed list below it, the
 * mesh-group label for BSSIDs sharing an SSID, and the per-row trust quick action.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MainActivityAccessPointOverviewTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = RuntimeEnvironment.getApplication();

    @Before
    public void setUp() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @After
    public void tearDown() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADB.resetForTesting();
    }

    @Test
    public void currentAccessPointIsShownWithSsidAndBssid() {
        connectTo("HomeMesh", "aa:aa:aa:aa:aa:01");

        MainActivity activity = launch();

        // #479: the row focuses on SSID/BSSID only -- no redundant trust/mesh status text.
        List<String> currentTexts = allText(activity.findViewById(R.id.wifi_aps_current_row));
        assertTrue(joined(currentTexts), currentTexts.stream().anyMatch(t -> t.contains("HomeMesh")));
        assertTrue(joined(currentTexts), currentTexts.stream().anyMatch(t -> t.contains("AA:AA:AA:AA:AA:01")));
        assertFalse(joined(currentTexts), currentTexts.stream()
                .anyMatch(t -> t.contains(context.getString(R.string.wifi_aps_untrusted_status))));
    }

    @Test
    public void unknownCurrentIdentityShowsTheUnavailableMessage() {
        // Default Robolectric WifiInfo has an unassociated/redacted BSSID -- isKnown() is false.
        MainActivity activity = launch();

        List<String> currentTexts = allText(activity.findViewById(R.id.wifi_aps_current_row));
        assertTrue(joined(currentTexts), currentTexts.stream()
                .anyMatch(t -> t.contains(context.getString(R.string.wifi_aps_current_unknown))));
    }

    @Test
    public void recentlyObservedAccessPointsAreListedBelowTheCurrentOne() {
        KeepADBBssidHistory.recordObservation(context, "OfficeMesh", "bb:bb:bb:bb:bb:01");
        connectTo("HomeMesh", "aa:aa:aa:aa:aa:01");

        MainActivity activity = launch();

        LinearLayout list = activity.findViewById(R.id.wifi_aps_list);
        assertEquals(1, list.getChildCount());
        List<String> recentTexts = allText(list);
        assertTrue(joined(recentTexts), recentTexts.stream().anyMatch(t -> t.contains("OfficeMesh")));
        View empty = activity.findViewById(R.id.wifi_aps_empty);
        assertEquals(View.GONE, empty.getVisibility());
    }

    @Test
    public void emptyStateIsShownWhenNoOtherAccessPointIsKnown() {
        connectTo("HomeMesh", "aa:aa:aa:aa:aa:01");

        MainActivity activity = launch();

        LinearLayout list = activity.findViewById(R.id.wifi_aps_list);
        assertEquals(0, list.getChildCount());
        TextView empty = activity.findViewById(R.id.wifi_aps_empty);
        assertEquals(View.VISIBLE, empty.getVisibility());
        // #468 acceptance criterion 3: an empty list needs no extra interaction to understand.
        assertEquals(View.GONE, activity.findViewById(R.id.wifi_aps_toggle).getVisibility());
    }

    @Test
    public void shortListShowsAllItemsWithoutAShowMoreToggle() {
        // Three others is well under the WIFI_APS_COLLAPSED_OTHERS threshold (5).
        for (int i = 0; i < 3; i++) {
            KeepADBBssidHistory.recordObservation(context, "Neighbor" + i, otherBssid(i));
        }
        connectTo("HomeMesh", "aa:aa:aa:aa:aa:01");

        MainActivity activity = launch();

        LinearLayout list = activity.findViewById(R.id.wifi_aps_list);
        assertEquals(3, list.getChildCount());
        // #468 acceptance criterion 3: a short list is already fully visible, no toggle needed.
        assertEquals(View.GONE, activity.findViewById(R.id.wifi_aps_toggle).getVisibility());
    }

    @Test
    public void longListIsCollapsedByDefaultAndShowsAShowMoreToggleWithTheHiddenCount() {
        for (int i = 0; i < 7; i++) {
            KeepADBBssidHistory.recordObservation(context, "Neighbor" + i, otherBssid(i));
        }
        connectTo("HomeMesh", "aa:aa:aa:aa:aa:01");

        MainActivity activity = launch();

        LinearLayout list = activity.findViewById(R.id.wifi_aps_list);
        // #468 acceptance criterion 1: compact by default -- only the first 5 of 7 are shown.
        assertEquals(5, list.getChildCount());
        TextView toggle = activity.findViewById(R.id.wifi_aps_toggle);
        assertEquals(View.VISIBLE, toggle.getVisibility());
        assertEquals(context.getString(R.string.wifi_aps_show_more_button, 2), toggle.getText().toString());
    }

    @Test
    public void tappingShowMoreRevealsTheFullListAndTappingAgainCollapsesIt() {
        for (int i = 0; i < 7; i++) {
            KeepADBBssidHistory.recordObservation(context, "Neighbor" + i, otherBssid(i));
        }
        connectTo("HomeMesh", "aa:aa:aa:aa:aa:01");
        MainActivity activity = launch();
        LinearLayout list = activity.findViewById(R.id.wifi_aps_list);
        TextView toggle = activity.findViewById(R.id.wifi_aps_toggle);

        toggle.performClick();

        // #468 acceptance criterion 1: fully expandable to see everything.
        assertEquals(7, list.getChildCount());
        assertEquals(context.getString(R.string.wifi_aps_show_less_button), toggle.getText().toString());

        toggle.performClick();

        assertEquals(5, list.getChildCount());
        assertEquals(context.getString(R.string.wifi_aps_show_more_button, 2), toggle.getText().toString());
    }

    @Test
    public void currentConnectionStaysVisibleAndUnchangedWhileExpandingAndCollapsingTheOthers() {
        for (int i = 0; i < 7; i++) {
            KeepADBBssidHistory.recordObservation(context, "Neighbor" + i, otherBssid(i));
        }
        connectTo("HomeMesh", "aa:aa:aa:aa:aa:01");
        MainActivity activity = launch();
        View currentRow = activity.findViewById(R.id.wifi_aps_current_row);
        List<String> beforeExpand = allText(currentRow);

        activity.findViewById(R.id.wifi_aps_toggle).performClick();
        // #468 acceptance criterion 2: the current connection's own row -- and its trust status
        // -- never moves and is never hidden by the collapsible "others" list toggling.
        assertEquals(beforeExpand, allText(currentRow));

        activity.findViewById(R.id.wifi_aps_toggle).performClick();
        assertEquals(beforeExpand, allText(currentRow));
    }

    @Test
    public void twoAccessPointsSharingAnSsidDoNotShowTheMeshLabel() {
        // #479: the mesh-count label ("AP X of Y") is redundant status text and was removed from
        // the individual AP rows, even though KeepADBAccessPointOverview still computes it.
        KeepADBBssidHistory.recordObservation(context, "HomeMesh", "aa:aa:aa:aa:aa:02");
        connectTo("HomeMesh", "aa:aa:aa:aa:aa:01");

        MainActivity activity = launch();

        List<String> currentTexts = allText(activity.findViewById(R.id.wifi_aps_current_row));
        String meshLabel = context.getString(R.string.wifi_aps_mesh_label, 1, 2);
        String meshLabelOther = context.getString(R.string.wifi_aps_mesh_label, 2, 2);
        boolean currentShowsMesh = currentTexts.stream()
                .anyMatch(t -> t.contains(meshLabel) || t.contains(meshLabelOther));
        assertFalse(joined(currentTexts), currentShowsMesh);
    }

    @Test
    public void trustButtonAddsTheAccessPointToTheAllowlistAndFlipsToUntrust() {
        connectTo("HomeMesh", "aa:aa:aa:aa:aa:01");
        MainActivity activity = launch();

        Button trustButton = findButton(activity.findViewById(R.id.wifi_aps_current_row));
        assertEquals(context.getString(R.string.wifi_aps_trust_button), trustButton.getText().toString());

        trustButton.performClick();

        assertEquals(1, KeepADBTrustedNetwork.getEntries(context).size());
        assertEquals("AA:AA:AA:AA:AA:01", KeepADBTrustedNetwork.getEntries(context).get(0).bssid);
        Button trustButtonAfter = findButton(activity.findViewById(R.id.wifi_aps_current_row));
        assertEquals(context.getString(R.string.wifi_aps_untrust_button), trustButtonAfter.getText().toString());
    }

    @Test
    public void untrustButtonRemovesTheAccessPointFromTheAllowlist() {
        connectTo("HomeMesh", "aa:aa:aa:aa:aa:01");
        KeepADBTrustedNetwork.addBssid(context, "aa:aa:aa:aa:aa:01", "HomeMesh");
        MainActivity activity = launch();

        Button trustButton = findButton(activity.findViewById(R.id.wifi_aps_current_row));
        assertEquals(context.getString(R.string.wifi_aps_untrust_button), trustButton.getText().toString());

        trustButton.performClick();

        assertTrue(KeepADBTrustedNetwork.getEntries(context).isEmpty());
    }

    /**
     * #470: adding a trusted network from here must immediately attempt the connection it was
     * blocking, without the user first having to open the pushdown notification.
     */
    @Test
    public void trustButtonImmediatelyAttemptsTheConnectionItWasBlocking() {
        connectTo("HomeMesh", "aa:aa:aa:aa:aa:01");
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
        // MODE_ALL_WIFI makes isAutoEnableStillPermitted's trust check pass under Robolectric,
        // whose WifiManager identity the allowlist branch cannot be driven through -- matching
        // the same setup KeepADBNetworkTrustPromptTest uses for the notification's own action.
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));
        MainActivity activity = launch();

        Button trustButton = findButton(activity.findViewById(R.id.wifi_aps_current_row));
        trustButton.performClick();

        assertTrue("Wireless Debugging must be turned on immediately after trusting the "
                        + "blocking access point from the main screen",
                KeepADB.isEnabled(context));
    }

    /** #480: "Trust" (add) and "Untrust" (remove) must use visually distinct styles instead of a
     * single shared button background. Asserted here via the text color each style applies,
     * since comparing background Drawables directly is brittle under Robolectric. */
    @Test
    public void trustAndUntrustButtonsUseDistinctColorStyles() {
        connectTo("HomeMesh", "aa:aa:aa:aa:aa:01");
        MainActivity activity = launch();

        Button trustButton = findButton(activity.findViewById(R.id.wifi_aps_current_row));
        int trustColor = trustButton.getCurrentTextColor();
        assertEquals(context.getColor(R.color.title_yellow), trustColor);

        trustButton.performClick();

        Button untrustButton = findButton(activity.findViewById(R.id.wifi_aps_current_row));
        int untrustColor = untrustButton.getCurrentTextColor();
        assertEquals(context.getColor(R.color.text_yellow), untrustColor);
        assertNotEquals(trustColor, untrustColor);
    }

    /** #479 acceptance criterion 3: enabling the "trusted only" filter hides untrusted access
     * points from both the current-connection row and the "others" list; trusted ones remain. */
    @Test
    public void trustedOnlyFilterHidesUntrustedAccessPointsFromCurrentAndOthersList() {
        KeepADBBssidHistory.recordObservation(context, "OfficeMesh", "bb:bb:bb:bb:bb:01");
        connectTo("HomeMesh", "aa:aa:aa:aa:aa:01");
        MainActivity activity = launch();
        Switch trustedOnlyToggle = activity.findViewById(R.id.wifi_aps_trusted_only_toggle);

        trustedOnlyToggle.setChecked(true);

        List<String> currentTexts = allText(activity.findViewById(R.id.wifi_aps_current_row));
        assertFalse(joined(currentTexts), currentTexts.stream().anyMatch(t -> t.contains("HomeMesh")));
        LinearLayout list = activity.findViewById(R.id.wifi_aps_list);
        assertEquals(0, list.getChildCount());

        KeepADBTrustedNetwork.addBssid(context, "aa:aa:aa:aa:aa:01", "HomeMesh");
        // The trust state changed outside the toggle's own listener -- flip it off and back on
        // to force a re-render against the now-updated trust state.
        trustedOnlyToggle.setChecked(false);
        trustedOnlyToggle.setChecked(true);

        currentTexts = allText(activity.findViewById(R.id.wifi_aps_current_row));
        assertTrue(joined(currentTexts), currentTexts.stream().anyMatch(t -> t.contains("HomeMesh")));
    }

    // --- helpers ----------------------------------------------------------------------------

    private MainActivity launch() {
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        return controller.get();
    }

    private void connectTo(String ssid, String bssid) {
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = ShadowWifiInfo.newInstance();
        shadowOf(info).setSSID(ssid);
        shadowOf(info).setBSSID(bssid);
        shadowOf(wifiManager).setConnectionInfo(info);
    }

    private static List<String> allText(View root) {
        List<String> texts = new ArrayList<>();
        collectText(root, texts);
        return texts;
    }

    private static void collectText(View view, List<String> out) {
        if (view instanceof TextView) {
            out.add(((TextView) view).getText().toString());
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectText(group.getChildAt(i), out);
            }
        }
    }

    private static Button findButton(View root) {
        if (root instanceof Button) return (Button) root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                Button found = findButton(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String joined(List<String> texts) {
        return String.join(" | ", texts);
    }

    /** Distinct BSSIDs for synthetic "other" access points, disjoint from the "aa:aa:..."
     * range {@link #connectTo} uses for the current connection in these tests. */
    private static String otherBssid(int index) {
        return String.format(java.util.Locale.US, "bb:bb:bb:bb:bb:%02x", index);
    }
}
