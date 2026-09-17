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
 * #484/#485: the trust-restriction toggle, the "manage whitelist" dialog and the "recently
 * blocked" dialog moved here from {@code SettingsActivity} onto the home screen's security card.
 * These tests are the moved/adapted counterparts of the ones that used to live in
 * {@code SettingsActivityTest} for those same entry points -- only the hosting activity and view
 * ids changed, not the covered behavior.
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

    @Test
    public void manageWhitelistDialogIsWrappedInScrollViewAndDeleteButtonHasContextualAccessibility() {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();

        KeepADBTrustedNetwork.addBssid(activity, "aa:bb:cc:dd:ee:ff", "MyOfficeNetwork");
        ShadowLooper.idleMainLooper();
        activity.findViewById(R.id.wifi_aps_manage_whitelist_button).performClick();
        ShadowLooper.idleMainLooper();

        AlertDialog dialog = activity.getActiveManageNetworksDialog();
        assertNotNull("Trusted networks dialog should be showing", dialog);
        assertTrue(dialog.isShowing());
        View customPanel = dialog.findViewById(android.R.id.custom);
        ScrollView scroll = findViewByType(customPanel, ScrollView.class);
        assertNotNull("Trusted networks dialog rows must be wrapped in a ScrollView", scroll);

        List<Button> buttons = findViewsByType(scroll, Button.class);
        assertTrue("Delete button should be present in trusted networks list", !buttons.isEmpty());
        assertEquals("Trusted network delete button must set contextual content description with network label",
                activity.getString(R.string.settings_trusted_network_delete_accessibility, "MyOfficeNetwork"),
                buttons.get(0).getContentDescription());

        dialog.dismiss();
        ShadowLooper.idleMainLooper();
    }

    /** #446: the recently-blocked list is the transparency half of the issue. */
    @Test
    public void blockedNetworkDialogListsBlockedAccessPointsAndCanAllowOne() {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
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
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();

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

        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
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
     * #484: clicking the toggle on while ACCESS_FINE_LOCATION isn't granted must revert the
     * switch and offer the permission dialog instead of silently switching modes -- mirrors the
     * equivalent behavior this test replaces from {@code SettingsActivityTest}.
     */
    @Test
    public void trustRestrictionToggleRequestsLocationPermissionBeforeEnablingAllowlistMode() {
        shadowOf((android.app.Application) RuntimeEnvironment.getApplication())
                .denyPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION);
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();

        // #260: allowlist mode is the default, so the toggle already starts checked. Force it
        // unchecked first so performClick() -- which flips CompoundButton's checked state before
        // invoking the listener -- actually simulates the user turning it *on*.
        android.widget.Switch toggle = activity.findViewById(R.id.wifi_aps_trust_restriction_toggle);
        toggle.setChecked(false);
        toggle.performClick();
        ShadowLooper.idleMainLooper();

        AlertDialog dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("Permission rationale dialog should be showing", dialog);
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
