package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

/**
 * Unit tests for {@link KeepADBTailscaleStatus} (#537). Drives {@link
 * KeepADBTailscaleStatus#detect} through the two injectable seams so the four acceptance-criteria
 * states -- not installed, installed and active, installed but inactive, unknown -- are each
 * deterministic ordinary JVM tests, independent of whether this machine happens to have a real
 * {@code tailscale0} interface or the Tailscale app installed.
 */
public class KeepADBTailscaleStatusTest {

    @After
    public void tearDown() {
        KeepADBTailscaleStatus.setPackageInstalledCheckForTesting(null);
        KeepADBTailscaleStatus.setInterfaceActiveCheckForTesting(null);
    }

    @Test
    public void notInstalledWhenPackageCheckReturnsFalse() {
        KeepADBTailscaleStatus.setPackageInstalledCheckForTesting((context, packageName) -> false);
        // The interface check must not even matter once the package isn't installed.
        KeepADBTailscaleStatus.setInterfaceActiveCheckForTesting(name -> {
            throw new AssertionError("must not be consulted when the package is not installed");
        });

        assertEquals(KeepADBTailscaleStatus.Status.NOT_INSTALLED,
                KeepADBTailscaleStatus.detect(null));
    }

    @Test
    public void activeWhenInstalledAndInterfaceUpWithCgnatAddress() {
        KeepADBTailscaleStatus.setPackageInstalledCheckForTesting((context, packageName) -> true);
        KeepADBTailscaleStatus.setInterfaceActiveCheckForTesting(name -> true);

        assertEquals(KeepADBTailscaleStatus.Status.ACTIVE, KeepADBTailscaleStatus.detect(null));
    }

    @Test
    public void inactiveWhenInstalledButInterfaceNotActive() {
        KeepADBTailscaleStatus.setPackageInstalledCheckForTesting((context, packageName) -> true);
        KeepADBTailscaleStatus.setInterfaceActiveCheckForTesting(name -> false);

        assertEquals(KeepADBTailscaleStatus.Status.INACTIVE, KeepADBTailscaleStatus.detect(null));
    }

    @Test
    public void unknownWhenPackageCheckItselfFails() {
        KeepADBTailscaleStatus.setPackageInstalledCheckForTesting((context, packageName) -> null);

        assertEquals(KeepADBTailscaleStatus.Status.UNKNOWN, KeepADBTailscaleStatus.detect(null));
    }

    @Test
    public void unknownWhenInterfaceCheckItselfFails() {
        KeepADBTailscaleStatus.setPackageInstalledCheckForTesting((context, packageName) -> true);
        KeepADBTailscaleStatus.setInterfaceActiveCheckForTesting(name -> null);

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
    public void unknownWhenInterfaceCheckThrows() {
        KeepADBTailscaleStatus.setPackageInstalledCheckForTesting((context, packageName) -> true);
        KeepADBTailscaleStatus.setInterfaceActiveCheckForTesting(name -> {
            throw new RuntimeException("simulated platform failure");
        });

        assertEquals(KeepADBTailscaleStatus.Status.UNKNOWN, KeepADBTailscaleStatus.detect(null));
    }

    @Test
    public void interfaceCheckReceivesTheDocumentedInterfaceName() {
        KeepADBTailscaleStatus.setPackageInstalledCheckForTesting((context, packageName) -> true);
        String[] seenName = new String[1];
        KeepADBTailscaleStatus.setInterfaceActiveCheckForTesting(name -> {
            seenName[0] = name;
            return true;
        });

        KeepADBTailscaleStatus.detect(null);

        assertEquals("tailscale0", seenName[0]);
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
}
