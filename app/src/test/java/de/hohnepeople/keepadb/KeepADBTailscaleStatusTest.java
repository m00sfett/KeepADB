package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Test;

import de.hohnepeople.keepadb.KeepADBTailscaleStatus.InterfaceSnapshot;

/**
 * Unit tests for {@link KeepADBTailscaleStatus} (#537, #581).
 *
 * <p>#581 found that the previous version of this test only ever drove {@link
 * KeepADBTailscaleStatus.InterfaceActiveCheck} (a name-in/bool-out seam that replaced the *whole*
 * interface check, including the name filter), so it never actually exercised the real
 * name-and-address decision logic -- the bug (matching only the literal name {@code tailscale0},
 * never Android's real {@code tun0}) shipped with green tests. The fix cuts a pure decision
 * function, {@link KeepADBTailscaleStatus#looksLikeTailscale}/{@link
 * KeepADBTailscaleStatus#isTailscaleActive}, that judges a plain description of an interface
 * (name, up, addresses) -- see {@link InterfaceSnapshot} -- with no seam in between. These tests
 * call that decision logic directly, and drive {@link KeepADBTailscaleStatus#detect} only through
 * the {@link KeepADBTailscaleStatus.InterfacesProvider} seam, which supplies realistic interface
 * snapshots (including {@code tun0}) rather than a precomputed boolean.
 */
public class KeepADBTailscaleStatusTest {

    private static final byte[] CGNAT_ADDRESS = {100, 101, 2, 3};
    private static final byte[] TAILSCALE_ULA_ADDRESS =
            {(byte) 0xfd, 0x7a, 0x11, 0x5c, (byte) 0xa1, (byte) 0xe0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};
    private static final byte[] CARRIER_CGNAT_ADDRESS = {100, 72, 5, 9};
    private static final byte[] PRIVATE_ADDRESS = {(byte) 192, (byte) 168, 1, 1};

    @After
    public void tearDown() {
        KeepADBTailscaleStatus.setPackageInstalledCheckForTesting(null);
        KeepADBTailscaleStatus.setInterfacesProviderForTesting(null);
    }

    // -- detect() through the two seams --

    @Test
    public void notInstalledWhenPackageCheckReturnsFalse() {
        KeepADBTailscaleStatus.setPackageInstalledCheckForTesting((context, packageName) -> false);
        // The interface provider must not even be consulted once the package isn't installed.
        KeepADBTailscaleStatus.setInterfacesProviderForTesting(() -> {
            throw new AssertionError("must not be consulted when the package is not installed");
        });

        assertEquals(KeepADBTailscaleStatus.Status.NOT_INSTALLED,
                KeepADBTailscaleStatus.detect(null));
    }

    @Test
    public void activeWhenRealisticAndroidTunInterfaceCarriesCgnatAddress() {
        KeepADBTailscaleStatus.setPackageInstalledCheckForTesting((context, packageName) -> true);
        KeepADBTailscaleStatus.setInterfacesProviderForTesting(() -> Collections.singletonList(
                new InterfaceSnapshot("tun0", true, CGNAT_ADDRESS)));

        assertEquals(KeepADBTailscaleStatus.Status.ACTIVE, KeepADBTailscaleStatus.detect(null));
    }

    @Test
    public void inactiveWhenInstalledButNoInterfaceMatches() {
        KeepADBTailscaleStatus.setPackageInstalledCheckForTesting((context, packageName) -> true);
        KeepADBTailscaleStatus.setInterfacesProviderForTesting(Collections::emptyList);

        assertEquals(KeepADBTailscaleStatus.Status.INACTIVE, KeepADBTailscaleStatus.detect(null));
    }

    @Test
    public void unknownWhenPackageCheckItselfFails() {
        KeepADBTailscaleStatus.setPackageInstalledCheckForTesting((context, packageName) -> null);

        assertEquals(KeepADBTailscaleStatus.Status.UNKNOWN, KeepADBTailscaleStatus.detect(null));
    }

