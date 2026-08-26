package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

public class KeepADBUsbProfileTest {

    @Test
    public void updatePersistsAllFieldsAndPreservesIdAndSelection() {
        MemoryPreferences preferences = new MemoryPreferences();
        TestContext context = new TestContext(preferences);
        KeepADBUsbProfile.Profile selected = KeepADBUsbProfile.add(
                context, "Selected", "192.0.2.1", "selected.local", "selected.ts.net");
        KeepADBUsbProfile.Profile unselected = KeepADBUsbProfile.add(
                context, "Unselected", "192.0.2.2", "old.local", "old.ts.net");
        KeepADBUsbProfile.select(context, selected.id);

        KeepADBUsbProfile.Profile updated = KeepADBUsbProfile.update(
                context, unselected.id, " Updated ", " 198.51.100.2 ", " new.local ", " new.ts.net ");

        assertEquals(unselected.id, updated.id);
        assertEquals("Updated", updated.name);
        assertEquals("198.51.100.2", updated.ipAddress);
        assertEquals("new.local", updated.hostname);
        assertEquals("new.ts.net", updated.tailnetHostname);
        assertEquals(selected.id, KeepADBUsbProfile.getSelected(context).id);

        TestContext restartedContext = new TestContext(preferences);
        KeepADBUsbProfile.Profile persisted = find(restartedContext, unselected.id);
        assertEquals(unselected.id, persisted.id);
        assertEquals("Updated", persisted.name);
        assertEquals("198.51.100.2", persisted.ipAddress);
        assertEquals("new.local", persisted.hostname);
        assertEquals("new.ts.net", persisted.tailnetHostname);
        assertEquals(selected.id, KeepADBUsbProfile.getSelected(restartedContext).id);

        KeepADBUsbProfile.Profile updatedSelected = KeepADBUsbProfile.update(
                restartedContext, selected.id, "Selected again", "", null, "");
        assertEquals(selected.id, updatedSelected.id);
        assertEquals(selected.id, KeepADBUsbProfile.getSelected(restartedContext).id);
        assertEquals("", updatedSelected.ipAddress);
        assertEquals("", updatedSelected.hostname);
        assertEquals("", updatedSelected.tailnetHostname);
    }

    @Test
    public void updateRejectsBlankNamesAndUnknownIdsWithoutChangingStoredProfile() {
        MemoryPreferences preferences = new MemoryPreferences();
        TestContext context = new TestContext(preferences);
        KeepADBUsbProfile.Profile original = KeepADBUsbProfile.add(
                context, "Original", "192.0.2.3", "host.local", "host.ts.net");

        assertNull(KeepADBUsbProfile.update(context, original.id, "   ", "new-ip", "new-host", "new-ts"));
        assertNull(KeepADBUsbProfile.update(context, original.id, null, "new-ip", "new-host", "new-ts"));
        assertNull(KeepADBUsbProfile.update(context, 9999, "Valid", "new-ip", "new-host", "new-ts"));

        KeepADBUsbProfile.Profile unchanged = find(context, original.id);
        assertEquals("Original", unchanged.name);
        assertEquals("192.0.2.3", unchanged.ipAddress);
        assertEquals("host.local", unchanged.hostname);
        assertEquals("host.ts.net", unchanged.tailnetHostname);
        assertEquals(1, KeepADBUsbProfile.getProfiles(context).size());
        assertFalse(KeepADBUsbProfile.getSelected(context) == null);
    }

