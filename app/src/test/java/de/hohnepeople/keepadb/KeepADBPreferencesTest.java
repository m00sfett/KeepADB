package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

public class KeepADBPreferencesTest {

    @After
    public void tearDown() {
        KeepADB.resetForTesting();
    }

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
    public void testLastDesiredOnPreferenceDefaultAndRoundTrip() {
        FakeContext context = new FakeContext();
        assertTrue(KeepADBPreferences.getLastDesiredOn(context));

        KeepADBPreferences.setLastDesiredOn(context, false);
        assertFalse(KeepADBPreferences.getLastDesiredOn(context));

        KeepADBPreferences.setLastDesiredOn(context, true);
        assertTrue(KeepADBPreferences.getLastDesiredOn(context));
    }

    @Test
    public void testLastDesiredOnNullContextSafety() {
        assertTrue(KeepADBPreferences.getLastDesiredOn(null));
        KeepADBPreferences.setLastDesiredOn(null, false);
    }

    @Test
    public void testSetKeepAliveEnabledResetsExplicitIntent() {
        FakeContext context = new FakeContext();
        KeepADB.resetForTesting();
        KeepADB.recordExplicitIntent(context, false);
        assertTrue(KeepADB.wasLastExplicitIntentOff(context));

        KeepADBPreferences.setKeepAliveEnabled(context, true);
        assertTrue(KeepADBPreferences.isKeepAliveEnabled(context));
        assertFalse(KeepADB.wasLastExplicitIntentOff(context));
        assertTrue(KeepADBPreferences.getLastDesiredOn(context));
    }

    @Test
    public void testWebhookReportStatusRoundTripAndLegacyInference() {
        FakeContext context = new FakeContext();

        assertEquals(KeepADBPreferences.WEBHOOK_STATUS_NEVER,
                KeepADBPreferences.getWebhookLastReportStatus(context));

        KeepADBPreferences.setWebhookLastReportStatus(context, KeepADBPreferences.WEBHOOK_STATUS_SUCCESS);
        assertEquals(KeepADBPreferences.WEBHOOK_STATUS_SUCCESS,
                KeepADBPreferences.getWebhookLastReportStatus(context));

        KeepADBPreferences.setWebhookLastReportStatus(context, KeepADBPreferences.WEBHOOK_STATUS_FAILED);
        assertEquals(KeepADBPreferences.WEBHOOK_STATUS_FAILED,
                KeepADBPreferences.getWebhookLastReportStatus(context));

        KeepADBPreferences.setWebhookLastReportStatus(context, KeepADBPreferences.WEBHOOK_STATUS_DEREGISTERED);
        assertEquals(KeepADBPreferences.WEBHOOK_STATUS_DEREGISTERED,
                KeepADBPreferences.getWebhookLastReportStatus(context));

        // Existing installations have no status key; infer the old success/deregistration state.
        KeepADBPreferences.setWebhookLastReportStatus(context, null);
        KeepADBPreferences.setWebhookLastReportedEndpoint(context, "192.0.2.10:40000");
        assertEquals(KeepADBPreferences.WEBHOOK_STATUS_SUCCESS,
                KeepADBPreferences.getWebhookLastReportStatus(context));
        KeepADBPreferences.setWebhookLastReportedEndpoint(context, null);
        KeepADBPreferences.setWebhookLastReportedAtNow(context);
        assertEquals(KeepADBPreferences.WEBHOOK_STATUS_DEREGISTERED,
                KeepADBPreferences.getWebhookLastReportStatus(context));
    }

