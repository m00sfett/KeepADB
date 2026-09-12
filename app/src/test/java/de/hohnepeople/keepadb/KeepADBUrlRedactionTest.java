package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * #350: the one rule for turning a webhook URL into display or log text.
 *
 * <p>Each case names the component under test. A case that only asserts "no secret appears" would
 * stay green if the redactor started returning the empty string for everything, so every case also
 * pins the part that must survive -- scheme, first two IPv4 octets, port, path.
 */
public class KeepADBUrlRedactionTest {

    // ---- query --------------------------------------------------------------------------------

    @Test
    public void queryIsReplacedByAMarkerNotPrinted() {
        assertEquals("https://register.example/hook?***",
                KeepADBUrlRedaction.forDisplay("https://register.example/hook?token=s3cr3t&id=7"));
    }

    @Test
    public void emptyQueryStillMarksThatParametersWerePresent() {
        assertEquals("https://register.example/hook?***",
                KeepADBUrlRedaction.forDisplay("https://register.example/hook?"));
    }

    @Test
    public void absentQueryAddsNoMarker() {
        assertEquals("https://register.example/hook",
                KeepADBUrlRedaction.forDisplay("https://register.example/hook"));
    }

    @Test
    public void logVariantDropsPathAndQueryEntirely() {
        assertEquals("https://register.example:8443",
                KeepADBUrlRedaction.forLog("https://register.example:8443/hook?token=s3cr3t"));
    }

    // ---- IPv4 ---------------------------------------------------------------------------------

    @Test
    public void ipv4KeepsTwoOctetsAndMasksTheRestDigitWise() {
        assertEquals("http://100.111.***.**:50829/register/s20",
                KeepADBUrlRedaction.forDisplay("http://100.111.111.21:50829/register/s20"));
        assertEquals("http://192.168.*.***:8080/endpoint",
                KeepADBUrlRedaction.forDisplay("http://192.168.1.100:8080/endpoint"));
        assertEquals("http://10.0.*.*",
                KeepADBUrlRedaction.forDisplay("http://10.0.0.1"));
    }

    @Test
    public void dottedQuadOutOfRangeIsTreatedAsAName() {
        // 999 is not an octet, so this is a (weird) registered name, not an address to mask.
        assertEquals("http://10.0.0.999/x", KeepADBUrlRedaction.forDisplay("http://10.0.0.999/x"));
    }

    @Test
    public void alternateIpv4NotationsAreMaskedLikeTheirDottedAddress() {
        assertEquals("http://127.0.*.*/x",
                KeepADBUrlRedaction.forDisplay("http://2130706433/x"));
        assertEquals("http://127.0.*.*/x",
                KeepADBUrlRedaction.forDisplay("http://0x7f.0.0.1/x"));
        assertEquals("http://100.111.***.**/x",
                KeepADBUrlRedaction.forDisplay("http://100.111.111.21./x"));
    }

    @Test
    public void alternateIpv4NotationKeepsOtherRedactionRulesIndependent() {
        assertEquals("http://127.0.*.*/x?***",
                KeepADBUrlRedaction.forDisplay(
                        "http://user:pass@2130706433/x?token=secret#fragment"));
    }

    @Test
    public void trailingDotDnsNameRemainsVisible() {
        assertEquals("http://register.example./x",
                KeepADBUrlRedaction.forDisplay("http://register.example./x"));
    }

    // ---- control characters -------------------------------------------------------------------

    @Test
    public void controlCharactersAreEscapedInDisplayAndLogOutput() {
        String raw = "http://register\n.example/path\rname";
        assertEquals("http://register\\n.example/path\\rname",
                KeepADBUrlRedaction.forDisplay(raw));
        assertEquals("http://register\\n.example",
                KeepADBUrlRedaction.forLog(raw));
    }

    @Test
    public void otherControlCharactersAreEscapedAsUnicode() {
        assertEquals("http://register\\u0000.example/x",
                KeepADBUrlRedaction.forDisplay("http://register\u0000.example/x"));
    }

    // ---- IPv6 ---------------------------------------------------------------------------------

    @Test
    public void ipv6LoopbackIsFullyMasked() {
        assertEquals("http://[***]/register/s20",
                KeepADBUrlRedaction.forDisplay("http://[::1]/register/s20"));
    }

    @Test
    public void ipv6CompressedWithPortKeepsThePortAndLosesTheHost() {
        assertEquals("http://[***]:8080/x",
                KeepADBUrlRedaction.forDisplay("http://[2001:db8::1]:8080/x"));
    }

    @Test
    public void ipv6FullNotationIsMasked() {
        assertEquals("https://[***]:50829/register/s20",
                KeepADBUrlRedaction.forDisplay(
                        "https://[2001:0db8:0000:0000:0000:0000:0000:0001]:50829/register/s20"));
    }

    @Test
    public void ipv6WithRawZoneIdIsMasked() {
        // java.net.URI throws on the un-encoded '%', which is why the redactor parses by hand.
        assertEquals("http://[***]:8080/x",
                KeepADBUrlRedaction.forDisplay("http://[fe80::1%eth0]:8080/x"));
    }

