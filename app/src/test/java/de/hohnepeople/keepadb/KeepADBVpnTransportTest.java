package de.hohnepeople.keepadb;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.InetAddress;

import org.junit.Test;

/**
 * Pure unit coverage for the product-neutral VPN address filter (#877) and #538's
 * reachability-probe wiring, independent of any Robolectric/ConnectivityManager scaffolding (see
 * {@code KeepADBTransportOverviewTest} for the end-to-end aggregation scenarios).
 */
public class KeepADBVpnTransportTest {

    @Test
    public void anyRoutableIpv4AddressIsUsableRegardlessOfVpnProduct() throws Exception {
        assertTrue("100.64.0.0/10 (Tailscale, NetBird, ...)", isUsable("100.101.2.3"));
        assertTrue("RFC1918 (WireGuard, corporate VPN, ...)", isUsable("10.8.0.2"));
        assertTrue(isUsable("192.168.200.1"));
        assertTrue(isUsable("172.16.5.5"));
    }

    @Test
    public void wildcardLoopbackAndLinkLocalAddressesAreRejected() throws Exception {
        assertFalse(isUsable("0.0.0.0"));
        assertFalse(isUsable("127.0.0.1"));
        assertFalse(isUsable("169.254.1.1"));
    }

    @Test
    public void ipv6AddressesAreLeftOut() throws Exception {
        assertFalse(KeepADBVpnTransport.isUsableVpnAddress(InetAddress.getByName("fe80::1")));
        assertFalse(KeepADBVpnTransport.isUsableVpnAddress(InetAddress.getByName("fd7a:115c:a1e0::1")));
        assertFalse(KeepADBVpnTransport.isUsableVpnAddress(null));
    }

    @Test
    public void verifyAdbReachableRejectsAMissingHostOrPortWithoutProbing() {
        // No probe override installed -- if this ever tried a real socket connect it would still
        // correctly return false for these inputs, but asserting it here pins the fast-reject
        // path as not even reaching the probe.
        assertFalse(KeepADBVpnTransport.verifyAdbReachable(null, 5555));
        assertFalse(KeepADBVpnTransport.verifyAdbReachable("10.8.0.2", 0));
        assertFalse(KeepADBVpnTransport.verifyAdbReachable("10.8.0.2", -1));
    }

    @Test
    public void verifyAdbReachableDelegatesToTheInstalledProbe() {
        try {
            KeepADBVpnTransport.setReachabilityProbeForTesting(
                    (host, port, timeoutMs) -> "10.8.0.2".equals(host) && port == 5555);

            assertTrue(KeepADBVpnTransport.verifyAdbReachable("10.8.0.2", 5555));
            assertFalse(KeepADBVpnTransport.verifyAdbReachable("10.8.0.2", 5556));
        } finally {
            KeepADBVpnTransport.setReachabilityProbeForTesting(null);
        }
    }

    private static boolean isUsable(String ipv4) throws Exception {
        return KeepADBVpnTransport.isUsableVpnAddress(InetAddress.getByName(ipv4));
    }
}
