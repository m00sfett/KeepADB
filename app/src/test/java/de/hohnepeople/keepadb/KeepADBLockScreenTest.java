package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.widget.Switch;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * #920: the option "Hide on the lock screen" keeps every KeepADB notification off the lock screen
 * ({@code VISIBILITY_SECRET}), independent of Android's sensitive-content setting, and leaves the
 * unlocked shade as it was.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBLockScreenTest {

    private static final String BSSID = "aa:bb:cc:dd:ee:01";
    private static final String SSID = "HomeNet";

    private final Application app = ApplicationProvider.getApplicationContext();

    @Before
    public void setUp() {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
        prefs().edit().clear().commit();
        KeepADB.resetForTesting();
        KeepADBPreferences.setAppLanguage(app, "en");
        // A written preference makes the #904 split treat this as an existing installation; pin
        // the notification switch to its new-installation default (details shown).
        KeepADBPreferences.setNotificationPrivacyEnabled(app, false);
    }

    @After
    public void tearDown() {
        prefs().edit().clear().commit();
        KeepADB.resetForTesting();
        KeepADBEndpointCoordinator.resetForTesting();
    }

    private android.content.SharedPreferences prefs() {
        return app.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE);
    }

    private NotificationManager manager() {
        return app.getSystemService(NotificationManager.class);
    }

    private Notification posted(int id) {
        return shadowOf(manager()).getNotification(id);
    }

    @Test
    public void theOptionIsOffByDefaultAndAMigrationLeavesItOff() {
        assertFalse(KeepADBPreferences.isLockScreenPrivacyEnabled(app));
        // An existing installation from before #904/#920: the privacy split migrates, this stays off.
        prefs().edit().putBoolean("privacy_mode_enabled", true).commit();
        KeepADBPreferences.isNotificationPrivacyEnabled(app);
        assertFalse(KeepADBPreferences.isLockScreenPrivacyEnabled(app));
    }

    @Test
    public void applyMarksANotificationSecretOnlyWhileTheOptionIsOn() {
        Notification off = KeepADBLockScreen.apply(app, new Notification.Builder(app, "c").build());
        assertNotEquals("counter-check: off leaves the visibility alone",
                Notification.VISIBILITY_SECRET, off.visibility);

        KeepADBPreferences.setLockScreenPrivacyEnabled(app, true);
        Notification on = KeepADBLockScreen.apply(app, new Notification.Builder(app, "c").build());
        assertEquals(Notification.VISIBILITY_SECRET, on.visibility);
    }

    @Test
    public void theMainNotificationIsSecretWithTheOptionAndKeepsItsDetailsForTheShade() {
        KeepADBNotification.renderEndpoint(app, "192.168.1.50", 39123);
        Notification before = posted(KeepADBNotification.NOTIFICATION_ID);
        assertNotNull(before);
        assertNotEquals(Notification.VISIBILITY_SECRET, before.visibility);

        KeepADBPreferences.setLockScreenPrivacyEnabled(app, true);
        KeepADBNotification.renderEndpoint(app, "192.168.1.50", 39123);
        Notification after = posted(KeepADBNotification.NOTIFICATION_ID);
        assertEquals(Notification.VISIBILITY_SECRET, after.visibility);
        // Unlocked, the shade still shows the endpoint (the notification switch is off).
        assertTrue(String.valueOf(after.extras.getCharSequence(Notification.EXTRA_TEXT))
                .contains("39123"));
    }

    @Test
    public void theTrustPromptWithItsButtonsIsSecretWithTheOption() {
        KeepADBPreferences.setLockScreenPrivacyEnabled(app, true);
        assertTrue(KeepADBNetworkTrustPrompt.reshow(app, BSSID, SSID));
        Notification prompt = posted(KeepADBNetworkTrustPrompt.NOTIFICATION_ID);
        assertNotNull(prompt);
        assertEquals(Notification.VISIBILITY_SECRET, prompt.visibility);
        assertTrue("unlocked, the Allow/Block buttons are still there",
                prompt.actions != null && prompt.actions.length == 2);
    }

    @Test
    public void theUsbNotificationIsSecretWithTheOption() {
        KeepADBPreferences.setLockScreenPrivacyEnabled(app, true);
        KeepADBUsbProfile.setNotificationEnabled(app, true);
        KeepADBUsbNotification.refresh(app, true);
        Notification usb = posted(KeepADBUsbNotification.NOTIFICATION_ID);
        assertNotNull(usb);
        assertEquals(Notification.VISIBILITY_SECRET, usb.visibility);
    }

    @Test
    public void changingTheOptionRepostsWhatIsVisibleAtOnceWithoutAlertingAgain() {
        KeepADBNotification.renderEndpoint(app, "192.168.1.50", 39123);
        KeepADBNetworkTrustPrompt.reshow(app, BSSID, SSID);

        KeepADBPreferences.setLockScreenPrivacyEnabled(app, true);
        KeepADBLockScreen.applyToPosted(app);
        for (int id : new int[] {KeepADBNotification.NOTIFICATION_ID,
                KeepADBNetworkTrustPrompt.NOTIFICATION_ID}) {
            Notification n = posted(id);
            assertEquals(Notification.VISIBILITY_SECRET, n.visibility);
            assertTrue((n.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0);
        }

        KeepADBPreferences.setLockScreenPrivacyEnabled(app, false);
        KeepADBLockScreen.applyToPosted(app);
        assertEquals(Notification.VISIBILITY_PRIVATE,
                posted(KeepADBNotification.NOTIFICATION_ID).visibility);
        assertEquals(Notification.VISIBILITY_PRIVATE,
                posted(KeepADBNetworkTrustPrompt.NOTIFICATION_ID).visibility);
    }

    @Test
    public void theSettingsShowTheSwitchUnderTheNotificationSwitchAndItAppliesAtOnce() {
        KeepADBNotification.renderEndpoint(app, "192.168.1.50", 39123);
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        Switch notifications = activity.findViewById(R.id.privacy_switch_notifications);
        Switch lockScreen = activity.findViewById(R.id.privacy_switch_lockscreen);
        assertNotNull(lockScreen);
        assertFalse("off on a new installation", lockScreen.isChecked());
        android.view.ViewGroup parent = (android.view.ViewGroup) lockScreen.getParent();
        assertTrue("placed under the notification switch",
                parent.indexOfChild(lockScreen) > parent.indexOfChild(notifications));

        lockScreen.performClick();
        assertTrue(KeepADBPreferences.isLockScreenPrivacyEnabled(app));
        assertFalse("it does not switch the notification details off",
                KeepADBPreferences.isNotificationPrivacyEnabled(app));
        assertEquals(Notification.VISIBILITY_SECRET,
                posted(KeepADBNotification.NOTIFICATION_ID).visibility);
    }
}
