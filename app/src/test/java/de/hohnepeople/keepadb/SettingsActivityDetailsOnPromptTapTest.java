package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlertDialog;
import android.app.Application;
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
 * #759 together with #760, end to end through the real posted notification: with connection details
 * on, the content tap of the "new Wi-Fi" prompt opens the in-app trust confirmation for exactly the
 * access point it was raised for (#759), and a network that is blocked after the prompt was raised
 * is not offered for trust there any more (#760). {@link SettingsActivityTrustConfirmationTest}
 * pins the same dialog for the details-off form; this class covers the details-on form that #759
 * moved onto it, and the 759-by-760 combination in which the recorded entry still exists but its
 * network has been blocked since.
 *
 * <p>Every denied case has its control in the same test: the identical tap offers the dialog while
 * nothing relevant is blocked.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SettingsActivityDetailsOnPromptTapTest {

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
        KeepADBPreferences.setNotificationDetailsEnabled(context, true);
    }

    @After
    public void tearDown() {
        prefs().edit().clear().commit();
        KeepADB.resetForTesting();
        KeepADBRegisterClient.resetHttpTransport();
    }

    @Test
    public void tappingTheDetailsOnPromptOpensTheConfirmationForThePromptedAccessPointEvenAfterARoam() {
        Intent tap = detailsOnPromptTap(SSID, BSSID);
        connectTo(OTHER_SSID, OTHER_BSSID);

        ActivityController<SettingsActivity> controller = open(tap);
        AlertDialog dialog = controller.get().getActiveTrustConfirmationDialog();
        assertNotNull("The tap must open the confirmation dialog, not the top of Settings", dialog);
        String message = messageOf(dialog);
        assertTrue("The dialog names the prompted network: " + message, message.contains(SSID));
        assertTrue("The dialog names the prompted access point: " + message,
                message.contains(BSSID.toUpperCase(java.util.Locale.ROOT)));
        assertFalse("It must not name the network the device roamed to: " + message,
                message.contains(OTHER_SSID));
        assertTrue("Opening the dialog must not trust anything by itself",
                KeepADBTrustedNetwork.getEntries(context).isEmpty());

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        List<KeepADBTrustedNetwork.Entry> entries = KeepADBTrustedNetwork.getEntries(context);
        assertEquals("Allow trusts the prompted access point and nothing else", 1, entries.size());
        assertEquals(BSSID, entries.get(0).bssid);
        assertEquals(SSID, entries.get(0).label);
        controller.pause().stop().destroy();
    }

    @Test
    public void aDetailsOnPromptTappedAfterItsAccessPointWasBlockedOffersNoTrustChoice() {
        Intent tap = detailsOnPromptTap(SSID, BSSID);
        KeepADBNetworkBlocklist.blockBssid(context, BSSID);

        ActivityController<SettingsActivity> controller = open(tap);
        SettingsActivity activity = controller.get();

        assertNull("A blocked access point is not asked about", activity.getActiveTrustConfirmationDialog());
        Intent fallback = shadowOf(activity).getNextStartedActivity();
        assertNotNull("The recently-prevented list opens instead (no crash on the empty result)", fallback);
        assertEquals(NetworkListActivity.class.getName(), fallback.getComponent().getClassName());
        assertEquals(NetworkListActivity.VIEW_PREVENTED,
                fallback.getStringExtra(NetworkListActivity.EXTRA_VIEW));
        assertTrue(KeepADBTrustedNetwork.getEntries(context).isEmpty());
        assertTrue("The block must still be there", KeepADBNetworkBlocklist.isBssidBlocked(context, BSSID));
        controller.pause().stop().destroy();

        // Control: the very same tap opens the dialog once the block is lifted explicitly.
        KeepADBNetworkBlocklist.unblockBssid(context, BSSID);
        ActivityController<SettingsActivity> lifted = open(tap);
        assertNotNull(lifted.get().getActiveTrustConfirmationDialog());
        lifted.pause().stop().destroy();
    }

    @Test
    public void aDetailsOnPromptTappedAfterItsWifiNameWasBlockedOffersNoTrustChoice() {
        Intent tap = detailsOnPromptTap(SSID, BSSID);
        KeepADBNetworkBlocklist.blockSsid(context, SSID);

        ActivityController<SettingsActivity> controller = open(tap);

        assertNull("A blocked Wi-Fi name is not asked about",
                controller.get().getActiveTrustConfirmationDialog());
        assertTrue(KeepADBTrustedNetwork.getEntries(context).isEmpty());
        controller.pause().stop().destroy();

        KeepADBNetworkBlocklist.unblockSsid(context, SSID);
        ActivityController<SettingsActivity> lifted = open(tap);
        assertNotNull(lifted.get().getActiveTrustConfirmationDialog());
        lifted.pause().stop().destroy();
    }

    @Test
    public void aBlockOnAnotherAccessPointDoesNotTakeTheDialogAway() {
        Intent tap = detailsOnPromptTap(SSID, BSSID);
        KeepADBNetworkBlocklist.blockBssid(context, OTHER_BSSID);
        KeepADBNetworkBlocklist.blockSsid(context, OTHER_SSID);

        ActivityController<SettingsActivity> controller = open(tap);

        assertNotNull("The block is about another network", controller.get().getActiveTrustConfirmationDialog());
        controller.pause().stop().destroy();
    }

    @Test
    public void allowOnAConfirmationOpenedBeforeTheBlockTrustsNothingAndKeepsTheBlock() {
        Intent tap = detailsOnPromptTap(SSID, BSSID);
        ActivityController<SettingsActivity> controller = open(tap);
        AlertDialog dialog = controller.get().getActiveTrustConfirmationDialog();
        assertNotNull("Precondition: the dialog was offered before the block", dialog);

        KeepADBNetworkBlocklist.blockBssid(context, BSSID);
        connectTo(SSID, BSSID);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        assertTrue("Trusting must not store anything for a blocked access point",
                KeepADBTrustedNetwork.getEntries(context).isEmpty());
        assertTrue("Trusting must not lift the block", KeepADBNetworkBlocklist.isBssidBlocked(context, BSSID));
        assertFalse(KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
        controller.pause().stop().destroy();
    }

    // --- helpers ----------------------------------------------------------------------------

    /**
     * Raises the real details-on prompt for {@code (ssid, bssid)} and returns the intent its
     * content PendingIntent would start. The text check proves the details-on form is the one
     * under test: it names the network, which the details-off form never does.
     */
    private Intent detailsOnPromptTap(String ssid, String bssid) {
        connectTo(ssid, bssid);
        assertTrue(KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));
        Notification prompt = postedPrompt();
        assertNotNull(prompt);
        String text = String.valueOf(prompt.extras.getCharSequence(Notification.EXTRA_TEXT));
        assertTrue("Precondition: details are on, the prompt names the network: " + text,
                text.contains(ssid));
        Intent tap = new Intent(shadowOf(prompt.contentIntent).getSavedIntent());
        KeepADBNetworkTrustPrompt.cancel(context);
        return tap;
    }

    private ActivityController<SettingsActivity> open(Intent tap) {
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class, new Intent(tap)).setup();
        ShadowLooper.idleMainLooper();
        return controller;
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

    private android.content.SharedPreferences prefs() {
        return context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE);
    }
}
