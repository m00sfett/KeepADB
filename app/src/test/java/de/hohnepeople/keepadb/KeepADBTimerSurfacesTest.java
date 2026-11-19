package de.hohnepeople.keepadb;

import static de.hohnepeople.keepadb.KeepADBForceTestSupport.HOUR;
import static de.hohnepeople.keepadb.KeepADBForceTestSupport.MINUTE;
import static de.hohnepeople.keepadb.KeepADBForceTestSupport.posted;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlarmManager;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

/**
 * #1000: what the user sees and taps of the Wifi-ADB timer and the extended force mode: the timer
 * card and its actions, the force card's companion, "+1 h" behind the unlock, the channels, the end
 * notice, the launcher shortcut and the home-screen row.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBTimerSurfacesTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = RuntimeEnvironment.getApplication();
    private KeepADBForceTestSupport.TestClock clock;
    private KeepADBFakeSettingsGateway gateway;

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
        KeepADBTimer.setNoticeSink(KeepADBTimerNotice.SINK);
        KeepADBPreferences.setNotificationPrivacyEnabled(context, false);
    }

    @After
    public void tearDown() {
        shadowOf(context.getSystemService(KeyguardManager.class)).setIsDeviceLocked(false);
        resetState();
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(null);
    }

    private void resetState() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
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

    private Notification card() {
        KeepADBNotification.renderEndpoint(context, "192.0.2.1", 40000);
        return posted(context, KeepADBNotification.NOTIFICATION_ID);
    }

    // ---- The timer card ---------------------------------------------------------------------------

    @Test
    public void theTimerCardNamesTheEndCountsDownAndOffersOffNowAndExtend() {
        KeepADBTimer.start(context, KeepADBTimer.Duration.MINUTES_30, "test");

        Notification card = card();

        assertEquals(KeepADBNotification.TIMER_CHANNEL_ID, card.getChannelId());
        assertFalse("Neutral, not red", card.extras.getBoolean(Notification.EXTRA_COLORIZED));
        assertTrue(card.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
                .startsWith("Wifi-ADB on until "));
        assertTrue(card.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString()
                .startsWith("Timer · off at "));
        assertTrue(card.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER));
        assertTrue(card.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN));
        assertEquals(clock.wall + 30 * MINUTE, card.when);
        assertEquals(2, card.actions.length);
        assertEquals("Off now", card.actions[0].title.toString());
        assertEquals("+1 h", card.actions[1].title.toString());
        assertEquals(KeepADBReceiver.ACTION_TIMER_EXTEND,
                shadowOf(card.actions[1].actionIntent).getSavedIntent().getAction());
        assertTrue(card.actions[1].isAuthenticationRequired());
        assertNull(posted(context, KeepADBNotification.TIMER_NOTIFICATION_ID));
    }

    @Test
    public void atTheCapTheCardOffersNoExtension() {
        KeepADBTimer.start(context, KeepADBTimer.Duration.HOURS_8, "test");
        for (int i = 0; i < 16; i++) KeepADBTimer.extend(context, "test");

        Notification card = card();

        assertEquals(1, card.actions.length);
        assertEquals("Off now", card.actions[0].title.toString());
    }

    @Test
    public void offNowOnTheTimerCardEndsTheTimerAndSwitchesOff() {
        KeepADBTimer.start(context, KeepADBTimer.Duration.HOUR_1, "test");
        Intent tapped = shadowOf(card().actions[0].actionIntent).getSavedIntent();

        new KeepADBReceiver().onReceive(context, tapped);

        assertFalse(KeepADBTimer.isActive(context));
        assertFalse(gateway.isEnabled(context));
    }

    @Test
    public void extendFromTheNotificationNeedsAnUnlockedDevice() {
        KeepADBTimer.start(context, KeepADBTimer.Duration.MINUTES_30, "test");
        Intent tapped = shadowOf(card().actions[1].actionIntent).getSavedIntent();
        shadowOf(context.getSystemService(KeyguardManager.class)).setIsDeviceLocked(true);

        new KeepADBReceiver().onReceive(context, tapped);
        assertEquals("Locked: nothing added", 30 * MINUTE, KeepADBTimer.status(context).remainingMs);

        shadowOf(context.getSystemService(KeyguardManager.class)).setIsDeviceLocked(false);
        new KeepADBReceiver().onReceive(context, tapped);
        assertEquals(30 * MINUTE + HOUR, KeepADBTimer.status(context).remainingMs);
    }

    @Test
    public void forceExtendFromTheNotificationNeedsAnUnlockedDeviceToo() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));
        Intent tapped = shadowOf(card().actions[2].actionIntent).getSavedIntent();
        assertEquals(KeepADBReceiver.ACTION_FORCE_EXTEND, tapped.getAction());
        shadowOf(context.getSystemService(KeyguardManager.class)).setIsDeviceLocked(true);

        new KeepADBReceiver().onReceive(context, tapped);
        assertEquals(HOUR, KeepADBForceMode.status(context).remainingMs);

        shadowOf(context.getSystemService(KeyguardManager.class)).setIsDeviceLocked(false);
        new KeepADBReceiver().onReceive(context, tapped);
        assertEquals(2 * HOUR, KeepADBForceMode.status(context).remainingMs);
    }

    @Test
    public void forceAndTimerTogetherShowTheRedCardAndTheTimerOnItsOwn() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOURS_24, false));
        KeepADBTimer.start(context, KeepADBTimer.Duration.HOURS_2, "test");

        Notification red = card();
        assertEquals(KeepADBNotification.FORCE_CHANNEL_ID, red.getChannelId());
        Notification companion = posted(context, KeepADBNotification.TIMER_NOTIFICATION_ID);
        assertNotNull("The timer gets its own card next to the force card", companion);
        assertEquals(KeepADBNotification.TIMER_CHANNEL_ID, companion.getChannelId());
        assertTrue(companion.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
                .startsWith("Wifi-ADB timer: off at "));
        assertEquals("Off now", companion.actions[0].title.toString());
        assertEquals("+1 h", companion.actions[1].title.toString());

        KeepADBForceMode.endNow(context);
        Notification timerCard = card();
        assertEquals(KeepADBNotification.TIMER_CHANNEL_ID, timerCard.getChannelId());
        assertNull("Without force the companion goes", posted(context, KeepADBNotification.TIMER_NOTIFICATION_ID));
    }

    @Test
    public void aSecondForceActivationAlertsAgain() {
        // Codex review: every render is "only alert once"; a new episode must lift it once.
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));
        // The activation renders the card itself: that first post of the episode alerts.
        Notification first = posted(context, KeepADBNotification.NOTIFICATION_ID);
        assertNotNull(first);
        assertEquals(0, first.flags & Notification.FLAG_ONLY_ALERT_ONCE);
        Notification refresh = card();
        assertTrue((refresh.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0);

        KeepADBForceMode.endNow(context);
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));
        Notification second = posted(context, KeepADBNotification.NOTIFICATION_ID);
        assertEquals("A new episode alerts again", 0, second.flags & Notification.FLAG_ONLY_ALERT_ONCE);
    }

    @Test
    public void theTimerCardDoesNotClaimKeepAliveIsOff() {
        KeepADBTimer.start(context, KeepADBTimer.Duration.HOUR_1, "test");
        assertFalse(card().extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
                .contains(context.getString(R.string.notification_text_keepalive_off)));
    }

    @Test
    public void aHiddenNotificationStaysVisibleWhileATimerRuns() {
        KeepADBPreferences.setNotificationHidden(context, true);
        KeepADBTimer.start(context, KeepADBTimer.Duration.HOUR_1, "test");

        assertNotNull(card());
    }

    // ---- Channels ---------------------------------------------------------------------------------

    @Test
    public void theThreeChannelsAreSilentAndTheOldOneIsDeleted() {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(
                KeepADBNotification.OBSOLETE_ENDPOINT_CHANNEL_ID, "old", NotificationManager.IMPORTANCE_LOW));

        assertTrue(KeepADBNotification.prepareChannel(context));

        assertNull(manager.getNotificationChannel(KeepADBNotification.OBSOLETE_ENDPOINT_CHANNEL_ID));
        assertImportance(manager, KeepADBNotification.CHANNEL_ID, NotificationManager.IMPORTANCE_DEFAULT);
        assertImportance(manager, KeepADBNotification.FORCE_CHANNEL_ID, NotificationManager.IMPORTANCE_HIGH);
        assertImportance(manager, KeepADBNotification.TIMER_CHANNEL_ID, NotificationManager.IMPORTANCE_DEFAULT);
    }

    private static void assertImportance(NotificationManager manager, String id, int importance) {
        NotificationChannel channel = manager.getNotificationChannel(id);
        assertNotNull(id, channel);
        assertEquals(id, importance, channel.getImportance());
        assertNull(id + " is silent", channel.getSound());
        assertFalse(id + " does not vibrate", channel.shouldVibrate());
    }

    // ---- The end notice ---------------------------------------------------------------------------

    @Test
    public void theEndPostsASilentNoticeThatGoesAwayByItself() {
        KeepADBTimer.start(context, KeepADBTimer.Duration.MINUTES_15, "test");
        clock.advance(15 * MINUTE);

        assertTrue(KeepADBTimer.finishIfExpired(context));
        ShadowLooper.idleMainLooper(5_000L, java.util.concurrent.TimeUnit.MILLISECONDS);

        Notification notice = posted(context, KeepADBTimerNotice.NOTIFICATION_ID);
        assertNotNull(notice);
        assertEquals(KeepADBNotification.TIMER_CHANNEL_ID, notice.getChannelId());
        assertEquals("Wifi-ADB off – timer ended", notice.extras.getCharSequence(Notification.EXTRA_TITLE).toString());
        assertEquals(KeepADBTimerNotice.TIMEOUT_MS, notice.getTimeoutAfter());
        assertTrue((notice.flags & Notification.FLAG_AUTO_CANCEL) != 0);
        assertTrue(notice.actions == null || notice.actions.length == 0);

        KeepADBTimer.start(context, KeepADBTimer.Duration.MINUTES_15, "test");
        assertNull("A new start removes the old notice", posted(context, KeepADBTimerNotice.NOTIFICATION_ID));
    }

    @Test
    public void theNoticeSaysWhenTheTimerEndedWhileThePhoneWasOff() {
        KeepADBTimer.start(context, KeepADBTimer.Duration.MINUTES_15, "test");
        clock.reboot(HOUR, MINUTE);

        assertTrue(KeepADBTimer.finishIfExpired(context));
        ShadowLooper.idleMainLooper(5_000L, java.util.concurrent.TimeUnit.MILLISECONDS);

        assertEquals(context.getString(R.string.timer_expired_text_while_off),
                posted(context, KeepADBTimerNotice.NOTIFICATION_ID).extras
                        .getCharSequence(Notification.EXTRA_TEXT).toString());
    }

    // ---- Launcher shortcut ------------------------------------------------------------------------

    @Test
    public void theShortcutStartsTheDefaultDurationAndSaysSo() {
        KeepADBPreferences.setTimerDefaultDuration(context, KeepADBTimer.Duration.HOURS_2);

        Robolectric.buildActivity(KeepADBTimerShortcutActivity.class).setup();

        KeepADBTimer.Status status = KeepADBTimer.status(context);
        assertNotNull(status);
        assertEquals(2 * HOUR, status.remainingMs);
        assertTrue(gateway.isEnabled(context));
        assertEquals("Wifi-ADB on for 2 h", ShadowToast.getTextOfLatestToast());
    }

    /** Holds the unlock callback back instead of answering it at once. */
    public static class CapturingTimerShortcutActivity extends KeepADBTimerShortcutActivity {
        final List<Runnable> onUnlocked = new ArrayList<>();

        @Override
        void requestUnlock(Runnable unlocked, Runnable notUnlocked) {
            onUnlocked.add(unlocked);
        }
    }

    /**
     * #1022: the timer shortcut waited for an unlock, and meanwhile Wifi-ADB was switched on and
     * explicitly off from other surfaces. The "off" is newer than the tap: no timer starts.
     */
    @Test
    public void theShortcutStartsNoTimerAfterANewerExplicitOff() {
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        shadowOf(keyguard).setIsKeyguardSecure(true);
        shadowOf(keyguard).setIsDeviceLocked(true);
        CapturingTimerShortcutActivity activity =
                Robolectric.buildActivity(CapturingTimerShortcutActivity.class).setup().get();
        assertEquals(1, activity.onUnlocked.size());

        assertTrue(KeepADB.setEnabled(context, true, KeepADB.SOURCE_WIDGET).isSuccess());
        assertTrue(KeepADB.setEnabled(context, false, KeepADB.SOURCE_NOTIFICATION).isSuccess());

        shadowOf(keyguard).setIsDeviceLocked(false);
        activity.onUnlocked.get(0).run();

        assertFalse(KeepADBTimer.isActive(context));
        assertFalse(gateway.isEnabled(context));
        assertTrue(activity.isFinishing());
        assertTrue(KeepADBDiagnostics.export(context).contains("reason=newer_intent"));
    }

    /** #1022: without a newer decision the unlocked timer shortcut still starts its timer. */
    @Test
    public void theShortcutStartsTheTimerAfterTheUnlock() {
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        shadowOf(keyguard).setIsKeyguardSecure(true);
        shadowOf(keyguard).setIsDeviceLocked(true);
        CapturingTimerShortcutActivity activity =
                Robolectric.buildActivity(CapturingTimerShortcutActivity.class).setup().get();
        assertFalse(KeepADBTimer.isActive(context));

        shadowOf(keyguard).setIsDeviceLocked(false);
        activity.onUnlocked.get(0).run();

        assertTrue(KeepADBTimer.isActive(context));
        assertTrue(gateway.isEnabled(context));
    }

    // ---- Home screen ------------------------------------------------------------------------------

    @Test
    public void theHomeRowStartsAShowsTheTimeLeftAndCanDropTheLimit() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        Button start = activity.findViewById(R.id.timer_start);
        assertEquals(View.VISIBLE, activity.findViewById(R.id.timer_panel).getVisibility());
        assertEquals("On for 1 h", start.getText().toString());

        start.performClick();
        ShadowLooper.idleMainLooper();

        assertTrue(KeepADBTimer.isActive(context));
        assertTrue(gateway.isEnabled(context));
        TextView status = activity.findViewById(R.id.timer_status);
        assertEquals(View.VISIBLE, status.getVisibility());
        assertTrue(status.getText().toString(), status.getText().toString().contains("60 min left")
                || status.getText().toString().contains("1 h 0 min left"));
        assertEquals(View.GONE, start.getVisibility());
        assertEquals(View.VISIBLE, activity.findViewById(R.id.timer_extend).getVisibility());

        activity.findViewById(R.id.timer_extend).performClick();
        assertEquals(2 * HOUR, KeepADBTimer.status(context).remainingMs);

        shadowOf((Application) context).clearStartedServices();
        activity.findViewById(R.id.timer_no_limit).performClick();
        ShadowLooper.idleMainLooper();
        Intent stopped = shadowOf((Application) context).getNextStoppedService();
        assertNotNull("Codex review: without the overlay the service stops", stopped);
        assertFalse(KeepADBTimer.isActive(context));
        assertTrue("No limit leaves Wifi-ADB on", gateway.isEnabled(context));
        assertEquals("Off in 1 h", start.getText().toString());
    }

    @Test
    public void theRemainingTimeIsRoundedUpAndNeverZero() {
        assertEquals("1 min", KeepADBTimerText.remaining(context, 1L));
        assertEquals("42 min", KeepADBTimerText.remaining(context, 41 * MINUTE + 1));
        assertEquals("1 h 0 min", KeepADBTimerText.remaining(context, HOUR));
        assertEquals("24 h 0 min", KeepADBTimerText.remaining(context, 24 * HOUR));
    }

    // ---- Follow-ups #1007-#1014 ------------------------------------------------------------------

    private KeepADBWidgetContent.Tile keepAliveTile() {
        return KeepADBWidgetContent.tile(context, KeepADBWidgetConfig.Action.KEEP_ALIVE,
                KeepADBWidgetState.project(context));
    }

    @Test
    public void theWidgetKeepAliveTileShowsWhatATapChangesWhileATimerRuns() {
        // #1007: the setting is off and only a timer keeps Wifi-ADB alive.
        KeepADBTimer.start(context, KeepADBTimer.Duration.HOUR_1, "test");
        assertTrue(KeepADBPreferences.isKeepAliveEffective(context));
        assertEquals("The status still knows the overlay", KeepADBWidgetState.KeepAlive.ACTIVE,
                KeepADBWidgetState.project(context).keepAlive);

        KeepADBWidgetContent.Tile before = keepAliveTile();
        assertEquals(context.getString(R.string.tile_state_off), before.state);
        assertEquals(KeepADBWidgetContent.Style.OFF, before.style);

        KeepADBReceiver.handleToggleKeepAliveAction(context);

        assertTrue(KeepADBPreferences.isKeepAliveEnabled(context));
        KeepADBWidgetContent.Tile after = keepAliveTile();
        assertEquals("A tap visibly changes the tile", context.getString(R.string.widget_state_on), after.state);
        assertEquals(KeepADBWidgetContent.Style.ON, after.style);
    }

    @Test
    public void theTimerWaitingReasonFollowsTheHomeStatusResolution() {
        // #1012: no longer "waiting for a trusted Wi-Fi" whatever the reason.
        assertEquals(R.string.overview_reason_no_wifi, KeepADBTimerSection.waitingReason(false,
                MainActivity.KeepAliveWaitingDetail.BLOCKED_UNTRUSTED_NETWORK));
        assertEquals(R.string.overview_reason_untrusted, KeepADBTimerSection.waitingReason(true,
                MainActivity.KeepAliveWaitingDetail.BLOCKED_UNTRUSTED_NETWORK));
        assertEquals(R.string.overview_reason_identity, KeepADBTimerSection.waitingReason(true,
                MainActivity.KeepAliveWaitingDetail.BLOCKED_IDENTITY_UNAVAILABLE));
        assertEquals(R.string.overview_reason_open_network, KeepADBTimerSection.waitingReason(true,
                MainActivity.KeepAliveWaitingDetail.BLOCKED_OPEN_NETWORK));
        assertEquals(R.string.overview_reason_backoff, KeepADBTimerSection.waitingReason(true,
                MainActivity.KeepAliveWaitingDetail.BLOCKED_RECOVERY_BACKOFF));
        assertEquals("Connected, nothing blocks: it is switching on",
                R.string.timer_waiting_reason_switching, KeepADBTimerSection.waitingReason(true,
                        MainActivity.KeepAliveWaitingDetail.WIFI_DISCONNECTED));
    }

    @Test
    public void theHomeRowNamesMissingWifiWhileATimerWaits() {
        KeepADBTimer.start(context, KeepADBTimer.Duration.HOUR_1, "test");
        gateway.setEnabledByUser(false);
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> false);

        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();

        String text = ((TextView) activity.findViewById(R.id.timer_status)).getText().toString();
        assertTrue(text, text.startsWith("Timer running · no Wi-Fi · "));
    }
}
