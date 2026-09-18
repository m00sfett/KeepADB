package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Deterministic tests for #496's automatic-enable backoff as wired into {@link KeepADB}'s
 * production call path ({@code setEnabled} -&gt; {@code applyNow}), using the same
 * {@link KeepADBFakeScheduler} seam as {@link KeepADBToggleSchedulingTest} instead of a real
 * clock. The pure state-machine transitions of {@link KeepADBRecoveryBackoff} itself are covered
 * without any of this Android-facing wiring in {@link KeepADBRecoveryBackoffTest}; the
 * {@link KeepADBService} call-site gating (deciding *whether* to call {@code setEnabled} at all)
 * is covered separately in {@link KeepADBServiceLifecycleRobolectricTest}.
 */
public class KeepADBRecoveryBackoffSchedulingTest {

    /** An automatic source: engages the #496 backoff on a readback mismatch. */
    private static final String AUTO = "keep_alive_check";

    private KeepADBFakeScheduler scheduler;
    private FakeContext ctx;

    @Before
    public void setUp() {
        KeepADB.resetForTesting();
        scheduler = new KeepADBFakeScheduler();
        scheduler.setClockMs(100_000);
        KeepADB.setSchedulerForTesting(scheduler);
        KeepADB.setSurfaceRefresherForTesting(new KeepADBFakeSurfaceRefresher());
        ctx = new FakeContext();
    }

    @After
    public void tearDown() {
        KeepADB.resetForTesting();
    }

    @Test
    public void anAcceptedWriteWithAStaleReadbackBlocksFurtherAutomaticAttempts() {
        KeepADB.setGatewayForTesting(new KeepADBStuckOffSettingsGateway());

        assertFalse(KeepADB.isAutomaticEnableBackoffBlocked());
        assertTrue("applyNow must still report the write as accepted",
                KeepADB.setEnabled(ctx, true, AUTO));

        assertTrue("a mismatched automatic enable must engage the #496 backoff",
                KeepADB.isAutomaticEnableBackoffBlocked());
    }

    @Test
    public void aSuccessfulAutomaticEnableNeverBlocks() {
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(false));

        assertTrue(KeepADB.setEnabled(ctx, true, AUTO));
        // #500: success is no longer decided by the readback taken right after the write -- that
        // one reads "on" in the broken case too. It is decided by the value still being on when
        // the confirmation window expires, which for a genuinely successful write it is.
        scheduler.advanceBy(KeepADBRecoveryBackoff.SUCCESS_CONFIRMATION_MS);

