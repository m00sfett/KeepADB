package de.hohnepeople.keepadb;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;

/**
 * Behavioral tests for the #168 USB-ADB -&gt; WLAN-ADB handover decision core. These exercise
 * {@link KeepADBUsbHandover#onRawUsbBroadcastInternal} directly -- it takes plain booleans/String
 * instead of a Context, so the connect-edge tracking and the isUserDisabled()/isEnabled() guards
 * can be proven without any Android stubbing.
 */
public class KeepADBUsbHandoverTest {

    private static final String OFF = KeepADBPreferences.USB_WLAN_HANDOVER_MODE_OFF;
    private static final String MANUAL = KeepADBPreferences.USB_WLAN_HANDOVER_MODE_MANUAL;
    private static final String AUTOMATIC = KeepADBPreferences.USB_WLAN_HANDOVER_MODE_AUTOMATIC;

    @Before
    public void setUp() {
        KeepADBUsbHandover.resetForTesting();
        KeepADB.resetForTesting();
    }

    @Test
    public void newConnectEdgeInAutomaticModeFiresWhenNotAlreadyEnabledAndNotUserDisabled() {
        assertTrue(KeepADBUsbHandover.onRawUsbBroadcastInternal(true, AUTOMATIC, false, false));
    }

    @Test
    public void sameConnectionReRefreshDoesNotReFireAutomaticMode() {
        // Genuine connect edge: fires once.
        assertTrue(KeepADBUsbHandover.onRawUsbBroadcastInternal(true, AUTOMATIC, false, false));
        // Same connection, still connected=true, no disconnect in between (e.g. a profile edit
        // re-refresh landing here) -- must NOT re-fire even though nothing else changed.
        assertFalse(KeepADBUsbHandover.onRawUsbBroadcastInternal(true, AUTOMATIC, false, false));
        assertFalse(KeepADBUsbHandover.onRawUsbBroadcastInternal(true, AUTOMATIC, true, false));
    }

    @Test
    public void disconnectReArmsForTheNextGenuineConnection() {
        assertTrue(KeepADBUsbHandover.onRawUsbBroadcastInternal(true, AUTOMATIC, false, false));
        assertFalse(KeepADBUsbHandover.onRawUsbBroadcastInternal(true, AUTOMATIC, false, false));

        // Real disconnect edge.
        assertFalse(KeepADBUsbHandover.onRawUsbBroadcastInternal(false, AUTOMATIC, false, false));

        // A new, genuine connect edge after the disconnect must be allowed to fire again.
        assertTrue(KeepADBUsbHandover.onRawUsbBroadcastInternal(true, AUTOMATIC, false, false));
    }

    @Test
    public void automaticModeNeverReEnablesAfterAnExplicitUserOff() {
        // First connect: fires and (in the real flow) KeepADB.setEnabled would be called.
        assertTrue(KeepADBUsbHandover.onRawUsbBroadcastInternal(true, AUTOMATIC, false, false));

        // Disconnect, then the user explicitly turns WLAN-ADB off (isUserDisabled() becomes true
        // in the real KeepADB class after such a call). A later, genuine new connect edge must
        // NOT override that explicit choice.
        KeepADBUsbHandover.onRawUsbBroadcastInternal(false, AUTOMATIC, false, false);
        assertFalse(KeepADBUsbHandover.onRawUsbBroadcastInternal(true, AUTOMATIC, false, true));
    }

    @Test
    public void alreadyEnabledNeverReTriggers() {
        assertFalse(KeepADBUsbHandover.onRawUsbBroadcastInternal(true, AUTOMATIC, true, false));
    }

    @Test
    public void offAndManualModeNeverAutoFireEvenOnAGenuineNewEdge() {
        assertFalse(KeepADBUsbHandover.onRawUsbBroadcastInternal(true, OFF, false, false));
        KeepADBUsbHandover.resetForTesting();
        assertFalse(KeepADBUsbHandover.onRawUsbBroadcastInternal(true, MANUAL, false, false));
    }