    @Test
    public void testSanitizeWebhookUrlStripsUserinfoAndFragment() {
        assertEquals("http://100.111.111.21:50829/register/s20?foo=bar",
                KeepADBPreferences.sanitizeWebhookUrl(
                        "http://user:pass@100.111.111.21:50829/register/s20?foo=bar#section"));
        assertEquals("http://example.com/test",
                KeepADBPreferences.sanitizeWebhookUrl("HTTP://user:pass@example.com/test#fragment"));
        assertEquals("http://example.com:8080/path",
                KeepADBPreferences.sanitizeWebhookUrl("http://user@example.com:8080/path"));
        assertEquals("http://example.com:8080/path",
                KeepADBPreferences.sanitizeWebhookUrl("http://:pass@example.com:8080/path"));
        assertEquals("https://example.com/api",
                KeepADBPreferences.sanitizeWebhookUrl("https://example.com/api#section"));
        assertEquals("http://example.com",
                KeepADBPreferences.sanitizeWebhookUrl("http://example.com"));
        assertNull(KeepADBPreferences.sanitizeWebhookUrl(null));
        assertEquals("", KeepADBPreferences.sanitizeWebhookUrl(""));
        assertEquals("", KeepADBPreferences.sanitizeWebhookUrl("   "));
        assertEquals("not-a-url", KeepADBPreferences.sanitizeWebhookUrl("not-a-url"));
        assertEquals("http://", KeepADBPreferences.sanitizeWebhookUrl("http://user:pass@"));
        assertEquals("http://example.com/api?email=alice@example.com",
                KeepADBPreferences.sanitizeWebhookUrl("http://user:pass@example.com/api?email=alice@example.com#section"));
        assertEquals("http://example.com?email=alice@example.com",
                KeepADBPreferences.sanitizeWebhookUrl("http://user:pass@example.com?email=alice@example.com#section"));
        // A legacy URL with an embedded '@' in its password and a non-standard IPv6 zone id takes
        // the manual fallback path. The complete userinfo must still be removed.
        assertEquals("http://[fe80::1%wlan0]:8443/register",
                KeepADBPreferences.sanitizeWebhookUrl(
                        "http://user:p@ss@[fe80::1%wlan0]:8443/register"));
        assertEquals("http://[fe80::1%wlan0]:8443/register?email=x@y",
                KeepADBPreferences.sanitizeWebhookUrl(
                        "http://user:p@ss@[fe80::1%wlan0]:8443/register?email=x@y"));
        assertNull(KeepADBPreferences.sanitizeWebhookUrl("http://legacy.example/register\nnext"));
    }

    @Test
    public void testMaskWebhookUrlMasksIpv4AndRemovesUserinfo() {
        // #350: the query string used to be printed verbatim here ("?foo=bar").
        assertEquals("http://100.111.***.**:50829/register/s20?***",
                KeepADBPreferences.maskWebhookUrl(
                        "http://user:pass@100.111.111.21:50829/register/s20?foo=bar#hash"));
        assertEquals("http://100.111.***.**:50829/register/s20",
                KeepADBPreferences.maskWebhookUrl("http://100.111.111.21:50829/register/s20"));
        assertEquals("http://192.168.*.***:8080/endpoint",
                KeepADBPreferences.maskWebhookUrl("http://192.168.1.100:8080/endpoint"));
        assertEquals("http://10.0.*.*",
                KeepADBPreferences.maskWebhookUrl("http://10.0.0.1"));
        assertEquals("https://example.com/webhook",
                KeepADBPreferences.maskWebhookUrl("https://user:pass@example.com/webhook#secret"));
        assertEquals("", KeepADBPreferences.maskWebhookUrl(null));
        assertEquals("", KeepADBPreferences.maskWebhookUrl(""));
        assertEquals("", KeepADBPreferences.maskWebhookUrl("   "));
    }

    @Test
    public void testSetRegisterWebhookUrlStripsUserinfoOnWrite() {
        FakeContext context = new FakeContext();
        KeepADBPreferences.setRegisterWebhookUrl(context,
                "http://admin:secret123@100.111.111.21:50829/register/s20#section");
        assertEquals("http://100.111.111.21:50829/register/s20",
                KeepADBPreferences.getRegisterWebhookUrl(context));
    }

