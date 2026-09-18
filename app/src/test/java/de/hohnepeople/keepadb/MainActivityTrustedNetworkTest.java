package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import static org.robolectric.Shadows.shadowOf;

import android.app.AlertDialog;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ScrollView;
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
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowWifiInfo;

/**
 * Tests for the Wi-Fi &amp; Access Points card and its security management dialogs (#484/#485,
 * moved to {@link SettingsActivity} under opt-in for #507): the allowlisted-but-unobserved list,
 * the recently blocked dialog, the mesh-BSSID convenience, and the SSID allowlist.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MainActivityTrustedNetworkTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    @Before
    public void setUp() {
        RuntimeEnvironment.getApplication()
                .getSharedPreferences("keepadb_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
    }

    @After
    public void tearDown() {
        RuntimeEnvironment.getApplication()
                .getSharedPreferences("keepadb_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
        KeepADB.resetForTesting();
    }

    private ActivityController<SettingsActivity> launchSettingsWithWifiAps() {
        KeepADBPreferences.setWifiApsFeatureEnabled(RuntimeEnvironment.getApplication(), true);
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        controller.get().findViewById(R.id.settings_wifi_aps_header).performClick();
        ShadowLooper.idleMainLooper();
        return controller;
    }

    /**
     * #492: the separate "manage whitelist" dialog was removed as a second management surface. It
     * is only a deduplication -- rather than a loss of reach -- because the card itself now lists
     * an allowlisted access point that no other source supplies, i.e. one that was never observed
     * (no history entry) and is not the current connection. That row carries the remove action, so
     * an entry can still be revoked without the dialog.
     */
    @Test
    public void accessPointCardListsAllowlistedAccessPointsThatWereNeverObserved() {
        ActivityController<SettingsActivity> controller = launchSettingsWithWifiAps();
        SettingsActivity activity = controller.get();

        KeepADBTrustedNetwork.addBssid(activity, "aa:bb:cc:dd:ee:ff", "MyOfficeNetwork");
        controller.pause().resume();
        ShadowLooper.idleMainLooper();

        List<TextView> texts = findViewsByType(activity.findViewById(R.id.wifi_aps_list), TextView.class);
        List<String> rendered = new ArrayList<>();
        for (TextView view : texts) rendered.add(view.getText().toString());
        assertTrue("The card must list an allowlisted-but-never-observed access point: " + rendered,
                rendered.contains("MyOfficeNetwork"));
        assertTrue("...and show its BSSID: " + rendered,
                rendered.contains("AA:BB:CC:DD:EE:FF"));

        // The row's own action revokes it, so no second management surface is needed.
        List<Button> buttons = findViewsByType(activity.findViewById(R.id.wifi_aps_list), Button.class);
        assertEquals(1, buttons.size());
        assertEquals(activity.getString(R.string.wifi_aps_untrust_accessibility, "MyOfficeNetwork"),
                buttons.get(0).getContentDescription());
        buttons.get(0).performClick();
        ShadowLooper.idleMainLooper();
        assertTrue("The row action must remove the allowlist entry",
                KeepADBTrustedNetwork.getEntries(activity).isEmpty());
    }

    /** #446: the recently-blocked list is the transparency half of the issue. */
    @Test
    public void blockedNetworkDialogListsBlockedAccessPointsAndCanAllowOne() {
        ActivityController<SettingsActivity> controller = launchSettingsWithWifiAps();
        SettingsActivity activity = controller.get();
        KeepADBBlockedNetworkHistory.record(activity,
                new KeepADBNetworkIdentity("Cafe-WLAN", "aa:bb:cc:dd:ee:01"), 1_000L);
        KeepADBBlockedNetworkHistory.record(activity,
                new KeepADBNetworkIdentity("Hotel-WLAN", "aa:bb:cc:dd:ee:02"), 2_000L);
        controller.pause().resume();
        ShadowLooper.idleMainLooper();

        Button entryPoint = activity.findViewById(R.id.wifi_aps_recently_blocked_button);
        assertTrue("The entry point must show the count: " + entryPoint.getText(),
                entryPoint.getText().toString().contains("2"));

        entryPoint.performClick();
        ShadowLooper.idleMainLooper();
        AlertDialog dialog = activity.getActiveBlockedNetworksDialog();
        assertNotNull("Blocked networks dialog should be showing", dialog);
        List<TextView> texts = findViewsByType(dialog.findViewById(android.R.id.custom), TextView.class);
        List<String> rendered = new ArrayList<>();
        for (TextView view : texts) rendered.add(view.getText().toString());
        assertTrue(rendered.toString(), rendered.contains("Cafe-WLAN"));
        assertTrue(rendered.toString(), rendered.contains("Hotel-WLAN"));
        // Newest first: the access point the user just failed on is at the top.
        assertTrue(rendered.indexOf("Hotel-WLAN") < rendered.indexOf("Cafe-WLAN"));

        // Nothing is trusted merely by looking at the list.
        assertTrue(KeepADBTrustedNetwork.getEntries(activity).isEmpty());

        List<Button> allowButtons =
                findViewsByType(dialog.findViewById(android.R.id.custom), Button.class);
        assertEquals(2, allowButtons.size());
        allowButtons.get(0).performClick();
        ShadowLooper.idleMainLooper();

        List<KeepADBTrustedNetwork.Entry> trusted = KeepADBTrustedNetwork.getEntries(activity);
        assertEquals(1, trusted.size());
        assertEquals("aa:bb:cc:dd:ee:02", trusted.get(0).bssid);
        assertEquals("Hotel-WLAN", trusted.get(0).label);
        // The allowed one leaves the blocked log; the other stays for a later decision.
        List<KeepADBBlockedNetworkHistory.Entry> remaining =
                KeepADBBlockedNetworkHistory.getEntries(activity);
        assertEquals(1, remaining.size());
        assertEquals("aa:bb:cc:dd:ee:01", remaining.get(0).bssid);
    }

    @Test
    public void blockedNetworkDialogExplainsItselfWhenNothingWasBlocked() {
        ActivityController<SettingsActivity> controller = launchSettingsWithWifiAps();
        SettingsActivity activity = controller.get();

        activity.findViewById(R.id.wifi_aps_recently_blocked_button).performClick();
        ShadowLooper.idleMainLooper();

        assertNull("An empty log must not open the row dialog",
                activity.getActiveBlockedNetworksDialog());
        assertTrue(KeepADBTrustedNetwork.getEntries(activity).isEmpty());
    }

    /**
     * #475: the "Allow" button in the blocked-access-points dialog must go through the same
     * trust-and-connect entry point this activity's per-access-point trust button uses (#470),
     * not just a bare {@code addBssid} that leaves the user waiting for Keep-Alive's next pass.
     * Also verifies the #474 fix keeps applying: allowing clears only this BSSID's own pending
     * prompt marker.
     */
    @Test
    public void blockedNetworkDialogAllowButtonImmediatelyAttemptsTheConnectionItWasBlocking() {
        String bssid = "aa:bb:cc:dd:ee:01";
        grantAutoEnableForTesting(bssid, "Cafe-WLAN");

        ActivityController<SettingsActivity> controller = launchSettingsWithWifiAps();
        SettingsActivity activity = controller.get();
        // Raises this BSSID's own pending-prompt marker, exactly like the real block path does.
        assertTrue(KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(activity));
        controller.pause().resume();
        ShadowLooper.idleMainLooper();

        Button entryPoint = activity.findViewById(R.id.wifi_aps_recently_blocked_button);
        entryPoint.performClick();
        ShadowLooper.idleMainLooper();
        AlertDialog dialog = activity.getActiveBlockedNetworksDialog();
        assertNotNull("Blocked networks dialog should be showing", dialog);
        List<Button> allowButtons =
                findViewsByType(dialog.findViewById(android.R.id.custom), Button.class);
        assertEquals(1, allowButtons.size());

        allowButtons.get(0).performClick();
        ShadowLooper.idleMainLooper();

        assertTrue("Wireless Debugging must be turned on immediately after allowing the "
                        + "blocked access point from the recently-blocked list",
                KeepADB.isEnabled(activity));
        assertTrue("Allowing the access point must clear its own pending prompt marker",
                KeepADBNetworkTrustPrompt.shouldPrompt(activity, bssid, System.currentTimeMillis()));
    }

    /**
     * #507: the home screen no longer hosts the Wi-Fi & Access Points panel or any of its controls.
     */
    @Test
    public void homeScreenNoLongerHostsTheWifiApsCard() {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();

        assertNull(activity.findViewById(R.id.settings_wifi_aps_panel));
        assertNull(activity.findViewById(R.id.wifi_aps_recently_blocked_button));
        assertNull(activity.findViewById(R.id.wifi_aps_list));
        assertNull(activity.findViewById(R.id.wifi_ssids_section));
    }

    /**
     * #492: the mesh-BSSID convenience moved here from SettingsActivity's removed
     * add-current-network button. Accepting it must still trust each additional BSSID through the
     * trust-and-connect entry point -- proven by the mesh BSSID's own pending prompt marker
     * (raised while the device was briefly connected to it) getting cleared, which a bare {@code
     * addBssid} call would not do. Moved test, unchanged behavior.
     */
    @Test
    public void meshBssidConvenienceImmediatelyAttemptsTheConnectionAndClearsItsPromptState() {
        String currentBssid = "aa:bb:cc:dd:ee:03";
        String meshBssid = "aa:bb:cc:dd:ee:04";
        String ssid = "MeshHome";
        grantAutoEnableForTesting(currentBssid, ssid);

        ActivityController<SettingsActivity> controller = launchSettingsWithWifiAps();
        SettingsActivity activity = controller.get();
        // The device was briefly connected to the mesh AP earlier and got its own pending prompt
        // for it, before roaming to the network under test.
        connectTo(ssid, meshBssid);
        assertTrue(KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(activity));
        KeepADBBssidHistory.recordObservation(activity, ssid, meshBssid);
        connectTo(ssid, currentBssid);
        controller.pause().resume();
        ShadowLooper.idleMainLooper();

        // The current access point's own trust button is the surviving trust entry point.
        List<Button> currentButtons =
                findViewsByType(activity.findViewById(R.id.wifi_aps_current_row), Button.class);
        assertEquals(1, currentButtons.size());
        currentButtons.get(0).performClick();
        ShadowLooper.idleMainLooper();
        AlertDialog meshDialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("The mesh convenience dialog should be showing", meshDialog);

        meshDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        assertEquals(2, KeepADBTrustedNetwork.getEntries(activity).size());
        assertTrue("Wireless Debugging must be turned on immediately after trusting the current, "
                        + "blocking access point", KeepADB.isEnabled(activity));
        assertTrue("Accepting the mesh BSSID must clear its own pending prompt marker",
                KeepADBNetworkTrustPrompt.shouldPrompt(activity, meshBssid, System.currentTimeMillis()));
    }

    /**
     * #492: the SSID section is gated behind its own opt-in, so a user who never chose the weaker
     * matching model never sees it -- and the section is not merely hidden but has no add action
     * to reach either.
     */
    @Test
    public void ssidSectionIsHiddenUntilItsOptInIsEnabled() {
        connectTo("MeshHome", "aa:bb:cc:dd:ee:05");
        ActivityController<SettingsActivity> controller = launchSettingsWithWifiAps();
        SettingsActivity activity = controller.get();

        assertEquals("The SSID section must be gone while its opt-in is off",
                View.GONE, activity.findViewById(R.id.wifi_ssids_section).getVisibility());

        KeepADBTrustedNetwork.setSsidMatchingEnabled(activity, true);
        controller.pause().resume();
        ShadowLooper.idleMainLooper();

        assertEquals(View.VISIBLE, activity.findViewById(R.id.wifi_ssids_section).getVisibility());
    }

    /** #492: the current SSID can be allowed and removed again from the card, and nothing else can
     * be added -- the only add action is for the currently connected, readable network. */
    @Test
    public void currentSsidCanBeAllowedAndRemovedFromTheCard() {
        connectTo("MeshHome", "aa:bb:cc:dd:ee:06");
        ActivityController<SettingsActivity> controller = launchSettingsWithWifiAps();
        SettingsActivity activity = controller.get();
        KeepADBTrustedNetwork.setSsidMatchingEnabled(activity, true);
        controller.pause().resume();
        ShadowLooper.idleMainLooper();

        List<Button> addButtons =
                findViewsByType(activity.findViewById(R.id.wifi_ssids_current_row), Button.class);
        assertEquals(1, addButtons.size());
        assertEquals(activity.getString(R.string.wifi_ssids_add_accessibility, "MeshHome"),
                addButtons.get(0).getContentDescription());
        addButtons.get(0).performClick();
        ShadowLooper.idleMainLooper();

        List<KeepADBTrustedNetwork.SsidEntry> listed = KeepADBTrustedNetwork.getSsidEntries(activity);
        assertEquals(1, listed.size());
        assertEquals("MeshHome", listed.get(0).ssid);
        // Already listed: the current row no longer offers a duplicate add.
        assertTrue(findViewsByType(activity.findViewById(R.id.wifi_ssids_current_row), Button.class)
                .isEmpty());

        List<Button> removeButtons =
                findViewsByType(activity.findViewById(R.id.wifi_ssids_list), Button.class);
        assertEquals(1, removeButtons.size());
        assertEquals(activity.getString(R.string.wifi_ssids_remove_accessibility, "MeshHome"),
                removeButtons.get(0).getContentDescription());
        removeButtons.get(0).performClick();
        ShadowLooper.idleMainLooper();
        assertTrue(KeepADBTrustedNetwork.getSsidEntries(activity).isEmpty());
    }

    /** #492: an unreadable identity offers no add action at all, so a placeholder can never be
     * stored as an allowed network name. */
    @Test
    public void unreadableIdentityOffersNoSsidAddAction() {
        connectTo(WifiManager.UNKNOWN_SSID, KeepADBNetworkIdentity.REDACTED_BSSID);
        ActivityController<SettingsActivity> controller = launchSettingsWithWifiAps();
        SettingsActivity activity = controller.get();
        KeepADBTrustedNetwork.setSsidMatchingEnabled(activity, true);
        controller.pause().resume();
        ShadowLooper.idleMainLooper();

        assertTrue("A masked identity must not offer an add action",
                findViewsByType(activity.findViewById(R.id.wifi_ssids_current_row), Button.class)
                        .isEmpty());
        assertNull(KeepADBTrustedNetwork.addCurrentSsid(activity));
        assertTrue(KeepADBTrustedNetwork.getSsidEntries(activity).isEmpty());
    }

    /**
     * Shared setup for the immediate-connect test, mirroring {@code
     * SettingsActivityTest#grantAutoEnableForTesting}: grants {@code WRITE_SECURE_SETTINGS},
     * switches to {@code MODE_ALL_WIFI} (Robolectric cannot drive the allowlist branch's
     * BSSID-based trust check), enables Keep-Alive, forces the Wi-Fi-connectivity check to report
     * connected, installs a gateway that starts switched off, and connects to {@code (ssid,
     * bssid)}.
     */
    private void grantAutoEnableForTesting(String bssid, String ssid) {
        android.content.Context context = RuntimeEnvironment.getApplication();
        shadowOf((android.app.Application) context).grantPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS,
                android.Manifest.permission.POST_NOTIFICATIONS);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));
        connectTo(ssid, bssid);
    }

    private void connectTo(String ssid, String bssid) {
        android.content.Context context = RuntimeEnvironment.getApplication();
        WifiManager wifiManager = (WifiManager) context.getSystemService(android.content.Context.WIFI_SERVICE);
        WifiInfo info = ShadowWifiInfo.newInstance();
        shadowOf(info).setSSID(ssid);
        shadowOf(info).setBSSID(bssid);
        shadowOf(wifiManager).setConnectionInfo(info);
    }

    private static <T extends View> T findViewByType(View root, Class<T> type) {
        if (root == null) return null;
        if (type.isInstance(root)) {
            return (T) root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                T found = findViewByType(group.getChildAt(i), type);
                if (found != null) return found;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T extends View> List<T> findViewsByType(View root, Class<T> type) {
        List<T> result = new ArrayList<>();
        findViewsByTypeInternal(root, type, result);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static <T extends View> void findViewsByTypeInternal(View root, Class<T> type, List<T> result) {
        if (root == null) return;
        if (type.isInstance(root)) {
            result.add((T) root);
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                findViewsByTypeInternal(group.getChildAt(i), type, result);
            }
        }
    }
}
