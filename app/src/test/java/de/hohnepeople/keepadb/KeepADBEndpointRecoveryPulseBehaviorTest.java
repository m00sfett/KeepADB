package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

/**
 * End-to-end behavior coverage for the endpoint-owned recovery guard (#347). #596 added the
 * {@code pulseIs*} entry-gate tests below, replacing the equivalent source-content assertions
 * formerly in {@link KeepADBWifiGatedDiscoveryContractTest}.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBEndpointRecoveryPulseBehaviorTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();
    private final Context context = org.robolectric.RuntimeEnvironment.getApplication();

    @Before
    public void setUp() {
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
        KeepADB.resetForTesting();
        KeepADBNetwork.resetForTesting();
        KeepADBEndpoint.resetForTesting();
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
    }

    @After
    public void tearDown() {
        KeepADBNetwork.resetForTesting();
        KeepADB.resetForTesting();
        KeepADBEndpoint.resetForTesting();
        KeepADBEndpointCoordinator.resetForTesting();
    }

    /**
     * Reproduces the real device coupling from #572's device comment (s20, Keep-Alive OFF, app
     * foregrounded): the pulse's own AUS write is observed by MainActivity's/KeepADBService's
     * ContentObserver, which runs {@link KeepADBEndpointCoordinator#refresh}. With Keep-Alive off that
     * tears the endpoint down via {@code stop() -> endpoint.stop()} on this very instance,
     * bumping {@code discoveryGeneration} before the pulse reaches its EIN stage. This test wires
     * that exact call ({@code KeepADBEndpointCoordinator.refresh}, not a raw {@code endpoint.stop()})
     * into the fake gateway's disable write, matching the ~14ms real-device gap synchronously.
     *
     * <p>Before the #572 fix (guard included {@code isCurrent(generation)}): red -- the EIN write
     * never happens, {@code gateway.writes} stays {@code [false]}, and the diagnostics show
     * {@code stage=enable reason=preconditions_changed}, exactly like the device logcat. After the
     * fix: green -- the EIN write completes and Wireless Debugging ends up back on.
     */
    @Test
    public void ownDisableWriteMustNotAbortTheEnableStageViaTheNotificationObserverCoupling() {
        ObservingGateway gateway = new ObservingGateway(context);
        KeepADBFakeScheduler scheduler = new KeepADBFakeScheduler();
        KeepADB.setGatewayForTesting(gateway);
        KeepADB.setSchedulerForTesting(scheduler);

        KeepADBEndpoint endpoint = new KeepADBEndpoint(context, new KeepADBFakeNsdProbe(), scheduler);
        KeepADBEndpointCoordinator.setEndpointForTesting(endpoint);

        endpoint.maybeSendRecoveryPulse(0L);
        // The notification's postSurfaceRefresh() queues a MAIN_HANDLER runnable this test does
        // not otherwise drain; idling avoids an unrelated "queued unexecuted runnables" warning
        // (see KeepADBNotificationRobolectricTest for the same pattern).
        ShadowLooper.idleMainLooper();

        assertEquals("the pulse must complete both stages despite its own AUS write tearing "
                + "discovery down through the real notification-observer coupling (#572)",
                Arrays.asList(false, true), gateway.writes);
        assertTrue("Wireless Debugging must end up back on", gateway.isEnabled(context));
        String diagnostics = KeepADBDiagnostics.export(context);
        assertFalse("must not report a stale discovery-generation abort: " + diagnostics,
                diagnostics.contains("stage=enable reason=preconditions_changed"));
    }

    /**
     * The invariant's other side (Gegenprobe): a *real* network change during the pause -- not a
     * side effect of the pulse's own write -- must still abort the EIN stage. This is exactly
     * {@link #endpointGuardCancelsRestoreWhenNetworkChangesDuringPulse()} et al. above, which stay
     * green after #572: they cancel via {@code KeepADB.currentNetworkGeneration()}/{@code
     * isWifiConnected}/{@code isCurrentNetworkTrusted}, none of which the pulse's own AUS write
     * can flip, unlike the endpoint's own {@code discoveryGeneration}.
     *
     * <p>Answer to "which violation would let a red-green test pass for the wrong reason": a fix
     * that simply deleted every EIN-stage guard check (instead of only dropping the
     * self-triggered {@code isCurrent(generation)} term) would also make this test green, but it
     * would let a genuine network/trust change during the pause slip through undetected -- which
     * is exactly what {@link #endpointGuardCancelsRestoreWhenNetworkChangesDuringPulse()},
     * {@link #endpointGuardCancelsRestoreWhenWifiDisconnectsDuringPulse()} and
     * {@link #endpointGuardCancelsRestoreWhenTrustIsWithdrawnDuringPulse()} independently pin.
     */
    @Test
    public void aRealNetworkChangeDuringThePauseStillAbortsTheEnableStage() {
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(true);
        NetworkChangingScheduler scheduler = new NetworkChangingScheduler(() -> KeepADB.noteNetworkChanged());
        assertRecoveryRestoreIsCancelled(gateway, scheduler);
    }

    /**
     * #296/#596: "trusted network" is an allowlist policy decision and says nothing about whether
     * the device is actually on a Wi-Fi transport at all right now -- so the pulse's *entry* gate
     * (before it ever attempts the AUS write) must require an active Wi-Fi connection in addition
     * to the trusted-network check, not instead of it. Asserted on the observable outcome (no AUS
     * write attempted at all), not on which check appears first in the source.
     *
     * <p>{@link KeepADB#performRecoveryPulse}'s own {@code guard} closure independently re-checks
     * {@code KeepADBService.isWifiConnected()} at both pulse stages (#309/#572) -- a real, useful
     * defense-in-depth layer, but one that would silently mask a defeated entry-gate check in this
     * test if Wi-Fi stayed disconnected throughout: the guard closure would then block the write
     * for its own, unrelated reason, and this test would stay green regardless of whether the entry
     * gate under test still exists at all (mutation-tested: it did). {@link
     * WifiReconnectingScheduler} makes the two independently observable by reconnecting Wi-Fi at
     * the exact point control would reach the guard closure, but only if the entry gate under test
     * failed to stop it from getting there first.
     */
    @Test
    public void pulseIsNeverSentWithoutAnActiveWifiConnectionEvenOnATrustedNetwork() {
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(true);
        WifiReconnectingScheduler scheduler = new WifiReconnectingScheduler();
        KeepADB.setGatewayForTesting(gateway);
        KeepADB.setSchedulerForTesting(scheduler);
        // setUp() already trusts MODE_ALL_WIFI; only Wi-Fi connectivity itself is withdrawn here.
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> false);

        KeepADBEndpoint endpoint = new KeepADBEndpoint(context, new KeepADBFakeNsdProbe(), scheduler);
        endpoint.maybeSendRecoveryPulse(0L);

        assertTrue("a trusted network alone must not be enough to send a recovery pulse without "
                        + "an active Wi-Fi transport for adbd to ever listen on",
                gateway.writes.isEmpty());
    }

    /** See {@link #pulseIsNeverSentWithoutAnActiveWifiConnectionEvenOnATrustedNetwork()}'s javadoc. */
    private static final class WifiReconnectingScheduler extends KeepADBFakeScheduler {
        @Override
        public void runAsync(Runnable runnable) {
            KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
            super.runAsync(runnable);
        }
    }

    /**
     * Gegenprobe to the test above (AGENTS.md: guard both sides of a two-sided invariant): an
     * active Wi-Fi transport alone must not bypass the existing trusted-network allowlist either
     * -- the #296 fix must add a check, not replace one.
     *
     * <p>Mirrors the previous test's masking concern: {@code guardStillApplies}'s closure also
     * independently re-checks {@link KeepADBTrustedNetwork#isCurrentNetworkTrusted}, so {@link
     * TrustGrantingScheduler} restores trust only once control would reach that closure.
     */
    @Test
    public void pulseIsStillBlockedOnAnUntrustedNetworkEvenWithAnActiveWifiConnection() {
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(true);
        TrustGrantingScheduler scheduler = new TrustGrantingScheduler(context);
        KeepADB.setGatewayForTesting(gateway);
        KeepADB.setSchedulerForTesting(scheduler);
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);

        KeepADBEndpoint endpoint = new KeepADBEndpoint(context, new KeepADBFakeNsdProbe(), scheduler);
        endpoint.maybeSendRecoveryPulse(0L);

        assertTrue("an active Wi-Fi transport alone must not bypass the trusted-network allowlist",
                gateway.writes.isEmpty());
    }

    /** See {@link #pulseIsStillBlockedOnAnUntrustedNetworkEvenWithAnActiveWifiConnection()}'s javadoc. */
    private static final class TrustGrantingScheduler extends KeepADBFakeScheduler {
        private final Context context;

        TrustGrantingScheduler(Context context) {
            this.context = context;
        }

        @Override
        public void runAsync(Runnable runnable) {
            KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
            super.runAsync(runnable);
        }
    }

    /**
     * {@link KeepADBSettingsGateway} fake that mirrors the real ContentObserver coupling: turning
     * Wireless Debugging off through this gateway synchronously runs the same
     * {@link KeepADBEndpointCoordinator#refresh} the real observer would, without needing a real
     * {@code Settings.Global} ContentObserver registration in a unit test.
     */
    private static final class ObservingGateway implements KeepADBSettingsGateway {
        private final Context context;
        final List<Boolean> writes = new ArrayList<>();
        private boolean enabled = true;

        ObservingGateway(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(Context ctx) {
            return enabled;
        }

        @Override
        public boolean write(Context appContext, boolean on) {
            writes.add(on);
            enabled = on;
            if (!on) {
                KeepADBEndpointCoordinator.refresh(context);
            }
            return true;
        }
    }

    @Test
    public void endpointGuardCancelsRestoreWhenNetworkChangesDuringPulse() {
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(true);
        NetworkChangingScheduler scheduler = new NetworkChangingScheduler(() -> KeepADB.noteNetworkChanged());
        assertRecoveryRestoreIsCancelled(gateway, scheduler);
    }

    @Test
    public void endpointGuardCancelsRestoreWhenWifiDisconnectsDuringPulse() {
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(true);
        NetworkChangingScheduler scheduler = new NetworkChangingScheduler(
                () -> KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> false));
        assertRecoveryRestoreIsCancelled(gateway, scheduler);
    }

    @Test
    public void endpointGuardCancelsRestoreWhenTrustIsWithdrawnDuringPulse() {
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(true);
        NetworkChangingScheduler scheduler = new NetworkChangingScheduler(
                () -> KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST));
        assertRecoveryRestoreIsCancelled(gateway, scheduler);
    }

    @Test
    public void networkGenerationChangeCannotInterleaveWithRestoreWrite() throws Exception {
        AtomicGenerationGateway gateway = new AtomicGenerationGateway();
        KeepADBFakeScheduler scheduler = new KeepADBFakeScheduler();
        KeepADB.setGatewayForTesting(gateway);
        KeepADB.setSchedulerForTesting(scheduler);

        long plannedGeneration = KeepADB.currentNetworkGeneration();
        KeepADBEndpoint endpoint = new KeepADBEndpoint(context, new KeepADBFakeNsdProbe(), scheduler);
        try {
            endpoint.maybeSendRecoveryPulse(0L);
        } finally {
            gateway.awaitNetworkChange();
        }

        assertEquals("the restore write must still be covered by KeepADB.class",
                plannedGeneration, gateway.generationDuringRestore);
        assertEquals("the competing network change must run after the write lock is released",
                plannedGeneration + 1, KeepADB.currentNetworkGeneration());
    }

    private void assertRecoveryRestoreIsCancelled(KeepADBFakeSettingsGateway gateway,
            NetworkChangingScheduler scheduler) {
        KeepADB.setGatewayForTesting(gateway);
        KeepADB.setSchedulerForTesting(scheduler);

        KeepADBEndpoint endpoint = new KeepADBEndpoint(context, new KeepADBFakeNsdProbe(), scheduler);
        endpoint.maybeSendRecoveryPulse(0L);

        assertEquals("a network change during the pause must prevent the stale restore write",
                Arrays.asList(false), gateway.writes);
        String diagnostics = KeepADBDiagnostics.export(context);
        assertTrue("a guard cancellation must identify changed preconditions: " + diagnostics,
                diagnostics.contains("stage=enable reason=preconditions_changed"));
        assertFalse("a guard cancellation must not claim a newer user intent: " + diagnostics,
                diagnostics.contains("stage=enable reason=newer_user_intent"));
    }

    private static final class NetworkChangingScheduler extends KeepADBFakeScheduler {
        private final Runnable change;
        private boolean changed;

        NetworkChangingScheduler(Runnable change) {
            this.change = change;
        }

        @Override
        public void sleep(long delayMs) {
            super.sleep(delayMs);
            if (!changed) {
                changed = true;
                change.run();
            }
        }

        @Override
        public long elapsedRealtimeMs() {
            return 10 * KeepADB.TOGGLE_COOLDOWN_MS;
        }
    }

    private static final class AtomicGenerationGateway implements KeepADBSettingsGateway {
        private final CountDownLatch networkChangeStarted = new CountDownLatch(1);
        private final CountDownLatch networkChangeFinished = new CountDownLatch(1);
        private volatile long generationDuringRestore = -1;
        private boolean enabled = true;

        @Override
        public boolean isEnabled(Context context) {
            return enabled;
        }

        @Override
        public boolean write(Context context, boolean on) {
            if (on) {
                Thread networkChange = new Thread(() -> {
                    networkChangeStarted.countDown();
                    KeepADB.noteNetworkChanged();
                    networkChangeFinished.countDown();
                });
                networkChange.start();
                try {
                    if (!networkChangeStarted.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("network change thread did not start");
                    }
                    if (networkChangeFinished.await(1, TimeUnit.SECONDS)) {
                        throw new AssertionError(
                                "network change completed while the restore write was in progress");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("interrupted while checking network change ordering",
                            interrupted);
                }
                generationDuringRestore = KeepADB.currentNetworkGeneration();
                enabled = true;
                return true;
            }
            enabled = false;
            return true;
        }

        void awaitNetworkChange() throws InterruptedException {
            if (!networkChangeFinished.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("network change thread did not finish");
            }
        }
    }
}