    @Test
    public void ipv6WithEncodedZoneIdIsMasked() {
        assertEquals("http://[***]/x",
                KeepADBUrlRedaction.forDisplay("http://[fe80::1%25eth0]/x"));
    }

    @Test
    public void ipv4MappedIpv6IsMaskedAsIpv6NotAsIpv4() {
        assertEquals("http://[***]:80/x",
                KeepADBUrlRedaction.forDisplay("http://[::ffff:192.168.1.100]:80/x"));
    }

    @Test
    public void bareUnbracketedIpv6DropsHostAndAmbiguousPort() {
        assertEquals("http://[***]", KeepADBUrlRedaction.forDisplay("http://::1:8080"));
    }

    @Test
    public void unterminatedIpv6BracketIsNotPrintedAtAll() {
        assertEquals("[redacted-url]", KeepADBUrlRedaction.forDisplay("http://[2001:db8::1/x"));
    }

    // ---- userinfo -----------------------------------------------------------------------------

    @Test
    public void userinfoIsRemovedWithoutATrace() {
        assertEquals("https://register.example/webhook",
                KeepADBUrlRedaction.forDisplay("https://user:pass@register.example/webhook"));
    }

    @Test
    public void userinfoContainingAnAtSignIsRemovedCompletely() {
        assertEquals("https://register.example/webhook",
                KeepADBUrlRedaction.forDisplay("https://user:p@ss@register.example/webhook"));
    }

    @Test
    public void anAtSignInThePathIsNotMistakenForUserinfo() {
        assertEquals("https://register.example/mail@example.com",
                KeepADBUrlRedaction.forDisplay("https://register.example/mail@example.com"));
    }

    @Test
    public void userinfoBeforeAnIpv6HostIsRemoved() {
        assertEquals("http://[***]:50829/register/s20",
                KeepADBUrlRedaction.forDisplay("http://root:toor@[fe80::1%eth0]:50829/register/s20"));
    }

    // ---- fragment -----------------------------------------------------------------------------

    @Test
    public void fragmentIsRemovedWithoutAMarker() {
        assertEquals("https://register.example/hook",
                KeepADBUrlRedaction.forDisplay("https://register.example/hook#token=secret"));
    }

    @Test
    public void fragmentContainingAQuestionMarkDoesNotFakeAQuery() {
        assertEquals("https://register.example/hook",
                KeepADBUrlRedaction.forDisplay("https://register.example/hook#a?b=c"));
    }

    // ---- scheme, port, degenerate input -------------------------------------------------------

    @Test
    public void schemeIsLowerCasedAndKept() {
        assertEquals("https://register.example/hook",
                KeepADBUrlRedaction.forDisplay("HTTPS://register.example/hook"));
    }

    @Test
    public void nonHttpSchemeIsNotDecomposed() {
        assertEquals("[redacted-url]",
                KeepADBUrlRedaction.forDisplay("ftp://user:pass@register.example/hook"));
    }

    @Test
    public void trailingColonWithoutPortIsNotMistakenForAHost() {
        assertEquals("https://register.example/hook",
                KeepADBUrlRedaction.forDisplay("https://register.example:/hook"));
    }

    @Test
    public void emptyAuthorityIsNotPrinted() {
        assertEquals("[redacted-url]", KeepADBUrlRedaction.forDisplay("http://"));
        assertEquals("[redacted-url]", KeepADBUrlRedaction.forDisplay("http://user:pass@"));
    }

    @Test
    public void inputWithoutASchemeIsNotPrinted() {
        assertEquals("[redacted-url]", KeepADBUrlRedaction.forDisplay("not-a-url"));
        assertEquals("[redacted-url]", KeepADBUrlRedaction.forDisplay("://register.example"));
    }

    @Test
    public void blankInputStaysBlank() {
        assertEquals("", KeepADBUrlRedaction.forDisplay(null));
        assertEquals("", KeepADBUrlRedaction.forDisplay(""));
        assertEquals("", KeepADBUrlRedaction.forDisplay("   "));
        assertEquals("", KeepADBUrlRedaction.forLog(null));
    }

    @Test
    public void surroundingWhitespaceDoesNotDefeatParsing() {
        assertEquals("http://10.0.*.*:50829/x",
                KeepADBUrlRedaction.forDisplay("  http://10.0.0.1:50829/x  "));
    }

    // ---- historical stored values -------------------------------------------------------------

    /**
     * The shape an old {@code last_reported_url} can have: userinfo, a raw-zone IPv6 host, a query
     * and a fragment at once. Nothing but scheme, port and path may come out.
     */
    @Test
    public void legacyStoredValueLosesEveryConfidentialComponent() {
        String legacy = "HTTP://admin:hunter2@[fe80::1%eth0]:50829/register/s20?token=abc&pw=xyz#sec";
        assertEquals("http://[***]:50829/register/s20?***", KeepADBUrlRedaction.forDisplay(legacy));
        assertEquals("http://[***]:50829", KeepADBUrlRedaction.forLog(legacy));
    }
}
