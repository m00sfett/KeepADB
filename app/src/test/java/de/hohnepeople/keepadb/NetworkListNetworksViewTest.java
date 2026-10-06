package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
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
import java.util.Locale;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.shadows.ShadowWifiInfo;

/**
 * #762: the single "Networks" view, driven through the real activity, its real dialogs and the
 * embedded decision component of #766. What is pinned, each invariant with its other side next to
 * it:
 *
 * <ul>
 *   <li>a trusted access point under a blocked name reads "Blocked" with the reason and never
 *       "Trusted", and reads "Trusted" without the name block;
 *   <li>"Stop trusting" and "Lift block" of an access point end in "Unknown"; lifting a name block
 *       gives the stored trust back;
 *   <li>with the privacy mode on no view and no description holds a Wi-Fi name or an address, and
 *       with it off they do (so the check can fail);
 *   <li>the undecided current network is asked through the embedded #766 component, and the list
 *       has no dialog of its own for that question.
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class NetworkListNetworksViewTest {

    private static final String HOME = "Heimnetz";
    private static final String KITCHEN = "aa:bb:cc:11:22:33";
    private static final String HALL = "aa:bb:cc:11:22:44";
    private static final String CAFE = "Cafe Sonne";
    private static final String CAFE_AP = "12:34:56:78:9a:bc";

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = RuntimeEnvironment.getApplication();
    private KeepADBFakeSettingsGateway gateway;

    @Before
    public void setUp() {
        shadowOf((Application) context).grantPermissions(
                Manifest.permission.WRITE_SECURE_SETTINGS, Manifest.permission.POST_NOTIFICATIONS);
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADB.resetForTesting();
        gateway = new KeepADBFakeSettingsGateway(false);
        KeepADB.setGatewayForTesting(gateway);
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        ShadowToast.reset();
    }

    @After
    public void tearDown() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADB.resetForTesting();
    }

    // --- Entry and order ---------------------------------------------------------------------

    @Test
    public void theViewHasItsOwnTitleIntroAndShowsTheCurrentNetworkBeforeTheSavedOnes() {
        connectTo(HOME, KITCHEN);
        KeepADBTrustedNetwork.addBssid(context, HALL, HOME);

        NetworkListActivity activity = open();

        assertEquals(NetworkListActivity.VIEW_NETWORKS, activity.getListView());
        TextView title = activity.findViewById(R.id.network_list_title);
        assertEquals(context.getString(R.string.networks_title), title.getText().toString());
        assertTrue(title.isAccessibilityHeading());
        assertEquals(context.getString(R.string.networks_intro),
                ((TextView) activity.findViewById(R.id.network_list_intro)).getText().toString());
        assertEquals(View.VISIBLE, activity.findViewById(R.id.networks_root).getVisibility());
        assertEquals("The older lists are not drawn in this view",
                View.GONE, activity.findViewById(R.id.wifi_aps_current_row).getVisibility());

        String all = everythingShown(activity);
        int current = all.indexOf(context.getString(R.string.networks_section_current));
        int saved = all.indexOf(context.getString(R.string.networks_section_saved));
        assertTrue(all, current >= 0 && saved > current);
        assertTrue("Current network is named above the saved ones",
                all.indexOf(KITCHEN.toUpperCase(Locale.ROOT)) < all.indexOf(HALL.toUpperCase(Locale.ROOT)));
    }

    @Test
    public void theOtherViewsAreUnchangedAndDoNotDrawTheNewOne() {
        NetworkListActivity activity = open(NetworkListActivity.VIEW_ALLOWED);
        assertEquals(View.GONE, activity.findViewById(R.id.networks_root).getVisibility());
        assertEquals(0, ((ViewGroup) activity.findViewById(R.id.networks_root)).getChildCount());
    }

    // --- Blocked before trusted ---------------------------------------------------------------

    @Test
    public void aTrustedAccessPointUnderABlockedNameShowsBlockedWithTheReasonAndNeverTrusted() {
        KeepADBTrustedNetwork.addBssid(context, KITCHEN, HOME);
        String trusted = context.getString(R.string.networks_badge_trusted);
        String blocked = context.getString(R.string.network_badge_blocked);

        View before = rowOf(open(), KITCHEN);
        assertTrue("Control without a name block: trusted: " + description(before),
                statusWords(before).contains(trusted));
        assertFalse(statusWords(before).contains(blocked));

        KeepADBNetworkBlocklist.blockSsid(context, HOME);
        NetworkListActivity activity = open();
        View row = rowOf(activity, KITCHEN);
        String spoken = description(row);
        assertTrue("Blocked is the status: " + spoken, spoken.contains(blocked));
        assertTrue("... with the reason: " + spoken,
                spoken.contains(context.getString(R.string.networks_reason_name_trusted, HOME)));
        assertFalse("Never 'Trusted' as the status of a block: " + spoken,
                statusWords(row).contains(trusted));
        assertTrue(statusWords(row).contains(blocked));
        assertTrue("The group carries the name block",
                everythingShown(activity).contains(context.getString(R.string.networks_badge_name_blocked)));
    }

    @Test
    public void theCurrentNetworkIsBlockedNotTrustedUnderABlockedNameAndTrustedWithoutOne() {
        connectTo(HOME, KITCHEN);
        KeepADBTrustedNetwork.addBssid(context, KITCHEN, HOME);
        String trusted = context.getString(R.string.networks_badge_trusted);
        String blocked = context.getString(R.string.network_badge_blocked);

        View beforeCard = currentCard(open());
        assertTrue(texts(beforeCard), statusWords(beforeCard).contains(trusted));
        assertFalse(texts(beforeCard), statusWords(beforeCard).contains(blocked));

        KeepADBNetworkBlocklist.blockSsid(context, HOME);
        View afterCard = currentCard(open());
        assertTrue(texts(afterCard), statusWords(afterCard).contains(blocked));
        assertFalse("The card of a blocked network never says trusted: " + texts(afterCard),
                statusWords(afterCard).contains(trusted));
        assertTrue(texts(afterCard),
                texts(afterCard).contains(context.getString(R.string.networks_reason_name_trusted, HOME)));
    }

    // --- The way back ------------------------------------------------------------------------

    @Test
    public void stopTrustingAndLiftingABlockBothEndInUnknownAndTheRowIsGone() {
        KeepADBTrustedNetwork.addBssid(context, KITCHEN, HOME);
        KeepADBTrustedNetwork.addBssid(context, HALL, HOME);
        NetworkListActivity activity = open();

        rowOf(activity, KITCHEN).performClick();
        press(R.string.networks_action_untrust);
        assertEquals(context.getString(R.string.networks_untrusted_toast), ShadowToast.getTextOfLatestToast());
        assertEquals(KeepADBNetworkList.Status.UNKNOWN, KeepADBNetworkList.statusOf(context, HOME, KITCHEN));
        assertNull("An unknown access point is no saved row", findRow(activity, KITCHEN));
        assertNotNull("The other access point is untouched", findRow(activity, HALL));

        rowOf(activity, HALL).performClick();
        press(R.string.networks_action_block);
        assertEquals(context.getString(R.string.network_decision_blocked_toast),
                ShadowToast.getTextOfLatestToast());
        assertTrue(statusWords(rowOf(activity, HALL)).contains(context.getString(R.string.network_badge_blocked)));

        rowOf(activity, HALL).performClick();
        press(R.string.networks_action_unblock);
        assertEquals(KeepADBNetworkList.Status.UNKNOWN, KeepADBNetworkList.statusOf(context, HOME, HALL));
        assertNull(findRow(activity, HALL));
        assertTrue("Nothing is stored for it any more", KeepADBTrustedNetwork.getEntries(context).isEmpty());
        assertTrue(KeepADBNetworkBlocklist.isEmpty(context));
        assertTrue("None of this switches Wireless Debugging: " + gateway.writes, gateway.writes.isEmpty());
    }

    @Test
    public void liftingTheNameBlockAsksFirstAndThenGivesTheStoredTrustBack() {
        KeepADBTrustedNetwork.addBssid(context, KITCHEN, HOME);
        KeepADBNetworkBlocklist.blockSsid(context, HOME);
        NetworkListActivity activity = open();

        rowOf(activity, KITCHEN).performClick();
        press(R.string.networks_action_unblock_name);
        AlertDialog confirm = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(confirm.isShowing());
        assertTrue("Nothing is lifted before the user confirms",
                KeepADBNetworkBlocklist.isSsidBlocked(context, HOME));

        // Cancel first: still blocked. Then confirm.
        confirm.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        assertTrue(KeepADBNetworkBlocklist.isSsidBlocked(context, HOME));

        rowOf(activity, KITCHEN).performClick();
        press(R.string.networks_action_unblock_name);
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        assertFalse(KeepADBNetworkBlocklist.isSsidBlocked(context, HOME));
        assertTrue("Trusted again as the stored trust applies",
                statusWords(rowOf(activity, KITCHEN)).contains(context.getString(R.string.networks_badge_trusted)));
    }

    @Test
    public void blockingAWifiNameFromTheGroupBlocksEveryAccessPointOfItAndOffersTheLiftAfterwards() {
        KeepADBTrustedNetwork.addBssid(context, KITCHEN, HOME);
        KeepADBTrustedNetwork.addBssid(context, HALL, HOME);
        NetworkListActivity activity = open();

        View header = groupHeader(activity, HOME);
        assertNotNull(header);
        assertTrue("The header is reachable by its own description: " + description(header),
                description(header).contains(HOME));
        header.performClick();
        press(R.string.network_decision_block_name, HOME);

        String blocked = context.getString(R.string.network_badge_blocked);
        assertTrue(statusWords(rowOf(activity, KITCHEN)).contains(blocked));
        assertTrue(statusWords(rowOf(activity, HALL)).contains(blocked));
        assertTrue(KeepADBNetworkBlocklist.isSsidBlocked(context, HOME));

        groupHeader(activity, HOME).performClick();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("The lift is offered where the block was set", button(dialog,
                context.getString(R.string.networks_action_unblock_name)));
        assertNull("... and blocking again is not", button(dialog,
                context.getString(R.string.network_decision_block_name, HOME)));
    }

    @Test
    public void aBlockedAddressTrustsAgainOnlyThroughTheExplicitTrustAnswerAndNotWhileTheNameIsBlocked() {
        KeepADBNetworkBlocklist.blockBssid(context, CAFE_AP);
        KeepADBBssidHistory.recordObservation(context, CAFE, CAFE_AP);
        NetworkListActivity activity = open();

        rowOf(activity, CAFE_AP).performClick();
        press(R.string.network_decision_trust);
        assertTrue(statusWords(rowOf(activity, CAFE_AP)).contains(context.getString(R.string.networks_badge_trusted)));
        assertFalse(KeepADBNetworkBlocklist.isBssidBlocked(context, CAFE_AP));

        // With the name blocked, "Trust" is not offered on a blocked address at all.
        KeepADBNetworkBlocklist.blockBssid(context, KITCHEN);
        KeepADBBssidHistory.recordObservation(context, HOME, KITCHEN);
        KeepADBNetworkBlocklist.blockSsid(context, HOME);
        rowOf(open(), KITCHEN).performClick();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNull(button(dialog, context.getString(R.string.network_decision_trust)));
        assertNotNull(button(dialog, context.getString(R.string.networks_action_unblock)));
    }

    /**
     * The dialog of a row offers exactly what changes that row's state: a trusted one can be
     * blocked, no longer trusted, renamed or its name blocked; a blocked one can be lifted (and,
     * where the name allows, trusted). Each button also stays away where it does not apply.
     */
    @Test
    public void theDialogOfARowOffersOnlyWhatAppliesToItsState() {
        KeepADBTrustedNetwork.addBssid(context, KITCHEN, HOME);
        NetworkListActivity activity = open();

        rowOf(activity, KITCHEN).performClick();
        AlertDialog trusted = ShadowAlertDialog.getLatestAlertDialog();
        for (int label : new int[] {R.string.networks_action_block, R.string.networks_action_untrust,
                R.string.networks_action_rename}) {
            assertNotNull(context.getString(label), button(trusted, context.getString(label)));
        }
        assertNotNull(button(trusted, context.getString(R.string.network_decision_block_name, HOME)));
        assertNotNull(button(trusted, context.getString(android.R.string.cancel)));
        assertNull("Nothing to lift on a trusted one", button(trusted,
                context.getString(R.string.networks_action_unblock)));
        assertNull("Nothing to trust on a trusted one", button(trusted,
                context.getString(R.string.network_decision_trust)));
        // Cancel changes nothing.
        button(trusted, context.getString(android.R.string.cancel)).performClick();
        assertEquals(1, KeepADBTrustedNetwork.getEntries(context).size());
        assertTrue(KeepADBNetworkBlocklist.isEmpty(context));

        KeepADBNetworkListActions.blockAccessPoint(context, KITCHEN);
        rowOf(open(), KITCHEN).performClick();
        AlertDialog blocked = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(button(blocked, context.getString(R.string.network_decision_trust)));
        assertNotNull(button(blocked, context.getString(R.string.networks_action_unblock)));
        assertNull("Nothing to block twice", button(blocked, context.getString(R.string.networks_action_block)));
        assertNull(button(blocked, context.getString(R.string.networks_action_untrust)));
    }

    @Test
    public void anOwnNameGivenInTheDialogShowsInTheRowAndKeepsTheAddressBelowIt() {
        KeepADBTrustedNetwork.addBssid(context, KITCHEN, HOME);
        NetworkListActivity activity = open();

        rowOf(activity, KITCHEN).performClick();
        press(R.string.networks_action_rename);
        AlertDialog rename = ShadowAlertDialog.getLatestAlertDialog();
        android.widget.EditText input = (android.widget.EditText) allViews(rename.getWindow().getDecorView())
                .stream().filter(v -> v instanceof android.widget.EditText).findFirst().get();
        input.setText("Kueche");
        rename.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        View row = rowOf(activity, KITCHEN);
        assertTrue(description(row), description(row).startsWith("Kueche"));
        assertTrue("The address stays visible in the row", texts(row).contains(KITCHEN.toUpperCase(Locale.ROOT)));
        assertEquals("The own name is display only: trust and name are untouched", HOME,
                KeepADBTrustedNetwork.getEntries(context).get(0).ssid());
    }

    // --- The current network and the embedded decision (#766) --------------------------------------

    @Test
    public void anUndecidedCurrentNetworkIsAskedThroughTheEmbeddedDecisionNotAnOwnDialog() {
        connectTo(CAFE, CAFE_AP);

        NetworkListActivity activity = open();

        NetworkDecisionView decision = findDecision(activity);
        assertNotNull("The #766 component is embedded", decision);
        assertTrue(decision.isBound());
        assertEquals("Decide later has nothing to close here", View.GONE,
                decision.findViewById(R.id.decision_later).getVisibility());
        assertTrue("The current card says unknown",
                texts(currentCard(activity)).contains(context.getString(R.string.networks_badge_unknown)));
        assertNull("No dialog of the list asks the question", ShadowAlertDialog.getLatestAlertDialog());
        assertTrue("Nothing is decided by looking", KeepADBTrustedNetwork.getEntries(context).isEmpty()
                && KeepADBNetworkBlocklist.isEmpty(context));

        activity.findViewById(R.id.decision_trust).performClick();
        ShadowLooper.idleMainLooper();

        assertEquals(1, KeepADBTrustedNetwork.getEntries(context).size());
        assertTrue("The card now says trusted",
                texts(currentCard(activity)).contains(context.getString(R.string.networks_badge_trusted)));
        assertNull("The question is gone once answered", findDecision(activity));
    }

    @Test
    public void blockingTheNameFromTheEmbeddedDecisionBlocksItAndTheCardSaysSo() {
        connectTo(CAFE, CAFE_AP);
        NetworkListActivity activity = open();

        activity.findViewById(R.id.decision_block_name).performClick();
        ShadowLooper.idleMainLooper();

        assertTrue(KeepADBNetworkBlocklist.isSsidBlocked(context, CAFE));
        String card = texts(currentCard(activity));
        assertTrue(card, card.contains(context.getString(R.string.network_badge_blocked)));
        assertTrue(card, card.contains(context.getString(R.string.networks_reason_name, CAFE)));
        assertNull(findDecision(activity));
    }

    @Test
    public void aNetworkTrustedOnlyByTheLegacyPolicyStillOffersTheDecisionAndSaysWhy() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        connectTo(CAFE, CAFE_AP);

        NetworkListActivity activity = open();

        String card = texts(currentCard(activity));
        assertTrue(card, card.contains(context.getString(R.string.networks_current_legacy)));
        assertNotNull(findDecision(activity));
        // The comfort switch says it has no effect there; in the strict policy it does not say so.
        assertTrue(everythingShown(activity).contains(context.getString(R.string.networks_comfort_no_effect)));
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        assertFalse(everythingShown(open()).contains(context.getString(R.string.networks_comfort_no_effect)));
    }

    @Test
    public void noWifiAndAnUnreadableNetworkEachHaveTheirOwnCardAndFix() {
        connectToUnreadable();
        shadowOf((Application) context).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
        NetworkListActivity unreadable = open();
        String card = texts(currentCard(unreadable));
        assertTrue(card, card.contains(context.getString(R.string.network_status_unreadable)));
        assertTrue(card, card.contains(context.getString(R.string.network_cause_permission_missing)));
        Button grant = buttonIn(currentCard(unreadable),
                context.getString(R.string.location_permission_panel_grant_button));
        assertNotNull(grant);
        grant.performClick();
        assertTrue(shadowOf(unreadable).getLastRequestedPermission().requestedPermissions.length > 0);
        assertEquals(Manifest.permission.ACCESS_FINE_LOCATION,
                shadowOf(unreadable).getLastRequestedPermission().requestedPermissions[0]);
        assertNull("No decision is asked about a network that cannot be read", findDecision(unreadable));

        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> false);
        connectNothing();
        String none = texts(currentCard(open()));
        assertTrue(none, none.contains(context.getString(R.string.network_status_no_wifi)));
        assertNotNull(buttonIn(currentCard(open()), context.getString(R.string.network_action_wifi_settings)));
    }

    // --- Saved list: empty state, order, folding ---------------------------------------------

    @Test
    public void withNothingSavedTheEmptyStateExplainsAndTheCurrentCardStays() {
        connectTo(HOME, KITCHEN);
        NetworkListActivity activity = open();
        String all = everythingShown(activity);
        assertTrue(all, all.contains(context.getString(R.string.networks_empty_title)));
        assertTrue(all, all.contains(context.getString(R.string.networks_empty_text)));
        assertNotNull(findDecision(activity));

        KeepADBTrustedNetwork.addBssid(context, KITCHEN, HOME);
        assertFalse(everythingShown(open()).contains(context.getString(R.string.networks_empty_title)));
    }

    @Test
    public void aLongGroupFoldsToThreeRowsAndExpandsOnRequest() {
        List<String> addresses = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            String bssid = String.format(Locale.ROOT, "aa:bb:cc:00:00:%02x", i);
            addresses.add(bssid);
            KeepADBTrustedNetwork.addBssid(context, bssid, HOME);
        }
        NetworkListActivity activity = open();

        int shown = 0;
        for (String bssid : addresses) if (findRow(activity, bssid) != null) shown++;
        assertEquals(NetworkListRenderer.COLLAPSED_ROWS, shown);
        TextView more = findText(activity.getWindow().getDecorView(),
                context.getString(R.string.wifi_aps_show_more_button, 3));
        assertNotNull(more);
        more.performClick();
        shown = 0;
        for (String bssid : addresses) if (findRow(activity, bssid) != null) shown++;
        assertEquals(6, shown);

        // Control: five access points are not folded.
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        for (int i = 1; i <= 5; i++) {
            KeepADBTrustedNetwork.addBssid(context, String.format(Locale.ROOT, "aa:bb:cc:00:00:%02x", i), HOME);
        }
        activity = open();
        shown = 0;
        for (int i = 1; i <= 5; i++) {
            if (findRow(activity, String.format(Locale.ROOT, "aa:bb:cc:00:00:%02x", i)) != null) shown++;
        }
        assertEquals(5, shown);
    }

    // --- Comfort switch and force note -------------------------------------------------------

    @Test
    public void theComfortSwitchIsTheOneSwitchBetweenStrictAndConvenienceAndNamesTheLevel() {
        NetworkListActivity activity = open();
        Switch comfort = findSwitch(activity);
        assertNotNull(comfort);
        assertFalse(comfort.isChecked());
        assertTrue(everythingShown(activity).contains(context.getString(R.string.networks_level,
                context.getString(R.string.force_level_maximum))));

        comfort.performClick();

        assertTrue(KeepADBTrustedNetwork.isTrustByNameEnabled(context));
        assertTrue("The level follows the switch", everythingShown(activity).contains(
                context.getString(R.string.networks_level, context.getString(R.string.force_level_balanced))));
        findSwitch(activity).performClick();
        assertFalse(KeepADBTrustedNetwork.isTrustByNameEnabled(context));
    }

    @Test
    public void theForceNoteIsShownExactlyWhileForceModeIsOn() {
        assertFalse(everythingShown(open()).contains(context.getString(R.string.networks_force_note)));

        KeepADBForceMode.setClockForTesting(new KeepADBForceTestSupport.TestClock());
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));
        String all = everythingShown(open());
        assertTrue(all, all.contains(context.getString(R.string.networks_force_note)));

        KeepADBForceMode.endNow(context);
        assertFalse(everythingShown(open()).contains(context.getString(R.string.networks_force_note)));
        KeepADBForceMode.resetForTesting();
    }

    // --- Privacy mode ------------------------------------------------------------------------

    @Test
    public void privacyModeLeavesNoWifiNameAndNoAddressInAnyViewOrDescriptionAndOffShowsThem() {
        connectTo(CAFE, CAFE_AP);
        KeepADBTrustedNetwork.addBssid(context, KITCHEN, HOME);
        KeepADBTrustedNetwork.addBssid(context, HALL, HOME);
        KeepADBTrustedNetwork.setCustomName(context, KeepADBTrustedNetwork.getEntries(context).get(0).id,
                "Kueche");
        KeepADBNetworkBlocklist.blockSsid(context, "Gast");
        KeepADBNetworkBlocklist.blockBssid(context, "ee:ee:ee:ee:ee:01");
        KeepADBBssidHistory.recordObservation(context, "Hotel-WLAN", "ee:ee:ee:ee:ee:01");
        String[] secrets = {CAFE, HOME, "Gast", "Hotel-WLAN", "Kueche",
                CAFE_AP, CAFE_AP.toUpperCase(Locale.ROOT), KITCHEN, KITCHEN.toUpperCase(Locale.ROOT),
                HALL.toUpperCase(Locale.ROOT), "EE:EE:EE:EE:EE:01", "11:22:33", "11:22:44", "78:9A:BC",
                "ee:ee"};

        KeepADBPreferences.setPrivacyModeEnabled(context, false);
        String visible = everythingShown(open());
        for (String secret : secrets) {
            if (!"ee:ee".equals(secret)) {
                assertTrue("Control: with privacy off '" + secret + "' is shown: " + visible,
                        visible.toLowerCase(Locale.ROOT).contains(secret.toLowerCase(Locale.ROOT)));
            }
        }

        KeepADBPreferences.setPrivacyModeEnabled(context, true);
        NetworkListActivity activity = open();
        String hidden = everythingShown(activity).toLowerCase(Locale.ROOT);
        for (String secret : secrets) {
            assertFalse("Privacy mode must hide '" + secret + "': " + hidden,
                    hidden.contains(secret.toLowerCase(Locale.ROOT)));
        }
        assertNull("The embedded decision, which shows the name on purpose, is not even built",
                findDecision(activity));
        assertTrue(hidden.contains(context.getString(R.string.networks_hidden_title).toLowerCase(Locale.ROOT)));

        // The placeholder's button does what the eye does: privacy off, the list is back.
        findButton(activity.getWindow().getDecorView(), context.getString(R.string.networks_hidden_show))
                .performClick();
        ShadowLooper.idleMainLooper();
        assertFalse(KeepADBPreferences.isPrivacyModeEnabled(context));
        assertTrue(everythingShown(activity).contains(CAFE));
    }

    // --- Helpers -----------------------------------------------------------------------------

    private NetworkListActivity open() {
        return open(NetworkListActivity.VIEW_NETWORKS);
    }

    private NetworkListActivity open(String view) {
        NetworkListActivity activity = Robolectric.buildActivity(NetworkListActivity.class,
                NetworkListActivity.intent(context, view)).setup().get();
        ShadowLooper.idleMainLooper();
        return activity;
    }

    private void connectTo(String ssid, String bssid) {
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = ShadowWifiInfo.newInstance();
        shadowOf(info).setSSID(ssid);
        shadowOf(info).setBSSID(bssid);
        shadowOf(wifiManager).setConnectionInfo(info);
    }

    private void connectToUnreadable() {
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = ShadowWifiInfo.newInstance();
        shadowOf(info).setSSID(WifiManager.UNKNOWN_SSID);
        shadowOf(info).setBSSID(KeepADBNetworkIdentity.REDACTED_BSSID);
        shadowOf(wifiManager).setConnectionInfo(info);
    }

    private void connectNothing() {
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        shadowOf(wifiManager).setConnectionInfo(null);
    }

    /** Presses a button of the latest dialog by its label; every button closes it first. */
    private void press(int labelRes, Object... args) {
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("A dialog is open", dialog);
        String label = context.getString(labelRes, args);
        Button button = button(dialog, label);
        assertNotNull("Button '" + label + "' in " + texts(dialog.getWindow().getDecorView()), button);
        button.performClick();
        ShadowLooper.idleMainLooper();
    }

    private static Button button(AlertDialog dialog, String label) {
        return findButton(dialog.getWindow().getDecorView(), label);
    }

    private static Button buttonIn(View root, String label) {
        return findButton(root, label);
    }

    private static Button findButton(View root, String label) {
        for (View view : allViews(root)) {
            if (view instanceof Button && label.contentEquals(((Button) view).getText())) {
                return (Button) view;
            }
        }
        return null;
    }

    private static TextView findText(View root, String text) {
        for (View view : allViews(root)) {
            if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) {
                return (TextView) view;
            }
        }
        return null;
    }

    private static Switch findSwitch(Activity activity) {
        for (View view : allViews(activity.getWindow().getDecorView())) {
            if (view instanceof Switch) return (Switch) view;
        }
        return null;
    }

    private static NetworkDecisionView findDecision(Activity activity) {
        for (View view : allViews(activity.getWindow().getDecorView())) {
            if (view instanceof NetworkDecisionView) return (NetworkDecisionView) view;
        }
        return null;
    }

    /** The saved row whose spoken line contains {@code bssid}, or null. */
    private static View findRow(Activity activity, String bssid) {
        for (View view : allViews(activity.getWindow().getDecorView())) {
            if (view.isClickable() && view.getContentDescription() != null
                    && view.getContentDescription().toString().toLowerCase(Locale.ROOT)
                            .contains(bssid.toLowerCase(Locale.ROOT))) {
                return view;
            }
        }
        return null;
    }

    private static View rowOf(Activity activity, String bssid) {
        View row = findRow(activity, bssid);
        assertNotNull("Row of " + bssid + " in " + everythingShown(activity), row);
        return row;
    }

    private static View groupHeader(Activity activity, String ssid) {
        for (View view : allViews(activity.getWindow().getDecorView())) {
            if (view.isClickable() && view.getContentDescription() != null
                    && view.getContentDescription().toString().contains(ssid)
                    && view instanceof LinearLayout && ((LinearLayout) view).getMinimumHeight() > 0
                    && !isRowDescription(view.getContentDescription().toString())) {
                return view;
            }
        }
        return null;
    }

    private static boolean isRowDescription(String description) {
        return description.toLowerCase(Locale.ROOT).matches(".*[0-9a-f]{2}:[0-9a-f]{2}:[0-9a-f]{2}.*");
    }

    /** The card below the "current network" heading: the first panel after it. */
    private static View currentCard(Activity activity) {
        ViewGroup root = activity.findViewById(R.id.networks_root);
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child instanceof TextView && ((TextView) child).getText().toString()
                    .equalsIgnoreCase(RuntimeEnvironment.getApplication()
                            .getString(R.string.networks_section_current))) {
                return root.getChildAt(i + 1);
            }
        }
        throw new AssertionError("No current network section: " + everythingShown(activity));
    }

    /**
     * The status words a row or card shows: every text that is exactly one word of the status
     * vocabulary and every part of the spoken line. A sentence that merely mentions a status
     * ("Trusted again once ...") is no status.
     */
    private static List<String> statusWords(View root) {
        List<String> words = new ArrayList<>();
        for (View view : allViews(root)) {
            if (view instanceof TextView) words.add(((TextView) view).getText().toString());
            if (view.getContentDescription() != null) {
                for (String part : view.getContentDescription().toString().split(", ")) words.add(part);
            }
        }
        return words;
    }

    private static String description(View view) {
        CharSequence description = view.getContentDescription();
        return description == null ? "" : description.toString();
    }

    private static String texts(View root) {
        StringBuilder out = new StringBuilder();
        collect(root, out);
        return out.toString();
    }

    private static String everythingShown(Activity activity) {
        return texts(activity.getWindow().getDecorView());
    }

    /** Every text and every description a view can carry: what a screen reader or the eye reaches. */
    private static void collect(View view, StringBuilder out) {
        if (view instanceof TextView) {
            out.append(((TextView) view).getText()).append('\n');
            if (((TextView) view).getHint() != null) out.append(((TextView) view).getHint()).append('\n');
        }
        if (view.getContentDescription() != null) out.append(view.getContentDescription()).append('\n');
        if (view.getStateDescription() != null) out.append(view.getStateDescription()).append('\n');
        if (view.getTooltipText() != null) out.append(view.getTooltipText()).append('\n');
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), out);
        }
    }

    private static List<View> allViews(View root) {
        List<View> views = new ArrayList<>();
        views.add(root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) views.addAll(allViews(group.getChildAt(i)));
        }
        return views;
    }
}
