package de.hohnepeople.keepadb;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.Inet6Address;
import java.net.InetAddress;

import org.junit.Test;

/**
 * Pure unit coverage for #538's Tailscale CGNAT-range detection and reachability-probe wiring,
 * independent of any Robolectric/ConnectivityManager scaffolding (see
 * {@code KeepADBTransportOverviewTest} for the end-to-end aggregation scenarios).
 */
public class KeepADBVpnTransportTest {

    @Test
    public void addressesInsideTheDocumentedCgnatRangeAreRecognized() throws Exception {
        assertTrue(isTailscale("100.64.0.0"));
        assertTrue(isTailscale("100.100.5.5"));
        assertTrue(isTailscale("100.127.255.255"));
    }

    @Test
    public void addressesOutsideTheCgnatRangeAreRejected() throws Exception {
        assertFalse("Just below the range", isTailscale("100.63.255.255"));
        assertFalse("Just above the range", isTailscale("100.128.0.0"));
        assertFalse("Private RFC1918, not Tailscale", isTailscale("192.168.1.5"));
        assertFalse("Different first octet entirely", isTailscale("10.100.0.1"));
    }

    @Test
    public void ipv6AddressesAreNeverTreatedAsTailscale() throws Exception {
        InetAddress ipv6 = Inet6Address.getByName("fe80::1");
        assertFalse(KeepADBVpnTransport.isTailscaleCgnatAddress(ipv6));
    }

    @Test
    public void verifyAdbReachableRejectsAMissingHostOrPortWithoutProbing() {
        // No probe override installed -- if this ever tried a real socket connect it would still
        // correctly return false for these inputs, but asserting it here pins the fast-reject
        // path as not even reaching the probe.
        assertFalse(KeepADBVpnTransport.verifyAdbReachable(null, 5555));
        assertFalse(KeepADBVpnTransport.verifyAdbReachable("100.64.1.2", 0));
        assertFalse(KeepADBVpnTransport.verifyAdbReachable("100.64.1.2", -1));
    }

    @Test
    public void verifyAdbReachableDelegatesToTheInstalledProbe() {
        try {
            KeepADBVpnTransport.setReachabilityProbeForTesting(
                    (host, port, timeoutMs) -> "100.64.1.2".equals(host) && port == 5555);

            assertTrue(KeepADBVpnTransport.verifyAdbReachable("100.64.1.2", 5555));
            assertFalse(KeepADBVpnTransport.verifyAdbReachable("100.64.1.2", 5556));
        } finally {
            KeepADBVpnTransport.setReachabilityProbeForTesting(null);
        }
    }

    private static boolean isTailscale(String ipv4) throws Exception {
        return KeepADBVpnTransport.isTailscaleCgnatAddress(InetAddress.getByName(ipv4));
    }
}
