package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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

/** Unit tests for the hide notification toggle on MainActivity (#443). */
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
        Context context = RuntimeEnvironment.getApplication();
        KeepADBPreferences.setNotificationHidden(context, false);

        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();

        Switch toggle = activity.findViewById(R.id.hide_notification_toggle);
        assertNotNull(toggle);
        assertFalse(toggle.isChecked());

        toggle.performClick();
        assertTrue(toggle.isChecked());
        assertTrue(KeepADBPreferences.isNotificationHidden(context));
        assertEquals(context.getString(R.string.settings_notification_hidden_toast),
                ShadowToast.getTextOfLatestToast());

        toggle.performClick();
        assertFalse(toggle.isChecked());
        assertFalse(KeepADBPreferences.isNotificationHidden(context));
        assertEquals(context.getString(R.string.settings_notification_visible_toast),
                ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void notificationSubtextSwitchesDynamicallyBasedOnKeepAlive() {
        Context context = RuntimeEnvironment.getApplication();
        KeepADBPreferences.setKeepAliveEnabled(context, false);

        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();

        TextView subtext = activity.findViewById(R.id.hide_notification_subtext);
        assertNotNull(subtext);
        assertEquals(context.getString(R.string.settings_hide_notification_subtext),
                subtext.getText().toString());

        Switch keepAliveToggle = activity.findViewById(R.id.keep_alive_toggle);
        keepAliveToggle.performClick();

        assertEquals(context.getString(R.string.settings_hide_notification_subtext_keepalive),
                subtext.getText().toString());

        keepAliveToggle.performClick();
        assertEquals(context.getString(R.string.settings_hide_notification_subtext),
                subtext.getText().toString());
    }
}
