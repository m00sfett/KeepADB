package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * #483 regression tests: IPv4 and IPv6 masking, port visibility, hostname pass-through, and both
 * states of the privacy toggle (via {@link KeepADBUrlRedaction#forDisplay(String, boolean)}, the
 * shared entry point for the webhook URL).
 */
public class KeepADBAddressMaskTest {

    // --- IPv4 --------------------------------------------------------------

    @Test
    public void ipv4LiteralKeepsOnlyFirstOctet() {
        assertEquals("192.*.*.*", KeepADBAddressMask.maskHost("192.168.1.100"));
        assertEquals("100.*.*.*", KeepADBAddressMask.maskHost("100.111.111.21"));
        assertEquals("10.*.*.*", KeepADBAddressMask.maskHost("10.0.0.1"));
    }

    @Test
    public void ipv4EndpointKeepsPortVisible() {
        assertEquals("192.*.*.*:50829",
                KeepADBAddressMask.maskEndpointForDisplay("192.168.1.100:50829"));
        assertEquals("100.*.*.*:5555",
                KeepADBAddressMask.maskEndpointForDisplay("100.111.111.21:5555"));
    }

    @Test
    public void ipv4WithoutPortIsStillMasked() {
        assertEquals("192.*.*.*", KeepADBAddressMask.maskEndpointForDisplay("192.168.1.100"));
    }

    // --- IPv6 --------------------------------------------------------------

    @Test
    public void ipv6KeepsOnlyFirstGroup() {
        assertEquals("fe80:***", KeepADBAddressMask.maskHost("fe80::1"));
        assertEquals("2001:***", KeepADBAddressMask.maskHost("2001:db8:85a3::8a2e:370:7334"));
    }

    @Test
    public void ipv6ZoneIdIsMaskedAway() {
        assertEquals("fe80:***", KeepADBAddressMask.maskHost("fe80::1%wlan0"));
    }

    @Test
    public void ipv6WithoutLeadingGroupRevealsNothing() {
        assertEquals("***", KeepADBAddressMask.maskHost("::1"));
    }

    @Test
    public void bracketedIpv6EndpointKeepsBracketsAndPort() {
        assertEquals("[fe80:***]:50829",
                KeepADBAddressMask.maskEndpointForDisplay("[fe80::1%wlan0]:50829"));
        assertEquals("[2001:***]:5555",
                KeepADBAddressMask.maskEndpointForDisplay("[2001:db8::1]:5555"));
    }

    @Test
    public void unbracketedIpv6WithoutPortIsMasked() {
        assertEquals("fe80:***", KeepADBAddressMask.maskEndpointForDisplay("fe80::1"));
    }

    // --- Hostnames ---------------------------------------------------------

    @Test
    public void hostnamesAreNeverMasked() {
        assertEquals("register.example", KeepADBAddressMask.maskHost("register.example"));
        assertEquals("phone-register.local",
                KeepADBAddressMask.maskHost("phone-register.local"));
        assertEquals("register.example:50829",
                KeepADBAddressMask.maskEndpointForDisplay("register.example:50829"));
    }

    @Test
    public void nullAndEmptyPassThrough() {
        assertEquals(null, KeepADBAddressMask.maskHost(null));
        assertEquals("", KeepADBAddressMask.maskHost(""));
        assertEquals(null, KeepADBAddressMask.maskEndpointForDisplay(null));
        assertEquals("", KeepADBAddressMask.maskEndpointForDisplay(""));
    }

    // --- Both toggle states on the webhook URL ------------------------------

    @Test
    public void privacyOffKeepsTheExistingRedactionUnchanged() {
        assertEquals("http://100.111.***.**:50829/register/s20",
                KeepADBUrlRedaction.forDisplay("http://100.111.111.21:50829/register/s20", false));
        assertEquals("http://100.111.***.**:50829/register/s20",
                KeepADBUrlRedaction.forDisplay("http://100.111.111.21:50829/register/s20"));
    }

    @Test
    public void privacyOnKeepsOnlyFirstOctetOfWebhookHost() {
        assertEquals("http://100.*.*.*:50829/register/s20",
                KeepADBUrlRedaction.forDisplay("http://100.111.111.21:50829/register/s20", true));
    }

    @Test
    public void privacyOnKeepsPortAndPathOfWebhookUrl() {
        assertEquals("http://192.*.*.*:8080/register/rolfphone",
                KeepADBUrlRedaction.forDisplay("http://192.168.1.100:8080/register/rolfphone", true));
    }

    @Test
    public void privacyOnLeavesWebhookHostnamesReadable() {
        assertEquals("https://register.example:50829/register/s20",
                KeepADBUrlRedaction.forDisplay("https://register.example:50829/register/s20", true));
    }

    @Test
    public void privacyOnNeverRevealsMoreThanPrivacyOffForIpv6() {
        // The #350 redaction already collapses every IPv6 literal in a URL; privacy mode must not
        // loosen that, so the first-group rule applies to the endpoint surfaces only.
        assertEquals("http://[***]:50829/register/s20",
                KeepADBUrlRedaction.forDisplay("http://[fe80::1%wlan0]:50829/register/s20", true));
    }

    @Test
    public void privacyOnStillCanonicalisesLegacyIpv4Notation() {
        assertEquals("http://192.*.*.*:8080/x",
                KeepADBUrlRedaction.forDisplay("http://0xC0A80001:8080/x", true));
    }
}
