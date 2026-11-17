package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewParent;
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
import org.robolectric.annotation.Config;

/**
 * #981: while Webhook Sync is not set up or is off, the home screen keeps the webhook panel as a
 * short hint with a way to its settings card instead of hiding it. Once set up and on, the panel
 * shows the status exactly as before.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MainActivityWebhookSetupHintTest {
    private static final String URL = "http://100.111.111.21:50829/register/s20";

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    @Rule
    public final KeepADBRegisterClientResetRule registerClientResetRule =
            new KeepADBRegisterClientResetRule();

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADBRegisterClient.setHttpTransport(new KeepADBFakeHttpTransport(true));
    }

    @After
    public void tearDown() {
        KeepADBRegisterClient.resetHttpTransport();
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADB.resetForTesting();
        RuntimeEnvironment.setFontScale(1.0f);
    }

    private MainActivity launch() {
        return Robolectric.buildActivity(MainActivity.class).setup().get();
    }

    @Test
    public void unconfiguredShowsTheHintAndASetupAction() {
        MainActivity activity = launch();

        View panel = activity.findViewById(R.id.webhook_status_panel);
        TextView status = activity.findViewById(R.id.webhook_status);
        Button action = activity.findViewById(R.id.webhook_status_action);
        assertEquals("no longer hidden", View.VISIBLE, panel.getVisibility());
        assertEquals(activity.getString(R.string.webhook_status_unconfigured),
                status.getText().toString());
        assertEquals(View.VISIBLE, action.getVisibility());
        assertEquals(activity.getString(R.string.webhook_status_action_setup),
                action.getText().toString());
        assertTrue("the action belongs to the webhook panel", isInside(action, panel));
    }

    @Test
    public void savedButInvalidUrlIsTreatedAsUnconfigured() {
        KeepADBPreferences.setAppPrivacyEnabled(context, false);
        KeepADBPreferences.setRegisterWebhookUrl(context, "not-a-url");
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        assertEquals("not-a-url", KeepADBPreferences.getRegisterWebhookUrl(context));

        MainActivity activity = launch();

        View panel = activity.findViewById(R.id.webhook_status_panel);
        TextView status = activity.findViewById(R.id.webhook_status);
        Button action = activity.findViewById(R.id.webhook_status_action);
        assertEquals(View.VISIBLE, panel.getVisibility());
        assertEquals(activity.getString(R.string.webhook_status_unconfigured),
                status.getText().toString());
        assertEquals(View.VISIBLE, action.getVisibility());
        assertEquals(activity.getString(R.string.webhook_status_action_setup),
                action.getText().toString());
        assertFalse("the invalid saved URL is not shown",
                status.getText().toString().contains("not-a-url"));
    }

    @Test
    public void theActionOpensTheWebhookCardOfTheSettings() {
        MainActivity activity = launch();

        activity.findViewById(R.id.webhook_status_action).performClick();

        Intent next = shadowOf(activity).getNextStartedActivity();
        assertNotNull(next);
        assertEquals(SettingsActivity.class.getName(), next.getComponent().getClassName());
        assertTrue(next.getBooleanExtra(SettingsActivity.EXTRA_FOCUS_WEBHOOK, false));
    }

    @Test
    public void savedButOffSaysSoWithoutShowingTheUrl() {
        KeepADBPreferences.setAppPrivacyEnabled(context, false);
        KeepADBPreferences.setRegisterWebhookUrl(context, URL);
        KeepADBPreferences.setRegisterWebhookEnabled(context, false);

        MainActivity activity = launch();

        TextView status = activity.findViewById(R.id.webhook_status);
        Button action = activity.findViewById(R.id.webhook_status_action);
        assertEquals(activity.getString(R.string.webhook_status_disabled),
                status.getText().toString());
        assertFalse(status.getText().toString().contains("100.111"));
        assertEquals(activity.getString(R.string.webhook_status_action_open),
                action.getText().toString());
        assertEquals(View.VISIBLE, action.getVisibility());

        action.performClick();
        Intent next = shadowOf(activity).getNextStartedActivity();
        assertTrue(next.getBooleanExtra(SettingsActivity.EXTRA_FOCUS_WEBHOOK, false));
    }

    @Test
    public void setUpAndOnKeepsTheStatusAndHidesTheAction() {
        KeepADBPreferences.setAppPrivacyEnabled(context, false);
        KeepADBPreferences.setRegisterWebhookUrl(context, URL);
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);

        MainActivity activity = launch();

        TextView status = activity.findViewById(R.id.webhook_status);
        assertEquals(View.VISIBLE, activity.findViewById(R.id.webhook_status_panel).getVisibility());
        assertTrue(status.getText().toString().contains(URL));
        assertEquals(View.GONE, activity.findViewById(R.id.webhook_status_action).getVisibility());
    }

    @Test
    public void theActionIsALargeEnoughTouchTargetWithItsOwnLabel() {
        Button action = launch().findViewById(R.id.webhook_status_action);
        float density = action.getResources().getDisplayMetrics().density;
        assertTrue(action.getMinHeight() >= Math.round(48 * density));
        assertTrue("TalkBack reads the visible label", action.getText().length() > 0);
        assertTrue(action.isClickable());
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp")
    public void narrowScreenAt200PercentFontFits() {
        RuntimeEnvironment.setFontScale(2.0f);
        OnboardingLayoutAssertions.assertFits(launch(), 320, "home 320dp/200%");
    }

    @Test
    @Config(qualifiers = "w600dp-h400dp")
    public void twoColumnLayoutHasTheHint() {
        assertHintIn(launch());
    }

    @Test
    @Config(qualifiers = "w840dp-h600dp")
    public void wideLayoutHasTheHint() {
        assertHintIn(launch());
    }

    @Test
    @Config(qualifiers = "sw600dp")
    public void tabletLayoutHasTheHint() {
        assertHintIn(launch());
    }

    @Test
    @Config(qualifiers = "sw600dp-w840dp-h900dp")
    public void wideTabletLayoutHasTheHint() {
        assertHintIn(launch());
    }

    @Test
    @Config(qualifiers = "sw840dp")
    public void largeTabletLayoutHasTheHint() {
        assertHintIn(launch());
    }

    private static void assertHintIn(MainActivity activity) {
        View panel = activity.findViewById(R.id.webhook_status_panel);
        Button action = activity.findViewById(R.id.webhook_status_action);
        assertNotNull(action);
        assertEquals(View.VISIBLE, panel.getVisibility());
        assertEquals(View.VISIBLE, action.getVisibility());
        assertTrue(isInside(action, panel));
    }

    private static boolean isInside(View view, View ancestor) {
        for (ViewParent p = view.getParent(); p != null; p = p.getParent()) {
            if (p == ancestor) return true;
        }
        return false;
    }
}
