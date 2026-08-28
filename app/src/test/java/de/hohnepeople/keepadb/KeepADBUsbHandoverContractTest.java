package de.hohnepeople.keepadb;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * Contract tests for the #168 USB-ADB -&gt; WLAN-ADB handover wiring. Mirrors
 * {@link KeepADBBootReceiverContractTest}'s source-reading style for the parts that need a real
 * Android Context (NotificationManager, checkSelfPermission, ContentResolver) and therefore
 * can't run as plain JVM behavioral tests without Robolectric. The decision core itself
 * ({@link KeepADBUsbHandover#onRawUsbBroadcastInternal}) is covered behaviorally in
 * {@link KeepADBUsbHandoverTest}.
 */
public class KeepADBUsbHandoverContractTest {

    @Test
    public void automaticEdgeDetectionIsFedOnlyFromRealOnReceiveBroadcastsNotFromTheRefreshOverload() throws IOException {
        String receiver = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBUsbReceiver.java");

        int onReceiveStart = receiver.indexOf("public void onReceive(");
        int onReceiveEnd = findMatchingBraceEnd(receiver, receiver.indexOf('{', onReceiveStart));
        String onReceiveBody = receiver.substring(onReceiveStart, onReceiveEnd);
        assertTrue(onReceiveBody.contains("KeepADBUsbHandover.onRawUsbBroadcast(context, connected)"));

        // The re-derived overload used by SettingsActivity (profile create/switch/edit) must
        // never reach the edge tracker, or an unrelated settings change while still connected
        // would look like a fresh USB connect.
        int refreshOverloadStart = receiver.indexOf("static void refresh(Context context) {");
        int refreshOverloadEnd = findMatchingBraceEnd(receiver, receiver.indexOf('{', refreshOverloadStart));
        String refreshOverloadBody = receiver.substring(refreshOverloadStart, refreshOverloadEnd);
        assertFalse(refreshOverloadBody.contains("KeepADBUsbHandover"));
    }

    @Test
    public void automaticModeChecksLastExplicitIntentBeforeEnabling() throws IOException {
        String handover = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBUsbHandover.java");
        int methodStart = handover.indexOf("static void onRawUsbBroadcast(Context context, boolean connected)");
        int methodEnd = findMatchingBraceEnd(handover, handover.indexOf('{', methodStart));
        String body = handover.substring(methodStart, methodEnd);
        // Deliberately wasLastExplicitIntentOff(), not isUserDisabled(): isUserDisabled() is a
        // one-shot token consumed by KeepADBService's content observer for an unrelated
        // Keep-Alive decision, so a second independent reader of it can be silently starved by
        // that consume() -- confirmed on real hardware (manual off -> content observer's
        // consumeUserDisabled() -> later genuine USB reconnect wrongly re-enabled WLAN-ADB).
        assertTrue(body.contains("KeepADB.wasLastExplicitIntentOff()"));
        assertFalse(body.contains("KeepADB.isUserDisabled()"));
        assertTrue(body.contains("KeepADB.isEnabled(appContext)"));
        assertTrue(handover.contains("USB_WLAN_HANDOVER_MODE_AUTOMATIC.equals(mode)"));
    }

    @Test
    public void handoverNeverCallsSetEnabledWithFalseAnywhere() throws IOException {
        for (String path : new String[] {
                "app/src/main/java/de/hohnepeople/keepadb/KeepADBUsbHandover.java",
                "app/src/main/java/de/hohnepeople/keepadb/KeepADBUsbNotification.java",
                "app/src/main/java/de/hohnepeople/keepadb/KeepADBUsbReceiver.java",
        }) {
            String source = read(path);
            Matcher matcher = Pattern.compile("setEnabled\\(([^)]*)\\)").matcher(source);
            while (matcher.find()) {
                String args = matcher.group(1);
                assertFalse(path + " calls setEnabled(..., false, ...): " + args,
                        args.matches(".*,\\s*false\\s*(,.*)?"));
            }
        }
    }

    @Test
    public void manualModeShowsActionOnlyWhileConnectedAndNotYetEnabled() throws IOException {
        String notification = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBUsbNotification.java");
        int refreshStart = notification.indexOf("static void refresh(Context context, boolean connected)");
        int refreshEnd = findMatchingBraceEnd(notification, notification.indexOf('{', refreshStart));
        String refreshBody = notification.substring(refreshStart, refreshEnd);

        assertTrue(refreshBody.contains("connected"));
        assertTrue(refreshBody.contains("USB_WLAN_HANDOVER_MODE_MANUAL.equals("));
        assertTrue(refreshBody.contains("!KeepADB.isEnabled(appContext)"));
        // Dispatched via KeepADBUsbReceiver's broadcast action, not called directly from here.
        assertFalse(notification.contains("KeepADBUsbHandover.handleManualAction"));
    }

    @Test
    public void manualActionFailureShowsErrorInsteadOfImplyingSuccess() throws IOException {
        String notification = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBUsbNotification.java");
        assertTrue(notification.contains("static void reportManualActionResult(Context context, boolean success)"));
        assertTrue(notification.contains("lastHandoverActionFailed = !success;"));
        assertTrue(notification.contains("R.string.usb_notification_handover_error"));

        String receiver = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBUsbReceiver.java");
        assertTrue(receiver.contains("KeepADBUsbHandover.handleManualAction(context)"));
        assertTrue(receiver.contains("KeepADBUsbNotification.reportManualActionResult(context, success)"));
    }

    @Test
    public void handoverModeDefaultsOffAndIsExposedAsASettingsControl() throws IOException {
        String preferences = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBPreferences.java");
        assertTrue(preferences.contains(
                "prefs.getString(KEY_USB_WLAN_HANDOVER_MODE, USB_WLAN_HANDOVER_MODE_OFF)"));

        String settingsActivity = read("app/src/main/java/de/hohnepeople/keepadb/SettingsActivity.java");
        assertTrue(settingsActivity.contains("showUsbHandoverModeDialog"));
        assertTrue(settingsActivity.contains("KeepADBPreferences.setUsbWlanHandoverMode(this, modes[which])"));

        String layout = read("app/src/main/res/layout/activity_settings.xml");
        assertTrue(layout.contains("settings_usb_handover_selector"));
    }

    @Test
    public void handoverModeSettingDoesNotClearUserDisabledUnlikeKeepAliveToggle() throws IOException {
        String preferences = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBPreferences.java");
        int setterStart = preferences.indexOf("static void setUsbWlanHandoverMode(Context context, String mode)");
        int setterEnd = findMatchingBraceEnd(preferences, preferences.indexOf('{', setterStart));
        String setterBody = preferences.substring(setterStart, setterEnd);

        // Unlike setKeepAliveEnabled(), merely picking a handover mode must not itself clear an
        // earlier explicit user disable -- only a genuine new connect edge (AUTOMATIC) or the
        // manual notification action may ever re-enable WLAN-ADB.
        assertFalse(setterBody.contains("consumeUserDisabled"));

        int keepAliveStart = preferences.indexOf("static void setKeepAliveEnabled(Context context, boolean enabled)");
        int keepAliveEnd = findMatchingBraceEnd(preferences, preferences.indexOf('{', keepAliveStart));
        String keepAliveBody = preferences.substring(keepAliveStart, keepAliveEnd);
        assertTrue(keepAliveBody.contains("consumeUserDisabled"));
    }

    @Test
    public void newTestResetHooksExist() throws IOException {
        String handover = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBUsbHandover.java");
        assertTrue(handover.contains("static synchronized void resetForTesting()"));
        String notification = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBUsbNotification.java");
        assertTrue(notification.contains("static void resetForTesting()"));
    }

    private static int findMatchingBraceEnd(String source, int openBraceIndex) {
        int depth = 0;
        for (int i = openBraceIndex; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i + 1;
            }
        }
        throw new AssertionError("No matching closing brace found");
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
