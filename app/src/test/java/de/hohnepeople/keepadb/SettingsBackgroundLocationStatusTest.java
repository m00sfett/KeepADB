package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;

/**
 * #645: the background-location status row in the settings shows three distinct states (active,
 * restricted, neutral). Each state has its own text and colour, the setup button stays reachable,
 * and every colour keeps WCAG AA contrast (4.5:1) on the panel background.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SettingsBackgroundLocationStatusTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        shadowOf((Application) context).denyPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
    }

    @After
    public void tearDown() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADB.resetForTesting();
    }

    private SettingsActivity open() {
        return Robolectric.buildActivity(SettingsActivity.class).setup().get();
    }

    private void assertState(SettingsActivity settings, int textRes, int colorRes) {
        TextView status = settings.findViewById(R.id.settings_background_location_status);
        assertEquals(context.getString(textRes), status.getText().toString());
        assertEquals(context.getColor(colorRes), status.getCurrentTextColor());
        Button button = settings.findViewById(R.id.settings_background_location_button);
        assertEquals(View.VISIBLE, button.getVisibility());
        assertTrue(button.isEnabled());
    }

    @Test
    public void activeStateIsGreen() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        shadowOf((Application) context).grantPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        assertState(open(), R.string.background_location_status_granted, R.color.status_ok_green);
    }

    @Test
    public void restrictedStateIsAmberAndKeepsButton() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        assertState(open(), R.string.background_location_status_missing, R.color.text_yellow);
    }

    @Test
    public void neutralStateIsMuted() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        assertState(open(), R.string.background_location_status_missing_inactive, R.color.night_muted);
    }

    @Test
    public void colourNeverIsTheOnlyCarrierOfTheState() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        String restricted = ((TextView) open().findViewById(R.id.settings_background_location_status))
                .getText().toString();
        shadowOf((Application) context).grantPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        String active = ((TextView) open().findViewById(R.id.settings_background_location_status))
                .getText().toString();
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        shadowOf((Application) context).denyPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        String neutral = ((TextView) open().findViewById(R.id.settings_background_location_status))
                .getText().toString();
        assertNotEquals(active, restricted);
        assertNotEquals(restricted, neutral);
        assertNotEquals(active, neutral);
    }

    @Test
    public void stateChangeOnResumeUpdatesTextAndColourOfTheLiveRegion() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        org.robolectric.android.controller.ActivityController<SettingsActivity> controller =
                Robolectric.buildActivity(SettingsActivity.class).setup();
        TextView status = controller.get().findViewById(R.id.settings_background_location_status);
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, status.getAccessibilityLiveRegion());
        assertEquals(context.getColor(R.color.text_yellow), status.getCurrentTextColor());

        controller.pause();
        shadowOf((Application) context).grantPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        controller.resume();
        assertEquals(context.getString(R.string.background_location_status_granted),
                status.getText().toString());
        assertEquals(context.getColor(R.color.status_ok_green), status.getCurrentTextColor());
    }

    @Test
    public void everyStateColourHasAaContrastOnPanel() {
        int panel = context.getColor(R.color.panel);
        int[] colors = {R.color.status_ok_green, R.color.text_yellow, R.color.night_muted};
        for (int color : colors) {
            double ratio = contrast(context.getColor(color), panel);
            assertTrue("contrast " + ratio, ratio >= 4.5);
        }
    }

    @Test
    public void mainCardTitleIsAHeadingAndDismissHasALabel() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        MainActivity main = Robolectric.buildActivity(MainActivity.class).setup().get();
        View panel = main.findViewById(R.id.background_location_panel);
        ArrayList<View> found = new ArrayList<>();
        panel.findViewsWithText(found, context.getString(R.string.background_location_panel_title),
                View.FIND_VIEWS_WITH_TEXT);
        assertEquals(1, found.size());
        assertTrue(found.get(0).isAccessibilityHeading());
        assertEquals(context.getString(R.string.action_dismiss),
                main.findViewById(R.id.btn_dismiss_background_location_panel)
                        .getContentDescription().toString());
    }

    private static double contrast(int a, int b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(int argb) {
        double[] c = {(argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF};
        for (int i = 0; i < 3; i++) {
            double v = c[i] / 255.0;
            c[i] = v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }
}
