package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
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
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
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
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.shadows.ShadowWifiInfo;

/**
 * #654: the three management views behind the Network card -- allowed access points, recently
 * prevented re-enabling and observed access points. They replace the former inline list, the
 * trusted-only filter and the "recently blocked" dialog.
 *
 * <p>Two invariants carry most of the weight here and are asserted on both sides: allowing an
 * access point grants exactly that access point and never switches Wireless Debugging on
 * ({@link #allowingNeverSwitchesWirelessDebuggingOn}), and the privacy mode hides every name and
 * address in every view while the off state shows them
 * ({@link #privacyModeHidesNamesAndAddressesInEveryView}).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class NetworkListActivityTest {

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

    // --- entry and structure ------------------------------------------------------------------

    @Test
    public void homeScreenHasNoAccessPointLists() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        assertNull(activity.findViewById(R.id.wifi_aps_current_row));
        assertNull(activity.findViewById(R.id.wifi_aps_list));
        assertNull(activity.findViewById(R.id.network_allowed_row));
    }

    @Test
    public void anUnknownOrMissingViewExtraFallsBackToTheAllowedView() {
        NetworkListActivity missing = Robolectric.buildActivity(NetworkListActivity.class,
                new Intent(context, NetworkListActivity.class)).setup().get();
        assertEquals(NetworkListActivity.VIEW_ALLOWED, missing.getListView());

        NetworkListActivity garbage = open("does-not-exist");
        assertEquals(NetworkListActivity.VIEW_ALLOWED, garbage.getListView());
        assertEquals(context.getString(R.string.network_row_allowed),
                ((TextView) garbage.findViewById(R.id.network_list_title)).getText().toString());
    }

    @Test
    public void eachViewHasItsOwnHeadingTitleAndIntro() {
        String[] views = {NetworkListActivity.VIEW_ALLOWED, NetworkListActivity.VIEW_PREVENTED,
                NetworkListActivity.VIEW_OBSERVED};
        int[] titles = {R.string.network_row_allowed, R.string.network_row_prevented,
                R.string.network_view_observed_title};
        int[] intros = {R.string.network_view_allowed_intro, R.string.network_view_prevented_intro,
                R.string.network_view_observed_intro};
        for (int i = 0; i < views.length; i++) {
            NetworkListActivity activity = open(views[i]);
            TextView title = activity.findViewById(R.id.network_list_title);
            assertEquals(context.getString(titles[i]), title.getText().toString());
            assertTrue("The title is an accessibility heading", title.isAccessibilityHeading());
            assertEquals(context.getString(intros[i]),
                    ((TextView) activity.findViewById(R.id.network_list_intro)).getText().toString());
        }
    }

    @Test
    public void theBackButtonClosesTheView() {
        NetworkListActivity activity = open(NetworkListActivity.VIEW_ALLOWED);
        assertEquals(context.getString(R.string.back),
                activity.findViewById(R.id.btn_back).getContentDescription());
        activity.findViewById(R.id.btn_back).performClick();
        assertTrue(activity.isFinishing());
    }

    // --- allowed access points ---------------------------------------------------------------

    @Test
    public void allowedViewShowsTheCurrentAccessPointFirstWithNameAddressAndStatus() {
        connectTo("HomeMesh", "aa:aa:aa:aa:aa:01");
        KeepADBTrustedNetwork.addBssid(context, "cc:cc:cc:cc:cc:01", "Office");

        NetworkListActivity activity = open(NetworkListActivity.VIEW_ALLOWED);

        View current = activity.findViewById(R.id.wifi_aps_current_row);
        assertEquals(View.VISIBLE, current.getVisibility());
        List<String> currentTexts = allText(current);
        assertTrue(joined(currentTexts), currentTexts.stream().anyMatch(t -> t.contains("HomeMesh")));
        assertTrue(joined(currentTexts),
                currentTexts.stream().anyMatch(t -> t.contains("AA:AA:AA:AA:AA:01")));
        assertTrue("The decision is stated in words, not only in colour: " + joined(currentTexts),
                currentTexts.contains(context.getString(R.string.network_status_not_allowed)));
        assertEquals(context.getString(R.string.wifi_ssids_add_button),
                findButton(current).getText().toString());

        List<String> listed = allText(activity.findViewById(R.id.wifi_aps_list));
        assertTrue(joined(listed), listed.contains("Office"));
        assertEquals("Only the explicitly allowed access point follows the current one", 1,
                ((LinearLayout) activity.findViewById(R.id.wifi_aps_list)).getChildCount());
    }

    @Test
    public void theCurrentRowIsMarkedAsTheCurrentConnection() {
        connectTo("HomeMesh", "aa:aa:aa:aa:aa:01");

        NetworkListActivity activity = open(NetworkListActivity.VIEW_ALLOWED);

        List<String> texts = allText(activity.findViewById(R.id.wifi_aps_current_row));
        assertTrue(joined(texts),
                texts.contains(context.getString(R.string.wifi_aps_current_badge) + " \u00b7 HomeMesh"));
    }

    @Test
    public void removingOneAllowedAccessPointLeavesTheOthersUntouched() {
        KeepADBTrustedNetwork.addBssid(context, "cc:cc:cc:cc:cc:01", "First");
        KeepADBTrustedNetwork.addBssid(context, "cc:cc:cc:cc:cc:02", "Second");
        NetworkListActivity activity = open(NetworkListActivity.VIEW_ALLOWED);

        Button removeSecond = findButtonWithDescription(activity.findViewById(R.id.wifi_aps_list),
                context.getString(R.string.network_action_remove_ap_accessibility, "Second"));
        assertNotNull(removeSecond);
        removeSecond.performClick();
        ShadowLooper.idleMainLooper();

        List<KeepADBTrustedNetwork.Entry> entries = KeepADBTrustedNetwork.getEntries(context);
        assertEquals(1, entries.size());
        assertEquals("First", entries.get(0).label);
        assertEquals("cc:cc:cc:cc:cc:01", entries.get(0).bssid);
    }

    /** Toasts and the mesh question quote a name only while the privacy mode is off. */
    @Test
    public void toastsAndTheMeshQuestionQuoteNamesOnlyWhenPrivacyIsOff() {
        preparedForAnAutomaticEnable("MeshHome", "aa:bb:cc:dd:ee:03");
        KeepADBBssidHistory.recordObservation(context, "MeshHome", "aa:bb:cc:dd:ee:04");

        for (boolean privacy : new boolean[] {false, true}) {
            KeepADBTrustedNetwork.setSsidMatchingEnabled(context, false);
            context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit()
                    .remove("trusted_network_ids").commit();
            KeepADBPreferences.setPrivacyModeEnabled(context, privacy);
            ShadowToast.reset();
            NetworkListActivity activity = open(NetworkListActivity.VIEW_ALLOWED);

            findButton(activity.findViewById(R.id.wifi_aps_current_row)).performClick();
            ShadowLooper.idleMainLooper();

            String toast = ShadowToast.getTextOfLatestToast();
            AlertDialog mesh = ShadowAlertDialog.getLatestAlertDialog();
            String meshText = String.valueOf(shadowOf(mesh).getMessage());
            assertEquals("privacy=" + privacy + " toast: " + toast, !privacy, toast.contains("MeshHome"));
            assertEquals("privacy=" + privacy + " mesh: " + meshText, !privacy,
                    meshText.contains("MeshHome"));
            mesh.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            ShadowLooper.idleMainLooper();

            // The same holds for the removal message of the row that was just allowed.
            ShadowToast.reset();
            findButton(activity.findViewById(R.id.wifi_aps_current_row)).performClick();
            ShadowLooper.idleMainLooper();
            String removed = ShadowToast.getTextOfLatestToast();
            assertEquals("privacy=" + privacy + " removal: " + removed, !privacy,
                    removed.contains("MeshHome"));
        }
    }

    @Test
    public void allowedViewNamesAnAllowedCurrentAccessPointAsSuch() {
        connectTo("HomeMesh", "aa:aa:aa:aa:aa:01");
        KeepADBTrustedNetwork.addBssid(context, "aa:aa:aa:aa:aa:01", "HomeMesh");

        NetworkListActivity activity = open(NetworkListActivity.VIEW_ALLOWED);

        View current = activity.findViewById(R.id.wifi_aps_current_row);
        assertTrue(allText(current).contains(context.getString(R.string.network_status_allowed_ap)));
        assertEquals(context.getString(R.string.wifi_ssids_remove_button),
                findButton(current).getText().toString());
        assertEquals("The allowed current access point is not listed a second time", 0,
                ((LinearLayout) activity.findViewById(R.id.wifi_aps_list)).getChildCount());
    }

    @Test
    public void unknownCurrentIdentityShowsTheUnavailableMessageInsteadOfARow() {
        NetworkListActivity activity = open(NetworkListActivity.VIEW_ALLOWED);

        List<String> texts = allText(activity.findViewById(R.id.wifi_aps_current_row));
        assertTrue(joined(texts), texts.contains(context.getString(R.string.wifi_aps_current_unknown)));
        assertNull("No action exists for an unreadable identity",
                findButton(activity.findViewById(R.id.wifi_aps_current_row)));
    }

    /**
     * #492/#654: an allowlisted access point that no other source supplies -- never observed, not
     * the current connection -- is still listed, with its own remove action, so it can always be
     * revoked.
     */
    @Test
    public void allowedViewListsAllowlistedAccessPointsThatWereNeverObserved() {
        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:ff", "MyOfficeNetwork");

        NetworkListActivity activity = open(NetworkListActivity.VIEW_ALLOWED);

        LinearLayout list = activity.findViewById(R.id.wifi_aps_list);
        List<String> rendered = allText(list);
        assertTrue(rendered.toString(), rendered.contains("MyOfficeNetwork"));
        assertTrue(rendered.toString(), rendered.contains("AA:BB:CC:DD:EE:FF"));
        List<Button> buttons = findButtons(list);
        assertEquals(1, buttons.size());
        assertEquals(context.getString(R.string.network_action_remove_ap_accessibility,
                "MyOfficeNetwork"), buttons.get(0).getContentDescription().toString());

        buttons.get(0).performClick();
        ShadowLooper.idleMainLooper();

        assertTrue("The row action removes exactly that entry",
                KeepADBTrustedNetwork.getEntries(context).isEmpty());
        assertEquals(0, list.getChildCount());
    }

    @Test
    public void allowedViewNeverListsObservedButNotAllowedAccessPoints() {
        KeepADBBssidHistory.recordObservation(context, "OfficeMesh", "bb:bb:bb:bb:bb:01");
        KeepADBTrustedNetwork.addBssid(context, "cc:cc:cc:cc:cc:01", "Cafe");

        NetworkListActivity allowed = open(NetworkListActivity.VIEW_ALLOWED);
        List<String> allowedTexts = allText(allowed.findViewById(R.id.wifi_aps_list));
        assertFalse(joined(allowedTexts), allowedTexts.contains("OfficeMesh"));
        assertTrue(joined(allowedTexts), allowedTexts.contains("Cafe"));

        NetworkListActivity observed = open(NetworkListActivity.VIEW_OBSERVED);
        List<String> observedTexts = allText(observed.findViewById(R.id.wifi_aps_list));
        assertTrue(joined(observedTexts), observedTexts.contains("OfficeMesh"));
        assertFalse("An allowlist entry nobody observed is no observation: " + joined(observedTexts),
                observedTexts.contains("Cafe"));
    }

    @Test
    public void allowedViewExplainsEmptinessAndOnlyThen() {
        NetworkListActivity empty = open(NetworkListActivity.VIEW_ALLOWED);
        TextView emptyView = empty.findViewById(R.id.wifi_aps_empty);
        assertEquals(View.VISIBLE, emptyView.getVisibility());
        assertEquals(context.getString(R.string.network_view_allowed_empty),
                emptyView.getText().toString());

        KeepADBTrustedNetwork.addBssid(context, "cc:cc:cc:cc:cc:01", "Cafe");
        NetworkListActivity filled = open(NetworkListActivity.VIEW_ALLOWED);
        assertEquals(View.GONE, filled.findViewById(R.id.wifi_aps_empty).getVisibility());
    }

    /**
     * #654: in "all networks" mode the saved list stays reachable but is explained as not
     * counting there; in allowlist mode that hint must not be shown.
     */
    @Test
    public void allowedViewHintsThatTheListIsInactiveOnlyInAllWifiMode() {
        KeepADBTrustedNetwork.addBssid(context, "cc:cc:cc:cc:cc:01", "Cafe");

        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        NetworkListActivity allWifi = open(NetworkListActivity.VIEW_ALLOWED);
        TextView hint = allWifi.findViewById(R.id.network_list_inactive_hint);
        assertEquals(View.VISIBLE, hint.getVisibility());
        assertEquals(context.getString(R.string.network_list_inactive_hint,
                        context.getString(R.string.network_mode_option_aps)),
                hint.getText().toString());
        assertTrue("The saved entry stays reachable",
                allText(allWifi.findViewById(R.id.wifi_aps_list)).contains("Cafe"));

        // #654 visual acceptance: with the name matching on, the second option of the mode choice
        // reads "Allowed access points and Wi-Fi names", and the hint must say exactly that.
        KeepADBTrustedNetwork.setSsidMatchingEnabled(context, true);
        TextView namedHint = open(NetworkListActivity.VIEW_ALLOWED)
                .findViewById(R.id.network_list_inactive_hint);
        assertEquals(context.getString(R.string.network_list_inactive_hint,
                        context.getString(R.string.network_mode_option_aps_names)),
                namedHint.getText().toString());
        assertTrue(namedHint.getText().toString()
                .contains(context.getString(R.string.network_mode_option_aps_names)));
        assertFalse("No truncated mode name", namedHint.getText().toString().contains("\u2026"));
        KeepADBTrustedNetwork.setSsidMatchingEnabled(context, false);

        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        NetworkListActivity allowlist = open(NetworkListActivity.VIEW_ALLOWED);
        assertEquals(View.GONE, allowlist.findViewById(R.id.network_list_inactive_hint).getVisibility());
    }

    @Test
    public void allowThenRemoveOnTheCurrentRowRoundTripsTheAllowlist() {
        connectTo("HomeMesh", "aa:aa:aa:aa:aa:01");
        NetworkListActivity activity = open(NetworkListActivity.VIEW_ALLOWED);

        Button allow = findButton(activity.findViewById(R.id.wifi_aps_current_row));
        assertEquals(context.getString(R.string.wifi_ssids_add_button), allow.getText().toString());
        assertEquals(context.getString(R.string.network_action_allow_ap_accessibility, "HomeMesh"),
                allow.getContentDescription().toString());
        allow.performClick();

        assertEquals(1, KeepADBTrustedNetwork.getEntries(context).size());
        assertEquals("AA:AA:AA:AA:AA:01", KeepADBTrustedNetwork.getEntries(context).get(0).bssid);
        Button remove = findButton(activity.findViewById(R.id.wifi_aps_current_row));
        assertEquals(context.getString(R.string.wifi_ssids_remove_button), remove.getText().toString());

        remove.performClick();
        assertTrue(KeepADBTrustedNetwork.getEntries(context).isEmpty());
    }

    /**
     * #654: allowing an access point afterwards grants exactly that and nothing else. The setup is
     * the one in which the former trust-and-connect path switched Wireless Debugging on at once
     * (all networks accepted, Keep-Alive on, Wi-Fi connected, switched off): with the grant-only
     * path the entry is stored and the gateway is never written.
     */
    @Test
    public void allowingNeverSwitchesWirelessDebuggingOn() {
        KeepADBFakeSettingsGateway gateway = preparedForAnAutomaticEnable("HomeMesh",
                "aa:aa:aa:aa:aa:01");
        NetworkListActivity activity = open(NetworkListActivity.VIEW_ALLOWED);

        findButton(activity.findViewById(R.id.wifi_aps_current_row)).performClick();
        ShadowLooper.idleMainLooper();

        assertEquals("The allowance itself must be stored", 1,
                KeepADBTrustedNetwork.getEntries(context).size());
        assertTrue("No enable action may be started by allowing: " + gateway.writes,
                gateway.writes.isEmpty());
        assertFalse(KeepADB.isEnabled(context));
    }

    @Test
    public void allowingFromTheObservedViewGrantsOnlyThatAccessPoint() {
        KeepADBFakeSettingsGateway gateway = preparedForAnAutomaticEnable("HomeMesh",
                "aa:aa:aa:aa:aa:01");
        KeepADBBssidHistory.recordObservation(context, "OfficeMesh", "bb:bb:bb:bb:bb:01");
        KeepADBBssidHistory.recordObservation(context, "OfficeMesh2", "bb:bb:bb:bb:bb:02");
        NetworkListActivity activity = open(NetworkListActivity.VIEW_OBSERVED);

        Button allowOffice = findButtonWithDescription(activity.findViewById(R.id.wifi_aps_list),
                context.getString(R.string.network_action_allow_ap_accessibility, "OfficeMesh"));
        assertNotNull(allowOffice);
        allowOffice.performClick();
        ShadowLooper.idleMainLooper();

        List<KeepADBTrustedNetwork.Entry> entries = KeepADBTrustedNetwork.getEntries(context);
        assertEquals("Only the access point that was clicked is allowed", 1, entries.size());
        assertEquals("BB:BB:BB:BB:BB:01", entries.get(0).bssid);
        assertTrue(gateway.writes.isEmpty());
    }

    @Test
    public void allowAndRemoveButtonsUseDistinctStyles() {
        connectTo("HomeMesh", "aa:aa:aa:aa:aa:01");
        NetworkListActivity activity = open(NetworkListActivity.VIEW_ALLOWED);

        Button allow = findButton(activity.findViewById(R.id.wifi_aps_current_row));
        int allowColor = allow.getCurrentTextColor();
        assertEquals(context.getColor(R.color.title_yellow), allowColor);
        allow.performClick();

        Button remove = findButton(activity.findViewById(R.id.wifi_aps_current_row));
        assertEquals(context.getColor(R.color.text_yellow), remove.getCurrentTextColor());
        assertNotEquals(allowColor, remove.getCurrentTextColor());
    }

    /**
     * #654: allowing the current access point still offers the other observed access points of the
     * same name (#266) -- an explicit, separate question. Accepting allows exactly those and, like
     * every allow action here, switches nothing on; declining leaves only the current one.
     */
    @Test
    public void meshOfferAllowsTheOthersOnlyOnAcceptanceAndNeverSwitchesAnythingOn() {
        KeepADBFakeSettingsGateway gateway = preparedForAnAutomaticEnable("MeshHome",
                "aa:bb:cc:dd:ee:03");
        KeepADBBssidHistory.recordObservation(context, "MeshHome", "aa:bb:cc:dd:ee:04");
        NetworkListActivity activity = open(NetworkListActivity.VIEW_ALLOWED);

        findButton(activity.findViewById(R.id.wifi_aps_current_row)).performClick();
        ShadowLooper.idleMainLooper();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("The mesh question must be asked", dialog);
        assertEquals("Only the current access point so far", 1,
                KeepADBTrustedNetwork.getEntries(context).size());

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        assertEquals(2, KeepADBTrustedNetwork.getEntries(context).size());
        assertTrue(gateway.writes.isEmpty());
    }

    @Test
    public void decliningTheMeshOfferKeepsOnlyTheCurrentAccessPoint() {
        preparedForAnAutomaticEnable("MeshHome", "aa:bb:cc:dd:ee:03");
        KeepADBBssidHistory.recordObservation(context, "MeshHome", "aa:bb:cc:dd:ee:04");
        NetworkListActivity activity = open(NetworkListActivity.VIEW_ALLOWED);

        findButton(activity.findViewById(R.id.wifi_aps_current_row)).performClick();
        ShadowLooper.idleMainLooper();
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        ShadowLooper.idleMainLooper();

        List<KeepADBTrustedNetwork.Entry> entries = KeepADBTrustedNetwork.getEntries(context);
        assertEquals(1, entries.size());
        assertEquals("AA:BB:CC:DD:EE:03", entries.get(0).bssid);
    }

    // --- observed access points -------------------------------------------------------------

    @Test
    public void observedViewListsObservedAccessPointsBelowTheCurrentOne() {
        KeepADBBssidHistory.recordObservation(context, "OfficeMesh", "bb:bb:bb:bb:bb:01");
        connectTo("HomeMesh", "aa:aa:aa:aa:aa:01");

        NetworkListActivity activity = open(NetworkListActivity.VIEW_OBSERVED);

        assertTrue(allText(activity.findViewById(R.id.wifi_aps_current_row)).stream()
                .anyMatch(t -> t.contains("HomeMesh")));
        LinearLayout list = activity.findViewById(R.id.wifi_aps_list);
        assertEquals(1, list.getChildCount());
        List<String> texts = allText(list);
        assertTrue(joined(texts), texts.contains("OfficeMesh"));
        assertTrue("Each observed row states its decision in words: " + joined(texts),
                texts.contains(context.getString(R.string.network_status_not_allowed)));
        assertEquals(View.GONE, activity.findViewById(R.id.wifi_aps_empty).getVisibility());
    }

    @Test
    public void observedViewExplainsItselfWhenNothingElseWasObserved() {
        connectTo("HomeMesh", "aa:aa:aa:aa:aa:01");

        NetworkListActivity activity = open(NetworkListActivity.VIEW_OBSERVED);

        assertEquals(0, ((LinearLayout) activity.findViewById(R.id.wifi_aps_list)).getChildCount());
        TextView empty = activity.findViewById(R.id.wifi_aps_empty);
        assertEquals(View.VISIBLE, empty.getVisibility());
        assertEquals(context.getString(R.string.wifi_aps_empty), empty.getText().toString());
        assertEquals(View.GONE, activity.findViewById(R.id.wifi_aps_toggle).getVisibility());
    }

    @Test
    public void shortObservedListShowsEverythingWithoutAShowMoreEntry() {
        for (int i = 0; i < NetworkListActivity.COLLAPSED_ROWS; i++) {
            KeepADBBssidHistory.recordObservation(context, "Neighbor" + i, otherBssid(i));
        }
        connectTo("HomeMesh", "aa:aa:aa:aa:aa:01");

        NetworkListActivity activity = open(NetworkListActivity.VIEW_OBSERVED);

        assertEquals(NetworkListActivity.COLLAPSED_ROWS,
                ((LinearLayout) activity.findViewById(R.id.wifi_aps_list)).getChildCount());
        assertEquals(View.GONE, activity.findViewById(R.id.wifi_aps_toggle).getVisibility());
    }

    @Test
    public void longObservedListCollapsesBehindShowMoreAndExpandsAgain() {
        int extra = 5;
        int total = NetworkListActivity.COLLAPSED_ROWS + extra;
        for (int i = 0; i < total; i++) {
            KeepADBBssidHistory.recordObservation(context, "Neighbor" + i, otherBssid(i));
        }
        connectTo("HomeMesh", "aa:aa:aa:aa:aa:01");

        NetworkListActivity activity = open(NetworkListActivity.VIEW_OBSERVED);
        LinearLayout list = activity.findViewById(R.id.wifi_aps_list);
        TextView toggle = activity.findViewById(R.id.wifi_aps_toggle);
        assertEquals(NetworkListActivity.COLLAPSED_ROWS, list.getChildCount());
        assertEquals(View.VISIBLE, toggle.getVisibility());
        assertEquals(context.getString(R.string.wifi_aps_show_more_button, extra),
                toggle.getText().toString());

        toggle.performClick();
        assertEquals(total, list.getChildCount());
        assertEquals(context.getString(R.string.wifi_aps_show_less_button), toggle.getText().toString());
        assertTrue("The current connection stays visible while expanded",
                allText(activity.findViewById(R.id.wifi_aps_current_row)).stream()
                        .anyMatch(t -> t.contains("HomeMesh")));

        toggle.performClick();
        assertEquals(NetworkListActivity.COLLAPSED_ROWS, list.getChildCount());
        assertTrue("The current connection stays visible while collapsed",
                allText(activity.findViewById(R.id.wifi_aps_current_row)).stream()
                        .anyMatch(t -> t.contains("HomeMesh")));
    }

    // --- recently prevented re-enabling -----------------------------------------------------

    @Test
    public void preventedViewListsNewestFirstWithReasonAndAllowsExactlyOne() {
        KeepADBFakeSettingsGateway gateway = preparedForAnAutomaticEnable("Cafe-WLAN",
                "aa:bb:cc:dd:ee:01");
        KeepADBBlockedNetworkHistory.record(context,
                new KeepADBNetworkIdentity("Cafe-WLAN", "aa:bb:cc:dd:ee:01"), 1_000L);
        KeepADBBlockedNetworkHistory.record(context,
                new KeepADBNetworkIdentity("Hotel-WLAN", "aa:bb:cc:dd:ee:02"), 2_000L);

        NetworkListActivity activity = open(NetworkListActivity.VIEW_PREVENTED);

        LinearLayout list = activity.findViewById(R.id.wifi_aps_list);
        List<String> rendered = allText(list);
        assertTrue(rendered.toString(), rendered.contains("Cafe-WLAN"));
        assertTrue(rendered.toString(), rendered.contains("Hotel-WLAN"));
        assertTrue("Newest first: the access point the user just failed on is at the top",
                rendered.indexOf("Hotel-WLAN") < rendered.indexOf("Cafe-WLAN"));
        assertEquals("Every entry states its reason", 2, count(rendered,
                context.getString(R.string.network_view_prevented_reason)));
        assertTrue("Nothing is allowed merely by looking at the list",
                KeepADBTrustedNetwork.getEntries(context).isEmpty());

        List<Button> allowButtons = findButtons(list);
        assertEquals(2, allowButtons.size());
        allowButtons.get(0).performClick();
        ShadowLooper.idleMainLooper();

        List<KeepADBTrustedNetwork.Entry> allowed = KeepADBTrustedNetwork.getEntries(context);
        assertEquals(1, allowed.size());
        assertEquals("aa:bb:cc:dd:ee:02", allowed.get(0).bssid);
        assertEquals("Hotel-WLAN", allowed.get(0).label);
        List<KeepADBBlockedNetworkHistory.Entry> remaining =
                KeepADBBlockedNetworkHistory.getEntries(context);
        assertEquals("The allowed one leaves the history; the other stays for a later decision", 1,
                remaining.size());
        assertEquals("aa:bb:cc:dd:ee:01", remaining.get(0).bssid);
        assertTrue("Allowing afterwards starts no enable action: " + gateway.writes,
                gateway.writes.isEmpty());
        assertEquals(1, ((LinearLayout) activity.findViewById(R.id.wifi_aps_list)).getChildCount());
    }

    @Test
    public void allowingFromTheHistoryClearsOnlyThatAccessPointsPromptMarker() {
        String bssid = "aa:bb:cc:dd:ee:01";
        preparedForAnAutomaticEnable("Cafe-WLAN", bssid);
        assertTrue(KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));
        assertFalse(KeepADBNetworkTrustPrompt.shouldPrompt(context, bssid, System.currentTimeMillis()));
        NetworkListActivity activity = open(NetworkListActivity.VIEW_PREVENTED);

        findButtons(activity.findViewById(R.id.wifi_aps_list)).get(0).performClick();
        ShadowLooper.idleMainLooper();

        assertTrue("Allowing clears this access point's own pending prompt marker (#474)",
                KeepADBNetworkTrustPrompt.shouldPrompt(context, bssid, System.currentTimeMillis()));
    }

    @Test
    public void preventedViewExplainsItselfWhenNothingWasPrevented() {
        NetworkListActivity activity = open(NetworkListActivity.VIEW_PREVENTED);

        TextView empty = activity.findViewById(R.id.wifi_aps_empty);
        assertEquals(View.VISIBLE, empty.getVisibility());
        assertEquals(context.getString(R.string.network_view_prevented_empty), empty.getText().toString());
        assertTrue(KeepADBTrustedNetwork.getEntries(context).isEmpty());
    }

    /** #654: an automatic history, not a block list -- no manual entry, no block action. */
    @Test
    public void preventedViewOffersNeitherManualEntriesNorBlockActions() {
        KeepADBBlockedNetworkHistory.record(context,
                new KeepADBNetworkIdentity("Cafe-WLAN", "aa:bb:cc:dd:ee:01"), 1_000L);
        KeepADBBlockedNetworkHistory.record(context,
                new KeepADBNetworkIdentity("Hotel-WLAN", "aa:bb:cc:dd:ee:02"), 2_000L);

        NetworkListActivity activity = open(NetworkListActivity.VIEW_PREVENTED);

        View root = activity.getWindow().getDecorView();
        assertTrue("A history must not offer a text field to add entries",
                findViews(root, EditText.class).isEmpty());
        List<Button> buttons = findViews(root, Button.class);
        assertEquals("One action per entry, and it only allows", 2, buttons.size());
        for (Button button : buttons) {
            assertEquals(context.getString(R.string.wifi_ssids_add_button), button.getText().toString());
        }
        assertEquals(context.getString(R.string.network_view_prevented_intro),
                ((TextView) activity.findViewById(R.id.network_list_intro)).getText().toString());
    }

    @Test
    public void anUnnamedHistoryEntryIsTitledByItsAddressAndNeverShowsAPlaceholderName() {
        KeepADBBlockedNetworkHistory.record(context,
                new KeepADBNetworkIdentity(WifiManager.UNKNOWN_SSID, "aa:bb:cc:dd:ee:09"), 1_000L);

        NetworkListActivity activity = open(NetworkListActivity.VIEW_PREVENTED);

        List<String> texts = allText(activity.findViewById(R.id.wifi_aps_list));
        assertTrue(joined(texts), texts.contains("aa:bb:cc:dd:ee:09"));
        assertFalse(joined(texts), texts.contains(WifiManager.UNKNOWN_SSID));
    }

    // --- privacy, sizes, descriptions --------------------------------------------------------

    /**
     * #654: with the privacy mode on, no view shows a network name or an address -- not in a row,
     * not in an accessibility description; with it off the same data is shown. Both sides are
     * asserted so a view that ignores the setting and a view that always hides both fail.
     */
    @Test
    public void privacyModeHidesNamesAndAddressesInEveryView() {
        connectTo("HomeMesh", "aa:bb:cc:dd:ee:01");
        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:02", "Cafe-WLAN");
        KeepADBBssidHistory.recordObservation(context, "OfficeMesh", "aa:bb:cc:dd:ee:03");
        KeepADBBlockedNetworkHistory.record(context,
                new KeepADBNetworkIdentity("Hotel-WLAN", "aa:bb:cc:dd:ee:04"), 1_000L);
        String[] secrets = {"HomeMesh", "Cafe-WLAN", "OfficeMesh", "Hotel-WLAN",
                "AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02", "AA:BB:CC:DD:EE:03", "AA:BB:CC:DD:EE:04",
                "aa:bb:cc:dd:ee:04", "EE:01", "EE:02", "EE:03", "EE:04"};
        String[] views = {NetworkListActivity.VIEW_ALLOWED, NetworkListActivity.VIEW_OBSERVED,
                NetworkListActivity.VIEW_PREVENTED};

        KeepADBPreferences.setPrivacyModeEnabled(context, false);
        for (String view : views) {
            String everything = everythingShown(open(view));
            boolean showsSomething = false;
            for (String secret : secrets) showsSomething |= everything.contains(secret);
            assertTrue("With privacy off the " + view + " view shows its data: " + everything,
                    showsSomething);
        }

        KeepADBPreferences.setPrivacyModeEnabled(context, true);
        for (String view : views) {
            NetworkListActivity activity = open(view);
            String everything = everythingShown(activity);
            for (String secret : secrets) {
                assertFalse("Privacy mode must hide '" + secret + "' in the " + view + " view: "
                        + everything, everything.contains(secret));
            }
            assertEquals("The hidden state is explained", View.VISIBLE,
                    activity.findViewById(R.id.network_list_privacy_hint).getVisibility());
        }
        assertEquals(View.GONE, openWithPrivacy(false).findViewById(R.id.network_list_privacy_hint)
                .getVisibility());
    }

    @Test
    public void hiddenRowsStayDistinguishableByPositionWithoutRevealingAnything() {
        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:02", "Cafe-WLAN");
        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:03", "Hotel-WLAN");
        KeepADBPreferences.setPrivacyModeEnabled(context, true);

        NetworkListActivity activity = open(NetworkListActivity.VIEW_ALLOWED);

        String hidden = context.getString(R.string.network_privacy_name_hidden);
        List<String> texts = allText(activity.findViewById(R.id.wifi_aps_list));
        assertTrue(joined(texts), texts.contains(hidden + " #1"));
        assertTrue(joined(texts), texts.contains(hidden + " #2"));
        for (Button button : findButtons(activity.findViewById(R.id.wifi_aps_list))) {
            String description = button.getContentDescription().toString();
            assertTrue("Buttons stay distinguishable for TalkBack: " + description,
                    description.contains(hidden + " #"));
        }
    }

    /**
     * #654: a hidden BSSID shows exactly its first and its last octet in every view. Checked on
     * both sides: a view that masks the last octet as well and a view that lets a middle octet
     * through both fail, because every address-shaped text must be {@code xx:*:*:*:*:yy} with the
     * octets of the fixture and no middle octet may appear anywhere.
     */
    @Test
    public void hiddenAddressesShowExactlyTheFirstAndTheLastOctetInEveryView() {
        connectTo("HomeMesh", "de:11:22:33:44:ad");
        KeepADBTrustedNetwork.addBssid(context, "c0:55:66:77:88:0f", "Cafe-WLAN");
        KeepADBBssidHistory.recordObservation(context, "OfficeMesh", "a1:99:98:97:96:b2");
        KeepADBBlockedNetworkHistory.record(context,
                new KeepADBNetworkIdentity("Hotel-WLAN", "f2:a3:a4:a5:a6:3c"), 1_000L);
        String[] middleOctets = {"11", "22", "33", "44", "55", "66", "77", "88", "99", "98", "97",
                "96", "a3", "a4", "a5", "a6"};
        KeepADBPreferences.setPrivacyModeEnabled(context, true);

        assertMaskedAddresses(open(NetworkListActivity.VIEW_ALLOWED), middleOctets,
                "de:*:*:*:*:ad", "c0:*:*:*:*:0f");
        assertMaskedAddresses(open(NetworkListActivity.VIEW_OBSERVED), middleOctets,
                "de:*:*:*:*:ad", "a1:*:*:*:*:b2");
        assertMaskedAddresses(open(NetworkListActivity.VIEW_PREVENTED), middleOctets,
                "f2:*:*:*:*:3c");

        // The off state shows the complete addresses, nothing masked.
        KeepADBPreferences.setPrivacyModeEnabled(context, false);
        String shown = everythingShown(open(NetworkListActivity.VIEW_ALLOWED)).toLowerCase(
                java.util.Locale.ROOT);
        assertTrue(shown, shown.contains("de:11:22:33:44:ad"));
        assertTrue(shown, shown.contains("c0:55:66:77:88:0f"));
        assertFalse(shown, shown.contains(":*:"));
    }

    private static void assertMaskedAddresses(Activity activity, String[] middleOctets,
                                              String... expected) {
        String everything = everythingShown(activity).toLowerCase(java.util.Locale.ROOT);
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\b[0-9a-f*]{1,2}(?::[0-9a-f*]{1,2}){5}\\b").matcher(everything);
        java.util.Set<String> found = new java.util.TreeSet<>();
        while (matcher.find()) found.add(matcher.group());
        assertEquals(everything, new java.util.TreeSet<>(java.util.Arrays.asList(expected)), found);
        for (String octet : middleOctets) {
            assertFalse("A middle octet '" + octet + "' is visible: " + everything,
                    everything.contains(":" + octet + ":"));
        }
    }

    @Test
    public void everyRowActionIsAtLeast48dpHighAndHasAContextualDescription() {
        connectTo("HomeMesh", "aa:aa:aa:aa:aa:01");
        KeepADBTrustedNetwork.addBssid(context, "cc:cc:cc:cc:cc:01", "Cafe");
        KeepADBBssidHistory.recordObservation(context, "OfficeMesh", "bb:bb:bb:bb:bb:01");
        KeepADBBlockedNetworkHistory.record(context,
                new KeepADBNetworkIdentity("Hotel-WLAN", "aa:bb:cc:dd:ee:04"), 1_000L);
        int minimum = (int) (48 * context.getResources().getDisplayMetrics().density);
        String[] views = {NetworkListActivity.VIEW_ALLOWED, NetworkListActivity.VIEW_OBSERVED,
                NetworkListActivity.VIEW_PREVENTED};

        for (String view : views) {
            List<Button> buttons = findViews(open(view).getWindow().getDecorView(), Button.class);
            assertFalse("The " + view + " view has actions in this fixture", buttons.isEmpty());
            for (Button button : buttons) {
                assertTrue(view + ": action must be at least 48dp high",
                        button.getMinHeight() >= minimum);
                assertNotNull(view + ": action needs a description naming its target",
                        button.getContentDescription());
                assertNotEquals(button.getText().toString(), button.getContentDescription().toString());
            }
        }
    }

    /** #654: a new activity must be declared, and it must not be reachable from other apps. */
    @Test
    public void theActivityIsRegisteredAndNotExported() throws Exception {
        android.content.pm.ActivityInfo info = context.getPackageManager().getActivityInfo(
                new android.content.ComponentName(context, NetworkListActivity.class), 0);
        assertFalse("Other apps must not be able to open the management views", info.exported);
        assertTrue(info.enabled);
    }

    // --- helpers ------------------------------------------------------------------------------

    private NetworkListActivity open(String view) {
        ActivityController<NetworkListActivity> controller = Robolectric.buildActivity(
                NetworkListActivity.class, NetworkListActivity.intent(context, view)).setup();
        ShadowLooper.idleMainLooper();
        return controller.get();
    }

    private NetworkListActivity openWithPrivacy(boolean privacy) {
        KeepADBPreferences.setPrivacyModeEnabled(context, privacy);
        return open(NetworkListActivity.VIEW_ALLOWED);
    }

    /** The setup in which the former trust-and-connect path switched Wireless Debugging on. */
    private KeepADBFakeSettingsGateway preparedForAnAutomaticEnable(String ssid, String bssid) {
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS,
                android.Manifest.permission.POST_NOTIFICATIONS);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(false);
        KeepADB.setGatewayForTesting(gateway);
        connectTo(ssid, bssid);
        return gateway;
    }

    private void connectTo(String ssid, String bssid) {
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = ShadowWifiInfo.newInstance();
        shadowOf(info).setSSID(ssid);
        shadowOf(info).setBSSID(bssid);
        shadowOf(wifiManager).setConnectionInfo(info);
    }

    /** Every visible string and every accessibility description of the activity, joined. */
    private static String everythingShown(Activity activity) {
        StringBuilder all = new StringBuilder();
        collectAll(activity.getWindow().getDecorView(), all);
        return all.toString();
    }

    private static void collectAll(View view, StringBuilder out) {
        if (view instanceof TextView) out.append(((TextView) view).getText()).append('\n');
        if (view.getContentDescription() != null) out.append(view.getContentDescription()).append('\n');
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectAll(group.getChildAt(i), out);
        }
    }

    private static List<String> allText(View root) {
        List<String> texts = new ArrayList<>();
        for (TextView view : findViews(root, TextView.class)) texts.add(view.getText().toString());
        return texts;
    }

    private static Button findButton(View root) {
        List<Button> buttons = findViews(root, Button.class);
        return buttons.isEmpty() ? null : buttons.get(0);
    }

    private static List<Button> findButtons(View root) {
        return findViews(root, Button.class);
    }

    private static Button findButtonWithDescription(View root, String description) {
        for (Button button : findViews(root, Button.class)) {
            if (description.contentEquals(button.getContentDescription())) return button;
        }
        return null;
    }

    private static <T extends View> List<T> findViews(View root, Class<T> type) {
        List<T> result = new ArrayList<>();
        collect(root, type, result);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static <T extends View> void collect(View view, Class<T> type, List<T> out) {
        if (type.isInstance(view)) out.add((T) view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), type, out);
        }
    }

    private static int count(List<String> values, String wanted) {
        int n = 0;
        for (String value : values) if (value.equals(wanted)) n++;
        return n;
    }

    private static String joined(List<String> texts) {
        return String.join(" | ", texts);
    }

    /** Distinct BSSIDs for synthetic "other" access points, disjoint from the "aa:aa:..." range. */
    private static String otherBssid(int index) {
        return String.format(java.util.Locale.US, "bb:bb:bb:bb:bb:%02x", index);
    }
}