        assertFalse("a write that actually takes hold must never engage the backoff",
                KeepADB.isAutomaticEnableBackoffBlocked());
    }

    /**
     * The exact #500 race, reproduced: the write is accepted, the immediate readback says "on",
     * and only afterwards does the system revert the value. Before the fix this booked a success
     * and reset the backoff, so every following trigger started a fresh unbounded attempt.
     */
    @Test
    public void anAcceptedWriteTheSystemRevertsAfterTheReadbackStillBlocksFurtherAttempts() {
        KeepADBRevertingSettingsGateway gateway =
                new KeepADBRevertingSettingsGateway(scheduler::clockMs, 200);
        KeepADB.setGatewayForTesting(gateway);

        assertTrue(KeepADB.setEnabled(ctx, true, AUTO));
        assertTrue("the readback right after the write must still report 'on' -- that is the "
                        + "whole point of this fake", KeepADB.isEnabled(ctx));

        assertTrue("an automatic attempt must block further attempts until it is confirmed, not "
                        + "on the strength of its own immediate readback",
                KeepADB.isAutomaticEnableBackoffBlocked());

        // The system reverts, then the confirmation window expires: the verdict is "did not take
        // hold", so the block stays.
        scheduler.advanceBy(KeepADBRecoveryBackoff.SUCCESS_CONFIRMATION_MS);
        assertFalse(KeepADB.isEnabled(ctx));
        assertTrue("the reverted write must leave the backoff blocked",
                KeepADB.isAutomaticEnableBackoffBlocked());

        // What the call sites do (KeepADBService's ContentObserver / keep-alive check): they only
        // attempt while the backoff is open. Exactly one write must have happened in total.
        for (int i = 0; i < 20; i++) {
            if (!KeepADB.isAutomaticEnableBackoffBlocked()) {
                KeepADB.setEnabled(ctx, true, AUTO);
            }
            scheduler.advanceBy(KeepADB.TOGGLE_COOLDOWN_MS);
        }
        assertEquals("no second automatic attempt may follow before a trigger or the fallback "
                        + "interval reopens the cycle", 1, gateway.writes.size());
    }

    /**
     * Counterpart to the test above: an observed "on" that is <em>not</em> our own in-flight
     * attempt (the user confirmed Android's pairing dialog) still reopens the cycle immediately.
     */
    @Test
    public void anExternallyObservedEnableAfterTheVerdictReopensTheCycle() {
        KeepADB.setGatewayForTesting(new KeepADBRevertingSettingsGateway(scheduler::clockMs, 200));
        assertTrue(KeepADB.setEnabled(ctx, true, AUTO));
        scheduler.advanceBy(KeepADBRecoveryBackoff.SUCCESS_CONFIRMATION_MS);
        assertTrue(KeepADB.isAutomaticEnableBackoffBlocked());

        KeepADB.noteObservedEnabled();

        assertFalse("an 'on' observed outside our own attempt is a real trigger",
                KeepADB.isAutomaticEnableBackoffBlocked());
    }

    /** The same signal <em>during</em> our own attempt's confirmation window must not reopen it. */
    @Test
    public void anObservedEnableDuringOurOwnAttemptDoesNotReopenTheCycle() {
        KeepADB.setGatewayForTesting(new KeepADBRevertingSettingsGateway(scheduler::clockMs, 200));
        assertTrue(KeepADB.setEnabled(ctx, true, AUTO));

        // This is precisely what the ContentObserver sees when our accepted write momentarily
        // flips the value on before the system reverts it.
        KeepADB.noteObservedEnabled();

        assertTrue("our own transient 'on' must not clear the block it just engaged",
                KeepADB.isAutomaticEnableBackoffBlocked());
        scheduler.advanceBy(KeepADBRecoveryBackoff.SUCCESS_CONFIRMATION_MS);
        assertTrue(KeepADB.isAutomaticEnableBackoffBlocked());
    }

    @Test
    public void aRejectedWriteNeverEngagesTheBackoff() {
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(false);
        gateway.setWriteSuccess(false);
        KeepADB.setGatewayForTesting(gateway);

        assertFalse("a rejected write must fail the toggle",
                KeepADB.setEnabled(ctx, true, AUTO));

        assertFalse("acceptance criterion: a rejected write is a distinct diagnosis from an "
                        + "accepted-but-ineffective readback and must not engage the backoff",
                KeepADB.isAutomaticEnableBackoffBlocked());
    }

    @Test
    public void aSecurityExceptionNeverEngagesTheBackoff() {
        KeepADB.setGatewayForTesting(new KeepADBSettingsGateway() {
            @Override
            public boolean isEnabled(Context context) {
                return false;
            }

            @Override
            public boolean write(Context appContext, boolean on) {
                throw new SecurityException("WRITE_SECURE_SETTINGS revoked");
            }
        });

        assertFalse(KeepADB.setEnabled(ctx, true, AUTO));

        assertFalse("acceptance criterion: a SecurityException is a distinct diagnosis from an "
                        + "accepted-but-ineffective readback and must not engage the backoff",
                KeepADB.isAutomaticEnableBackoffBlocked());
    }

    @Test
    public void theBlockClearsOnceTheFallbackIntervalHasElapsed() {
        KeepADB.setGatewayForTesting(new KeepADBStuckOffSettingsGateway());
        assertTrue(KeepADB.setEnabled(ctx, true, AUTO));
        assertTrue(KeepADB.isAutomaticEnableBackoffBlocked());

        scheduler.advanceBy(KeepADBRecoveryBackoff.FALLBACK_RETRY_INTERVAL_MS);

        assertFalse("the fallback interval must reopen the cycle without any other trigger",
                KeepADB.isAutomaticEnableBackoffBlocked());
    }

    @Test
    public void aManualActionReopensABlockedAutomaticPath() {
        KeepADB.setGatewayForTesting(new KeepADBStuckOffSettingsGateway());
        assertTrue(KeepADB.setEnabled(ctx, true, AUTO));
        assertTrue(KeepADB.isAutomaticEnableBackoffBlocked());

        // Acceptance criterion: "Manuelle Nutzeraktionen ... können einen blockierten
        // automatischen Pfad bewusst erneut anstoßen." A manual tap, even one that itself ends
        // in the very same mismatch, must reopen the path rather than leave it stuck.
        assertTrue("a manual source's own write must still be reported as accepted",
                KeepADB.setEnabled(ctx, true, "app"));

        assertFalse("a manual action is never gated by the automatic backoff and must reset it",
                KeepADB.isAutomaticEnableBackoffBlocked());
    }

    @Test
    public void aManualDisableAlsoReopensABlockedAutomaticPath() {
        KeepADB.setGatewayForTesting(new KeepADBStuckOffSettingsGateway());
        assertTrue(KeepADB.setEnabled(ctx, true, AUTO));
        assertTrue(KeepADB.isAutomaticEnableBackoffBlocked());

        KeepADB.setEnabled(ctx, false, "tile");

        assertFalse("any explicit manual action -- on or off -- is evidence the user is in "
                        + "control and must reopen the backoff",
                KeepADB.isAutomaticEnableBackoffBlocked());
    }

    @Test
    public void aNetworkChangeReopensABlockedAutomaticPath() {
        KeepADB.setGatewayForTesting(new KeepADBStuckOffSettingsGateway());
        assertTrue(KeepADB.setEnabled(ctx, true, AUTO));
        assertTrue(KeepADB.isAutomaticEnableBackoffBlocked());

        // e.g. KeepADBService's NetworkCallback#onAvailable()/#onLost().
        KeepADB.noteNetworkChanged();

        assertFalse("a network change ends the 'unchanged state' cycle the backoff bounds",
                KeepADB.isAutomaticEnableBackoffBlocked());
    }

    @Test
    public void resetAutomaticEnableBackoffReopensABlockedPath() {
        KeepADB.setGatewayForTesting(new KeepADBStuckOffSettingsGateway());
        assertTrue(KeepADB.setEnabled(ctx, true, AUTO));
        assertTrue(KeepADB.isAutomaticEnableBackoffBlocked());

        // e.g. KeepADBService#onCreate() (a fresh app/service instance) or the ContentObserver
        // observing an externally-granted permission.
        KeepADB.resetAutomaticEnableBackoff();

        assertFalse(KeepADB.isAutomaticEnableBackoffBlocked());
    }

    @Test
    public void resetForTestingClearsAnyLeftoverBackoffBetweenTests() {
        KeepADB.setGatewayForTesting(new KeepADBStuckOffSettingsGateway());
        assertTrue(KeepADB.setEnabled(ctx, true, AUTO));
        assertTrue(KeepADB.isAutomaticEnableBackoffBlocked());

        KeepADB.resetForTesting();
        KeepADB.setSchedulerForTesting(scheduler);

        assertFalse("resetForTesting must not leak a blocked backoff into the next test",
                KeepADB.isAutomaticEnableBackoffBlocked());
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