    @Test
    public void unknownWhenInterfaceEnumerationItselfFails() {
        KeepADBTailscaleStatus.setPackageInstalledCheckForTesting((context, packageName) -> true);
        KeepADBTailscaleStatus.setInterfacesProviderForTesting(() -> null);

        assertEquals(KeepADBTailscaleStatus.Status.UNKNOWN, KeepADBTailscaleStatus.detect(null));
    }

    @Test
    public void unknownWhenPackageCheckThrows() {
        KeepADBTailscaleStatus.setPackageInstalledCheckForTesting((context, packageName) -> {
            throw new RuntimeException("simulated platform failure");
        });

        assertEquals(KeepADBTailscaleStatus.Status.UNKNOWN, KeepADBTailscaleStatus.detect(null));
    }

    @Test
    public void unknownWhenInterfaceProviderThrows() {
        KeepADBTailscaleStatus.setPackageInstalledCheckForTesting((context, packageName) -> true);
        KeepADBTailscaleStatus.setInterfacesProviderForTesting(() -> {
            throw new RuntimeException("simulated platform failure");
        });

        assertEquals(KeepADBTailscaleStatus.Status.UNKNOWN, KeepADBTailscaleStatus.detect(null));
    }

    @Test
    public void packageCheckReceivesTheDocumentedPackageName() {
        String[] seenPackage = new String[1];
        KeepADBTailscaleStatus.setPackageInstalledCheckForTesting((context, packageName) -> {
            seenPackage[0] = packageName;
            return false;
        });

        KeepADBTailscaleStatus.detect(null);

        assertEquals("com.tailscale.ipn", seenPackage[0]);
    }

    // -- looksLikeTailscale()/isTailscaleActive(): the real, previously-untested decision logic --

    @Test
    public void tun0WithCgnatAddressLooksLikeTailscale() {
        // The exact real-world shape from #581's device report: Android's Tailscale app on tun0.
        assertTrue(KeepADBTailscaleStatus.looksLikeTailscale(
                new InterfaceSnapshot("tun0", true, CGNAT_ADDRESS)));
    }

    @Test
    public void tun0WithOnlyTailscaleUlaLooksLikeTailscale() {
        assertTrue(KeepADBTailscaleStatus.looksLikeTailscale(
                new InterfaceSnapshot("tun0", true, TAILSCALE_ULA_ADDRESS)));
    }

    @Test
    public void linuxStyleTailscale0WithCgnatAddressStillLooksLikeTailscale() {
        // The previous (#537) exact-match target must keep working.
        assertTrue(KeepADBTailscaleStatus.looksLikeTailscale(
                new InterfaceSnapshot("tailscale0", true, CGNAT_ADDRESS)));
    }

    @Test
    public void downTunnelInterfaceDoesNotLookLikeTailscaleEvenWithCgnatAddress() {
        assertFalse(KeepADBTailscaleStatus.looksLikeTailscale(
                new InterfaceSnapshot("tun0", false, CGNAT_ADDRESS)));
    }

    @Test
    public void foreignVpnOnTun0WithoutTailscaleAddressDoesNotLookLikeTailscale() {
        // Counter-proof #1: some other VPN app also happens to be named tun0 by the platform, but
        // its address is unrelated to Tailscale.
        assertFalse(KeepADBTailscaleStatus.looksLikeTailscale(
                new InterfaceSnapshot("tun0", true, PRIVATE_ADDRESS)));
    }

    @Test
    public void carrierCgnatOnRmnetDoesNotLookLikeTailscale() {
        // Counter-proof #2: a mobile carrier's own CGNAT deployment on the cellular data
        // interface can land in the very same 100.64.0.0/10 block Tailscale uses. The name filter
        // is what keeps this from being misreported as Tailscale.
        assertFalse(KeepADBTailscaleStatus.looksLikeTailscale(
                new InterfaceSnapshot("rmnet_data0", true, CARRIER_CGNAT_ADDRESS)));
    }

    @Test
    public void wlanWithCgnatLookingAddressDoesNotLookLikeTailscale() {
        assertFalse(KeepADBTailscaleStatus.looksLikeTailscale(
                new InterfaceSnapshot("wlan0", true, CARRIER_CGNAT_ADDRESS)));
    }

