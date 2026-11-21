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

    /** #1032: 24 h is the seventh duration and starts right at the cap. */
    @Test
    public void twentyFourHoursStartsAtTheCapWithoutExtension() {
        assertEquals("24h", KeepADBTimer.Duration.HOURS_24.token);
        assertEquals(24 * HOUR, KeepADBTimer.Duration.HOURS_24.millis);
        assertEquals(KeepADBTimer.MAX_REMAINING_MS, KeepADBTimer.Duration.HOURS_24.millis);
        assertEquals(KeepADBTimer.Duration.HOURS_24, KeepADBTimer.Duration.fromToken("24h"));
        assertEquals(7, KeepADBTimer.Duration.values().length);
        assertEquals(KeepADBTimer.Duration.HOURS_24,
                KeepADBTimer.Duration.values()[KeepADBTimer.Duration.values().length - 1]);

        KeepADB.ToggleResult result = KeepADBTimer.start(context, KeepADBTimer.Duration.HOURS_24, "test");

        assertTrue(result.isSuccess());
        assertTrue(gateway.isEnabled(context));
        KeepADBTimer.Status status = KeepADBTimer.status(context);
        assertEquals(KeepADBTimer.MAX_REMAINING_MS, status.remainingMs);
        assertFalse("A 24 h timer is already at the cap", status.canExtend());
        clock.advance(24 * HOUR - MINUTE);
        assertTrue(KeepADBTimer.isActive(context));
        clock.advance(2 * MINUTE);
        KeepADBTimer.finishIfExpired(context);
        assertFalse("It ends like every other duration", KeepADBTimer.isActive(context));
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
    public void aManualOnAfterAnOutstandingEndDropsTheOffNotice() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBTimer.start(context, KeepADBTimer.Duration.MINUTES_15, "test");
        clock.advance(15 * MINUTE);
        gateway.setWriteSuccess(false);
        KeepADBTimer.finishIfExpired(context);
        assertTrue(KeepADBTimer.isShutdownPending(context));
        gateway.setWriteSuccess(true);

        KeepADB.setEnabled(context, true, KeepADB.SOURCE_APP);
        fireTimerAlarm();
        KeepADBTimer.restore(context);
        drainToggles();

        assertTrue(gateway.isEnabled(context));
        assertTrue("No 'Wifi-ADB off' notice after the user switched it on", notices.isEmpty());
    }

    // ---- Review #1000 (Claude S4) ------------------------------------------------------------------

    @Test
    public void anExpiryWhileWifiAdbIsAlreadyOffStopsTheService() {
        KeepADBTimer.start(context, KeepADBTimer.Duration.MINUTES_15, "test");
        gateway.setEnabledByUser(false); // left the trusted Wi-Fi: Android dropped it, the timer waits
        clock.advance(16 * MINUTE);
        shadowOf((Application) context).clearStartedServices();

        assertTrue(KeepADBTimer.finishIfExpired(context));

        Intent stopped = shadowOf((Application) context).getNextStoppedService();
        assertNotNull("Nothing was written, yet the timer's service must stop", stopped);
        assertEquals(KeepADBService.class.getName(), stopped.getComponent().getClassName());
    }

    @Test
    public void allowingANetworkFromTheTrustPromptKeepsTheTimer() {
        KeepADBTimer.start(context, KeepADBTimer.Duration.HOUR_1, "test");
        gateway.setEnabledByUser(false);

        KeepADB.setEnabled(context, true, KeepADB.SOURCE_NETWORK_TRUST_PROMPT);

        assertTrue("The prompt answers a network question, not the limit", KeepADBTimer.isActive(context));
        assertTrue(gateway.isEnabled(context));
    }

    @Test
    public void aTimerOffThatIsNoLongerOwedIsNeitherRecordedNorWritten() {
        // The race the review found: a driver looked while the off was owed, then a manual "on"
        // settled it. Under the KeepADB monitor the timer's off and its intent are dropped.
        KeepADBTimer.start(context, KeepADBTimer.Duration.HOUR_1, "test");
        assertFalse(KeepADBTimer.isShutdownPending(context));

        KeepADB.recordTimerOffIntent(context);
        KeepADB.ToggleResult result = KeepADB.setEnabled(context, false, KeepADB.SOURCE_TIMER_EXPIRY);
        drainToggles();

        assertEquals(KeepADB.ToggleResult.SUPERSEDED, result);
        assertTrue(gateway.isEnabled(context));
        assertTrue("The user's on stays the intent", KeepADBPreferences.getLastDesiredOn(context));
    }

    @Test
    public void aClockSetBroadcastFinishesATimerTheClockEnded() {
        KeepADBTimer.start(context, KeepADBTimer.Duration.MINUTES_30, "test");
        clock.setWallClock(clock.wall + 2 * HOUR);

        new BootReceiver().onReceive(context, new Intent(Intent.ACTION_TIME_CHANGED));
        drainToggles();

        assertFalse(prefs().contains(KeepADBTimer.KEY_STATE));
        assertFalse(gateway.isEnabled(context));
    }

    @Test
    public void theApplicationRegistersTheNoticeSinkAndSettlesAnInterruptedOff() {
        KeepADBTimer.resetForTesting();
        KeepADBTimer.setClockForTesting(clock);
        prefs().edit().putBoolean(KeepADBTimer.KEY_SHUTDOWN_PENDING, true)
                .putBoolean(KeepADBTimer.KEY_NOTICE_PENDING, true).commit();
        gateway.setEnabledByUser(true);

        ((KeepADBApplication) context).onCreate();
        drainToggles();

        assertTrue("The production sink is registered", KeepADBTimer.hasNoticeSinkForTesting());
        assertFalse(gateway.isEnabled(context));
        KeepADBTimer.restore(context);
        assertFalse(KeepADBTimer.isShutdownPending(context));
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

    // ---- Follow-ups #1007-#1014 ------------------------------------------------------------------

    private boolean endNoticePosted() {
        return shadowOf(context.getSystemService(NotificationManager.class))
                .getNotification(KeepADBTimerNotice.NOTIFICATION_ID) != null;
    }

    @Test
    public void aManualOnCancelsAPostedEndNotice() {
        // #1008: the notice was posted at the end; an hour later the user switches Wifi-ADB on.
        assertTrue(KeepADBTimerNotice.postExpired(context, null));
        assertTrue(endNoticePosted());

        KeepADB.setEnabled(context, true, KeepADB.SOURCE_TILE);

        assertTrue(gateway.isEnabled(context));
        assertFalse("'Wifi-ADB off' must not stay after a manual on", endNoticePosted());
    }

    @Test
    public void allowingFromTheTrustPromptAlsoCancelsAPostedEndNotice() {
        assertTrue(KeepADBTimerNotice.postExpired(context, null));

        KeepADB.setEnabled(context, true, KeepADB.SOURCE_NETWORK_TRUST_PROMPT);

        assertFalse(endNoticePosted());
    }

    @Test
    public void aManualOnThatFinishesAnExpiredTimerLeavesNoEndNoticeBehind() {
        // #1008: the deadline passed while Wifi-ADB was off and no driver has finished the end yet
        // (the alarm is inexact). The manual on finishes it, which posts the notice; the notice
        // must be cancelled afterwards, not before.
        KeepADBTimer.setNoticeSink(KeepADBTimerNotice.SINK);
        KeepADBTimer.start(context, KeepADBTimer.Duration.MINUTES_15, "test");
        gateway.setEnabledByUser(false);
        clock.advance(20 * MINUTE);
        assertTrue(KeepADBTimer.isExpiryPending(context));

        KeepADB.setEnabled(context, true, KeepADB.SOURCE_TILE);
        drainToggles();

        assertTrue(gateway.isEnabled(context));
        assertFalse("'Wifi-ADB off' must not claim the state of a switched-on Wifi-ADB",
                endNoticePosted());
        assertFalse(KeepADBTimer.isShutdownPending(context));
    }

    @Test
    public void aManualOffOrAnAutomaticOnKeepsThePostedEndNotice() {
        assertTrue(KeepADBTimerNotice.postExpired(context, null));

        KeepADB.setEnabled(context, false, KeepADB.SOURCE_TILE);
        assertTrue("An off agrees with the notice", endNoticePosted());
        KeepADB.setEnabled(context, true, "keep_alive_check");
        drainToggles();
        assertTrue("Only a deliberate on supersedes it", endNoticePosted());
    }

    @Test
    public void aStartWhoseWriteIsRejectedLeavesNoTimer() {
        // #1009: the UI says the start failed, so nothing may wait and end with an "off" notice.
        gateway.setWriteSuccess(false);

        KeepADB.ToggleResult result = KeepADBTimer.start(context, KeepADBTimer.Duration.HOUR_1, "test");

        assertEquals(KeepADB.ToggleResult.WRITE_REJECTED, result);
        assertFalse(KeepADBTimer.isActive(context));
        assertFalse(prefs().contains(KeepADBTimer.KEY_STATE));
        assertFalse(KeepADBPreferences.isKeepAliveEffective(context));
        clock.advance(2 * HOUR);
        assertFalse(KeepADBTimer.finishIfExpired(context));
        assertTrue("No end for a start that never switched on", notices.isEmpty());
    }

    @Test
    public void aStartRefusedWithASecurityExceptionLeavesNoTimer() {
        gateway.setWriteThrowsSecurityException(true);

        KeepADB.ToggleResult result = KeepADBTimer.start(context, KeepADBTimer.Duration.HOUR_1, "test");

        assertEquals(KeepADB.ToggleResult.SECURITY_EXCEPTION, result);
        assertFalse(KeepADBTimer.isActive(context));
        assertFalse(prefs().contains(KeepADBTimer.KEY_STATE));
    }

    @Test
    public void aFailedRestartKeepsTheTimerThatWasRunning() {
        KeepADBTimer.start(context, KeepADBTimer.Duration.MINUTES_30, "test");
        clock.advance(10 * MINUTE);
        gateway.setWriteSuccess(false);

        KeepADBTimer.start(context, KeepADBTimer.Duration.HOURS_8, "test");

        KeepADBTimer.Status status = KeepADBTimer.status(context);
        assertNotNull("Wifi-ADB is still on: its limit must stay", status);
        assertEquals(20 * MINUTE, status.remainingMs);
    }

    private void leaveAnOutstandingOff() {
        KeepADBTimer.start(context, KeepADBTimer.Duration.HOUR_1, "test");
        gateway.setWriteSuccess(false);
        KeepADB.setEnabled(context, false, KeepADB.SOURCE_TILE);
        assertTrue(KeepADBTimer.isShutdownPending(context));
        gateway.setWriteSuccess(true);
    }

    @Test
    public void aSelfPairingOnSettlesNoOutstandingTimerOff() {
        // #1010: the self-pairing setup ends nothing and settles nothing.
        leaveAnOutstandingOff();

        KeepADB.setEnabled(context, true, KeepADB.SOURCE_SELF_PAIRING);

        assertTrue("The timer's off is still owed", KeepADBTimer.isShutdownPending(context));
        KeepADBTimer.restore(context);
        drainToggles();
        assertFalse("The owed off is carried out", gateway.isEnabled(context));
    }

    @Test
    public void aSelfPairingOffThatTheReadbackConfirmsSettlesTheOutstandingOff() {
        leaveAnOutstandingOff();

        KeepADB.setEnabled(context, false, KeepADB.SOURCE_SELF_PAIRING);

        assertFalse(gateway.isEnabled(context));
        assertFalse("Wifi-ADB is off: nothing is owed any more", KeepADBTimer.isShutdownPending(context));
    }

    @Test
    public void aPendingAutomaticOnAcrossTheDeadlineDoesNotSwitchWifiAdbBackOn() {
        // #1013: Wifi-ADB is already off at the deadline, so the timer writes nothing; a debounced
        // automatic on planned just before must not fire afterwards and record "on" again.
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBTimer.start(context, KeepADBTimer.Duration.MINUTES_15, "test");
        gateway.setEnabledByUser(false);
        KeepADB.ToggleResult planned = KeepADB.setEnabled(context, true, "keep_alive_check");
        assertEquals("Within the window after the start's own write", KeepADB.ToggleResult.SCHEDULED, planned);
        clock.advance(15 * MINUTE);

        assertTrue(KeepADBTimer.finishIfExpired(context));
        drainToggles();

        assertFalse("Still off after the pending on's window", gateway.isEnabled(context));
        assertFalse("The off intent stands", KeepADBPreferences.getLastDesiredOn(context));
        assertFalse(KeepADB.isTogglePending());
        assertFalse(KeepADBTimer.isShutdownPending(context));
    }

    @Test
    public void aPendingAutomaticOnWithAGuardLeavesNoStaleOnIntentAtTheDeadline() {
        // #1013: timer-only session; the guard would abort and roll back to the baseline "on".
        KeepADBTimer.start(context, KeepADBTimer.Duration.MINUTES_15, "test");
        gateway.setEnabledByUser(false);
        KeepADB.setEnabled(context, true, "keep_alive_check",
                app -> KeepADBPreferences.isKeepAliveEffective(app));
        clock.advance(15 * MINUTE);

        assertTrue(KeepADBTimer.finishIfExpired(context));
        drainToggles();

        assertFalse(gateway.isEnabled(context));
        assertFalse("No stale on intent", KeepADBPreferences.getLastDesiredOn(context));
    }

    @Test
    public void aPendingAutomaticOnThatTheLooperAlreadyDispatchedFindsItselfSupersededAtTheDeadline() {
        // #1013: dropping the posted callback is not enough. The debounce runnable may already be
        // dispatched and waiting for the KeepADB monitor while the deadline's off intent is
        // recorded; it must find its intent token out of date instead of writing "on".
        List<Runnable> posted = new ArrayList<>();
        KeepADBFakeScheduler scheduler = new KeepADBFakeScheduler() {
            @Override
            public void postDelayed(Runnable runnable, long delayMs) {
                posted.add(runnable);
                super.postDelayed(runnable, delayMs);
            }
        };
        scheduler.setClockMs(100_000L);
        KeepADB.setSchedulerForTesting(scheduler);
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBTimer.start(context, KeepADBTimer.Duration.MINUTES_15, "test");
        gateway.setEnabledByUser(false);
        assertEquals(KeepADB.ToggleResult.SCHEDULED, KeepADB.setEnabled(context, true, "keep_alive_check"));
        assertEquals(1, posted.size());
        int writesBefore = gateway.writes.size();
        clock.advance(15 * MINUTE);

        assertTrue(KeepADBTimer.finishIfExpired(context));
        posted.get(0).run();

        assertEquals("The superseded runnable writes nothing", writesBefore, gateway.writes.size());
        assertFalse(gateway.isEnabled(context));
        assertFalse("The off intent stands", KeepADBPreferences.getLastDesiredOn(context));
        assertFalse(KeepADB.isTogglePending());
    }

    @Test
    public void thePendingTimerOffItselfIsNotSupersededByARepeatedDriver() {
        // The other side of #1013: only a pending automatic "on" gives way, not the timer's own
        // debounced "off" that a later driver finds still waiting.
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBTimer.start(context, KeepADBTimer.Duration.MINUTES_15, "test");
        clock.advance(15 * MINUTE);
        KeepADB.setEnabled(context, true, "keep_alive_check");
        assertTrue(KeepADBTimer.finishIfExpired(context));
        assertTrue("The timer's off waits in the debounce window", KeepADB.isTogglePending());

        KeepADB.recordTimerOffIntent(context);

        assertTrue("Still pending", KeepADB.isTogglePending());
        drainToggles();
        assertFalse(gateway.isEnabled(context));
        assertFalse(KeepADBTimer.isShutdownPending(context));
    }

    @Test
    public void aDebouncedTimerOffSettledByAReadbackBeforeItFiresWritesNothing() {
        // #1014: the off waits in the debounce window; the user switches Wifi-ADB off in Android's
        // settings, a driver's readback settles the obligation (no newer token), and the user
        // switches it on again. The waiting off must not switch it off once more.
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBTimer.start(context, KeepADBTimer.Duration.MINUTES_15, "test");
        clock.advance(15 * MINUTE);
        KeepADB.setEnabled(context, true, "keep_alive_check");
        assertTrue(KeepADBTimer.finishIfExpired(context));
        assertTrue(KeepADB.isTogglePending());
        int writesBefore = gateway.writes.size();

        gateway.setEnabledByUser(false);
        KeepADBTimer.settleShutdown(context);
        assertFalse("Settled by the readback", KeepADBTimer.isShutdownPending(context));
        assertTrue("Same intent, no newer token", KeepADB.isTogglePending());
        gateway.setEnabledByUser(true);
        drainToggles();

        assertEquals("The settled off is dropped, not written", writesBefore, gateway.writes.size());
        assertTrue(gateway.isEnabled(context));
        assertFalse("The off intent stays", KeepADBPreferences.getLastDesiredOn(context));
    }

    // ---- #1016: a debounced start --------------------------------------------------------------

    /** A manual off right before: the start's switch-on waits out the manual re-enable gap. */
    private KeepADB.ToggleResult startInsideTheReenableGap(KeepADBTimer.Duration duration) {
        KeepADB.setEnabled(context, false, KeepADB.SOURCE_TILE);
        KeepADB.ToggleResult result = KeepADBTimer.start(context, duration, "test");
        assertEquals("Precondition: the switch-on is debounced", KeepADB.ToggleResult.SCHEDULED, result);
        return result;
    }

    @Test
    public void aDebouncedStartWhoseDelayedWriteIsRejectedLeavesNoTimer() {
        gateway.setEnabledByUser(true);
        startInsideTheReenableGap(KeepADBTimer.Duration.HOUR_1);
        assertTrue("Effective from the first instant, as before", KeepADBTimer.isActive(context));
        gateway.setWriteSuccess(false);

        drainToggles();

        assertFalse(gateway.isEnabled(context));
        assertFalse("No timer for a switch-on that never happened", KeepADBTimer.isActive(context));
        assertFalse(prefs().contains(KeepADBTimer.KEY_STATE));
        assertFalse(KeepADBPreferences.isKeepAliveEffective(context));
        clock.advance(2 * HOUR);
        assertFalse(KeepADBTimer.finishIfExpired(context));
        assertTrue("No 'off' notice for it either", notices.isEmpty());
    }

    @Test
    public void aDebouncedStartRefusedWithASecurityExceptionLeavesNoTimer() {
        gateway.setEnabledByUser(true);
        startInsideTheReenableGap(KeepADBTimer.Duration.HOUR_1);
        gateway.setWriteThrowsSecurityException(true);

        drainToggles();

        assertFalse(KeepADBTimer.isActive(context));
        assertFalse(prefs().contains(KeepADBTimer.KEY_STATE));
    }

    @Test
    public void aDebouncedStartThatGoesThroughKeepsItsTimer() {
        // The other side: the hook must not undo a start whose delayed write succeeded.
        gateway.setEnabledByUser(true);
        startInsideTheReenableGap(KeepADBTimer.Duration.MINUTES_30);

        drainToggles();

        assertTrue(gateway.isEnabled(context));
        assertEquals(30 * MINUTE, KeepADBTimer.status(context).remainingMs);
        gateway.setWriteSuccess(false);
        KeepADB.setEnabled(context, true, "keep_alive_check");
        drainToggles();
        assertTrue("A later failed timer-unrelated write leaves it", KeepADBTimer.isActive(context));
    }

    @Test
    public void aDebouncedStartSupersededByAManualOffEndsWithWifiAdbOff() {
        // A superseded start is left to the newer intent: a manual off ends the timer.
        gateway.setEnabledByUser(true);
        startInsideTheReenableGap(KeepADBTimer.Duration.HOUR_1);

        KeepADB.setEnabled(context, false, KeepADB.SOURCE_TILE);
        drainToggles();

        assertFalse(gateway.isEnabled(context));
        assertFalse(KeepADBTimer.isActive(context));
        assertFalse(KeepADBPreferences.getLastDesiredOn(context));
    }

    @Test
    public void anImmediateFailedStartIsUndoneOnceAndLoggedOnce() {
        gateway.setWriteSuccess(false);

        KeepADBTimer.start(context, KeepADBTimer.Duration.HOUR_1, "test");
        drainToggles();

        assertFalse(KeepADBTimer.isActive(context));
        String journal = KeepADBDiagnostics.export(context);
        assertTrue(journal, journal.contains("outcome=cancelled detail=result=WRITE_REJECTED"));
        assertFalse("The delayed hook stands aside for an immediate failure",
                journal.contains("detail=result=WRITE_REJECTED delayed"));
    }

    @Test
    public void aDelayedFailureLeavesAStateThatReplacedTheStartAlone() {
        gateway.setEnabledByUser(true);
        startInsideTheReenableGap(KeepADBTimer.Duration.HOUR_1);
        assertTrue("+1 h inside the gap gives the timer a new state", KeepADBTimer.extend(context, "test"));
        gateway.setWriteSuccess(false);

        drainToggles();

        assertNotNull("Only the start's own state is undone", KeepADBTimer.status(context));
    }

    @Test
    public void aDelayedFailedRestartKeepsTheTimerThatWasRunning() {
        // Codex review #1016: the replaced timer comes back with its own deadline.
        KeepADBTimer.start(context, KeepADBTimer.Duration.MINUTES_30, "test");
        // An "off" intent right after the start's own write: the next start's on waits out the gap.
        KeepADB.recordExplicitIntent(context, false);
        assertEquals(KeepADB.ToggleResult.SCHEDULED,
                KeepADBTimer.start(context, KeepADBTimer.Duration.HOURS_8, "test"));
        gateway.setWriteSuccess(false);

        drainToggles();

        KeepADBTimer.Status status = KeepADBTimer.status(context);
        assertNotNull("The running timer is restored", status);
        assertEquals(30 * MINUTE, status.remainingMs);
    }
}
