package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import org.robolectric.shadows.ShadowScanResult;
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
        assertEquals(context.getString(R.string.network_view_allowed_title),
                ((TextView) garbage.findViewById(R.id.network_list_title)).getText().toString());
    }

    @Test
    public void eachViewHasItsOwnHeadingTitleAndIntro() {
        String[] views = {NetworkListActivity.VIEW_ALLOWED, NetworkListActivity.VIEW_PREVENTED,
                NetworkListActivity.VIEW_OBSERVED};
        int[] titles = {R.string.network_view_allowed_title, R.string.network_row_prevented,
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

    /** #686: the mesh question must not outlive the activity (rotation would leak its window). */
    @Test
    public void theMeshQuestionIsDismissedWhenTheViewIsDestroyed() {
        preparedForAnAutomaticEnable("MeshHome", "aa:bb:cc:dd:ee:03");
        KeepADBBssidHistory.recordObservation(context, "MeshHome", "aa:bb:cc:dd:ee:04");
        ActivityController<NetworkListActivity> controller = Robolectric.buildActivity(
                NetworkListActivity.class, NetworkListActivity.intent(context,
                        NetworkListActivity.VIEW_ALLOWED)).setup();
        ShadowLooper.idleMainLooper();
        NetworkListActivity activity = controller.get();

        findButton(activity.findViewById(R.id.wifi_aps_current_row)).performClick();
        ShadowLooper.idleMainLooper();
        AlertDialog mesh = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(mesh);
        assertTrue("The mesh question is on screen", mesh.isShowing());

        controller.destroy();

        assertFalse("Destroying the activity dismisses the mesh question", mesh.isShowing());
    }

    @Test
    public void dismissingTheMeshQuestionDropsTheListActivityReference() throws Exception {
        ActivityController<NetworkListActivity> controller = openWithMeshQuestion();
        NetworkListActivity activity = controller.get();
        AlertDialog mesh = getField(activity, "activeMeshDialog");
        assertNotNull(mesh);

        mesh.dismiss();
        ShadowLooper.idleMainLooper();

        assertNull("Dismissal drops the list activity's reference",
                getField(activity, "activeMeshDialog"));
        controller.destroy();
    }

    @Test
    public void aStaleMeshDismissDoesNotClearAReplacementReference() throws Exception {
        ActivityController<NetworkListActivity> controller = openWithMeshQuestion();
        NetworkListActivity activity = controller.get();
        AlertDialog original = getField(activity, "activeMeshDialog");
        AlertDialog replacement = new AlertDialog.Builder(activity).create();
        setField(activity, "activeMeshDialog", replacement);

        original.dismiss();
        ShadowLooper.idleMainLooper();

        assertSame("An older dialog's dismissal must not clear the newer reference", replacement,
                getField(activity, "activeMeshDialog"));
        controller.destroy();
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
        assertTrue(rendered.toString(), rendered.contains(
                "AA:BB:CC:DD:EE:FF"));
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
                observedTexts.stream().anyMatch(text -> text.contains("Cafe")));
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
        assertTrue("The prevented list formats BSSIDs like the other lists: " + rendered,
                rendered.stream().anyMatch(text -> text.contains("AA:BB:CC:DD:EE:02")));
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
        assertTrue(joined(texts), texts.stream().anyMatch(text -> text.contains("AA:BB:CC:DD:EE:09")));
        assertFalse(joined(texts), texts.contains(WifiManager.UNKNOWN_SSID));
    }

    // --- numbers, own names and bands (#714) ----------------------------------------------------

    private static final String DOT = " · ";

    /** Several access points of one SSID are told apart by their stable number and their BSSID. */
    @Test
    public void everyAllowedAccessPointShowsItsOwnNumberAndItsAddress() {
        KeepADBTrustedNetwork.addBssid(context, "aa:aa:aa:aa:aa:01", "HomeMesh");
        KeepADBTrustedNetwork.addBssid(context, "aa:aa:aa:aa:aa:02", "HomeMesh");
        KeepADBTrustedNetwork.addBssid(context, "aa:aa:aa:aa:aa:03", "HomeMesh");

        NetworkListActivity activity = open(NetworkListActivity.VIEW_ALLOWED);

        List<String> texts = allText(activity.findViewById(R.id.wifi_aps_list));
        assertTrue(joined(texts), texts.contains("HomeMesh (1)"));
        assertTrue(joined(texts), texts.contains("HomeMesh (2)"));
        assertTrue(joined(texts), texts.contains("HomeMesh (3)"));
        for (int i = 1; i <= 3; i++) {
            assertTrue(joined(texts), texts.contains("AA:AA:AA:AA:AA:0" + i));
        }
    }

    /**
     * #722: line 1 is the network name with the access point number in brackets behind it, line 2
     * the address with the band; no row starts with a "#n" entry number or contains a " · " dot.
     * A name carried by one access point only has no number at all.
     */
    @Test
    public void rowTitlesCarryTheNumberBehindTheNameAndNeverInFront() {
        KeepADBTrustedNetwork.addBssid(context, "aa:aa:aa:aa:aa:01", "HomeMesh");
        KeepADBTrustedNetwork.addBssid(context, "cc:cc:cc:cc:cc:01", "Cafe");
        KeepADBTrustedNetwork.addBssid(context, "aa:aa:aa:aa:aa:02", "HomeMesh");

        NetworkListActivity activity = open(NetworkListActivity.VIEW_ALLOWED);

        List<String> texts = allText(activity.findViewById(R.id.wifi_aps_list));
        assertTrue(joined(texts), texts.contains("HomeMesh (1)"));
        assertTrue(joined(texts), texts.contains("HomeMesh (3)"));
        assertTrue("A single access point of a name has no number: " + joined(texts),
                texts.contains("Cafe"));
        assertTrue(joined(texts), texts.contains("AA:AA:AA:AA:AA:02"));
        for (String text : texts) {
            assertFalse("No leading entry number: " + text, text.startsWith("#"));
            assertFalse("No dot separator: " + text, text.contains(DOT));
        }
    }

    /**
     * #729: a name that is shared only with an access point outside the drawn list gets no number.
     * The second "Office" is merely observed (not allowed), so the allowed list holds one.
     */
    @Test
    public void aNameRepeatedOnlyOutsideTheListGetsNoNumber() {
        KeepADBTrustedNetwork.addBssid(context, "aa:aa:aa:aa:aa:01", "Office");
        KeepADBBssidHistory.recordObservation(context, "Office", "aa:aa:aa:aa:aa:02");
        connectTo("Office", "aa:aa:aa:aa:aa:03");

        NetworkListActivity activity = open(NetworkListActivity.VIEW_ALLOWED);

        List<String> texts = allText(activity.findViewById(R.id.wifi_aps_list));
        assertTrue(joined(texts), texts.contains("Office"));
        for (String text : texts) {
            assertFalse("No number for a single entry: " + text, text.startsWith("Office ("));
        }
    }

    /** #729: the same name twice in the drawn list keeps the stable numbers behind it. */
    @Test
    public void aNameRepeatedInsideTheListKeepsItsNumbers() {
        KeepADBTrustedNetwork.addBssid(context, "aa:aa:aa:aa:aa:01", "Office");
        KeepADBTrustedNetwork.addBssid(context, "cc:cc:cc:cc:cc:01", "Cafe");
        KeepADBTrustedNetwork.addBssid(context, "aa:aa:aa:aa:aa:02", "Office");

        NetworkListActivity activity = open(NetworkListActivity.VIEW_ALLOWED);

        List<String> texts = allText(activity.findViewById(R.id.wifi_aps_list));
        assertTrue(joined(texts), texts.contains("Office (1)"));
        assertTrue(joined(texts), texts.contains("Office (3)"));
        assertTrue(joined(texts), texts.contains("Cafe"));
    }

    /**
     * #722: with the privacy mode on, the only "#" of a row is the one of the hidden name; the
     * access point number follows it in brackets and the same name keeps the same "#n".
     */
    @Test
    public void hiddenRowTitlesHaveExactlyOneHashAndTheApNumberInBrackets() {
        KeepADBTrustedNetwork.addBssid(context, "aa:aa:aa:aa:aa:01", "HomeMesh");
        KeepADBTrustedNetwork.addBssid(context, "cc:cc:cc:cc:cc:01", "Cafe");
        KeepADBTrustedNetwork.addBssid(context, "aa:aa:aa:aa:aa:02", "HomeMesh");
        KeepADBPreferences.setPrivacyModeEnabled(context, true);

        NetworkListActivity activity = open(NetworkListActivity.VIEW_ALLOWED);

        String hidden = context.getString(R.string.network_privacy_name_hidden);
        List<String> texts = allText(activity.findViewById(R.id.wifi_aps_list));
        assertTrue(joined(texts), texts.contains(hidden + " #1 (1)"));
        assertTrue(joined(texts), texts.contains(hidden + " #1 (3)"));
        assertTrue(joined(texts), texts.contains(hidden + " #2"));
        for (String text : texts) {
            assertFalse("No leading entry number: " + text, text.startsWith("#"));
            assertFalse("No dot separator: " + text, text.contains(DOT));
            assertTrue("At most one hash per text: " + text,
                    text.indexOf('#') == text.lastIndexOf('#'));
        }
        assertFalse(joined(texts), joined(texts).contains("HomeMesh"));
    }

    @Test
    public void theNumbersOfTheRowsStayWhenOtherEntriesAreRemovedOrAdded() {
        KeepADBTrustedNetwork.addBssid(context, "aa:aa:aa:aa:aa:01", "HomeMesh");
        KeepADBTrustedNetwork.addBssid(context, "aa:aa:aa:aa:aa:02", "HomeMesh");
        KeepADBTrustedNetwork.addBssid(context, "aa:aa:aa:aa:aa:03", "HomeMesh");
        ActivityController<NetworkListActivity> controller = Robolectric.buildActivity(
                NetworkListActivity.class, NetworkListActivity.intent(context,
                        NetworkListActivity.VIEW_ALLOWED)).setup();
        ShadowLooper.idleMainLooper();
        NetworkListActivity activity = controller.get();
        LinearLayout list = activity.findViewById(R.id.wifi_aps_list);

        // Remove the middle row through its own button.
        findButtons(list).get(1).performClick();
        ShadowLooper.idleMainLooper();

        List<String> afterRemoval = allText(list);
        assertTrue(joined(afterRemoval), afterRemoval.contains("HomeMesh (1)"));
        assertTrue("The row behind the gap keeps its number: " + joined(afterRemoval),
                afterRemoval.contains("HomeMesh (3)"));
        assertFalse(joined(afterRemoval), afterRemoval.contains("HomeMesh (2)"));
        assertEquals(2, list.getChildCount());

        KeepADBTrustedNetwork.addBssid(context, "aa:aa:aa:aa:aa:04", "HomeMesh");
        controller.pause().resume();

        List<String> afterAdding = allText(list);
        assertTrue(joined(afterAdding), afterAdding.contains("HomeMesh (1)"));
        assertTrue(joined(afterAdding), afterAdding.contains("HomeMesh (3)"));
        assertTrue("A new entry never takes the number of a removed one: " + joined(afterAdding),
                afterAdding.contains("HomeMesh (4)"));
        assertFalse(joined(afterAdding), afterAdding.contains("HomeMesh (2)"));
    }

    @Test
    public void theCurrentRowNamesTheBadgeBeforeTheNumber() {
        connectTo("HomeMesh", "aa:aa:aa:aa:aa:01");
        KeepADBTrustedNetwork.addBssid(context, "aa:aa:aa:aa:aa:01", "HomeMesh");
        KeepADBTrustedNetwork.addBssid(context, "aa:aa:aa:aa:aa:02", "HomeMesh");

        NetworkListActivity activity = open(NetworkListActivity.VIEW_ALLOWED);

        List<String> texts = allText(activity.findViewById(R.id.wifi_aps_current_row));
        assertTrue(joined(texts), texts.contains(
                context.getString(R.string.wifi_aps_current_badge) + DOT + "HomeMesh (1)"));
    }

    /** Only a stored entry has a number and a name to edit; an observation is neither. */
    @Test
    public void rowsThatAreNoStoredEntryHaveNeitherANumberNorAPencil() {
        KeepADBBssidHistory.recordObservation(context, "OfficeMesh", "bb:bb:bb:bb:bb:01");
        KeepADBTrustedNetwork.addBssid(context, "cc:cc:cc:cc:cc:01", "Cafe");
        KeepADBBlockedNetworkHistory.record(context,
                new KeepADBNetworkIdentity("Hotel-WLAN", "aa:bb:cc:dd:ee:04"), 1_000L);

        NetworkListActivity observed = open(NetworkListActivity.VIEW_OBSERVED);
        List<String> observedTexts = allText(observed.findViewById(R.id.wifi_aps_list));
        assertTrue(joined(observedTexts), observedTexts.contains("OfficeMesh"));
        assertTrue(findViews(observed.findViewById(R.id.wifi_aps_list), ImageButton.class).isEmpty());

        NetworkListActivity prevented = open(NetworkListActivity.VIEW_PREVENTED);
        assertTrue(findViews(prevented.findViewById(R.id.wifi_aps_list), ImageButton.class).isEmpty());
        assertFalse(joined(allText(prevented.findViewById(R.id.wifi_aps_list))).contains("#1"));

        NetworkListActivity allowed = open(NetworkListActivity.VIEW_ALLOWED);
        assertEquals("Exactly the stored entry has a pencil", 1,
                findViews(allowed.findViewById(R.id.wifi_aps_list), ImageButton.class).size());
    }

    @Test
    public void theNumberOfAStoredEntryAlsoShowsInTheObservedView() {
        KeepADBBssidHistory.recordObservation(context, "OfficeMesh", "bb:bb:bb:bb:bb:01");
        KeepADBTrustedNetwork.addBssid(context, "cc:cc:cc:cc:cc:01", "Cafe");
        KeepADBTrustedNetwork.addBssid(context, "bb:bb:bb:bb:bb:01", "OfficeMesh");

        NetworkListActivity observed = open(NetworkListActivity.VIEW_OBSERVED);

        List<String> texts = allText(observed.findViewById(R.id.wifi_aps_list));
        assertTrue("Entry 2 is the observed one: " + joined(texts),
                texts.contains("OfficeMesh"));
    }

    @Test
    public void thePencilSitsDirectlyNextToTheNameIsAtLeast48dpAndIsNoLabelledButton() {
        KeepADBTrustedNetwork.addBssid(context, "cc:cc:cc:cc:cc:01", "Cafe");

        NetworkListActivity activity = open(NetworkListActivity.VIEW_ALLOWED);

        LinearLayout list = activity.findViewById(R.id.wifi_aps_list);
        ImageButton pencil = findViews(list, ImageButton.class).get(0);
        ViewGroup titleRow = (ViewGroup) pencil.getParent();
        TextView name = null;
        for (TextView candidate : findViews(titleRow, TextView.class)) {
            if (candidate.getText().toString().equals("Cafe")) name = candidate;
        }
        assertNotNull("The name is in the same row as the pencil", name);
        assertEquals("The pencil is the very next view after the name",
                titleRow.indexOfChild(name) + 1, titleRow.indexOfChild(pencil));
        int minimum = (int) (48 * context.getResources().getDisplayMetrics().density);
        assertTrue(pencil.getLayoutParams().width >= minimum);
        assertTrue(pencil.getLayoutParams().height >= minimum);
        assertEquals(context.getString(R.string.network_ap_rename_accessibility, "Cafe"),
                pencil.getContentDescription().toString());
        assertEquals("The only labelled button of the row is still its allow/remove action", 1,
                findButtons(list).size());
    }

    @Test
    public void tappingThePencilOpensASimpleDialogWithOkAndCancelAndNoResetYet() {
        KeepADBTrustedNetwork.addBssid(context, "cc:cc:cc:cc:cc:01", "Cafe");
        NetworkListActivity activity = open(NetworkListActivity.VIEW_ALLOWED);

        AlertDialog dialog = openNameDialog(activity);

        assertTrue(dialog.isShowing());
        assertEquals(context.getString(R.string.network_ap_rename_title, 1),
                String.valueOf(shadowOf(dialog).getTitle()));
        assertFalse("Dialog title shows the entry number in brackets, not as #n",
                String.valueOf(shadowOf(dialog).getTitle()).contains("#"));
        EditText field = nameField(dialog);
        assertEquals("No own name yet: the field starts empty", "", field.getText().toString());
        assertEquals(context.getString(R.string.network_ap_rename_hint), field.getHint().toString());
        assertEquals(context.getString(android.R.string.ok),
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
        assertEquals(context.getString(android.R.string.cancel),
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());
        assertEquals("There is nothing to reset yet", View.GONE,
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).getVisibility());
    }

    @Test
    public void savingANameShowsItInPlaceOfTheNetworkNameWhileTheSsidStaysVisible() {
        KeepADBFakeSettingsGateway gateway = preparedForAnAutomaticEnable("Cafe", "aa:00:00:00:00:99");
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBTrustedNetwork.addBssid(context, "cc:cc:cc:cc:cc:01", "Cafe");
        NetworkListActivity activity = open(NetworkListActivity.VIEW_ALLOWED);

        AlertDialog dialog = openNameDialog(activity);
        nameField(dialog).setText("Kitchen");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        assertEquals("Kitchen", KeepADBTrustedNetwork.getEntries(context).get(0).customName);
        List<String> texts = allText(activity.findViewById(R.id.wifi_aps_list));
        // #729: "Cafe" occurs once in this list (the connected one is not allowed), so no number.
        assertTrue(joined(texts), texts.contains("Kitchen"));
        assertTrue("The unchanged SSID stays in the row: " + joined(texts), texts.contains("Cafe"));
        assertTrue(joined(texts), texts.contains("CC:CC:CC:CC:CC:01"));
        // Trust and the action are exactly what they were.
        KeepADBTrustedNetwork.Entry entry = KeepADBTrustedNetwork.getEntries(context).get(0);
        assertEquals("cc:cc:cc:cc:cc:01", entry.bssid);
        assertEquals("Cafe", entry.label);
        assertEquals(KeepADBTrustedNetwork.MODE_ALLOWLIST, KeepADBTrustedNetwork.getMode(context));
        assertEquals(context.getString(R.string.wifi_ssids_remove_button),
                findButtons(activity.findViewById(R.id.wifi_aps_list)).get(0).getText().toString());
        assertTrue("Naming switches nothing on: " + gateway.writes, gateway.writes.isEmpty());
    }

    @Test
    public void aSavedNameCanBeChangedAndThenReset() {
        KeepADBTrustedNetwork.addBssid(context, "cc:cc:cc:cc:cc:01", "Cafe");
        KeepADBTrustedNetwork.setCustomName(context,
                KeepADBTrustedNetwork.getEntries(context).get(0).id, "Kitchen");
        NetworkListActivity activity = open(NetworkListActivity.VIEW_ALLOWED);

        AlertDialog change = openNameDialog(activity);
        assertEquals("The field offers the current name", "Kitchen",
                nameField(change).getText().toString());
        assertEquals(View.VISIBLE, change.getButton(AlertDialog.BUTTON_NEUTRAL).getVisibility());
        nameField(change).setText("Garage");
        change.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertEquals("Garage", KeepADBTrustedNetwork.getEntries(context).get(0).customName);
        assertTrue(allText(activity.findViewById(R.id.wifi_aps_list)).contains("Garage"));

        AlertDialog reset = openNameDialog(activity);
        assertEquals(context.getString(R.string.network_ap_rename_reset),
                reset.getButton(AlertDialog.BUTTON_NEUTRAL).getText().toString());
        reset.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        ShadowLooper.idleMainLooper();

        assertNull(KeepADBTrustedNetwork.getEntries(context).get(0).customName);
        List<String> texts = allText(activity.findViewById(R.id.wifi_aps_list));
        assertTrue("Back to the default display: " + joined(texts), texts.contains("Cafe"));
        assertFalse(joined(texts), texts.contains("Garage"));
        assertEquals("The SSID is no longer repeated as a second line", 1, count(texts, "Cafe"));
    }

    @Test
    public void cancelAndDismissDiscardWhatWasTyped() {
        KeepADBTrustedNetwork.addBssid(context, "cc:cc:cc:cc:cc:01", "Cafe");
        NetworkListActivity activity = open(NetworkListActivity.VIEW_ALLOWED);

        AlertDialog cancelled = openNameDialog(activity);
        nameField(cancelled).setText("Ghost");
        cancelled.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertNull("Cancel stores nothing", KeepADBTrustedNetwork.getEntries(context).get(0).customName);

        KeepADBTrustedNetwork.setCustomName(context,
                KeepADBTrustedNetwork.getEntries(context).get(0).id, "Kitchen");
        AlertDialog dismissed = openNameDialog(activity);
        nameField(dismissed).setText("Ghost");
        dismissed.dismiss();
        ShadowLooper.idleMainLooper();
        assertEquals("Dismissing keeps the saved name", "Kitchen",
                KeepADBTrustedNetwork.getEntries(context).get(0).customName);
        assertTrue(allText(open(NetworkListActivity.VIEW_ALLOWED).findViewById(R.id.wifi_aps_list))
                .contains("Kitchen"));
    }

    @Test
    public void confirmingAnEmptyFieldResetsTheName() {
        KeepADBTrustedNetwork.addBssid(context, "cc:cc:cc:cc:cc:01", "Cafe");
        KeepADBTrustedNetwork.setCustomName(context,
                KeepADBTrustedNetwork.getEntries(context).get(0).id, "Kitchen");
        NetworkListActivity activity = open(NetworkListActivity.VIEW_ALLOWED);

        AlertDialog dialog = openNameDialog(activity);
        nameField(dialog).setText("   ");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        assertNull(KeepADBTrustedNetwork.getEntries(context).get(0).customName);
    }

    @Test
    public void aNameGivenToOneRowNeverAppearsOnAnother() {
        KeepADBTrustedNetwork.addBssid(context, "aa:aa:aa:aa:aa:01", "HomeMesh");
        KeepADBTrustedNetwork.addBssid(context, "aa:aa:aa:aa:aa:02", "HomeMesh");
        NetworkListActivity activity = open(NetworkListActivity.VIEW_ALLOWED);

        AlertDialog dialog = openNameDialog(activity, 1);
        nameField(dialog).setText("Upstairs");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        List<String> texts = allText(activity.findViewById(R.id.wifi_aps_list));
        assertTrue(joined(texts), texts.contains("HomeMesh (1)"));
        assertTrue(joined(texts), texts.contains("Upstairs (2)"));
        assertNull(KeepADBTrustedNetwork.getEntries(context).get(0).customName);
    }

    @Test
    public void theNameDialogIsDismissedWhenTheViewIsDestroyed() {
        KeepADBTrustedNetwork.addBssid(context, "cc:cc:cc:cc:cc:01", "Cafe");
        ActivityController<NetworkListActivity> controller = Robolectric.buildActivity(
                NetworkListActivity.class, NetworkListActivity.intent(context,
                        NetworkListActivity.VIEW_ALLOWED)).setup();
        ShadowLooper.idleMainLooper();
        AlertDialog dialog = openNameDialog(controller.get());
        assertTrue(dialog.isShowing());

        controller.destroy();

        assertFalse("Destroying the activity dismisses the naming popup", dialog.isShowing());
    }

    /** Both sides: with privacy off the name and the pencil show, with it on neither does. */
    @Test
    public void thePrivacyModeHidesAnOwnNameAndTheEditorThatWouldShowIt() {
        KeepADBTrustedNetwork.addBssid(context, "cc:cc:cc:cc:cc:01", "Cafe");
        KeepADBTrustedNetwork.setCustomName(context,
                KeepADBTrustedNetwork.getEntries(context).get(0).id, "Kitchen");

        KeepADBPreferences.setPrivacyModeEnabled(context, false);
        NetworkListActivity visible = open(NetworkListActivity.VIEW_ALLOWED);
        assertTrue(everythingShown(visible).contains("Kitchen"));
        assertEquals(1, findViews(visible.findViewById(R.id.wifi_aps_list), ImageButton.class).size());

        KeepADBPreferences.setPrivacyModeEnabled(context, true);
        NetworkListActivity hidden = open(NetworkListActivity.VIEW_ALLOWED);
        String everything = everythingShown(hidden);
        assertFalse("Privacy must hide the own name: " + everything, everything.contains("Kitchen"));
        assertFalse("Privacy must hide the network name: " + everything, everything.contains("Cafe"));
        assertTrue("The number stays: " + everything, everything.contains("#1"));
        assertTrue(findViews(hidden.findViewById(R.id.wifi_aps_list), ImageButton.class).isEmpty());
    }

    // --- band (#714) ----------------------------------------------------------------------------

    @Test
    public void everyBssidShowsItsOwnBandBehindItsAddressAndUnmeasuredOnesShowNone() {
        KeepADBTrustedNetwork.addBssid(context, "aa:aa:aa:aa:aa:01", "HomeMesh");
        KeepADBTrustedNetwork.addBssid(context, "aa:aa:aa:aa:aa:02", "HomeMesh");
        KeepADBTrustedNetwork.addBssid(context, "aa:aa:aa:aa:aa:03", "HomeMesh");
        KeepADBTrustedNetwork.addBssid(context, "aa:aa:aa:aa:aa:04", "HomeMesh");
        cachedScan(scan("HomeMesh", "aa:aa:aa:aa:aa:01", 2412), scan("HomeMesh", "aa:aa:aa:aa:aa:02", 5180),
                scan("HomeMesh", "aa:aa:aa:aa:aa:04", 6415));

        NetworkListActivity activity = open(NetworkListActivity.VIEW_ALLOWED);

        List<String> texts = allText(activity.findViewById(R.id.wifi_aps_list));
        assertTrue(joined(texts), texts.contains("AA:AA:AA:AA:AA:01 (2.4 GHz)"));
        assertTrue(joined(texts), texts.contains("AA:AA:AA:AA:AA:02 (5 GHz)"));
        assertTrue("No scan data for this one, so no band text at all: " + joined(texts),
                texts.contains("AA:AA:AA:AA:AA:03"));
        assertTrue(joined(texts), texts.contains("AA:AA:AA:AA:AA:04 (6 GHz)"));
    }

    @Test
    public void theBandOfTheCurrentConnectionComesFromItsOwnFrequency() {
        connectTo("HomeMesh", "aa:aa:aa:aa:aa:01", 5200);

        NetworkListActivity activity = open(NetworkListActivity.VIEW_ALLOWED);

        List<String> texts = allText(activity.findViewById(R.id.wifi_aps_current_row));
        assertTrue(joined(texts), texts.contains("AA:AA:AA:AA:AA:01 (5 GHz)"));
    }

    /** "Band data is added as soon as Android delivers it": the next drawing picks it up. */
    @Test
    public void bandDataAppearsOnTheNextDrawingOnceAndroidHasIt() {
        KeepADBTrustedNetwork.addBssid(context, "aa:aa:aa:aa:aa:01", "HomeMesh");
        ActivityController<NetworkListActivity> controller = Robolectric.buildActivity(
                NetworkListActivity.class, NetworkListActivity.intent(context,
                        NetworkListActivity.VIEW_ALLOWED)).setup();
        ShadowLooper.idleMainLooper();
        LinearLayout list = controller.get().findViewById(R.id.wifi_aps_list);
        assertTrue(allText(list).contains("AA:AA:AA:AA:AA:01"));

        cachedScan(scan("HomeMesh", "aa:aa:aa:aa:aa:01", 5180));
        controller.pause().resume();

        List<String> texts = allText(list);
        assertTrue(joined(texts), texts.contains("AA:AA:AA:AA:AA:01 (5 GHz)"));
        assertFalse(joined(texts), texts.contains("AA:AA:AA:AA:AA:01"));
    }

    @Test
    public void anEntryWithoutANetworkNameShowsItsAddressAsTitleWithTheBandBehindIt() {
        KeepADBTrustedNetwork.addBssid(context, "aa:aa:aa:aa:aa:01", null);
        cachedScan(scan("", "aa:aa:aa:aa:aa:01", 2412));

        NetworkListActivity activity = open(NetworkListActivity.VIEW_ALLOWED);

        List<String> texts = allText(activity.findViewById(R.id.wifi_aps_list));
        assertTrue(joined(texts), texts.contains("AA:AA:AA:AA:AA:01 (2.4 GHz)"));
        assertEquals("The address is not repeated in a second line", 1,
                texts.stream().filter(text -> text.contains("AA:AA:AA:AA:AA:01")).count());
    }

    @Test
    public void anOwnNameOnAnEntryWithoutANetworkNameStillGetsItsAddressLine() {
        KeepADBTrustedNetwork.addBssid(context, "aa:aa:aa:aa:aa:01", null);
        KeepADBTrustedNetwork.setCustomName(context,
                KeepADBTrustedNetwork.getEntries(context).get(0).id, "Hallway");

        NetworkListActivity activity = open(NetworkListActivity.VIEW_ALLOWED);

        List<String> texts = allText(activity.findViewById(R.id.wifi_aps_list));
        assertTrue(joined(texts), texts.contains("Hallway"));
        assertTrue(joined(texts), texts.contains("AA:AA:AA:AA:AA:01"));
    }

    @Test
    public void theObservedAndThePreventedViewShowTheBandToo() {
        KeepADBBssidHistory.recordObservation(context, "OfficeMesh", "bb:bb:bb:bb:bb:01");
        KeepADBBlockedNetworkHistory.record(context,
                new KeepADBNetworkIdentity("Hotel-WLAN", "aa:bb:cc:dd:ee:02"), 2_000L);
        KeepADBBlockedNetworkHistory.record(context,
                new KeepADBNetworkIdentity(WifiManager.UNKNOWN_SSID, "aa:bb:cc:dd:ee:09"), 1_000L);
        cachedScan(scan("OfficeMesh", "bb:bb:bb:bb:bb:01", 2437),
                scan("Hotel-WLAN", "aa:bb:cc:dd:ee:02", 5180));

        List<String> observed = allText(open(NetworkListActivity.VIEW_OBSERVED)
                .findViewById(R.id.wifi_aps_list));
        assertTrue(joined(observed), observed.contains("BB:BB:BB:BB:BB:01 (2.4 GHz)"));

        List<String> prevented = allText(open(NetworkListActivity.VIEW_PREVENTED)
                .findViewById(R.id.wifi_aps_list));
        assertTrue(joined(prevented),
                prevented.stream().anyMatch(t -> t.startsWith("AA:BB:CC:DD:EE:02 (5 GHz)" + DOT)));
        assertTrue("An unnamed entry is titled by its address, band behind it: " + joined(prevented),
                prevented.contains("AA:BB:CC:DD:EE:09"));
    }

    @Test
    public void thePrivacyModeKeepsTheMaskedAddressBeforeTheBand() {
        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:02", "Cafe-WLAN");
        cachedScan(scan("Cafe-WLAN", "aa:bb:cc:dd:ee:02", 5180));
        KeepADBPreferences.setPrivacyModeEnabled(context, true);

        String everything = everythingShown(open(NetworkListActivity.VIEW_ALLOWED));

        assertTrue(everything, everything.contains("AA:*:*:*:*:02 (5 GHz)"));
        assertFalse(everything, everything.contains("BB:CC"));
    }

    // --- last band seen (#721) ------------------------------------------------------------------

    private List<String> observedTexts() {
        return allText(open(NetworkListActivity.VIEW_OBSERVED).findViewById(R.id.wifi_aps_list));
    }

    /** No band known anywhere: the address stands alone -- no brackets, no placeholder text. */
    @Test
    public void anAccessPointWithoutAnyKnownBandShowsNoBracketsAndNoPlaceholder() {
        KeepADBTrustedNetwork.addBssid(context, "aa:aa:aa:aa:aa:03", "Cafe");
        KeepADBPreferences.setWifiApsFeatureEnabled(context, true);
        KeepADBBssidHistory.recordObservation(context, "Cafe", "aa:aa:aa:aa:aa:03");

        NetworkListActivity activity = open(NetworkListActivity.VIEW_ALLOWED);

        List<String> texts = allText(activity.findViewById(R.id.wifi_aps_list));
        assertTrue(joined(texts), texts.contains("AA:AA:AA:AA:AA:03"));
        String everything = everythingShown(activity);
        assertFalse(everything, everything.contains("AA:AA:AA:AA:AA:03 ("));
        assertFalse(everything, everything.contains("()"));
        assertFalse(everything, everything.toLowerCase(java.util.Locale.ROOT).contains("unknown"));
    }

    /** The point of #721: out of the scan cache, an observed access point still shows its band. */
    @Test
    public void anObservedAccessPointOutsideTheScanCacheShowsItsStoredBand() {
        KeepADBPreferences.setWifiApsFeatureEnabled(context, true);
        KeepADBBssidHistory.recordObservation(context, "OfficeMesh", "bb:bb:bb:bb:bb:01",
                KeepADBAccessPointBand.GHZ_5);
        KeepADBBssidHistory.recordObservation(context, "OfficeMesh", "bb:bb:bb:bb:bb:02");

        List<String> texts = observedTexts();

        assertTrue(joined(texts), texts.contains("BB:BB:BB:BB:BB:01 (5 GHz)"));
        assertTrue("A BSSID without a stored band shows none: " + joined(texts),
                texts.contains("BB:BB:BB:BB:BB:02"));
    }

    @Test
    public void theLiveBandWinsOverTheStoredBand() {
        KeepADBPreferences.setWifiApsFeatureEnabled(context, true);
        KeepADBBssidHistory.recordObservation(context, "OfficeMesh", "bb:bb:bb:bb:bb:01",
                KeepADBAccessPointBand.GHZ_2_4);
        cachedScan(scan("OfficeMesh", "bb:bb:bb:bb:bb:01", 5180));

        List<String> texts = observedTexts();

        assertTrue(joined(texts), texts.contains("BB:BB:BB:BB:BB:01 (5 GHz)"));
        assertFalse(joined(texts), texts.contains("BB:BB:BB:BB:BB:01 (2.4 GHz)"));
    }

    @Test
    public void theStoredBandAlsoShowsInTheAllowedAndThePreventedView() {
        KeepADBPreferences.setWifiApsFeatureEnabled(context, true);
        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:01", "Cafe");
        KeepADBBssidHistory.recordObservation(context, "Cafe", "aa:bb:cc:dd:ee:01",
                KeepADBAccessPointBand.GHZ_6);
        KeepADBBlockedNetworkHistory.record(context,
                new KeepADBNetworkIdentity("Hotel-WLAN", "aa:bb:cc:dd:ee:02"), 2_000L);
        KeepADBBssidHistory.recordObservation(context, "Hotel-WLAN", "aa:bb:cc:dd:ee:02",
                KeepADBAccessPointBand.GHZ_2_4);

        List<String> allowed = allText(open(NetworkListActivity.VIEW_ALLOWED)
                .findViewById(R.id.wifi_aps_list));
        List<String> prevented = allText(open(NetworkListActivity.VIEW_PREVENTED)
                .findViewById(R.id.wifi_aps_list));

        assertTrue(joined(allowed), allowed.contains("AA:BB:CC:DD:EE:01 (6 GHz)"));
        assertTrue(joined(prevented),
                prevented.stream().anyMatch(t -> t.startsWith("AA:BB:CC:DD:EE:02 (2.4 GHz)" + DOT)));
    }

    @Test
    public void thePrivacyModeMasksTheAddressBehindAStoredBandToo() {
        KeepADBPreferences.setWifiApsFeatureEnabled(context, true);
        KeepADBBssidHistory.recordObservation(context, "OfficeMesh", "bb:bb:bb:bb:bb:01",
                KeepADBAccessPointBand.GHZ_5);
        KeepADBPreferences.setPrivacyModeEnabled(context, true);

        String everything = everythingShown(open(NetworkListActivity.VIEW_OBSERVED));

        assertTrue(everything, everything.contains("BB:*:*:*:*:01 (5 GHz)"));
        assertFalse(everything, everything.contains("BB:BB"));
    }

    /**
     * Off means off: a band that was left in the store is not shown while the option is off, and
     * turning the option off deletes it for good -- turning it on again does not bring it back.
     */
    @Test
    public void withTheObservationOffOnlyTheLiveBandShowsAndTurningItOffDeletesTheStoredOne() {
        KeepADBPreferences.setWifiApsFeatureEnabled(context, true);
        KeepADBBssidHistory.recordObservation(context, "OfficeMesh", "bb:bb:bb:bb:bb:01",
                KeepADBAccessPointBand.GHZ_5);
        assertTrue(joined(observedTexts()), observedTexts().contains("BB:BB:BB:BB:BB:01 (5 GHz)"));

        // A leftover band with the option off (flipped without the setter): not shown.
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit()
                .putBoolean(KeepADBPreferences.KEY_WIFI_APS_FEATURE_ENABLED, false).commit();
        assertTrue(joined(observedTexts()), observedTexts().contains("BB:BB:BB:BB:BB:01"));
        assertFalse(joined(observedTexts()), observedTexts().contains("BB:BB:BB:BB:BB:01 (5 GHz)"));

        // The live band of the very same access point still shows while the option is off.
        cachedScan(scan("OfficeMesh", "bb:bb:bb:bb:bb:01", 2412));
        assertTrue(joined(observedTexts()), observedTexts().contains("BB:BB:BB:BB:BB:01 (2.4 GHz)"));
        cachedScan();

        // Through the setter: deleted, and not back when the option is switched on again.
        KeepADBPreferences.setWifiApsFeatureEnabled(context, true);
        KeepADBBssidHistory.recordObservation(context, "OfficeMesh", "bb:bb:bb:bb:bb:01",
                KeepADBAccessPointBand.GHZ_5);
        KeepADBPreferences.setWifiApsFeatureEnabled(context, false);
        KeepADBPreferences.setWifiApsFeatureEnabled(context, true);
        assertTrue(joined(observedTexts()), observedTexts().contains("BB:BB:BB:BB:BB:01"));
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
    public void hiddenRowsOfDifferentNamesStayDistinguishableByNumberWithoutRevealingAnything() {
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
     * #654 (user decision of 2026-09-30): hidden names are numbered per name. In the allowed view
     * the current access point and the list entries share one count, so the same name reads alike
     * on every row (several access points of one mesh) and two different names never share a
     * number -- in particular no list entry takes the number of the current access point.
     */
    @Test
    public void hiddenNamesAreNumberedPerNameAndTheCurrentAccessPointSharesTheCount() {
        connectTo("HomeMesh", "a1:00:00:00:00:a1");
        KeepADBTrustedNetwork.addBssid(context, "b2:00:00:00:00:b2", "HomeMesh");
        KeepADBTrustedNetwork.addBssid(context, "c3:00:00:00:00:c3", "Cafe-WLAN");
        KeepADBTrustedNetwork.addBssid(context, "d4:00:00:00:00:d4", "HomeMesh");
        KeepADBTrustedNetwork.addBssid(context, "e5:00:00:00:00:e5", "Hotel-WLAN");
        KeepADBPreferences.setPrivacyModeEnabled(context, true);

        NetworkListActivity activity = open(NetworkListActivity.VIEW_ALLOWED);

        String hidden = context.getString(R.string.network_privacy_name_hidden);
        Map<String, String> names = namesByMaskedAddress(activity);
        assertEquals(names.toString(), 5, names.size());
        assertEquals("The current access point", hidden + " #1", names.get("a1:*:*:*:*:a1"));
        assertEquals("Same name as the current one, behind it the entry number", hidden + " #1 (1)",
                names.get("b2:*:*:*:*:b2"));
        assertEquals("Another name", hidden + " #2", names.get("c3:*:*:*:*:c3"));
        assertEquals("A second access point of the current name", hidden + " #1 (3)",
                names.get("d4:*:*:*:*:d4"));
        assertEquals("A third name", hidden + " #3", names.get("e5:*:*:*:*:e5"));
        // The row action names its target with the same number, so TalkBack reads what is shown.
        Button removeCafe = findButtonWithDescription(activity.findViewById(R.id.wifi_aps_list),
                context.getString(R.string.network_action_remove_ap_accessibility, hidden + " #2"));
        assertNotNull(removeCafe);
    }

    @Test
    public void theObservedViewNumbersHiddenNamesPerNameToo() {
        connectTo("HomeMesh", "a1:00:00:00:00:a1");
        KeepADBBssidHistory.recordObservation(context, "HomeMesh", "b2:00:00:00:00:b2");
        KeepADBBssidHistory.recordObservation(context, "Cafe-WLAN", "c3:00:00:00:00:c3");
        KeepADBBssidHistory.recordObservation(context, "Hotel-WLAN", "e5:00:00:00:00:e5");
        KeepADBBssidHistory.recordObservation(context, "HomeMesh", "d4:00:00:00:00:d4");
        KeepADBPreferences.setPrivacyModeEnabled(context, true);

        Map<String, String> names = namesByMaskedAddress(open(NetworkListActivity.VIEW_OBSERVED));

        String hidden = context.getString(R.string.network_privacy_name_hidden);
        assertEquals(names.toString(), 5, names.size());
        assertEquals(hidden + " #1", names.get("a1:*:*:*:*:a1"));
        assertEquals(hidden + " #1", names.get("b2:*:*:*:*:b2"));
        assertEquals(hidden + " #1", names.get("d4:*:*:*:*:d4"));
        assertNotEquals("Two different names must not read alike", names.get("c3:*:*:*:*:c3"),
                names.get("e5:*:*:*:*:e5"));
        assertEquals("Names other than the current one are #2 and #3, in either order",
                new HashSet<>(java.util.Arrays.asList(hidden + " #2", hidden + " #3")),
                new HashSet<>(java.util.Arrays.asList(names.get("c3:*:*:*:*:c3"),
                        names.get("e5:*:*:*:*:e5"))));
    }

    @Test
    public void thePreventedViewNumbersHiddenNamesPerNameInTheOrderShown() {
        KeepADBBlockedNetworkHistory.record(context,
                new KeepADBNetworkIdentity("Hotel-WLAN", "f1:00:00:00:00:f1"), 1_000L);
        KeepADBBlockedNetworkHistory.record(context,
                new KeepADBNetworkIdentity("Cafe-WLAN", "f2:00:00:00:00:f2"), 2_000L);
        KeepADBBlockedNetworkHistory.record(context,
                new KeepADBNetworkIdentity("Hotel-WLAN", "f3:00:00:00:00:f3"), 3_000L);
        KeepADBPreferences.setPrivacyModeEnabled(context, true);

        Map<String, String> names = namesByMaskedAddress(open(NetworkListActivity.VIEW_PREVENTED));

        String hidden = context.getString(R.string.network_privacy_name_hidden);
        assertEquals(names.toString(), 3, names.size());
        assertEquals("Newest entry first, so its name is #1", hidden + " #1",
                names.get("f3:*:*:*:*:f3"));
        assertEquals(hidden + " #2", names.get("f2:*:*:*:*:f2"));
        assertEquals("The older entry of the same name reads alike", hidden + " #1",
                names.get("f1:*:*:*:*:f1"));
    }

    /**
     * A redraw (the view is shown again) and the "show more" toggle must not renumber rows that
     * were already there: the numbers follow the order the rows are shown in, so rows 21 and on
     * continue the count and a repeated name in a later row reuses its earlier number.
     */
    @Test
    public void hiddenNumbersSurviveARedrawAndTheShowMoreToggle() {
        for (int i = 0; i < NetworkListActivity.COLLAPSED_ROWS + 5; i++) {
            KeepADBBssidHistory.recordObservation(context, "Neighbor" + i, otherBssid(i));
        }
        // A second access point of one name, recorded last so it is listed first (newest first).
        KeepADBBssidHistory.recordObservation(context, "Neighbor3", "cc:cc:cc:cc:cc:03");
        connectTo("HomeMesh", "aa:aa:aa:aa:aa:01");
        KeepADBPreferences.setPrivacyModeEnabled(context, true);
        ActivityController<NetworkListActivity> controller = Robolectric.buildActivity(
                NetworkListActivity.class, NetworkListActivity.intent(context,
                        NetworkListActivity.VIEW_OBSERVED)).setup();
        ShadowLooper.idleMainLooper();
        NetworkListActivity activity = controller.get();

        Map<String, String> collapsed = namesByMaskedAddress(activity);
        assertEquals(NetworkListActivity.COLLAPSED_ROWS + 1, collapsed.size());

        controller.pause().resume();
        assertEquals("A redraw keeps every number", collapsed, namesByMaskedAddress(activity));

        activity.findViewById(R.id.wifi_aps_toggle).performClick();
        Map<String, String> expanded = namesByMaskedAddress(activity);
        assertEquals(NetworkListActivity.COLLAPSED_ROWS + 5 + 1 + 1, expanded.size());
        for (Map.Entry<String, String> row : collapsed.entrySet()) {
            assertEquals("Showing more must not renumber " + row.getKey(), row.getValue(),
                    expanded.get(row.getKey()));
        }
        assertEquals("Both access points of one name read alike", expanded.get("bb:*:*:*:*:03"),
                expanded.get("cc:*:*:*:*:03"));
        assertEquals("One number per name: HomeMesh and the 25 neighbours",
                NetworkListActivity.COLLAPSED_ROWS + 5 + 1, new HashSet<>(expanded.values()).size());
    }

    /**
     * The count belongs to one drawing of the view: after the list changed, the rows are numbered
     * again from the first one, so a name that left does not leave a gap behind.
     */
    @Test
    public void aRedrawAfterAChangeCountsAgainFromTheFirstRow() {
        connectTo("HomeMesh", "a1:00:00:00:00:a1");
        KeepADBTrustedNetwork.addBssid(context, "c3:00:00:00:00:c3", "Cafe-WLAN");
        KeepADBTrustedNetwork.addBssid(context, "e5:00:00:00:00:e5", "Hotel-WLAN");
        KeepADBPreferences.setPrivacyModeEnabled(context, true);
        NetworkListActivity activity = open(NetworkListActivity.VIEW_ALLOWED);
        String hidden = context.getString(R.string.network_privacy_name_hidden);
        Map<String, String> before = namesByMaskedAddress(activity);
        assertEquals(hidden + " #2", before.get("c3:*:*:*:*:c3"));
        assertEquals(hidden + " #3", before.get("e5:*:*:*:*:e5"));

        Button removeCafe = findButtonWithDescription(activity.findViewById(R.id.wifi_aps_list),
                context.getString(R.string.network_action_remove_ap_accessibility, hidden + " #2"));
        assertNotNull(removeCafe);
        removeCafe.performClick();
        ShadowLooper.idleMainLooper();

        Map<String, String> after = namesByMaskedAddress(activity);
        assertEquals(after.toString(), 2, after.size());
        assertEquals(hidden + " #1", after.get("a1:*:*:*:*:a1"));
        assertEquals("The remaining name moves up, no gap at #2", hidden + " #2",
                after.get("e5:*:*:*:*:e5"));
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
                "96", "A3", "A4", "A5", "A6"};
        KeepADBPreferences.setPrivacyModeEnabled(context, true);

        assertMaskedAddresses(open(NetworkListActivity.VIEW_ALLOWED), middleOctets,
                "DE:*:*:*:*:AD", "C0:*:*:*:*:0F");
        assertMaskedAddresses(open(NetworkListActivity.VIEW_OBSERVED), middleOctets,
                "DE:*:*:*:*:AD", "A1:*:*:*:*:B2");
        assertMaskedAddresses(open(NetworkListActivity.VIEW_PREVENTED), middleOctets,
                "F2:*:*:*:*:3C");

        // The off state shows the complete addresses, nothing masked.
        KeepADBPreferences.setPrivacyModeEnabled(context, false);
        String shown = everythingShown(open(NetworkListActivity.VIEW_ALLOWED));
        assertTrue(shown, shown.contains("DE:11:22:33:44:AD"));
        assertTrue(shown, shown.contains("C0:55:66:77:88:0F"));
        assertFalse(shown, shown.contains(":*:"));
    }

    private static void assertMaskedAddresses(Activity activity, String[] middleOctets,
                                              String... expected) {
        String everything = everythingShown(activity);
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\b[0-9A-F*]{1,2}(?::[0-9A-F*]{1,2}){5}\\b").matcher(everything);
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

    /** Taps the pencil of the first (or the {@code index}-th) row of the list and returns the popup. */
    private AlertDialog openNameDialog(NetworkListActivity activity) {
        return openNameDialog(activity, 0);
    }

    private AlertDialog openNameDialog(NetworkListActivity activity, int index) {
        List<ImageButton> pencils = findViews(activity.findViewById(R.id.wifi_aps_list), ImageButton.class);
        assertTrue("A pencil exists at " + index, pencils.size() > index);
        pencils.get(index).performClick();
        ShadowLooper.idleMainLooper();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("The naming popup must open", dialog);
        return dialog;
    }

    private static EditText nameField(AlertDialog dialog) {
        List<EditText> fields = findViews(dialog.getWindow().getDecorView(), EditText.class);
        assertEquals("One simple text field", 1, fields.size());
        return fields.get(0);
    }

    private void cachedScan(ScanResult... results) {
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        shadowOf(wifiManager).setScanResults(java.util.Arrays.asList(results));
    }

    private static ScanResult scan(String ssid, String bssid, int frequency) {
        return ShadowScanResult.newInstance(ssid, bssid, "[WPA2-PSK-CCMP]", -50, frequency);
    }

    private void connectTo(String ssid, String bssid, int frequency) {
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = ShadowWifiInfo.newInstance();
        shadowOf(info).setSSID(ssid);
        shadowOf(info).setBSSID(bssid);
        shadowOf(info).setFrequency(frequency);
        shadowOf(wifiManager).setConnectionInfo(info);
    }

    private NetworkListActivity open(String view) {
        ActivityController<NetworkListActivity> controller = Robolectric.buildActivity(
                NetworkListActivity.class, NetworkListActivity.intent(context, view)).setup();
        ShadowLooper.idleMainLooper();
        return controller.get();
    }

    private ActivityController<NetworkListActivity> openWithMeshQuestion() {
        preparedForAnAutomaticEnable("MeshHome", "aa:bb:cc:dd:ee:03");
        KeepADBBssidHistory.recordObservation(context, "MeshHome", "aa:bb:cc:dd:ee:04");
        ActivityController<NetworkListActivity> controller = Robolectric.buildActivity(
                NetworkListActivity.class, NetworkListActivity.intent(context,
                        NetworkListActivity.VIEW_ALLOWED)).setup();
        ShadowLooper.idleMainLooper();
        findButton(controller.get().findViewById(R.id.wifi_aps_current_row)).performClick();
        ShadowLooper.idleMainLooper();
        AlertDialog mesh = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(mesh);
        assertTrue(mesh.isShowing());
        return controller;
    }

    @SuppressWarnings("unchecked")
    private static <T> T getField(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(target);
    }

    private static void setField(Object target, String name, Object value)
            throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
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

    /**
     * The name each row of the current-access-point row and the list shows, by the masked address
     * the row carries (first and last octet), lower-cased. The current badge is stripped.
     */
    private static Map<String, String> namesByMaskedAddress(NetworkListActivity activity) {
        Pattern masked = Pattern.compile("[0-9a-f]{2}:\\*:\\*:\\*:\\*:[0-9a-f]{2}");
        String badge = activity.getString(R.string.wifi_aps_current_badge) + " \u00b7 ";
        // #714: a stored entry's title starts with its entry number, which is not part of the name.
        Pattern entryNumber = Pattern.compile("^#\\d+ \u00b7 ");
        Map<String, String> result = new LinkedHashMap<>();
        for (int group : new int[] {R.id.wifi_aps_current_row, R.id.wifi_aps_list}) {
            ViewGroup rows = activity.findViewById(group);
            for (int i = 0; i < rows.getChildCount(); i++) {
                List<String> texts = allText(rows.getChildAt(i));
                String name = texts.get(0);
                if (name.startsWith(badge)) name = name.substring(badge.length());
                name = entryNumber.matcher(name).replaceFirst("");
                String address = null;
                for (String text : texts) {
                    Matcher matcher = masked.matcher(text.toLowerCase(java.util.Locale.ROOT));
                    if (matcher.find()) {
                        address = matcher.group();
                        break;
                    }
                }
                assertNotNull("A row carries its masked address: " + joined(texts), address);
                assertNull("Every row has its own address: " + address, result.put(address, name));
            }
        }
        return result;
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
