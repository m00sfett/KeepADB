package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.robolectric.Shadows.shadowOf;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowNotificationManager;

/**
 * Unit and behavioral tests for {@link KeepADBUsbNotification} (Issue #323).
 * Verifies that the USB notification uses the per-app wrapped locale context on Android &lt; 33
 * across title, content text, notification channel, and action buttons, and correctly reacts
 * to connect, disconnect, and cancel.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32)
public class KeepADBUsbNotificationTest {

    private final Context context = RuntimeEnvironment.getApplication();

    @Before
    public void setUp() {
        KeepADBUsbNotification.resetForTesting();
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
        context.getSharedPreferences("keepadb_usb_profiles", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
    }

    @After
    public void tearDown() {
        KeepADBUsbNotification.cancel(context);
        KeepADBUsbNotification.resetForTesting();
    }

    @Test
    public void refreshUsesWrappedLocaleContextOnPreTiramisu() {
        KeepADBPreferences.setAppLanguage(context, "de");
        KeepADBUsbProfile.setNotificationEnabled(context, true);

        KeepADBUsbNotification.refresh(context, true);

        NotificationManager manager = context.getSystemService(NotificationManager.class);
        ShadowNotificationManager shadowManager = shadowOf(manager);
        Notification notification = shadowManager.getNotification(KeepADBUsbNotification.NOTIFICATION_ID);
        assertNotNull("USB notification should be posted when enabled and connected", notification);

        String title = notification.extras.getString(Notification.EXTRA_TITLE);
        assertEquals("USB-ADB aktiv", title);

        CharSequence content = notification.extras.getCharSequence(Notification.EXTRA_TEXT);
        assertNotNull(content);
        assertEquals("Kein Hostprofil ausgewählt", content.toString());

        NotificationChannel channel = manager.getNotificationChannel(KeepADBUsbNotification.CHANNEL_ID);
        assertNotNull("USB notification channel must be created", channel);
        assertEquals("USB-ADB-Verbindung", channel.getName().toString());
        assertEquals("Das aktive USB-ADB-Hostprofil", channel.getDescription());

        assertNotNull(notification.actions);
        assertEquals(1, notification.actions.length);
        assertEquals("Hostprofil anlegen", notification.actions[0].title.toString());
    }

    @Test
    public void refreshInFrenchUsesFrenchLocaleStrings() {
        KeepADBPreferences.setAppLanguage(context, "fr");
        KeepADBUsbProfile.setNotificationEnabled(context, true);

        KeepADBUsbNotification.refresh(context, true);

        NotificationManager manager = context.getSystemService(NotificationManager.class);
        ShadowNotificationManager shadowManager = shadowOf(manager);
        Notification notification = shadowManager.getNotification(KeepADBUsbNotification.NOTIFICATION_ID);
        assertNotNull(notification);

        String title = notification.extras.getString(Notification.EXTRA_TITLE);
        assertEquals("USB-ADB actif", title);

        CharSequence content = notification.extras.getCharSequence(Notification.EXTRA_TEXT);
        assertNotNull(content);
        assertEquals("Aucun profil d’hôte sélectionné", content.toString());

        NotificationChannel channel = manager.getNotificationChannel(KeepADBUsbNotification.CHANNEL_ID);
        assertNotNull("USB notification channel must be created", channel);
        assertEquals("Connexion USB-ADB", channel.getName().toString());
        assertEquals("Profil d’hôte USB-ADB actif", channel.getDescription());

        assertNotNull(notification.actions);
        assertEquals(1, notification.actions.length);
        assertEquals("Créer un profil d’hôte", notification.actions[0].title.toString());
    }

    @Test
    public void refreshWithProfilesAndHandoverActionLocalizesAllActions() {
        KeepADBPreferences.setAppLanguage(context, "de");
        KeepADBUsbProfile.setNotificationEnabled(context, true);
        KeepADBPreferences.setUsbWlanHandoverMode(context, KeepADBPreferences.USB_WLAN_HANDOVER_MODE_MANUAL);
        KeepADBUsbProfile.add(context, "ThinkPad", "192.168.1.50", "thinkpad.local", "");

        KeepADBUsbNotification.refresh(context, true);

        NotificationManager manager = context.getSystemService(NotificationManager.class);
        Notification notification = shadowOf(manager).getNotification(KeepADBUsbNotification.NOTIFICATION_ID);
        assertNotNull(notification);

        assertNotNull(notification.actions);
        assertEquals(3, notification.actions.length);
        assertEquals("Profil wechseln", notification.actions[0].title.toString());
        assertEquals("Neues Profil", notification.actions[1].title.toString());
        assertEquals("WLAN-ADB aktivieren", notification.actions[2].title.toString());
    }

    @Test
    public void refreshWithHandoverErrorUsesLocalizedErrorMessage() {
        KeepADBPreferences.setAppLanguage(context, "de");
        KeepADBUsbProfile.setNotificationEnabled(context, true);
        KeepADBPreferences.setUsbWlanHandoverMode(context, KeepADBPreferences.USB_WLAN_HANDOVER_MODE_MANUAL);

        KeepADBUsbNotification.reportManualActionResult(context, false);

        NotificationManager manager = context.getSystemService(NotificationManager.class);
        Notification notification = shadowOf(manager).getNotification(KeepADBUsbNotification.NOTIFICATION_ID);
        assertNotNull(notification);

        CharSequence content = notification.extras.getCharSequence(Notification.EXTRA_TEXT);
        assertNotNull(content);
        assertEquals("WLAN-ADB konnte nicht aktiviert werden. Berechtigung prüfen.", content.toString());

        // Switch to French and verify the error message is translated
        KeepADBPreferences.setAppLanguage(context, "fr");
        KeepADBUsbNotification.refresh(context, true);

        Notification notificationFr = shadowOf(manager).getNotification(KeepADBUsbNotification.NOTIFICATION_ID);
        assertNotNull(notificationFr);
        CharSequence contentFr = notificationFr.extras.getCharSequence(Notification.EXTRA_TEXT);
        assertNotNull(contentFr);
        assertEquals("Impossible d’activer WLAN-ADB. Vérifiez l’autorisation.", contentFr.toString());
    }

    @Test
    public void refreshWhenProfileNotificationDisabledFallsBackToLocalizedTitle() {
        KeepADBPreferences.setAppLanguage(context, "de");
        KeepADBUsbProfile.setNotificationEnabled(context, true);
        KeepADBUsbProfile.setProfileNotificationEnabled(context, false);

        KeepADBUsbNotification.refresh(context, true);

        NotificationManager manager = context.getSystemService(NotificationManager.class);
        Notification notification = shadowOf(manager).getNotification(KeepADBUsbNotification.NOTIFICATION_ID);
        assertNotNull(notification);

        CharSequence content = notification.extras.getCharSequence(Notification.EXTRA_TEXT);
        assertNotNull(content);
        assertEquals("USB-ADB aktiv", content.toString());
    }

    @Test
    public void refreshWhenDisconnectedCancelsNotification() {
        KeepADBUsbProfile.setNotificationEnabled(context, true);
        KeepADBUsbNotification.refresh(context, true);

        NotificationManager manager = context.getSystemService(NotificationManager.class);
        assertNotNull(shadowOf(manager).getNotification(KeepADBUsbNotification.NOTIFICATION_ID));

        KeepADBUsbNotification.refresh(context, false);
        assertNull(shadowOf(manager).getNotification(KeepADBUsbNotification.NOTIFICATION_ID));
    }
}
