package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

/** #1030: finding adbd's Wifi-ADB port without mDNS. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBPortFallbackTest {
    private static final String HOST = "192.168.1.20";

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();
    private final Context context = RuntimeEnvironment.getApplication();
    private FakeProber prober;
    private TestClock clock;

    @Before
    public void setUp() {
        KeepADBPortFallback.resetForTesting();
        KeepADBPreferences.clearLastAdbPort(context);
        prober = new FakeProber();
        clock = new TestClock();
        KeepADBPortFallback.setProberForTesting(prober);
        KeepADBPortFallback.setClockForTesting(clock);
    }

    @After
    public void tearDown() {
        KeepADBPortFallback.resetForTesting();
        KeepADBPreferences.clearLastAdbPort(context);
        KeepADB.resetForTesting();
        KeepADBEndpoint.resetForTesting();
        KeepADBNetwork.resetForTesting();
    }

    @Test
    public void rememberedPortOfThisBootIsCheckedWithoutAScan() {
        KeepADBPortFallback.remember(context, 41234);
        prober.stls.add(41234);

        KeepADBPortFallback.Result result = KeepADBPortFallback.find(context, () -> false);

        assertNotNull(result);
        assertEquals(HOST, result.host);
        assertEquals(41234, result.port);
        assertEquals(0, prober.scans);
    }

    @Test
    public void rememberedPortFromAnotherBootIsDroppedAndTheRangeIsScanned() {
        KeepADBPortFallback.remember(context, 41234);
        prober.stls.add(41234);
        clock.boot = 8;
        prober.scanResult = List.of(45678);

        KeepADBPortFallback.Result result = KeepADBPortFallback.find(context, () -> false);

        assertNotNull(result);
        assertEquals(45678, result.port);
        assertFalse("the old boot's port must never be probed", prober.probed.contains(41234));
        assertEquals("find() alone never remembers; only a winning delivery does", -1,
                KeepADBPortFallback.rememberedPort(context));
    }

    @Test
    public void unreadableBootCounterNeverRemembersAPort() {
        KeepADBPortFallback.remember(context, 41234);
        clock.boot = -1;
        KeepADBPortFallback.remember(context, 42000);

        assertEquals(-1, KeepADBPreferences.getLastAdbPort(context));
        assertEquals(-1, KeepADBPortFallback.rememberedPort(context));
    }

    @Test
    public void staleRememberedPortFallsBackToTheScan() {
        KeepADBPortFallback.remember(context, 41234);
        prober.scanResult = List.of(45678);

        KeepADBPortFallback.Result result = KeepADBPortFallback.find(context, () -> false);

        assertNotNull(result);
        assertEquals(45678, result.port);
        assertTrue(prober.probed.contains(41234));
        assertEquals(1, prober.scans);
    }

    @Test
    public void severalAdbAnswersReportNothing() {
        prober.scanResult = List.of(40001, 40002);

        assertNull(KeepADBPortFallback.find(context, () -> false));
        assertEquals(-1, KeepADBPortFallback.rememberedPort(context));
    }

    @Test
    public void portNotReachableOnTheWifiAddressReportsNothing() {
        prober.scanResult = List.of(45678);
        prober.unreachable.add(45678);

        assertNull(KeepADBPortFallback.find(context, () -> false));
    }

    @Test
    public void withoutAWifiAddressNothingIsProbed() {
        KeepADBPortFallback.remember(context, 41234);
        prober.host = null;

        assertNull(KeepADBPortFallback.find(context, () -> false));
        assertTrue(prober.probed.isEmpty());
        assertEquals(0, prober.scans);
    }

    @Test
    public void scanRunsAtMostOncePerCooldown() {
        clock.elapsed = 100_000L;
        assertNull(KeepADBPortFallback.find(context, () -> false));
        clock.elapsed += KeepADBPortFallback.SCAN_COOLDOWN_MS - 1;
        assertNull(KeepADBPortFallback.find(context, () -> false));
        assertEquals(1, prober.scans);

        clock.elapsed += 1;
        assertNull(KeepADBPortFallback.find(context, () -> false));
        assertEquals(2, prober.scans);
    }

    @Test
    public void cancelledSearchDoesNotScan() {
        assertNull(KeepADBPortFallback.find(context, () -> true));
        assertEquals(0, prober.scans);
    }

    @Test
    public void scanCancelledMidwayReportsNothingAndKeepsNoPort() {
        boolean[] cancelled = {false};
        prober.scanResult = List.of(45678);
        prober.onScan = () -> cancelled[0] = true;

        assertNull(KeepADBPortFallback.find(context, () -> cancelled[0]));
        assertEquals(-1, KeepADBPortFallback.rememberedPort(context));
    }

    // --- KeepADBEndpoint integration ---

    @Test
    public void silentMdnsFindsTheEndpointThroughTheFallbackBeforeThePulse() {
        KeepADBFakeSettingsGateway gateway = enableWifiAdb();
        KeepADBFakeScheduler scheduler = new KeepADBFakeScheduler();
        KeepADB.setSchedulerForTesting(scheduler);
        prober.scanResult = List.of(45678);
        RecordingListener listener = new RecordingListener();
        KeepADBEndpoint endpoint = new KeepADBEndpoint(context, new KeepADBFakeNsdProbe(),
                scheduler, null, true);

        endpoint.discover(listener, true);
        scheduler.advanceTo(KeepADBEndpoint.FALLBACK_DELAY_MS);

        assertEquals(List.of(HOST + ":45678"), listener.events);
        assertEquals("the delivered port is remembered", 45678,
                KeepADBPortFallback.rememberedPort(context));
        scheduler.advanceTo(20_000);
        ShadowLooper.idleMainLooper();
        assertEquals("no pulse and no timeout after the fallback found the port",
                List.of(HOST + ":45678"), listener.events);
        assertTrue("Wifi-ADB was never restarted", gateway.writes.isEmpty());
    }

    @Test
    public void pulseAndTimeoutWaitForARunningFallback() {
        KeepADBFakeSettingsGateway gateway = enableWifiAdb();
        KeepADBFakeScheduler scheduler = new KeepADBFakeScheduler();
        KeepADB.setSchedulerForTesting(scheduler);
        scheduler.setDeferAsync(true);
        RecordingListener listener = new RecordingListener();
        KeepADBEndpoint endpoint = new KeepADBEndpoint(context, new KeepADBFakeNsdProbe(),
                scheduler, null, true);

        endpoint.discover(listener, true);
        // The fake runs due work in posting order; step through it in time order like a Handler.
        scheduler.advanceTo(KeepADBEndpoint.FALLBACK_DELAY_MS);
        scheduler.advanceTo(9_000);
        assertTrue("the pulse waits while the fallback runs", gateway.writes.isEmpty());
        assertTrue("the timeout waits too", listener.events.isEmpty());

        scheduler.setDeferAsync(false);
        scheduler.runDeferredAsync();
        scheduler.advanceBy(0);
        ShadowLooper.idleMainLooper();
        assertEquals("a fallback without result lets the deferred pulse run",
                Arrays.asList(false, true), gateway.writes);
        assertTrue("mDNS keeps its window after the pulse", listener.events.isEmpty());

        scheduler.advanceBy(10_000);
        ShadowLooper.idleMainLooper();
        assertEquals(List.of("unavailable"), listener.events);
    }

    @Test
    public void fallbackEndingBetweenPulseAndTimeoutKeepsThePulseWindow() {
        KeepADBFakeSettingsGateway gateway = enableWifiAdb();
        KeepADBFakeScheduler scheduler = new KeepADBFakeScheduler();
        KeepADB.setSchedulerForTesting(scheduler);
        scheduler.setDeferAsync(true);
        RecordingListener listener = new RecordingListener();
        KeepADBEndpoint endpoint = new KeepADBEndpoint(context, new KeepADBFakeNsdProbe(),
                scheduler, null, true);

        endpoint.discover(listener, true);
        scheduler.advanceTo(KeepADBEndpoint.FALLBACK_DELAY_MS);
        scheduler.advanceTo(7_800);
        scheduler.setDeferAsync(false);
        scheduler.runDeferredAsync();
        scheduler.advanceBy(0);
        ShadowLooper.idleMainLooper();
        assertEquals(Arrays.asList(false, true), gateway.writes);

        scheduler.advanceTo(10_700);
        ShadowLooper.idleMainLooper();
        assertTrue("the original 8 s timeout must not cut the pulse's window",
                listener.events.isEmpty());
        scheduler.advanceTo(11_800);
        ShadowLooper.idleMainLooper();
        assertEquals(List.of("unavailable"), listener.events);
    }

    @Test
    public void stoppedDiscoveryIgnoresALateFallbackResult() {
        enableWifiAdb();
        KeepADBFakeScheduler scheduler = new KeepADBFakeScheduler();
        KeepADB.setSchedulerForTesting(scheduler);
        scheduler.setDeferAsync(true);
        prober.scanResult = List.of(45678);
        RecordingListener listener = new RecordingListener();
        KeepADBEndpoint endpoint = new KeepADBEndpoint(context, new KeepADBFakeNsdProbe(),
                scheduler, null, true);

        endpoint.discover(listener, true);
        scheduler.advanceTo(KeepADBEndpoint.FALLBACK_DELAY_MS);
        endpoint.stop();
        scheduler.runDeferredAsync();

        assertTrue(listener.events.isEmpty());
        assertEquals("a cancelled fallback never scans", 0, prober.scans);
        assertEquals("a superseded fallback remembers nothing", -1,
                KeepADBPortFallback.rememberedPort(context));
    }

    @Test
    public void testConstructorsKeepTheFallbackOff() {
        enableWifiAdb();
        KeepADBFakeScheduler scheduler = new KeepADBFakeScheduler();
        KeepADB.setSchedulerForTesting(scheduler);
        RecordingListener listener = new RecordingListener();
        KeepADBEndpoint endpoint = new KeepADBEndpoint(context, new KeepADBFakeNsdProbe(), scheduler);

        endpoint.discover(listener, false);
        scheduler.advanceTo(20_000);

        assertEquals(List.of("unavailable"), listener.events);
        assertTrue(prober.probed.isEmpty());
        assertEquals(0, prober.scans);
    }

    private KeepADBFakeSettingsGateway enableWifiAdb() {
        shadowOf((Application) context).grantPermissions(
                android.Manifest.permission.WRITE_SECURE_SETTINGS);
        KeepADB.resetForTesting();
        KeepADBNetwork.resetForTesting();
        KeepADBEndpoint.resetForTesting();
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        KeepADBFakeSettingsGateway gateway = new KeepADBFakeSettingsGateway(true);
        KeepADB.setGatewayForTesting(gateway);
        return gateway;
    }

    private static final class RecordingListener implements KeepADBEndpoint.Listener {
        final List<String> events = new ArrayList<>();

        @Override
        public void onEndpoint(String host, int port) {
            events.add(host + ":" + port);
        }

        @Override
        public void onUnavailable() {
            events.add("unavailable");
        }
    }

    private static final class FakeProber implements KeepADBPortFallback.Prober {
        String host = HOST;
        final Set<Integer> stls = new HashSet<>();
        final Set<Integer> unreachable = new HashSet<>();
        final List<Integer> probed = new ArrayList<>();
        List<Integer> scanResult = List.of();
        Runnable onScan;
        int scans;

        @Override
        public String ownWifiHost(Context context) {
            return host;
        }

        @Override
        public boolean offersAdbOverTls(int port) {
            probed.add(port);
            return stls.contains(port);
        }

        @Override
        public List<Integer> scan(BooleanSupplier cancelled) {
            scans++;
            if (onScan != null) onScan.run();
            return scanResult;
        }

        @Override
        public boolean isReachable(String host, int port) {
            return !unreachable.contains(port);
        }
    }

    private static final class TestClock implements KeepADBDeadline.Clock {
        long elapsed = 50_000L;
        int boot = 7;

        @Override
        public long wallMs() {
            return 0;
        }

        @Override
        public long elapsedMs() {
            return elapsed;
        }

        @Override
        public int bootCount(Context context) {
            return boot;
        }
    }
}
