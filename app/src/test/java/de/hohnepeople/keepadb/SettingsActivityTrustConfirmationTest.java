package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlertDialog;
import android.app.Application;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.widget.TextView;

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
 * #598: with connection details off (the default), the untrusted-network prompt has no allow
 * action; its tap opens {@link SettingsActivity}'s confirmation dialog instead. These tests pin the
 * two sides of the invariant "what gets trusted is exactly the access point the dialog names":
 * allow trusts that BSSID (and nothing else), and no intent content and no roam -- between the
 * notification and the tap, or between the dialog and the click -- can make it trust a different
 * one.
 *
 * <p>Where possible the dialog is opened with the intent saved in the real posted notification's
 * content PendingIntent, not a hand-built copy, so the notification-to-dialog wiring is covered
 * end to end.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SettingsActivityTrustConfirmationTest {

    private static final String BSSID = "aa:bb:cc:dd:ee:01";
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
        KeepADBTrustedNetwork.resetVerifiedTrustForTesting();
    }

    @After
    public void tearDown() {
        prefs().edit().clear().commit();
        KeepADB.resetForTesting();
        KeepADBRegisterClient.resetHttpTransport();
        KeepADBTrustedNetwork.resetVerifiedTrustForTesting();
        unlockDevice();
    }

    @Test
    public void tappingTheDetailsOffPromptNamesTheNetworkAndAllowTrustsExactlyThatBssid() {
        Intent tap = promptTapIntentFor("Cafe-WLAN", BSSID);

        ActivityController<SettingsActivity> controller = open(tap);
        AlertDialog dialog = controller.get().getActiveTrustConfirmationDialog();
        assertNotNull("The tap must open the confirmation dialog", dialog);
        String message = messageOf(dialog);
        assertTrue("The dialog must name the network: " + message, message.contains("Cafe-WLAN"));
        assertTrue("The dialog must name the BSSID: " + message, message.contains(BSSID));
        assertTrue("Opening the dialog must not trust anything by itself",
                KeepADBTrustedNetwork.getEntries(context).isEmpty());

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        List<KeepADBTrustedNetwork.Entry> entries = KeepADBTrustedNetwork.getEntries(context);
        assertEquals(1, entries.size());
        assertEquals(BSSID, entries.get(0).bssid);
        assertEquals("Cafe-WLAN", entries.get(0).label);
        assertNull("The answered prompt must go away", postedPrompt());
        assertTrue(KeepADBBlockedNetworkHistory.getEntries(context).isEmpty());
        controller.pause().stop().destroy();
    }

    @Test
    public void blockInTheDialogTrustsNothingAndBehavesLikeTheNotificationsBlockAction() {
        Intent tap = promptTapIntentFor("Cafe-WLAN", BSSID);
        // The platform auto-cancels on tap; keep a prompt posted to see that block removes it.
        KeepADBNetworkTrustPrompt.reshow(context, BSSID, "Cafe-WLAN");
        assertNotNull(postedPrompt());

        ActivityController<SettingsActivity> controller = open(tap);
        AlertDialog dialog = controller.get().getActiveTrustConfirmationDialog();
        assertNotNull(dialog);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        ShadowLooper.idleMainLooper();

        assertTrue(KeepADBTrustedNetwork.getEntries(context).isEmpty());
        assertFalse(KeepADB.isEnabled(context));
        assertNull("Block must remove the prompt", postedPrompt());
        assertEquals("Declining keeps the access point reviewable in Settings", 1,
                KeepADBBlockedNetworkHistory.getEntries(context).size());
        controller.pause().stop().destroy();
    }

    /**
     * Roam between the notification and the tap: the device is now on a different access point
     * (here even with the same SSID, the case a user cannot tell apart by name). The dialog must
     * still name and trust the prompted BSSID, never the current one.
     */
    @Test
    public void aRoamBeforeTheTapNeitherShowsNorTrustsTheNewAccessPoint() {
        Intent tap = promptTapIntentFor("Cafe-WLAN", BSSID);
        connectTo("Cafe-WLAN", OTHER_BSSID);
        KeepADBBlockedNetworkHistory.record(context,
                new KeepADBNetworkIdentity("\"Cafe-WLAN\"", OTHER_BSSID), 2L);

        ActivityController<SettingsActivity> controller = open(tap);
        AlertDialog dialog = controller.get().getActiveTrustConfirmationDialog();
        assertNotNull(dialog);
        String message = messageOf(dialog);
        assertTrue(message, message.contains(BSSID));
        assertFalse("The dialog must not name the current access point: " + message,
                message.contains(OTHER_BSSID));

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        assertOnlyTrusted(BSSID);
        controller.pause().stop().destroy();
    }

    /** Roam between showing the dialog and the click: the captured BSSID must not be re-read. */
    @Test
    public void aRoamWhileTheDialogIsOpenDoesNotSwapTheTrustedBssid() {
        Intent tap = promptTapIntentFor("Cafe-WLAN", BSSID);
        ActivityController<SettingsActivity> controller = open(tap);
        AlertDialog dialog = controller.get().getActiveTrustConfirmationDialog();
        assertNotNull(dialog);

        connectTo("Other-WLAN", OTHER_BSSID);
        KeepADBBlockedNetworkHistory.record(context,
                new KeepADBNetworkIdentity("\"Other-WLAN\"", OTHER_BSSID), 2L);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        assertOnlyTrusted(BSSID);
        assertEquals("Cafe-WLAN", KeepADBTrustedNetwork.getEntries(context).get(0).label);
        controller.pause().stop().destroy();
    }

    /**
     * A BSSID the app never recorded as blocked (a substituted or stale extra) offers no trust
     * choice at all: no confirmation dialog, only the recently-blocked list, where nothing is
     * trusted without its own click.
     */
    @Test
    public void anUnrecordedBssidOpensNoConfirmationAndTrustsNothing() {
        KeepADBBlockedNetworkHistory.record(context,
                new KeepADBNetworkIdentity("\"Cafe-WLAN\"", BSSID), 1L);

        ActivityController<SettingsActivity> controller = open(
                KeepADBNetworkTrustPrompt.confirmInAppIntent(context, "aa:bb:cc:dd:ee:99"));
        SettingsActivity activity = controller.get();

        assertNull(activity.getActiveTrustConfirmationDialog());
        assertNotNull("The recently-blocked list opens instead",
                activity.getActiveBlockedNetworksDialog());
        assertTrue(KeepADBTrustedNetwork.getEntries(context).isEmpty());
        controller.pause().stop().destroy();

        for (String bssid : new String[] {null, "", KeepADBNetworkIdentity.REDACTED_BSSID,
                KeepADBNetworkIdentity.UNSET_BSSID}) {
            ActivityController<SettingsActivity> c = open(
                    KeepADBNetworkTrustPrompt.confirmInAppIntent(context, bssid));
            assertNull("No confirmation for placeholder BSSID " + bssid,
                    c.get().getActiveTrustConfirmationDialog());
            c.pause().stop().destroy();
        }
        assertTrue(KeepADBTrustedNetwork.getEntries(context).isEmpty());
    }

    /** The name shown and stored comes from the app's own record, never from an intent extra. */
    @Test
    public void aLabelExtraCannotRenameTheNetworkTheDialogShowsOrStores() {
        KeepADBBlockedNetworkHistory.record(context,
                new KeepADBNetworkIdentity("\"Cafe-WLAN\"", BSSID), 1L);
        Intent forged = KeepADBNetworkTrustPrompt.confirmInAppIntent(context, BSSID)
                .putExtra(KeepADBNetworkTrustPrompt.EXTRA_LABEL, "Home-WLAN");

        ActivityController<SettingsActivity> controller = open(forged);
        AlertDialog dialog = controller.get().getActiveTrustConfirmationDialog();
        assertNotNull(dialog);
        assertFalse(messageOf(dialog).contains("Home-WLAN"));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        assertEquals("Cafe-WLAN", KeepADBTrustedNetwork.getEntries(context).get(0).label);
        controller.pause().stop().destroy();
    }

    /** Only the prompt's own action opens the dialog; a BSSID extra alone does nothing. */
    @Test
    public void aBssidExtraWithoutTheConfirmActionOpensNoDialog() {
        KeepADBBlockedNetworkHistory.record(context,
                new KeepADBNetworkIdentity("\"Cafe-WLAN\"", BSSID), 1L);
        Intent plain = new Intent(context, SettingsActivity.class)
                .putExtra(KeepADBNetworkTrustPrompt.EXTRA_BSSID, BSSID);

        ActivityController<SettingsActivity> controller = open(plain);

        assertNull(controller.get().getActiveTrustConfirmationDialog());
        controller.pause().stop().destroy();
    }

    /**
     * #604: a rotation ({@code recreate()}) must not lose the dialog. The intent's action/extra is
     * already consumed by the first {@code onResume()}, so the recreated instance can only be
     * showing the dialog again if the BSSID survived in {@code onSaveInstanceState}/{@code
     * onCreate}'s saved-instance bundle -- and it must be the same BSSID, resolved again from the
     * app's own blocked-network record, not re-read from the (by then actionless) intent.
     */
    @Test
    public void aRotationKeepsTheConfirmationDialogShowingTheSameNetwork() {
        Intent tap = promptTapIntentFor("Cafe-WLAN", BSSID);
        ActivityController<SettingsActivity> controller = open(tap);
        AlertDialog dialogBeforeRotation = controller.get().getActiveTrustConfirmationDialog();
        assertNotNull(dialogBeforeRotation);

        controller.recreate();
        ShadowLooper.idleMainLooper();

        SettingsActivity recreated = controller.get();
        AlertDialog dialogAfterRotation = recreated.getActiveTrustConfirmationDialog();
        assertNotNull("The dialog must reappear after rotation", dialogAfterRotation);
        String message = messageOf(dialogAfterRotation);
        assertTrue("Must still name the originally prompted network: " + message,
                message.contains("Cafe-WLAN"));
        assertTrue("Must still name the originally prompted BSSID: " + message,
                message.contains(BSSID));

        dialogAfterRotation.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        assertOnlyTrusted(BSSID);
        controller.pause().stop().destroy();
    }

    @Test
    public void theConfirmIntentIsConsumedAndALaterResumeDoesNotAskAgain() {
        Intent tap = promptTapIntentFor("Cafe-WLAN", BSSID);
        ActivityController<SettingsActivity> controller = open(tap);
        SettingsActivity activity = controller.get();
        AlertDialog dialog = activity.getActiveTrustConfirmationDialog();
        assertNotNull(dialog);
        assertNull(activity.getIntent().getAction());
        assertFalse(activity.getIntent().hasExtra(KeepADBNetworkTrustPrompt.EXTRA_BSSID));

        dialog.dismiss();
        ShadowLooper.idleMainLooper();
        controller.pause().resume();
        ShadowLooper.idleMainLooper();

        assertNull(activity.getActiveTrustConfirmationDialog());
        assertTrue(KeepADBTrustedNetwork.getEntries(context).isEmpty());
        controller.pause().stop().destroy();
    }

    /**
     * The auth gate stays the notification allow action's own: should the dialog ever be clicked
     * while the device reports itself locked, nothing is trusted and the prompt is re-offered.
     */
    @Test
    public void allowWhileTheDeviceReportsLockedTrustsNothing() {
        Intent tap = promptTapIntentFor("Cafe-WLAN", BSSID);
        ActivityController<SettingsActivity> controller = open(tap);
        AlertDialog dialog = controller.get().getActiveTrustConfirmationDialog();
        assertNotNull(dialog);

        lockDevice();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        assertTrue(KeepADBTrustedNetwork.getEntries(context).isEmpty());
        assertNotNull("The question must stay open", postedPrompt());
        controller.pause().stop().destroy();
    }

    // --- helpers ----------------------------------------------------------------------------

    /**
     * Raises the real details-off prompt for {@code (ssid, bssid)} and returns the intent its
     * content PendingIntent would start.
     */
    private Intent promptTapIntentFor(String ssid, String bssid) {
        connectTo(ssid, bssid);
        assertTrue(KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));
        Notification prompt = postedPrompt();
        assertNotNull(prompt);
        Intent tap = new Intent(shadowOf(prompt.contentIntent).getSavedIntent());
        KeepADBNetworkTrustPrompt.cancel(context);
        return tap;
    }

    private ActivityController<SettingsActivity> open(Intent intent) {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class, intent).setup();
        ShadowLooper.idleMainLooper();
        return controller;
    }

    private void assertOnlyTrusted(String bssid) {
        List<KeepADBTrustedNetwork.Entry> entries = KeepADBTrustedNetwork.getEntries(context);
        assertEquals(1, entries.size());
        assertEquals(bssid, entries.get(0).bssid);
    }

    private static String messageOf(AlertDialog dialog) {
        TextView message = dialog.findViewById(android.R.id.message);
        assertNotNull(message);
        return String.valueOf(message.getText());
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

    private void lockDevice() {
        shadowOf(context.getSystemService(KeyguardManager.class)).setIsDeviceLocked(true);
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
