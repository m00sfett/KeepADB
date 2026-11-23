package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.TreeMap;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * #956: the first start after the update removes what the removed USB-ADB area stored: the
 * notification and handover settings, every host profile (host names and IP addresses), the
 * notification channel and a notification that is still showing. The cleanup runs from the
 * application object, so whichever component is first after the update triggers it.
 *
 * <p>Each test writes the old state first and then runs the application's {@code onCreate} again,
 * which is what the next process start does. Without the cleanup every "gone" assertion is red.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBUsbRemovalMigrationTest {
    private static final String USB_CHANNEL = "keepadb_usb";
    private static final int USB_NOTIFICATION_ID = 2;

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Application app = RuntimeEnvironment.getApplication();
    private NotificationManager manager;

    @Before
    public void setUp() {
        manager = app.getSystemService(NotificationManager.class);
        prefs().edit().clear().commit();
    }

    /** What 1.12.x stored for a user who set up two host profiles and the automatic handover. */
    private void storeTheOldUsbArea() {
        prefs().edit()
                .putBoolean("usb_notification_enabled", true)
                .putBoolean("usb_profile_notification_enabled", false)
                .putInt("usb_profile_next_id", 3)
                .putInt("usb_profile_selected_id", 2)
                .putString("usb_profile_ids", "1,2")
                .putString("usb_profile_1_name", "Desk")
                .putString("usb_profile_1_ip", "192.168.1.50")
                .putString("usb_profile_1_host", "desk.local")
                .putString("usb_profile_1_tailnet", "desk.tailnet.example")
                .putString("usb_profile_2_name", "Laptop")
                .putString("usb_profile_2_ip", "10.0.0.7")
                .putString("usb_profile_2_host", "laptop.fritz.box")
                .putString("usb_profile_2_tailnet", "")
                // Orphans: a profile that is not in the id list any more and a field no version
                // of the cleanup knows by name. Only a prefix match removes these.
                .putString("usb_profile_99_host", "orphan.example")
                .putString("usb_profile_1_note", "left by an unknown build")
                .putString("usb_wlan_handover_mode", "automatic")
                .commit();
    }

    private void storeSettingsThatMustSurvive() {
        prefs().edit()
                .putBoolean("keep_alive_enabled", true)
                .putString("trusted_network_mode", "allowlist")
                .putString("register_webhook_url", "http://100.111.111.21:50829/register/s20")
                .putBoolean("register_webhook_enabled", true)
                .putString("app_language", "de")
                .commit();
    }

    private void nextProcessStart() {
        ((KeepADBApplication) app).onCreate();
    }

    @Test
    public void everyStoredUsbKeyIsGoneAfterTheFirstStartAndTheOtherSettingsAreUntouched() {
        storeSettingsThatMustSurvive();
        storeTheOldUsbArea();
        Map<String, ?> before = prefs().getAll();
        assertTrue("control: the old state is really stored", before.containsKey("usb_profile_2_ip"));

        nextProcessStart();

        Map<String, Object> expected = new TreeMap<>();
        for (Map.Entry<String, ?> entry : before.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith("usb_")) expected.put(key, entry.getValue());
        }
        expected.put("privacy_split_initialized", true);
        expected.put("notification_privacy_enabled", true);
        expected.put("widget_privacy_enabled", false);
        expected.put("onboarding_existing_install", true);
        expected.put("open_network_shutdown_enabled", false); // #997: existing installation
        assertEquals("only the USB keys were removed", expected, new TreeMap<>(prefs().getAll()));
        for (String key : prefs().getAll().keySet()) {
            assertFalse("a USB key is left: " + key, key.startsWith("usb_"));
        }
    }

    @Test
    public void theNotificationChannelAndAShowingNotificationAreGone() {
        manager.createNotificationChannel(new NotificationChannel(
                USB_CHANNEL, "USB-ADB", NotificationManager.IMPORTANCE_LOW));
        manager.createNotificationChannel(new NotificationChannel(
                KeepADBNotification.CHANNEL_ID, "Endpoint", NotificationManager.IMPORTANCE_LOW));
        manager.notify(USB_NOTIFICATION_ID, new Notification.Builder(app, USB_CHANNEL)
                .setSmallIcon(R.drawable.ic_keepadb).setContentTitle("USB-ADB active").build());
        manager.notify(KeepADBNotification.NOTIFICATION_ID,
                new Notification.Builder(app, KeepADBNotification.CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_keepadb).setContentTitle("Endpoint").build());
        assertNotNull("control: the USB notification is showing",
                shadowOf(manager).getNotification(USB_NOTIFICATION_ID));

        nextProcessStart();

        assertNull(shadowOf(manager).getNotification(USB_NOTIFICATION_ID));
        assertNull(manager.getNotificationChannel(USB_CHANNEL));
        assertNotNull("the endpoint notification is not touched",
                shadowOf(manager).getNotification(KeepADBNotification.NOTIFICATION_ID));
        assertNotNull("the endpoint channel is not touched",
                manager.getNotificationChannel(KeepADBNotification.CHANNEL_ID));
    }

    @Test
    public void theChannelIsRemovedEvenWhenNoNotificationIsShowing() {
        manager.createNotificationChannel(new NotificationChannel(
                USB_CHANNEL, "USB-ADB", NotificationManager.IMPORTANCE_LOW));

        nextProcessStart();

        assertNull(manager.getNotificationChannel(USB_CHANNEL));
    }

    @Test
    public void aSecondStartChangesNothingAndTheOtherNotificationOfTheSameIdIsKept() {
        storeSettingsThatMustSurvive();
        storeTheOldUsbArea();
        manager.createNotificationChannel(new NotificationChannel(
                USB_CHANNEL, "USB-ADB", NotificationManager.IMPORTANCE_LOW));
        nextProcessStart();
        Map<String, ?> afterFirst = prefs().getAll();

        // Id 2 is free again: a notification of another channel under it must survive.
        manager.createNotificationChannel(new NotificationChannel(
                KeepADBNotification.CHANNEL_ID, "Endpoint", NotificationManager.IMPORTANCE_LOW));
        manager.notify(USB_NOTIFICATION_ID, new Notification.Builder(app, KeepADBNotification.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_keepadb).setContentTitle("Other").build());
        nextProcessStart();

        assertEquals(new TreeMap<>(afterFirst), new TreeMap<>(prefs().getAll()));
        assertNotNull(shadowOf(manager).getNotification(USB_NOTIFICATION_ID));
        assertNull(manager.getNotificationChannel(USB_CHANNEL));
    }

    @Test
    public void aNewInstallationGetsNoWriteFromTheCleanup() {
        // The application's own first start already ran for this test; a new installation keeps
        // exactly the privacy marker, and a later start adds nothing (in particular not the
        // assistant's existing/new decision, which must stay open for the first real question).
        prefs().edit().clear().commit();
        KeepADBPreferencesMigrations.migrateOpenNetworkShutdown(app);
        KeepADBPreferencesMigrations.migratePrivacySplit(app);
        Map<String, ?> before = prefs().getAll();

        nextProcessStart();

        assertEquals(new TreeMap<>(before), new TreeMap<>(prefs().getAll()));
        assertFalse(prefs().contains("onboarding_existing_install"));
    }

    @Test
    public void anInstallationWhoseOnlyKeysWereUsbKeysStaysAnExistingInstallation() {
        prefs().edit().clear().commit();
        prefs().edit().putString("usb_wlan_handover_mode", "manual").commit();

        nextProcessStart();

        assertFalse(prefs().contains("usb_wlan_handover_mode"));
        assertTrue("it was not a new installation before the update, and still is not",
                KeepADBOnboarding.isExistingInstall(app));
        assertTrue("the privacy split saw the stored key and kept notification details hidden",
                KeepADBPreferences.isNotificationPrivacyEnabled(app));
    }

    @Test
    public void everyComponentRunsInTheProcessWhoseApplicationDoesTheCleanup() throws IOException {
        // The screens, the boot receiver, the service, the tile and the widgets all start the
        // process through the manifest's application object; a component with its own process
        // would start without it.
        String manifest = new String(Files.readAllBytes(projectRoot()
                .resolve("app/src/main/AndroidManifest.xml")), StandardCharsets.UTF_8);

        assertTrue(manifest.contains("android:name=\".KeepADBApplication\""));
        assertFalse("a component with its own process would skip the application's cleanup",
                manifest.contains("android:process"));
        assertTrue(RuntimeEnvironment.getApplication() instanceof KeepADBApplication);
    }

    private SharedPreferences prefs() {
        return app.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE);
    }

    private static Path projectRoot() {
        Path directory = Paths.get("").toAbsolutePath();
        while (directory != null && !Files.exists(directory.resolve("settings.gradle"))) {
            directory = directory.getParent();
        }
        if (directory == null) throw new IllegalStateException("Could not locate project root");
        return directory;
    }
}
