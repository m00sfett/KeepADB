package de.hohnepeople.keepadb;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class KeepADBPreferencesTest {

    @Test
    public void testValidHttpUrl() {
        assertTrue(KeepADBPreferences.isValidWebhookUrl("http://100.111.111.21:50829/register/s20"));
        assertTrue(KeepADBPreferences.isValidWebhookUrl("http://localhost:8080/hook"));
        assertTrue(KeepADBPreferences.isValidWebhookUrl("http://example.com/api"));
        assertTrue(KeepADBPreferences.isValidWebhookUrl("  http://192.168.1.100:5000/register  "));
    }

    @Test
    public void testValidHttpsUrl() {
        assertTrue(KeepADBPreferences.isValidWebhookUrl("https://example.com/webhook"));
        assertTrue(KeepADBPreferences.isValidWebhookUrl("https://my-server.tailscale.net:8443/endpoint"));
    }

    @Test
    public void testInvalidUrls() {
        assertFalse(KeepADBPreferences.isValidWebhookUrl(null));
        assertFalse(KeepADBPreferences.isValidWebhookUrl(""));
        assertFalse(KeepADBPreferences.isValidWebhookUrl("   "));
        assertFalse(KeepADBPreferences.isValidWebhookUrl("ftp://example.com"));
        assertFalse(KeepADBPreferences.isValidWebhookUrl("not-a-url"));
        assertFalse(KeepADBPreferences.isValidWebhookUrl("http://"));
        assertFalse(KeepADBPreferences.isValidWebhookUrl("https://"));
        assertFalse(KeepADBPreferences.isValidWebhookUrl("://example.com"));
    }

    @Test
    public void testUsbPreferencesStateAndClear() {
        FakeContext context = new FakeContext();
        org.junit.Assert.assertNull(KeepADBPreferences.getUsbWebhookLastReportedUrl(context));
        org.junit.Assert.assertNull(KeepADBPreferences.getUsbWebhookLastReportedPayload(context));
        org.junit.Assert.assertNull(KeepADBPreferences.getUsbWebhookLastProfileId(context));
        org.junit.Assert.assertEquals(0L, KeepADBPreferences.getUsbWebhookLastReportedAt(context));

        KeepADBPreferences.setUsbWebhookLastReportedState(context, "http://example.com/register", "{\"active\":true}",
                7, "Desk", "192.168.1.50", "desk-host", "desk.tailnet.ts.net");

        org.junit.Assert.assertEquals("http://example.com/register", KeepADBPreferences.getUsbWebhookLastReportedUrl(context));
        org.junit.Assert.assertEquals("{\"active\":true}", KeepADBPreferences.getUsbWebhookLastReportedPayload(context));
        org.junit.Assert.assertEquals(Integer.valueOf(7), KeepADBPreferences.getUsbWebhookLastProfileId(context));
        org.junit.Assert.assertEquals("Desk", KeepADBPreferences.getUsbWebhookLastProfileName(context));
        org.junit.Assert.assertEquals("192.168.1.50", KeepADBPreferences.getUsbWebhookLastIpAddress(context));
        org.junit.Assert.assertEquals("desk-host", KeepADBPreferences.getUsbWebhookLastHostname(context));
        org.junit.Assert.assertEquals("desk.tailnet.ts.net", KeepADBPreferences.getUsbWebhookLastTailnetHostname(context));
        assertTrue(KeepADBPreferences.getUsbWebhookLastReportedAt(context) > 0L);

        KeepADBPreferences.clearUsbWebhookReportedState(context);
        org.junit.Assert.assertNull(KeepADBPreferences.getUsbWebhookLastReportedUrl(context));
        org.junit.Assert.assertNull(KeepADBPreferences.getUsbWebhookLastReportedPayload(context));
        org.junit.Assert.assertNull(KeepADBPreferences.getUsbWebhookLastProfileId(context));
        org.junit.Assert.assertNull(KeepADBPreferences.getUsbWebhookLastProfileName(context));
        org.junit.Assert.assertNull(KeepADBPreferences.getUsbWebhookLastIpAddress(context));
        org.junit.Assert.assertNull(KeepADBPreferences.getUsbWebhookLastHostname(context));
        org.junit.Assert.assertNull(KeepADBPreferences.getUsbWebhookLastTailnetHostname(context));
        org.junit.Assert.assertEquals(0L, KeepADBPreferences.getUsbWebhookLastReportedAt(context));
    }

    @Test
    public void testUsbPreferencesNullContextSafety() {
        org.junit.Assert.assertNull(KeepADBPreferences.getUsbWebhookLastReportedUrl(null));
        org.junit.Assert.assertNull(KeepADBPreferences.getUsbWebhookLastReportedPayload(null));
        org.junit.Assert.assertNull(KeepADBPreferences.getUsbWebhookLastProfileId(null));
        org.junit.Assert.assertNull(KeepADBPreferences.getUsbWebhookLastProfileName(null));
        org.junit.Assert.assertNull(KeepADBPreferences.getUsbWebhookLastIpAddress(null));
        org.junit.Assert.assertNull(KeepADBPreferences.getUsbWebhookLastHostname(null));
        org.junit.Assert.assertNull(KeepADBPreferences.getUsbWebhookLastTailnetHostname(null));
        org.junit.Assert.assertEquals(0L, KeepADBPreferences.getUsbWebhookLastReportedAt(null));

        KeepADBPreferences.setUsbWebhookLastReportedUrl(null, "http://example.com");
        KeepADBPreferences.setUsbWebhookLastReportedPayload(null, "payload");
        KeepADBPreferences.setUsbWebhookLastReportedState(null, "url", "payload", 1, "name", "ip", "host", "tail");
        KeepADBPreferences.clearUsbWebhookReportedState(null);
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
