package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.Application;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
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

import static org.robolectric.Shadows.shadowOf;

/**
 * #959: the help texts of the Protection page are one line each, with "More" showing the full
 * text that was always there: {@code untrusted_shutdown_hint}, {@code security_check_hint} and
 * the background-detection status. The full texts are reused unchanged.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ProtectionHelpTextsTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = RuntimeEnvironment.getApplication();
    private ActivityController<ProtectionActivity> controller;

    @Before
    public void setUp() {
        prefs().edit().clear().commit();
        shadowOf((Application) context).denyPermissions(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        controller = Robolectric.buildActivity(ProtectionActivity.class).setup();
        ShadowLooper.idleMainLooper();
    }

    @After
    public void tearDown() {
        controller.pause().stop().destroy();
        prefs().edit().clear().commit();
        KeepADB.resetForTesting();
    }

    @Test
    public void theShutdownHintIsOneLineWithMoreAndTheFullTextBehindIt() {
        assertHint(R.id.protection_untrusted_shutdown_hint, R.string.untrusted_shutdown_hint_short,
                R.string.untrusted_shutdown_hint);
    }

    @Test
    public void theSecurityCheckHintIsOneLineWithMoreAndTheFullTextBehindIt() {
        assertHint(R.id.settings_security_check_hint, R.string.security_check_hint_short,
                R.string.security_check_hint);
    }

    @Test
    public void theBackgroundDetectionStatusIsOneLineWithMoreAndTheFullStatusAndReadingBehindIt() {
        ProtectionActivity page = controller.get();
        TextView status = page.findViewById(R.id.settings_background_location_status);
        View reading = page.findViewById(R.id.network_detection_now);

        assertEquals("Collapsed: the short state and More",
                context.getString(R.string.protection_background_short_missing) + "  "
                        + context.getString(R.string.hint_more), status.getText().toString());
        assertEquals("The current reading belongs to the full wording", View.GONE,
                reading.getVisibility());

        status.performClick();

        assertEquals(context.getString(R.string.background_location_status_missing)
                        + "  " + context.getString(R.string.hint_less),
                status.getText().toString());
        assertEquals(View.VISIBLE, reading.getVisibility());

        status.performClick();
        assertEquals(View.GONE, reading.getVisibility());
    }

    @Test
    public void theHintsStateIsAnnouncedAsCollapsedAndExpanded() {
        ProtectionActivity page = controller.get();
        View hint = page.findViewById(R.id.protection_untrusted_shutdown_hint);
        assertEquals(context.getString(R.string.card_state_collapsed),
                hint.getStateDescription().toString());

        hint.performClick();

        assertEquals(context.getString(R.string.card_state_expanded),
                hint.getStateDescription().toString());
        assertTrue(hint.isClickable());
        assertTrue(hint.isFocusable());
    }

    @Test
    public void theExpansionSurvivesARefreshAndTheFullTextsAreNotShownBeforeTheTap() {
        ProtectionActivity page = controller.get();
        String shown = shownText(page.getWindow().getDecorView());
        for (int full : new int[] {R.string.untrusted_shutdown_hint, R.string.security_check_hint,
                R.string.background_location_status_missing}) {
            assertFalse("Collapsed: " + context.getString(full), shown.contains(context.getString(full)));
        }

        TextView hint = page.findViewById(R.id.protection_untrusted_shutdown_hint);
        hint.performClick();
        page.refresh();

        assertTrue("A refresh (a Wi-Fi change) keeps the text open",
                hint.getText().toString().startsWith(
                        context.getString(R.string.untrusted_shutdown_hint)));
    }

    @Test
    public void everyShortLineDiffersFromItsFullText() {
        assertNotEquals(context.getString(R.string.untrusted_shutdown_hint_short),
                context.getString(R.string.untrusted_shutdown_hint));
        assertNotEquals(context.getString(R.string.security_check_hint_short),
                context.getString(R.string.security_check_hint));
        assertTrue(context.getString(R.string.untrusted_shutdown_hint_short).length()
                < context.getString(R.string.untrusted_shutdown_hint).length());
        assertTrue(context.getString(R.string.security_check_hint_short).length()
                < context.getString(R.string.security_check_hint).length());
    }

    private void assertHint(int viewId, int shortRes, int fullRes) {
        TextView hint = controller.get().findViewById(viewId);
        String more = context.getString(R.string.hint_more);
        String less = context.getString(R.string.hint_less);
        assertEquals(context.getString(shortRes) + "  " + more, hint.getText().toString());

        hint.performClick();
        assertEquals("The full text is the unchanged text", context.getString(fullRes) + "  " + less,
                hint.getText().toString());

        hint.performClick();
        assertEquals(context.getString(shortRes) + "  " + more, hint.getText().toString());
    }

    private static String shownText(View root) {
        StringBuilder out = new StringBuilder();
        collect(root, out);
        return out.toString();
    }

    private static void collect(View view, StringBuilder out) {
        if (view instanceof TextView && view.isShown()) {
            out.append(((TextView) view).getText()).append('\n');
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), out);
        }
    }

    private android.content.SharedPreferences prefs() {
        return context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE);
    }
}