    @Test
    public void disconnectEdgeNeverFiresRegardlessOfMode() {
        assertFalse(KeepADBUsbHandover.onRawUsbBroadcastInternal(false, AUTOMATIC, false, false));
        assertFalse(KeepADBUsbHandover.onRawUsbBroadcastInternal(false, MANUAL, false, false));
        assertFalse(KeepADBUsbHandover.onRawUsbBroadcastInternal(false, OFF, false, false));
    }

    @Test
    public void repeatedConnectedTrueBroadcastsWithoutADisconnectInBetweenFireAtMostOnce() {
        // Simulates several real USB_STATE broadcasts all reporting connected=true in a row
        // (Android can re-broadcast USB_STATE for unrelated extra changes while still plugged
        // in) -- only the first should count as a new edge.
        assertTrue(KeepADBUsbHandover.onRawUsbBroadcastInternal(true, AUTOMATIC, false, false));
        for (int i = 0; i < 5; i++) {
            assertFalse(KeepADBUsbHandover.onRawUsbBroadcastInternal(true, AUTOMATIC, false, false));
        }
    }

    /**
     * Reproduces the exact real-device sequence found via on-device diagnostics (not
     * simulation) after #168 landed: a manual user OFF sets both KeepADB's one-shot
     * userDisabled token and its non-consumed "last explicit intent" state; an unrelated
     * KeepADBService content-observer decision then consumes (and resets) the one-shot token
     * for its own Keep-Alive stop-vs-recover purpose; a genuine new USB connect edge arriving
     * afterwards must still see the (still-off) last explicit intent and must NOT re-enable.
     * Unlike the other tests here, this one drives the real {@link KeepADB#setEnabled} and
     * {@link KeepADB#consumeUserDisabled()} entry points instead of poking booleans directly, so
     * it actually exercises the cross-component bug, not just the isolated decision function.
     */
    @Test
    public void consumeUserDisabledMidSequenceDoesNotUnblockAGenuineLaterUsbReconnect() {
        Context ctx = new FakeContext();

        // 1. User manually turns WLAN-ADB off via the app toggle.
        KeepADB.setEnabled(ctx, false, "app");
        assertTrue("setEnabled(false) must record the last explicit intent as off",
                KeepADB.wasLastExplicitIntentOff());

        // 2. KeepADBService's content observer fires on this very change and consumes the
        //    one-shot userDisabled token to decide "this was deliberate, stop recovering" --
        //    exactly as KeepADBService.registerAdbObserver() does.
        assertTrue("the one-shot token must reflect the user-initiated off",
                KeepADB.consumeUserDisabled());
        assertFalse("consuming is destructive by design: isUserDisabled() resets to false",
                KeepADB.isUserDisabled());

        // 3. The non-consumed field must be unaffected by that unrelated read.
        assertTrue("wasLastExplicitIntentOff() must survive an unrelated consumeUserDisabled() call",
                KeepADB.wasLastExplicitIntentOff());

        // 4. A genuine new USB connect edge arrives later, in AUTOMATIC mode.
        boolean shouldEnable = KeepADBUsbHandover.onRawUsbBroadcastInternal(
                true, AUTOMATIC, KeepADB.isEnabled(ctx), KeepADB.wasLastExplicitIntentOff());
        assertFalse("automatic USB handover must not override an explicit prior user OFF, "
                        + "even after an unrelated consumeUserDisabled() call happened in between",
                shouldEnable);
    }

    /**
     * Minimal fake Context so {@link KeepADB#setEnabled} can run end-to-end in a plain JVM unit
     * test: routes {@code getApplicationContext()} back to itself and provides a working
     * in-memory SharedPreferences so {@link KeepADBDiagnostics#event} (called internally by
     * setEnabled) doesn't NPE on a null preferences instance. Same lightweight pattern as
     * {@link KeepADBUsbHandoverModeTest}'s TestContext/MemoryPreferences.
     */
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
                if (clear) values.clear();
                for (String key : removals) values.remove(key);
                for (Map.Entry<String, Object> entry : updates.entrySet()) {
                    if (entry.getValue() == null) values.remove(entry.getKey());
                    else values.put(entry.getKey(), entry.getValue());
                }
                return true;
            }

            @Override
            public void apply() {
                commit();
            }
        }
    }
}
