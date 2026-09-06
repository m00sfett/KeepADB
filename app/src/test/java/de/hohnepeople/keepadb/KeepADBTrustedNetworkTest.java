package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class KeepADBTrustedNetworkTest {

    @Test
    public void defaultModeIsAllWifiAndTrustsEveryNetwork() {
        FakeContext context = new FakeContext();
        assertEquals(KeepADBTrustedNetwork.MODE_ALL_WIFI, KeepADBTrustedNetwork.getMode(context));
        assertFalse(KeepADBTrustedNetwork.isAllowlistMode(context));
        // No known network identity is available in a plain JVM test (no real WifiInfo), yet
        // MODE_ALL_WIFI must still trust the network -- this is the fail-open-by-design default
        // that preserves pre-#245 behavior for upgrading users.
        assertTrue(KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
        assertEquals(KeepADBTrustedNetwork.BlockReason.NONE, KeepADBTrustedNetwork.getBlockReason(context));
    }

    @Test
    public void allowlistModeFailsClosedWithNoKnownIdentity() {
        FakeContext context = new FakeContext();
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        assertTrue(KeepADBTrustedNetwork.isAllowlistMode(context));
        // Without a real WifiInfo, KeepADBNetworkIdentity.current() can't know the network --
        // allowlist mode must fail closed rather than trusting it.
        assertFalse(KeepADBTrustedNetwork.isCurrentNetworkTrusted(context));
        assertEquals(KeepADBTrustedNetwork.BlockReason.IDENTITY_UNAVAILABLE,
                KeepADBTrustedNetwork.getBlockReason(context));
    }

    @Test
    public void addCurrentNetworkFailsWithoutAKnownIdentity() {
        FakeContext context = new FakeContext();
        assertNull(KeepADBTrustedNetwork.addCurrentNetwork(context, "Home"));
        assertTrue(KeepADBTrustedNetwork.getEntries(context).isEmpty());
    }

    @Test
    public void removeIsANoOpForAnUnknownId() {
        FakeContext context = new FakeContext();
        assertFalse(KeepADBTrustedNetwork.remove(context, 999));
    }

    @Test
    public void findAndRemoveCurrentNetworkFailWithoutAKnownIdentity() {
        FakeContext context = new FakeContext();
        // Same JVM-test limitation as addCurrentNetworkFailsWithoutAKnownIdentity: no real
        // WifiInfo is available, so the current network's identity is always unknown here.
        assertNull(KeepADBTrustedNetwork.findEntryForCurrentNetwork(context));
        assertNull(KeepADBTrustedNetwork.removeCurrentNetwork(context));
    }

    @Test
    public void entriesRoundTripThroughPreferences() {
        FakeContext context = new FakeContext();
        // Exercise the persistence layer directly with a fabricated id list, since a JVM unit
        // test can't produce a real WifiInfo for addCurrentNetwork() to persist through.
        context.getSharedPreferences("keepadb_prefs", 0).edit()
                .putString("trusted_network_ids", "1,2")
                .putString("trusted_network_1_label", "Home")
                .putString("trusted_network_1_bssid", "aa:bb:cc:dd:ee:ff")
                .putString("trusted_network_2_label", "Office")
                .putString("trusted_network_2_bssid", "11:22:33:44:55:66")
                .apply();

        List<KeepADBTrustedNetwork.Entry> entries = KeepADBTrustedNetwork.getEntries(context);
        assertEquals(2, entries.size());
        assertEquals("Home", entries.get(0).label);
        assertEquals("aa:bb:cc:dd:ee:ff", entries.get(0).bssid);

        assertTrue(KeepADBTrustedNetwork.remove(context, 1));
        assertEquals(1, KeepADBTrustedNetwork.getEntries(context).size());
        assertEquals("Office", KeepADBTrustedNetwork.getEntries(context).get(0).label);
    }

    private static final class FakeContext extends android.content.ContextWrapper {
        private final android.content.SharedPreferences preferences = new MemoryPreferences();

        FakeContext() {
            super(null);
        }

        @Override
        public android.content.Context getApplicationContext() {
            return this;
        }

        @Override
        public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
            return preferences;
        }
    }

    private static final class MemoryPreferences implements android.content.SharedPreferences {
        private final java.util.Map<String, Object> values = new java.util.HashMap<>();

        @Override
        public java.util.Map<String, ?> getAll() {
            return new java.util.HashMap<>(values);
        }

        @Override
        public String getString(String key, String defValue) {
            Object value = values.get(key);
            return value instanceof String ? (String) value : defValue;
        }

        @SuppressWarnings("unchecked")
        @Override
        public java.util.Set<String> getStringSet(String key, java.util.Set<String> defValues) {
            Object value = values.get(key);
            return value instanceof java.util.Set ? java.util.Set.copyOf((java.util.Set<String>) value) : defValues;
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
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}

        @Override
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}

        private final class MemoryEditor implements Editor {
            private final java.util.Map<String, Object> updates = new java.util.HashMap<>();
            private final java.util.Set<String> removals = new java.util.HashSet<>();
            private boolean clear;

            @Override
            public Editor putString(String key, String value) {
                updates.put(key, value);
                return this;
            }

            @Override
            public Editor putStringSet(String key, java.util.Set<String> values) {
                updates.put(key, values == null ? null : java.util.Set.copyOf(values));
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
                for (String k : removals) values.remove(k);
                values.putAll(updates);
            }
        }
    }
}
