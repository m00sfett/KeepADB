package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ApplicationProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowKeyguardManager;

/**
 * #588: the USB notification's MANUAL "Enable WLAN-ADB" action must not switch Wireless Debugging
 * on from a locked, secured device. Pinned on both layers: the built {@link Notification.Action}
 * asks the platform to reauthenticate (API 31+), and the real receiver path refuses while
 * {@link KeyguardManager#isDeviceLocked()} -- on API 30 too, where the platform flag is absent.
 * The counter-tests pin the other side: unlocked taps and the AUTOMATIC handover (no user tap)
 * still enable exactly as before.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBUsbHandoverUnlockGateTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = ApplicationProvider.getApplicationContext();

    @Before
    public void setUp() {
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS,
                android.Manifest.permission.POST_NOTIFICATIONS);
        resetState();
        KeepADBUsbProfile.setNotificationEnabled(context, true);
        KeepADBPreferences.setUsbWlanHandoverMode(context,
                KeepADBPreferences.USB_WLAN_HANDOVER_MODE_MANUAL);
    }

    @After
    public void tearDown() {
        setDeviceLocked(false);
        KeepADBUsbNotification.cancel(context);
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(null);
        resetState();
    }

    private void resetState() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("keepadb_usb_profiles", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("keepadb_diagnostics", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADBUsbNotification.resetForTesting();
        KeepADBUsbHandover.resetForTesting();
        KeepADB.resetForTesting(context);
        KeepADB.setSurfaceRefresherForTesting(new KeepADBFakeSurfaceRefresher());
    }

    // --- Notification action ----------------------------------------------------------------

    @Test
    public void theHandoverActionRequiresAuthenticationAndTargetsTheReceiverExplicitly() {
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));

        KeepADBUsbNotification.refresh(context, true);

        Notification.Action handover = handoverAction(postedNotification());
        assertTrue("Enable action must require authentication (API 31+)",
                handover.isAuthenticationRequired());
        Intent saved = shadowOf(handover.actionIntent).getSavedIntent();
        assertEquals(KeepADBUsbReceiver.ACTION_HANDOVER_ENABLE, saved.getAction());
        assertEquals(new ComponentName(context, KeepADBUsbReceiver.class), saved.getComponent());
    }

    // --- Receiver gate ----------------------------------------------------------------------

    @Test
    public void aLockedTapDoesNotEnableRecordsAnEventAndKeepsTheActionAvailable() {
        assertLockedTapIsRefusedThenSucceedsAfterUnlock();
    }

    /** API 30 has no setAuthenticationRequired, so the receiver gate is the only barrier there. */
    @Test
    @Config(sdk = 30)
    public void aLockedTapIsAlsoRefusedOnApi30() {
        assertLockedTapIsRefusedThenSucceedsAfterUnlock();
    }

    @Test
    public void anUnlockedTapEnablesAsBefore() {
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(false);
        KeepADB.setGatewayForTesting(gateway);
        setDeviceLocked(false);

        new KeepADBUsbReceiver().onReceive(context, handoverIntent());

        assertEquals(Collections.singletonList(true), gateway.writes);
        assertFalse(KeepADBUsbNotification.isLastHandoverActionFailed());
    }

    /**
     * The gate must not swallow a genuine error that happened before: a locked tap proves nothing
     * about the permission either way, so an earlier failure message stays exactly as it was.
     */
    @Test
    public void aLockedTapLeavesAnEarlierGenuineFailureUntouched() {
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));
        KeepADBUsbNotification.reportManualActionResult(context, false);
        assertTrue(KeepADBUsbNotification.isLastHandoverActionFailed());
        setDeviceLocked(true);

        new KeepADBUsbReceiver().onReceive(context, handoverIntent());

        assertTrue(KeepADBUsbNotification.isLastHandoverActionFailed());
    }

    /**
     * The user decision covers the manual action only. The AUTOMATIC handover is not a tap on the
     * lock screen; it must keep enabling on a trusted network while the device is locked.
     */
    @Test
    public void theAutomaticHandoverStillEnablesWhileLocked() {
        KeepADBPreferences.setUsbWlanHandoverMode(context,
                KeepADBPreferences.USB_WLAN_HANDOVER_MODE_AUTOMATIC);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(false);
        KeepADBFakeScheduler scheduler = new KeepADBFakeScheduler();
        scheduler.setClockMs(100_000);
        KeepADB.setGatewayForTesting(gateway);
        KeepADB.setSchedulerForTesting(scheduler);
        setDeviceLocked(true);

        new KeepADBUsbReceiver().onReceive(context, new Intent(KeepADBUsbReceiver.ACTION_USB_STATE)
                .putExtra("connected", true)
                .putExtra("configured", true)
                .putExtra("adb", true));
        scheduler.advanceBy(KeepADB.TOGGLE_COOLDOWN_MS + 1);

        assertEquals(Collections.singletonList(true), gateway.writes);
    }

    // --- Exposure contract ------------------------------------------------------------------

    /**
     * A foreign app must not be able to send ACTION_HANDOVER_ENABLE: the receiver stays
     * non-exported (the platform then only delivers our own explicit PendingIntent), and
     * handleManualAction has exactly one caller -- behind the lock check -- so no second,
     * ungated entry point can appear without this test noticing.
     */
    @Test
    public void theReceiverIsNotExportedAndTheManualEnableHasOnlyTheGatedCaller() throws IOException {
        String manifest = read("app/src/main/AndroidManifest.xml");
        int start = manifest.indexOf("android:name=\".KeepADBUsbReceiver\"");
        assertTrue(start >= 0);
        String block = manifest.substring(start, manifest.indexOf("</receiver>", start));
        assertTrue(block.contains("android:exported=\"false\""));

        Path sources = projectRoot().resolve("app/src/main/java/de/hohnepeople/keepadb");
        long callers;
        try (Stream<Path> files = Files.list(sources)) {
            callers = files.mapToLong(file -> count(readPath(file),
                    "KeepADBUsbHandover.handleManualAction(")).sum();
        }
        assertEquals(1, callers);
        String receiver = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBUsbReceiver.java");
        int lockCheck = receiver.indexOf("keyguardManager.isDeviceLocked()");
        int call = receiver.indexOf("KeepADBUsbHandover.handleManualAction(");
        assertTrue("the only manual enable must sit behind the lock check",
                lockCheck >= 0 && call > lockCheck);
    }

    // --- helpers ----------------------------------------------------------------------------

    private void assertLockedTapIsRefusedThenSucceedsAfterUnlock() {
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(false);
        KeepADB.setGatewayForTesting(gateway);
        setDeviceLocked(true);
        // Model a lock screen that dismissed the notification on the tap: only the receiver's own
        // re-post can bring the action back.
        KeepADBUsbNotification.cancel(context);

        new KeepADBUsbReceiver().onReceive(context, handoverIntent());

        assertTrue("a locked tap must not write anything", gateway.writes.isEmpty());
        assertTrue(KeepADBDiagnostics.export(context).contains(
                "event=user_action source=usb_handover_manual outcome=blocked detail=device_locked"));
        assertFalse("a locked tap is not a permission failure",
                KeepADBUsbNotification.isLastHandoverActionFailed());
        Notification notification = postedNotification();
        assertNotNull("the notification must be re-offered", notification);
        assertNotNull("the enable action must stay available", handoverAction(notification));
        CharSequence text = notification.extras.getCharSequence(Notification.EXTRA_TEXT);
        assertFalse(context.getString(R.string.usb_notification_handover_error)
                .contentEquals(text == null ? "" : text));

        setDeviceLocked(false);
        assertTrue(KeepADBUsbReceiver.handleHandoverEnableAction(context));
        assertEquals(Collections.singletonList(true), gateway.writes);
    }

    private Intent handoverIntent() {
        return new Intent(context, KeepADBUsbReceiver.class)
                .setAction(KeepADBUsbReceiver.ACTION_HANDOVER_ENABLE);
    }

    private Notification postedNotification() {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        return shadowOf(manager).getNotification(KeepADBUsbNotification.NOTIFICATION_ID);
    }

    private Notification.Action handoverAction(Notification notification) {
        if (notification.actions == null) return null;
        String title = context.getString(R.string.usb_notification_enable_wlan_handover);
        return Arrays.stream(notification.actions)
                .filter(action -> title.contentEquals(action.title))
                .findFirst()
                .orElse(null);
    }

    private void setDeviceLocked(boolean locked) {
        ShadowKeyguardManager shadow = shadowOf(context.getSystemService(KeyguardManager.class));
        shadow.setIsDeviceLocked(locked);
        shadow.setKeyguardLocked(locked);
    }

    private static long count(String haystack, String needle) {
        long n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) n++;
        return n;
    }

    private static String readPath(Path path) {
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String read(String relativePath) {
        return readPath(projectRoot().resolve(relativePath));
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
