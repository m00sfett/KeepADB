package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.widget.Switch;
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
import org.robolectric.shadows.ShadowToast;

/** Runtime contract for notification settings and the simplified main page (#822). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MainActivityNotificationToggleTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    @Before
    public void setUp() {
        RuntimeEnvironment.getApplication()
                .getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
    }

    @After
    public void tearDown() {
        RuntimeEnvironment.getApplication()
                .getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit();
        KeepADB.resetForTesting();
    }

    @Test
    public void notificationToggleReflectsPreferenceAndSyncsOnClick() {
        // #456: the switch uses positive framing ("persistent notification" ON = visible), which
        // is the inverse of the underlying isNotificationHidden()/setNotificationHidden() state.
        Context context = RuntimeEnvironment.getApplication();
        KeepADBPreferences.setNotificationHidden(context, false);

        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();

        Switch toggle = activity.findViewById(R.id.settings_hide_notification_toggle);
        assertNotNull(toggle);
        assertTrue(toggle.isChecked());

        toggle.performClick();
        assertFalse(toggle.isChecked());
        assertTrue(KeepADBPreferences.isNotificationHidden(context));
        assertEquals(context.getString(R.string.settings_notification_hidden_toast),
                ShadowToast.getTextOfLatestToast());

        toggle.performClick();
        assertTrue(toggle.isChecked());
        assertFalse(KeepADBPreferences.isNotificationHidden(context));
        assertEquals(context.getString(R.string.settings_notification_visible_toast),
                ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void notificationSubtextSwitchesDynamicallyBasedOnKeepAlive() {
        Context context = RuntimeEnvironment.getApplication();
        KeepADBPreferences.setKeepAliveEnabled(context, false);

        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        SettingsActivity activity = controller.get();

        TextView subtext = activity.findViewById(R.id.settings_hide_notification_subtext);
        assertNotNull(subtext);
        assertEquals(context.getString(R.string.settings_hide_notification_subtext),
                subtext.getText().toString());

        Switch toggle = activity.findViewById(R.id.settings_hide_notification_toggle);
        KeepADBPreferences.setNotificationHidden(context, true);
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        controller.pause().resume();
        assertFalse(toggle.isEnabled());
        assertTrue(toggle.isChecked());
        assertTrue(KeepADBPreferences.isNotificationHidden(context));
        assertEquals(context.getString(R.string.settings_hide_notification_subtext_keepalive),
                subtext.getText().toString());
        // Even a stale or programmatic click cannot overwrite the saved preference.
        toggle.performClick();
        assertTrue(KeepADBPreferences.isNotificationHidden(context));
        assertTrue(toggle.isChecked());

        KeepADBPreferences.setKeepAliveEnabled(context, false);
        controller.pause().resume();
        assertTrue(toggle.isEnabled());
        assertFalse(toggle.isChecked());
        assertEquals(context.getString(R.string.settings_hide_notification_subtext),
                subtext.getText().toString());
        controller.pause().stop().destroy();
    }

    @Test
    public void visiblePreferenceAlsoSurvivesKeepAliveAndActivityRecreation() {
        Context context = RuntimeEnvironment.getApplication();
        KeepADBPreferences.setNotificationHidden(context, false);
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        controller.recreate();
        Switch toggle = controller.get().findViewById(R.id.settings_hide_notification_toggle);
        assertFalse(toggle.isEnabled());
        assertTrue(toggle.isChecked());
        assertFalse(KeepADBPreferences.isNotificationHidden(context));
        KeepADBPreferences.setKeepAliveEnabled(context, false);
        controller.pause().resume();
        assertTrue(toggle.isEnabled());
        assertTrue(toggle.isChecked());
        controller.pause().stop().destroy();
    }

    @Test
    public void mainPageContainsOnlyTheTwoDebuggingControls() {
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity main = controller.get();
        assertNotNull(main.findViewById(R.id.toggle));
        assertNotNull(main.findViewById(R.id.keep_alive_toggle));
        int oldToggle = main.getResources().getIdentifier("hide_notification_toggle", "id", main.getPackageName());
        int oldSubtext = main.getResources().getIdentifier("hide_notification_subtext", "id", main.getPackageName());
        assertNull(main.findViewById(oldToggle));
        assertNull(main.findViewById(oldSubtext));
        assertNull(main.findViewById(R.id.settings_hide_notification_toggle));
        controller.pause().stop().destroy();
    }
}
