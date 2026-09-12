package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;

import java.util.Arrays;

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
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
    }

    @After
    public void tearDown() {
        KeepADBNetwork.resetForTesting();
        KeepADB.resetForTesting();
    }

    @Test
    public void endpointGuardCancelsRestoreWhenNetworkChangesDuringPulse() {
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(true);
        NetworkChangingScheduler scheduler = new NetworkChangingScheduler();
        KeepADB.setGatewayForTesting(gateway);
        KeepADB.setSchedulerForTesting(scheduler);

        KeepADBEndpoint endpoint = new KeepADBEndpoint(context, new KeepADBFakeNsdProbe(), scheduler);
        endpoint.maybeSendRecoveryPulse(0L);

        assertEquals("the endpoint must disable debugging before its recovery pause",
                Arrays.asList(false), gateway.writes);
        assertEquals("a network change during the pause must prevent the stale restore write",
                Arrays.asList(false), gateway.writes);
    }

    private static final class NetworkChangingScheduler extends KeepADBFakeScheduler {
        private boolean changed;

        @Override
        public void sleep(long delayMs) {
            super.sleep(delayMs);
            if (!changed) {
                changed = true;
                KeepADB.noteNetworkChanged();
            }
        }

        @Override
        public long elapsedRealtimeMs() {
            return 10 * KeepADB.TOGGLE_COOLDOWN_MS;
        }
    }
}
