package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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

/**
 * #959: the protection level is chosen on the Protection page, and it is the only control of
 * by-name trust: the "Also trust by Wi-Fi name" switch exists nowhere. The tests pin the one
 * thing that must not drift, the level the page shows against the stored values behind it, for the
 * two presets and the two previous settings, and that opening the page writes nothing.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ProtectionLevelSectionTest {

    private static final int LEGACY_ALL = 0;
    private static final int LEGACY_NAMES = 1;
    private static final int MAXIMUM = 2;
    private static final int BALANCED = 3;

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Context context = RuntimeEnvironment.getApplication();
    private final List<ActivityController<ProtectionActivity>> controllers = new ArrayList<>();

    @Before
    public void setUp() {
        shadowOf((android.app.Application) context).grantPermissions(
                android.Manifest.permission.POST_NOTIFICATIONS,
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
        prefs().edit().clear().commit();
        KeepADBForceMode.resetForTesting();
        KeepADBForceMode.setClockForTesting(new KeepADBForceTestSupport.TestClock());
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));
    }

    @After
    public void tearDown() {
        for (ActivityController<ProtectionActivity> controller : controllers) {
            controller.pause().stop().destroy();
        }
        controllers.clear();
        prefs().edit().clear().commit();
        KeepADBForceMode.resetForTesting();
        KeepADB.resetForTesting();
    }

    // --- what is shown ---------------------------------------------------------------------------

    @Test
    public void aNewInstallationShowsMaximumSecurityAndNoPreviousSetting() {
        ProtectionActivity page = open();

        assertEquals(KeepADBTrustedNetwork.ProtectionLevel.MAXIMUM_SECURITY,
                KeepADBTrustedNetwork.getProtectionLevel(context));
        assertShown(page, MAXIMUM);
        assertEquals(View.GONE, card(page, LEGACY_ALL).getVisibility());
        assertEquals(View.GONE, card(page, LEGACY_NAMES).getVisibility());
        assertEquals(View.VISIBLE, card(page, MAXIMUM).getVisibility());
        assertEquals(View.VISIBLE, card(page, BALANCED).getVisibility());
        assertEquals(context.getString(R.string.force_level_maximum), title(page, MAXIMUM));
        assertEquals(context.getString(R.string.force_level_balanced), title(page, BALANCED));
    }

    /**
     * The selection is derived from the stored policy: every combination of the values behind
     * the level reads as the level {@link KeepADBTrustedNetwork#getProtectionLevel} names, the
     * page selects exactly that card, and a previous setting is shown for as long as it is stored.
     */
    @Test
    public void thePageAlwaysShowsTheLevelTheStoredValuesNameForAllFourLevels() {
        int checked = 0;
        for (boolean allowlist : new boolean[] {true, false}) {
            for (boolean byName : new boolean[] {false, true}) {
                for (boolean nameList : new boolean[] {false, true}) {
                    prefs().edit().clear().commit();
                    store(allowlist, byName, nameList);
                    Map<String, ?> before = everyPreference();

                    ProtectionActivity page = open();

                    KeepADBTrustedNetwork.ProtectionLevel level =
                            KeepADBTrustedNetwork.getProtectionLevel(context);
                    int expected = level == KeepADBTrustedNetwork.ProtectionLevel.LEGACY_ALL_WIFI
                            ? LEGACY_ALL
                            : level == KeepADBTrustedNetwork.ProtectionLevel.LEGACY_NAME_LIST
                            ? LEGACY_NAMES
                            : level == KeepADBTrustedNetwork.ProtectionLevel.BALANCED
                            ? BALANCED : MAXIMUM;
                    String label = "allowlist=" + allowlist + " byName=" + byName
                            + " nameList=" + nameList + " -> " + level;
                    assertShown(page, expected);
                    assertEquals(label + ": the previous all-Wi-Fi setting shows while stored",
                            expected == LEGACY_ALL,
                            card(page, LEGACY_ALL).getVisibility() == View.VISIBLE);
                    assertEquals(label + ": the previous name list shows while stored",
                            expected == LEGACY_NAMES,
                            card(page, LEGACY_NAMES).getVisibility() == View.VISIBLE);
                    assertEquals(label + ": opening the page writes nothing", before,
                            everyPreference());
                    checked++;
                    closeAll();
                }
            }
        }
        assertEquals(8, checked);
    }

    // --- choosing a preset ---------------------------------------------------------------------------

    @Test
    public void choosingBalancedStoresTheByNameValueAndMaximumTakesItBack() {
        ProtectionActivity page = open();
        assertFalse(KeepADBTrustedNetwork.isTrustByNameEnabled(context));

        card(page, BALANCED).performClick();

        assertTrue("Balanced is exactly by-name trust on", KeepADBTrustedNetwork.isTrustByNameEnabled(context));
        assertEquals(KeepADBTrustedNetwork.ProtectionLevel.BALANCED,
                KeepADBTrustedNetwork.getProtectionLevel(context));
        assertTrue("The policy stays the allowlist", KeepADBTrustedNetwork.isAllowlistMode(context));
        assertFalse(KeepADBTrustedNetwork.isSsidMatchingEnabled(context));
        assertShown(page, BALANCED);
        assertEquals(context.getString(R.string.force_level_balanced), KeepADBForceNotice.levelLabel(page));

        card(page, MAXIMUM).performClick();

        assertFalse(KeepADBTrustedNetwork.isTrustByNameEnabled(context));
        assertEquals(KeepADBTrustedNetwork.ProtectionLevel.MAXIMUM_SECURITY,
                KeepADBTrustedNetwork.getProtectionLevel(context));
        assertShown(page, MAXIMUM);
    }

    @Test
    public void theStoredKeyAndMeaningOfTheByNameValueAreUnchanged() {
        ProtectionActivity page = open();

        card(page, BALANCED).performClick();

        assertEquals("trust_by_name", KeepADBTrustedNetwork.KEY_TRUST_BY_NAME);
        assertTrue(prefs().getBoolean("trust_by_name", false));
        card(page, MAXIMUM).performClick();
        assertTrue("The key stays stored, now false", prefs().contains("trust_by_name"));
        assertFalse(prefs().getBoolean("trust_by_name", true));
    }

    /** Choosing a level that is already stored writes nothing. */
    @Test
    public void choosingTheStoredLevelWritesNothing() {
        ProtectionActivity page = open();
        Map<String, ?> before = everyPreference();

        card(page, MAXIMUM).performClick();

        assertEquals(before, everyPreference());
    }

    @Test
    public void leavingTheFormerAllWifiSettingByAPresetGoesBackToTheAllowlistAndKeepsTheLists() {
        for (int preset : new int[] {MAXIMUM, BALANCED}) {
            prefs().edit().clear().commit();
            KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:01", "Home");
            KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
            ProtectionActivity page = open();
            assertShown(page, LEGACY_ALL);
            assertEquals(View.VISIBLE, card(page, LEGACY_ALL).getVisibility());
            assertEquals(context.getString(R.string.force_level_legacy_all), title(page, LEGACY_ALL));

            card(page, preset).performClick();

            assertTrue(KeepADBTrustedNetwork.isAllowlistMode(context));
            assertEquals(preset == BALANCED, KeepADBTrustedNetwork.isTrustByNameEnabled(context));
            assertEquals(preset == BALANCED ? KeepADBTrustedNetwork.ProtectionLevel.BALANCED
                            : KeepADBTrustedNetwork.ProtectionLevel.MAXIMUM_SECURITY,
                    KeepADBTrustedNetwork.getProtectionLevel(context));
            assertEquals("The trusted access points stay stored", 1,
                    KeepADBTrustedNetwork.getEntries(context).size());
            assertEquals("The previous setting is gone from the page once left", View.GONE,
                    card(page, LEGACY_ALL).getVisibility());
            assertShown(page, preset);
            closeAll();
        }
    }

    @Test
    public void leavingTheFormerNameListByAPresetSwitchesItOffAndKeepsItsEntries() {
        for (int preset : new int[] {MAXIMUM, BALANCED}) {
            prefs().edit().clear().commit();
            KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
            KeepADBTrustedNetwork.addSsid(context, "Mesh");
            KeepADBTrustedNetwork.setSsidMatchingEnabled(context, true);
            ProtectionActivity page = open();
            assertShown(page, LEGACY_NAMES);
            assertEquals(context.getString(R.string.force_level_legacy_names),
                    title(page, LEGACY_NAMES));

            card(page, preset).performClick();

            assertFalse("The name list is switched off, not emptied",
                    KeepADBTrustedNetwork.isSsidMatchingEnabled(context));
            assertEquals(1, KeepADBTrustedNetwork.getSsidEntries(context).size());
            assertEquals(preset == BALANCED, KeepADBTrustedNetwork.isTrustByNameEnabled(context));
            assertEquals(preset == BALANCED ? KeepADBTrustedNetwork.ProtectionLevel.BALANCED
                            : KeepADBTrustedNetwork.ProtectionLevel.MAXIMUM_SECURITY,
                    KeepADBTrustedNetwork.getProtectionLevel(context));
            assertEquals(View.GONE, card(page, LEGACY_NAMES).getVisibility());
            closeAll();
        }
    }

    /** A previous setting is shown, not chosen: a tap on it changes nothing. */
    @Test
    public void tappingAPreviousSettingChangesNothing() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        ProtectionActivity page = open();
        Map<String, ?> before = everyPreference();

        card(page, LEGACY_ALL).performClick();

        assertEquals(before, everyPreference());
        assertShown(page, LEGACY_ALL);
    }

    /** The force mode overrides trust while it runs: choosing a level neither ends nor starts it. */
    @Test
    public void choosingALevelDuringForceModeLeavesTheModeRunningAndSaysItAppliesLater() {
        ProtectionActivity page = open();
        assertEquals(View.GONE, page.findViewById(R.id.protection_level_force_note).getVisibility());
        assertTrue(KeepADBForceMode.activate(context, KeepADBForceMode.Span.HOUR_1, false));
        controllers.get(0).pause().resume();
        assertEquals(View.VISIBLE, page.findViewById(R.id.protection_level_force_note).getVisibility());

        card(page, BALANCED).performClick();

        assertTrue("The mode keeps running", KeepADBForceMode.isActive(context));
        assertEquals(KeepADBTrustedNetwork.ProtectionLevel.BALANCED,
                KeepADBTrustedNetwork.getProtectionLevel(context));
    }

    // --- the switch is gone ------------------------------------------------------------------------------

    /**
     * Of the switches of the former card only the automatic-behavior ones are left: the two of
     * then, and the open-network rule added since (#997).
     */
    @Test
    public void theByNameSwitchExistsNowhereOnThePageAndOnlyTheAutomaticSwitchesRemain() {
        ProtectionActivity page = open();
        List<Integer> switches = new ArrayList<>();
        collectSwitches(page.getWindow().getDecorView(), switches);

        assertEquals(3, switches.size());
        assertTrue(switches.contains(R.id.settings_untrusted_shutdown_toggle));
        assertTrue(switches.contains(R.id.settings_open_network_shutdown_toggle));
        assertTrue(switches.contains(R.id.settings_security_check_toggle));
        assertEquals(0, page.getResources().getIdentifier("settings_trust_by_name_toggle", "id",
                page.getPackageName()));
        assertNull(findTextContaining(page.getWindow().getDecorView(), "Also trust by Wi-Fi name"));
    }

    @Test
    public void theWifiNetworksListHasNoSwitchEitherAndShowsTheLevelAsOneRow() {
        ActivityController<NetworkListActivity> controller = Robolectric.buildActivity(
                NetworkListActivity.class, NetworkListActivity.intent(context)).setup();
        ShadowLooper.idleMainLooper();
        try {
            List<Integer> switches = new ArrayList<>();
            collectSwitches(controller.get().getWindow().getDecorView(), switches);
            assertTrue("The list holds no switch: " + switches, switches.isEmpty());
            assertEquals(context.getString(R.string.networks_level,
                            KeepADBForceNotice.levelLabel(context)),
                    findTextContaining(controller.get().getWindow().getDecorView(),
                            context.getString(R.string.networks_level,
                                    KeepADBForceNotice.levelLabel(context))));
        } finally {
            controller.pause().stop().destroy();
        }
    }

    @Test
    public void theAssistantsCustomizeAreaHoldsNoByNameSwitchEither() {
        ActivityController<OnboardingActivity> controller = Robolectric.buildActivity(
                OnboardingActivity.class,
                OnboardingActivity.stepIntent(context, KeepADBOnboarding.Step.PROTECTION)).setup();
        try {
            List<Integer> switches = new ArrayList<>();
            collectSwitches(controller.get().getWindow().getDecorView(), switches);
            assertTrue("The assistant's protection step holds no switch: " + switches,
                    switches.isEmpty());
        } finally {
            controller.pause().stop().destroy();
        }
    }

    // --- helpers ------------------------------------------------------------------------------------------

    private ProtectionActivity open() {
        ActivityController<ProtectionActivity> controller =
                Robolectric.buildActivity(ProtectionActivity.class).setup();
        controllers.add(controller);
        ShadowLooper.idleMainLooper();
        return controller.get();
    }

    private void closeAll() {
        for (ActivityController<ProtectionActivity> controller : controllers) {
            controller.pause().stop().destroy();
        }
        controllers.clear();
    }

    private void store(boolean allowlist, boolean byName, boolean nameList) {
        KeepADBTrustedNetwork.setMode(context, allowlist
                ? KeepADBTrustedNetwork.MODE_ALLOWLIST : KeepADBTrustedNetwork.MODE_ALL_WIFI);
        KeepADBTrustedNetwork.setTrustByNameEnabled(context, byName);
        if (nameList) {
            KeepADBTrustedNetwork.addSsid(context, "Mesh");
            KeepADBTrustedNetwork.setSsidMatchingEnabled(context, true);
        }
    }

    private static View card(ProtectionActivity page, int index) {
        return ((ViewGroup) page.findViewById(R.id.protection_level_options)).getChildAt(index);
    }

    private static String title(ProtectionActivity page, int index) {
        return ((TextView) card(page, index).findViewById(R.id.choice_title)).getText().toString();
    }

    /** Exactly the card at {@code index} is the chosen one (activated, radio on). */
    private static void assertShown(ProtectionActivity page, int index) {
        for (int i = 0; i < 4; i++) {
            assertEquals("card " + i + " chosen", i == index, card(page, i).isActivated());
            assertEquals("card " + i + " radio", i == index,
                    ((android.widget.RadioButton) card(page, i).findViewById(R.id.choice_indicator))
                            .isChecked());
        }
    }

    private static void collectSwitches(View view, List<Integer> out) {
        if (view instanceof Switch) out.add(view.getId());
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectSwitches(group.getChildAt(i), out);
        }
    }

    private static String findTextContaining(View view, String needle) {
        if (view instanceof TextView && ((TextView) view).getText().toString().contains(needle)) {
            return ((TextView) view).getText().toString();
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                String found = findTextContaining(group.getChildAt(i), needle);
                if (found != null) return found;
            }
        }
        return null;
    }

    private Map<String, ?> everyPreference() {
        Map<String, Object> all = new TreeMap<>();
        for (Map.Entry<String, ?> entry : prefs().getAll().entrySet()) {
            all.put(entry.getKey(), entry.getValue());
        }
        return all;
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE);
    }
}
