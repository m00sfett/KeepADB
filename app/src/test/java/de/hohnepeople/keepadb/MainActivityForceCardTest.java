package de.hohnepeople.keepadb;

import static de.hohnepeople.keepadb.KeepADBForceTestSupport.HOUR;
import static de.hohnepeople.keepadb.KeepADBForceTestSupport.posted;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

/**
 * #763: the permanent warning card on the home screen. It is there exactly while the force mode is
 * on (read from the pure state, so it is gone at the deadline before the expiry transition ran),
 * cannot be dismissed, and leads in one tap to ending the mode or to its row in Settings.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MainActivityForceCardTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = RuntimeEnvironment.getApplication();
    private KeepADBForceTestSupport.TestClock clock;

    @Before
    public void setUp() {
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.POST_NOTIFICATIONS,
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
        resetState();
        KeepADBPreferences.setAppLanguage(context, "en");
        clock = new KeepADBForceTestSupport.TestClock();
        KeepADBForceMode.setClockForTesting(clock);
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));
    }

    @After
    public void tearDown() {
        resetState();
    }

    private void resetState() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADBForceMode.resetForTesting();
        KeepADBEndpointCoordinator.resetForTesting();
        KeepADBEndpoint.resetForTesting();
        KeepADB.resetForTesting();
        KeepADBRegisterClient.resetForTesting();
        context.getSystemService(NotificationManager.class).cancelAll();
    }

    @Test
    public void theCardIsHiddenWithoutTheMode() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();

        assertEquals(View.GONE, activity.findViewById(R.id.force_warning_panel).getVisibility());
    }

    @Test
    public void theCardShowsTheEndOfALimitedModeAndNoEndTimeOtherwise() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOURS_24, false));
        MainActivity limited = Robolectric.buildActivity(MainActivity.class).setup().get();
        View panel = limited.findViewById(R.id.force_warning_panel);
        assertEquals(View.VISIBLE, panel.getVisibility());
        String text = ((TextView) limited.findViewById(R.id.force_warning_text)).getText().toString();
        assertTrue(text, text.startsWith("Wireless debugging is turned back on in every network. Active until "));
        assertFalse(text, text.contains("No end time"));

        resetState();
        KeepADBPreferences.setAppLanguage(context, "en");
        KeepADBForceMode.setClockForTesting(clock);
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.UNLIMITED, true));
        MainActivity unlimited = Robolectric.buildActivity(MainActivity.class).setup().get();
        assertEquals(View.VISIBLE, unlimited.findViewById(R.id.force_warning_panel).getVisibility());
        assertEquals("Wireless debugging is turned back on in every network. No end time.",
                ((TextView) unlimited.findViewById(R.id.force_warning_text)).getText().toString());
    }

    @Test
    public void theCardCannotBeDismissedItOffersExactlyEndAndSettings() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();

        Button end = activity.findViewById(R.id.btn_force_end);
        Button settings = activity.findViewById(R.id.btn_force_settings);
        assertEquals("End force mode", end.getText().toString());
        assertEquals("Settings", settings.getText().toString());
        int buttons = 0;
        android.view.ViewGroup panel = activity.findViewById(R.id.force_warning_panel);
        buttons += countButtons(panel);
        assertEquals("No dismiss control: only End and Settings", 2, buttons);
    }

    @Test
    public void theCardAppearsAndDisappearsWithTheModeWhileTheScreenIsOpen() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        assertEquals(View.GONE, activity.findViewById(R.id.force_warning_panel).getVisibility());

        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));
        ShadowLooper.idleMainLooper();
        assertEquals("Started elsewhere (dialog, assistant): the open screen follows", View.VISIBLE,
                activity.findViewById(R.id.force_warning_panel).getVisibility());

        clock.advance(HOUR);
        assertTrue(KeepADBForceMode.finishIfExpired(context));
        ShadowLooper.idleMainLooper();

        assertEquals("Expired (heartbeat, alarm): gone", View.GONE,
                activity.findViewById(R.id.force_warning_panel).getVisibility());
    }

    @Test
    public void theScreenListensForTheModeOnlyWhileItIsResumed() {
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        assertTrue("Resumed: the screen redraws when the mode starts or ends",
                KeepADBForceMode.hasStateListenerForTesting());

        controller.pause();

        assertFalse("Paused: nothing keeps the hidden screen alive", KeepADBForceMode.hasStateListenerForTesting());
        controller.resume();
        assertTrue(KeepADBForceMode.hasStateListenerForTesting());
        controller.pause().stop().destroy();
        assertFalse(KeepADBForceMode.hasStateListenerForTesting());
    }

    @Test
    public void reopeningTheScreenAfterTheDeadlineShowsNoCardAndDeliversTheNotice() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));
        clock.advance(HOUR);

        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();

        assertEquals(View.GONE, activity.findViewById(R.id.force_warning_panel).getVisibility());
        assertNotNull(posted(context, KeepADBForceNotice.NOTIFICATION_ID));
    }

    @Test
    public void theCardIsGoneAtTheDeadlineEvenBeforeAnyTransitionRan() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        clock.advance(HOUR - 1);
        controller.pause().resume();
        assertEquals(View.VISIBLE, controller.get().findViewById(R.id.force_warning_panel).getVisibility());

        // Render without the transition: the pure state alone decides what the card shows.
        clock.advance(1);
        MainActivity second = Robolectric.buildActivity(MainActivity.class).create().get();

        assertEquals(View.GONE, second.findViewById(R.id.force_warning_panel).getVisibility());
    }

    @Test
    public void endForceModeEndsItHidesTheCardAndStatesTheRestoredLevel() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.DAYS_30, false));
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();

        activity.findViewById(R.id.btn_force_end).performClick();
        ShadowLooper.idleMainLooper();

        assertFalse(KeepADBForceMode.isActive(context));
        assertEquals(View.GONE, activity.findViewById(R.id.force_warning_panel).getVisibility());
        assertEquals("Force mode ended. Protection level back to: Maximum security.",
                ShadowToast.getTextOfLatestToast());
        assertNull("Ended by hand: no expiry notice", posted(context, KeepADBForceNotice.NOTIFICATION_ID));
    }

    @Test
    public void theSettingsButtonOpensTheForceRowOfTheSettings() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();

        activity.findViewById(R.id.btn_force_settings).performClick();

        Intent started = shadowOf(activity).getNextStartedActivity();
        assertEquals(SettingsActivity.class.getName(), started.getComponent().getClassName());
        assertTrue(started.getBooleanExtra(SettingsActivity.EXTRA_FOCUS_FORCE, false));
        assertTrue("Tapping Settings does not end anything", KeepADBForceMode.isActive(context));
    }

    private static int countButtons(android.view.ViewGroup group) {
        int count = 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof Button) count++;
            if (child instanceof android.view.ViewGroup) count += countButtons((android.view.ViewGroup) child);
        }
        return count;
    }
}
