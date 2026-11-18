package de.hohnepeople.keepadb;

import static de.hohnepeople.keepadb.KeepADBForceTestSupport.HOUR;
import static de.hohnepeople.keepadb.KeepADBForceTestSupport.MINUTE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlarmManager;
import android.app.Application;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import android.os.Looper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlarmManager;
import org.robolectric.shadows.ShadowLooper;

/**
 * #1000: the Wifi-ADB timer at its real call path. Start, extension and its cap, the manual on/off
 * that ends it, the expiry as a "manual off", the Keep-Alive overlay and its suppression between
 * expiry and end, process death, reboot with time left, expiry while the device was off, and the
 * fail-closed clock cases. Each rule from both sides.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBTimerTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = RuntimeEnvironment.getApplication();
    private KeepADBForceTestSupport.TestClock clock;
    private KeepADBFakeSettingsGateway gateway;
    private final List<String> notices = new ArrayList<>();

    @Before
    public void setUp() {
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.POST_NOTIFICATIONS,
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
        resetState();
        clock = new KeepADBForceTestSupport.TestClock();
        KeepADBTimer.setClockForTesting(clock);
        KeepADBForceMode.setClockForTesting(clock);
        gateway = new KeepADBFakeSettingsGateway(false);
        KeepADB.setGatewayForTesting(gateway);
        KeepADBTimer.setNoticeSink((app, reason) -> notices.add(reason == null ? "deadline" : reason));
    }

    @After
    public void tearDown() {
        resetState();
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(null);
    }

    private void resetState() {
        prefs().edit().clear().commit();
        context.getSharedPreferences("keepadb_diagnostics", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADBTimer.resetForTesting();
        KeepADBForceMode.resetForTesting();
        KeepADBEndpointCoordinator.resetForTesting();
        KeepADBEndpoint.resetForTesting();
        KeepADB.resetForTesting();
        KeepADBRegisterClient.resetForTesting();
        context.getSystemService(NotificationManager.class).cancelAll();
        ShadowAlarmManager.reset();
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE);
    }

    /** Delivers the armed timer alarm the way the platform would, instead of calling restore(). */
    private boolean fireTimerAlarm() {
        ShadowAlarmManager alarms = shadowOf(context.getSystemService(AlarmManager.class));
        for (ShadowAlarmManager.ScheduledAlarm alarm : alarms.getScheduledAlarms()) {
            Intent fired = shadowOf(alarm.operation).getSavedIntent();
            if (KeepADBReceiver.ACTION_TIMER_EXPIRE.equals(fired.getAction())) {
                new KeepADBReceiver().onReceive(context, fired);
                return true;
            }
        }
        return false;
    }

    private void drainToggles() {
        ShadowLooper.idleMainLooper(5_000L, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    // ---- Start ------------------------------------------------------------------------------------

    @Test
    public void startSwitchesWifiAdbOnAndRunsForTheChosenDuration() {
        KeepADB.ToggleResult result = KeepADBTimer.start(context, KeepADBTimer.Duration.MINUTES_30, "test");

        assertEquals(KeepADB.ToggleResult.APPLIED, result);
        assertTrue(gateway.isEnabled(context));
        KeepADBTimer.Status status = KeepADBTimer.status(context);
        assertNotNull(status);
        assertEquals(30 * MINUTE, status.remainingMs);
        assertTrue("The start is an explicit on", KeepADBPreferences.getLastDesiredOn(context));
    }

    @Test
    public void theTimerIsAKeepAliveOverlayThatLeavesTheSettingAlone() {
        assertFalse(KeepADBPreferences.isKeepAliveEffective(context));

        KeepADBTimer.start(context, KeepADBTimer.Duration.HOUR_1, "test");

        assertTrue(KeepADBPreferences.isKeepAliveEffective(context));
        assertFalse("The stored setting is never written", KeepADBPreferences.isKeepAliveEnabled(context));
        assertFalse(prefs().contains("keep_alive_enabled"));
    }

    @Test
    public void startWithoutThePermissionLeavesNoTimerBehind() {
        shadowOf((Application) context).denyPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS);

        KeepADB.ToggleResult result = KeepADBTimer.start(context, KeepADBTimer.Duration.HOUR_1, "test");

        assertEquals(KeepADB.ToggleResult.PERMISSION_MISSING, result);
        assertFalse(KeepADBTimer.isActive(context));
        assertFalse(prefs().contains(KeepADBTimer.KEY_STATE));
    }

    @Test
    public void startWithAnUnreadableBootCountIsRefused() {
        clock.bootCountReadable = false;

        assertNull(KeepADBTimer.start(context, KeepADBTimer.Duration.HOUR_1, "test"));
        assertFalse(gateway.isEnabled(context));
        assertFalse(prefs().contains(KeepADBTimer.KEY_STATE));
    }

    @Test
    public void aNewStartReplacesTheRunningTimer() {
        KeepADBTimer.start(context, KeepADBTimer.Duration.HOURS_8, "test");
        clock.advance(HOUR);

        KeepADBTimer.start(context, KeepADBTimer.Duration.MINUTES_15, "test");

        assertEquals(15 * MINUTE, KeepADBTimer.status(context).remainingMs);
    }

    @Test
    public void startArmsTheExpiryAlarmAtTheDeadline() {
        KeepADBTimer.start(context, KeepADBTimer.Duration.MINUTES_15, "test");

        ShadowAlarmManager alarms = shadowOf(context.getSystemService(AlarmManager.class));
        boolean armed = false;
        for (ShadowAlarmManager.ScheduledAlarm alarm : alarms.getScheduledAlarms()) {
            armed |= alarm.getTriggerAtMs() == clock.elapsed + 15 * MINUTE;
        }
        assertTrue("An alarm at the deadline", armed);

        KeepADB.setEnabled(context, false, KeepADB.SOURCE_APP);
        for (ShadowAlarmManager.ScheduledAlarm alarm : alarms.getScheduledAlarms()) {
            assertFalse("A manual off cancels it",
                    alarm.getTriggerAtMs() == clock.elapsed + 15 * MINUTE);
        }
    }

    // ---- Extension --------------------------------------------------------------------------------

    @Test
    public void extendAddsOneHourToWhatIsLeft() {
        KeepADBTimer.start(context, KeepADBTimer.Duration.MINUTES_30, "test");
        clock.advance(10 * MINUTE);

        assertTrue(KeepADBTimer.extend(context, "test"));

        assertEquals(20 * MINUTE + HOUR, KeepADBTimer.status(context).remainingMs);
    }

    @Test
    public void extendIsCappedAtTwentyFourHoursAndThenOffersNothing() {
        KeepADBTimer.start(context, KeepADBTimer.Duration.HOURS_8, "test");
        for (int i = 0; i < 20; i++) KeepADBTimer.extend(context, "test");

        KeepADBTimer.Status capped = KeepADBTimer.status(context);
        assertEquals(KeepADBTimer.MAX_REMAINING_MS, capped.remainingMs);
        assertFalse(capped.canExtend());
        assertFalse("At the cap a tap adds nothing", KeepADBTimer.extend(context, "test"));
        assertEquals(KeepADBTimer.MAX_REMAINING_MS, KeepADBTimer.status(context).remainingMs);
    }

    @Test
    public void extendBelowTheCapStillOffersMore() {
        KeepADBTimer.start(context, KeepADBTimer.Duration.HOURS_8, "test");
        assertTrue(KeepADBTimer.status(context).canExtend());
    }

    @Test
    public void anExpiredTimerIsFinishedNotExtended() {
        KeepADBTimer.start(context, KeepADBTimer.Duration.MINUTES_15, "test");
        clock.advance(16 * MINUTE);

        assertFalse(KeepADBTimer.extend(context, "test"));
        drainToggles();
        assertFalse(KeepADBTimer.isActive(context));
        assertFalse(prefs().contains(KeepADBTimer.KEY_STATE));
        assertEquals(1, notices.size());
    }

    // ---- Manual on/off ----------------------------------------------------------------------------

    @Test
    public void aManualOffEndsTheTimerWithoutANotice() {
        KeepADBTimer.start(context, KeepADBTimer.Duration.HOUR_1, "test");

        KeepADB.setEnabled(context, false, KeepADB.SOURCE_TILE);

        assertFalse(KeepADBTimer.isActive(context));
        assertFalse(gateway.isEnabled(context));
        assertTrue(notices.isEmpty());
        assertFalse(KeepADBPreferences.isKeepAliveEffective(context));
    }

    @Test
    public void aManualOnWithoutATimerEndsItAndLeavesWifiAdbOn() {
        KeepADBTimer.start(context, KeepADBTimer.Duration.HOUR_1, "test");
        gateway.setEnabledByUser(false);

        KeepADB.setEnabled(context, true, KeepADB.SOURCE_APP);

        assertFalse(KeepADBTimer.isActive(context));
        assertTrue(gateway.isEnabled(context));
        clock.advance(2 * HOUR);
        KeepADBTimer.finishIfExpired(context);
        assertTrue("No timer left to switch it off", gateway.isEnabled(context));
    }

    @Test
    public void anAutomaticWriteDoesNotEndTheTimer() {
        KeepADBTimer.start(context, KeepADBTimer.Duration.HOUR_1, "test");

        KeepADB.setEnabled(context, true, "keep_alive_check");
        drainToggles();

        assertTrue(KeepADBTimer.isActive(context));
    }

    // ---- Expiry -----------------------------------------------------------------------------------

    @Test
    public void expiryTurnsWifiAdbOffAsAnExplicitOffAndPostsTheNotice() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBTimer.start(context, KeepADBTimer.Duration.MINUTES_15, "test");
        clock.advance(15 * MINUTE);

        assertTrue(KeepADBTimer.finishIfExpired(context));
        drainToggles();

        assertFalse(gateway.isEnabled(context));
        assertFalse("Keep-Alive waits for the next deliberate on", KeepADBPreferences.getLastDesiredOn(context));
        assertTrue("The stored Keep-Alive setting stays as it was", KeepADBPreferences.isKeepAliveEnabled(context));
        assertEquals(1, notices.size());
        assertEquals("deadline", notices.get(0));
    }

    @Test
    public void theEndHappensOnceWhateverTheNumberOfDrivers() {
        KeepADBTimer.start(context, KeepADBTimer.Duration.MINUTES_15, "test");
        clock.advance(20 * MINUTE);

        assertTrue(KeepADBTimer.finishIfExpired(context));
        assertFalse(KeepADBTimer.finishIfExpired(context));
        KeepADBTimer.restore(context);
        drainToggles();

        assertEquals(1, notices.size());
    }

    @Test
    public void betweenExpiryAndEndNothingKeepsWifiAdbAliveNotEvenTheStoredSettingOrForce() {
        // Codex review: every other guard condition holds (Wi-Fi up, Force makes it trusted, the
        // stored Keep-Alive is on), so only the timer's deadline can flip the guard.
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        KeepADBForceTestSupport.connectTo(context, KeepADBForceTestSupport.SSID, KeepADBForceTestSupport.BSSID);
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOURS_24, false));
        KeepADBTimer.start(context, KeepADBTimer.Duration.MINUTES_15, "test");
        assertTrue(KeepADBPreferences.isKeepAliveEnabled(context));
        assertTrue("Control: permitted before the deadline",
                KeepADBService.isAutoEnableStillPermittedIgnoringBackoff(context));

        clock.advance(15 * MINUTE); // no driver has run yet

        assertTrue(KeepADBTimer.isExpiryPending(context));
        assertFalse(KeepADBPreferences.isKeepAliveEffective(context));
        assertFalse("The auto-enable guard refuses",
                KeepADBService.isAutoEnableStillPermittedIgnoringBackoff(context));
    }

    // ---- Codex review: the switch-off is an obligation until it is seen ---------------------------

    @Test
    public void aCrashBetweenTheEndAndTheOffWriteIsRepairedByTheNextDriver() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBTimer.start(context, KeepADBTimer.Duration.MINUTES_15, "test");
        // What the end's single commit leaves behind when the process dies before the write.
        prefs().edit().remove(KeepADBTimer.KEY_STATE)
                .putBoolean(KeepADBTimer.KEY_SHUTDOWN_PENDING, true)
                .putBoolean(KeepADBPreferences.KEY_LAST_DESIRED_ON, false).commit();
        assertTrue(gateway.isEnabled(context));
        assertFalse("Keep-Alive stays suppressed", KeepADBPreferences.isKeepAliveEffective(context));

        KeepADBTimer.restore(context);
        drainToggles();

        assertFalse(gateway.isEnabled(context));
        assertFalse(KeepADBTimer.isShutdownPending(context));
        assertTrue("Settled: the stored setting decides again", KeepADBPreferences.isKeepAliveEffective(context)
                || !KeepADBPreferences.getLastDesiredOn(context));
    }

    @Test
    public void aRejectedOffKeepsTheObligationAndTheOffIntentUntilItSticks() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBTimer.start(context, KeepADBTimer.Duration.MINUTES_15, "test");
        clock.advance(15 * MINUTE);
        gateway.setWriteSuccess(false);

        assertTrue(KeepADBTimer.finishIfExpired(context));
        drainToggles();

        assertTrue(gateway.isEnabled(context));
        assertTrue(KeepADBTimer.isShutdownPending(context));
        assertTrue("No 'Wifi-ADB off' notice while it is on", notices.isEmpty());
        assertTrue("A retry alarm is armed", fireTimerAlarm());
        drainToggles();
        assertFalse("A rolled-back intent is asserted again", KeepADBPreferences.getLastDesiredOn(context));
        assertFalse(KeepADBPreferences.isKeepAliveEffective(context));
        assertTrue(notices.isEmpty());

        gateway.setWriteSuccess(true);
        assertTrue("Still armed while the off is outstanding", fireTimerAlarm());
        drainToggles();

        assertFalse(gateway.isEnabled(context));
        assertFalse("Settled by the write itself, no extra call", KeepADBTimer.isShutdownPending(context));
        assertEquals("The notice follows the real off, once", 1, notices.size());
    }

    @Test
    public void aDelayedOffThatSucceedsSettlesSoALaterExternalEnableStays() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBTimer.start(context, KeepADBTimer.Duration.MINUTES_15, "test");
        clock.advance(15 * MINUTE);
        // A recent automatic write puts the expiry's off into the debounce window.
        KeepADB.setEnabled(context, true, "keep_alive_check");

        KeepADBTimer.finishIfExpired(context);
        assertTrue("Debounced: still outstanding", KeepADBTimer.isShutdownPending(context));
        // Past the debounce window, before the timer's own re-check at window + 500 ms.
        ShadowLooper.idleMainLooper(KeepADB.TOGGLE_COOLDOWN_MS + 100L, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertFalse(gateway.isEnabled(context));
        assertFalse("The delayed off itself settled the obligation", KeepADBTimer.isShutdownPending(context));
        drainToggles();

        gateway.setEnabledByUser(true); // the user enables it in Android's settings
        fireTimerAlarm();
        KeepADBTimer.restore(context);
        drainToggles();
        assertTrue("A settled end never switches it off again", gateway.isEnabled(context));
    }

    @Test
    public void aFailedManualOffSurvivesARebootAsAnOff() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBTimer.start(context, KeepADBTimer.Duration.HOUR_1, "test");
        gateway.setWriteSuccess(false);
        KeepADB.setEnabled(context, false, KeepADB.SOURCE_TILE);
        assertTrue(KeepADBTimer.isShutdownPending(context));

        // Reboot: Android switches Wifi-ADB off itself; the process starts fresh.
        clock.reboot(MINUTE, MINUTE);
        gateway.setWriteSuccess(true);
        gateway.setEnabledByUser(false);
        KeepADB.resetForTesting();
        KeepADB.setGatewayForTesting(gateway);
        KeepADBTimer.settleShutdown(context); // what KeepADBApplication.onCreate does
        shadowOf((Application) context).clearStartedServices();
        new BootReceiver().onReceive(context, new Intent(Intent.ACTION_BOOT_COMPLETED));

        assertFalse(KeepADBTimer.isShutdownPending(context));
        assertFalse("The user's off survives", KeepADBPreferences.getLastDesiredOn(context));
        assertFalse(KeepADBService.shouldRun(context));
        assertNull("Boot recovery does not start", shadowOf((Application) context).getNextStartedService());
    }

    @Test
    public void aFailedManualOffLeavesTheOffAsAnObligation() {
        KeepADBTimer.start(context, KeepADBTimer.Duration.HOUR_1, "test");
        gateway.setWriteSuccess(false);

        KeepADB.setEnabled(context, false, KeepADB.SOURCE_TILE);

        assertFalse(KeepADBTimer.isActive(context));
        assertTrue(gateway.isEnabled(context));
        assertTrue(KeepADBTimer.isShutdownPending(context));
        gateway.setWriteSuccess(true);
        assertTrue(fireTimerAlarm());
        drainToggles();
        assertFalse(gateway.isEnabled(context));
        assertFalse(KeepADBTimer.isShutdownPending(context));
    }

    @Test
    public void afterARebootTheServiceRestoresWifiAdbForTheTimeLeft() {
        // Codex review: run the service the boot path starts, through the real enable attempt.
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        KeepADBForceTestSupport.connectTo(context, KeepADBForceTestSupport.SSID, KeepADBForceTestSupport.BSSID);
        KeepADBTrustedNetwork.addBssid(context, KeepADBForceTestSupport.BSSID, KeepADBForceTestSupport.SSID);
        KeepADBTimer.start(context, KeepADBTimer.Duration.HOUR_1, "test");
        clock.reboot(MINUTE, MINUTE);
        gateway.setEnabledByUser(false);
        KeepADB.resetForTesting();
        KeepADB.setGatewayForTesting(gateway);
        new BootReceiver().onReceive(context, new Intent(Intent.ACTION_BOOT_COMPLETED));
        assertFalse(KeepADBPreferences.isKeepAliveEnabled(context));

        ServiceController<KeepADBService> controller = Robolectric.buildService(KeepADBService.class);
        try {
            controller.create();
            controller.get().onStartCommand(new Intent(context, KeepADBService.class), 0, 1);
            ShadowLooper.idleMainLooper();
            android.os.SystemClock.sleep(1600);
            controller.get().recheckAndEnable();
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1600));
        } finally {
            controller.destroy();
        }

        assertTrue("The timer, not the stored setting, kept it alive", gateway.isEnabled(context));
    }

    @Test
    public void aManualOnSettlesAnOutstandingOff() {
        KeepADBTimer.start(context, KeepADBTimer.Duration.HOUR_1, "test");
        gateway.setWriteSuccess(false);
        KeepADB.setEnabled(context, false, KeepADB.SOURCE_TILE);
        assertTrue(KeepADBTimer.isShutdownPending(context));
        gateway.setWriteSuccess(true);

        KeepADB.setEnabled(context, true, KeepADB.SOURCE_APP);

        assertFalse(KeepADBTimer.isShutdownPending(context));
        KeepADBTimer.restore(context);
        drainToggles();
        assertTrue("Nothing switches the user's on off again", gateway.isEnabled(context));
    }

    @Test
    public void aSuccessfulManualOffSettlesAtOnce() {
        KeepADBTimer.start(context, KeepADBTimer.Duration.HOUR_1, "test");

        KeepADB.setEnabled(context, false, KeepADB.SOURCE_TILE);

        assertFalse(KeepADBTimer.isShutdownPending(context));
    }

    @Test
    public void oneMillisecondBeforeTheDeadlineTheTimerStillHolds() {
        KeepADBTimer.start(context, KeepADBTimer.Duration.MINUTES_15, "test");
        clock.advance(15 * MINUTE - 1);

        assertFalse(KeepADBTimer.finishIfExpired(context));
        assertTrue(KeepADBTimer.isActive(context));
        assertTrue(KeepADBPreferences.isKeepAliveEffective(context));
        assertTrue(gateway.isEnabled(context));
    }

    @Test
    public void expiryWithoutThePermissionStillRecordsTheOff() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBTimer.start(context, KeepADBTimer.Duration.MINUTES_15, "test");
        shadowOf((Application) context).denyPermissions(android.Manifest.permission.WRITE_SECURE_SETTINGS);
        clock.advance(16 * MINUTE);

        assertTrue(KeepADBTimer.finishIfExpired(context));

        assertFalse(KeepADBPreferences.getLastDesiredOn(context));
    }

    @Test
    public void theAlarmBroadcastFinishesTheTimer() {
        KeepADBTimer.start(context, KeepADBTimer.Duration.MINUTES_15, "test");
        clock.advance(15 * MINUTE);

        new KeepADBReceiver().onReceive(context,
                new Intent(context, KeepADBReceiver.class).setAction(KeepADBReceiver.ACTION_TIMER_EXPIRE));
        drainToggles();

        assertFalse(prefs().contains(KeepADBTimer.KEY_STATE));
        assertEquals(1, notices.size());
    }

    // ---- Process death, reboot, clocks ------------------------------------------------------------

    @Test
    public void theStateSurvivesProcessDeathThroughPreferencesOnly() {
        KeepADBTimer.start(context, KeepADBTimer.Duration.HOUR_1, "test");
        String stored = prefs().getString(KeepADBTimer.KEY_STATE, null);
        KeepADBTimer.resetForTesting();
        KeepADBTimer.setClockForTesting(clock);
        clock.advance(10 * MINUTE);

        assertNotNull(stored);
        assertEquals(50 * MINUTE, KeepADBTimer.status(context).remainingMs);
    }

    @Test
    public void aRebootWithTimeLeftKeepsTheTimerAndKeepAliveEffective() {
        KeepADBTimer.start(context, KeepADBTimer.Duration.HOUR_1, "test");
        clock.reboot(5 * MINUTE, 2 * MINUTE);
        gateway.setEnabledByUser(false); // Android resets Wireless Debugging on boot

        shadowOf((Application) context).clearStartedServices();
        new BootReceiver().onReceive(context, new Intent(Intent.ACTION_BOOT_COMPLETED));

        KeepADBTimer.Status status = KeepADBTimer.status(context);
        assertNotNull(status);
        assertEquals(53 * MINUTE, status.remainingMs);
        // Codex review: the boot path really starts the service that restores Wifi-ADB.
        Intent started = shadowOf((Application) context).getNextStartedService();
        assertNotNull("The boot path starts the Keep-Alive service", started);
        assertEquals(KeepADBService.class.getName(), started.getComponent().getClassName());
        assertTrue(KeepADBPreferences.isKeepAliveEffective(context));
        assertTrue("The intent is still on, so Keep-Alive restores", KeepADBPreferences.getLastDesiredOn(context));
        assertTrue(notices.isEmpty());
    }

    @Test
    public void afterTheRebootTheBudgetIsReboundSoAClockSetBackCannotExtendIt() {
        KeepADBTimer.start(context, KeepADBTimer.Duration.HOUR_1, "test");
        clock.reboot(5 * MINUTE, 2 * MINUTE);
        KeepADBTimer.restore(context); // the first driver of the new boot rebinds

        clock.setWallClock(clock.wall - 3 * HOUR);
        clock.elapsed += MINUTE;

        assertEquals(52 * MINUTE, KeepADBTimer.status(context).remainingMs);
    }

    @Test
    public void expiryWhileTheDeviceWasOffKeepsWifiAdbOffAfterTheBoot() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBTimer.start(context, KeepADBTimer.Duration.MINUTES_15, "test");
        clock.reboot(30 * MINUTE, MINUTE);
        gateway.setEnabledByUser(false);

        new BootReceiver().onReceive(context, new Intent(Intent.ACTION_BOOT_COMPLETED));
        drainToggles();

        assertFalse(KeepADBTimer.isActive(context));
        assertFalse(gateway.isEnabled(context));
        assertFalse("Keep-Alive finds an explicit off", KeepADBPreferences.getLastDesiredOn(context));
        assertFalse(KeepADBService.shouldRun(context));
        assertEquals(KeepADBTimer.REASON_WHILE_OFF, notices.get(0));
    }

    @Test
    public void aWallClockBeforeTheBaseAfterARebootFailsClosed() {
        KeepADBTimer.start(context, KeepADBTimer.Duration.HOURS_8, "test");
        clock.reboot(MINUTE, MINUTE);
        clock.setWallClock(clock.wall - 2 * HOUR);

        assertFalse(KeepADBTimer.isActive(context));
        assertTrue(KeepADBTimer.finishIfExpired(context));
        drainToggles();
        assertEquals(KeepADBTimer.REASON_SAFETY, notices.get(0));
    }

    @Test
    public void anUnreadableBootCountEndsARunningTimer() {
        KeepADBTimer.start(context, KeepADBTimer.Duration.HOURS_8, "test");
        clock.bootCountReadable = false;

        assertFalse(KeepADBTimer.isActive(context));
        assertTrue(KeepADBTimer.finishIfExpired(context));
        drainToggles();
        assertEquals(KeepADBTimer.REASON_SAFETY, notices.get(0));
    }

    @Test
    public void aClockSetForwardEndsItEarly() {
        KeepADBTimer.start(context, KeepADBTimer.Duration.HOURS_2, "test");
        clock.setWallClock(clock.wall + 3 * HOUR);

        assertFalse(KeepADBTimer.isActive(context));
    }

    // ---- Storage ----------------------------------------------------------------------------------

    @Test
    public void aBudgetAboveTheCapReadsAsDamaged() {
        assertNull(KeepADBTimer.decode("1;" + (KeepADBTimer.MAX_REMAINING_MS + 1) + ";1800000000000;5;41"));
        assertNotNull(KeepADBTimer.decode("1;" + KeepADBTimer.MAX_REMAINING_MS + ";1800000000000;5;41"));
        assertNull(KeepADBTimer.decode("1;0;1800000000000;5;41"));
        assertNull(KeepADBTimer.decode("2;3600000;1800000000000;5;41"));
        assertNull(KeepADBTimer.decode("1;abc;1800000000000;5;41"));
    }

    @Test
    public void theDefaultDurationIsOneHourAndUnknownTokensFallBackToIt() {
        assertEquals(KeepADBTimer.Duration.HOUR_1, KeepADBPreferences.getTimerDefaultDuration(context));
        KeepADBPreferences.setTimerDefaultDuration(context, KeepADBTimer.Duration.HOURS_4);
        assertEquals(KeepADBTimer.Duration.HOURS_4, KeepADBPreferences.getTimerDefaultDuration(context));
        prefs().edit().putString(KeepADBPreferences.KEY_TIMER_DEFAULT_DURATION, "99d").commit();
        assertEquals(KeepADBTimer.Duration.HOUR_1, KeepADBPreferences.getTimerDefaultDuration(context));
    }
}