    @Test
    public void deleteUnselectedProfileRemovesAllDataAndKeepsSelection() {
        MemoryPreferences preferences = new MemoryPreferences();
        TestContext context = new TestContext(preferences);
        KeepADBUsbProfile.Profile selected = KeepADBUsbProfile.add(
                context, "Selected", "192.0.2.10", "selected.local", "selected.ts.net");
        KeepADBUsbProfile.Profile deleted = KeepADBUsbProfile.add(
                context, "Deleted", "192.0.2.11", "deleted.local", "deleted.ts.net");
        KeepADBUsbProfile.select(context, selected.id);

        assertTrue(KeepADBUsbProfile.delete(context, deleted.id));
        assertEquals(1, KeepADBUsbProfile.getProfiles(context).size());
        assertEquals(selected.id, KeepADBUsbProfile.getSelected(context).id);
        assertEquals(null, findOrNull(context, deleted.id));
        assertFalse(preferences.contains("usb_profile_" + deleted.id + "_name"));
        assertFalse(preferences.contains("usb_profile_" + deleted.id + "_ip"));
        assertFalse(preferences.contains("usb_profile_" + deleted.id + "_host"));
        assertFalse(preferences.contains("usb_profile_" + deleted.id + "_tailnet"));
    }

    @Test
    public void deleteSelectedProfileChoosesNextRemainingProfileInOrder() {
        MemoryPreferences preferences = new MemoryPreferences();
        TestContext context = new TestContext(preferences);
        KeepADBUsbProfile.Profile first = KeepADBUsbProfile.add(context, "First", "", "", "");
        KeepADBUsbProfile.Profile selected = KeepADBUsbProfile.add(context, "Selected", "", "", "");
        KeepADBUsbProfile.Profile next = KeepADBUsbProfile.add(context, "Next", "", "", "");
        KeepADBUsbProfile.select(context, selected.id);

        assertTrue(KeepADBUsbProfile.delete(context, selected.id));
        assertEquals(next.id, KeepADBUsbProfile.getSelected(context).id);
        assertEquals(first.id, KeepADBUsbProfile.getProfiles(context).get(0).id);
        assertEquals(next.id, KeepADBUsbProfile.getProfiles(context).get(1).id);
    }

    @Test
    public void deleteSelectedLastProfileChoosesPreviousRemainingProfile() {
        MemoryPreferences preferences = new MemoryPreferences();
        TestContext context = new TestContext(preferences);
        KeepADBUsbProfile.Profile first = KeepADBUsbProfile.add(context, "First", "", "", "");
        KeepADBUsbProfile.Profile selected = KeepADBUsbProfile.add(context, "Selected", "", "", "");
        KeepADBUsbProfile.select(context, selected.id);

        assertTrue(KeepADBUsbProfile.delete(context, selected.id));
        assertEquals(first.id, KeepADBUsbProfile.getSelected(context).id);
        assertEquals(first.id, KeepADBUsbProfile.getProfiles(context).get(0).id);
    }

    @Test
    public void deleteLastProfileClearsSelectionAndUnknownDeleteIsNoOp() {
        MemoryPreferences preferences = new MemoryPreferences();
        TestContext context = new TestContext(preferences);
        KeepADBUsbProfile.Profile only = KeepADBUsbProfile.add(
                context, "Only", "192.0.2.12", "only.local", "only.ts.net");

        assertFalse(KeepADBUsbProfile.delete(context, 9999));
        assertTrue(KeepADBUsbProfile.delete(context, only.id));
        assertTrue(KeepADBUsbProfile.getProfiles(context).isEmpty());
        assertNull(KeepADBUsbProfile.getSelected(context));
        assertFalse(preferences.contains("usb_profile_selected_id"));
        assertFalse(preferences.contains("usb_profile_ids"));
        assertFalse(preferences.contains("usb_profile_" + only.id + "_name"));
        assertFalse(preferences.contains("usb_profile_" + only.id + "_ip"));
        assertFalse(preferences.contains("usb_profile_" + only.id + "_host"));
        assertFalse(preferences.contains("usb_profile_" + only.id + "_tailnet"));
    }

    private static KeepADBUsbProfile.Profile find(Context context, int id) {
        for (KeepADBUsbProfile.Profile profile : KeepADBUsbProfile.getProfiles(context)) {
            if (profile.id == id) return profile;
        }
        throw new AssertionError("Missing profile " + id);
    }

    private static KeepADBUsbProfile.Profile findOrNull(Context context, int id) {
        for (KeepADBUsbProfile.Profile profile : KeepADBUsbProfile.getProfiles(context)) {
            if (profile.id == id) return profile;
        }
        return null;
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