    @Test
    public void testGetRegisterWebhookUrlStripsUserinfoOnRead() {
        FakeContext context = new FakeContext();
        context.getSharedPreferences("keepadb_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putString("register_webhook_url", "http://user:pass@100.111.111.21:50829/register/s20#frag")
                .apply();
        assertEquals("http://100.111.111.21:50829/register/s20",
                KeepADBPreferences.getRegisterWebhookUrl(context));
    }

    /**
     * #350, acceptance criterion 2: a value written by an older app version, before
     * {@code setWebhookLastReportedUrl} sanitised anything. It is not merely displayed -- the
     * register client uses it as the DELETE target when the webhook URL changes, so an unsanitised
     * read would put the stored credentials on the wire.
     */
    @Test
    public void testGetWebhookLastReportedUrlSanitizesLegacyValueOnRead() {
        FakeContext context = new FakeContext();
        context.getSharedPreferences("keepadb_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putString("register_webhook_last_url",
                        "http://admin:hunter2@100.111.111.21:50829/register/s20#frag")
                .apply();
        assertEquals("http://100.111.111.21:50829/register/s20",
                KeepADBPreferences.getWebhookLastReportedUrl(context));
    }



    /** #350: the legacy value must also be unreadable through the display boundary. */
    @Test
    public void testLegacyLastReportedUrlIsFullyRedactedForDisplay() {
        FakeContext context = new FakeContext();
        context.getSharedPreferences("keepadb_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putString("register_webhook_last_url",
                        "http://admin:hunter2@[2001:db8::1]:50829/register/s20?token=abc#frag")
                .apply();
        assertEquals("http://[***]:50829/register/s20?***",
                KeepADBPreferences.maskWebhookUrl(
                        KeepADBPreferences.getWebhookLastReportedUrl(context)));
    }

    /** #483: with the privacy toggle off, every display value stays exactly as before. */
    @Test
    public void testPrivacyModeOffLeavesDisplayValuesUntouched() {
        FakeContext context = new FakeContext();
        assertFalse(KeepADBPreferences.isPrivacyModeEnabled(context));
        assertEquals("192.168.1.100",
                KeepADBPreferences.maskHostForDisplay(context, "192.168.1.100"));
        assertEquals("[fe80::1%wlan0]:50829",
                KeepADBPreferences.maskEndpointForDisplay(context, "[fe80::1%wlan0]:50829"));
        assertEquals("http://100.111.***.**:50829/register/s20",
                KeepADBPreferences.maskWebhookUrlForDisplay(
                        context, "http://100.111.111.21:50829/register/s20"));
    }

    /** #483: with the privacy toggle on, addresses are masked and ports stay readable. */
    @Test
    public void testPrivacyModeOnMasksAddressesButKeepsPortsAndHostnames() {
        FakeContext context = new FakeContext();
        KeepADBPreferences.setPrivacyModeEnabled(context, true);
        assertTrue(KeepADBPreferences.isPrivacyModeEnabled(context));
        assertEquals("192.*.*.*", KeepADBPreferences.maskHostForDisplay(context, "192.168.1.100"));
        assertEquals("192.*.*.*:5555",
                KeepADBPreferences.maskEndpointForDisplay(context, "192.168.1.100:5555"));
        assertEquals("[fe80:***]:50829",
                KeepADBPreferences.maskEndpointForDisplay(context, "[fe80::1%wlan0]:50829"));
        assertEquals("http://100.*.*.*:50829/register/s20",
                KeepADBPreferences.maskWebhookUrlForDisplay(
                        context, "http://100.111.111.21:50829/register/s20"));
        assertEquals("https://register.example:50829/register/s20",
                KeepADBPreferences.maskWebhookUrlForDisplay(
                        context, "https://register.example:50829/register/s20"));
    }

    /** #483: masking is display-only — the stored originals must survive a toggle round trip. */
    @Test
    public void testPrivacyModeDoesNotAlterStoredValues() {
        FakeContext context = new FakeContext();
        String url = "http://100.111.111.21:50829/register/s20";
        KeepADBPreferences.setRegisterWebhookUrl(context, url);
        KeepADBPreferences.setWebhookLastReportedEndpoint(context, "192.168.1.100:5555");
        KeepADBPreferences.setPrivacyModeEnabled(context, true);
        KeepADBPreferences.maskWebhookUrlForDisplay(
                context, KeepADBPreferences.getRegisterWebhookUrl(context));
        KeepADBPreferences.maskEndpointForDisplay(
                context, KeepADBPreferences.getWebhookLastReportedEndpoint(context));
        assertEquals(url, KeepADBPreferences.getRegisterWebhookUrl(context));
        assertEquals("192.168.1.100:5555",
                KeepADBPreferences.getWebhookLastReportedEndpoint(context));
        KeepADBPreferences.setPrivacyModeEnabled(context, false);
        assertEquals(url, KeepADBPreferences.getRegisterWebhookUrl(context));
        assertEquals("192.168.1.100:5555",
                KeepADBPreferences.getWebhookLastReportedEndpoint(context));
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
