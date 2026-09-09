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
    /** An automatic source: still debounced by TOGGLE_COOLDOWN_MS after #310. */
    private static final String AUTO = "keep_alive_check";

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
        // The verified-trust memory is intentionally process-wide in production, so it has to be
        // cleared here or a neighbouring test could leave this one's trust checks fail-open.
        KeepADBTrustedNetwork.resetVerifiedTrustForTesting();
        ctx = new FakeContext();
    }

    @After
    public void tearDown() {
        KeepADB.resetForTesting();
        KeepADBTrustedNetwork.resetVerifiedTrustForTesting();
    }

    @Test
    public void firstToggleAppliesImmediatelyWhenNotThrottled() {
        assertTrue(KeepADB.setEnabled(ctx, true, "app"));
        assertEquals(Arrays.asList(true), gateway.writes);
        assertTrue(gateway.isEnabled(ctx));
    }

    @Test
    public void rapidSecondToggleIsDebouncedAndANewerIntentSupersedesTheDelayedOne() {
        // #310: the debounce is now scoped to *automatic* sources, so this scenario drives one
        // (AUTO) throughout; the manual counterpart is manualTogglesAreNeverDelayed() below.
        // First call applies immediately (clock is far past the cooldown window).
        assertTrue(KeepADB.setEnabled(ctx, true, AUTO));
        assertEquals(1, gateway.writes.size());

        // A second call at the same instant is within the cooldown window and gets scheduled
        // instead of applied immediately.
        assertTrue(KeepADB.setEnabled(ctx, false, AUTO));
        assertEquals("the throttled call must not have written yet", 1, gateway.writes.size());
        assertTrue(scheduler.hasAnyPending());

        // A third, newer call before the scheduled one fires must supersede it -- the "false"
        // intent in between must never reach the gateway.
        assertTrue(KeepADB.setEnabled(ctx, true, AUTO));

        scheduler.advanceBy(KeepADB.TOGGLE_COOLDOWN_MS);
        assertEquals("the superseded false intent must never reach the gateway",
                Arrays.asList(true, true), gateway.writes);
        assertTrue(gateway.isEnabled(ctx));
    }

    @Test
    public void surfacesAreRefreshedOncePerAppliedWriteAndNeverForASupersededOne() {
        assertTrue(KeepADB.setEnabled(ctx, true, AUTO));
        assertEquals("an applied write must fan out to the surfaces exactly once",
                1, surfaces.refreshCount);

        // Throttled, then immediately superseded. #318: scheduling a write now fans out too, so
        // the surfaces can show the pending state during the debounce window -- that is a render
        // of "a write is in flight", not a claim that anything was applied (the gateway assertions
        // in throttledTogglesCollapseToTheNewestIntent() pin the applied side).
        assertTrue(KeepADB.setEnabled(ctx, false, AUTO));
        assertEquals("scheduling must make the pending state visible", 2, surfaces.refreshCount);
        assertTrue(KeepADB.setEnabled(ctx, true, AUTO));
        assertEquals(3, surfaces.refreshCount);

        scheduler.advanceBy(KeepADB.TOGGLE_COOLDOWN_MS);
        assertEquals("only the surviving intent may refresh the surfaces on apply",
                4, surfaces.refreshCount);
        assertFalse("the debounce window must be over", KeepADB.isTogglePending());
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

        // AUTO throughout: after #310 only an automatic source is debounced at all, so only an
        // automatic retry can still observe whether the rejected write moved the debounce anchor.
        assertFalse("a gateway that rejected the write must not report success",
                KeepADB.setEnabled(ctx, true, AUTO));
        assertEquals(Arrays.asList(true), gateway.writes);
        assertFalse(gateway.isEnabled(ctx));
        // #318: a rejected write does fan out -- not to publish a new state, but so the surfaces
        // drop any pending indicator and fall back to the real, unchanged setting. What must not
        // happen is the *bookkeeping* of an applied write, which the retry below pins.
        assertEquals("a rejected write must resolve the pending indicator exactly once",
                1, surfaces.refreshCount);

        // #309: recordApplied() must have been skipped too. Had the failed write been booked as
        // applied, it would have moved the debounce anchor to "now" and this immediate retry
        // would be delayed instead of writing straight away.
        gateway.setWriteSuccess(true);
        assertTrue(KeepADB.setEnabled(ctx, true, AUTO));
        assertEquals("the retry after a rejected write must not be debounced",
                Arrays.asList(true, true), gateway.writes);
        assertTrue(gateway.isEnabled(ctx));
    }

    // ---------------------------------------------------------------------------------------
    // #310: automatic enables are revalidated at write time; manual ones are never delayed.
    // ---------------------------------------------------------------------------------------

    /**
     * Acceptance criterion 2: "Manuelles Einschalten durch den Nutzer bleibt als expliziter
     * Override ohne Verzögerung erhalten." Every manual surface must write straight away even
     * deep inside the cooldown window that would delay an automatic caller (proven by the
     * automatic control case at the end, which *is* delayed at the very same clock reading).
     */
    @Test
    public void manualTogglesAreNeverDelayed() {
        assertTrue(KeepADB.setEnabled(ctx, true, "app"));
        assertEquals(1, gateway.writes.size());

        // Each of these lands well inside TOGGLE_COOLDOWN_MS of the previous write.
        assertTrue(KeepADB.setEnabled(ctx, false, "tile"));
        assertTrue(KeepADB.setEnabled(ctx, true, "widget"));
        assertTrue(KeepADB.setEnabled(ctx, false, "notification"));
        assertTrue(KeepADB.setEnabled(ctx, true, KeepADB.SOURCE_USB_HANDOVER_MANUAL));

        assertFalse("a manual toggle must never be parked on the scheduler",
                scheduler.hasAnyPending());
        assertEquals("every manual toggle must have reached the gateway immediately",
                Arrays.asList(true, false, true, false, true), gateway.writes);
        assertTrue(gateway.isEnabled(ctx));

        // Control: at this very same clock reading an automatic source is still debounced, so
        // the assertions above are about the manual/automatic split, not about a wide-open
        // cooldown window.
        assertTrue(KeepADB.setEnabled(ctx, false, AUTO));
        assertEquals(5, gateway.writes.size());
        assertTrue(scheduler.hasAnyPending());
    }

    /**
     * Acceptance criterion 1, network half: the Wi-Fi changed while an automatic enable sat in
     * its cooldown window, so the enable must be dropped. The counter-probe below runs the
     * identical sequence without the network change and proves the write does land otherwise.
     */
    @Test
    public void pendingAutomaticEnableIsDroppedWhenTheNetworkChangedDuringTheCooldown() {
        assertTrue(KeepADB.setEnabled(ctx, false, AUTO)); // anchors the cooldown window
        assertEquals(1, gateway.writes.size());

        assertTrue(KeepADB.setEnabled(ctx, true, AUTO, appContext -> true));
        assertTrue("the automatic enable must have been scheduled, not applied",
                scheduler.hasAnyPending());

        KeepADB.noteNetworkChanged(); // e.g. KeepADBService's onLost()/onAvailable()

        scheduler.advanceBy(KeepADB.TOGGLE_COOLDOWN_MS);
        assertEquals("an enable planned for a network we left must never be written",
                Arrays.asList(false), gateway.writes);
        assertFalse(gateway.isEnabled(ctx));
    }

    @Test
    public void pendingAutomaticEnableSurvivesWhenTheNetworkDidNotChange() {
        assertTrue(KeepADB.setEnabled(ctx, false, AUTO));
        assertTrue(KeepADB.setEnabled(ctx, true, AUTO, appContext -> true));
        assertTrue(scheduler.hasAnyPending());

        scheduler.advanceBy(KeepADB.TOGGLE_COOLDOWN_MS);
        assertEquals("without a network change the pending enable must still be applied",
                Arrays.asList(false, true), gateway.writes);
        assertTrue(gateway.isEnabled(ctx));
    }

    /**
     * Acceptance criterion 1, Keep-Alive half -- driven through the real production guard
     * {@link KeepADBService#isAutoEnableStillPermitted}, not a stand-in, so this pins the actual
     * wiring KeepADBService uses. MODE_ALL_WIFI keeps the trusted-network half of that guard out
     * of the way (a plain JVM test has no WifiManager, so allowlist mode always fails closed);
     * the trusted-network half is covered separately below.
     */
    @Test
    public void pendingAutomaticEnableIsDroppedWhenKeepAliveIsDisabledDuringTheCooldown() {
        KeepADBTrustedNetwork.setMode(ctx, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        KeepADBPreferences.setKeepAliveEnabled(ctx, true);

        assertTrue(KeepADB.setEnabled(ctx, false, AUTO));
        assertTrue(KeepADB.setEnabled(ctx, true, AUTO, KeepADBService::isAutoEnableStillPermitted));
        assertTrue(scheduler.hasAnyPending());

        KeepADBPreferences.setKeepAliveEnabled(ctx, false);

        scheduler.advanceBy(KeepADB.TOGGLE_COOLDOWN_MS);
        assertEquals("a Keep-Alive enable must not fire after Keep-Alive was switched off",
                Arrays.asList(false), gateway.writes);
        assertFalse(gateway.isEnabled(ctx));
    }

    @Test
    public void pendingAutomaticEnableSurvivesWhenKeepAliveStaysEnabled() {
        KeepADBTrustedNetwork.setMode(ctx, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        KeepADBPreferences.setKeepAliveEnabled(ctx, true);

        assertTrue(KeepADB.setEnabled(ctx, false, AUTO));
        assertTrue(KeepADB.setEnabled(ctx, true, AUTO, KeepADBService::isAutoEnableStillPermitted));

        scheduler.advanceBy(KeepADB.TOGGLE_COOLDOWN_MS);
        assertEquals("the counter-probe: with Keep-Alive left on the enable must be applied",
                Arrays.asList(false, true), gateway.writes);
        assertTrue(gateway.isEnabled(ctx));
    }

    /**
     * Acceptance criterion 1, trust half: the same production guard must also drop the pending
     * enable when the network stops being trusted mid-cooldown -- here by switching the policy
     * back to allowlist mode, which this JVM test's contextless WifiManager can never satisfy.
     */
    @Test
    public void pendingAutomaticEnableIsDroppedWhenTheNetworkIsNoLongerTrusted() {
        KeepADBTrustedNetwork.setMode(ctx, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        KeepADBPreferences.setKeepAliveEnabled(ctx, true);

        assertTrue(KeepADB.setEnabled(ctx, false, AUTO));
        assertTrue(KeepADB.setEnabled(ctx, true, AUTO, KeepADBService::isAutoEnableStillPermitted));
        assertTrue(scheduler.hasAnyPending());

        KeepADBTrustedNetwork.setMode(ctx, KeepADBTrustedNetwork.MODE_ALLOWLIST);

        scheduler.advanceBy(KeepADB.TOGGLE_COOLDOWN_MS);
        assertEquals("an enable must not fire onto a network that is no longer trusted",
                Arrays.asList(false), gateway.writes);
        assertFalse(gateway.isEnabled(ctx));
    }

    /**
     * The guard must not leak into the disable direction: #310 is explicitly scoped to the
     * automatic ON path, and a guard that says "no" must never stop an automatic OFF.
     */
    @Test
    public void anAutomaticDisableIsNeverBlockedByTheGuard() {
        assertTrue(KeepADB.setEnabled(ctx, true, AUTO));
        assertTrue(KeepADB.setEnabled(ctx, false, AUTO, appContext -> false));
        scheduler.advanceBy(KeepADB.TOGGLE_COOLDOWN_MS);

        assertEquals(Arrays.asList(true, false), gateway.writes);
        assertFalse(gateway.isEnabled(ctx));
    }

    /**
     * A manual toggle landing during the cooldown must still supersede a pending automatic one
     * (non-goal: #310 removes the manual *delay*, never the token-superseding race protection).
     */
    @Test
    public void aManualToggleStillSupersedesAPendingAutomaticEnable() {
        assertTrue(KeepADB.setEnabled(ctx, false, AUTO));
        assertTrue(KeepADB.setEnabled(ctx, true, AUTO, appContext -> true));
        assertTrue(scheduler.hasAnyPending());

        assertTrue("the manual off must be applied at once", KeepADB.setEnabled(ctx, false, "app"));
        assertEquals(Arrays.asList(false, false), gateway.writes);

        scheduler.advanceBy(KeepADB.TOGGLE_COOLDOWN_MS);
        assertEquals("the superseded automatic enable must never reach the gateway",
                Arrays.asList(false, false), gateway.writes);
        assertFalse(gateway.isEnabled(ctx));
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
