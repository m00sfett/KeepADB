package de.hohnepeople.keepadb;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Static contracts for Issue #173 (offer WLAN-ADB disable action in notification).
 */
public class KeepADBNotificationActionContractTest {

    @Test
    public void manifestDeclaresKeepADBReceiverWithActionDisable() throws IOException {
        String manifest = read("app/src/main/AndroidManifest.xml");
        assertTrue(manifest.contains("android:name=\".KeepADBReceiver\""));
        int receiverStart = manifest.indexOf("<receiver\n            android:name=\".KeepADBReceiver\"");
        int receiverEnd = manifest.indexOf("</receiver>", receiverStart);
        assertTrue(receiverStart >= 0);
        assertTrue(receiverEnd > receiverStart);
        String receiverManifest = manifest.substring(receiverStart, receiverEnd);

        assertTrue(receiverManifest.contains("android:exported=\"false\""));
        assertTrue(receiverManifest.contains("<action android:name=\"de.hohnepeople.keepadb.ACTION_DISABLE\" />"));
    }

    @Test
    public void notificationBuildsExplicitDisableAction() throws IOException {
        String notification = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBNotification.java");
        assertTrue(notification.contains("disableAction(Context context)"));
        assertTrue(notification.contains("new Intent(context, KeepADBReceiver.class)"));
        assertTrue(notification.contains(".setAction(KeepADBReceiver.ACTION_DISABLE)"));
        assertTrue(notification.contains("PendingIntent.getBroadcast("));
        assertTrue(notification.contains("PendingIntent.FLAG_IMMUTABLE"));
        assertTrue(notification.contains("R.string.notification_action_disable"));
        assertTrue(notification.contains(".addAction(disableAction(context))"));
    }

    @Test
    public void receiverDispatchesSetEnabledWithFalseAndHandlesFailure() throws IOException {
        String receiver = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBReceiver.java");
        assertTrue(receiver.contains("ACTION_DISABLE.equals(action)"));
        assertTrue(receiver.contains("KeepADB.setEnabled(context, false, \"notification\")"));
        assertTrue(receiver.contains("R.string.permission_error_toast"));
        assertTrue(receiver.contains("Toast.makeText("));
        assertTrue(receiver.contains("KeepADBService.sync(context)"));
        assertTrue(receiver.contains("KeepADBNotification.refresh(context)"));
        assertTrue(receiver.contains("KeepADBWidget.refreshAll(context)"));
    }

    @Test
    public void stringResourcesContainNotificationActionDisable() throws IOException {
        String defaultStrings = read("app/src/main/res/values/strings.xml");
        String germanStrings = read("app/src/main/res/values-de/strings.xml");

        assertTrue(defaultStrings.contains("<string name=\"notification_action_disable\">"));
        assertTrue(germanStrings.contains("<string name=\"notification_action_disable\">WLAN-ADB ausschalten</string>"));
    }

    private static String read(String relativePath) throws IOException {
        Path directory = Paths.get("").toAbsolutePath();
        while (directory != null && !Files.exists(directory.resolve("settings.gradle"))) {
            directory = directory.getParent();
        }
        if (directory == null) {
            throw new IllegalStateException("Could not locate project root");
        }
        return new String(Files.readAllBytes(directory.resolve(relativePath)), StandardCharsets.UTF_8);
    }
}
