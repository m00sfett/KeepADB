package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowNotificationManager;
import org.robolectric.shadows.ShadowPendingIntent;
import org.robolectric.shadows.ShadowWifiInfo;

/**
 * #759: tap-target table (notification, state, content-tap target intent) from the UX concept
 * 3.1, one test per row that this phase owns. Rows 8 to 10 (identity unavailable) are pinned in
 * {@link KeepADBNetworkTrustPromptTest}; the end-to-end tap of rows 6 and 7 into the dialog is
 * pinned in {@link SettingsActivityDetailsOnPromptTapTest} and {@link
 * SettingsActivityTrustConfirmationTest}. Row 14 (widget, tile) are toggles without a content tap,
 * rows 5 and 13 belong to the force mode (#763) and do not exist yet.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBNotificationTapTargetsTest {

    private static final String BSSID = "aa:bb:cc:dd:ee:01";

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = RuntimeEnvironment.getApplication();

    @Before
    public void setUp() {
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.POST_NOTIFICATIONS,
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit();
        KeepADB.resetForTesting();
        KeepADBUsbNotification.resetForTesting();
    }

    @After
    public void tearDown() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit();
        KeepADB.resetForTesting();
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(null);
    }

    // --- Rows 1 to 4: main notification states open MainActivity ----------------------------

    @Test
    public void row1EndpointActiveOpensMainActivity() {
        Notification notification = KeepADBNotification.getServiceNotification(context);
        assertOpensMainActivity(notification);
        KeepADBNotification.renderEndpoint(context, "192.0.2.1", 40000);
        assertOpensMainActivity(posted(KeepADBNotification.NOTIFICATION_ID));
    }

    @Test
    public void row2SearchingOpensMainActivity() {
        KeepADBNotification.renderSearching(context);
        assertOpensMainActivity(posted(KeepADBNotification.NOTIFICATION_ID));
    }

    @Test
    public void row3DisabledKeepAliveWaitingOpensMainActivity() {
        KeepADBNotification.renderDisabledKeepAliveWaiting(context);
        assertOpensMainActivity(posted(KeepADBNotification.NOTIFICATION_ID));
    }

    @Test
    public void row4PermissionMissingOpensMainActivityInPhase1() {
        KeepADBNotification.showPermissionMissing(context);
        assertOpensMainActivity(posted(KeepADBNotification.NOTIFICATION_ID));
    }

    // --- Rows 6 and 7: new WLAN prompt opens the in-app confirmation for its own BSSID -------

    @Test
    public void row6NewWlanWithDetailsOpensTheConfirmationForThePromptedBssid() {
        KeepADBPreferences.setNotificationDetailsEnabled(context, true);
        connectTo("Cafe-WLAN", BSSID);
        assertTrue(KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));

        assertConfirmationTarget(posted(KeepADBNetworkTrustPrompt.NOTIFICATION_ID));
    }

    @Test
    public void row7NewWlanWithoutDetailsOpensTheSameConfirmation() {
        KeepADBPreferences.setNotificationDetailsEnabled(context, false);
        connectTo("Cafe-WLAN", BSSID);
        assertTrue(KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context));

        assertConfirmationTarget(posted(KeepADBNetworkTrustPrompt.NOTIFICATION_ID));
    }

    // --- Row 11: USB with profile notification opens the profile dialog ----------------------

    @Test
    public void row11UsbWithProfileNotificationOpensTheProfileDialogAndDoesNotFocusTheUsbCard() {
        KeepADBUsbProfile.setNotificationEnabled(context, true);
        KeepADBUsbProfile.setProfileNotificationEnabled(context, true);

        // Without a profile the tap offers to create one ...
        KeepADBUsbNotification.refresh(context, true);
        Intent create = savedIntent(posted(KeepADBUsbNotification.NOTIFICATION_ID));
        assertEquals(SettingsActivity.class.getName(), create.getComponent().getClassName());
        assertEquals(KeepADBUsbNotification.ACTION_CREATE,
                create.getStringExtra(KeepADBUsbNotification.EXTRA_PROFILE_ACTION));
        assertFalse(create.hasExtra(SettingsActivity.EXTRA_FOCUS_USB));

        // ... with one it opens the switcher.
        KeepADBUsbProfile.add(context, "ThinkPad", "192.168.1.50", "thinkpad.local", "");
        KeepADBUsbNotification.refresh(context, true);
        Intent select = savedIntent(posted(KeepADBUsbNotification.NOTIFICATION_ID));
        assertEquals(SettingsActivity.class.getName(), select.getComponent().getClassName());
        assertEquals(KeepADBUsbNotification.ACTION_SWITCH,
                select.getStringExtra(KeepADBUsbNotification.EXTRA_PROFILE_ACTION));
        assertFalse(select.hasExtra(SettingsActivity.EXTRA_FOCUS_USB));
    }

    // --- Row 12: USB without profile notification focuses the USB card -----------------------

    @Test
    public void row12UsbWithoutProfileNotificationOpensSettingsFocusedOnUsb() {
        KeepADBUsbProfile.setNotificationEnabled(context, true);
        KeepADBUsbProfile.setProfileNotificationEnabled(context, false);
        KeepADBUsbNotification.refresh(context, true);

        Intent target = savedIntent(posted(KeepADBUsbNotification.NOTIFICATION_ID));
        assertEquals(SettingsActivity.class.getName(), target.getComponent().getClassName());
        assertTrue(target.getBooleanExtra(SettingsActivity.EXTRA_FOCUS_USB, false));
        assertFalse(target.hasExtra(KeepADBUsbNotification.EXTRA_PROFILE_ACTION));
    }

    // --- N6 / #603: SettingsActivity PendingIntents never share an identity ------------------

    @Test
    public void settingsActivityContentIntentsOfDifferentNotificationsAreDistinct() {
        KeepADBPreferences.setNotificationDetailsEnabled(context, true);
        connectTo("Cafe-WLAN", BSSID);
        KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context);
        KeepADBUsbProfile.setNotificationEnabled(context, true);
        KeepADBUsbProfile.setProfileNotificationEnabled(context, false);
        KeepADBUsbNotification.refresh(context, true);

        PendingIntent trust = posted(KeepADBNetworkTrustPrompt.NOTIFICATION_ID).contentIntent;
        PendingIntent usb = posted(KeepADBUsbNotification.NOTIFICATION_ID).contentIntent;
        ShadowPendingIntent trustShadow = shadowOf(trust);
        ShadowPendingIntent usbShadow = shadowOf(usb);
        assertTrue("Both must differ by request code or by intent identity (action/component)",
                trustShadow.getRequestCode() != usbShadow.getRequestCode()
                        || !trustShadow.getSavedIntent().filterEquals(usbShadow.getSavedIntent()));
    }

    // --- Lock screen: no new activity may show over the keyguard -----------------------------

    @Test
    public void noActivityDeclaresShowWhenLockedOrTurnScreenOn() throws IOException {
        Path manifest = Paths.get("src/main/AndroidManifest.xml");
        if (!Files.exists(manifest)) manifest = Paths.get("app/src/main/AndroidManifest.xml");
        String text = new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8);
        assertFalse("showWhenLocked would expose notification targets on the lock screen",
                text.contains("showWhenLocked"));
        assertFalse(text.contains("turnScreenOn"));
    }

    // --- Public version stays redacted and action-free (lock screen) -------------------------

    @Test
    public void publicVersionsOfTheTouchedNotificationsKeepNoNameNoActionNoIntent() {
        KeepADBPreferences.setNotificationDetailsEnabled(context, true);
        connectTo("Cafe-WLAN", BSSID);
        KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context);
        Notification prompt = posted(KeepADBNetworkTrustPrompt.NOTIFICATION_ID);
        assertPublicVersionIsNeutral(prompt);

        KeepADBUsbProfile.setNotificationEnabled(context, true);
        KeepADBUsbProfile.setProfileNotificationEnabled(context, false);
        KeepADBUsbNotification.refresh(context, true);
        assertPublicVersionIsNeutral(posted(KeepADBUsbNotification.NOTIFICATION_ID));
    }

    private void assertPublicVersionIsNeutral(Notification notification) {
        Notification pub = notification.publicVersion;
        assertNotNull(pub);
        assertNull(pub.contentIntent);
        assertTrue(pub.actions == null || pub.actions.length == 0);
        String text = String.valueOf(pub.extras.getCharSequence(Notification.EXTRA_TEXT));
        assertFalse(text.contains("Cafe-WLAN"));
        assertFalse(text.toLowerCase(java.util.Locale.ROOT).contains(BSSID));
    }

    private void assertConfirmationTarget(Notification notification) {
        Intent target = savedIntent(notification);
        assertEquals(SettingsActivity.class.getName(), target.getComponent().getClassName());
        assertEquals(KeepADBNetworkTrustPrompt.ACTION_CONFIRM_IN_APP, target.getAction());
        assertEquals(BSSID, target.getStringExtra(KeepADBNetworkTrustPrompt.EXTRA_BSSID));
        assertFalse(target.hasExtra(KeepADBNetworkTrustPrompt.EXTRA_LABEL));
    }

    private void assertOpensMainActivity(Notification notification) {
        assertNotNull(notification);
        Intent target = savedIntent(notification);
        assertEquals(MainActivity.class.getName(), target.getComponent().getClassName());
    }

    private Intent savedIntent(Notification notification) {
        assertNotNull(notification);
        assertNotNull("content intent missing", notification.contentIntent);
        return shadowOf(notification.contentIntent).getSavedIntent();
    }

    private Notification posted(int id) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        ShadowNotificationManager shadow = shadowOf(manager);
        return shadow.getNotification(id);
    }

    private void connectTo(String ssid, String bssid) {
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = ShadowWifiInfo.newInstance();
        shadowOf(info).setSSID(ssid);
        shadowOf(info).setBSSID(bssid);
        shadowOf(wifiManager).setConnectionInfo(info);
    }
}
