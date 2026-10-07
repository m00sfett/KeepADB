package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;
import java.util.Map;

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
import org.robolectric.shadows.ShadowToast;
import org.robolectric.shadows.ShadowWifiInfo;

/**
 * #766 (with #759 and #760), end to end: the real "new Wi-Fi" prompt is posted, its content
 * PendingIntent is taken from the posted notification and started, and the decision dialog is
 * driven through its buttons. What is pinned, each with the other side next to it:
 *
 * <ul>
 *   <li>trust and both block answers write exactly what they say -- and "decide later", Back, a
 *       tap outside and rotating write nothing;
 *   <li>the dialog is bound to the prompted access point, not to the current connection, and takes
 *       its name from the app's own record, not from the intent;
 *   <li>a block holds against a trust tapped afterwards, with a message that says so;
 *   <li>the name and address are shown even in the privacy mode (deliberately) and never while
 *       the keyguard is up (Keyguard check, no name in the view hierarchy).
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class NetworkDecisionActivityTest {

    private static final String SSID = "Cafe-WLAN";
    private static final String BSSID = "aa:bb:cc:dd:ee:01";
    private static final String OTHER_SSID = "Other-WLAN";
    private static final String OTHER_BSSID = "aa:bb:cc:dd:ee:02";

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = RuntimeEnvironment.getApplication();

    @Before
    public void setUp() {
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.POST_NOTIFICATIONS,
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
        prefs().edit().clear().commit();
        KeepADB.resetForTesting();
        ShadowToast.reset();
    }

    @After
    public void tearDown() {
        prefs().edit().clear().commit();
        KeepADB.resetForTesting();
        KeepADBRegisterClient.resetHttpTransport();
        unlockDevice();
    }

    // --- The prompt's tap opens the decision for exactly the prompted access point ----------------

    @Test
    public void tappingTheDetailsOffPromptNamesTheNetworkAndTrustTrustsExactlyThatBssid() {
        Intent tap = promptTap(SSID, BSSID, false);

        ActivityController<NetworkDecisionActivity> controller = open(tap);
        NetworkDecisionActivity activity = controller.get();
        assertEquals(SSID, text(activity, R.id.decision_name));
        assertEquals(BSSID.toUpperCase(Locale.ROOT), text(activity, R.id.decision_bssid));
        assertEquals(View.VISIBLE, activity.findViewById(R.id.decision_trust).getVisibility());
        assertTrue("Showing the question must not trust anything",
                KeepADBTrustedNetwork.getEntries(context).isEmpty());
        assertTrue("... nor block anything", KeepADBNetworkBlocklist.isEmpty(context));
        assertNull("An open question keeps its dialog, no list (#798)",
                shadowOf(activity).getNextStartedActivity());

        click(activity, R.id.decision_trust);
        ShadowLooper.idleMainLooper();

        List<KeepADBTrustedNetwork.Entry> entries = KeepADBTrustedNetwork.getEntries(context);
        assertEquals(1, entries.size());
        assertEquals(BSSID, entries.get(0).bssid);
        assertEquals(SSID, entries.get(0).label);
        assertTrue(KeepADBNetworkBlocklist.isEmpty(context));
        assertNull("The answered prompt must go away", postedPrompt());
        assertTrue(KeepADBBlockedNetworkHistory.getEntries(context).isEmpty());
        assertTrue("The dialog closes after an answer", activity.isFinishing());
        controller.pause().stop().destroy();
    }

    /** Roam between the notification and the tap: the dialog names and trusts the prompted BSSID. */
    @Test
    public void tappingTheDetailsOnPromptAfterARoamStillDecidesThePromptedAccessPoint() {
        Intent tap = promptTap(SSID, BSSID, true);
        connectTo(OTHER_SSID, OTHER_BSSID);
        KeepADBBlockedNetworkHistory.record(context,
                new KeepADBNetworkIdentity("\"" + OTHER_SSID + "\"", OTHER_BSSID), 2L);

        ActivityController<NetworkDecisionActivity> controller = open(tap);
        NetworkDecisionActivity activity = controller.get();
        assertEquals(SSID, text(activity, R.id.decision_name));
        assertEquals(BSSID.toUpperCase(Locale.ROOT), text(activity, R.id.decision_bssid));
        assertFalse(allText(activity).contains(OTHER_SSID));
        assertFalse(allText(activity).contains(OTHER_BSSID.toUpperCase(Locale.ROOT)));

        click(activity, R.id.decision_trust);
        ShadowLooper.idleMainLooper();

        assertOnlyTrusted(BSSID);
        assertEquals("The other recorded access point is untouched", 1,
                KeepADBBlockedNetworkHistory.getEntries(context).size());
        controller.pause().stop().destroy();
    }

    /** Roam while the dialog is open: the bound BSSID is never re-read from the connection. */
    @Test
    public void aRoamWhileTheDialogIsOpenDoesNotSwapTheAnsweredAccessPoint() {
        Intent tap = promptTap(SSID, BSSID, false);
        ActivityController<NetworkDecisionActivity> controller = open(tap);

        connectTo(OTHER_SSID, OTHER_BSSID);
        KeepADBBlockedNetworkHistory.record(context,
                new KeepADBNetworkIdentity("\"" + OTHER_SSID + "\"", OTHER_BSSID), 2L);
        click(controller.get(), R.id.decision_block_ap);
        ShadowLooper.idleMainLooper();

        assertTrue(KeepADBNetworkBlocklist.isBssidBlocked(context, BSSID));
        assertFalse("The access point the device roamed to is not blocked",
                KeepADBNetworkBlocklist.isBssidBlocked(context, OTHER_BSSID));
        controller.pause().stop().destroy();
    }

    // --- Block answers (N1: "Blockieren" really blocks) --------------------------------------------

    @Test
    public void blockAccessPointBlocksExactlyThatAccessPointAndNotItsName() {
        Intent tap = promptTap(SSID, BSSID, false);
        KeepADBNetworkTrustPrompt.reshow(context, BSSID, SSID);
        assertNotNull("Precondition: a prompt is showing", postedPrompt());
        ActivityController<NetworkDecisionActivity> controller = open(tap);
        assertFalse("Control: nothing is blocked before the answer",
                KeepADBNetworkBlocklist.isBssidBlocked(context, BSSID));

        click(controller.get(), R.id.decision_block_ap);
        ShadowLooper.idleMainLooper();

        assertTrue(KeepADBNetworkBlocklist.isBssidBlocked(context, BSSID));
        assertFalse(KeepADBNetworkBlocklist.isSsidBlocked(context, SSID));
        assertFalse("Another access point of the same name stays unblocked",
                KeepADBNetworkBlocklist.isBssidBlocked(context, OTHER_BSSID));
        assertTrue(KeepADBTrustedNetwork.getEntries(context).isEmpty());
        assertFalse(KeepADB.isEnabled(context));
        assertNull("The answered prompt must go away", postedPrompt());
        assertTrue("A blocked network is not 'recently prevented'",
                KeepADBBlockedNetworkHistory.getEntries(context).isEmpty());
        assertEquals(KeepADBTrustedNetwork.Decision.BLOCKED_ACCESS_POINT,
                KeepADBTrustedNetwork.evaluate(context, identity(SSID, BSSID)));
        assertEquals(context.getString(R.string.network_decision_blocked_toast),
                ShadowToast.getTextOfLatestToast());
        assertTrue(controller.get().isFinishing());
        controller.pause().stop().destroy();
    }

    @Test
    public void blockNameBlocksTheWifiNameForEveryAccessPointAndKeepsTheAddressUnblocked() {
        Intent tap = promptTap(SSID, BSSID, false);
        KeepADBBlockedNetworkHistory.record(context, identity(SSID, OTHER_BSSID), 3L);
        ActivityController<NetworkDecisionActivity> controller = open(tap);
        TextView blockName = controller.get().findViewById(R.id.decision_block_name);
        assertEquals(View.VISIBLE, blockName.getVisibility());
        assertTrue("The button names the Wi-Fi name: " + blockName.getText(),
                blockName.getText().toString().contains(SSID));

        click(controller.get(), R.id.decision_block_name);
        ShadowLooper.idleMainLooper();

        assertTrue(KeepADBNetworkBlocklist.isSsidBlocked(context, SSID));
        assertFalse("A name block is not an address block",
                KeepADBNetworkBlocklist.isBssidBlocked(context, BSSID));
        assertEquals("Every access point of that name is blocked", KeepADBTrustedNetwork.Decision.BLOCKED_NAME,
                KeepADBTrustedNetwork.evaluate(context, identity(SSID, OTHER_BSSID)));
        assertEquals(KeepADBTrustedNetwork.Decision.BLOCKED_NAME,
                KeepADBTrustedNetwork.evaluate(context, identity(SSID, BSSID)));
        assertEquals("A different name is not affected", KeepADBTrustedNetwork.Decision.UNKNOWN_NETWORK,
                KeepADBTrustedNetwork.evaluate(context, identity(OTHER_SSID, OTHER_BSSID)));
        assertTrue("Recorded access points of the blocked name are dropped",
                KeepADBBlockedNetworkHistory.getEntries(context).isEmpty());
        assertTrue(KeepADBTrustedNetwork.getEntries(context).isEmpty());
        assertNull(postedPrompt());
        assertTrue(controller.get().isFinishing());
        controller.pause().stop().destroy();
    }

    @Test
    public void withoutAReadableNameThereIsNoNameBlockAndNoComfortNote() {
        KeepADBTrustedNetwork.setTrustByNameEnabled(context, true);
        Intent tap = promptTap("", BSSID, false);

        ActivityController<NetworkDecisionActivity> controller = open(tap);
        NetworkDecisionActivity activity = controller.get();

        assertEquals(context.getString(R.string.network_decision_name_hidden),
                text(activity, R.id.decision_name));
        assertEquals(BSSID.toUpperCase(Locale.ROOT), text(activity, R.id.decision_bssid));
        assertEquals("There is no name to block", View.GONE,
                activity.findViewById(R.id.decision_block_name).getVisibility());
        assertEquals("... and none that could be trusted by name", View.GONE,
                activity.findViewById(R.id.decision_comfort_note).getVisibility());
        click(activity, R.id.decision_block_name);
        assertTrue("A click on the hidden button must not block anything",
                KeepADBNetworkBlocklist.isEmpty(context));
        controller.pause().stop().destroy();
    }

    @Test
    public void theComfortNoteAppearsOnlyWhileTheComfortSwitchIsOn() {
        Intent tap = promptTap(SSID, BSSID, false);

        ActivityController<NetworkDecisionActivity> off = open(tap);
        assertEquals(View.GONE, off.get().findViewById(R.id.decision_comfort_note).getVisibility());
        off.pause().stop().destroy();

        KeepADBTrustedNetwork.setTrustByNameEnabled(context, true);
        ActivityController<NetworkDecisionActivity> on = open(tap);
        TextView note = on.get().findViewById(R.id.decision_comfort_note);
        assertEquals(View.VISIBLE, note.getVisibility());
        assertTrue(note.getText().toString(), note.getText().toString().contains(SSID));
        on.pause().stop().destroy();
    }

    // --- "Decide later", Back, outside and rotation decide nothing --------------------------------

    @Test
    public void decideLaterBackAndATapOutsideChangeNoStatusAndKeepTheQuestionOpen() {
        Intent tap = promptTap(SSID, BSSID, true);
        Map<String, ?> before = prefs().getAll();

        ActivityController<NetworkDecisionActivity> later = open(tap);
        click(later.get(), R.id.decision_later);
        assertTrue("Decide later closes the dialog", later.get().isFinishing());
        later.pause().stop().destroy();
        assertEquals("Decide later writes nothing", before, prefs().getAll());

        ActivityController<NetworkDecisionActivity> back = open(tap);
        back.get().onBackPressed();
        assertTrue("Back closes the dialog", back.get().isFinishing());
        back.pause().stop().destroy();
        assertEquals("Back writes nothing", before, prefs().getAll());

        ActivityController<NetworkDecisionActivity> outside = open(tap);
        MotionEvent down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, -5000f, -5000f, 0);
        MotionEvent up = MotionEvent.obtain(0L, 10L, MotionEvent.ACTION_UP, -5000f, -5000f, 0);
        outside.get().onTouchEvent(down);
        outside.get().onTouchEvent(up);
        assertTrue("A tap outside the dialog closes it", outside.get().isFinishing());
        outside.pause().stop().destroy();
        assertEquals("A tap outside writes nothing", before, prefs().getAll());

        assertTrue(KeepADBTrustedNetwork.getEntries(context).isEmpty());
        assertTrue(KeepADBNetworkBlocklist.isEmpty(context));
        assertEquals("The access point stays recorded, so the question can be answered later", 1,
                KeepADBBlockedNetworkHistory.getEntries(context).size());
        // Control: the very same tap still opens the question afterwards.
        ActivityController<NetworkDecisionActivity> again = open(tap);
        assertEquals(SSID, text(again.get(), R.id.decision_name));
        assertFalse(again.get().isFinishing());
        again.pause().stop().destroy();
    }

    @Test
    public void aRotationKeepsTheQuestionOnTheSameAccessPointAndWritesNothing() {
        Intent tap = promptTap(SSID, BSSID, false);
        ActivityController<NetworkDecisionActivity> controller = open(tap);
        Map<String, ?> before = prefs().getAll();

        controller.recreate();
        ShadowLooper.idleMainLooper();
        connectTo(OTHER_SSID, OTHER_BSSID);
        controller.recreate();
        ShadowLooper.idleMainLooper();

        NetworkDecisionActivity recreated = controller.get();
        assertEquals(SSID, text(recreated, R.id.decision_name));
        assertEquals(BSSID.toUpperCase(Locale.ROOT), text(recreated, R.id.decision_bssid));
        assertEquals("Rotating writes nothing", before, prefs().getAll());
        click(recreated, R.id.decision_trust);
        ShadowLooper.idleMainLooper();
        assertOnlyTrusted(BSSID);
        controller.pause().stop().destroy();
    }

    // --- The record, not the intent, decides what is shown and what is answered -------------------

    @Test
    public void aLabelExtraCannotRenameTheNetworkTheDialogShowsOrStores() {
        KeepADBBlockedNetworkHistory.record(context, identity(SSID, BSSID), 1L);
        Intent forged = KeepADBNetworkTrustPrompt.decisionIntent(context, BSSID)
                .putExtra(KeepADBNetworkTrustPrompt.EXTRA_LABEL, "Home-WLAN");

        ActivityController<NetworkDecisionActivity> controller = open(forged);
        assertEquals(SSID, text(controller.get(), R.id.decision_name));
        assertFalse(allText(controller.get()).contains("Home-WLAN"));
        click(controller.get(), R.id.decision_trust);
        ShadowLooper.idleMainLooper();

        assertEquals(SSID, KeepADBTrustedNetwork.getEntries(context).get(0).label);
        controller.pause().stop().destroy();
    }

    @Test
    public void anUnrecordedOrPlaceholderBssidOffersNoChoiceAndWritesNothing() {
        KeepADBBlockedNetworkHistory.record(context, identity(SSID, BSSID), 1L);
        Map<String, ?> before = prefs().getAll();

        for (String bssid : new String[] {"aa:bb:cc:dd:ee:99", null, "",
                KeepADBNetworkIdentity.REDACTED_BSSID, KeepADBNetworkIdentity.UNSET_BSSID}) {
            ShadowToast.reset();
            ActivityController<NetworkDecisionActivity> controller =
                    open(KeepADBNetworkTrustPrompt.decisionIntent(context, bssid));
            assertEquals("No question for '" + bssid + "'",
                    View.GONE, controller.get().findViewById(R.id.decision_details).getVisibility());
            assertTrue(controller.get().isFinishing());
            assertEquals(context.getString(R.string.network_decision_expired_toast),
                    ShadowToast.getTextOfLatestToast());
            assertNull("No list for an expired request", shadowOf(controller.get()).getNextStartedActivity());
            controller.pause().stop().destroy();
        }
        assertEquals(before, prefs().getAll());

        // Only the action of the prompt opens it with a BSSID extra, but the activity never acts
        // without an explicit click either way.
        assertTrue(KeepADBTrustedNetwork.getEntries(context).isEmpty());
        assertTrue(KeepADBNetworkBlocklist.isEmpty(context));
    }

    @Test
    public void aQuestionAnsweredElsewhereInTheMeantimeIsNotAskedAgain() {
        Intent tap = promptTap(SSID, BSSID, false);
        KeepADBReceiver.allowBssidOnly(context, BSSID, SSID);

        ActivityController<NetworkDecisionActivity> trusted = open(tap);
        assertTrue(trusted.get().isFinishing());
        assertEquals(context.getString(R.string.network_decision_already_decided_toast),
                ShadowToast.getTextOfLatestToast());
        // #798: besides the message, the Networks list opens on the row of this access point.
        Intent listIntent = shadowOf(trusted.get()).getNextStartedActivity();
        assertEquals(NetworkListActivity.class.getName(), listIntent.getComponent().getClassName());
        assertEquals(BSSID, listIntent.getStringExtra(NetworkListActivity.EXTRA_FOCUS_BSSID));
        assertEquals("Opening it again changes nothing", 1, KeepADBTrustedNetwork.getEntries(context).size());
        trusted.pause().stop().destroy();

        // The same on the other side: blocked through the list (or the notification) meanwhile.
        KeepADBTrustedNetwork.remove(context, KeepADBTrustedNetwork.getEntries(context).get(0).id);
        KeepADBBlockedNetworkHistory.record(context, identity(SSID, BSSID), 5L);
        KeepADBNetworkBlocklist.blockBssid(context, BSSID);
        ShadowToast.reset();
        ActivityController<NetworkDecisionActivity> blocked = open(tap);
        assertTrue(blocked.get().isFinishing());
        assertEquals(context.getString(R.string.network_decision_already_decided_toast),
                ShadowToast.getTextOfLatestToast());
        assertEquals(NetworkListActivity.class.getName(), shadowOf(blocked.get())
                .getNextStartedActivity().getComponent().getClassName());
        assertTrue(KeepADBTrustedNetwork.getEntries(context).isEmpty());
        assertTrue("The block is still there", KeepADBNetworkBlocklist.isBssidBlocked(context, BSSID));
        blocked.pause().stop().destroy();
    }

    // --- A block holds against a later trust (and says so) ------------------------------------------

    @Test
    public void trustOnADialogOpenedBeforeTheBlockStoresNothingKeepsTheBlockAndSaysSo() {
        Intent tap = promptTap(SSID, BSSID, false);
        ActivityController<NetworkDecisionActivity> controller = open(tap);
        assertEquals("Precondition: the question was offered before the block", SSID,
                text(controller.get(), R.id.decision_name));

        KeepADBNetworkBlocklist.blockBssid(context, BSSID);
        connectTo(SSID, BSSID);
        click(controller.get(), R.id.decision_trust);
        ShadowLooper.idleMainLooper();

        assertTrue("Trusting must not store anything for a blocked access point",
                KeepADBTrustedNetwork.getEntries(context).isEmpty());
        assertTrue("Trusting must not lift the block", KeepADBNetworkBlocklist.isBssidBlocked(context, BSSID));
        assertFalse(KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
        assertEquals("The refusal is reported as such, not as a generic failure",
                context.getString(R.string.network_decision_trust_refused_toast),
                ShadowToast.getTextOfLatestToast());
        assertTrue(controller.get().isFinishing());
        controller.pause().stop().destroy();
    }

    @Test
    public void trustOnADialogOpenedBeforeAWifiNameBlockIsRefusedToo() {
        Intent tap = promptTap(SSID, BSSID, false);
        ActivityController<NetworkDecisionActivity> controller = open(tap);

        KeepADBNetworkBlocklist.blockSsid(context, SSID);
        click(controller.get(), R.id.decision_trust);
        ShadowLooper.idleMainLooper();

        assertTrue(KeepADBTrustedNetwork.getEntries(context).isEmpty());
        assertTrue(KeepADBNetworkBlocklist.isSsidBlocked(context, SSID));
        assertEquals(context.getString(R.string.network_decision_trust_refused_toast),
                ShadowToast.getTextOfLatestToast());
        controller.pause().stop().destroy();
    }

    @Test
    public void controlTheSameTrustClickWithoutABlockTrustsAndSaysSo() {
        Intent tap = promptTap(SSID, BSSID, false);
        ActivityController<NetworkDecisionActivity> controller = open(tap);

        click(controller.get(), R.id.decision_trust);
        ShadowLooper.idleMainLooper();

        assertOnlyTrusted(BSSID);
        assertEquals(context.getString(R.string.network_ap_allowed_toast, SSID),
                ShadowToast.getTextOfLatestToast());
        controller.pause().stop().destroy();
    }

    /**
     * The auth gate stays the notification allow action's own: should the decision be answered
     * while the device reports itself locked, nothing is trusted, the dialog stays open (the
     * question is not lost) and the prompt is offered again.
     */
    @Test
    public void trustWhileTheDeviceReportsLockedStoresNothingAndKeepsTheQuestionOpen() {
        Intent tap = promptTap(SSID, BSSID, false);
        ActivityController<NetworkDecisionActivity> controller = open(tap);

        shadowOf(context.getSystemService(KeyguardManager.class)).setIsDeviceLocked(true);
        click(controller.get(), R.id.decision_trust);
        ShadowLooper.idleMainLooper();

        assertTrue(KeepADBTrustedNetwork.getEntries(context).isEmpty());
        assertFalse("The dialog stays open", controller.get().isFinishing());
        assertEquals(context.getString(R.string.network_decision_failed_toast),
                ShadowToast.getTextOfLatestToast());
        assertNotNull("The question is offered again", postedPrompt());
        controller.pause().stop().destroy();
    }

    /** Trusting an access point still tells the user when Wireless Debugging could not be switched on. */
    @Test
    public void trustWithoutTheSecureSettingsPermissionStillSaysSo() {
        shadowOf((Application) context).denyPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS);
        Intent tap = promptTap(SSID, BSSID, false);
        ActivityController<NetworkDecisionActivity> controller = open(tap);

        click(controller.get(), R.id.decision_trust);
        ShadowLooper.idleMainLooper();

        assertOnlyTrusted(BSSID);
        assertEquals(context.getString(R.string.permission_error_toast, context.getPackageName()),
                ShadowToast.getTextOfLatestToast());
        controller.pause().stop().destroy();
    }

    // --- Privacy mode: the dialog shows the name on purpose, toasts do not -------------------------

    @Test
    public void thePrivacyModeDoesNotHideTheNetworkTheUserIsDecidingOnButTheToastStaysMasked() {
        KeepADBPreferences.setPrivacyModeEnabled(context, true);
        Intent tap = promptTap(SSID, BSSID, false);

        ActivityController<NetworkDecisionActivity> controller = open(tap);
        NetworkDecisionActivity activity = controller.get();
        assertEquals("Deliberate exception: the name is shown", SSID, text(activity, R.id.decision_name));
        assertEquals("... and the address", BSSID.toUpperCase(Locale.ROOT),
                text(activity, R.id.decision_bssid));

        click(activity, R.id.decision_trust);
        ShadowLooper.idleMainLooper();

        String toast = ShadowToast.getTextOfLatestToast();
        assertNotNull(toast);
        assertFalse("The toast stays masked: " + toast, toast.contains(SSID));
        assertFalse(toast, toast.toUpperCase(Locale.ROOT).contains(BSSID.toUpperCase(Locale.ROOT)));
        assertOnlyTrusted(BSSID);
        controller.pause().stop().destroy();
    }

    // --- Keyguard: never a name on a locked device --------------------------------------------------

    @Test
    public void whileTheKeyguardIsUpNeitherNameNorAddressNorAnswerIsInTheViewHierarchy() {
        Intent tap = promptTap(SSID, BSSID, false);
        lockKeyguard();

        ActivityController<NetworkDecisionActivity> controller = open(tap);
        NetworkDecisionActivity activity = controller.get();

        assertNoNetworkText(activity);
        assertEquals(View.GONE, activity.findViewById(R.id.decision_details).getVisibility());
        // A click on an answer button that is not on screen must not answer anything.
        click(activity, R.id.decision_trust);
        click(activity, R.id.decision_block_ap);
        click(activity, R.id.decision_block_name);
        assertTrue(KeepADBTrustedNetwork.getEntries(context).isEmpty());
        assertTrue(KeepADBNetworkBlocklist.isEmpty(context));
        assertFalse("The question stays open for after the unlock", activity.isFinishing());

        // Control: the unlock brings the very same question, with the name, back.
        unlockDevice();
        controller.pause().resume();
        assertEquals(SSID, text(activity, R.id.decision_name));
        assertEquals(View.VISIBLE, activity.findViewById(R.id.decision_details).getVisibility());
        controller.pause().stop().destroy();
    }

    @Test
    public void theNameIsRemovedAgainWhenTheActivityStopsAndTheKeyguardIsUpAfterwards() {
        Intent tap = promptTap(SSID, BSSID, true);
        ActivityController<NetworkDecisionActivity> controller = open(tap);
        assertEquals(SSID, text(controller.get(), R.id.decision_name));

        controller.pause().stop();
        assertNoNetworkText(controller.get());

        lockKeyguard();
        controller.restart().start().resume();
        assertNoNetworkText(controller.get());

        unlockDevice();
        controller.pause().resume();
        assertEquals(SSID, text(controller.get(), R.id.decision_name));
        controller.pause().stop().destroy();
    }

    /**
     * The lock-screen redaction of the prompt itself is unchanged: neither copy of the details-off
     * notification (the private one is what Android shows when sensitive lock-screen content is
     * allowed) names the network, and the public copy has no action and no intent in both forms.
     */
    @Test
    public void thePromptNotificationStaysNeutralOnTheLockScreen() {
        connectTo(SSID, BSSID);
        assertTrue(KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));
        Notification neutral = postedPrompt();
        KeepADBNotificationTextScan.assertMentionsNone(neutral, SSID, BSSID,
                BSSID.toUpperCase(Locale.ROOT));
        assertTrue(neutral.actions == null || neutral.actions.length == 0);
        assertNull(neutral.publicVersion.contentIntent);
        assertTrue(neutral.publicVersion.actions == null || neutral.publicVersion.actions.length == 0);

        KeepADBNetworkTrustPrompt.cancel(context);
        KeepADBNetworkTrustPrompt.clearPromptState(context);
        KeepADBPreferences.setNotificationDetailsEnabled(context, true);
        assertTrue(KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));
        Notification detailed = postedPrompt();
        KeepADBNotificationTextScan.assertMentionsNone(detailed.publicVersion, SSID, BSSID,
                BSSID.toUpperCase(Locale.ROOT));
        assertNull(detailed.publicVersion.contentIntent);
        assertTrue(detailed.publicVersion.actions == null || detailed.publicVersion.actions.length == 0);
    }

    // --- helpers ----------------------------------------------------------------------------------

    /**
     * Raises the real prompt for {@code (ssid, bssid)} (details on or off) and returns the intent
     * its content PendingIntent would start. The prompt is removed again, as the platform does on
     * a tap, so the tests see only what the activity itself does to the notification.
     */
    private Intent promptTap(String ssid, String bssid, boolean details) {
        KeepADBPreferences.setNotificationDetailsEnabled(context, details);
        connectTo(ssid, bssid);
        assertTrue(KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));
        Notification prompt = postedPrompt();
        assertNotNull(prompt);
        String shown = String.valueOf(prompt.extras.getCharSequence(Notification.EXTRA_TEXT));
        assertEquals("Precondition: only the details-on form names the network",
                details, shown.contains(bssid.toUpperCase(Locale.ROOT)));
        Intent tap = new Intent(shadowOf(prompt.contentIntent).getSavedIntent());
        assertEquals(NetworkDecisionActivity.class.getName(), tap.getComponent().getClassName());
        KeepADBNetworkTrustPrompt.cancel(context);
        return tap;
    }

    private ActivityController<NetworkDecisionActivity> open(Intent intent) {
        ActivityController<NetworkDecisionActivity> controller =
                Robolectric.buildActivity(NetworkDecisionActivity.class, new Intent(intent)).setup();
        ShadowLooper.idleMainLooper();
        return controller;
    }

    private static void click(NetworkDecisionActivity activity, int id) {
        View view = activity.findViewById(id);
        assertNotNull(view);
        view.performClick();
    }

    private static String text(NetworkDecisionActivity activity, int id) {
        return String.valueOf(((TextView) activity.findViewById(id)).getText());
    }

    /** Every text and content description of the activity's view hierarchy, joined. */
    private static String allText(NetworkDecisionActivity activity) {
        StringBuilder out = new StringBuilder();
        collect(activity.getWindow().getDecorView(), out);
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

    private static void assertNoNetworkText(NetworkDecisionActivity activity) {
        String all = allText(activity);
        for (String secret : new String[] {SSID, BSSID, BSSID.toUpperCase(Locale.ROOT)}) {
            assertFalse("The view hierarchy must not hold '" + secret + "': " + all, all.contains(secret));
        }
    }

    private void assertOnlyTrusted(String bssid) {
        List<KeepADBTrustedNetwork.Entry> entries = KeepADBTrustedNetwork.getEntries(context);
        assertEquals(1, entries.size());
        assertEquals(bssid, entries.get(0).bssid);
    }

    private static KeepADBNetworkIdentity identity(String ssid, String bssid) {
        return new KeepADBNetworkIdentity("\"" + ssid + "\"", bssid);
    }

    private void connectTo(String ssid, String bssid) {
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = ShadowWifiInfo.newInstance();
        shadowOf(info).setSSID(ssid);
        shadowOf(info).setBSSID(bssid);
        shadowOf(wifiManager).setConnectionInfo(info);
    }

    private Notification postedPrompt() {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        return shadowOf(manager).getNotification(KeepADBNetworkTrustPrompt.NOTIFICATION_ID);
    }

    /** Only the keyguard, not the credential lock: what the activity's own check looks at. */
    private void lockKeyguard() {
        shadowOf(context.getSystemService(KeyguardManager.class)).setKeyguardLocked(true);
    }

    private void unlockDevice() {
        shadowOf(context.getSystemService(KeyguardManager.class)).setIsDeviceLocked(false);
        shadowOf(context.getSystemService(KeyguardManager.class)).setKeyguardLocked(false);
    }

    private android.content.SharedPreferences prefs() {
        return context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE);
    }
}
