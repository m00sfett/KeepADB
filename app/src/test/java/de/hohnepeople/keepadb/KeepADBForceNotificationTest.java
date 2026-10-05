package de.hohnepeople.keepadb;

import static de.hohnepeople.keepadb.KeepADBForceTestSupport.BSSID;
import static de.hohnepeople.keepadb.KeepADBForceTestSupport.HOUR;
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
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlarmManager;

/**
 * #763: the force mode on the persistent notification. The warning line leads the long text and the
 * header line and "End force mode" is the first action, exactly while the mode is on, in every state
 * the notification can be in. Nothing of it reaches a public version (F4). Rows 5 and 13 of the tap
 * table of the UX concept (3.1) live here: row 5 is this notification's content tap (the home
 * screen, where the warning card is), row 13 the expiry notice's (the force row in Settings), which
 * is pinned in {@link KeepADBForceModeTest}. N6: no PendingIntent of the force mode may collide with
 * another of the app.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBForceNotificationTest {

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
        KeepADBForceMode.setClockForTesting(clock);
        gateway = new KeepADBFakeSettingsGateway(false);
        KeepADB.setGatewayForTesting(gateway);
    }

    @After
    public void tearDown() {
        resetState();
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(null);
    }

    private void resetState() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADBForceMode.resetForTesting();
        KeepADBEndpointCoordinator.resetForTesting();
        KeepADBEndpoint.resetForTesting();
        KeepADB.resetForTesting();
        KeepADBUsbNotification.resetForTesting();
        KeepADBRegisterClient.resetForTesting();
        context.getSystemService(NotificationManager.class).cancelAll();
        ShadowAlarmManager.reset();
    }

    // --- The endpoint card ---------------------------------------------------------------------------------

    @Test
    public void withoutForceTheEndpointCardIsExactlyAsBefore() {
        KeepADBNotification.renderEndpoint(context, "192.0.2.1", 40000);

        Notification card = posted(context, KeepADBNotification.NOTIFICATION_ID);
        assertEquals(2, card.actions.length);
        assertEquals("Turn off Wifi-ADB", card.actions[0].title.toString());
        assertNull(card.extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
        assertFalse(card.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("Force"));
    }

    @Test
    public void whileForceIsOnTheCardLeadsWithTheWarningAndEndForceIsTheFirstAction() {
        startForce(KeepADBForceMode.Span.HOURS_24);

        KeepADBNotification.renderEndpoint(context, "192.0.2.1", 40000);

        Notification card = posted(context, KeepADBNotification.NOTIFICATION_ID);
        String line = card.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString();
        assertTrue(line, line.startsWith("Force mode on until "));
        assertTrue("The long text leads with the same line", card.extras
                .getCharSequence(Notification.EXTRA_BIG_TEXT).toString().startsWith(line + "\n"));
        assertEquals(3, card.actions.length);
        assertEquals("One UI cuts off the last action (#593): End force mode comes first",
                "End force mode", card.actions[0].title.toString());
        assertEquals("Turn off Wifi-ADB", card.actions[1].title.toString());
        Intent end = shadowOf(card.actions[0].actionIntent).getSavedIntent();
        assertEquals(KeepADBReceiver.ACTION_FORCE_END, end.getAction());
        assertEquals(KeepADBForceMode.REQUEST_CODE_END, shadowOf(card.actions[0].actionIntent).getRequestCode());
        assertTrue(shadowOf(card.actions[0].actionIntent).isBroadcastIntent());
    }

    @Test
    public void theUnlimitedModeSaysSoAndRow5TheContentTapStaysTheHomeScreen() {
        startForce(KeepADBForceMode.Span.UNLIMITED);

        KeepADBNotification.renderEndpoint(context, "192.0.2.1", 40000);

        Notification card = posted(context, KeepADBNotification.NOTIFICATION_ID);
        assertEquals("Force mode on, no end time",
                card.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString());
        Intent tap = shadowOf(card.contentIntent).getSavedIntent();
        assertEquals("Row 5: the warning card lives on the home screen",
                MainActivity.class.getName(), tap.getComponent().getClassName());
    }

    @Test
    public void theWarningIsThereInEveryStateOfTheNotificationAndGoneWithoutTheMode() {
        startForce(KeepADBForceMode.Span.HOUR_1);

        KeepADBNotification.renderSearching(context);
        assertForceCard(posted(context, KeepADBNotification.NOTIFICATION_ID));
        KeepADBNotification.renderDisabledKeepAliveWaiting(context);
        assertForceCard(posted(context, KeepADBNotification.NOTIFICATION_ID));
        KeepADBNotification.showPermissionMissing(context);
        assertForceCard(posted(context, KeepADBNotification.NOTIFICATION_ID));
        assertForceCard(KeepADBNotification.getServiceNotification(context));

        KeepADBForceMode.endNow(context);
        KeepADBNotification.renderSearching(context);
        assertNoForce(posted(context, KeepADBNotification.NOTIFICATION_ID));
        KeepADBNotification.renderEndpoint(context, "192.0.2.1", 40000);
        assertNoForce(posted(context, KeepADBNotification.NOTIFICATION_ID));
    }

    @Test
    public void theWarningDisappearsWithTheDeadlineEvenBeforeAnyTransitionRan() {
        startForce(KeepADBForceMode.Span.HOUR_1);
        clock.advance(HOUR - 1);
        KeepADBNotification.renderEndpoint(context, "192.0.2.1", 40000);
        assertForceCard(posted(context, KeepADBNotification.NOTIFICATION_ID));

        clock.advance(1);
        KeepADBNotification.renderEndpoint(context, "192.0.2.1", 40000);

        assertNoForce(posted(context, KeepADBNotification.NOTIFICATION_ID));
    }

    @Test
    public void theTransitionsRedrawThePostedNotificationWithoutAnyoneRenderingItByHand() {
        // Keep-Alive on, Wireless Debugging off: the coordinator shows its "waiting" placeholder.
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));
        assertForceCard(posted(context, KeepADBNotification.NOTIFICATION_ID));

        assertTrue(KeepADBForceMode.endNow(context));
        assertNoForce(posted(context, KeepADBNotification.NOTIFICATION_ID));

        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));
        assertForceCard(posted(context, KeepADBNotification.NOTIFICATION_ID));
        clock.advance(HOUR);
        assertTrue(KeepADBForceMode.finishIfExpired(context));
        assertNoForce(posted(context, KeepADBNotification.NOTIFICATION_ID));
    }

    @Test
    public void theEndActionOfTheRealPostedNotificationEndsTheMode() {
        startForce(KeepADBForceMode.Span.DAYS_7);
        KeepADBNotification.renderEndpoint(context, "192.0.2.1", 40000);
        Notification card = posted(context, KeepADBNotification.NOTIFICATION_ID);

        Intent tapped = shadowOf(card.actions[0].actionIntent).getSavedIntent();
        new KeepADBReceiver().onReceive(context, tapped);

        assertFalse(KeepADBForceMode.isActive(context));
        KeepADBNotification.renderEndpoint(context, "192.0.2.1", 40000);
        assertNoForce(posted(context, KeepADBNotification.NOTIFICATION_ID));
    }

    // --- Lock screen (F4) ----------------------------------------------------------------------------------------

    @Test
    public void thePublicVersionStaysNeutralWhileForceIsOnWhateverTheDetailsSetting() {
        startForce(KeepADBForceMode.Span.HOURS_24);
        for (boolean details : new boolean[] {false, true}) {
            KeepADBPreferences.setNotificationDetailsEnabled(context, details);
            KeepADBNotification.renderEndpoint(context, "192.0.2.1", 40000);

            Notification publicVersion = posted(context, KeepADBNotification.NOTIFICATION_ID).publicVersion;
            assertNotNull(publicVersion);
            String visible = String.valueOf(publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE))
                    + String.valueOf(publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT))
                    + String.valueOf(publicVersion.extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
                    + String.valueOf(publicVersion.extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
            assertFalse("details=" + details + ": " + visible, visible.toLowerCase().contains("force"));
            assertTrue("A glance at a locked screen must not trigger anything",
                    publicVersion.actions == null || publicVersion.actions.length == 0);
            assertNull(publicVersion.contentIntent);
        }
    }

    // --- The hide preference does not hide a running force mode ---------------------------------------------------------

    @Test
    public void aRunningForceModeKeepsItsWarningVisibleEvenWhenTheNotificationIsHidden() {
        KeepADBPreferences.setNotificationHidden(context, true);
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADB.recordExplicitIntent(context, false); // the service is not required: hidden is hidden
        assertFalse(KeepADBService.shouldRun(context));

        KeepADBNotification.renderEndpoint(context, "192.0.2.1", 40000);
        assertNull("Control: hidden and nothing running, no notification",
                posted(context, KeepADBNotification.NOTIFICATION_ID));
        KeepADBNotification.renderDisabledKeepAliveWaiting(context);
        assertNull("Control, placeholder path", posted(context, KeepADBNotification.NOTIFICATION_ID));

        startForceKeepingTheManualOff();
        KeepADBNotification.renderEndpoint(context, "192.0.2.1", 40000);
        assertForceCard(posted(context, KeepADBNotification.NOTIFICATION_ID));
        context.getSystemService(NotificationManager.class).cancelAll();
        KeepADBNotification.renderDisabledKeepAliveWaiting(context);
        assertForceCard(posted(context, KeepADBNotification.NOTIFICATION_ID));
    }

    // --- N6: the PendingIntents of the force mode collide with nobody ------------------------------------------------------

    @Test
    public void noPendingIntentOfTheForceModeCollidesWithAnyOtherOfTheApp() {
        List<NamedIntent> others = new ArrayList<>();
        List<NamedIntent> force = new ArrayList<>();

        // The persistent card in the force variant, with its content tap and its three actions.
        startForce(KeepADBForceMode.Span.DAYS_7);
        ShadowAlarmManager.ScheduledAlarm armed = shadowOf(context.getSystemService(AlarmManager.class))
                .peekNextScheduledAlarm();
        force.add(new NamedIntent("alarm", armed.operation));
        KeepADBNotification.renderEndpoint(context, "192.0.2.1", 40000);
        Notification card = posted(context, KeepADBNotification.NOTIFICATION_ID);
        others.add(new NamedIntent("card content", card.contentIntent));
        force.add(new NamedIntent("card end force", card.actions[0].actionIntent));
        others.add(new NamedIntent("card disable", card.actions[1].actionIntent));
        others.add(new NamedIntent("card keep-alive", card.actions[2].actionIntent));

        // The expiry notice with its offer.
        gateway = new KeepADBFakeSettingsGateway(true);
        KeepADB.setGatewayForTesting(gateway);
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        connectTo(context, SSID, BSSID);
        clock.advance(8 * 24 * HOUR);
        assertTrue(KeepADBForceMode.finishIfExpired(context));
        Notification notice = posted(context, KeepADBForceNotice.NOTIFICATION_ID);
        force.add(new NamedIntent("notice content", notice.contentIntent));
        force.add(new NamedIntent("notice turn off", notice.actions[0].actionIntent));

        // Every other notification that targets SettingsActivity or KeepADBReceiver.
        KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context);
        Notification prompt = posted(context, KeepADBNetworkTrustPrompt.NOTIFICATION_ID);
        others.add(new NamedIntent("prompt content", prompt.contentIntent));
        for (Notification.Action action : prompt.actions) {
            others.add(new NamedIntent("prompt action " + action.title, action.actionIntent));
        }
        KeepADBUsbProfile.setNotificationEnabled(context, true);
        KeepADBUsbProfile.setProfileNotificationEnabled(context, false);
        KeepADBUsbNotification.refresh(context, true);
        others.add(new NamedIntent("usb content (card focus)",
                posted(context, KeepADBUsbNotification.NOTIFICATION_ID).contentIntent));
        KeepADBUsbProfile.setProfileNotificationEnabled(context, true);
        KeepADBUsbProfile.add(context, "ThinkPad", "192.168.1.50", "thinkpad.local", "");
        KeepADBUsbNotification.refresh(context, true);
        Notification usb = posted(context, KeepADBUsbNotification.NOTIFICATION_ID);
        others.add(new NamedIntent("usb content (profile)", usb.contentIntent));
        for (Notification.Action action : usb.actions) {
            others.add(new NamedIntent("usb action " + action.title, action.actionIntent));
        }

        assertTrue("The scenario must produce the pending intents it claims to compare",
                force.size() == 4 && others.size() >= 7);
        for (NamedIntent mine : force) {
            for (NamedIntent other : others) {
                assertFalse(mine.name + " collides with " + other.name
                        + ": same type, request code and intent identity, so one would overwrite"
                        + " the extras of the other (N6, FLAG_UPDATE_CURRENT ignores extras)",
                        mine.collidesWith(other));
            }
            for (NamedIntent other : force) {
                if (other != mine) {
                    assertFalse(mine.name + " collides with " + other.name, mine.collidesWith(other));
                }
            }
        }
    }

    // --- Helpers -------------------------------------------------------------------------------------------------------------------

    private void startForce(KeepADBForceMode.Span span) {
        assertTrue(KeepADBForceMode.activate(context, span, span.isUnlimited()));
    }

    private void startForceKeepingTheManualOff() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));
        assertTrue("A manual 'off' of Wireless Debugging is not overridden by the mode",
                KeepADB.wasLastExplicitIntentOff(context));
        assertFalse("... so the service still has no reason to run", KeepADBService.shouldRun(context));
    }

    private void assertForceCard(Notification card) {
        assertNotNull(card);
        CharSequence line = card.extras.getCharSequence(Notification.EXTRA_SUB_TEXT);
        assertNotNull("force line missing", line);
        assertTrue(line.toString(), line.toString().startsWith("Force mode on"));
        assertTrue(card.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
                .startsWith(line + "\n"));
        assertEquals("End force mode", card.actions[0].title.toString());
    }

    private void assertNoForce(Notification card) {
        assertNotNull(card);
        assertNull(card.extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
        assertFalse(card.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("Force mode"));
        if (card.actions != null) {
            for (Notification.Action action : card.actions) {
                assertFalse(action.title.toString().contains("force"));
            }
        }
    }

    /** A PendingIntent with what the platform matches on: its kind, request code and intent identity. */
    private static final class NamedIntent {
        final String name;
        final boolean activity;
        final int requestCode;
        final Intent intent;

        NamedIntent(String name, PendingIntent pendingIntent) {
            this.name = name;
            this.activity = shadowOf(pendingIntent).isActivityIntent();
            this.requestCode = shadowOf(pendingIntent).getRequestCode();
            this.intent = shadowOf(pendingIntent).getSavedIntent();
        }

        /** Intent#filterEquals ignores extras, like PendingIntent matching does. */
        boolean collidesWith(NamedIntent other) {
            return activity == other.activity && requestCode == other.requestCode
                    && intent.filterEquals(other.intent);
        }
    }
}
