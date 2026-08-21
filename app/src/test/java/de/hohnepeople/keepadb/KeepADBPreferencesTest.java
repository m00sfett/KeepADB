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
}
