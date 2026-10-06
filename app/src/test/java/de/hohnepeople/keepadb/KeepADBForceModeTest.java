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
    public void afterARebootAWallClockBeforeTheReboundBaseCannotBeMeasuredEitherAndEndsTheMode() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.DAYS_7, false));
        clock.advance(2 * HOUR);
        clock.reboot(30 * MINUTE, 5 * MINUTE);
        bootCompleted();
        long reboundBase = clock.wall;
        clock.advance(10 * MINUTE);

        // The second restart finds a wall clock that is after the activation but before the base
        // the budget was bound to: counting forward from the base is impossible, so it ends. Without
        // the rebinding this value would still be a measurable instant after the start.
        clock.reboot(5 * MINUTE, 1 * MINUTE);
        clock.setWallClock(reboundBase - MINUTE);

        assertFalse("The wall clock only counts forward from the rebound base",
                KeepADBForceMode.isActive(context));
    }

    // --- The budget is bound to the new boot after a restart (#763, review P1) -------------------------------

    @Test
    public void aClockSetBackwardAfterARebootStillCannotExtendTheRemainingBudget() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOURS_24, false));
        clock.advance(2 * HOUR);
        clock.reboot(30 * MINUTE, 5 * MINUTE);
        bootCompleted();
        assertEquals("The restart keeps the mode: 24 hours minus 2 hours before it, minus 35 minutes since",
                21 * HOUR + 25 * MINUTE, KeepADBForceMode.status(context).remainingMs);

        // An hour into the new boot a wrong date is corrected by half an hour (NTP, or by hand).
        clock.advance(HOUR);
        clock.setWallClock(clock.wall - 30 * MINUTE);
        timeChanged();

        assertEquals("One real hour has passed since the restart: 20 h 25 min are left, not 20 h 55 min",
                20 * HOUR + 25 * MINUTE, KeepADBForceMode.status(context).remainingMs);
        assertEquals("The re-armed alarm follows the same budget, not the extended wall measure",
                clock.elapsed + 20 * HOUR + 25 * MINUTE, alarms().peekNextScheduledAlarm().getTriggerAtMs());
    }

    @Test
    public void aClockSetForwardAfterARebootStillEndsTheModeEarlierNeverLate() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOURS_24, false));
        clock.advance(2 * HOUR);
        clock.reboot(30 * MINUTE, 5 * MINUTE);
        bootCompleted();

        clock.advance(HOUR);
        clock.setWallClock(clock.wall + 3 * HOUR); // set forward: the wall measure is the smaller one
        timeChanged();

        assertEquals("The narrower measure wins: 21 h 25 min minus 1 h real and 3 h set forward",
                17 * HOUR + 25 * MINUTE, KeepADBForceMode.status(context).remainingMs);

        clock.setWallClock(clock.wall + DAY);
        timeChanged();
        assertFalse("A clock set far forward ends it", KeepADBForceMode.isActive(context));
        assertNotNull("... and reports it once", posted(context, KeepADBForceNotice.NOTIFICATION_ID));
    }

    @Test
    public void theBudgetBoundToTheNewBootSurvivesProcessDeathWhicheverDriverRunsFirst() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOURS_24, false));
        String atStart = prefs().getString(KeepADBForceMode.KEY_STATE, null);
        clock.advance(2 * HOUR);
        clock.reboot(30 * MINUTE, 5 * MINUTE);

        // Not the boot receiver: a screen opening or the heartbeat may come first.
        assertFalse(KeepADBForceMode.finishIfExpired(context));
        String bound = prefs().getString(KeepADBForceMode.KEY_STATE, null);
        assertNotNull(bound);
        assertFalse("The remaining budget was stored for the new boot", bound.equals(atStart));

        // A new process has nothing of the mode but preferences and the clock itself.
        KeepADBForceMode.resetForTesting();
        KeepADBForceMode.setClockForTesting(clock);
        clock.advance(HOUR);
        clock.setWallClock(clock.wall - 30 * MINUTE);

        assertEquals("No driver ran after the correction: the pure read alone must not be extended",
                20 * HOUR + 25 * MINUTE, KeepADBForceMode.status(context).remainingMs);
    }

    @Test
    public void aSecondRebootCountsFromTheReboundBudgetAndBindsItAgain() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOURS_24, false));
        clock.advance(2 * HOUR);
        clock.reboot(30 * MINUTE, 5 * MINUTE);
        bootCompleted();                               // 21 h 25 min left, bound to boot 2
        clock.advance(HOUR);

        clock.reboot(10 * MINUTE, 2 * MINUTE);
        bootCompleted();

        assertEquals("21 h 25 min minus the hour and the 12 minutes since the first rebinding",
                20 * HOUR + 13 * MINUTE, KeepADBForceMode.status(context).remainingMs);

        clock.advance(HOUR);
        clock.setWallClock(clock.wall - 30 * MINUTE);
        timeChanged();

        assertEquals("Bound to boot 3 now: the correction extends nothing",
                19 * HOUR + 13 * MINUTE, KeepADBForceMode.status(context).remainingMs);

        clock.advance(19 * HOUR + 13 * MINUTE - 1);
        assertTrue(KeepADBForceMode.isActive(context));
        clock.advance(1);
        assertFalse("... and it ends exactly when the real time since the activation is 24 hours",
                KeepADBForceMode.isActive(context));
    }

    @Test
    public void aRebootDoesNotEndTheModeAndAnUnchangedBootDoesNotRewriteTheState() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.DAYS_7, false));
        clock.advance(HOUR);
        clock.reboot(10 * MINUTE, 2 * MINUTE);
        bootCompleted();
        assertTrue("A restart alone never ends the mode", KeepADBForceMode.isActive(context));
        String bound = prefs().getString(KeepADBForceMode.KEY_STATE, null);

        clock.advance(20 * MINUTE);
        KeepADBForceMode.restore(context);
        KeepADBForceMode.finishIfExpired(context);
        timeChanged();

        assertEquals("Within the bound boot nothing moves the stored base", bound,
                prefs().getString(KeepADBForceMode.KEY_STATE, null));
    }

    // --- A clock set backward is written down before a restart can credit it (#763, second review) -------------

    @Test
    public void aClockSetBackwardInOneBootIsWrittenDownSoTheNextRestartCannotCreditIt() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOURS_24, false));
        clock.advance(2 * HOUR);

        // Within this boot the monotonic clock already keeps the deadline right; what the restart
        // finds is only what was stored, so the set has to be stored while it is still known.
        clock.setWallClock(clock.wall - 30 * MINUTE);
        KeepADBForceMode.finishIfExpired(context); // any driver: heartbeat, screen, alarm, clock set
        assertEquals("Nothing is credited by the set itself", 22 * HOUR,
                KeepADBForceMode.status(context).remainingMs);

        clock.advance(HOUR);
        clock.reboot(30 * MINUTE, 5 * MINUTE);
        bootCompleted();

        assertEquals("Real time since the activation: 2 h, 1 h and 35 min; not 30 minutes less",
                24 * HOUR - 3 * HOUR - 35 * MINUTE, KeepADBForceMode.status(context).remainingMs);
    }

    @Test
    public void theClockSetBroadcastWritesItDownAtOnceAndSoDoesEveryFurtherSet() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOURS_24, false));
        clock.advance(2 * HOUR);
        clock.setWallClock(clock.wall - 30 * MINUTE);
        timeChanged();
        clock.advance(HOUR);
        clock.setWallClock(clock.wall - 10 * MINUTE); // a second correction, in the same boot
        timeChanged();
        clock.advance(HOUR);

        clock.reboot(30 * MINUTE, 5 * MINUTE);
        bootCompleted();

        assertEquals("2 h + 1 h + 1 h + 35 min of real time have passed",
                24 * HOUR - 4 * HOUR - 35 * MINUTE, KeepADBForceMode.status(context).remainingMs);
    }

    @Test
    public void aClockSetBackwardAfterARestartIsWrittenDownForTheRestartAfterThat() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOURS_24, false));
        clock.advance(2 * HOUR);
        clock.reboot(30 * MINUTE, 5 * MINUTE);
        bootCompleted();
        clock.advance(HOUR);
        clock.setWallClock(clock.wall - 30 * MINUTE);
        timeChanged();
        clock.advance(HOUR);

        clock.reboot(20 * MINUTE, 2 * MINUTE);
        bootCompleted();

        assertEquals("2 h, 35 min, 1 h, 1 h and 22 min of real time have passed",
                24 * HOUR - 2 * HOUR - 35 * MINUTE - 2 * HOUR - 22 * MINUTE,
                KeepADBForceMode.status(context).remainingMs);
    }

    @Test
    public void aSmallDriftOrCorrectionBelowTheToleranceWritesNothingAndTheSumOfSmallOnesDoes() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOURS_24, false));
        String atStart = prefs().getString(KeepADBForceMode.KEY_STATE, null);
        clock.advance(HOUR);

        clock.setWallClock(clock.wall - 3_000L);
        KeepADBForceMode.finishIfExpired(context);
        assertEquals("A few seconds of NTP step are no reason to write", atStart,
                prefs().getString(KeepADBForceMode.KEY_STATE, null));

        clock.setWallClock(clock.wall - 3_000L); // 6 s behind the monotonic clock in total
        KeepADBForceMode.finishIfExpired(context);
        assertFalse("... but what adds up beyond the tolerance is written, measured from the base",
                atStart.equals(prefs().getString(KeepADBForceMode.KEY_STATE, null)));
        assertEquals("The budget is exactly what the monotonic clock measures",
                23 * HOUR, KeepADBForceMode.status(context).remainingMs);
    }

    @Test
    public void aClockSetForwardIsNeverWrittenDownSoSettingItRightAgainLosesNothing() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOURS_24, false));
        clock.advance(HOUR);
        String before = prefs().getString(KeepADBForceMode.KEY_STATE, null);

        clock.setWallClock(clock.wall + 3 * HOUR); // set forward by mistake: the narrower measure wins
        timeChanged();
        assertEquals(20 * HOUR, KeepADBForceMode.status(context).remainingMs);
        assertEquals("Only the backward side is stored", before,
                prefs().getString(KeepADBForceMode.KEY_STATE, null));

        clock.setWallClock(clock.wall - 3 * HOUR); // and set right again
        timeChanged();
        assertEquals("Back on the real time: 23 hours are left, nothing was taken for good",
                23 * HOUR, KeepADBForceMode.status(context).remainingMs);
    }

    @Test
    public void aClockSetBackwardAndLaterCorrectedForwardCostsTheJumpButNeverCreditsIt() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOURS_24, false));
        clock.advance(HOUR);
        clock.setWallClock(clock.wall - HOUR); // a wrong date, seen by a driver ...
        timeChanged();
        clock.advance(10 * MINUTE);
        clock.setWallClock(clock.wall + HOUR); // ... and put right again afterwards
        timeChanged();

        long realRemaining = 24 * HOUR - HOUR - 10 * MINUTE;
        long remaining = KeepADBForceMode.status(context).remainingMs;
        assertTrue("Never more than the real time that is left", remaining <= realRemaining);
        assertEquals("The stored base cannot tell the correction from a clock set forward: it "
                + "shortens by the jump, the safe side", realRemaining - HOUR, remaining);
    }

    @Test
    public void aRestartTheBootCounterMissedIsStillTakenForARestartBecauseTheMonotonicClockWentBack() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOURS_24, false));
        int bootAtStart = clock.boot;
        clock.advance(2 * HOUR);
        clock.reboot(30 * MINUTE, 5 * MINUTE);
        clock.boot = bootAtStart; // a counter that did not move: only elapsed time went back
        assertEquals(24 * HOUR - 2 * HOUR - 35 * MINUTE, KeepADBForceMode.status(context).remainingMs);

        assertFalse(KeepADBForceMode.finishIfExpired(context));
        clock.advance(HOUR);
        clock.setWallClock(clock.wall - 30 * MINUTE);

        assertEquals("The base was rebound to the new run of the monotonic clock: 20 h 25 min",
                24 * HOUR - 3 * HOUR - 35 * MINUTE, KeepADBForceMode.status(context).remainingMs);
    }

    // --- An unreadable boot counter ends the mode (#763, review P1) ------------------------------------------

    @Test
    public void whenTheBootCountCannotBeReadAtTheStartALimitedModeIsRefusedAndNothingIsTouched() {
        clock.bootCountReadable = false;

        for (KeepADBForceMode.Span span : KeepADBForceMode.Span.values()) {
            if (span.isUnlimited()) continue;
            assertFalse(span.token, KeepADBForceMode.activate(context, span, false));
        }

        assertFalse(KeepADBForceMode.isActive(context));
        assertFalse("Refused: nothing stored", prefs().contains(KeepADBForceMode.KEY_STATE));
        assertFalse("... Keep-Alive is not switched on for a mode that cannot run",
                KeepADBPreferences.isKeepAliveEnabled(context));
        assertNull("... and no alarm armed", alarms().peekNextScheduledAlarm());
        assertNull("... and nothing to report", posted(context, KeepADBForceNotice.NOTIFICATION_ID));

        clock.bootCountReadable = true;
        assertTrue("The refusal is not permanent: readable again, it can be started",
                KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));
    }

    @Test
    public void whenTheBootCountBecomesUnreadableTheModeEndsAtOnceWithTheOneNoticeAndStaysEnded() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOURS_24, false));
        clock.advance(2 * HOUR);
        clock.reboot(30 * MINUTE, 5 * MINUTE);
        clock.bootCountReadable = false;

        assertFalse("The gate does not wait for any transition: off at once",
                KeepADBForceMode.isActive(context));
        bootCompleted();

        assertFalse(prefs().contains(KeepADBForceMode.KEY_STATE));
        assertNotNull("The existing one-time notice reports it", posted(context, KeepADBForceNotice.NOTIFICATION_ID));
        assertTrue("... without a way back into the mode",
                posted(context, KeepADBForceNotice.NOTIFICATION_ID).actions == null
                        || posted(context, KeepADBForceNotice.NOTIFICATION_ID).actions.length == 0);
        context.getSystemService(NotificationManager.class).cancel(KeepADBForceNotice.NOTIFICATION_ID);

        // The trigger goes away: the mode is over and does not come back, and nothing repeats the notice.
        clock.bootCountReadable = true;
        KeepADBForceMode.restore(context);
        bootCompleted();
        timeChanged();
        assertFalse("It healed by ending: no lasting block, no revival", KeepADBForceMode.isActive(context));
        assertNull("Exactly once", posted(context, KeepADBForceNotice.NOTIFICATION_ID));
        assertTrue("The user can start it again once the counter is readable",
                KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));
    }

    @Test
    public void aBootCountThatTurnsUnreadableWithinTheBootEndsTheModeToo() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOURS_24, false));
        clock.advance(HOUR);
        assertTrue(KeepADBForceMode.isActive(context));

        clock.bootCountReadable = false;

        assertFalse("Not telling a restart from none, the monotonic clock cannot be trusted: end",
                KeepADBForceMode.isActive(context));
        assertTrue(KeepADBForceMode.finishIfExpired(context));
        assertNotNull(posted(context, KeepADBForceNotice.NOTIFICATION_ID));
    }

    @Test
    public void theUnlimitedModeHasNoTimeBudgetAndDoesNotNeedTheBootCount() {
        clock.bootCountReadable = false;

        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.UNLIMITED, true));
        clock.advance(40 * DAY);
        KeepADBForceMode.restore(context);
        bootCompleted();

        assertTrue("Nothing to measure, nothing to end", KeepADBForceMode.isActive(context));
        assertNull(posted(context, KeepADBForceNotice.NOTIFICATION_ID));
        assertTrue(KeepADBForceMode.endNow(context));
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
    public void activatingStartsTheKeepAliveServiceButEndingAndExpiryNeverStartAForegroundService() {
        org.robolectric.shadows.ShadowApplication shadowApp = shadowOf((Application) context);
        drainStartedServices(shadowApp);

        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));
        Intent started = shadowApp.getNextStartedService();
        assertNotNull("Keep-Alive was switched on, so its service is started", started);
        assertEquals(KeepADBService.class.getName(), started.getComponent().getClassName());

        drainStartedServices(shadowApp);
        clock.advance(HOUR);
        assertTrue(KeepADBForceMode.finishIfExpired(context));
        assertNull("Expiry (a broadcast, possibly from the background) leaves the service alone",
                shadowApp.getNextStartedService());

        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));
        drainStartedServices(shadowApp);
        assertTrue(KeepADBForceMode.endNow(context));
        assertNull("Ending leaves it alone as well", shadowApp.getNextStartedService());
    }

    private static void drainStartedServices(org.robolectric.shadows.ShadowApplication shadowApp) {
        while (shadowApp.getNextStartedService() != null) {
            // discard
        }
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

    @Test
    public void aStoredBudgetThatIsNotPositiveOrExceedsItsSpanReadsAsOffAndNeverAsExtended() {
        String base = ";" + clock.wall + ";" + clock.elapsed + ";" + clock.boot;
        String[] damaged = {
                "2;1h;3600001" + base,      // more than the span: an extension
                "2;1h;0" + base, "2;1h;-5" + base, "2;1h;x" + base,
                "2;unlimited;1" + base,     // an unlimited mode has no budget
                "2;1h;1800000;0;5;7", "2;1h;1800000;1800000000000;-1;7",
                "2;1h;1800000" + base + ";9", "2;1h" + base};
        for (String value : damaged) {
            prefs().edit().putString(KeepADBForceMode.KEY_STATE, value).commit();
            assertFalse("'" + value + "' must read as off", KeepADBForceMode.isActive(context));
        }

        prefs().edit().putString(KeepADBForceMode.KEY_STATE, "2;1h;1800000" + base).commit();
        assertEquals("A good value in the same slot is on, with exactly its stored budget",
                30 * MINUTE, KeepADBForceMode.status(context).remainingMs);
        prefs().edit().putString(KeepADBForceMode.KEY_STATE, "2;1h;3600000" + base).commit();
        assertEquals("... and a budget equal to the span is the whole span",
                HOUR, KeepADBForceMode.status(context).remainingMs);
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

    // --- The reason of an early end is told apart (#773) ----------------------------------------------------------------

    private String noticeText() {
        Notification notice = posted(context, KeepADBForceNotice.NOTIFICATION_ID);
        assertNotNull(notice);
        return notice.extras.getCharSequence(Notification.EXTRA_TEXT).toString();
    }

    private static final String TIME_UP_TEXT = "The selected time is up.";
    private static final String SAFETY_TEXT = "ended early for safety";

    @Test
    public void aRealExpiryStillSaysTheTimeIsUpAndStoresNoReason() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));
        clock.advance(HOUR);

        assertTrue(KeepADBForceMode.finishIfExpired(context));

        assertTrue(noticeText(), noticeText().startsWith(TIME_UP_TEXT));
        assertFalse(noticeText().contains(SAFETY_TEXT));
        assertFalse("No reason is left behind once delivered", prefs().contains(KeepADBForceMode.KEY_NOTICE_REASON));
    }

    @Test
    public void anUnreadableBootCountEndsTheModeWithTheSafetyReasonNotTimeIsUp() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOURS_24, false));
        clock.advance(HOUR);
        clock.bootCountReadable = false;

        assertTrue(KeepADBForceMode.finishIfExpired(context));

        assertTrue(noticeText(), noticeText().contains(SAFETY_TEXT));
        assertFalse(noticeText().contains(TIME_UP_TEXT));
        assertTrue(noticeText().contains("Protection level back to: Maximum security."));
        assertFalse("Delivered: reason and pending flag are gone",
                prefs().contains(KeepADBForceMode.KEY_NOTICE_REASON));
        assertFalse(prefs().getBoolean(KeepADBForceMode.KEY_NOTICE_PENDING, false));
    }

    @Test
    public void aWallClockBeforeTheBaseAfterARestartEndsTheModeWithTheSafetyReason() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.DAYS_7, false));
        long start = clock.wall;
        clock.advance(HOUR);
        clock.reboot(10 * MINUTE, 2 * MINUTE);
        clock.setWallClock(start - DAY);

        assertTrue(KeepADBForceMode.finishIfExpired(context));

        assertTrue(noticeText(), noticeText().contains(SAFETY_TEXT));
        assertFalse(noticeText().contains(TIME_UP_TEXT));
    }

    @Test
    public void aDeadlinePassedAfterARestartStillSaysTheTimeIsUp() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));
        clock.advance(30 * MINUTE);
        clock.reboot(40 * MINUTE, 5 * MINUTE);

        assertTrue(KeepADBForceMode.finishIfExpired(context));

        assertTrue(noticeText(), noticeText().startsWith(TIME_UP_TEXT));
        assertFalse(noticeText().contains(SAFETY_TEXT));
    }

    @Test
    public void theReasonIsReportedOnceAndAFollowingExpiryOrActivationDoesNotInheritIt() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOURS_24, false));
        clock.bootCountReadable = false;
        assertTrue(KeepADBForceMode.finishIfExpired(context));
        assertTrue(noticeText().contains(SAFETY_TEXT));
        context.getSystemService(NotificationManager.class).cancel(KeepADBForceNotice.NOTIFICATION_ID);
        assertFalse(KeepADBForceMode.finishIfExpired(context));
        KeepADBForceMode.restore(context);
        assertNull("Exactly once", posted(context, KeepADBForceNotice.NOTIFICATION_ID));

        clock.bootCountReadable = true;
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));
        clock.advance(HOUR);
        assertTrue(KeepADBForceMode.finishIfExpired(context));
        assertTrue("A later real expiry says time is up", noticeText().startsWith(TIME_UP_TEXT));
    }

    @Test
    public void aPendingNoticeFromBeforeTheReasonKeyExistedReadsAsAnOrdinaryExpiry() {
        prefs().edit().putBoolean(KeepADBForceMode.KEY_NOTICE_PENDING, true).commit();

        KeepADBForceMode.restore(context);

        assertTrue(noticeText(), noticeText().startsWith(TIME_UP_TEXT));
    }

    @Test
    public void aSafetyReasonNeverSurvivesStartingOrEndingTheMode() {
        prefs().edit().putBoolean(KeepADBForceMode.KEY_NOTICE_PENDING, true)
                .putString(KeepADBForceMode.KEY_NOTICE_REASON, KeepADBForceMode.REASON_SAFETY).commit();
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));
        assertFalse(prefs().contains(KeepADBForceMode.KEY_NOTICE_REASON));

        prefs().edit().putString(KeepADBForceMode.KEY_NOTICE_REASON, KeepADBForceMode.REASON_SAFETY).commit();
        assertTrue(KeepADBForceMode.endNow(context));
        assertFalse(prefs().contains(KeepADBForceMode.KEY_NOTICE_REASON));
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

    private void bootCompleted() {
        new BootReceiver().onReceive(context, new Intent(Intent.ACTION_BOOT_COMPLETED));
    }

    private void timeChanged() {
        new BootReceiver().onReceive(context, new Intent(Intent.ACTION_TIME_CHANGED));
    }

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
