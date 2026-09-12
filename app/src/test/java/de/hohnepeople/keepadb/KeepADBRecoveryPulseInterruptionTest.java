package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

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
        // in its pause. This must update KeepADB's generation token/userDisabled/lastDesiredOn.
        // #310: as a manual source it is also applied at once instead of being debounced, which
        // is why a second "false" write appears below -- the pulse's stage-1 write had just moved
        // the debounce anchor, so before #310 the user's tap sat in the cooldown and never landed.
        assertTrue(KeepADB.setEnabled(ctx, false, "app"));
        assertTrue("an explicit disable must be recorded as the last intent",
                KeepADB.wasLastExplicitIntentOff(ctx));

        scheduler.releaseSleep();
        assertTrue("pulse thread must finish within the timeout", scheduler.awaitThreadFinished());

        assertEquals("the pulse's restore stage must never have written 'true'",
                Arrays.asList(false, false), gateway.writes);
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

    /**
     * #309: the narrow window the previous code left open. The pulse's restore stage checked the
     * intent token inside {@code synchronized (KeepADB.class)} but wrote *after* releasing that
     * lock, so a manual disable could complete in between and then be silently overwritten by
     * the pulse. Here the gateway parks the pulse inside its restore write while a second thread
     * issues that manual disable: with the write covered by the same lock as the guard, the
     * manual call cannot squeeze in between, and whichever order the two end up in, the user's
     * explicit off is the last thing applied.
     */
    @Test
    public void aManualDisableRacingTheRestoreWriteIsNeverOverwrittenByThePulse() throws InterruptedException {
        FakeContext ctx = new FakeContext();
        LatchedGateway gateway = new LatchedGateway(true);
        RacingScheduler scheduler = new RacingScheduler();
        KeepADB.setGatewayForTesting(gateway);
        KeepADB.setSchedulerForTesting(scheduler);
        KeepADB.setSurfaceRefresherForTesting(new KeepADBFakeSurfaceRefresher());

        KeepADB.performRecoveryPulse(ctx);
        assertTrue("the pulse must reach its restore write", gateway.awaitRestoreEntered());
        assertEquals("only the disable stage may have been applied so far",
                Arrays.asList(false), gateway.writes());

        CountDownLatch manualFinished = new CountDownLatch(1);
        Thread manual = new Thread(() -> {
            KeepADB.setEnabled(ctx, false, "app");
            manualFinished.countDown();
        }, "test-manual-disable");
        manual.start();
        // Bounded wait that is *expected* to time out once the barrier is in place: the manual
        // disable is blocked on KeepADB.class, which the pulse holds across its restore write.
        // Without the barrier it completes right here -- and the pulse then overwrites it.
        manualFinished.await(500, TimeUnit.MILLISECONDS);

        gateway.releaseRestore();
        assertTrue("the manual disable must finish once the pulse released the lock",
                manualFinished.await(5, TimeUnit.SECONDS));
        assertTrue(scheduler.awaitThreadFinished());
        manual.join(5000);

        assertEquals("the manual disable must be applied after the pulse's restore, not before it",
                Arrays.asList(false, true, false), gateway.writes());
        assertFalse("the user's manual disable must win", gateway.isEnabled(ctx));
    }

    @Test
    public void rejectedDisableMustNotReachTheRestoreStage() {
        FakeContext ctx = new FakeContext();
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(true);
        gateway.setWriteSuccess(false);
        KeepADB.setGatewayForTesting(gateway);
        KeepADB.setSchedulerForTesting(new KeepADBFakeScheduler());

        KeepADB.performRecoveryPulse(ctx);

        assertEquals(Arrays.asList(false), gateway.writes);
    }

    @Test
    public void interruptedPauseMustNotReachTheRestoreStage() {
        FakeContext ctx = new FakeContext();
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(true);
        KeepADB.setGatewayForTesting(gateway);
        KeepADB.setSchedulerForTesting(new InterruptingScheduler());

        KeepADB.performRecoveryPulse(ctx);

        assertEquals(Arrays.asList(false), gateway.writes);
    }

    @Test
    public void recoveryGuardIsRecheckedBeforeTheRestoreStage() {
        FakeContext ctx = new FakeContext();
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(true);
        KeepADB.setGatewayForTesting(gateway);
        KeepADB.setSchedulerForTesting(new KeepADBFakeScheduler());
        AtomicInteger guardCalls = new AtomicInteger();

        KeepADB.performRecoveryPulse(ctx, ignored -> guardCalls.incrementAndGet() == 1);

        assertEquals("the guard must be checked before both pulse writes", 2, guardCalls.get());
        assertEquals("a changed context must cancel the restore write", Arrays.asList(false),
                gateway.writes);
    }

    /**
     * {@link KeepADBSettingsGateway} fake that parks the caller inside the recovery pulse's
     * restore write (the {@code true} write) until the test releases it, so the test can act
     * while the pulse is mid-write.
     */
    private static final class LatchedGateway implements KeepADBSettingsGateway {
        private final CountDownLatch restoreEntered = new CountDownLatch(1);
        private final CountDownLatch restoreGate = new CountDownLatch(1);
        private final List<Boolean> writes = Collections.synchronizedList(new ArrayList<>());
        private volatile boolean enabled;

        LatchedGateway(boolean initiallyEnabled) {
            this.enabled = initiallyEnabled;
        }

        @Override
        public boolean isEnabled(Context context) {
            return enabled;
        }

        @Override
        public boolean write(Context appContext, boolean on) {
            if (on && restoreEntered.getCount() > 0) {
                restoreEntered.countDown();
                try {
                    restoreGate.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            writes.add(on);
            enabled = on;
            return true;
        }

        List<Boolean> writes() {
            return new ArrayList<>(writes);
        }

        boolean awaitRestoreEntered() throws InterruptedException {
            return restoreEntered.await(5, TimeUnit.SECONDS);
        }

        void releaseRestore() {
            restoreGate.countDown();
        }
    }

    /**
     * Runs the pulse on a real thread and hands out a strictly increasing monotonic clock: every
     * reading is a full toggle cooldown past the previous one, so no toggle in this test is ever
     * debounced and the write order alone decides the outcome.
     */
    private static final class RacingScheduler implements KeepADBScheduler {
        private final AtomicLong clockMs = new AtomicLong();
        private final CountDownLatch threadFinished = new CountDownLatch(1);

        @Override
        public void postDelayed(Runnable runnable, long delayMs) {
            throw new AssertionError("no toggle in this test may be debounced");
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
        public void sleep(long delayMs) {
        }

        @Override
        public long elapsedRealtimeMs() {
            return clockMs.addAndGet(10 * KeepADB.TOGGLE_COOLDOWN_MS);
        }

        boolean awaitThreadFinished() throws InterruptedException {
            return threadFinished.await(5, TimeUnit.SECONDS);
        }
    }

    private static final class InterruptingScheduler implements KeepADBScheduler {
        @Override public void postDelayed(Runnable runnable, long delayMs) { }
        @Override public void removeCallbacks(Runnable runnable) { }
        @Override public void runAsync(Runnable runnable) { runnable.run(); }
        @Override public void sleep(long delayMs) throws InterruptedException {
            throw new InterruptedException("test interruption");
        }
        @Override public long elapsedRealtimeMs() { return 10 * KeepADB.TOGGLE_COOLDOWN_MS; }
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
