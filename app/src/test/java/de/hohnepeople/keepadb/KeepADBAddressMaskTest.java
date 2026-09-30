package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.Locale;

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
    public void privacyOffShowsTheFullWebhookHostUnredacted() {
        // #550: the open-eye (privacy off) state is an explicit "show me everything" request for
        // the webhook display, so the host is no longer partially masked here -- unlike the
        // one-argument forDisplay(String), which keeps the old #350 default masking for its own
        // (non-webhook) callers.
        assertEquals("http://100.111.111.21:50829/register/s20",
                KeepADBUrlRedaction.forDisplay("http://100.111.111.21:50829/register/s20", false));
    }

    @Test
    public void privacyOffShowsTheFullWebhookIpv6HostUnredacted() {
        assertEquals("http://[fe80::1%wlan0]:50829/register/s20",
                KeepADBUrlRedaction.forDisplay("http://[fe80::1%wlan0]:50829/register/s20", false));
    }

    @Test
    public void oneArgumentForDisplayKeepsTheOldDefaultMaskingRegardlessOfPrivacyToggle() {
        // The single-argument overload backs the legacy maskWebhookUrl() helper and is not on the
        // webhook display path (see KeepADBPreferences#maskWebhookUrlForDisplay). #550 only changes
        // forDisplay(String, boolean) for privacyMode == false; this overload's byte-identical
        // #350 behaviour must survive unchanged.
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
    public void privacyOnStillCollapsesIpv6Fully() {
        // Privacy mode's host rule is IPv4-only (first octet); an IPv6 literal collapses fully
        // either way, exactly as the pre-#550 default redaction did.
        assertEquals("http://[***]:50829/register/s20",
                KeepADBUrlRedaction.forDisplay("http://[fe80::1%wlan0]:50829/register/s20", true));
    }

    @Test
    public void privacyOnStillCanonicalisesLegacyIpv4Notation() {
        assertEquals("http://192.*.*.*:8080/x",
                KeepADBUrlRedaction.forDisplay("http://0xC0A80001:8080/x", true));
    }

    @Test
    public void privacyOffShowsLegacyIpv4NotationRawWithNoCanonicalisation() {
        // No redaction also means no canonicalisation pass -- privacy off is a "show the stored
        // value" mode, not a "show the stored value, normalised" mode.
        assertEquals("http://0xC0A80001:8080/x",
                KeepADBUrlRedaction.forDisplay("http://0xC0A80001:8080/x", false));
    }

    // --- #550: MainActivity toggle wiring ------------------------------------
    //
    // MainActivity's privacy-mode-toggle OnClickListener already calls refreshWebhookStatus()
    // synchronously right after flipping the preference (see MainActivity#onCreate, the
    // btn_toggle_privacy_mode click listener), and refreshWebhookStatus() is the sole call site of
    // KeepADBPreferences#maskWebhookUrlForDisplay -- the method this class's forDisplay(String,
    // boolean) tests stand in for. So the toggle-to-redisplay wiring was already correct before this
    // issue; only the redaction rule itself (this file's tests above) was wrong for privacyMode ==
    // false. Actually exercising that OnClickListener needs a live MainActivity (Robolectric or an
    // instrumented device run), which this module's plain JUnit + Android stub setup does not
    // provide -- verified instead by reading MainActivity.java rather than by an automated test.

    // --- BSSID (#654) -------------------------------------------------------

    @Test
    public void bssidKeepsOnlyTheFirstAndTheLastOctet() {
        assertEquals("AA:*:*:*:*:01", KeepADBAddressMask.maskBssid("AA:BB:CC:DD:EE:01"));
        assertEquals("AA:*:*:*:*:01", KeepADBAddressMask.maskBssid("aa:bb:cc:dd:ee:01"));
        assertEquals("02:*:*:*:*:5E", KeepADBAddressMask.maskBssid(" 02:1a:2b:3c:4d:5e "));
        assertEquals("The example of the user decision", "DE:*:*:*:*:AD",
                KeepADBAddressMask.maskBssid("de:11:22:33:44:ad"));
    }

    @Test
    public void theKeptOctetsAreAlwaysUppercase() {
        assertEquals("AA:*:*:*:*:FF", KeepADBAddressMask.maskBssid("aA:bB:cC:dD:eE:Ff"));
        assertEquals("AA:*:*:*:*:FF", KeepADBAddressMask.maskBssid("AA:11:22:33:44:FF"));
        assertEquals("AA:*:*:*:*:FF", KeepADBAddressMask.maskBssid("aa:11:22:33:44:ff"));
    }

    @Test
    public void aMaskedBssidNeverContainsAMiddleOctet() {
        String masked = KeepADBAddressMask.maskBssid("aa:bb:cc:dd:ee:01");
        for (String middle : new String[] {"bb", "cc", "dd", "ee"}) {
            assertFalse("Masked BSSID leaks '" + middle + "': " + masked, masked.contains(middle));
        }
    }

    /**
     * Both sides of the rule over every octet value: the first and the last octet decide the
     * output (hiding one of them is wrong), the four between them never do (showing one of them is
     * wrong). A test that only looked for the middle octets would stay green when the last octet
     * is masked again; one that only looked for the last would stay green when an extra octet leaks.
     */
    @Test
    public void onlyTheFirstAndTheLastOctetInfluenceTheMask() {
        String[] base = {"a0", "b1", "c2", "d3", "e4", "f5"};
        String baseMask = KeepADBAddressMask.maskBssid(String.join(":", base));
        assertEquals("A0:*:*:*:*:F5", baseMask);
        for (int position = 0; position < 6; position++) {
            for (int value = 0; value < 256; value++) {
                String[] octets = base.clone();
                octets[position] = String.format(Locale.ROOT, "%02x", value);
                String masked = KeepADBAddressMask.maskBssid(String.join(":", octets));
                if (position == 0) {
                    assertEquals(octets[0].toUpperCase(Locale.ROOT) + ":*:*:*:*:F5", masked);
                } else if (position == 5) {
                    assertEquals("A0:*:*:*:*:" + octets[5].toUpperCase(Locale.ROOT), masked);
                } else {
                    assertEquals("Octet " + position + " must not show up in the mask: " + masked,
                            baseMask, masked);
                }
            }
        }
    }

    @Test
    public void anythingThatIsNotASixOctetBssidIsMaskedCompletely() {
        for (String odd : new String[] {"aa:bb:cc", "aa:bb:cc:dd:ee", "aa:bb:cc:dd:ee:01:02",
                "aa-bb-cc-dd-ee-01", "zz:bb:cc:dd:ee:01", "aa:bb:cc:dd:ee:0", "aa:bb:cc:dd:ee:001",
                "not a bssid", "aabbccddee01", "aa:bb:cc:dd:ee:", ":bb:cc:dd:ee:01",
                "aa::cc:dd:ee:01", "aa:bb:cc:dd:ee:0g", "aa:bb:cc:dd:ee:-1", "aa:bb:cc:dd:ee:0 1",
                "aa:bb:cc:dd:ee:01:", "aa:bb:cc:dd:ee:ff:00:11"}) {
            assertEquals("Unexpected format must not leak: " + odd, "***",
                    KeepADBAddressMask.maskBssid(odd));
        }
    }

    @Test
    public void blankBssidsPassThroughWithoutInventingAMask() {
        assertEquals(null, KeepADBAddressMask.maskBssid(null));
        assertEquals("", KeepADBAddressMask.maskBssid(""));
        assertEquals("   ", KeepADBAddressMask.maskBssid("   "));
    }
}
