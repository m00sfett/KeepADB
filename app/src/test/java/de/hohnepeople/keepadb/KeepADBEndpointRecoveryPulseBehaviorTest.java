package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** End-to-end behavior coverage for the endpoint-owned recovery guard (#347). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBEndpointRecoveryPulseBehaviorTest {
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
        endpoint.maybeSendRecoveryPulse(0L);

        assertEquals("the restore write must still be covered by KeepADB.class",
                plannedGeneration, gateway.generationDuringRestore);
        assertEquals("the competing network change must run after the write lock is released",
                plannedGeneration + 1, KeepADB.currentNetworkGeneration());
        gateway.awaitNetworkChange();
    }

    private void assertRecoveryRestoreIsCancelled(KeepADBFakeSettingsGateway gateway,
            NetworkChangingScheduler scheduler) {
        KeepADB.setGatewayForTesting(gateway);
        KeepADB.setSchedulerForTesting(scheduler);

        KeepADBEndpoint endpoint = new KeepADBEndpoint(context, new KeepADBFakeNsdProbe(), scheduler);
        endpoint.maybeSendRecoveryPulse(0L);

        assertEquals("a network change during the pause must prevent the stale restore write",
                Arrays.asList(false), gateway.writes);
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
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("interrupted while starting network change", interrupted);
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
