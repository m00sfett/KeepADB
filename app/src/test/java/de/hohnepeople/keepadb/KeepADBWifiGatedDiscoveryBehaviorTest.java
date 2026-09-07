package de.hohnepeople.keepadb;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * #303 entry step: a first real behavioral migration of the #296 discovery-skip scenario, sitting
 * alongside the still-active {@link KeepADBWifiGatedDiscoveryContractTest} (a source-content
 * contract) rather than replacing it -- see AGENTS.md/#286 on why both stay, and {@link
 * KeepADBWifiProbe}'s javadoc for why the seam that makes this possible lives on {@link
 * KeepADBNetwork} rather than on {@code KeepADBNotification} itself.
 *
 * <p>Unlike the contract test (which only proves the two relevant snippets exist in the right
 * source order), this drives {@link KeepADBNotification#refresh(Context)} end-to-end against a
 * genuine, Robolectric-shadowed {@link android.app.NotificationManager} (matching the {@link
 * KeepADBNotificationRobolectricTest} #286 entry step) and a deterministic Wi-Fi state, then
 * asserts on the actual runtime outcome: whether a discovery attempt was actually started ({@link
 * KeepADBNotification#hasActiveDiscoveryAttemptForTesting()}) and whether the
 * {@code endpoint_discovery_skipped} diagnostics event {@link KeepADBNotification#refreshInternal}
 * emits on the skip path was actually persisted -- not whether particular source text appears.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBWifiGatedDiscoveryBehaviorTest {

    private final Context context = org.robolectric.RuntimeEnvironment.getApplication();

    @Before
    public void grantNotificationPermission() {
        shadowOf((Application) context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
    }

    @After
    public void resetSharedState() {
        KeepADBNotification.resetForTesting();
        KeepADBNetwork.resetForTesting();
        KeepADB.resetForTesting();
    }

    @Test
    public void refreshNeverStartsDiscoveryWithoutAnActiveWifiConnection() {
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(true));
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> false);

        KeepADBNotification.refresh(context);

        assertFalse("startDiscoveryDirectLocked() must not have run while Wi-Fi is disconnected "
                        + "(#296) -- KeepADBEndpoint must never be instantiated for a doomed-to-fail "
                        + "discovery attempt",
                KeepADBNotification.hasActiveDiscoveryAttemptForTesting());
        assertTrue("refreshInternal() must record the skip as a real diagnostics event, not just "
                        + "silently no-op",
                KeepADBDiagnostics.export(context).contains("event=endpoint_discovery_skipped"));
    }

    @Test
    public void refreshStartsDiscoveryOnceWifiIsConnected() {
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(true));
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);

        KeepADBNotification.refresh(context);

        assertTrue("the connected case must still be reachable: startDiscoveryDirectLocked() must "
                        + "run and instantiate a real KeepADBEndpoint",
                KeepADBNotification.hasActiveDiscoveryAttemptForTesting());
        assertFalse("the connected case must never be misreported as skipped",
                KeepADBDiagnostics.export(context).contains("event=endpoint_discovery_skipped"));
    }
}
