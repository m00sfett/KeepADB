package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowNetwork;
import org.robolectric.shadows.ShadowWifiInfo;

/**
 * #697: the lifecycle and state-ownership transitions of the Network card after it moved from
 * {@link SettingsActivity} into {@link KeepADBNetworkCard}: who registers and removes the Wi-Fi
 * callback, which access point the action acts on, what a save before a destroy keeps, what a
 * rotation brings back (and what it must not), and that rendering never writes a preference.
 *
 * <p>Each test names both sides of the invariant it guards, so a change that only satisfies the
 * obvious half stays red: the callback test fails on a missing re-registration and on a stacked
 * one, the access point tests on a stale snapshot and on a re-read connection, the restore tests
 * on a lost binding and on one taken from the current connection.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SettingsNetworkCardLifecycleTest {

    private static final String BSSID_A = "aa:bb:cc:dd:ee:01";
    private static final String BSSID_B = "aa:bb:cc:dd:ee:02";

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = RuntimeEnvironment.getApplication();

    @Before
    public void setUp() {
        prefs().edit().clear().commit();
        activityPrefs().edit().clear().commit();
        shadowOf((Application) context).denyPermissions(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        shadowOf(context.getSystemService(LocationManager.class)).setLocationEnabled(true);
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        ShadowDialog.reset();
        ShadowLog.clear();
    }

    @After
    public void tearDown() {
        prefs().edit().clear().commit();
        activityPrefs().edit().clear().commit();
        KeepADB.resetForTesting();
    }

    // --- the Wi-Fi callback -------------------------------------------------------------------

    /**
     * Stop removes exactly the callback the card registered (every other registered callback
     * stays, a second stop is a no-op instead of an unregister attempt), and every restart
     * registers exactly one new callback: never none (reference not cleared on stop) and never a
     * stacked second one.
     */
    @Test
    public void stopRemovesOnlyTheCardsOwnCallbackAndEveryRestartRegistersExactlyOne() {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        ConnectivityManager.NetworkCallback foreign = new ConnectivityManager.NetworkCallback() {};
        manager.registerNetworkCallback(wifiRequest(), foreign);
        Set<ConnectivityManager.NetworkCallback> others = new HashSet<>(callbacks(manager));
        assertTrue(others.contains(foreign));

        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        assertEquals("Exactly one callback of the card", 1, cardCallbacks(manager).size());
        others.addAll(withoutCard(callbacks(manager)));

        for (int round = 1; round <= 3; round++) {
            controller.pause().stop();
            assertEquals("Stop removes the card's callback (round " + round + ")", 0,
                    cardCallbacks(manager).size());
            assertEquals("Stop removes nothing else (round " + round + ")", others,
                    withoutCard(callbacks(manager)));

            // The framework never stops twice; a stray second stop must still do nothing.
            controller.get().onStop();
            assertEquals(others, withoutCard(callbacks(manager)));

            controller.restart().start().resume();
            assertEquals("A restart registers exactly one callback (round " + round + ")", 1,
                    cardCallbacks(manager).size());
            assertEquals(others, withoutCard(callbacks(manager)));
        }

        // A second start without a stop in between must not stack another callback.
        controller.get().onStart();
        assertEquals(1, cardCallbacks(manager).size());

        assertTrue("A stop must never try to unregister a callback that is not registered",
                warnings("Failed to unregister").isEmpty());
        controller.pause().stop().destroy();
        manager.unregisterNetworkCallback(foreign);
    }

    // --- the access point the action acts on ----------------------------------------------------

    /**
     * The action follows the last rendering, in both directions: after a Wi-Fi change re-rendered
     * the card for B it allows B (not the stale A), and when the device then drifts back to A
     * without a re-render it still allows the B that was on screen (not the current A).
     */
    @Test
    public void theActionActsOnTheLastRenderedAccessPointNeitherStaleNorRoamedTo() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        connectTo("Cafe-WLAN", BSSID_A);
        SettingsActivity activity = open();
        assertEquals("Cafe-WLAN · AA:BB:CC:DD:EE:01", text(activity, R.id.network_connection_line));

        connectTo("Cafe-WLAN", BSSID_B);
        deliverWifiChange();
        assertEquals("The Wi-Fi callback re-rendered the card for B",
                "Cafe-WLAN · AA:BB:CC:DD:EE:02", text(activity, R.id.network_connection_line));

        connectTo("Cafe-WLAN", BSSID_A);
        activity.findViewById(R.id.network_status_action).performClick();
        ShadowLooper.idleMainLooper();

        List<KeepADBTrustedNetwork.Entry> entries = KeepADBTrustedNetwork.getEntries(context);
        assertEquals("Exactly the access point on screen is allowed", 1, entries.size());
        assertEquals(BSSID_B, entries.get(0).bssid);
    }

    // --- destroy ------------------------------------------------------------------------------------

    @Test
    public void destroyDismissesTheTrustConfirmationDialog() {
        KeepADBBlockedNetworkHistory.record(context,
                new KeepADBNetworkIdentity("\"Cafe-WLAN\"", BSSID_A), 1L);
        ActivityController<SettingsActivity> controller = Robolectric.buildActivity(
                SettingsActivity.class,
                KeepADBNetworkTrustPrompt.confirmInAppIntent(context, BSSID_A)).setup();
        AlertDialog dialog = controller.get().getActiveTrustConfirmationDialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());

        controller.pause().stop().destroy();

        assertFalse("Destroy closes the confirmation (no window leak)", dialog.isShowing());
        assertNull(controller.get().getActiveTrustConfirmationDialog());
        assertTrue("Closing it by destroy decides nothing",
                KeepADBTrustedNetwork.getEntries(context).isEmpty());
    }

    @Test
    public void destroyDismissesTheBackgroundLocationRationale() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        controller.get().findViewById(R.id.settings_background_location_button).performClick();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());

        controller.pause().stop().destroy();

        assertFalse("Destroy closes the rationale (no window leak)", dialog.isShowing());
    }

    // --- save before destroy and the restore ---------------------------------------------------

    /**
     * The binding of the saved confirmation is written before destroy dismisses the dialog, and
     * the restored one stays bound to that recorded access point: a roam to B (which the history
     * also knows) in between changes neither what it names nor what it trusts.
     */
    @Test
    public void aSaveBeforeDestroyKeepsTheBindingAndTheRestoreIgnoresTheRoam() {
        KeepADBBlockedNetworkHistory.record(context,
                new KeepADBNetworkIdentity("\"Cafe-WLAN\"", BSSID_A), 1L);
        ActivityController<SettingsActivity> controller = Robolectric.buildActivity(
                SettingsActivity.class,
                KeepADBNetworkTrustPrompt.confirmInAppIntent(context, BSSID_A)).setup();
        Bundle state = new Bundle();
        controller.saveInstanceState(state);
        assertEquals(BSSID_A, state.getString(KeepADBNetworkCard.STATE_TRUST_CONFIRMATION_BSSID));
        controller.pause().stop().destroy();
        ShadowLooper.idleMainLooper();

        connectTo("Cafe-WLAN", BSSID_B);
        KeepADBBlockedNetworkHistory.record(context,
                new KeepADBNetworkIdentity("\"Cafe-WLAN\"", BSSID_B), 2L);
        ActivityController<SettingsActivity> restored =
                Robolectric.buildActivity(SettingsActivity.class).setup(state);

        AlertDialog dialog = restored.get().getActiveTrustConfirmationDialog();
        assertNotNull("The confirmation is back", dialog);
        String message = String.valueOf(((TextView) dialog.findViewById(android.R.id.message)).getText());
        assertTrue(message, message.contains(BSSID_A.toUpperCase(java.util.Locale.ROOT)));
        assertFalse("The roam must not rename it: " + message,
                message.contains(BSSID_B.toUpperCase(java.util.Locale.ROOT)));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        List<KeepADBTrustedNetwork.Entry> entries = KeepADBTrustedNetwork.getEntries(context);
        assertEquals(1, entries.size());
        assertEquals(BSSID_A, entries.get(0).bssid);
        restored.pause().stop().destroy();
    }

    /**
     * The mesh question is derived from live data and offered only right after an allow: a
     * rotation closes it and brings nothing back, and the access points it offered stay unallowed.
     */
    @Test
    public void theMeshQuestionIsNeitherSavedNorRestoredAndAllowsNothingImplicitly() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        KeepADBBssidHistory.recordObservation(context, "HomeMesh", BSSID_A);
        connectTo("HomeMesh", BSSID_B);
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        controller.get().findViewById(R.id.settings_network_beta_header).performClick();
        controller.get().findViewById(R.id.network_status_action).performClick();
        ShadowLooper.idleMainLooper();
        AlertDialog mesh = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(mesh);
        assertTrue("The mesh question is on screen", mesh.isShowing());

        Bundle state = new Bundle();
        controller.saveInstanceState(state);
        for (String key : state.keySet()) {
            assertFalse("No mesh state may be saved: " + key, key.toLowerCase().contains("mesh"));
        }
        controller.pause().stop().destroy();
        ShadowLooper.idleMainLooper();
        assertFalse("Destroy closes the question", mesh.isShowing());
        ActivityController<SettingsActivity> restored =
                Robolectric.buildActivity(SettingsActivity.class).setup(state);
        ShadowLooper.idleMainLooper();

        for (android.app.Dialog shown : ShadowDialog.getShownDialogs()) {
            assertFalse("Nothing is restored after the rotation", shown.isShowing());
        }
        List<KeepADBTrustedNetwork.Entry> entries = KeepADBTrustedNetwork.getEntries(context);
        assertEquals("Only the access point that was allowed explicitly", 1, entries.size());
        assertEquals(BSSID_B, entries.get(0).bssid);
        restored.pause().stop().destroy();
    }

    /**
     * A restored background rationale only shows again: it requests nothing, starts nothing, grants
     * nothing and does not touch the mode; and one that was dismissed before the save is not
     * brought back.
     */
    @Test
    public void aRestoredBackgroundRationaleGrantsAndRequestsNothingAndADismissedOneStaysGone() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        controller.get().findViewById(R.id.settings_background_location_button).performClick();
        assertNotNull(ShadowAlertDialog.getLatestAlertDialog());

        ActivityController<SettingsActivity> restored = rotate(controller);
        SettingsActivity activity = restored.get();

        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        assertTrue("The rationale is back", dialog.isShowing());
        assertEquals(context.getString(R.string.background_location_panel_title),
                shadowOf(dialog).getTitle().toString());
        assertNull("Restoring requests nothing", shadowOf(activity).getLastRequestedPermission());
        assertNull("Restoring opens no system page", shadowOf(activity).getNextStartedActivity());
        assertFalse("Restoring grants nothing", KeepADBBackgroundLocation.isGranted(context));
        assertFalse(KeepADBTrustedNetwork.isAllowlistMode(context));

        dialog.dismiss();
        ShadowLooper.idleMainLooper();
        ShadowDialog.reset();
        ActivityController<SettingsActivity> again = rotate(restored);
        assertTrue("A dismissed rationale is not restored", ShadowDialog.getShownDialogs().isEmpty());
        again.pause().stop().destroy();
    }

    // --- rendering writes nothing --------------------------------------------------------------------

    /**
     * Rendering is programmatic: opening, resuming, a Wi-Fi change, a refresh and a rotation (also
     * with the two rationales and the confirmation restored) write no preference at all -- not the
     * trust settings, not the observation or any other key, and not the Activity's own file --
     * whatever the mode, the name setting, the grants and the connection look like.
     */
    @Test
    public void renderingAndRestoringNeverWriteAPreference() {
        int combinations = 0;
        for (boolean allowlist : new boolean[] {false, true}) {
            for (boolean names : new boolean[] {false, true}) {
                for (boolean fine : new boolean[] {false, true}) {
                    for (boolean background : new boolean[] {false, true}) {
                        for (int connection = 0; connection < 3; connection++) {
                            prefs().edit().clear().commit();
                            activityPrefs().edit().clear().commit();
                            ShadowDialog.reset();
                            setPermission(Manifest.permission.ACCESS_FINE_LOCATION, fine);
                            setPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION, background);
                            KeepADBTrustedNetwork.setMode(context, allowlist
                                    ? KeepADBTrustedNetwork.MODE_ALLOWLIST
                                    : KeepADBTrustedNetwork.MODE_ALL_WIFI);
                            KeepADBTrustedNetwork.setSsidMatchingEnabled(context, names);
                            KeepADBTrustedNetwork.addSsid(context, "Mesh");
                            KeepADBTrustedNetwork.addBssid(context, BSSID_B, "Home");
                            KeepADBBlockedNetworkHistory.record(context,
                                    new KeepADBNetworkIdentity("\"Cafe-WLAN\"", BSSID_A), 1L);
                            KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
                            if (connection == 0) {
                                connectTo("Mesh", BSSID_A);
                            } else if (connection == 1) {
                                connectTo(WifiManager.UNKNOWN_SSID, KeepADBNetworkIdentity.REDACTED_BSSID);
                            } else {
                                KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> false);
                            }
                            Map<String, ?> before = everyPreference();
                            String label = "allowlist=" + allowlist + " names=" + names + " fine="
                                    + fine + " background=" + background + " connection=" + connection;

                            ActivityController<SettingsActivity> controller =
                                    Robolectric.buildActivity(SettingsActivity.class).setup();
                            SettingsActivity activity = controller.get();
                            activity.findViewById(R.id.settings_network_beta_header).performClick();
                            activity.findViewById(R.id.network_ssid_header).performClick();
                            deliverWifiChange();
                            controller.pause().resume();
                            activity.refresh();
                            assertEquals(label, before, everyPreference());

                            controller = rotate(controller);
                            assertEquals(label + " (after a rotation)", before, everyPreference());
                            controller.pause().stop().destroy();
                            combinations++;
                        }
                    }
                }
            }
        }
        assertEquals(48, combinations);
    }

    @Test
    public void restoringTheDialogsWritesNoPreferenceEither() {
        // The two rationales: allowlist permission (FINE denied) and background location.
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        controller.get().findViewById(R.id.network_mode_allowlist).performClick();
        Map<String, ?> before = everyPreference();
        controller = rotate(controller);
        assertNotNull(controller.get().getActiveAllowlistPermissionDialog());
        assertEquals(before, everyPreference());
        controller.get().getActiveAllowlistPermissionDialog().dismiss();
        controller.get().findViewById(R.id.settings_background_location_button).performClick();
        before = everyPreference();
        controller = rotate(controller);
        assertEquals(before, everyPreference());
        controller.pause().stop().destroy();

        // The confirmation, restored from the recorded access point.
        KeepADBBlockedNetworkHistory.record(context,
                new KeepADBNetworkIdentity("\"Cafe-WLAN\"", BSSID_A), 1L);
        controller = Robolectric.buildActivity(SettingsActivity.class,
                KeepADBNetworkTrustPrompt.confirmInAppIntent(context, BSSID_A)).setup();
        before = everyPreference();
        controller = rotate(controller);
        assertNotNull(controller.get().getActiveTrustConfirmationDialog());
        assertEquals(before, everyPreference());
        controller.pause().stop().destroy();
    }

    /**
     * Wi-Fi-name matching stays off on a fresh install and nothing the card shows turns it on, and
     * the card (and its Wi-Fi-name section) opens collapsed again after a rotation -- the expansion
     * lives only in the view tree.
     */
    @Test
    public void nameMatchingStaysOffAndTheCardReopensCollapsedAfterARotation() {
        assertFalse(KeepADBTrustedNetwork.isSsidMatchingEnabled(context));
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity before = controller.get();
        before.findViewById(R.id.settings_network_beta_header).performClick();
        before.findViewById(R.id.network_ssid_header).performClick();
        assertEquals(View.VISIBLE, before.findViewById(R.id.settings_network_beta_body).getVisibility());
        assertEquals(View.VISIBLE, before.findViewById(R.id.network_ssid_body).getVisibility());

        ActivityController<SettingsActivity> restored = rotate(controller);
        SettingsActivity after = restored.get();

        // Own visibility, not isShown(): a collapsed card would hide its section anyway.
        assertEquals("The card is collapsed again", View.GONE,
                after.findViewById(R.id.settings_network_beta_body).getVisibility());
        assertEquals("The Wi-Fi-name section is collapsed again", View.GONE,
                after.findViewById(R.id.network_ssid_body).getVisibility());
        assertFalse(KeepADBTrustedNetwork.isSsidMatchingEnabled(context));
        assertFalse(((android.widget.Switch) after.findViewById(R.id.settings_trusted_ssid_toggle))
                .isChecked());
        restored.pause().stop().destroy();
    }

    // --- what stays where it was ---------------------------------------------------------------------

    /**
     * The grant button remembers that it asked in the Activity's own preferences file, as before the
     * extraction: the key and the file are part of what existing installs have stored.
     */
    @Test
    public void theLocationRequestFlagStaysInTheActivitysOwnPreferences() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        SettingsActivity activity = open();
        assertFalse(activityPrefs().getBoolean("location_permission_requested", false));

        activity.findViewById(R.id.network_status_action).performClick();

        assertNotNull(shadowOf(activity).getLastRequestedPermission());
        assertTrue(activityPrefs().getBoolean("location_permission_requested", false));
        assertEquals(activityPrefs().getAll(), activity.getPreferences(Context.MODE_PRIVATE).getAll());
        assertFalse("It is not a key of the shared settings file",
                prefs().contains("location_permission_requested"));
    }

    @Test
    public void bundleKeysAndRequestCodesAreUnchanged() {
        assertEquals("settings_trust_confirmation_bssid",
                KeepADBNetworkCard.STATE_TRUST_CONFIRMATION_BSSID);
        assertEquals("settings_background_location_showing",
                KeepADBNetworkCard.STATE_BACKGROUND_LOCATION_SHOWING);
        assertEquals("settings_allowlist_permission_showing",
                KeepADBNetworkCard.STATE_ALLOWLIST_PERMISSION_SHOWING);
        assertEquals(3001, KeepADBNetworkCard.TRUSTED_NETWORK_LOCATION_PERMISSION_REQUEST);
        assertEquals(3002, KeepADBNetworkCard.WIFI_APS_LOCATION_PERMISSION_REQUEST);
    }

    // --- helpers ----------------------------------------------------------------------------------------

    private SettingsActivity open() {
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        activity.findViewById(R.id.settings_network_beta_header).performClick();
        ShadowLooper.idleMainLooper();
        return activity;
    }

    /** saveInstanceState -> destroy -> a fresh instance restored from the bundle (#672). */
    private static ActivityController<SettingsActivity> rotate(
            ActivityController<SettingsActivity> controller) {
        Bundle state = new Bundle();
        controller.saveInstanceState(state);
        controller.pause().stop().destroy();
        ShadowLooper.idleMainLooper();
        return Robolectric.buildActivity(SettingsActivity.class).setup(state);
    }

    private void connectTo(String ssid, String bssid) {
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = ShadowWifiInfo.newInstance();
        shadowOf(info).setSSID(ssid);
        shadowOf(info).setBSSID(bssid);
        shadowOf(wifiManager).setConnectionInfo(info);
    }

    private void setPermission(String permission, boolean granted) {
        if (granted) {
            shadowOf((Application) context).grantPermissions(permission);
        } else {
            shadowOf((Application) context).denyPermissions(permission);
        }
    }

    private void deliverWifiChange() {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        Network network = ShadowNetwork.newInstance(101);
        for (ConnectivityManager.NetworkCallback callback : new ArrayList<>(callbacks(manager))) {
            callback.onAvailable(network);
            callback.onCapabilitiesChanged(network, new NetworkCapabilities());
            callback.onLost(network);
        }
        ShadowLooper.idleMainLooper();
    }

    private static Set<ConnectivityManager.NetworkCallback> callbacks(ConnectivityManager manager) {
        return shadowOf(manager).getNetworkCallbacks();
    }

    /** The callbacks registered by the Network card (anonymous classes declared inside it). */
    private static Set<ConnectivityManager.NetworkCallback> cardCallbacks(
            ConnectivityManager manager) {
        Set<ConnectivityManager.NetworkCallback> own = new HashSet<>();
        for (ConnectivityManager.NetworkCallback callback : callbacks(manager)) {
            if (callback.getClass().getEnclosingClass() == KeepADBNetworkCard.class) {
                own.add(callback);
            }
        }
        return own;
    }

    private static Set<ConnectivityManager.NetworkCallback> withoutCard(
            Set<ConnectivityManager.NetworkCallback> all) {
        Set<ConnectivityManager.NetworkCallback> rest = new HashSet<>();
        for (ConnectivityManager.NetworkCallback callback : all) {
            if (callback.getClass().getEnclosingClass() != KeepADBNetworkCard.class) {
                rest.add(callback);
            }
        }
        return rest;
    }

    private static NetworkRequest wifiRequest() {
        return new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();
    }

    private static List<ShadowLog.LogItem> warnings(String messagePart) {
        List<ShadowLog.LogItem> found = new ArrayList<>();
        for (ShadowLog.LogItem item : ShadowLog.getLogsForTag("KeepADB")) {
            if (item.type == android.util.Log.WARN && item.msg != null
                    && item.msg.contains(messagePart)) {
                found.add(item);
            }
        }
        return found;
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE);
    }

    /**
     * The Activity's own file, as {@code Activity#getPreferences} names it: the activity's class
     * name relative to the package (the debug variant's package suffix keeps the full name).
     */
    private SharedPreferences activityPrefs() {
        String packageName = context.getPackageName();
        String className = SettingsActivity.class.getName();
        String file = className.startsWith(packageName + ".")
                ? className.substring(packageName.length() + 1) : className;
        return context.getSharedPreferences(file, Context.MODE_PRIVATE);
    }

    /** Both preference files the Settings screen can reach, by file and key. */
    private Map<String, ?> everyPreference() {
        Map<String, Object> all = new TreeMap<>();
        for (Map.Entry<String, ?> entry : prefs().getAll().entrySet()) {
            all.put("keepadb_prefs/" + entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, ?> entry : activityPrefs().getAll().entrySet()) {
            all.put("activity/" + entry.getKey(), entry.getValue());
        }
        return all;
    }

    private static String text(SettingsActivity activity, int id) {
        return ((TextView) activity.findViewById(id)).getText().toString();
    }
}
