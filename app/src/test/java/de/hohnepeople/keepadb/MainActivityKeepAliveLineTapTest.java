package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.view.View;
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

/**
 * #801 (UX concept 5.3, point 1): the Keep-Alive line names the protection level, and a tap on it
 * opens the settings at the network card, where the level is shown. While the force mode is on the
 * line names no level and is no tap target, as before.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MainActivityKeepAliveLineTapTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = RuntimeEnvironment.getApplication();

    @Before
    public void setUp() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADB.resetForTesting(context);
        KeepADBForceMode.resetForTesting();
        KeepADBForceMode.setClockForTesting(new KeepADBForceTestSupport.TestClock());
        KeepADBPreferences.setAppLanguage(context, "en");
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS,
                android.Manifest.permission.POST_NOTIFICATIONS,
                android.Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        shadowOf((PowerManager) context.getSystemService(Context.POWER_SERVICE))
                .setIgnoringBatteryOptimizations(context.getPackageName(), true);
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(true));
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> false);
    }

    @After
    public void tearDown() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADBForceMode.resetForTesting();
        KeepADBEndpointCoordinator.resetForTesting();
        KeepADB.resetForTesting();
    }

    @Test
    public void tappingTheLineThatNamesTheLevelOpensTheSettingsAtTheNetworkCard() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        TextView line = activity.findViewById(R.id.keep_alive_subtext);
        assertTrue(line.getText().toString(),
                line.getText().toString().contains(
                        context.getString(R.string.networks_level, KeepADBForceNotice.levelLabel(context))));
        assertTrue(line.isClickable());

        line.performClick();

        Intent opened = shadowOf(activity).getNextStartedActivity();
        assertNotNull("the tap must start something", opened);
        assertEquals(ProtectionActivity.class.getName(), opened.getComponent().getClassName());
        assertTrue(opened.getBooleanExtra(ProtectionActivity.EXTRA_FOCUS_LEVEL, false));
        assertNull("the tap does not toggle Keep-Alive", shadowOf((Application) context).getNextStartedService());
    }

    /** The other side: in the force mode the line names no level and leads nowhere. */
    @Test
    public void inTheForceModeTheLineIsNoTapTargetAndComesBackWhenForceEnds() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        TextView line = activity.findViewById(R.id.keep_alive_subtext);
        assertEquals(context.getString(R.string.keep_alive_subtext), line.getText().toString());
        assertFalse(line.isClickable());

        line.performClick();
        assertNull(shadowOf(activity).getNextStartedActivity());

        assertTrue(KeepADBForceMode.endNow(context));
        controller.pause().resume();
        assertTrue("the line is a tap target again once force ended", line.isClickable());
        line.performClick();
        Intent opened = shadowOf(activity).getNextStartedActivity();
        assertNotNull(opened);
        assertTrue(opened.getBooleanExtra(ProtectionActivity.EXTRA_FOCUS_LEVEL, false));
    }

    /** The line is a real tap target: 48 dp high, a chevron says it leads on, keyboard reachable. */
    @Test
    public void theTappableLineIsAtLeast48dpHighShowsAChevronAndTakesFocus() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        TextView line = activity.findViewById(R.id.keep_alive_subtext);
        float density = activity.getResources().getDisplayMetrics().density;

        assertTrue("min height " + line.getMinHeight(), line.getMinHeight() >= 48 * density);
        assertNotNull("a chevron at the end", line.getCompoundDrawablesRelative()[2]);
        assertTrue(line.isFocusable());
        assertNotNull("press feedback", line.getBackground());
    }

    /** Counter-check: the force mode removes tap target, chevron and the extra height again. */
    @Test
    public void inTheForceModeThereIsNoChevronNoFocusAndNoExtraHeight() {
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        TextView line = activity.findViewById(R.id.keep_alive_subtext);

        assertNull(line.getCompoundDrawablesRelative()[2]);
        assertFalse(line.isFocusable());
        assertEquals(0, line.getMinHeight());
        assertNull(line.getBackground());
    }

    /** #805: a screen reader gets the button role on the tappable line, and only on it. */
    @Test
    public void theTappableLineIsExposedToScreenReadersAsAButtonOnlyOutsideTheForceMode() {
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        TextView line = controller.get().findViewById(R.id.keep_alive_subtext);
        assertEquals(android.widget.Button.class.getName(),
                line.createAccessibilityNodeInfo().getClassName().toString());
        assertTrue(line.createAccessibilityNodeInfo().isClickable());

        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));
        controller.pause().resume();
        assertEquals("control: plain text while force is on", TextView.class.getName(),
                line.createAccessibilityNodeInfo().getClassName().toString());
    }

    @Test
    public void theProtectionPageOpensAtTheLevelOnlyWhenAsked() {
        ProtectionActivity plain =
                Robolectric.buildActivity(ProtectionActivity.class).setup().get();
        assertEquals("control: the level section is on the page either way", View.VISIBLE,
                plain.findViewById(R.id.protection_level_panel).getVisibility());
        assertFalse(plain.getIntent().hasExtra(ProtectionActivity.EXTRA_FOCUS_LEVEL));

        ProtectionActivity focused = Robolectric.buildActivity(ProtectionActivity.class,
                ProtectionActivity.levelIntent(context)).setup().get();
        assertEquals(View.VISIBLE, focused.findViewById(R.id.protection_level_panel).getVisibility());
        assertFalse("the request is used up",
                focused.getIntent().hasExtra(ProtectionActivity.EXTRA_FOCUS_LEVEL));
    }
}
