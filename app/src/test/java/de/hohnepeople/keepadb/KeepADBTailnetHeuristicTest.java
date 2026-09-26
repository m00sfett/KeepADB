package de.hohnepeople.keepadb;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * #561 regression tests: the CGNAT-range and MagicDNS-suffix heuristic that decides whether a
 * failed webhook call may show the "Tailscale might be disconnected" hint.
 */
public class KeepADBTailnetHeuristicTest {

    // --- Tailnet-looking targets: should classify as tailnet -----------------------------------

    @Test
    public void cgnatIpv4HostMatches() {
        assertTrue(KeepADBTailnetHeuristic.looksLikeTailnetTarget("http://100.64.0.1:8080/register/s20"));
        assertTrue(KeepADBTailnetHeuristic.looksLikeTailnetTarget("http://100.111.111.21:50829/register/s20"));
        assertTrue(KeepADBTailnetHeuristic.looksLikeTailnetTarget("http://100.127.255.255/register/s20"));
    }

    @Test
    public void magicDnsHostnameMatches() {
        assertTrue(KeepADBTailnetHeuristic.looksLikeTailnetTarget("http://phone-register.tailxyz.ts.net/register/s20"));
        assertTrue(KeepADBTailnetHeuristic.looksLikeTailnetTarget("https://foo.ts.net:50829/hook"));
    }

    @Test
    public void magicDnsHostnameMatchIsCaseInsensitive() {
        assertTrue(KeepADBTailnetHeuristic.looksLikeTailnetTarget("http://Foo.TS.NET/hook"));
    }

    // --- Non-tailnet targets: must fall back to the generic failure message --------------------

    @Test
    public void privateLanIpv4DoesNotMatch() {
        assertFalse(KeepADBTailnetHeuristic.looksLikeTailnetTarget("http://192.168.1.100:50829/register/s20"));
        assertFalse(KeepADBTailnetHeuristic.looksLikeTailnetTarget("http://10.0.0.5/register/s20"));
    }

    @Test
    public void publicHostnameDoesNotMatch() {
        assertFalse(KeepADBTailnetHeuristic.looksLikeTailnetTarget("https://example.com/register/s20"));
        assertFalse(KeepADBTailnetHeuristic.looksLikeTailnetTarget("https://hooks.example.org/register/s20"));
    }

    @Test
    public void ipv4JustOutsideCgnatRangeDoesNotMatch() {
        // 100.63.x.x and 100.128.x.x are outside 100.64.0.0/10.
        assertFalse(KeepADBTailnetHeuristic.looksLikeTailnetTarget("http://100.63.255.255/hook"));
        assertFalse(KeepADBTailnetHeuristic.looksLikeTailnetTarget("http://100.128.0.0/hook"));
    }

    @Test
    public void hostnameThatMerelyContainsTsNetDoesNotMatch() {
        // Must be a proper ".ts.net" suffix, not just a substring anywhere in the host.
        assertFalse(KeepADBTailnetHeuristic.looksLikeTailnetTarget("http://ts.net.example.com/hook"));
        assertFalse(KeepADBTailnetHeuristic.looksLikeTailnetTarget("http://notts.net/hook"));
    }

    @Test
    public void bareMagicDnsSuffixWithoutHostLabelDoesNotMatch() {
        assertFalse(KeepADBTailnetHeuristic.looksLikeTailnetTarget("http://ts.net/hook"));
    }

    // --- Malformed / null input: must never throw, always classify as "not tailnet" ------------

    @Test
    public void nullUrlDoesNotMatch() {
        assertFalse(KeepADBTailnetHeuristic.looksLikeTailnetTarget(null));
    }

    @Test
    public void blankUrlDoesNotMatch() {
        assertFalse(KeepADBTailnetHeuristic.looksLikeTailnetTarget("   "));
    }

    @Test
    public void unparseableUrlDoesNotMatch() {
        assertFalse(KeepADBTailnetHeuristic.looksLikeTailnetTarget("not a url at all"));
    }
}
