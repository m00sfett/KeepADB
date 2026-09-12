package de.hohnepeople.keepadb;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Regression coverage for issue #348: {@link KeepADBTrustedNetwork#MODE_ALL_WIFI} is a trust
 * decision about which network is acceptable for automatic re-enable; it says nothing about
 * whether the device is actually on a Wi-Fi transport right now -- Wi-Fi can drop in the
 * background while the mode stays set. {@link KeepADBService#isAutoEnableStillPermitted} and
 * {@link KeepADBUsbHandover#isAutoHandoverStillPermitted} -- the two automatic-re-enable guards
 * re-evaluated at debounced write time -- must both require an actually connected Wi-Fi
 * transport in addition to the trusted-network decision, matching the invariant {@code
 * KeepADBEndpoint#maybeSendRecoveryPulse} already established for the recovery pulse (#296).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBAutoEnableWifiTransportGateTest {

    private final Context context = RuntimeEnvironment.getApplication();

    @Before
    public void setUp() {
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        KeepADBTrustedNetwork.resetVerifiedTrustForTesting();
        KeepADBNetwork.resetForTesting();
        KeepADB.resetForTesting();
    }

    @After
    public void tearDown() {
        KeepADBNetwork.resetForTesting();
    }

    @Test
    public void serviceGuardBlocksAllWifiModeWithoutAnActiveWifiTransport() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> false);

        assertFalse("MODE_ALL_WIFI must not substitute for an actually connected Wi-Fi transport",
                KeepADBService.isAutoEnableStillPermitted(context));
    }

    @Test
    public void serviceGuardAllowsAllWifiModeWithAnActiveWifiTransport() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);

        assertTrue("MODE_ALL_WIFI with an active Wi-Fi transport must still permit auto-enable",
                KeepADBService.isAutoEnableStillPermitted(context));
    }

    @Test
    public void serviceGuardStillBlocksAllowlistModeOnAnUntrustedNetworkEvenWithWifiConnected() {
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);

        assertFalse("An active Wi-Fi transport alone must not bypass the trusted-network allowlist",
                KeepADBService.isAutoEnableStillPermitted(context));
    }

    @Test
    public void usbHandoverGuardBlocksAllWifiModeWithoutAnActiveWifiTransport() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> false);

        assertFalse("MODE_ALL_WIFI must not substitute for an actually connected Wi-Fi transport",
                KeepADBUsbHandover.isAutoHandoverStillPermitted(context));
    }

    @Test
    public void usbHandoverGuardAllowsAllWifiModeWithAnActiveWifiTransport() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);

        assertTrue("MODE_ALL_WIFI with an active Wi-Fi transport must still permit the USB handover",
                KeepADBUsbHandover.isAutoHandoverStillPermitted(context));
    }
}
