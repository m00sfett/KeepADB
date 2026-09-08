package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Deterministic tests for KeepADB's debounce and generation-token logic (#249), using the
 * KeepADBSettingsGateway/KeepADBScheduler seams introduced in #248 instead of a real
 * ContentResolver, Handler, or Thread. {@link KeepADBFakeScheduler#runAsync} runs synchronously,
 * which is enough to prove ordering/cancellation for these scenarios; the one scenario that
 * genuinely needs concurrent execution (a manual disable landing *during* a recovery pulse's
 * pause) is covered separately in {@link KeepADBRecoveryPulseInterruptionTest} with real threads
 * and latches instead.
 *
 * <p>Scenario "process recreation with a persisted manual-off intent" is already covered by
 * {@link KeepADBUsbHandoverTest#lastDesiredOnPreferencesPersistenceAcrossSimulatedRestart()}
 * against the production gateway/scheduler, so it isn't duplicated here.
 */
public class KeepADBToggleSchedulingTest {
    private KeepADBFakeScheduler scheduler;
    private KeepADBFakeSettingsGateway gateway;
    private KeepADBFakeSurfaceRefresher surfaces;
    private FakeContext ctx;

    @Before
    public void setUp() {
        KeepADB.resetForTesting();
        scheduler = new KeepADBFakeScheduler();
        scheduler.setClockMs(100_000); // comfortably past TOGGLE_COOLDOWN_MS since "boot"
        gateway = new KeepADBFakeSettingsGateway(false);
        surfaces = new KeepADBFakeSurfaceRefresher();
        KeepADB.setSchedulerForTesting(scheduler);
        KeepADB.setGatewayForTesting(gateway);
        KeepADB.setSurfaceRefresherForTesting(surfaces);
        ctx = new FakeContext();
    }

    @After
    public void tearDown() {
        KeepADB.resetForTesting();
    }

    @Test
    public void firstToggleAppliesImmediatelyWhenNotThrottled() {
        assertTrue(KeepADB.setEnabled(ctx, true, "app"));
        assertEquals(Arrays.asList(true), gateway.writes);
        assertTrue(gateway.isEnabled(ctx));
    }

    @Test
    public void rapidSecondToggleIsDebouncedAndANewerIntentSupersedesTheDelayedOne() {
        // First call applies immediately (clock is far past the cooldown window).
        assertTrue(KeepADB.setEnabled(ctx, true, "app"));
        assertEquals(1, gateway.writes.size());

        // A second call at the same instant is within the cooldown window and gets scheduled
        // instead of applied immediately.
        assertTrue(KeepADB.setEnabled(ctx, false, "app"));
        assertEquals("the throttled call must not have written yet", 1, gateway.writes.size());
        assertTrue(scheduler.hasAnyPending());

        // A third, newer call before the scheduled one fires must supersede it -- the "false"
        // intent in between must never reach the gateway.
        assertTrue(KeepADB.setEnabled(ctx, true, "app"));

        scheduler.advanceBy(KeepADB.TOGGLE_COOLDOWN_MS);
        assertEquals("the superseded false intent must never reach the gateway",
                Arrays.asList(true, true), gateway.writes);
        assertTrue(gateway.isEnabled(ctx));
    }

    @Test
    public void surfacesAreRefreshedOncePerAppliedWriteAndNeverForASupersededOne() {
        assertTrue(KeepADB.setEnabled(ctx, true, "app"));
        assertEquals("an applied write must fan out to the surfaces exactly once",
                1, surfaces.refreshCount);

        // Throttled, then immediately superseded: neither the gateway nor the surfaces may see it.
        assertTrue(KeepADB.setEnabled(ctx, false, "app"));
        assertEquals(1, surfaces.refreshCount);
        assertTrue(KeepADB.setEnabled(ctx, true, "app"));

        scheduler.advanceBy(KeepADB.TOGGLE_COOLDOWN_MS);
        assertEquals("only the surviving intent may refresh the surfaces",
                2, surfaces.refreshCount);
    }

    @Test
    public void recoveryPulseRestoresWhenUninterrupted() {
        gateway = new KeepADBFakeSettingsGateway(true);
        KeepADB.setGatewayForTesting(gateway);

        // KeepADBFakeScheduler's runAsync() runs synchronously and its sleep() only advances
        // the virtual clock rather than blocking, so both pulse stages complete within this
        // one call -- there is no external "advance past the pause" step with this scheduler
        // (see the class javadoc; KeepADBRecoveryPulseInterruptionTest uses a real thread and
        // latches instead specifically to observe the state in between the two stages).
        KeepADB.performRecoveryPulse(ctx);
        assertEquals("both pulse stages must apply when nothing intervenes",
                Arrays.asList(false, true), gateway.writes);
        assertTrue(gateway.isEnabled(ctx));
    }

    @Test
    public void recoveryPulseAbortsBeforeItsDisableWriteWhenANewerIntentLandedFirst() {
        gateway = new KeepADBFakeSettingsGateway(true);
        KeepADB.setGatewayForTesting(gateway);
        // The pulse body is queued rather than run inline, which reproduces the real gap between
        // beginPulse() on the calling thread and the pulse body starting on its own thread.
        scheduler.setDeferAsync(true);

        KeepADB.performRecoveryPulse(ctx);
        assertEquals("nothing may be written before the pulse body runs", 0, gateway.writes.size());

        // A manual "on" tap lands in that gap. It issues a newer intent token, so the pulse is
        // already stale when its *first* (disable) write would happen -- #309: before the fix
        // only the second (restore) stage checked the token, so this off-write went through and
        // switched wireless debugging back off behind the user's back.
        assertTrue(KeepADB.setEnabled(ctx, true, "app"));
        assertEquals(Arrays.asList(true), gateway.writes);

        scheduler.runDeferredAsync();
        assertEquals("a superseded pulse must not reach the gateway at all",
                Arrays.asList(true), gateway.writes);
        assertTrue("the manual intent must survive the stale pulse", gateway.isEnabled(ctx));
    }

    @Test
    public void aRejectedWriteFailsTheToggleAndIsNotBookedAsApplied() {
        gateway.setWriteSuccess(false);

        assertFalse("a gateway that rejected the write must not report success",
                KeepADB.setEnabled(ctx, true, "app"));
        assertEquals(Arrays.asList(true), gateway.writes);
        assertFalse(gateway.isEnabled(ctx));
        assertEquals("a write that never landed must not fan out to the surfaces",
                0, surfaces.refreshCount);

        // #309: recordApplied() must have been skipped too. Had the failed write been booked as
        // applied, it would have moved the debounce anchor to "now" and this immediate retry
        // would be delayed instead of writing straight away.
        gateway.setWriteSuccess(true);
        assertTrue(KeepADB.setEnabled(ctx, true, "app"));
        assertEquals("the retry after a rejected write must not be debounced",
                Arrays.asList(true, true), gateway.writes);
        assertTrue(gateway.isEnabled(ctx));
    }

    private static final class FakeContext extends ContextWrapper {
        private final SharedPreferences preferences = new MemoryPreferences();

        FakeContext() {
            super(null);
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }

        @Override
        public int checkSelfPermission(String permission) {
            return PackageManager.PERMISSION_GRANTED;
        }

        @Override
        public SharedPreferences getSharedPreferences(String name, int mode) {
            return preferences;
        }
    }

    private static final class MemoryPreferences implements SharedPreferences {
        private final Map<String, Object> values = new HashMap<>();

        @Override
        public Map<String, ?> getAll() {
            return new HashMap<>(values);
        }

        @Override
        public String getString(String key, String defValue) {
            Object value = values.get(key);
            return value instanceof String ? (String) value : defValue;
        }

        @SuppressWarnings("unchecked")
        @Override
        public Set<String> getStringSet(String key, Set<String> defValues) {
            Object value = values.get(key);
            return value instanceof Set ? Set.copyOf((Set<String>) value) : defValues;
        }

        @Override
        public int getInt(String key, int defValue) {
            Object value = values.get(key);
            return value instanceof Integer ? (Integer) value : defValue;
        }

        @Override
        public long getLong(String key, long defValue) {
            Object value = values.get(key);
            return value instanceof Long ? (Long) value : defValue;
        }

        @Override
        public float getFloat(String key, float defValue) {
            Object value = values.get(key);
            return value instanceof Float ? (Float) value : defValue;
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            Object value = values.get(key);
            return value instanceof Boolean ? (Boolean) value : defValue;
        }

        @Override
        public boolean contains(String key) {
            return values.containsKey(key);
        }

        @Override
        public Editor edit() {
            return new MemoryEditor();
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        }

        @Override
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        }

        private final class MemoryEditor implements Editor {
            private final Map<String, Object> updates = new HashMap<>();
            private final Set<String> removals = new java.util.HashSet<>();
            private boolean clear;

            @Override
            public Editor putString(String key, String value) {
                updates.put(key, value);
                return this;
            }

            @Override
            public Editor putStringSet(String key, Set<String> values) {
                updates.put(key, values == null ? null : Set.copyOf(values));
                return this;
            }

            @Override
            public Editor putInt(String key, int value) {
                updates.put(key, value);
                return this;
            }

            @Override
            public Editor putLong(String key, long value) {
                updates.put(key, value);
                return this;
            }

            @Override
            public Editor putFloat(String key, float value) {
                updates.put(key, value);
                return this;
            }

            @Override
            public Editor putBoolean(String key, boolean value) {
                updates.put(key, value);
                return this;
            }

            @Override
            public Editor remove(String key) {
                removals.add(key);
                return this;
            }

            @Override
            public Editor clear() {
                clear = true;
                return this;
            }

            @Override
            public boolean commit() {
                apply();
                return true;
            }

            @Override
            public void apply() {
                if (clear) values.clear();
                for (String key : removals) values.remove(key);
                values.putAll(updates);
            }
        }
    }
}
