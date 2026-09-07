package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * Unit tests for the mesh-BSSID observation history (#266): size-bounded per-SSID history,
 * oldest-entry eviction, and the no-entry-without-a-known-SSID rule.
 */
public class KeepADBBssidHistoryTest {

    @Test
    public void recordsAndReturnsAKnownBssidForItsSsid() {
        FakeContext context = new FakeContext();
        KeepADBBssidHistory.recordObservation(context, "HomeMesh", "aa:aa:aa:aa:aa:01");

        List<String> known = KeepADBBssidHistory.getKnownBssids(context, "HomeMesh");
        assertEquals(1, known.size());
        assertEquals("aa:aa:aa:aa:aa:01", known.get(0));
    }

    @Test
    public void ignoresObservationsWithoutAKnownSsid() {
        FakeContext context = new FakeContext();
        KeepADBBssidHistory.recordObservation(context, null, "aa:aa:aa:aa:aa:01");
        KeepADBBssidHistory.recordObservation(context, "", "aa:aa:aa:aa:aa:02");
        KeepADBBssidHistory.recordObservation(context, "   ", "aa:aa:aa:aa:aa:03");

        assertTrue(KeepADBBssidHistory.getKnownBssids(context, "").isEmpty());
        assertTrue(KeepADBBssidHistory.getKnownBssids(context, "HomeMesh").isEmpty());
    }

    @Test
    public void ignoresObservationsWithoutAKnownBssid() {
        FakeContext context = new FakeContext();
        KeepADBBssidHistory.recordObservation(context, "HomeMesh", null);
        KeepADBBssidHistory.recordObservation(context, "HomeMesh", "");

        assertTrue(KeepADBBssidHistory.getKnownBssids(context, "HomeMesh").isEmpty());
    }

    @Test
    public void recordingTheSameBssidTwiceDoesNotDuplicateIt() {
        FakeContext context = new FakeContext();
        KeepADBBssidHistory.recordObservation(context, "HomeMesh", "aa:aa:aa:aa:aa:01");
        KeepADBBssidHistory.recordObservation(context, "HomeMesh", "AA:AA:AA:AA:AA:01");

        assertEquals(1, KeepADBBssidHistory.getKnownBssids(context, "HomeMesh").size());
    }

    @Test
    public void separatesHistoryPerSsid() {
        FakeContext context = new FakeContext();
        KeepADBBssidHistory.recordObservation(context, "HomeMesh", "aa:aa:aa:aa:aa:01");
        KeepADBBssidHistory.recordObservation(context, "OfficeMesh", "bb:bb:bb:bb:bb:01");

        assertEquals(1, KeepADBBssidHistory.getKnownBssids(context, "HomeMesh").size());
        assertEquals(1, KeepADBBssidHistory.getKnownBssids(context, "OfficeMesh").size());
        assertEquals("bb:bb:bb:bb:bb:01", KeepADBBssidHistory.getKnownBssids(context, "OfficeMesh").get(0));
    }

    @Test
    public void evictsTheOldestBssidOnceThePerSsidLimitIsExceeded() {
        FakeContext context = new FakeContext();
        for (int i = 1; i <= KeepADBBssidHistory.MAX_BSSIDS_PER_SSID + 2; i++) {
            KeepADBBssidHistory.recordObservation(context, "HomeMesh", bssid(i));
        }

        List<String> known = KeepADBBssidHistory.getKnownBssids(context, "HomeMesh");
        assertEquals(KeepADBBssidHistory.MAX_BSSIDS_PER_SSID, known.size());
        // The two oldest (1 and 2) must have been evicted; the two newest must be present.
        assertTrue(known.contains(bssid(3)));
        assertTrue(known.contains(bssid(KeepADBBssidHistory.MAX_BSSIDS_PER_SSID + 2)));
        assertTrue(!known.contains(bssid(1)));
        assertTrue(!known.contains(bssid(2)));
    }

    @Test
    public void additionalBssidsExcludeAlreadyListedOnesCaseInsensitively() {
        FakeContext context = new FakeContext();
        KeepADBBssidHistory.recordObservation(context, "HomeMesh", "aa:aa:aa:aa:aa:01");
        KeepADBBssidHistory.recordObservation(context, "HomeMesh", "aa:aa:aa:aa:aa:02");
        KeepADBBssidHistory.recordObservation(context, "HomeMesh", "aa:aa:aa:aa:aa:03");

        List<String> additional = KeepADBBssidHistory.getAdditionalBssids(context, "HomeMesh",
                Collections.singletonList("AA:AA:AA:AA:AA:01"));

        assertEquals(2, additional.size());
        assertTrue(additional.contains("aa:aa:aa:aa:aa:02"));
        assertTrue(additional.contains("aa:aa:aa:aa:aa:03"));
    }

    @Test
    public void additionalBssidsIsEmptyForAnUnseenSsid() {
        FakeContext context = new FakeContext();
        assertTrue(KeepADBBssidHistory.getAdditionalBssids(context, "UnknownSsid", Collections.emptyList())
                .isEmpty());
    }

    @Test
    public void evictsTheLeastRecentlyObservedSsidOnceTheTotalSsidLimitIsExceeded() {
        FakeContext context = new FakeContext();
        for (int i = 1; i <= KeepADBBssidHistory.MAX_SSIDS + 2; i++) {
            KeepADBBssidHistory.recordObservation(context, ssid(i), "aa:aa:aa:aa:aa:01");
        }

        // The two least-recently-observed SSIDs (1 and 2) must have been evicted entirely.
        assertTrue(KeepADBBssidHistory.getKnownBssids(context, ssid(1)).isEmpty());
        assertTrue(KeepADBBssidHistory.getKnownBssids(context, ssid(2)).isEmpty());
        assertEquals(1, KeepADBBssidHistory.getKnownBssids(context, ssid(3)).size());
        assertEquals(1,
                KeepADBBssidHistory.getKnownBssids(context, ssid(KeepADBBssidHistory.MAX_SSIDS + 2)).size());
    }

    @Test
    public void reObservingAnSsidCountsAsRecentAndProtectsItFromEviction() {
        FakeContext context = new FakeContext();
        KeepADBBssidHistory.recordObservation(context, "OldButRefreshed", "aa:aa:aa:aa:aa:01");
        for (int i = 1; i <= KeepADBBssidHistory.MAX_SSIDS - 1; i++) {
            KeepADBBssidHistory.recordObservation(context, ssid(i), "aa:aa:aa:aa:aa:01");
        }
        // Touch it again -- it is now the most-recently-observed SSID, not the oldest.
        KeepADBBssidHistory.recordObservation(context, "OldButRefreshed", "aa:aa:aa:aa:aa:02");
        // One more distinct SSID pushes the total to MAX_SSIDS + 1; without the touch above,
        // "OldButRefreshed" would be the least-recently-observed and get evicted here.
        KeepADBBssidHistory.recordObservation(context, "OneMore", "aa:aa:aa:aa:aa:01");

        List<String> known = KeepADBBssidHistory.getKnownBssids(context, "OldButRefreshed");
        assertEquals(2, known.size());
        assertTrue(known.contains("aa:aa:aa:aa:aa:01"));
        assertTrue(known.contains("aa:aa:aa:aa:aa:02"));
        // The actual least-recently-observed SSID (ssid(1)) must be the one evicted instead.
        assertTrue(KeepADBBssidHistory.getKnownBssids(context, ssid(1)).isEmpty());
    }

    private static String ssid(int index) {
        return "Ssid" + index;
    }

    private static String bssid(int index) {
        return String.format(java.util.Locale.US, "aa:aa:aa:aa:aa:%02x", index);
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
