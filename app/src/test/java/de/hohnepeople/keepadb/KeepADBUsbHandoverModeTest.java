package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

/**
 * Behavioral tests for the #168 {@code usb_wlan_handover_mode} preference: default-off, a
 * round-trip through all three valid values, and rejection of an unrecognized stored value.
 * Uses the same lightweight ContextWrapper + in-memory SharedPreferences fake as
 * {@link KeepADBUsbProfileTest}, since the getter/setter only ever touch SharedPreferences.
 */
public class KeepADBUsbHandoverModeTest {

    @Test
    public void defaultsToOff() {
        TestContext context = new TestContext(new MemoryPreferences());
        assertEquals(KeepADBPreferences.USB_WLAN_HANDOVER_MODE_OFF,
                KeepADBPreferences.getUsbWlanHandoverMode(context));
    }

    @Test
    public void roundTripsAllThreeValidModes() {
        TestContext context = new TestContext(new MemoryPreferences());

        KeepADBPreferences.setUsbWlanHandoverMode(context, KeepADBPreferences.USB_WLAN_HANDOVER_MODE_MANUAL);
        assertEquals(KeepADBPreferences.USB_WLAN_HANDOVER_MODE_MANUAL,
                KeepADBPreferences.getUsbWlanHandoverMode(context));

        KeepADBPreferences.setUsbWlanHandoverMode(context, KeepADBPreferences.USB_WLAN_HANDOVER_MODE_AUTOMATIC);
        assertEquals(KeepADBPreferences.USB_WLAN_HANDOVER_MODE_AUTOMATIC,
                KeepADBPreferences.getUsbWlanHandoverMode(context));

        KeepADBPreferences.setUsbWlanHandoverMode(context, KeepADBPreferences.USB_WLAN_HANDOVER_MODE_OFF);
        assertEquals(KeepADBPreferences.USB_WLAN_HANDOVER_MODE_OFF,
                KeepADBPreferences.getUsbWlanHandoverMode(context));
    }

    @Test
    public void unrecognizedStoredValueFallsBackToOffRatherThanFailingOpen() {
        MemoryPreferences preferences = new MemoryPreferences();
        preferences.edit().putString("usb_wlan_handover_mode", "garbage").apply();
        TestContext context = new TestContext(preferences);

        assertEquals(KeepADBPreferences.USB_WLAN_HANDOVER_MODE_OFF,
                KeepADBPreferences.getUsbWlanHandoverMode(context));
    }

    @Test
    public void settingAnUnrecognizedModeSanitizesToOff() {
        TestContext context = new TestContext(new MemoryPreferences());
        KeepADBPreferences.setUsbWlanHandoverMode(context, "not-a-real-mode");
        assertEquals(KeepADBPreferences.USB_WLAN_HANDOVER_MODE_OFF,
                KeepADBPreferences.getUsbWlanHandoverMode(context));
    }

    private static final class TestContext extends ContextWrapper {
        private final SharedPreferences preferences;

        TestContext(SharedPreferences preferences) {
            super(null);
            this.preferences = preferences;
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
