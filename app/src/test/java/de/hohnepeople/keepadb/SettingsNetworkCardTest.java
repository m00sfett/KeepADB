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
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
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
    public void theHeadFollowsAModeChangeMadeInTheCard() {
        shadowOf((Application) context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
        SettingsActivity activity = open();
        TextView head = activity.findViewById(R.id.settings_network_beta_subtitle);
        String before = head.getText().toString();

        activity.findViewById(R.id.network_mode_allowlist).performClick();
        ShadowDialog.reset();

        assertNotEquals(before, head.getText().toString());
        assertEquals(context.getString(R.string.network_head_mode,
                context.getString(R.string.network_mode_option_aps)), head.getText().toString());
    }

    @Test
    public void theSecondOptionNamesWifiNamesWhileTheMatchingSettingIsOn() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBTrustedNetwork.setSsidMatchingEnabled(context, true);
        SettingsActivity active = open();
        RadioButton allowlist = active.findViewById(R.id.network_mode_allowlist);
        assertTrue(allowlist.isChecked());
        assertEquals(context.getString(R.string.network_mode_option_aps_names),
                allowlist.getText().toString());

        // All networks stays the active mode; the second option still says what it would mean.
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        SettingsActivity allWifi = open();
        assertTrue(((RadioButton) allWifi.findViewById(R.id.network_mode_all_wifi)).isChecked());
        assertFalse(((RadioButton) allWifi.findViewById(R.id.network_mode_allowlist)).isChecked());
        assertEquals(context.getString(R.string.network_mode_option_aps_names),
                ((RadioButton) allWifi.findViewById(R.id.network_mode_allowlist)).getText().toString());

        KeepADBTrustedNetwork.setSsidMatchingEnabled(context, false);
        SettingsActivity off = open();
        assertEquals(context.getString(R.string.network_mode_option_aps),
                ((RadioButton) off.findViewById(R.id.network_mode_allowlist)).getText().toString());
    }

    @Test
    public void theCurrentConnectionComesBeforeTheModeChoice() {
        SettingsActivity activity = open();
        ViewGroup body = activity.findViewById(R.id.settings_network_beta_body);
        assertTrue(body.indexOfChild(activity.findViewById(R.id.settings_network_status_panel))
                < body.indexOfChild(activity.findViewById(R.id.settings_network_mode_panel)));
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
        assertEquals("HomeMesh · aa:bb:cc:dd:ee:01", text(activity, R.id.network_connection_line));
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
        assertEquals(SettingsActivity.WIFI_APS_LOCATION_PERMISSION_REQUEST, request.requestCode);

        // After a permanent denial the same button leads to the app's system settings instead.
        activity.onRequestPermissionsResult(SettingsActivity.WIFI_APS_LOCATION_PERMISSION_REQUEST,
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

    // --- management entries and observation --------------------------------------------------

    @Test
    public void managementEntriesShowTheirCountsAndOpenTheirOwnViews() {
        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:01", "A");
        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:02", "B");
        KeepADBBlockedNetworkHistory.record(context,
                new KeepADBNetworkIdentity("C", "aa:bb:cc:dd:ee:03"), 1_000L);
        KeepADBPreferences.setWifiApsFeatureEnabled(context, true);
        SettingsActivity activity = open();

        assertEquals("2", text(activity, R.id.network_allowed_count));
        assertEquals("1", text(activity, R.id.network_prevented_count));

        String[] views = {NetworkListActivity.VIEW_ALLOWED, NetworkListActivity.VIEW_PREVENTED,
                NetworkListActivity.VIEW_OBSERVED};
        int[] rows = {R.id.network_allowed_row, R.id.network_prevented_row, R.id.network_observed_row};
        for (int i = 0; i < rows.length; i++) {
            activity.findViewById(rows[i]).performClick();
            Intent opened = shadowOf(activity).getNextStartedActivity();
            assertNotNull(opened);
            assertEquals(NetworkListActivity.class.getName(), opened.getComponent().getClassName());
            assertEquals(views[i], opened.getStringExtra(NetworkListActivity.EXTRA_VIEW));
        }
    }

    @Test
    public void theCountsFollowChangesMadeInTheViewsOnReturn() {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();
        assertEquals("0", text(activity, R.id.network_allowed_count));

        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:01", "A");
        controller.pause().resume();

        assertEquals("1", text(activity, R.id.network_allowed_count));
    }

    /**
     * #654: the observation option controls only the observation and its list. Allowed and
     * prevented entries, the state and the advanced section stay reachable with it off, and
     * flipping it changes no allowlist, mode or name setting.
     */
    @Test
    public void theObservationOptionControlsOnlyTheObservedList() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:01", "A");
        KeepADBTrustedNetwork.setSsidMatchingEnabled(context, true);
        KeepADBTrustedNetwork.addSsid(context, "Mesh");
        Map<String, ?> trustBefore = trustSettings();
        SettingsActivity activity = open();
        Switch observe = activity.findViewById(R.id.settings_wifi_aps_feature_toggle);

        assertFalse("Observation is opt-in", observe.isChecked());
        assertFalse(activity.findViewById(R.id.network_observed_row).isShown());
        for (int id : new int[] {R.id.network_status_label, R.id.network_mode_allowlist,
                R.id.network_allowed_row, R.id.network_prevented_row, R.id.network_ssid_header}) {
            assertTrue("Reachable with observation off: " + id, activity.findViewById(id).isShown());
        }

        observe.performClick();
        assertTrue(KeepADBPreferences.isWifiApsFeatureEnabled(context));
        assertTrue(activity.findViewById(R.id.network_observed_row).isShown());
        assertEquals("Observation changes no allowlist, mode or name setting", trustBefore,
                trustSettings());

        observe.performClick();
        assertFalse(KeepADBPreferences.isWifiApsFeatureEnabled(context));
        assertFalse(activity.findViewById(R.id.network_observed_row).isShown());
        assertTrue(activity.findViewById(R.id.network_allowed_row).isShown());
        assertEquals(trustBefore, trustSettings());
    }

    @Test
    public void turningObservationOffStopsSettingsRefreshAndKeepsHistory() {
        KeepADBPreferences.setWifiApsFeatureEnabled(context, true);
        KeepADBBssidHistory.recordObservation(context, "HomeMesh", "aa:bb:cc:dd:ee:01");
        connectTo("HomeMesh", "aa:bb:cc:dd:ee:02");

        SettingsActivity activity = open();
        assertEquals(java.util.Arrays.asList("aa:bb:cc:dd:ee:01", "aa:bb:cc:dd:ee:02"),
                KeepADBBssidHistory.getKnownBssids(context, "HomeMesh"));

        Switch observe = activity.findViewById(R.id.settings_wifi_aps_feature_toggle);
        assertTrue(observe.isChecked());
        observe.performClick();
        assertFalse(KeepADBPreferences.isWifiApsFeatureEnabled(context));

        connectTo("HomeMesh", "aa:bb:cc:dd:ee:03");
        activity.refresh();
        assertEquals("Turning observation off retains prior entries and records no new one",
                java.util.Arrays.asList("aa:bb:cc:dd:ee:01", "aa:bb:cc:dd:ee:02"),
                KeepADBBssidHistory.getKnownBssids(context, "HomeMesh"));

        connectTo("HomeMesh", "aa:bb:cc:dd:ee:04");
        SettingsActivity reopened = open();
        assertFalse(((Switch) reopened.findViewById(R.id.settings_wifi_aps_feature_toggle))
                .isChecked());
        assertEquals("Opening Settings while observation is off does not record either",
                java.util.Arrays.asList("aa:bb:cc:dd:ee:01", "aa:bb:cc:dd:ee:02"),
                KeepADBBssidHistory.getKnownBssids(context, "HomeMesh"));
    }

    @Test
    public void theInactiveListHintAppearsOnlyInAllNetworksModeWithSavedEntries() {
        View hint;
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        hint = open().findViewById(R.id.network_lists_inactive_hint);
        assertEquals("Nothing saved, nothing to explain", View.GONE, hint.getVisibility());

        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:01", "A");
        SettingsActivity allWifi = open();
        hint = allWifi.findViewById(R.id.network_lists_inactive_hint);
        assertEquals(View.VISIBLE, hint.getVisibility());
        assertEquals(context.getString(R.string.network_list_inactive_hint,
                        context.getString(R.string.network_mode_option_aps)),
                ((TextView) hint).getText().toString());
        assertEquals("The saved list stays reachable", "1", text(allWifi, R.id.network_allowed_count));

        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        assertEquals(View.GONE, open().findViewById(R.id.network_lists_inactive_hint).getVisibility());
    }

    /**
     * #654 visual acceptance: the hint named the mode as "Only allowed ..." while the choice read
     * "Allowed access points and Wi-Fi names" once the name matching was on. In every state the
     * hint now names the label the second option shows at that moment, everywhere it appears: the
     * card entry and, with the matching saved but without effect, the line of the advanced section.
     */
    @Test
    public void theInactiveListHintNamesTheSecondOptionExactlyAsTheChoiceShowsItInEveryState() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:01", "A");
        String[] seenOptions = new String[2];
        for (boolean names : new boolean[] {false, true}) {
            KeepADBTrustedNetwork.setSsidMatchingEnabled(context, names);
            SettingsActivity activity = open();
            activity.findViewById(R.id.network_ssid_header).performClick();
            String option = ((RadioButton) activity.findViewById(R.id.network_mode_allowlist))
                    .getText().toString();
            seenOptions[names ? 1 : 0] = option;
            assertEquals("The fixture shows the label of the matching state",
                    context.getString(names ? R.string.network_mode_option_aps_names
                            : R.string.network_mode_option_aps), option);

            TextView cardHint = activity.findViewById(R.id.network_lists_inactive_hint);
            assertEquals(View.VISIBLE, cardHint.getVisibility());
            assertTrue("names=" + names + ": the card hint names the visible option '" + option
                    + "': " + cardHint.getText(), cardHint.getText().toString().contains(option));
            assertFalse("No truncated mode name: " + cardHint.getText(),
                    cardHint.getText().toString().contains("\u2026"));

            if (names) {
                String effect = text(activity, R.id.network_ssid_effect);
                assertTrue("The advanced section names the visible option '" + option + "': "
                        + effect, effect.contains(option));
                assertFalse("No truncated mode name: " + effect, effect.contains("\u2026"));
                assertEquals("The same sentence in both places", cardHint.getText().toString(), effect);
            }
        }
        assertNotEquals("The two states really show different labels", seenOptions[0], seenOptions[1]);
    }

    // --- the advanced Wi-Fi-name section -----------------------------------------------------

    @Test
    public void theAdvancedSectionIsTheLastContentOfTheCardAndStartsCollapsed() {
        SettingsActivity activity = open();
        ViewGroup body = activity.findViewById(R.id.settings_network_beta_body);

        View last = body.getChildAt(body.getChildCount() - 1);
        assertEquals(R.id.settings_network_ssid_panel, last.getId());
        assertEquals(View.GONE, activity.findViewById(R.id.network_ssid_body).getVisibility());
        assertEquals("+", text(activity, R.id.network_ssid_arrow));
        assertEquals(context.getString(R.string.card_state_collapsed),
                activity.findViewById(R.id.network_ssid_header).getStateDescription().toString());
        assertTrue("The header is an operable 48dp target",
                activity.findViewById(R.id.network_ssid_header).hasOnClickListeners());
    }

    @Test
    public void theClosedHeaderStatesOffActiveOrWithoutEffectAndTheNumberOfNames() {
        // Off, no names.
        assertEquals(context.getString(R.string.network_ssid_state_off),
                text(open(), R.id.network_ssid_state));

        // On and active: allowlist mode.
        KeepADBTrustedNetwork.addSsid(context, "Home");
        KeepADBTrustedNetwork.addSsid(context, "Mesh");
        KeepADBTrustedNetwork.setSsidMatchingEnabled(context, true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        assertEquals(context.getString(R.string.network_ssid_state_on, 2),
                text(open(), R.id.network_ssid_state));

        // Saved but without effect: all networks allowed.
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        assertEquals(context.getString(R.string.network_ssid_state_no_effect, 2),
                text(open(), R.id.network_ssid_state));

        // Off again with saved names: still off -- names alone never switch it on.
        KeepADBTrustedNetwork.setSsidMatchingEnabled(context, false);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        assertEquals(context.getString(R.string.network_ssid_state_off),
                text(open(), R.id.network_ssid_state));
    }

    @Test
    public void theClosedHeaderNeverRevealsANetworkName() {
        KeepADBTrustedNetwork.addSsid(context, "SecretHome");
        KeepADBTrustedNetwork.setSsidMatchingEnabled(context, true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBPreferences.setPrivacyModeEnabled(context, false);

        SettingsActivity activity = open();

        assertFalse("Collapsed, no name is on screen: " + shownText(activity),
                shownText(activity).contains("SecretHome"));
        activity.findViewById(R.id.network_ssid_header).performClick();
        assertTrue("Expanded, the user can see and manage the names",
                shownText(activity).contains("SecretHome"));
    }

    @Test
    public void expandingTheSectionShowsStateWarningSwitchAndNamesTogetherInThatOrder() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBTrustedNetwork.addSsid(context, "Home");
        SettingsActivity activity = open();
        View header = activity.findViewById(R.id.network_ssid_header);

        header.performClick();

        ViewGroup body = activity.findViewById(R.id.network_ssid_body);
        assertEquals(View.VISIBLE, body.getVisibility());
        assertEquals("−", text(activity, R.id.network_ssid_arrow));
        assertEquals(context.getString(R.string.card_state_expanded),
                header.getStateDescription().toString());
        View effect = activity.findViewById(R.id.network_ssid_effect);
        View toggle = activity.findViewById(R.id.settings_trusted_ssid_toggle);
        View current = activity.findViewById(R.id.wifi_ssids_current_row);
        View names = activity.findViewById(R.id.wifi_ssids_list);
        assertTrue(body.indexOfChild(effect) < body.indexOfChild(toggle));
        assertTrue("The switch is followed directly by the name list",
                body.indexOfChild(toggle) < body.indexOfChild(current)
                        && body.indexOfChild(current) < body.indexOfChild(names));
        // The risk warning sits between the state line and the switch.
        List<String> bodyTexts = textsOf(body);
        assertTrue(bodyTexts.contains(context.getString(R.string.settings_trusted_ssid_warning)));
        assertTrue("Every access point with the same name is accepted, stated in the warning",
                context.getString(R.string.settings_trusted_ssid_warning).length() > 0);
        assertTrue(effect.isShown() && toggle.isShown() && names.isShown());

        header.performClick();
        assertEquals(View.GONE, body.getVisibility());
        assertEquals(context.getString(R.string.card_state_collapsed),
                header.getStateDescription().toString());
    }

    @Test
    public void theEffectLineDistinguishesOffActiveAndWithoutEffect() {
        SettingsActivity off = open();
        assertEquals(context.getString(R.string.network_ssid_effect_off),
                text(off, R.id.network_ssid_effect));

        KeepADBTrustedNetwork.setSsidMatchingEnabled(context, true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        SettingsActivity active = open();
        assertEquals(context.getString(R.string.network_ssid_effect_on),
                text(active, R.id.network_ssid_effect));

        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        SettingsActivity noEffect = open();
        assertEquals("Saved matching names the option as it reads while the matching is on",
                context.getString(R.string.network_list_inactive_hint,
                        context.getString(R.string.network_mode_option_aps_names)),
                text(noEffect, R.id.network_ssid_effect));
    }

    @Test
    public void savedNamesStayReachableAndRemovableInAllNetworksModeWithTheSwitchInoperable() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        KeepADBTrustedNetwork.setSsidMatchingEnabled(context, true);
        KeepADBTrustedNetwork.addSsid(context, "Mesh");
        SettingsActivity activity = open();
        activity.findViewById(R.id.network_ssid_header).performClick();

        Switch toggle = activity.findViewById(R.id.settings_trusted_ssid_toggle);
        assertTrue("The saved setting is shown as it is", toggle.isChecked());
        assertFalse("Nothing to widen while all networks are allowed", toggle.isEnabled());
        LinearLayoutHolder list = new LinearLayoutHolder(activity.findViewById(R.id.wifi_ssids_list));
        assertEquals("Mesh", list.texts().get(0));

        Button remove = list.buttons().get(0);
        remove.performClick();
        assertTrue(KeepADBTrustedNetwork.getSsidEntries(context).isEmpty());
        assertEquals("Saved name setting untouched by removing a name", true,
                KeepADBTrustedNetwork.isSsidMatchingEnabled(context));
    }

    @Test
    public void theCurrentNameCanBeAllowedAndRemovedAgainFromTheAdvancedSection() {
        connectTo("MeshHome", "aa:bb:cc:dd:ee:06");
        SettingsActivity activity = open();
        activity.findViewById(R.id.network_ssid_header).performClick();

        List<Button> add = buttonsOf(activity.findViewById(R.id.wifi_ssids_current_row));
        assertEquals(1, add.size());
        assertEquals(context.getString(R.string.wifi_ssids_add_accessibility, "MeshHome"),
                add.get(0).getContentDescription().toString());
        add.get(0).performClick();

        List<KeepADBTrustedNetwork.SsidEntry> listed = KeepADBTrustedNetwork.getSsidEntries(context);
        assertEquals(1, listed.size());
        assertEquals("MeshHome", listed.get(0).ssid);
        assertFalse("Adding a name never switches the matching on",
                KeepADBTrustedNetwork.isSsidMatchingEnabled(context));
        assertTrue("Already listed: no duplicate add",
                buttonsOf(activity.findViewById(R.id.wifi_ssids_current_row)).isEmpty());

        List<Button> remove = buttonsOf(activity.findViewById(R.id.wifi_ssids_list));
        assertEquals(1, remove.size());
        assertEquals(context.getString(R.string.wifi_ssids_remove_accessibility, "MeshHome"),
                remove.get(0).getContentDescription().toString());
        remove.get(0).performClick();
        assertTrue(KeepADBTrustedNetwork.getSsidEntries(context).isEmpty());
    }

    /**
     * #655 visual acceptance: at font scale 2.0 the Allow and Remove actions of this section were
     * only as tall as their label and the label touched both button edges, because the Material
     * default button has no horizontal padding. Every action of the section now keeps its own
     * padding, a 48dp minimum that holds in the measured layout at the largest font, its place
     * below the name in a vertical row, and a label that may wrap but is never cut.
     */
    @Test
    public void theWifiNameActionsKeepPaddingAndA48dpTargetAtTheLargestFont() {
        RuntimeEnvironment.setFontScale(2.0f);
        connectTo("MeshHome", "aa:bb:cc:dd:ee:06");
        KeepADBTrustedNetwork.addSsid(context, "Mesh");
        SettingsActivity activity = open();
        activity.findViewById(R.id.network_ssid_header).performClick();
        View root = activity.getWindow().getDecorView();
        int width = dp(360);
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(4000), View.MeasureSpec.EXACTLY));
        root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());

        List<Button> actions = new ArrayList<>(
                buttonsOf(activity.findViewById(R.id.wifi_ssids_current_row)));
        actions.addAll(buttonsOf(activity.findViewById(R.id.wifi_ssids_list)));
        assertEquals("Allow for the current name, Remove for the saved one", 2, actions.size());
        for (Button action : actions) {
            String name = action.getText().toString();
            assertEquals("The fixture runs at the largest font scale", 2.0f,
                    activity.getResources().getConfiguration().fontScale, 0.001f);
            assertTrue(name + ": at least 48dp high once measured at font scale 2.0",
                    action.getMeasuredHeight() >= dp(48));
            assertTrue(name + ": the 48dp minimum is declared, not only reached by the font size",
                    action.getMinHeight() >= dp(48));
            assertTrue(name + ": the label keeps air to both edges",
                    action.getPaddingLeft() >= dp(16) && action.getPaddingRight() >= dp(16));
            assertTrue(name + ": the label keeps air above and below",
                    action.getPaddingTop() >= dp(8) && action.getPaddingBottom() >= dp(8));
            assertNull(name + ": a long label must wrap, not be cut with an ellipsis",
                    action.getEllipsize());
            assertEquals(name + ": a long label must wrap, not be limited to one line",
                    Integer.MAX_VALUE, action.getMaxLines());
            assertTrue(name + ": the action sits below its name in a vertical row",
                    action.getParent() instanceof LinearLayout
                            && ((LinearLayout) action.getParent()).getOrientation()
                                    == LinearLayout.VERTICAL
                            && ((ViewGroup) action.getParent()).indexOfChild(action) > 0);
            assertTrue(name + ": never wider than the row it sits in",
                    action.getMeasuredWidth() <= ((View) action.getParent()).getMeasuredWidth());
        }
    }

    @Test
    public void anUnreadableIdentityOffersNoNameAddAction() {
        connectTo(WifiManager.UNKNOWN_SSID, KeepADBNetworkIdentity.REDACTED_BSSID);
        SettingsActivity activity = open();
        activity.findViewById(R.id.network_ssid_header).performClick();

        assertTrue(buttonsOf(activity.findViewById(R.id.wifi_ssids_current_row)).isEmpty());
        assertTrue(textsOf(activity.findViewById(R.id.wifi_ssids_current_row))
                .contains(context.getString(R.string.wifi_ssids_current_unknown)));
        assertNull(KeepADBTrustedNetwork.addCurrentSsid(context));
    }

    @Test
    public void theEmptyNameListSaysSo() {
        SettingsActivity activity = open();
        activity.findViewById(R.id.network_ssid_header).performClick();
        assertEquals(View.VISIBLE, activity.findViewById(R.id.wifi_ssids_empty).getVisibility());

        KeepADBTrustedNetwork.addSsid(context, "Home");
        SettingsActivity filled = open();
        assertEquals(View.GONE, filled.findViewById(R.id.wifi_ssids_empty).getVisibility());
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
        assertFalse(((Switch) fresh.findViewById(R.id.settings_trusted_ssid_toggle)).isChecked());

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
                activity.findViewById(R.id.network_ssid_header).performClick();
                controller.pause().resume();

                assertEquals("allowlist=" + allowlist + " names=" + names, before, trustSettings());
            }
        }
    }

    @Test
    public void theModeChoiceWritesOnlyTheModeAndLeavesEveryListUntouched() {
        shadowOf((Application) context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
        KeepADBTrustedNetwork.setSsidMatchingEnabled(context, true);
        KeepADBTrustedNetwork.addSsid(context, "Mesh");
        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:01", "Home");
        SettingsActivity activity = open();
        Map<String, ?> before = trustSettings();

        activity.findViewById(R.id.network_mode_allowlist).performClick();
        ShadowDialog.reset();
        activity.findViewById(R.id.network_mode_all_wifi).performClick();

        Map<String, Object> after = new TreeMap<>(trustSettings());
        assertEquals(KeepADBTrustedNetwork.MODE_ALL_WIFI, after.remove("trusted_network_mode"));
        Map<String, Object> expected = new TreeMap<>(before);
        expected.remove("trusted_network_mode");
        assertEquals("Only the mode may differ", expected, after);
    }

    // --- privacy ------------------------------------------------------------------------------

    /**
     * #654: the privacy mode hides network names and addresses on the whole card -- the head, the
     * current connection, the name list and the descriptions -- and the off state shows them.
     */
    @Test
    public void privacyModeHidesNamesAndAddressesOnTheWholeCard() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBTrustedNetwork.addSsid(context, "SavedName");
        connectTo("HomeMesh", "aa:bb:cc:dd:ee:01");
        String[] secrets = {"HomeMesh", "SavedName", "aa:bb:cc:dd:ee:01", "AA:BB:CC:DD:EE:01",
                "ee:01", "EE:01"};

        KeepADBPreferences.setPrivacyModeEnabled(context, false);
        SettingsActivity shown = open();
        shown.findViewById(R.id.network_ssid_header).performClick();
        String visible = everything(shown);
        assertTrue(visible, visible.contains("HomeMesh"));
        assertTrue(visible, visible.contains("SavedName"));
        assertTrue(visible, visible.contains("aa:bb:cc:dd:ee:01"));
        assertEquals(View.GONE, shown.findViewById(R.id.network_privacy_hint).getVisibility());

        KeepADBPreferences.setPrivacyModeEnabled(context, true);
        SettingsActivity hidden = open();
        hidden.findViewById(R.id.network_ssid_header).performClick();
        String concealed = everything(hidden);
        for (String secret : secrets) {
            assertFalse("Privacy mode must hide '" + secret + "': " + concealed,
                    concealed.contains(secret));
        }
        assertEquals(View.VISIBLE, hidden.findViewById(R.id.network_privacy_hint).getVisibility());
        assertTrue("The action still names its target for TalkBack, without the real name",
                concealed.contains(context.getString(R.string.network_privacy_name_hidden)));
    }

    /**
     * #654 (user decision of 2026-09-30): in the Wi-Fi-name section the current name and the list
     * share one count of hidden names -- the same name reads alike in both places and a listed
     * name never takes the number of a different current name.
     */
    @Test
    public void hiddenWifiNamesAreNumberedPerNameAcrossTheCurrentRowAndTheList() {
        KeepADBTrustedNetwork.addSsid(context, "Cafe-WLAN");
        KeepADBTrustedNetwork.addSsid(context, "HomeMesh");
        KeepADBTrustedNetwork.addSsid(context, "Hotel-WLAN");
        connectTo("HomeMesh", "aa:bb:cc:dd:ee:01");
        KeepADBPreferences.setPrivacyModeEnabled(context, true);
        SettingsActivity activity = open();
        activity.findViewById(R.id.network_ssid_header).performClick();

        String hidden = context.getString(R.string.network_privacy_name_hidden);
        String badge = context.getString(R.string.wifi_aps_current_badge) + " \u00b7 ";
        assertTrue(textsOf(activity.findViewById(R.id.wifi_ssids_current_row)).toString(),
                textsOf(activity.findViewById(R.id.wifi_ssids_current_row))
                        .contains(badge + hidden + " #1"));
        List<String> listed = new ArrayList<>();
        for (String text : textsOf(activity.findViewById(R.id.wifi_ssids_list))) {
            if (text.startsWith(hidden)) listed.add(text);
        }
        assertEquals("Cafe-WLAN, HomeMesh (the current name), Hotel-WLAN, in list order",
                java.util.Arrays.asList(hidden + " #2", hidden + " #1", hidden + " #3"), listed);
        // The remove action of the current name's entry is described with that same number.
        boolean described = false;
        for (Button button : buttonsOf(activity.findViewById(R.id.wifi_ssids_list))) {
            described |= context.getString(R.string.wifi_ssids_remove_accessibility, hidden + " #1")
                    .contentEquals(button.getContentDescription());
        }
        assertTrue("The action names its target with the shown number", described);
    }

    /** #654: the current BSSID in the card reads first and last octet while hidden, all when not. */
    @Test
    public void privacyModeShowsTheFirstAndTheLastOctetOfTheCurrentAddress() {
        connectTo("HomeMesh", "de:11:22:33:44:ad");

        KeepADBPreferences.setPrivacyModeEnabled(context, false);
        assertEquals("HomeMesh \u00b7 de:11:22:33:44:ad",
                text(open(), R.id.network_connection_line));

        KeepADBPreferences.setPrivacyModeEnabled(context, true);
        SettingsActivity hidden = open();
        assertEquals(context.getString(R.string.network_privacy_name_hidden)
                + " \u00b7 de:*:*:*:*:ad", text(hidden, R.id.network_connection_line));
        String concealed = everything(hidden);
        for (String middle : new String[] {":11:", ":22:", ":33:", ":44:"}) {
            assertFalse("A middle octet is visible: " + concealed, concealed.contains(middle));
        }
    }

    @Test
    public void theConfirmationForAllowingTheCurrentNameUsesTheHiddenPlaceholderToo() {
        connectTo("HomeMesh", "aa:bb:cc:dd:ee:01");
        KeepADBPreferences.setPrivacyModeEnabled(context, true);
        SettingsActivity activity = open();
        activity.findViewById(R.id.network_ssid_header).performClick();

        Button add = buttonsOf(activity.findViewById(R.id.wifi_ssids_current_row)).get(0);
        assertFalse(add.getContentDescription().toString().contains("HomeMesh"));
        org.robolectric.shadows.ShadowToast.reset();
        add.performClick();

        assertEquals("The name is stored as it is; only the display is hidden", "HomeMesh",
                KeepADBTrustedNetwork.getSsidEntries(context).get(0).ssid);
        assertFalse("The toast must not quote a hidden name",
                org.robolectric.shadows.ShadowToast.getTextOfLatestToast().contains("HomeMesh"));

        // Removing it again is announced just as discreetly.
        org.robolectric.shadows.ShadowToast.reset();
        buttonsOf(activity.findViewById(R.id.wifi_ssids_list)).get(0).performClick();
        assertFalse(org.robolectric.shadows.ShadowToast.getTextOfLatestToast().contains("HomeMesh"));
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

        activity.findViewById(R.id.network_ssid_header).performClick();
        org.robolectric.shadows.ShadowToast.reset();
        buttonsOf(activity.findViewById(R.id.wifi_ssids_current_row)).get(0).performClick();
        assertEquals(context.getString(R.string.wifi_ssids_added_toast, hidden),
                org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
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
            if (key.startsWith("trusted_network_") || key.startsWith("trusted_ssid_")) {
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
