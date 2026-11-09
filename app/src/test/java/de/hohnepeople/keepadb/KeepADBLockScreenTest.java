package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
    public void theOptionIsOffByDefault() {
        assertFalse(KeepADBPreferences.isLockScreenPrivacyEnabled(app));
    }

    @Test
    public void theMigrationOfAnExistingInstallationLeavesTheOptionOff() {
        // Review R1: an installation from before #904 with only legacy values stored.
        prefs().edit().clear().commit();
        prefs().edit().putBoolean("privacy_mode_enabled", true)
                .putBoolean("keep_alive_enabled", true).commit();
        KeepADBPreferences.migratePrivacySplit(app);
        assertTrue("the split ran: details hidden, as the old default",
                KeepADBPreferences.isNotificationPrivacyEnabled(app));
        assertTrue(KeepADBPreferences.isWidgetPrivacyEnabled(app));
        assertFalse("and the lock-screen option was not derived",
                KeepADBPreferences.isLockScreenPrivacyEnabled(app));
        assertFalse(prefs().contains("lockscreen_privacy_enabled"));
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
    public void aPostThatReadTheOldValueReachesTheSystemBeforeTheOptionChanges() throws Exception {
        // Review R1: the option is read and the notification posted under one lock, and the
        // option is written under it too, so a toggle cannot slip between the two.
        java.util.concurrent.CountDownLatch inPost = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        KeepADBLockScreen.beforePostForTesting = () -> {
            inPost.countDown();
            try {
                release.await(3, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        try {
            Thread poster = new Thread(() ->
                    KeepADBNotification.renderEndpoint(app, "192.168.1.50", 39123));
            poster.start();
            assertTrue(inPost.await(3, java.util.concurrent.TimeUnit.SECONDS));

            Thread toggler = new Thread(() -> KeepADBLockScreen.setEnabled(app, true));
            toggler.start();
            toggler.join(300);
            assertTrue("the toggle waits for the post that read the old value", toggler.isAlive());
            assertFalse(KeepADBPreferences.isLockScreenPrivacyEnabled(app));

            release.countDown();
            poster.join(3000);
            toggler.join(3000);
            assertTrue(KeepADBPreferences.isLockScreenPrivacyEnabled(app));
            assertNotEquals("the old post went out with the old value",
                    Notification.VISIBILITY_SECRET,
                    posted(KeepADBNotification.NOTIFICATION_ID).visibility);
        } finally {
            KeepADBLockScreen.beforePostForTesting = null;
            release.countDown();
        }
        // Every post from now on carries the new value.
        KeepADBNotification.renderEndpoint(app, "192.168.1.50", 39123);
        assertEquals(Notification.VISIBILITY_SECRET,
                posted(KeepADBNotification.NOTIFICATION_ID).visibility);
    }

    @Test
    public void turningItOnRemovesAPostedTrustNoticeWithDetails() {
        KeepADBNetworkTrustPrompt.reshow(app, BSSID, SSID);
        assertNotNull(posted(KeepADBNetworkTrustPrompt.NOTIFICATION_ID));
        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        ((Switch) activity.findViewById(R.id.privacy_switch_lockscreen)).performClick();
        assertNull("the notice with the network name and buttons is gone; it returns neutral",
                posted(KeepADBNetworkTrustPrompt.NOTIFICATION_ID));
    }

    @Test
    public void theSettingsShowTheSwitchUnderTheNotificationSwitchAndItAppliesAtOnce() {
        // A running Keep-Alive with a verified endpoint, so the coordinator re-renders the card.
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(true));
        KeepADBPreferences.setKeepAliveEnabled(app, true);
        KeepADBEndpointCoordinator.seedEndpointForTesting("192.168.1.50", 39123,
                System.currentTimeMillis());
        KeepADBEndpointCoordinator.setReachabilityProbeForTesting((host, port, timeoutMs) -> true);
        KeepADBEndpointCoordinator.setWorkerStarterForTesting(Thread::run);
        KeepADBEndpointCoordinator.refresh(app);
        assertNotNull(posted(KeepADBNotification.NOTIFICATION_ID));
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
        // The owner re-renders the main card at once, now secret.
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        assertEquals(Notification.VISIBILITY_SECRET,
                posted(KeepADBNotification.NOTIFICATION_ID).visibility);
    }
}
