package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;

public class KeepADBReceiverTest {

    @Before
    public void setUp() {
        KeepADB.resetForTesting();
    }

    @Test
    public void nullIntentOrContextIsIgnoredSafely() {
        KeepADBReceiver receiver = new KeepADBReceiver();
        receiver.onReceive(null, null);
        receiver.onReceive(new FakeContext(true), null);
        receiver.onReceive(null, new TestIntent(KeepADBReceiver.ACTION_DISABLE));
    }

    @Test
    public void unknownActionIsIgnored() {
        FakeContext context = new FakeContext(true);
        KeepADBReceiver receiver = new KeepADBReceiver();
        receiver.onReceive(context, new TestIntent("com.example.UNKNOWN"));

        assertFalse(KeepADB.isUserDisabled());
        assertFalse(KeepADB.wasLastExplicitIntentOff());
    }

    @Test
    public void disableActionInvokesSetEnabledFalseAndRecordsDiagnostics() {
        FakeContext context = new FakeContext(true);
        KeepADBReceiver receiver = new KeepADBReceiver();
        receiver.onReceive(context, new TestIntent(KeepADBReceiver.ACTION_DISABLE));

        assertTrue(KeepADB.isUserDisabled());
        assertTrue(KeepADB.wasLastExplicitIntentOff());

        String export = KeepADBDiagnostics.export(context);
        assertTrue(export.contains("event=user_action source=notification outcome=disable detail=action_button"));
    }

    @Test
    public void disableActionWhenPermissionMissingFailsGracefully() {
        FakeContext context = new FakeContext(false);
        KeepADBReceiver receiver = new KeepADBReceiver();
        receiver.onReceive(context, new TestIntent(KeepADBReceiver.ACTION_DISABLE));

        assertFalse(KeepADB.isUserDisabled());
        assertFalse(KeepADB.wasLastExplicitIntentOff());

        String export = KeepADBDiagnostics.export(context);
        assertTrue(export.contains("event=user_action source=notification outcome=disable detail=action_button"));
        assertTrue(export.contains("event=toggle_attempt source=notification outcome=failed"));
    }

    @Test
    public void handleDisableActionDirectlyReturnsSuccessOrFailure() {
        FakeContext permittedContext = new FakeContext(true);
        assertTrue(KeepADBReceiver.handleDisableAction(permittedContext));
        assertTrue(KeepADB.wasLastExplicitIntentOff());

        KeepADB.resetForTesting();

        FakeContext deniedContext = new FakeContext(false);
        assertFalse(KeepADBReceiver.handleDisableAction(deniedContext));
        assertFalse(KeepADB.wasLastExplicitIntentOff());
    }

    @Test
    public void actionConstantMatchesExpectedString() {
        assertEquals("de.hohnepeople.keepadb.ACTION_DISABLE", KeepADBReceiver.ACTION_DISABLE);
    }

    private static final class TestIntent extends Intent {
        private final String action;

        TestIntent(String action) {
            this.action = action;
        }

        @Override
        public String getAction() {
            return action;
        }
    }

    private static final class FakeContext extends ContextWrapper {
        private final boolean hasPermission;
        private final SharedPreferences preferences = new MemoryPreferences();

        FakeContext(boolean hasPermission) {
            super(null);
            this.hasPermission = hasPermission;
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }

        @Override
        public String getPackageName() {
            return "de.hohnepeople.keepadb";
        }

        @Override
        public int checkSelfPermission(String permission) {
            return hasPermission ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED;
        }

        @Override
        public boolean stopService(Intent name) {
            return true;
        }

        @Override
        public ComponentName startService(Intent service) {
            return null;
        }

        @Override
        public ComponentName startForegroundService(Intent service) {
            return null;
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
