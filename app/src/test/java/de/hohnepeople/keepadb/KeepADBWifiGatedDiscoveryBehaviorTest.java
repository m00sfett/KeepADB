package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.os.Looper;

import java.time.Duration;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * #303 entry step (later extended for #596): a real behavioral migration of the #296
 * discovery-skip scenario, sitting alongside the much-reduced {@link
 * KeepADBWifiGatedDiscoveryContractTest} (which now only keeps the one assertion this class
 * cannot practically replace -- see that class's javadoc) rather than a source-content contract
 * -- see AGENTS.md/#286 on why both stay, and {@link KeepADBWifiProbe}'s javadoc for why the seam
 * that makes this possible lives on {@link KeepADBNetwork} rather than on {@code
 * KeepADBNotification} itself.
 *
 * <p>Unlike a source-content contract (which only proves relevant snippets exist in the right
 * source order), this drives {@link KeepADBNotification#refresh(Context)} end-to-end against a
 * genuine, Robolectric-shadowed {@link android.app.NotificationManager} (matching the {@link
 * KeepADBNotificationRobolectricTest} #286 entry step) and a deterministic Wi-Fi state, then
 * asserts on the actual runtime outcome: whether a discovery attempt was actually started ({@link
 * KeepADBNotification#hasActiveDiscoveryAttemptForTesting()}) and whether the
 * {@code endpoint_discovery_skipped} diagnostics event {@link KeepADBNotification#refreshInternal}
 * emits on the skip path was actually persisted -- not whether particular source text appears.
 *
 * <p>#596 added the {@code scheduleRetryLocked*} group below: they drive a real discovery attempt
 * to {@code onUnavailable()} via the {@link KeepADBFakeNsdProbe}/{@link KeepADBFakeScheduler} seam
 * (matching {@link KeepADBNotificationRobolectricTest#notificationShowsDisabledWaitingWhenWirelessDebuggingDropsMidDiscovery()}),
 * then observes whether the real 2s/5s retry {@code Handler.postDelayed} chain actually fires a
 * second discovery attempt (via {@link KeepADBFakeNsdProbe#discoverServicesCallCount}) instead of
 * grepping {@code scheduleRetryLocked()}'s source for how many times it mentions the Wi-Fi check.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBWifiGatedDiscoveryBehaviorTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

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

    /**
     * #296/#596: the retry chain's own first check (before it ever schedules anything) must abort
     * without an active Wi-Fi connection -- otherwise a discovery that started while connected but
     * whose {@code onUnavailable()} fires after Wi-Fi already dropped mid-attempt would still queue
     * a pointless 2s retry. Asserted by the *absence* of a second discovery attempt after the
     * normal retry delay elapses, not by counting Wi-Fi-check occurrences in the source.
     */
    @Test
    public void retryNeverSchedulesASecondAttemptWhenWifiIsAlreadyDisconnectedWhenDiscoveryFails() {
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(true));
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        KeepADBFakeNsdProbe nsdProbe = new KeepADBFakeNsdProbe();
        KeepADBFakeScheduler scheduler = new KeepADBFakeScheduler();
        KeepADBEndpoint fakeEndpoint = new KeepADBEndpoint(context, nsdProbe, scheduler);
        KeepADBNotification.setEndpointForTesting(fakeEndpoint);

        KeepADBNotification.refresh(context);
        assertEquals("precondition: the first discovery attempt must have started",
                1, nsdProbe.discoverServicesCallCount);

        // Wi-Fi drops while the fake NSD discovery is still in flight; KeepADBEndpoint's own
        // OVERALL_TIMEOUT_MS watchdog then fires onUnavailable(), landing in scheduleRetryLocked()
        // with Wi-Fi already gone.
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> false);
        scheduler.advanceBy(8_000);

        // Advance well past the 2s initial retry delay: if scheduleRetryLocked() had queued a
        // retry anyway, this is where it would fire and start a second discovery attempt.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5_000));

        assertEquals("no retry may have been scheduled without an active Wi-Fi connection",
                1, nsdProbe.discoverServicesCallCount);
    }

    /**
     * #296/#596: the retry chain's *second* check -- inside the already-scheduled delayed
     * runnable itself -- must also abort if Wi-Fi drops in the gap between scheduling the retry
     * and the retry actually running, not just at scheduling time.
     */
    @Test
    public void retryAbortsInsideTheDelayedRunnableWhenWifiDropsAfterBeingScheduled() {
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(true));
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        KeepADBFakeNsdProbe nsdProbe = new KeepADBFakeNsdProbe();
        KeepADBFakeScheduler scheduler = new KeepADBFakeScheduler();
        KeepADBEndpoint fakeEndpoint = new KeepADBEndpoint(context, nsdProbe, scheduler);
        KeepADBNotification.setEndpointForTesting(fakeEndpoint);

        KeepADBNotification.refresh(context);
        assertEquals(1, nsdProbe.discoverServicesCallCount);

        // Discovery fails while Wi-Fi is still connected, so scheduleRetryLocked() does queue its
        // 2s retry this time.
        scheduler.advanceBy(8_000);

        // Wi-Fi drops only now, after scheduling but before the delayed runnable has run.
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> false);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5_000));

        assertEquals("the delayed retry runnable's own Wi-Fi check must abort it, so no second "
                        + "discovery attempt may have started",
                1, nsdProbe.discoverServicesCallCount);
    }

    /**
     * Positive control for the two tests above (AGENTS.md: guard both directions of a two-sided
     * invariant): with an active Wi-Fi connection throughout, the retry chain must still actually
     * fire and start a second discovery attempt after the delay -- otherwise a fix that made
     * scheduleRetryLocked() abort unconditionally would pass the two tests above for the wrong
     * reason.
     */
    @Test
    public void retryStartsASecondDiscoveryAttemptAfterTheDelayWhileWifiStaysConnected() {
        KeepADB.setGatewayForTesting(new KeepADBFakeSettingsGateway(true));
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        KeepADBFakeNsdProbe nsdProbe = new KeepADBFakeNsdProbe();
        KeepADBFakeScheduler scheduler = new KeepADBFakeScheduler();
        KeepADBEndpoint fakeEndpoint = new KeepADBEndpoint(context, nsdProbe, scheduler);
        KeepADBNotification.setEndpointForTesting(fakeEndpoint);

        KeepADBNotification.refresh(context);
        assertEquals(1, nsdProbe.discoverServicesCallCount);

        scheduler.advanceBy(8_000);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5_000));

        assertEquals("with Wi-Fi connected throughout, the retry chain must still be reachable "
                        + "and start a second discovery attempt",
                2, nsdProbe.discoverServicesCallCount);
    }
}
