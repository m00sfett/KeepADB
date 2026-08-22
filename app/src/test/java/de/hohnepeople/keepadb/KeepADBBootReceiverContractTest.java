package de.hohnepeople.keepadb;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Static contracts for the boot receiver's sender boundary and failure handling. */
public class KeepADBBootReceiverContractTest {

    @Test
    public void bootReceiverIsSystemOnlyAndAcceptsOnlyBootCompleted() throws IOException {
        String manifest = read("app/src/main/AndroidManifest.xml");
        String receiver = read("app/src/main/java/de/hohnepeople/keepadb/BootReceiver.java");
        String receiverManifest = manifest.substring(
                manifest.indexOf("<receiver\n            android:name=\".BootReceiver\""),
                manifest.indexOf("</receiver>", manifest.indexOf(".BootReceiver")));

        assertTrue(receiverManifest.contains("android:exported=\"false\""));
        assertTrue(receiverManifest.contains("<action android:name=\"android.intent.action.BOOT_COMPLETED\" />"));
        assertFalse(receiverManifest.contains("QUICKBOOT_POWERON"));
        assertFalse(receiver.contains("QUICKBOOT_POWERON"));
        assertTrue(receiver.contains("Intent.ACTION_BOOT_COMPLETED.equals(action)"));
        assertFalse(receiver.contains("KeepADB.setEnabled"));
        assertFalse(receiver.contains("KeepADBNotification.refresh"));
        assertFalse(receiver.contains("KeepADBWidget.refreshAll"));
    }

    @Test
    public void foregroundStartFailuresDoNotContinueBootRecovery() throws IOException {
        String receiver = read("app/src/main/java/de/hohnepeople/keepadb/BootReceiver.java");
        String service = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBService.java");
        String beforeStartCommand = service.substring(0, service.indexOf("public int onStartCommand"));
        String startCommand = service.substring(service.indexOf("public int onStartCommand"));

        assertTrue(receiver.contains("if (!KeepADBService.start(context))"));
        assertTrue(receiver.contains("Failed to start KeepADB foreground service"));
        assertTrue(service.contains("catch (RuntimeException e)"));
        assertTrue(service.contains("Failed to enter foreground mode; cleanup requested="));
        assertTrue(service.contains("return START_NOT_STICKY;"));
        assertTrue(service.contains("foregroundReady = true;"));
        assertTrue(service.contains("Ignoring recheck before foreground promotion"));
        assertTrue(service.contains("Ignoring state change before foreground promotion"));
        assertTrue(service.contains("Ignoring network loss before foreground promotion"));
        assertFalse(beforeStartCommand.contains("heartbeatNow();"));
        assertFalse(beforeStartCommand.contains("KeepADB.consumeUserDisabled();"));
        assertFalse(beforeStartCommand.contains("registerAdbObserver();"));
        assertFalse(beforeStartCommand.contains("registerNetworkCallback();"));
        assertFalse(beforeStartCommand.contains("startHeartbeatTicker();"));
        assertTrue(startCommand.indexOf("startForeground(") < startCommand.indexOf("foregroundReady = true;"));
        assertTrue(startCommand.indexOf("foregroundReady = true;") < startCommand.indexOf("heartbeatNow();"));
        assertTrue(startCommand.indexOf("heartbeatNow();") < startCommand.indexOf("registerAdbObserver();"));
        assertTrue(startCommand.indexOf("registerAdbObserver();") < startCommand.indexOf("recheckAndEnable();"));
        assertTrue(service.contains("failForegroundStart(startId, e);"));
        assertTrue(service.contains("foregroundReady = false;"));
        assertTrue(service.contains("stopHeartbeatTicker();"));
        assertTrue(service.contains("unregisterAdbObserver();"));
        assertTrue(service.contains("unregisterNetworkCallback();"));
        assertTrue(service.contains("stopForeground(STOP_FOREGROUND_REMOVE);"));
        assertTrue(service.contains("boolean stopRequested = stopSelfResult(startId);"));
        assertTrue(service.contains("cleanup requested="));
    }

    @Test
    public void serviceStopsAndRemovesNotificationWhenDisabled() throws IOException {
        String service = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBService.java");
        String notification = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBNotification.java");

        assertTrue(service.contains("if (KeepADBPreferences.isKeepAliveEnabled(context) && KeepADB.isEnabled(context))"));
        assertTrue(service.contains("stopForeground(STOP_FOREGROUND_REMOVE)"));
        assertTrue(notification.contains("manager.cancel(NOTIFICATION_ID);"));
        assertFalse(notification.contains("showPlaceholder(context, manager,"));
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
