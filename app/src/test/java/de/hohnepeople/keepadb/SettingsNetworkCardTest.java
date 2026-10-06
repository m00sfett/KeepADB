package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
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
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowNetwork;
import org.robolectric.shadows.ShadowWifiInfo;

/**
 * #654/#655: the Network card in Settings -- the active mode in the closed head, the current
 * connection with cause and action before the mode choice, the separate background-access facts,
 * the two management entries, the observation option and the advanced Wi-Fi-name section at the
 * very bottom.
 *
 * <p>Each test states both sides of what it guards. The ones that matter most: the card never
 * writes a setting by itself ({@link #openingAndRefreshingNeverChangesAStoredSetting}), allowing
 * an access point from the card grants exactly that and never switches Wireless Debugging on
 * ({@link #allowingFromTheCardNeverSwitchesWirelessDebuggingOn}), and the privacy mode hides
 * every name and address on the card while the off state shows them
 * ({@link #privacyModeHidesNamesAndAddressesOnTheWholeCard}).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SettingsNetworkCardTest {

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
        ShadowDialog.reset();
    }

    @After
    public void tearDown() {
        prefs().edit().clear().commit();
        KeepADB.resetForTesting();
    }

    // --- the closed head and the mode choice -------------------------------------------------

    @Test
    public void theClosedHeadNamesTheActiveModeReadBackFromTheStoredSettings() {
        String[] seen = new String[4];
        int i = 0;
        for (boolean allowlist : new boolean[] {false, true}) {
            for (boolean names : new boolean[] {false, true}) {
                KeepADBTrustedNetwork.setMode(context, allowlist
                        ? KeepADBTrustedNetwork.MODE_ALLOWLIST : KeepADBTrustedNetwork.MODE_ALL_WIFI);
                KeepADBTrustedNetwork.setSsidMatchingEnabled(context, names);
                SettingsActivity activity = openClosed();

                TextView head = activity.findViewById(R.id.settings_network_beta_subtitle);
                assertTrue("The head is readable while the card is closed", head.isShown());
                assertEquals(View.GONE,
                        activity.findViewById(R.id.settings_network_beta_body).getVisibility());
                int option = allowlist
                        ? (names ? R.string.network_mode_option_aps_names : R.string.network_mode_option_aps)
                        : R.string.network_mode_option_all_wifi;
                assertEquals(context.getString(R.string.network_head_mode, context.getString(option)),
                        head.getText().toString());
                seen[i++] = head.getText().toString();
            }
        }
        // all networks with or without a saved name setting reads the same; the others differ.
        assertEquals("Saved names do not change the mode shown while all networks are allowed",
                seen[0], seen[1]);
        assertNotEquals(seen[0], seen[2]);
        assertNotEquals("Active Wi-Fi names are named in the mode", seen[2], seen[3]);
    }

    @Test
    public void theCurrentConnectionComesBeforeTheLevelAndTheSwitches() {
        SettingsActivity activity = open();
        ViewGroup body = activity.findViewById(R.id.settings_network_beta_body);
        assertTrue(body.indexOfChild(activity.findViewById(R.id.settings_network_status_panel))
                < body.indexOfChild(activity.findViewById(R.id.settings_network_level_panel)));
    }

    // --- the current connection -------------------------------------------------------------

    @Test
    public void anAllowedAccessPointIsNamedAsSuchAndOffersNoAction() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:01", "HomeMesh");
        connectTo("HomeMesh", "aa:bb:cc:dd:ee:01");

        SettingsActivity activity = open();

        assertEquals(context.getString(R.string.network_status_allowed_ap), text(activity, R.id.network_status_label));
        assertEquals(context.getColor(R.color.status_ok_green),
                ((TextView) activity.findViewById(R.id.network_status_label)).getCurrentTextColor());
        assertEquals("HomeMesh · AA:BB:CC:DD:EE:01", text(activity, R.id.network_connection_line));
        assertEquals(context.getString(R.string.network_cause_allowed), text(activity, R.id.network_status_cause));
        assertEquals(View.GONE, activity.findViewById(R.id.network_status_action).getVisibility());
    }

    @Test
    public void aNotAllowedAccessPointExplainsWhyAndOffersToAllowItWithoutSwitchingAnythingOn() {
        KeepADBFakeSettingsGateway gateway = preparedForAnAutomaticEnable();
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        connectTo("HomeMesh", "aa:bb:cc:dd:ee:01");

        SettingsActivity activity = open();

        assertEquals(context.getString(R.string.network_status_not_allowed),
                text(activity, R.id.network_status_label));
        assertEquals(context.getColor(R.color.text_yellow),
                ((TextView) activity.findViewById(R.id.network_status_label)).getCurrentTextColor());
        assertEquals(context.getString(R.string.network_cause_not_allowed),
                text(activity, R.id.network_status_cause));
        Button action = activity.findViewById(R.id.network_status_action);
        assertEquals(View.VISIBLE, action.getVisibility());
        assertEquals(context.getString(R.string.network_action_allow_ap), action.getText().toString());
        assertTrue("Looking at the card allows nothing", KeepADBTrustedNetwork.getEntries(context).isEmpty());

        action.performClick();
        ShadowLooper.idleMainLooper();

        List<KeepADBTrustedNetwork.Entry> entries = KeepADBTrustedNetwork.getEntries(context);
        assertEquals(1, entries.size());
        assertEquals("aa:bb:cc:dd:ee:01", entries.get(0).bssid);
        assertEquals(context.getString(R.string.network_status_allowed_ap),
                text(activity, R.id.network_status_label));
        assertEquals(View.GONE, action.getVisibility());
        assertTrue(gateway.writes.isEmpty());
    }

    /**
     * #762 (E1 review): a blocked network read "Not allowed" with an allow action that is refused.
     * It now reads "Blocked", gives the reason and offers no action; the same network without the
     * block keeps the old text and the action, so the block is what changed it.
     */
    @Test
    public void aBlockedNetworkReadsBlockedAndOffersNoActionThatCannotWork() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:01", "HomeMesh");
        connectTo("HomeMesh", "aa:bb:cc:dd:ee:01");
        KeepADBNetworkBlocklist.blockSsid(context, "HomeMesh");

        SettingsActivity activity = open();

        assertEquals(context.getString(R.string.network_badge_blocked),
                text(activity, R.id.network_status_label));
        assertEquals(context.getColor(R.color.link_red),
                ((TextView) activity.findViewById(R.id.network_status_label)).getCurrentTextColor());
        assertEquals(context.getString(R.string.network_cause_blocked),
                text(activity, R.id.network_status_cause));
        assertEquals(View.GONE, activity.findViewById(R.id.network_status_action).getVisibility());

        KeepADBNetworkBlocklist.unblockSsid(context, "HomeMesh");
        SettingsActivity unblocked = open();
        assertEquals(context.getString(R.string.network_status_allowed_ap),
                text(unblocked, R.id.network_status_label));
    }

    /**
     * #654: allowing an access point afterwards grants exactly that and nothing else. The setup is
     * the one in which the former trust-and-connect path enabled Wireless Debugging at once.
     */
    @Test
    public void allowingFromTheCardNeverSwitchesWirelessDebuggingOn() {
        KeepADBFakeSettingsGateway gateway = preparedForAnAutomaticEnable();
        connectTo("HomeMesh", "aa:bb:cc:dd:ee:01");
        SettingsActivity activity = open();

        activity.findViewById(R.id.network_status_action).performClick();
        ShadowLooper.idleMainLooper();

        assertEquals("The allowance itself must be stored", 1,
                KeepADBTrustedNetwork.getEntries(context).size());
        assertTrue("No enable action may be started by allowing: " + gateway.writes,
                gateway.writes.isEmpty());
        assertFalse(KeepADB.isEnabled(context));
    }

    @Test
    public void theActionGrantsTheAccessPointTheCardShowedNotTheOneTheDeviceRoamedTo() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        connectTo("Cafe-WLAN", "aa:bb:cc:dd:ee:01");
        SettingsActivity activity = open();

        connectTo("Cafe-WLAN", "aa:bb:cc:dd:ee:02");
        activity.findViewById(R.id.network_status_action).performClick();
        ShadowLooper.idleMainLooper();

        List<KeepADBTrustedNetwork.Entry> entries = KeepADBTrustedNetwork.getEntries(context);
        assertEquals(1, entries.size());
        assertEquals("The access point that was on screen", "aa:bb:cc:dd:ee:01", entries.get(0).bssid);
        assertEquals("Cafe-WLAN", entries.get(0).label);
    }

    @Test
    public void anAllowanceByWifiNameIsNamedSeparatelyFromTheAccessPointAllowance() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBTrustedNetwork.addSsid(context, "MeshHome");
        connectTo("MeshHome", "aa:bb:cc:dd:ee:05");

        KeepADBTrustedNetwork.setSsidMatchingEnabled(context, true);
        SettingsActivity byName = open();
        assertEquals(context.getString(R.string.network_status_allowed_name),
                text(byName, R.id.network_status_label));
        assertEquals(context.getString(R.string.network_cause_allowed_by_name),
                text(byName, R.id.network_status_cause));
        assertEquals("The access point itself can still be allowed separately",
                context.getString(R.string.network_action_allow_ap),
                text(byName, R.id.network_status_action));

        KeepADBTrustedNetwork.setSsidMatchingEnabled(context, false);
        SettingsActivity off = open();
        assertEquals("A listed name counts only while the switch is on",
                context.getString(R.string.network_status_not_allowed),
                text(off, R.id.network_status_label));
    }

    @Test
    public void allNetworksModeSaysTheListsDoNotCountYetStillOffersToAllow() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        connectTo("HomeMesh", "aa:bb:cc:dd:ee:01");

        SettingsActivity activity = open();

        assertEquals(context.getString(R.string.network_cause_all_wifi),
                text(activity, R.id.network_status_cause));
        assertEquals("Nothing is held back in this mode, so the state is not shown in amber",
                context.getColor(R.color.night_muted),
                ((TextView) activity.findViewById(R.id.network_status_label)).getCurrentTextColor());
        assertEquals(context.getString(R.string.network_action_allow_ap),
                text(activity, R.id.network_status_action));
    }

    /**
     * #654 visual acceptance: the all-networks cause ended in "... the 'Only allowed ...' mode",
     * right above a choice whose second option read "Allowed access points and Wi-Fi names" once
     * the matching was on. The sentence now names neither label of the second option, so it cannot
     * contradict whichever one the choice shows.
     */
    @Test
    public void theAllNetworksCauseNamesNeitherLabelOfTheAllowedOptionInEitherState() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        connectTo("HomeMesh", "aa:bb:cc:dd:ee:01");
        for (boolean names : new boolean[] {false, true}) {
            KeepADBTrustedNetwork.setSsidMatchingEnabled(context, names);
            SettingsActivity activity = open();

            String cause = text(activity, R.id.network_status_cause);

            assertEquals(context.getString(R.string.network_cause_all_wifi), cause);
            assertTrue("It still names the active mode: " + cause,
                    cause.contains(context.getString(R.string.network_mode_option_all_wifi)));
            assertFalse("No truncated mode name: " + cause, cause.contains("\u2026"));
            for (int label : new int[] {R.string.network_mode_option_aps,
                    R.string.network_mode_option_aps_names}) {
                assertFalse("names=" + names + ": must not quote '" + context.getString(label)
                        + "': " + cause, cause.contains(context.getString(label)));
            }
            assertFalse("Nothing else may shorten a mode name either: " + cause,
                    cause.contains("Only allowed"));
        }
    }

    @Test
    public void noWifiOffersTheWifiSettings() {
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> false);
        SettingsActivity activity = open();

        assertEquals(context.getString(R.string.network_status_no_wifi), text(activity, R.id.network_status_label));
        assertEquals(context.getString(R.string.network_cause_no_wifi), text(activity, R.id.network_status_cause));
        assertEquals(View.GONE, activity.findViewById(R.id.network_connection_line).getVisibility());
        Button action = activity.findViewById(R.id.network_status_action);
        assertEquals(context.getString(R.string.network_action_wifi_settings), action.getText().toString());

        action.performClick();
        Intent opened = shadowOf(activity).getNextStartedActivity();
        assertNotNull(opened);
        assertEquals(Settings.ACTION_WIFI_SETTINGS, opened.getAction());
    }

    @Test
    public void unreadableWithoutLocationPermissionOffersTheGrantAndNamesTheCause() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);

        SettingsActivity activity = open();

        assertEquals(context.getString(R.string.network_status_unreadable), text(activity, R.id.network_status_label));
        assertEquals(context.getString(R.string.network_cause_permission_missing),
                text(activity, R.id.network_status_cause));
        Button action = activity.findViewById(R.id.network_status_action);
        assertEquals(context.getString(R.string.location_permission_panel_grant_button),
                action.getText().toString());

        action.performClick();
        org.robolectric.shadows.ShadowActivity.PermissionsRequest request =
                shadowOf(activity).getLastRequestedPermission();
        assertNotNull(request);
        assertEquals(Manifest.permission.ACCESS_FINE_LOCATION, request.requestedPermissions[0]);
        assertEquals(KeepADBNetworkCard.WIFI_APS_LOCATION_PERMISSION_REQUEST, request.requestCode);

        // After a permanent denial the same button leads to the app's system settings instead.
        activity.onRequestPermissionsResult(KeepADBNetworkCard.WIFI_APS_LOCATION_PERMISSION_REQUEST,
                new String[] {Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION},
                new int[] {android.content.pm.PackageManager.PERMISSION_DENIED,
                        android.content.pm.PackageManager.PERMISSION_DENIED});
        Button updated = activity.findViewById(R.id.network_status_action);
        assertEquals(context.getString(R.string.location_permission_settings_button),
                updated.getText().toString());
        updated.performClick();
        Intent opened = shadowOf(activity).getNextStartedActivity();
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, opened.getAction());
    }

    @Test
    public void unreadableWithLocationSwitchedOffOffersTheLocationSettings() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        shadowOf((Application) context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
        shadowOf(context.getSystemService(LocationManager.class)).setLocationEnabled(false);

        SettingsActivity activity = open();

        assertEquals(context.getString(R.string.network_cause_location_off),
                text(activity, R.id.network_status_cause));
        Button action = activity.findViewById(R.id.network_status_action);
        assertEquals(context.getString(R.string.network_action_location_settings),
                action.getText().toString());
        action.performClick();
        assertEquals(Settings.ACTION_LOCATION_SOURCE_SETTINGS,
                shadowOf(activity).getNextStartedActivity().getAction());
    }

    @Test
    public void aHiddenIdentityWithoutTheBackgroundGrantOffersTheBackgroundSetup() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        shadowOf((Application) context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION);

        SettingsActivity activity = open();

        assertEquals(context.getString(R.string.settings_trusted_network_status_identity_unavailable),
                text(activity, R.id.network_status_cause));
        Button action = activity.findViewById(R.id.network_status_action);
        assertEquals(context.getString(R.string.network_background_setup_button),
                action.getText().toString());
        action.performClick();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        assertEquals(context.getString(R.string.background_location_panel_title),
                shadowOf(dialog).getTitle().toString());
    }

    @Test
    public void unreadableWhileAllNetworksAreAllowedSaysNothingIsPaused() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);

        SettingsActivity activity = open();

        assertEquals(context.getString(R.string.network_cause_unreadable_all_wifi),
                text(activity, R.id.network_status_cause));
        assertNotEquals("The paused-Keep-Alive text would be wrong here",
                context.getString(R.string.network_cause_permission_missing),
                text(activity, R.id.network_status_cause));
    }

    // --- background access and the current reading are two facts ------------------------------

    @Test
    public void backgroundAccessAndCurrentDetectionAreShownAsSeparateFacts() {
        shadowOf((Application) context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);

        // Readable right now, but no background grant: restricted, and no claim of a failure.
        connectTo("HomeMesh", "aa:bb:cc:dd:ee:01");
        SettingsActivity readable = open();
        assertEquals(context.getString(R.string.background_location_status_missing),
                text(readable, R.id.settings_background_location_status));
        assertEquals(context.getString(R.string.network_detection_readable),
                text(readable, R.id.network_detection_now));
        assertEquals(context.getString(R.string.network_background_setup_button),
                text(readable, R.id.settings_background_location_button));

        // Unreadable right now, but the grant is there: allowed, and no claim of a working start.
        shadowOf((Application) context).grantPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        connectTo(WifiManager.UNKNOWN_SSID, KeepADBNetworkIdentity.REDACTED_BSSID);
        SettingsActivity unreadable = open();
        assertEquals(context.getString(R.string.background_location_status_granted),
                text(unreadable, R.id.settings_background_location_status));
        assertEquals(context.getString(R.string.network_detection_unreadable),
                text(unreadable, R.id.network_detection_now));
        assertEquals(context.getString(R.string.background_location_settings_button),
                text(unreadable, R.id.settings_background_location_button));

        // No Wi-Fi at all: nothing to read, and the grant verdict stays what it was.
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> false);
        SettingsActivity none = open();
        assertEquals(context.getString(R.string.network_detection_no_wifi),
                text(none, R.id.network_detection_now));
        assertEquals(context.getString(R.string.background_location_status_granted),
                text(none, R.id.settings_background_location_status));
    }

    @Test
    public void backgroundAccessIsNotRequiredWhileAllNetworksAreAllowed() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);

        SettingsActivity activity = open();

        assertEquals(context.getString(R.string.background_location_status_missing_inactive),
                text(activity, R.id.settings_background_location_status));
        assertEquals("The setup stays reachable", View.VISIBLE,
                activity.findViewById(R.id.settings_background_location_button).getVisibility());
        assertEquals(context.getColor(R.color.night_muted),
                ((TextView) activity.findViewById(R.id.settings_background_location_status))
                        .getCurrentTextColor());
    }

    // --- the one Networks entry ---------------------------------------------------------------

    @Test
    public void theNetworksEntryShowsTheCountsAndOpensTheOneList() {
        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:01", "A");
        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:02", "B");
        KeepADBNetworkBlocklist.blockBssid(context, "aa:bb:cc:dd:ee:03");
        SettingsActivity activity = open();

        assertEquals(context.getString(R.string.networks_count, 2, 1),
                text(activity, R.id.network_networks_count));

        activity.findViewById(R.id.network_networks_row).performClick();
        Intent opened = shadowOf(activity).getNextStartedActivity();
        assertNotNull(opened);
        assertEquals(NetworkListActivity.class.getName(), opened.getComponent().getClassName());
        assertNull("There is one list, so no view is named", opened.getExtras());
    }

    @Test
    public void theCountsFollowChangesMadeInTheListOnReturn() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();
        assertEquals(context.getString(R.string.networks_count, 0, 0),
                text(activity, R.id.network_networks_count));

        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:01", "A");
        controller.pause().resume();

        assertEquals(context.getString(R.string.networks_count, 1, 0),
                text(activity, R.id.network_networks_count));
    }

    @Test
    public void theOpenCardRefreshesForWifiChangesAndUnregistersWhenStopped() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:01", "HomeMesh");
        connectTo("HomeMesh", "aa:bb:cc:dd:ee:02");

        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();
        activity.findViewById(R.id.settings_network_beta_header).performClick();
        ShadowLooper.idleMainLooper();
        assertEquals(context.getString(R.string.network_status_not_allowed),
                text(activity, R.id.network_status_label));

        ConnectivityManager connectivityManager =
                context.getSystemService(ConnectivityManager.class);
        assertEquals("The visible Settings activity registers one Wi-Fi callback", 1,
                shadowOf(connectivityManager).getNetworkCallbacks().size());

        connectTo("HomeMesh", "aa:bb:cc:dd:ee:01");
        Network network = ShadowNetwork.newInstance(101);
        for (ConnectivityManager.NetworkCallback callback
                : shadowOf(connectivityManager).getNetworkCallbacks()) {
            callback.onAvailable(network);
        }
        ShadowLooper.idleMainLooper();

        assertEquals(context.getString(R.string.network_status_allowed_ap),
                text(activity, R.id.network_status_label));
        assertEquals("The callback only refreshes display state", 1,
                KeepADBTrustedNetwork.getEntries(context).size());
        assertFalse(KeepADB.isEnabled(context));

        controller.stop();
        assertTrue("The Wi-Fi callback is removed with the visible activity",
                shadowOf(connectivityManager).getNetworkCallbacks().isEmpty());
        controller.destroy();
    }

    // --- nothing is ever switched by opening the screen ---------------------------------------

    /**
     * #655: the redesign must never turn the Wi-Fi-name matching (or the mode) on or off by itself.
     * The persisted trust settings -- mode, both allowlists, the matching switch -- are identical
     * before and after opening, refreshing, resuming and expanding, in every combination, and the
     * fresh-install default stays off.
     */
    @Test
    public void openingAndRefreshingNeverChangesAStoredSetting() {
        assertFalse("Fresh install: matching is off", KeepADBTrustedNetwork.isSsidMatchingEnabled(context));
        SettingsActivity fresh = open();
        assertFalse(KeepADBTrustedNetwork.isSsidMatchingEnabled(context));
        assertFalse(((Switch) fresh.findViewById(R.id.settings_trust_by_name_toggle)).isChecked());

        for (boolean allowlist : new boolean[] {false, true}) {
            for (boolean names : new boolean[] {false, true}) {
                prefs().edit().clear().commit();
                KeepADBTrustedNetwork.setMode(context, allowlist
                        ? KeepADBTrustedNetwork.MODE_ALLOWLIST : KeepADBTrustedNetwork.MODE_ALL_WIFI);
                KeepADBTrustedNetwork.setSsidMatchingEnabled(context, names);
                KeepADBTrustedNetwork.addSsid(context, "Mesh");
                KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:01", "Home");
                connectTo("Mesh", "aa:bb:cc:dd:ee:02");
                Map<String, ?> before = trustSettings();

                ActivityController<SettingsActivity> controller =
                        Robolectric.buildActivity(SettingsActivity.class).setup();
                SettingsActivity activity = controller.get();
                activity.findViewById(R.id.settings_network_beta_header).performClick();
                controller.pause().resume();

                assertEquals("allowlist=" + allowlist + " names=" + names, before, trustSettings());
            }
        }
    }

    // --- privacy ------------------------------------------------------------------------------

    /**
     * #654: the privacy mode hides network names and addresses on the whole card -- the head, the
     * current connection, the name list and the descriptions -- and the off state shows them.
     */
    @Test
    public void privacyModeHidesNamesAndAddressesOnTheWholeCard() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        connectTo("HomeMesh", "aa:bb:cc:dd:ee:01");
        String[] secrets = {"HomeMesh", "aa:bb:cc:dd:ee:01", "AA:BB:CC:DD:EE:01",
                "ee:01", "EE:01"};

        KeepADBPreferences.setPrivacyModeEnabled(context, false);
        SettingsActivity shown = open();
        String visible = everything(shown);
        assertTrue(visible, visible.contains("HomeMesh"));
        assertTrue(visible, visible.contains("AA:BB:CC:DD:EE:01"));
        assertEquals(View.GONE, shown.findViewById(R.id.network_privacy_hint).getVisibility());

        KeepADBPreferences.setPrivacyModeEnabled(context, true);
        SettingsActivity hidden = open();
        String concealed = everything(hidden);
        for (String secret : secrets) {
            assertFalse("Privacy mode must hide '" + secret + "': " + concealed,
                    concealed.contains(secret));
        }
        assertEquals(View.VISIBLE, hidden.findViewById(R.id.network_privacy_hint).getVisibility());
        assertTrue("The action still names its target for TalkBack, without the real name",
                concealed.contains(context.getString(R.string.network_privacy_name_hidden)));
    }

    /** #654: the current BSSID in the card reads first and last octet while hidden, all when not. */
    @Test
    public void privacyModeShowsTheFirstAndTheLastOctetOfTheCurrentAddress() {
        connectTo("HomeMesh", "de:11:22:33:44:ad");

        KeepADBPreferences.setPrivacyModeEnabled(context, false);
        assertEquals("HomeMesh \u00b7 DE:11:22:33:44:AD",
                text(open(), R.id.network_connection_line));

        KeepADBPreferences.setPrivacyModeEnabled(context, true);
        SettingsActivity hidden = open();
        assertEquals(context.getString(R.string.network_privacy_name_hidden)
                + " \u00b7 DE:*:*:*:*:AD", text(hidden, R.id.network_connection_line));
        String concealed = everything(hidden);
        for (String middle : new String[] {":11:", ":22:", ":33:", ":44:"}) {
            assertFalse("A middle octet is visible: " + concealed, concealed.contains(middle));
        }
    }

    /**
     * #654: a hidden name that stands alone -- the description of the status action, the toast
     * after allowing it -- reads "Name hidden" without a number. The number belongs to the rows
     * of a list; outside one it would point at a row that is not there.
     */
    @Test
    public void aHiddenNameOutsideAListCarriesNoNumber() {
        preparedForAnAutomaticEnable();
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        connectTo("HomeMesh", "aa:bb:cc:dd:ee:01");
        KeepADBPreferences.setPrivacyModeEnabled(context, true);
        SettingsActivity activity = open();
        String hidden = context.getString(R.string.network_privacy_name_hidden);

        Button action = activity.findViewById(R.id.network_status_action);
        assertEquals(View.VISIBLE, action.getVisibility());
        assertEquals(context.getString(R.string.network_action_allow_ap_accessibility, hidden),
                action.getContentDescription().toString());

    }

    // --- helpers ------------------------------------------------------------------------------

    private SettingsActivity open() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();
        activity.findViewById(R.id.settings_network_beta_header).performClick();
        ShadowLooper.idleMainLooper();
        return activity;
    }

    private SettingsActivity openClosed() {
        return Robolectric.buildActivity(SettingsActivity.class).setup().get();
    }

    /** The setup in which the former trust-and-connect path switched Wireless Debugging on. */
    private KeepADBFakeSettingsGateway preparedForAnAutomaticEnable() {
        shadowOf((Application) context).grantPermissions(
                Manifest.permission.WRITE_SECURE_SETTINGS, Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.ACCESS_FINE_LOCATION);
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(false);
        KeepADB.setGatewayForTesting(gateway);
        return gateway;
    }

    private void connectTo(String ssid, String bssid) {
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = ShadowWifiInfo.newInstance();
        shadowOf(info).setSSID(ssid);
        shadowOf(info).setBSSID(bssid);
        shadowOf(wifiManager).setConnectionInfo(info);
    }

    private void connectTo(String ssid, String bssid, int frequency) {
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = ShadowWifiInfo.newInstance();
        shadowOf(info).setSSID(ssid);
        shadowOf(info).setBSSID(bssid);
        shadowOf(info).setFrequency(frequency);
        shadowOf(wifiManager).setConnectionInfo(info);
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE);
    }

    private int dp(int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density);
    }

    /** Every persisted key that decides trust: the mode and both allowlists with the name switch. */
    private Map<String, ?> trustSettings() {
        Map<String, Object> result = new TreeMap<>();
        for (Map.Entry<String, ?> entry : prefs().getAll().entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("trusted_network_") || key.startsWith("trusted_ssid_")
                    || key.equals("trust_by_name")) {
                result.put(key, entry.getValue());
            }
        }
        return result;
    }

    private String text(SettingsActivity activity, int id) {
        return ((TextView) activity.findViewById(id)).getText().toString();
    }

    private static List<String> textsOf(View root) {
        List<String> texts = new ArrayList<>();
        for (TextView view : viewsOf(root, TextView.class)) texts.add(view.getText().toString());
        return texts;
    }

    private static List<Button> buttonsOf(View root) {
        return viewsOf(root, Button.class);
    }

    /** Text of every view that is actually on screen (a collapsed body contributes nothing). */
    private static String shownText(SettingsActivity activity) {
        StringBuilder out = new StringBuilder();
        for (TextView view : viewsOf(activity.getWindow().getDecorView(), TextView.class)) {
            if (view.isShown()) out.append(view.getText()).append('\n');
        }
        return out.toString();
    }

    /** Every string and description in the Network card, shown or not. */
    private static String everything(SettingsActivity activity) {
        StringBuilder out = new StringBuilder();
        collect(activity.findViewById(R.id.settings_network_beta_panel), out);
        return out.toString();
    }

    private static void collect(View view, StringBuilder out) {
        if (view instanceof TextView) out.append(((TextView) view).getText()).append('\n');
        if (view.getContentDescription() != null) out.append(view.getContentDescription()).append('\n');
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), out);
        }
    }

    private static <T extends View> List<T> viewsOf(View root, Class<T> type) {
        List<T> result = new ArrayList<>();
        collectViews(root, type, result);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static <T extends View> void collectViews(View view, Class<T> type, List<T> out) {
        if (type.isInstance(view)) out.add((T) view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectViews(group.getChildAt(i), type, out);
        }
    }

    /** Small view of a list container: its text and its buttons. */
    private static final class LinearLayoutHolder {
        private final View root;

        LinearLayoutHolder(View root) {
            this.root = root;
        }

        List<String> texts() {
            return textsOf(root);
        }

        List<Button> buttons() {
            return buttonsOf(root);
        }
    }
}
