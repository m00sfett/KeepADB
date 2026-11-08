package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * #912: the settings of a placed control widget are per instance, survive a process restart, are
 * removed with the widget and never touch the app's own settings or another instance.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBWidgetConfigTest {

    private static final KeepADBWidgetConfig.Action ADB = KeepADBWidgetConfig.Action.ADB;
    private static final KeepADBWidgetConfig.Action KEEP_ALIVE = KeepADBWidgetConfig.Action.KEEP_ALIVE;
    private static final KeepADBWidgetConfig.Action SYNC = KeepADBWidgetConfig.Action.WEBHOOK_SYNC;

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADBWidgetConfig.clearAll(context);
    }

    @After
    public void tearDown() {
        KeepADBWidgetConfig.clearAll(context);
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test
    public void defaultsAreAdbKeepAliveSyncWithAdbAsTheMainAction() {
        KeepADBWidgetConfig defaults = KeepADBWidgetConfig.defaults();
        assertEquals(Arrays.asList(ADB, KEEP_ALIVE, SYNC), defaults.order);
        assertEquals(ADB, defaults.primary);
        assertFalse(defaults.hideDetails);
    }

    @Test
    public void aWidgetWithoutAnEntryReadsTheDefaults() {
        // Every widget placed before this version is in this state.
        assertFalse(KeepADBWidgetConfig.isStored(context, 4711));
        KeepADBWidgetConfig legacy = KeepADBWidgetConfig.load(context, 4711);
        assertEquals(KeepADBWidgetConfig.defaults().order, legacy.order);
        assertEquals(KeepADBWidgetConfig.defaults().primary, legacy.primary);
        assertFalse(legacy.hideDetails);
    }

    @Test
    public void theMainActionSurvivesShrinkingAndTheOrderKeepsThePositions() {
        KeepADBWidgetConfig config = KeepADBWidgetConfig.of(SYNC,
                Arrays.asList(ADB, KEEP_ALIVE, SYNC), false);

        assertEquals(Collections.singletonList(SYNC), config.shown(1));
        // The main action plus the first of the others, in the order's sequence.
        assertEquals(Arrays.asList(ADB, SYNC), config.shown(2));
        assertEquals(Arrays.asList(ADB, KEEP_ALIVE, SYNC), config.shown(3));
        assertEquals("asking for more than exists shows all, once",
                Arrays.asList(ADB, KEEP_ALIVE, SYNC), config.shown(9));
        assertTrue(config.shown(0).isEmpty());
    }

    @Test
    public void aCustomOrderIsKeptInEverySize() {
        KeepADBWidgetConfig config = KeepADBWidgetConfig.of(KEEP_ALIVE,
                Arrays.asList(SYNC, KEEP_ALIVE, ADB), false);

        assertEquals(Collections.singletonList(KEEP_ALIVE), config.shown(1));
        assertEquals(Arrays.asList(SYNC, KEEP_ALIVE), config.shown(2));
        assertEquals(Arrays.asList(SYNC, KEEP_ALIVE, ADB), config.shown(3));
    }

    @Test
    public void anUnavailableActionTakesNoPlaceAndTheOthersMoveUp() {
        KeepADBWidgetConfig config = KeepADBWidgetConfig.of(ADB,
                Arrays.asList(ADB, SYNC, KEEP_ALIVE), false);
        java.util.Set<KeepADBWidgetConfig.Action> noSync = java.util.EnumSet.of(SYNC);

        assertEquals(Arrays.asList(ADB, KEEP_ALIVE), config.shown(3, noSync));
        assertEquals(Arrays.asList(ADB), config.shown(1, noSync));
        assertEquals("with everything available nothing changes",
                config.shown(3), config.shown(3, java.util.Collections.<KeepADBWidgetConfig.Action>emptySet()));

        // An unavailable main action gives its place to the first of the others.
        KeepADBWidgetConfig syncFirst = KeepADBWidgetConfig.of(SYNC,
                Arrays.asList(SYNC, ADB, KEEP_ALIVE), false);
        assertEquals(Collections.singletonList(SYNC), syncFirst.shown(1));
        assertEquals(Collections.singletonList(ADB), syncFirst.shown(1, noSync));
    }

    @Test
    public void anyInputIsNormalizedToAPermutationOfAllThree() {
        KeepADBWidgetConfig config = KeepADBWidgetConfig.of(null,
                Arrays.asList(SYNC, null, SYNC, KEEP_ALIVE), true);
        assertEquals(ADB, config.primary);
        assertEquals(Arrays.asList(SYNC, KEEP_ALIVE, ADB), config.order);
        assertTrue(config.hideDetails);
    }

    @Test
    public void anUnreadableStoredValueFallsBackPartByPart() {
        assertEquals(KeepADBWidgetConfig.defaults().order,
                KeepADBWidgetConfig.decode("garbage").order);
        assertEquals(ADB, KeepADBWidgetConfig.decode(null).primary);
        KeepADBWidgetConfig partial = KeepADBWidgetConfig.decode("keep_alive;webhook_sync,bogus;1");
        assertEquals(KEEP_ALIVE, partial.primary);
        assertEquals("the readable part is kept, the missing actions follow in default order",
                Arrays.asList(SYNC, ADB, KEEP_ALIVE), partial.order);
        assertTrue(partial.hideDetails);
    }

    /**
     * Report now left the widget in #912, but entries stored by a build that still had it must
     * decode: the retired token is ignored and a main action that was Report now becomes ADB.
     */
    @Test
    public void aStoredEntryThatNamesTheRetiredReportNowStillDecodes() {
        KeepADBWidgetConfig primary = KeepADBWidgetConfig.decode(
                "report_now;keep_alive,report_now,webhook_sync,adb;1");
        assertEquals("the retired main action falls back to the default", ADB, primary.primary);
        assertEquals("the retired token is dropped from the order",
                Arrays.asList(KEEP_ALIVE, SYNC, ADB), primary.order);
        assertTrue(primary.hideDetails);

        KeepADBWidgetConfig inOrderOnly = KeepADBWidgetConfig.decode(
                "webhook_sync;adb,keep_alive,webhook_sync,report_now;0");
        assertEquals(SYNC, inOrderOnly.primary);
        assertEquals(Arrays.asList(ADB, KEEP_ALIVE, SYNC), inOrderOnly.order);

        // Through the stored file, the way a legacy instance reaches it.
        context.getSharedPreferences("keepadb_widget_config", Context.MODE_PRIVATE).edit()
                .putString("widget.61", "report_now;report_now,adb,keep_alive,webhook_sync;0").commit();
        KeepADBWidgetConfig loaded = KeepADBWidgetConfig.load(context, 61);
        assertEquals(ADB, loaded.primary);
        assertEquals(Arrays.asList(ADB, KEEP_ALIVE, SYNC), loaded.order);
    }

    @Test
    public void eachInstanceKeepsItsOwnSettingsAndTheyOutliveTheProcess() {
        KeepADBWidgetConfig a = KeepADBWidgetConfig.of(KEEP_ALIVE,
                Arrays.asList(KEEP_ALIVE, ADB, SYNC), true);
        KeepADBWidgetConfig b = KeepADBWidgetConfig.of(SYNC,
                Arrays.asList(SYNC, KEEP_ALIVE, ADB), false);
        KeepADBWidgetConfig.save(context, 11, a);
        KeepADBWidgetConfig.save(context, 12, b);

        // A fresh read from the file stands in for a recreated process: nothing is cached.
        Context again = ApplicationProvider.getApplicationContext();
        KeepADBWidgetConfig readA = KeepADBWidgetConfig.load(again, 11);
        KeepADBWidgetConfig readB = KeepADBWidgetConfig.load(again, 12);
        assertEquals(KEEP_ALIVE, readA.primary);
        assertEquals(a.order, readA.order);
        assertTrue(readA.hideDetails);
        assertEquals(SYNC, readB.primary);
        assertEquals(b.order, readB.order);
        assertFalse(readB.hideDetails);

        // Changing one never changes the other.
        KeepADBWidgetConfig.save(context, 11, KeepADBWidgetConfig.defaults());
        assertEquals(b.order, KeepADBWidgetConfig.load(context, 12).order);
        assertEquals(SYNC, KeepADBWidgetConfig.load(context, 12).primary);
    }

    @Test
    public void savingNeverTouchesTheAppSettings() {
        SharedPreferences appPrefs = context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE);
        appPrefs.edit().putBoolean("keep_alive_enabled", true).putString("custom", "x").commit();
        Map<String, ?> before = new TreeMap<>(appPrefs.getAll());

        KeepADBWidgetConfig.save(context, 21, KeepADBWidgetConfig.of(KEEP_ALIVE, null, true));
        KeepADBWidgetConfig.delete(context, new int[] {21});

        assertEquals(before, new TreeMap<>(appPrefs.getAll()));
    }

    @Test
    public void deletingAnInstanceRemovesOnlyItsEntry() {
        KeepADBWidgetConfig custom = KeepADBWidgetConfig.of(KEEP_ALIVE, null, true);
        KeepADBWidgetConfig.save(context, 31, custom);
        KeepADBWidgetConfig.save(context, 32, custom);
        KeepADBWidgetConfig.save(context, 33, custom);

        new KeepADBWidget().onDeleted(context, new int[] {31, 33});

        assertFalse(KeepADBWidgetConfig.isStored(context, 31));
        assertTrue("the other instance keeps its settings", KeepADBWidgetConfig.isStored(context, 32));
        assertFalse(KeepADBWidgetConfig.isStored(context, 33));
        assertEquals(KEEP_ALIVE, KeepADBWidgetConfig.load(context, 32).primary);
    }

    @Test
    public void removingTheLastWidgetClearsEveryEntry() {
        KeepADBWidgetConfig.save(context, 41, KeepADBWidgetConfig.of(KEEP_ALIVE, null, true));
        KeepADBWidgetConfig.save(context, 42, KeepADBWidgetConfig.of(SYNC, null, false));

        new KeepADBWidget().onDisabled(context);

        assertFalse(KeepADBWidgetConfig.isStored(context, 41));
        assertFalse(KeepADBWidgetConfig.isStored(context, 42));
    }

    @Test
    public void theIdIsTheOnlyKeyAndInvalidIdsAreHarmless() {
        KeepADBWidgetConfig.delete(context, null);
        KeepADBWidgetConfig.delete(context, new int[0]);
        KeepADBWidgetConfig.save(context, AppWidgetManager.INVALID_APPWIDGET_ID,
                KeepADBWidgetConfig.defaults());
        List<KeepADBWidgetConfig.Action> order = KeepADBWidgetConfig.load(context, 51).order;
        assertEquals(3, order.size());
    }
}
