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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Covers the one recovery-pulse scenario (#249) that genuinely needs concurrent execution: a
 * manual disable arriving *during* the pulse's off-to-on pause, on a different thread than the
 * pulse itself. {@link KeepADB#performRecoveryPulse} runs its restore stage via {@code
 * scheduler.runAsync(...)} followed by a real {@code scheduler.sleep(...)} pause -- unlike the
 * simpler debounce scenarios in {@link KeepADBToggleSchedulingTest}, collapsing that to run
 * synchronously would make it impossible to inject anything "during" the pause. Instead, this
 * uses a real background thread with {@link CountDownLatch}s as synchronization points, so the
 * test is still deterministic (bounded waits, no reliance on real elapsed time or sleep-based
 * races) while genuinely exercising the {@code synchronized(KeepADB.class)} guard that's
 * supposed to protect against exactly this race.
 */
public class KeepADBRecoveryPulseInterruptionTest {

    @Before
    public void setUp() {
        KeepADB.resetForTesting();
    }

    @After
    public void tearDown() {
        KeepADB.resetForTesting();
    }

    @Test
    public void manualDisableDuringARecoveryPulseCancelsTheRestoreStage() throws InterruptedException {
        FakeContext ctx = new FakeContext();
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(true);
        LatchedScheduler scheduler = new LatchedScheduler();
        KeepADB.setGatewayForTesting(gateway);
        KeepADB.setSchedulerForTesting(scheduler);

        KeepADB.performRecoveryPulse(ctx);

        assertTrue("pulse thread must reach its pause within the timeout",
                scheduler.awaitSleepEntered());
        assertEquals("stage 1 (disable) must have applied before the pause",
                Arrays.asList(false), gateway.writes);

        // A manual disable arrives on this (test/main) thread while the pulse thread is parked
        // in its pause. This must update KeepADB's generation token/userDisabled/lastDesiredOn
        // regardless of whether the debounce itself applies immediately.
        assertTrue(KeepADB.setEnabled(ctx, false, "app"));
        assertTrue("an explicit disable must be recorded as the last intent",
                KeepADB.wasLastExplicitIntentOff(ctx));

        scheduler.releaseSleep();
        assertTrue("pulse thread must finish within the timeout", scheduler.awaitThreadFinished());

        assertEquals("the pulse's restore stage must never have written 'true'",
                Arrays.asList(false), gateway.writes);
        assertFalse(gateway.isEnabled(ctx));
    }

    @Test
    public void uninterruptedPulseStillRestoresViaARealThreadAndSleep() throws InterruptedException {
        FakeContext ctx = new FakeContext();
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(true);
        LatchedScheduler scheduler = new LatchedScheduler();
        KeepADB.setGatewayForTesting(gateway);
        KeepADB.setSchedulerForTesting(scheduler);

        KeepADB.performRecoveryPulse(ctx);
        assertTrue(scheduler.awaitSleepEntered());
        scheduler.releaseSleep();
        assertTrue(scheduler.awaitThreadFinished());

        assertEquals(Arrays.asList(false, true), gateway.writes);
        assertTrue(gateway.isEnabled(ctx));
    }

    private static final class LatchedScheduler implements KeepADBScheduler {
        private final CountDownLatch sleepEntered = new CountDownLatch(1);
        private final CountDownLatch releaseGate = new CountDownLatch(1);
        private final CountDownLatch threadFinished = new CountDownLatch(1);

        @Override
        public void postDelayed(Runnable runnable, long delayMs) {
            // Not exercised meaningfully here: the manual setEnabled() call in this test is
            // always within the (frozen-at-0) cooldown window and gets debounced, but this
            // test only cares about its side effect on KeepADB's static intent state, which
            // happens unconditionally before scheduling -- see the class javadoc.
        }

        @Override
        public void removeCallbacks(Runnable runnable) {
        }

        @Override
        public void runAsync(Runnable runnable) {
            new Thread(() -> {
                runnable.run();
                threadFinished.countDown();
            }, "test-recovery-pulse").start();
        }

        @Override
        public void sleep(long delayMs) throws InterruptedException {
            sleepEntered.countDown();
            releaseGate.await();
        }

        @Override
        public long elapsedRealtimeMs() {
            return 0;
        }

        boolean awaitSleepEntered() throws InterruptedException {
            return sleepEntered.await(5, TimeUnit.SECONDS);
        }

        void releaseSleep() {
            releaseGate.countDown();
        }

        boolean awaitThreadFinished() throws InterruptedException {
            return threadFinished.await(5, TimeUnit.SECONDS);
        }
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
