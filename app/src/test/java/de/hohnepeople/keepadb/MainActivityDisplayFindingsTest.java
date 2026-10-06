package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.os.PowerManager;
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

import java.lang.reflect.Field;

/**
 * #791 (F7, F8, F9): the home screen's warning texts read as sentences, the Keep-Alive line names
 * the protection level, and a missing system permission clears the endpoint instead of leaving an
 * address found earlier on the screen.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MainActivityDisplayFindingsTest {

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

    // ---- F7 ---------------------------------------------------------------------------------------

    @Test
    public void theWarningTextsAreSentencesWithoutABareCount() {
        for (int res : new int[] {R.string.onboarding_intro_less_secure,
                R.string.home_warning_limited_text}) {
            String text = context.getString(res);
            assertFalse("no count slot: " + text, text.contains("%"));
            assertTrue("a sentence, not a label with a number: " + text, text.endsWith("."));
            assertFalse("no colon label: " + text, text.contains(":"));
        }
        // The real card shows exactly that text, and no digit made it in.
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        shadowOf((PowerManager) context.getSystemService(Context.POWER_SERVICE))
                .setIgnoringBatteryOptimizations(context.getPackageName(), false);
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        String shown = ((TextView) activity.findViewById(R.id.warning_limited)
                .findViewById(R.id.home_warning_text)).getText().toString();
        assertEquals(context.getString(R.string.home_warning_limited_text), shown);
        assertFalse(shown, shown.matches(".*\\d.*"));
    }

    // ---- F8 ---------------------------------------------------------------------------------------

    @Test
    public void theKeepAliveLineNamesTheProtectionLevelAndFollowsIt() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBTrustedNetwork.setTrustByNameEnabled(context, false);
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        String line = subtext(activity);
        assertTrue(line, line.contains(context.getString(R.string.keep_alive_subtext)));
        assertTrue(line, line.contains(context.getString(R.string.networks_level,
                context.getString(R.string.force_level_maximum))));

        KeepADBTrustedNetwork.setTrustByNameEnabled(context, true);
        MainActivity other = Robolectric.buildActivity(MainActivity.class).setup().get();
        String balanced = subtext(other);
        assertTrue(balanced, balanced.contains(context.getString(R.string.force_level_balanced)));
        assertFalse(balanced, balanced.contains(context.getString(R.string.force_level_maximum)));
    }

    @Test
    public void duringTheForceModeTheKeepAliveLineDoesNotClaimAProtectionLevel() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBTrustedNetwork.setTrustByNameEnabled(context, false);
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        assertEquals(context.getString(R.string.keep_alive_subtext), subtext(activity));
    }

    // ---- F9 ---------------------------------------------------------------------------------------

    @Test
    public void anEndpointFoundWithoutTheSystemPermissionIsNotShown() throws Exception {
        // The real case: discovery still reports an address (Wireless Debugging is on in the
        // system) while this app has no permission to manage it.
        shadowOf((Application) context).denyPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        TextView endpoint = controller.get().findViewById(R.id.endpoint);

        endpointListener().onEndpoint("192.0.2.10", 10001);

        String shown = endpoint.getText().toString();
        assertFalse("the address must not be shown: " + shown, shown.contains("10001"));
        assertEquals(context.getString(R.string.endpoint_unavailable), shown);

        // The other side: with the permission the same report is shown.
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
        endpointListener().onEndpoint("192.0.2.10", 10002);
        assertTrue(endpoint.getText().toString(), endpoint.getText().toString().contains("10002"));
    }

    private static String subtext(MainActivity activity) {
        return ((TextView) activity.findViewById(R.id.keep_alive_subtext)).getText().toString();
    }

    private static KeepADBEndpointCoordinator.EndpointListener endpointListener() throws Exception {
        Field field = KeepADBEndpointCoordinator.class.getDeclaredField("endpointListener");
        field.setAccessible(true);
        return (KeepADBEndpointCoordinator.EndpointListener) field.get(null);
    }
}
