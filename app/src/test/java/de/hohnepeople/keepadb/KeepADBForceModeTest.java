package de.hohnepeople.keepadb;

import static de.hohnepeople.keepadb.KeepADBForceTestSupport.BSSID;
import static de.hohnepeople.keepadb.KeepADBForceTestSupport.DAY;
import static de.hohnepeople.keepadb.KeepADBForceTestSupport.HOUR;
import static de.hohnepeople.keepadb.KeepADBForceTestSupport.MINUTE;
import static de.hohnepeople.keepadb.KeepADBForceTestSupport.SSID;
import static de.hohnepeople.keepadb.KeepADBForceTestSupport.connectTo;
import static de.hohnepeople.keepadb.KeepADBForceTestSupport.posted;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlarmManager;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

import java.util.TimeZone;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlarmManager;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

/**
 * #763: the mechanism of the force mode, at the real call path. Who ends it and when: the pure
 * deadline, the state transition, the alarm, the service heartbeat, the receivers for boot, app
 * update and clock set. What survives what: process death (only preferences carry state), a reboot
 * (the monotonic clock starts over), a clock set in either direction, a time zone or daylight
 * saving change. Each time rule is tested from both sides: the case it must end and the case in
 * which it must not.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBForceModeTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = RuntimeEnvironment.getApplication();
    private final TimeZone originalTimeZone = TimeZone.getDefault();
    private KeepADBForceTestSupport.TestClock clock;
    private KeepADBFakeSettingsGateway gateway;

    @Before
    public void setUp() {
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.POST_NOTIFICATIONS,
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
        resetState();
        clock = new KeepADBForceTestSupport.TestClock();
        KeepADBForceMode.setClockForTesting(clock);
        gateway = new KeepADBFakeSettingsGateway(false);
        KeepADB.setGatewayForTesting(gateway);
    }

    @After
    public void tearDown() {
        TimeZone.setDefault(originalTimeZone);
        resetState();
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(null);
    }

    private void resetState() {
        prefs().edit().clear().commit();
        context.getSharedPreferences("keepadb_diagnostics", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADBForceMode.resetForTesting();
        KeepADBEndpointCoordinator.resetForTesting();
        KeepADBEndpoint.resetForTesting();
        KeepADB.resetForTesting();
        KeepADBRegisterClient.resetForTesting();
        context.getSystemService(NotificationManager.class).cancelAll();
        ShadowAlarmManager.reset();
    }

    // --- Off by default, and what "on" means ----------------------------------------------------------

    @Test
    public void theForceModeIsOffUntilTheDialogPathActivatesIt() {
        assertFalse(KeepADBForceMode.isActive(context));
        assertNull(KeepADBForceMode.status(context));
        assertFalse("A fresh installation never has it on", prefs().contains(KeepADBForceMode.KEY_STATE));
        assertFalse(KeepADBForceMode.isActive(null));
    }

    @Test
    public void everyLimitedSpanIsActiveForExactlyItsDurationAndSwitchesKeepAliveOn() {
        for (KeepADBForceMode.Span span : KeepADBForceMode.Span.values()) {
            if (span.isUnlimited()) continue;
            resetState();
            clock = new KeepADBForceTestSupport.TestClock();
            KeepADBForceMode.setClockForTesting(clock);
            assertFalse("precondition: Keep-Alive is off", KeepADBPreferences.isKeepAliveEnabled(context));

            assertTrue(span.token, KeepADBForceMode.activate(context, span, false));

            KeepADBForceMode.Status status = KeepADBForceMode.status(context);
            assertNotNull(span.token, status);
            assertEquals(span.token, span.millis, status.remainingMs);
            assertEquals(span.token, clock.wall + span.millis, status.endsAtWallMs);
            assertTrue("F7: Force switches Keep-Alive on with it", KeepADBPreferences.isKeepAliveEnabled(context));
            clock.advance(span.millis - 1);
            assertTrue(span.token + " is still on one millisecond before its deadline",
                    KeepADBForceMode.isActive(context));
            clock.advance(1);
            assertFalse(span.token + " is off at its deadline", KeepADBForceMode.isActive(context));
        }
    }

    @Test
    public void theUnlimitedSpanIsRefusedWithoutTheAcknowledgmentAndAcceptedWithIt() {
        assertFalse(KeepADBForceMode.activate(context, KeepADBForceMode.Span.UNLIMITED, false));
        assertFalse(KeepADBForceMode.isActive(context));
        assertFalse("A refused activation stores nothing", prefs().contains(KeepADBForceMode.KEY_STATE));
        assertFalse("... and does not touch Keep-Alive", KeepADBPreferences.isKeepAliveEnabled(context));

        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.UNLIMITED, true));
        assertTrue(KeepADBForceMode.isActive(context));
        assertTrue(KeepADBForceMode.status(context).isUnlimited());
    }

    @Test
    public void activatingDoesNotTouchAnythingTheTrustModelStores() {
        KeepADBTrustedNetwork.addBssid(context, BSSID, SSID);
        KeepADBNetworkBlocklist.blockSsid(context, "Blocked-Net");
        KeepADBTrustedNetwork.setTrustByNameEnabled(context, true);
        KeepADBTrustedNetwork.ProtectionLevel before = KeepADBTrustedNetwork.getProtectionLevel(context);
        String blocksBefore = KeepADBNetworkBlocklist.getBlockedSsids(context).toString();

        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOURS_24, false));
        clock.advance(25 * HOUR);
        KeepADBForceMode.finishIfExpired(context);

        assertEquals("Back to the previous protection level is the overlay going away",
                before, KeepADBTrustedNetwork.getProtectionLevel(context));
        assertEquals(blocksBefore, KeepADBNetworkBlocklist.getBlockedSsids(context).toString());
        assertEquals(1, KeepADBTrustedNetwork.getEntries(context).size());
        assertTrue(KeepADBTrustedNetwork.isTrustByNameEnabled(context));
    }

    @Test
    public void keepAliveThatWasAlreadyOnIsNotTouchedAndAManualOffStaysRespected() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADB.recordExplicitIntent(context, false);
        assertTrue(KeepADB.wasLastExplicitIntentOff(context));

        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));

        assertTrue("The user's manual 'Wireless Debugging off' is not an overridable block",
                KeepADB.wasLastExplicitIntentOff(context));
    }

    // --- The deadline survives restarts ------------------------------------------------------------------

    @Test
    public void aProcessKillChangesNothingBecauseOnlyPreferencesCarryTheState() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOURS_24, false));
        clock.advance(3 * HOUR);

        // A new process has no statics of the mode but the clock itself.
        KeepADBForceMode.resetForTesting();
        KeepADBForceMode.setClockForTesting(clock);

        KeepADBForceMode.Status status = KeepADBForceMode.status(context);
        assertNotNull(status);
        assertEquals(21 * HOUR, status.remainingMs);
    }

    @Test
    public void aRebootKeepsTheModeForTheRemainingWallClockTimeAndEndsItAtTheDeadline() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOURS_24, false));
        clock.advance(2 * HOUR);

        // Off for 30 minutes, then 5 minutes after boot: the monotonic clock starts over.
        clock.reboot(30 * MINUTE, 5 * MINUTE);

        KeepADBForceMode.Status status = KeepADBForceMode.status(context);
        assertNotNull("A reboot must not end the mode", status);
        assertEquals("24 hours minus 2 hours before the restart, minus 35 minutes since",
                24 * HOUR - 2 * HOUR - 35 * MINUTE, status.remainingMs);

        clock.advance(status.remainingMs - 1);
        assertTrue(KeepADBForceMode.isActive(context));
        clock.advance(1);
        assertFalse("... and ends it at the original deadline", KeepADBForceMode.isActive(context));
    }

    @Test
    public void aRebootThatSpansTheDeadlineEndsTheMode() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));
        clock.advance(10 * MINUTE);

        clock.reboot(3 * HOUR, 1 * MINUTE);

        assertFalse(KeepADBForceMode.isActive(context));
    }

    @Test
    public void afterARebootAWallClockBeforeTheStartCannotBeMeasuredAndEndsTheMode() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.DAYS_7, false));
        long start = clock.wall;
        clock.advance(HOUR);

        clock.reboot(10 * MINUTE, 2 * MINUTE);
        clock.setWallClock(start - DAY); // a reset real-time clock, or a clock set far back

        assertFalse("Fail closed: the remaining time is unknowable", KeepADBForceMode.isActive(context));
    }

    @Test
    public void whenTheBootCountCannotBeReadOnlyTheWallClockCountsAndAnEarlierClockEndsTheMode() {
        clock.bootCountReadable = false;
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOURS_24, false));
        long start = clock.wall;

        clock.advance(HOUR);
        assertTrue("Without a boot count the wall clock alone still measures", KeepADBForceMode.isActive(context));
        assertEquals(23 * HOUR, KeepADBForceMode.status(context).remainingMs);

        clock.setWallClock(start - 5 * MINUTE);
        assertFalse("... and a clock set before the start cannot be told from a restart",
                KeepADBForceMode.isActive(context));
    }

    @Test
    public void theUnlimitedModeSurvivesRebootsAndAppUpdatesUntilItIsEnded() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.UNLIMITED, true));

        clock.advance(400 * DAY);
        clock.reboot(DAY, 5 * MINUTE);
        new BootReceiver().onReceive(context, new Intent(Intent.ACTION_MY_PACKAGE_REPLACED));
        new BootReceiver().onReceive(context, new Intent(Intent.ACTION_BOOT_COMPLETED));

        assertTrue(KeepADBForceMode.isActive(context));
        assertNull("No notice for a mode that has no end",
                posted(context, KeepADBForceNotice.NOTIFICATION_ID));
        assertTrue(KeepADBForceMode.endNow(context));
        assertFalse(KeepADBForceMode.isActive(context));
    }

    // --- Clock sets and time zones: they may end the mode early, never extend it ---------------------------

    @Test
    public void aClockSetBackwardWithinABootCannotExtendTheMode() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));
        long start = clock.wall;

        // 30 minutes pass, and the wall clock is moved back by a day (a wrong date being corrected).
        clock.advance(30 * MINUTE);
        clock.setWallClock(start - DAY);

        assertTrue(KeepADBForceMode.isActive(context));
        assertEquals("The monotonic clock decides: 30 minutes are left, not a day",
                30 * MINUTE, KeepADBForceMode.status(context).remainingMs);

        clock.elapsed += 30 * MINUTE; // wall stays far in the past
        assertFalse("One real hour has passed, whatever the wall clock says",
                KeepADBForceMode.isActive(context));
    }

    @Test
    public void aClockSetForwardEndsTheModeEarlyNeverLate() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOURS_24, false));

        clock.elapsed += 10 * MINUTE; // only ten real minutes
        clock.wall += 2 * DAY;         // but the wall clock was set two days forward

        assertFalse("Fail safe: the narrower state wins", KeepADBForceMode.isActive(context));
    }

    @Test
    public void aSmallClockCorrectionBackwardDoesNotLengthenTheMode() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOURS_24, false));

        clock.advance(HOUR);
        clock.setWallClock(clock.wall - 10 * MINUTE); // an NTP step backwards

        assertEquals("23 hours of real time are left, not 23 hours 10 minutes",
                23 * HOUR, KeepADBForceMode.status(context).remainingMs);
    }

    @Test
    public void timeZonesAndDaylightSavingTimeDoNotMoveTheDeadline() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOURS_24, false));
        long remainingBefore = KeepADBForceMode.status(context).remainingMs;

        for (String zone : new String[] {"America/Los_Angeles", "Asia/Kolkata", "Pacific/Chatham",
                "UTC", "Europe/Berlin"}) {
            TimeZone.setDefault(TimeZone.getTimeZone(zone));
            assertEquals("Epoch based: " + zone, remainingBefore,
                    KeepADBForceMode.status(context).remainingMs);
        }
        // The moment the clocks go back one hour in Berlin the epoch clock simply keeps counting.
        clock.advance(HOUR);
        assertEquals(remainingBefore - HOUR, KeepADBForceMode.status(context).remainingMs);
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
        assertEquals(remainingBefore - HOUR, KeepADBForceMode.status(context).remainingMs);
    }

    // --- The transition: pure read first, then one visible expiry ------------------------------------------------

    @Test
    public void theGateIsOffAtTheDeadlineBeforeAnyTransitionRanAndAReadNeverWrites() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));
        String stored = prefs().getString(KeepADBForceMode.KEY_STATE, null);
        clock.advance(HOUR);

        assertFalse("Pure read: off the instant the deadline passed", KeepADBForceMode.isActive(context));

        assertEquals("... and reading never clears or writes the state", stored,
                prefs().getString(KeepADBForceMode.KEY_STATE, null));
        assertFalse(prefs().getBoolean(KeepADBForceMode.KEY_NOTICE_PENDING, false));
        assertNull("... and posts nothing", posted(context, KeepADBForceNotice.NOTIFICATION_ID));
    }

    @Test
    public void finishingAnExpiredModeClearsTheStateAndPostsTheNoticeExactlyOnce() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));
        clock.advance(HOUR);

        assertTrue("The first caller performs the transition", KeepADBForceMode.finishIfExpired(context));

        assertFalse(prefs().contains(KeepADBForceMode.KEY_STATE));
        assertFalse("Delivered: nothing pending any more",
                prefs().getBoolean(KeepADBForceMode.KEY_NOTICE_PENDING, false));
        assertNotNull(posted(context, KeepADBForceNotice.NOTIFICATION_ID));

        // Every other driver, any number of times, must not post it again: cancel it as the user
        // would and see that nothing brings it back.
        context.getSystemService(NotificationManager.class).cancel(KeepADBForceNotice.NOTIFICATION_ID);
        assertFalse(KeepADBForceMode.finishIfExpired(context));
        KeepADBForceMode.restore(context);
        new BootReceiver().onReceive(context, new Intent(Intent.ACTION_BOOT_COMPLETED));
        new KeepADBReceiver().onReceive(context, new Intent(KeepADBReceiver.ACTION_FORCE_EXPIRE));
        new BootReceiver().onReceive(context, new Intent(Intent.ACTION_TIME_CHANGED));
        assertNull("Exactly once", posted(context, KeepADBForceNotice.NOTIFICATION_ID));
    }

    @Test
    public void aRestartDuringTheRunningTimeStillGivesExactlyOneNoticeAfterTheDeadline() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOURS_24, false));
        clock.advance(2 * HOUR);
        clock.reboot(20 * MINUTE, 3 * MINUTE);
        new BootReceiver().onReceive(context, new Intent(Intent.ACTION_BOOT_COMPLETED));
        assertTrue("Still running after the restart", KeepADBForceMode.isActive(context));
        assertNull(posted(context, KeepADBForceNotice.NOTIFICATION_ID));

        // The alarm is gone with the reboot; the boot receiver armed a new one for what is left.
        ShadowAlarmManager.ScheduledAlarm alarm = alarms().peekNextScheduledAlarm();
        assertNotNull("The reboot re-armed the alarm", alarm);
        assertEquals(clock.elapsed + KeepADBForceMode.status(context).remainingMs, alarm.getTriggerAtMs());

        clock.advance(22 * HOUR);
        new KeepADBReceiver().onReceive(context, new Intent(KeepADBReceiver.ACTION_FORCE_EXPIRE));

        assertFalse(KeepADBForceMode.isActive(context));
        assertNotNull(posted(context, KeepADBForceNotice.NOTIFICATION_ID));
        context.getSystemService(NotificationManager.class).cancel(KeepADBForceNotice.NOTIFICATION_ID);
        KeepADBForceMode.finishIfExpired(context);
        assertNull(posted(context, KeepADBForceNotice.NOTIFICATION_ID));
    }

    @Test
    public void anExpiryThatHappenedWhileTheDeviceWasOffIsReportedAfterBootEvenWithoutKeepAlive() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));
        KeepADBPreferences.setKeepAliveEnabled(context, false);
        clock.advance(10 * MINUTE);
        clock.reboot(5 * HOUR, 1 * MINUTE);

        new BootReceiver().onReceive(context, new Intent(Intent.ACTION_BOOT_COMPLETED));

        assertFalse(KeepADBForceMode.isActive(context));
        assertNotNull("Reported, although nothing else started", posted(context, KeepADBForceNotice.NOTIFICATION_ID));
    }

    @Test
    public void anAppUpdateClearsTheAlarmAndTheUpdateReceiverArmsItAgain() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOURS_24, false));
        clock.advance(HOUR);
        dropTheAlarmsLikeAnUpdateDoes();
        assertNull(alarms().peekNextScheduledAlarm());

        new BootReceiver().onReceive(context, new Intent(Intent.ACTION_MY_PACKAGE_REPLACED));

        assertTrue("An update does not end the mode", KeepADBForceMode.isActive(context));
        assertEquals(clock.elapsed + 23 * HOUR, alarms().peekNextScheduledAlarm().getTriggerAtMs());
    }

    @Test
    public void anExpiryThatFellIntoAnAppUpdateIsReportedByTheUpdateReceiver() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));
        dropTheAlarmsLikeAnUpdateDoes();
        clock.advance(2 * HOUR);

        new BootReceiver().onReceive(context, new Intent(Intent.ACTION_MY_PACKAGE_REPLACED));

        assertFalse(KeepADBForceMode.isActive(context));
        assertNotNull(posted(context, KeepADBForceNotice.NOTIFICATION_ID));
    }

    @Test
    public void aNoticeThatWasRecordedButNotPostedBeforeProcessDeathIsDeliveredOnTheNextRestore() {
        // What a crash between "state cleared + pending set" and "notice posted" leaves behind.
        prefs().edit().putBoolean(KeepADBForceMode.KEY_NOTICE_PENDING, true).commit();
        assertNull(posted(context, KeepADBForceNotice.NOTIFICATION_ID));

        KeepADBForceMode.restore(context);

        assertNotNull(posted(context, KeepADBForceNotice.NOTIFICATION_ID));
        assertFalse(prefs().getBoolean(KeepADBForceMode.KEY_NOTICE_PENDING, false));
    }

    @Test
    public void theServiceHeartbeatFinishesAnExpiredMode() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));
        ServiceController<KeepADBService> controller = Robolectric.buildService(KeepADBService.class);
        try {
            controller.create();
            controller.get().onStartCommand(new Intent(context, KeepADBService.class), 0, 1);
            ShadowLooper.idleMainLooper();
            assertTrue("Within the time nothing is finished", KeepADBForceMode.isActive(context));
            assertNull(posted(context, KeepADBForceNotice.NOTIFICATION_ID));

            clock.advance(HOUR);
            controller.get().onStartCommand(new Intent(context, KeepADBService.class), 0, 2);
            ShadowLooper.idleMainLooper();

            assertFalse(prefs().contains(KeepADBForceMode.KEY_STATE));
            assertNotNull("The heartbeat delivered the notice", posted(context, KeepADBForceNotice.NOTIFICATION_ID));
        } finally {
            controller.destroy();
        }
    }

    @Test
    public void nothingExtendsOrRestartsAnExpiredOrRunningMode() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));
        String stored = prefs().getString(KeepADBForceMode.KEY_STATE, null);

        // Every driver while it runs: the start (the stored value) does not move.
        clock.advance(20 * MINUTE);
        KeepADBForceMode.restore(context);
        KeepADBForceMode.finishIfExpired(context);
        new KeepADBReceiver().onReceive(context, new Intent(KeepADBReceiver.ACTION_FORCE_EXPIRE));
        new BootReceiver().onReceive(context, new Intent(Intent.ACTION_TIME_CHANGED));
        assertEquals("No silent extension while it runs", stored,
                prefs().getString(KeepADBForceMode.KEY_STATE, null));
        assertEquals(40 * MINUTE, KeepADBForceMode.status(context).remainingMs);

        // And once it is over, no driver brings it back.
        clock.advance(HOUR);
        KeepADBForceMode.finishIfExpired(context);
        KeepADBForceMode.restore(context);
        new BootReceiver().onReceive(context, new Intent(Intent.ACTION_MY_PACKAGE_REPLACED));
        new KeepADBReceiver().onReceive(context, new Intent(KeepADBReceiver.ACTION_FORCE_EXPIRE));
        new KeepADBReceiver().onReceive(context, new Intent(KeepADBReceiver.ACTION_FORCE_END));
        assertFalse(KeepADBForceMode.isActive(context));
        assertFalse(prefs().contains(KeepADBForceMode.KEY_STATE));
        assertNull("... and no alarm is left armed", alarms().peekNextScheduledAlarm());
    }

    @Test
    public void startingANewSpanThroughTheDialogPathReplacesTheOldOneWithAFullNewStart() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.DAYS_7, false));
        clock.advance(2 * DAY);

        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));

        assertEquals("An explicit new start, not an extension and not a leftover of the old one",
                HOUR, KeepADBForceMode.status(context).remainingMs);
    }

    @Test
    public void aDamagedStoredValueReadsAsOffAndNeverAsOn() {
        String[] damaged = {
                "", "garbage", "1;1h", "1;1h;x;5;7", "1;1h;0;5;7", "1;1h;-3;5;7", "1;1h;99;-1;7",
                "2;1h;1800000000000;5;7", "1;forever;1800000000000;5;7", "1;1h;1800000000000;5;7;9"};
        for (String value : damaged) {
            prefs().edit().putString(KeepADBForceMode.KEY_STATE, value).commit();
            assertFalse("'" + value + "' must read as off", KeepADBForceMode.isActive(context));
        }
        // A good value in the same slot afterwards is on, so the rows above were refused for the
        // right reason and not because the slot was ignored.
        prefs().edit().putString(KeepADBForceMode.KEY_STATE, "1;1h;" + clock.wall + ";" + clock.elapsed
                + ";" + clock.boot).commit();
        assertTrue(KeepADBForceMode.isActive(context));
    }

    // --- Ending it by hand -------------------------------------------------------------------------------------------

    @Test
    public void endingByHandEndsOnceNeedsNoNoticeAndLeavesNothingBehind() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.DAYS_7, false));
        assertNotNull(alarms().peekNextScheduledAlarm());

        assertTrue(KeepADBForceMode.endNow(context));

        assertFalse(KeepADBForceMode.isActive(context));
        assertFalse(prefs().contains(KeepADBForceMode.KEY_STATE));
        assertNull("The user ended it, nobody needs to be told it expired",
                posted(context, KeepADBForceNotice.NOTIFICATION_ID));
        assertNull("The alarm is cancelled", alarms().peekNextScheduledAlarm());
        assertFalse("Nothing to end the second time", KeepADBForceMode.endNow(context));
        assertTrue("Keep-Alive stays on (it is the user's setting, Force only switched it on)",
                KeepADBPreferences.isKeepAliveEnabled(context));
    }

    @Test
    public void endingAModeThatJustRanOutIsReportedAsAnExpiryNotAsEndedByTheUser() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));
        clock.advance(HOUR + MINUTE);

        assertFalse("Not 'ended by you'", KeepADBForceMode.endNow(context));

        assertNotNull("... but reported as what it was", posted(context, KeepADBForceNotice.NOTIFICATION_ID));
    }

    @Test
    public void theEndActionOfTheNotificationEndsTheModeAndSaysSo() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOURS_24, false));

        assertTrue(KeepADBReceiver.handleForceEndAction(context));

        assertFalse(KeepADBForceMode.isActive(context));
        assertTrue(ShadowToast.getTextOfLatestToast(), ShadowToast.getTextOfLatestToast()
                .startsWith("Force mode ended."));
        ShadowToast.reset();
        assertFalse("Nothing running: nothing to end, no toast", KeepADBReceiver.handleForceEndAction(context));
        assertNull(ShadowToast.getLatestToast());
    }

    // --- The alarm ---------------------------------------------------------------------------------------------------------

    @Test
    public void theExpiryAlarmIsInexactMonotonicAndSetForTheEffectiveDeadline() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOURS_24, false));

        ShadowAlarmManager.ScheduledAlarm alarm = alarms().peekNextScheduledAlarm();
        assertNotNull(alarm);
        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, alarm.getType());
        assertEquals(clock.elapsed + 24 * HOUR, alarm.getTriggerAtMs());
        assertTrue("Fires also in Doze, but inexact: no exact alarm permission is needed",
                alarm.isAllowWhileIdle());
        assertFalse(alarm.getWindowLengthMs() == ShadowAlarmManager.WINDOW_EXACT);
        Intent fired = shadowOf(alarm.operation).getSavedIntent();
        assertEquals(KeepADBReceiver.ACTION_FORCE_EXPIRE, fired.getAction());
        assertEquals(KeepADBReceiver.class.getName(), fired.getComponent().getClassName());
        assertEquals(KeepADBForceMode.REQUEST_CODE_EXPIRY_ALARM, shadowOf(alarm.operation).getRequestCode());
    }

    @Test
    public void aClockSetReArmsTheAlarmForTheNewEffectiveDeadline() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOURS_24, false));
        clock.advance(HOUR);
        clock.setWallClock(clock.wall + 6 * HOUR); // set forward: the wall measure now has 17 h left

        new BootReceiver().onReceive(context, new Intent(Intent.ACTION_TIME_CHANGED));

        assertTrue(KeepADBForceMode.isActive(context));
        assertEquals(clock.elapsed + 17 * HOUR, alarms().peekNextScheduledAlarm().getTriggerAtMs());
    }

    @Test
    public void aClockSetFarForwardEndsAndReportsAtOnceInsteadOfWaitingForTheOldAlarm() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOURS_24, false));
        clock.setWallClock(clock.wall + 3 * DAY);

        new BootReceiver().onReceive(context, new Intent(Intent.ACTION_TIME_CHANGED));

        assertFalse(KeepADBForceMode.isActive(context));
        assertNotNull(posted(context, KeepADBForceNotice.NOTIFICATION_ID));
        assertNull(alarms().peekNextScheduledAlarm());
    }

    @Test
    public void anAlarmThatFiresBeforeTheEffectiveDeadlineChangesNothingAndArmsAgain() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOURS_24, false));
        clock.advance(HOUR);

        new KeepADBReceiver().onReceive(context, new Intent(KeepADBReceiver.ACTION_FORCE_EXPIRE));

        assertTrue(KeepADBForceMode.isActive(context));
        assertNull(posted(context, KeepADBForceNotice.NOTIFICATION_ID));
        assertEquals(clock.elapsed + 23 * HOUR, alarms().peekNextScheduledAlarm().getTriggerAtMs());
    }

    @Test
    public void theUnlimitedModeArmsNoAlarm() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.UNLIMITED, true));

        assertNull(alarms().peekNextScheduledAlarm());
    }

    // --- The expiry notice ------------------------------------------------------------------------------------------------

    @Test
    public void theExpiryNoticeHasItsOwnChannelAndOffersNoWayBackIntoTheMode() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));
        clock.advance(HOUR);
        KeepADBForceMode.finishIfExpired(context);

        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = manager.getNotificationChannel(KeepADBForceNotice.CHANNEL_ID);
        assertNotNull("N5: a channel of its own", channel);
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.getImportance());
        assertFalse(KeepADBForceNotice.CHANNEL_ID.equals(KeepADBNotification.CHANNEL_ID));
        assertFalse(KeepADBForceNotice.CHANNEL_ID.equals(KeepADBNetworkTrustPrompt.CHANNEL_ID));

        Notification notice = posted(context, KeepADBForceNotice.NOTIFICATION_ID);
        assertEquals(KeepADBForceNotice.CHANNEL_ID, notice.getChannelId());
        assertEquals("Force mode ended", notice.extras.getString(Notification.EXTRA_TITLE));
        assertTrue(notice.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
                .contains("Protection level back to: Maximum security."));
        assertTrue("A tap leads to the force row", shadowOf(notice.contentIntent).isActivityIntent());
        Intent tap = shadowOf(notice.contentIntent).getSavedIntent();
        assertEquals(SettingsActivity.class.getName(), tap.getComponent().getClassName());
        assertTrue(tap.getBooleanExtra(SettingsActivity.EXTRA_FOCUS_FORCE, false));
        assertTrue("An action that would start the mode again must not exist, no action at all here",
                notice.actions == null || notice.actions.length == 0);
        assertTrue("The notice goes away with a tap", (notice.flags & Notification.FLAG_AUTO_CANCEL) != 0);
        assertEquals("Nothing is switched off by the notice itself", 0, gateway.writes.size());
    }

    @Test
    public void theNoticeNamesTheStoredProtectionLevelInTheWordsOfThePresets() {
        KeepADBTrustedNetwork.setTrustByNameEnabled(context, true);
        assertTrue(expiredNoticeText().contains("Balanced"));

        KeepADBTrustedNetwork.setTrustByNameEnabled(context, false);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        assertTrue(expiredNoticeText().contains("All Wi-Fi networks (previous setting)"));

        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBTrustedNetwork.setSsidMatchingEnabled(context, true);
        KeepADBTrustedNetwork.addSsid(context, "Legacy-Net");
        assertTrue(expiredNoticeText().contains("Wi-Fi name list (previous setting)"));

        KeepADBTrustedNetwork.setSsidMatchingEnabled(context, false);
        assertTrue(expiredNoticeText().contains("Maximum security"));
    }

    @Test
    public void theNoticeOffersTurnOffNowOnlyWhenDebuggingIsStillOnInAWifiThatIsNotTrusted() {
        // Unknown network, Wi-Fi up, Wireless Debugging on: report and offer, never switch off.
        stillOnInNetwork(true, true);
        Notification exposed = expireAndReadNotice();
        assertEquals(1, exposed.actions.length);
        assertEquals("Turn off now", exposed.actions[0].title.toString());
        Intent turnOff = shadowOf(exposed.actions[0].actionIntent).getSavedIntent();
        assertEquals(KeepADBReceiver.ACTION_DISABLE, turnOff.getAction());
        assertEquals(KeepADBForceMode.REQUEST_CODE_EXPIRED_TURN_OFF,
                shadowOf(exposed.actions[0].actionIntent).getRequestCode());
        assertTrue(exposed.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
                .contains("Wireless debugging is still on right now."));
        assertTrue("F6: only reported, not switched off by the expiry", gateway.writes.isEmpty());
        assertTrue(KeepADB.isEnabled(context));

        // Controls, one fact changed each: the offer must disappear.
        stillOnInNetwork(false, true); // debugging already off
        assertNoOffer(expireAndReadNotice());
        stillOnInNetwork(true, false); // not on a Wi-Fi network at all
        assertNoOffer(expireAndReadNotice());
        stillOnInNetwork(true, true);
        KeepADBTrustedNetwork.addBssid(context, BSSID, SSID); // the network is trusted after all
        assertNoOffer(expireAndReadNotice());
    }

    @Test
    public void theNoticeOffersTurnOffNowOnABlockedNetworkToo() {
        stillOnInNetwork(true, true);
        KeepADBTrustedNetwork.addBssid(context, BSSID, SSID);
        KeepADBNetworkBlocklist.blockBssid(context, BSSID);

        Notification notice = expireAndReadNotice();

        assertEquals("Blocked is not trusted: with Force gone the block applies again, so report",
                1, notice.actions.length);
    }

    @Test
    public void theNoticePublicVersionIsNeutralAndCarriesNothingOfForceOrLevelOrActions() {
        stillOnInNetwork(true, true);
        Notification notice = expireAndReadNotice();

        Notification publicVersion = notice.publicVersion;
        assertNotNull(publicVersion);
        Bundle extras = publicVersion.extras;
        String visible = String.valueOf(extras.getCharSequence(Notification.EXTRA_TITLE))
                + String.valueOf(extras.getCharSequence(Notification.EXTRA_TEXT))
                + String.valueOf(extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        assertFalse(visible, visible.toLowerCase().contains("force"));
        assertFalse(visible, visible.contains("Maximum security"));
        assertFalse(visible, visible.contains("Wireless debugging"));
        assertEquals("Security notices", extras.getCharSequence(Notification.EXTRA_TITLE).toString());
        assertTrue("A glance at a locked screen must not trigger anything",
                publicVersion.actions == null || publicVersion.actions.length == 0);
        assertNull(publicVersion.contentIntent);
    }

    @Test
    public void withoutTheNotificationPermissionTheModeStillEndsAndNothingStaysPending() {
        shadowOf((Application) context).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));
        clock.advance(HOUR);

        assertTrue(KeepADBForceMode.finishIfExpired(context));

        assertFalse(KeepADBForceMode.isActive(context));
        assertNull(posted(context, KeepADBForceNotice.NOTIFICATION_ID));
        assertFalse("A notice that cannot be shown must not be shown days later",
                prefs().getBoolean(KeepADBForceMode.KEY_NOTICE_PENDING, false));
    }

    // --- Helpers ------------------------------------------------------------------------------------------------------------

    private SharedPreferences prefs() {
        return context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE);
    }

    private ShadowAlarmManager alarms() {
        return shadowOf(context.getSystemService(AlarmManager.class));
    }

    private String expiredNoticeText() {
        resetNoticeState();
        Notification notice = expireAndReadNotice();
        return notice.extras.getCharSequence(Notification.EXTRA_TEXT).toString();
    }

    private void resetNoticeState() {
        context.getSystemService(NotificationManager.class).cancelAll();
    }

    /** Android removes an app's alarms when it is updated (and at reboot); the receivers re-arm. */
    private void dropTheAlarmsLikeAnUpdateDoes() {
        ShadowAlarmManager.ScheduledAlarm armed = alarms().peekNextScheduledAlarm();
        if (armed != null) context.getSystemService(AlarmManager.class).cancel(armed.operation);
    }

    private Notification expireAndReadNotice() {
        resetNoticeState();
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));
        clock.advance(HOUR);
        assertTrue(KeepADBForceMode.finishIfExpired(context));
        Notification notice = posted(context, KeepADBForceNotice.NOTIFICATION_ID);
        assertNotNull(notice);
        return notice;
    }

    private void assertNoOffer(Notification notice) {
        assertTrue("No 'Turn off now' offer expected",
                notice.actions == null || notice.actions.length == 0);
    }

    /** Wireless Debugging {@code debuggingOn}, Wi-Fi {@code wifiUp}, on an access point nobody trusts. */
    private void stillOnInNetwork(boolean debuggingOn, boolean wifiUp) {
        gateway = new KeepADBFakeSettingsGateway(debuggingOn);
        KeepADB.setGatewayForTesting(gateway);
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> wifiUp);
        connectTo(context, SSID, BSSID);
    }
}