    @Test
    public void isTailscaleActiveIgnoresNonMatchingInterfacesAndFindsTheMatchingOne() {
        List<InterfaceSnapshot> interfaces = Arrays.asList(
                new InterfaceSnapshot("wlan0", true, PRIVATE_ADDRESS),
                new InterfaceSnapshot("rmnet_data0", true, CARRIER_CGNAT_ADDRESS),
                new InterfaceSnapshot("tun0", true, CGNAT_ADDRESS));

        assertTrue(KeepADBTailscaleStatus.isTailscaleActive(interfaces));
    }

    @Test
    public void isTailscaleActiveFalseWhenNoInterfaceMatches() {
        List<InterfaceSnapshot> interfaces = Arrays.asList(
                new InterfaceSnapshot("wlan0", true, PRIVATE_ADDRESS),
                new InterfaceSnapshot("rmnet_data0", true, CARRIER_CGNAT_ADDRESS));

        assertFalse(KeepADBTailscaleStatus.isTailscaleActive(interfaces));
    }

    // -- hasTunnelName() --

    @Test
    public void hasTunnelNameAcceptsAndroidTunPrefix() {
        assertTrue(KeepADBTailscaleStatus.hasTunnelName("tun0"));
        assertTrue(KeepADBTailscaleStatus.hasTunnelName("tun1"));
    }

    @Test
    public void hasTunnelNameAcceptsLinuxTailscalePrefix() {
        assertTrue(KeepADBTailscaleStatus.hasTunnelName("tailscale0"));
    }

    @Test
    public void hasTunnelNameRejectsCarrierAndWifiInterfaces() {
        assertFalse(KeepADBTailscaleStatus.hasTunnelName("rmnet_data0"));
        assertFalse(KeepADBTailscaleStatus.hasTunnelName("wlan0"));
        assertFalse(KeepADBTailscaleStatus.hasTunnelName(null));
    }

    // -- CGNAT (100.64.0.0/10) range boundary tests --

    @Test
    public void cgnatRangeAcceptsLowerBound() {
        assertTrue(KeepADBTailscaleStatus.isCgnatAddress(new byte[]{100, 64, 0, 0}));
    }

    @Test
    public void cgnatRangeAcceptsUpperBound() {
        assertTrue(KeepADBTailscaleStatus.isCgnatAddress(new byte[]{100, 127, (byte) 255, (byte) 255}));
    }

    @Test
    public void cgnatRangeRejectsJustBelowLowerBound() {
        assertFalse(KeepADBTailscaleStatus.isCgnatAddress(new byte[]{100, 63, (byte) 255, (byte) 255}));
    }

    @Test
    public void cgnatRangeRejectsJustAboveUpperBound() {
        assertFalse(KeepADBTailscaleStatus.isCgnatAddress(new byte[]{100, (byte) 128, 0, 0}));
    }

    @Test
    public void cgnatRangeRejectsPrivateRfc1918Address() {
        assertFalse(KeepADBTailscaleStatus.isCgnatAddress(new byte[]{(byte) 192, (byte) 168, 1, 1}));
    }

    @Test
    public void cgnatRangeRejectsNullOrWrongLength() {
        assertFalse(KeepADBTailscaleStatus.isCgnatAddress(null));
        assertFalse(KeepADBTailscaleStatus.isCgnatAddress(new byte[]{100, 64, 0}));
    }

    // -- Tailscale ULA (fd7a:115c:a1e0::/48) tests --

    @Test
    public void ulaMatchesTailscalePrefix() {
        assertTrue(KeepADBTailscaleStatus.isTailscaleUlaAddress(TAILSCALE_ULA_ADDRESS));
    }

    @Test
    public void ulaRejectsDifferentUlaPrefix() {
        byte[] otherUla = {(byte) 0xfd, 0x00, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};
        assertFalse(KeepADBTailscaleStatus.isTailscaleUlaAddress(otherUla));
    }

    @Test
    public void ulaRejectsNullOrWrongLength() {
        assertFalse(KeepADBTailscaleStatus.isTailscaleUlaAddress(null));
        assertFalse(KeepADBTailscaleStatus.isTailscaleUlaAddress(CGNAT_ADDRESS));
    }
}
