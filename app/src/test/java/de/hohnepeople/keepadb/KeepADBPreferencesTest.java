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
        assertTrue(WebhookUrlCodec.isValid("http://100.111.111.21:50829/register/s20"));
        assertTrue(WebhookUrlCodec.isValid("http://localhost:8080/hook"));
        assertTrue(WebhookUrlCodec.isValid("http://example.com/api"));
        assertTrue(WebhookUrlCodec.isValid("  http://192.168.1.100:5000/register  "));
    }

    @Test
    public void testValidHttpsUrl() {
        assertTrue(WebhookUrlCodec.isValid("https://example.com/webhook"));
        assertTrue(WebhookUrlCodec.isValid("https://my-server.tailscale.net:8443/endpoint"));
    }

    @Test
    public void testInvalidUrls() {
        assertFalse(WebhookUrlCodec.isValid(null));
        assertFalse(WebhookUrlCodec.isValid(""));
        assertFalse(WebhookUrlCodec.isValid("   "));
        assertFalse(WebhookUrlCodec.isValid("ftp://example.com"));
        assertFalse(WebhookUrlCodec.isValid("not-a-url"));
        assertFalse(WebhookUrlCodec.isValid("http://"));
        assertFalse(WebhookUrlCodec.isValid("https://"));
        assertFalse(WebhookUrlCodec.isValid("://example.com"));
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

        assertEquals(WebhookReportPrefs.WEBHOOK_STATUS_NEVER,
                WebhookReportPrefs.getWebhookLastReportStatus(context));

        WebhookReportPrefs.setWebhookLastReportStatus(context, WebhookReportPrefs.WEBHOOK_STATUS_SUCCESS);
        assertEquals(WebhookReportPrefs.WEBHOOK_STATUS_SUCCESS,
                WebhookReportPrefs.getWebhookLastReportStatus(context));

        WebhookReportPrefs.setWebhookLastReportStatus(context, WebhookReportPrefs.WEBHOOK_STATUS_FAILED);
        assertEquals(WebhookReportPrefs.WEBHOOK_STATUS_FAILED,
                WebhookReportPrefs.getWebhookLastReportStatus(context));

        WebhookReportPrefs.setWebhookLastReportStatus(context, WebhookReportPrefs.WEBHOOK_STATUS_DEREGISTERED);
        assertEquals(WebhookReportPrefs.WEBHOOK_STATUS_DEREGISTERED,
                WebhookReportPrefs.getWebhookLastReportStatus(context));

        // Existing installations have no status key; infer the old success/deregistration state.
        WebhookReportPrefs.setWebhookLastReportStatus(context, null);
        WebhookReportPrefs.setWebhookLastReportedEndpoint(context, "192.0.2.10:40000");
        assertEquals(WebhookReportPrefs.WEBHOOK_STATUS_SUCCESS,
                WebhookReportPrefs.getWebhookLastReportStatus(context));
        WebhookReportPrefs.setWebhookLastReportedEndpoint(context, null);
        WebhookReportPrefs.setWebhookLastReportedAtNow(context);
        assertEquals(WebhookReportPrefs.WEBHOOK_STATUS_DEREGISTERED,
                WebhookReportPrefs.getWebhookLastReportStatus(context));
    }

    @Test
    public void testSanitizeWebhookUrlStripsUserinfoAndFragment() {
        assertEquals("http://100.111.111.21:50829/register/s20?foo=bar",
                WebhookUrlCodec.sanitize(
                        "http://user:pass@100.111.111.21:50829/register/s20?foo=bar#section"));
        assertEquals("http://example.com/test",
                WebhookUrlCodec.sanitize("HTTP://user:pass@example.com/test#fragment"));
        assertEquals("http://example.com:8080/path",
                WebhookUrlCodec.sanitize("http://user@example.com:8080/path"));
        assertEquals("http://example.com:8080/path",
                WebhookUrlCodec.sanitize("http://:pass@example.com:8080/path"));
        assertEquals("https://example.com/api",
                WebhookUrlCodec.sanitize("https://example.com/api#section"));
        assertEquals("http://example.com",
                WebhookUrlCodec.sanitize("http://example.com"));
        assertNull(WebhookUrlCodec.sanitize(null));
        assertEquals("", WebhookUrlCodec.sanitize(""));
        assertEquals("", WebhookUrlCodec.sanitize("   "));
        assertEquals("not-a-url", WebhookUrlCodec.sanitize("not-a-url"));
        assertEquals("http://", WebhookUrlCodec.sanitize("http://user:pass@"));
        assertEquals("http://example.com/api?email=alice@example.com",
                WebhookUrlCodec.sanitize("http://user:pass@example.com/api?email=alice@example.com#section"));
        assertEquals("http://example.com?email=alice@example.com",
                WebhookUrlCodec.sanitize("http://user:pass@example.com?email=alice@example.com#section"));
        // A legacy URL with an embedded '@' in its password and a non-standard IPv6 zone id takes
        // the manual fallback path. The complete userinfo must still be removed.
        assertEquals("http://[fe80::1%wlan0]:8443/register",
                WebhookUrlCodec.sanitize(
                        "http://user:p@ss@[fe80::1%wlan0]:8443/register"));
        assertEquals("http://[fe80::1%wlan0]:8443/register?email=x@y",
                WebhookUrlCodec.sanitize(
                        "http://user:p@ss@[fe80::1%wlan0]:8443/register?email=x@y"));
        assertNull(WebhookUrlCodec.sanitize("http://legacy.example/register\nnext"));
    }

    @Test
    public void testMaskWebhookUrlMasksIpv4AndRemovesUserinfo() {
        // #350: the query string used to be printed verbatim here ("?foo=bar").
        assertEquals("http://100.111.***.**:50829/register/s20?***",
                KeepADBUrlRedaction.forDisplay(
                        "http://user:pass@100.111.111.21:50829/register/s20?foo=bar#hash"));
        assertEquals("http://100.111.***.**:50829/register/s20",
                KeepADBUrlRedaction.forDisplay("http://100.111.111.21:50829/register/s20"));
        assertEquals("http://192.168.*.***:8080/endpoint",
                KeepADBUrlRedaction.forDisplay("http://192.168.1.100:8080/endpoint"));
        assertEquals("http://10.0.*.*",
                KeepADBUrlRedaction.forDisplay("http://10.0.0.1"));
        assertEquals("https://example.com/webhook",
                KeepADBUrlRedaction.forDisplay("https://user:pass@example.com/webhook#secret"));
        assertEquals("", KeepADBUrlRedaction.forDisplay(null));
        assertEquals("", KeepADBUrlRedaction.forDisplay(""));
        assertEquals("", KeepADBUrlRedaction.forDisplay("   "));
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
                WebhookReportPrefs.getWebhookLastReportedUrl(context));
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
                KeepADBUrlRedaction.forDisplay(
                        WebhookReportPrefs.getWebhookLastReportedUrl(context)));
    }

    /** #483: with the privacy toggle off, every display value stays exactly as before. */
    @Test
    public void testPrivacyModeOffLeavesDisplayValuesUntouched() {
        FakeContext context = new FakeContext();
        KeepADBPreferences.setAppPrivacyEnabled(context, false);
        assertFalse(KeepADBPreferences.isAppPrivacyEnabled(context));
        assertEquals("192.168.1.100",
                KeepADBPrivacy.maskHostForDisplay(context, "192.168.1.100"));
        assertEquals("[fe80::1%wlan0]:50829",
                KeepADBPrivacy.maskEndpointForDisplay(context, "[fe80::1%wlan0]:50829"));
        // #550: privacy mode off is now a "show it all" state for the webhook display too --
        // it no longer keeps the #350 default two-octet host mask.
        assertEquals("http://100.111.111.21:50829/register/s20",
                KeepADBPrivacy.maskWebhookUrlForDisplay(
                        context, "http://100.111.111.21:50829/register/s20"));
    }

    /** #509: a new installation starts with privacy masking disabled (addresses fully visible). */
    @Test
    public void testPrivacyModeDefaultsToDisabled() {
        assertFalse(KeepADBPreferences.isAppPrivacyEnabled(new FakeContext()));
    }

    /** #483: with the privacy toggle on, addresses are masked and ports stay readable. */
    @Test
    public void testPrivacyModeOnMasksAddressesButKeepsPortsAndMasksTheWebhookHostName() {
        FakeContext context = new FakeContext();
        KeepADBPreferences.setAppPrivacyEnabled(context, true);
        assertTrue(KeepADBPreferences.isAppPrivacyEnabled(context));
        assertEquals("192.*.*.*", KeepADBPrivacy.maskHostForDisplay(context, "192.168.1.100"));
        assertEquals("192.*.*.*:5555",
                KeepADBPrivacy.maskEndpointForDisplay(context, "192.168.1.100:5555"));
        assertEquals("[fe80:***]:50829",
                KeepADBPrivacy.maskEndpointForDisplay(context, "[fe80::1%wlan0]:50829"));
        assertEquals("http://100.*.*.*:50829/register/s20",
                KeepADBPrivacy.maskWebhookUrlForDisplay(
                        context, "http://100.111.111.21:50829/register/s20"));
        // #904: a registered name in the webhook address is masked too; the port stays.
        assertEquals("https://***:50829/register/s20",
                KeepADBPrivacy.maskWebhookUrlForDisplay(
                        context, "https://register.example:50829/register/s20"));
    }

    /** #904: the app display masks a registered name in every host rendering; off, nothing changes. */
    @Test
    public void testPrivacyModeMasksRegisteredNamesInEndpointAndHostRenderings() {
        FakeContext context = new FakeContext();
        KeepADBPreferences.setAppPrivacyEnabled(context, false);
        assertEquals("phone.tailnet.ts.net:41234",
                KeepADBPrivacy.maskEndpointForDisplay(context, "phone.tailnet.ts.net:41234"));
        assertEquals("phone.tailnet.ts.net",
                KeepADBPrivacy.maskHostForDisplay(context, "phone.tailnet.ts.net"));

        KeepADBPreferences.setAppPrivacyEnabled(context, true);
        assertEquals("***:41234",
                KeepADBPrivacy.maskEndpointForDisplay(context, "phone.tailnet.ts.net:41234"));
        assertEquals("***", KeepADBPrivacy.maskHostForDisplay(context, "phone.tailnet.ts.net"));
    }

    /** #483: masking is display-only — the stored originals must survive a toggle round trip. */
    @Test
    public void testPrivacyModeDoesNotAlterStoredValues() {
        FakeContext context = new FakeContext();
        String url = "http://100.111.111.21:50829/register/s20";
        KeepADBPreferences.setRegisterWebhookUrl(context, url);
        WebhookReportPrefs.setWebhookLastReportedEndpoint(context, "192.168.1.100:5555");
        KeepADBPreferences.setAppPrivacyEnabled(context, true);
        KeepADBPrivacy.maskWebhookUrlForDisplay(
                context, KeepADBPreferences.getRegisterWebhookUrl(context));
        KeepADBPrivacy.maskEndpointForDisplay(
                context, WebhookReportPrefs.getWebhookLastReportedEndpoint(context));
        assertEquals(url, KeepADBPreferences.getRegisterWebhookUrl(context));
        assertEquals("192.168.1.100:5555",
                WebhookReportPrefs.getWebhookLastReportedEndpoint(context));
        KeepADBPreferences.setAppPrivacyEnabled(context, false);
        assertEquals(url, KeepADBPreferences.getRegisterWebhookUrl(context));
        assertEquals("192.168.1.100:5555",
                WebhookReportPrefs.getWebhookLastReportedEndpoint(context));
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
