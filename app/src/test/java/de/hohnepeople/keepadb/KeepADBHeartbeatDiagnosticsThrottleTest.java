package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Issue #545: the 60s heartbeat re-triggers {@code KeepADBService.recheckAndEnable()} on every
 * tick, which used to write a fresh "keep_alive_check" pair into the bounded diagnostics ring
 * buffer every time -- draining the export's historical coverage to roughly an hour. A release
 * build must coalesce repeated, unchanged heartbeat ticks instead of storing each one, while
 * still storing an actual state change (e.g. "waiting for network" -> "recheck due"); a debug
 * build keeps every tick. Unit tests here always execute under the debug variant's applicationId
 * (see {@code SettingsActivity.isDebugBuild()}), so {@code getPackageName()} alone cannot
 * exercise the release path -- this test therefore drives the package-private
 * {@code storeEveryTick} overload directly instead of relying on the package-name derivation.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBHeartbeatDiagnosticsThrottleTest {

    private final Context context = RuntimeEnvironment.getApplication();

    @Test
    public void releaseBuildCoalescesRepeatedIdenticalHeartbeatTicksButKeepsStateChanges() {
        String marker = "marker-" + System.nanoTime();
        KeepADBDiagnostics.heartbeatEvent(context, "keep_alive_check", "service", "waiting",
                "reason=waiting_for_network " + marker, false);
        int afterFirst = countStoredEvents();

        for (int i = 0; i < 5; i++) {
            KeepADBDiagnostics.heartbeatEvent(context, "keep_alive_check", "service", "waiting",
                    "reason=waiting_for_network " + marker, false);
        }
        assertEquals("repeated identical heartbeat ticks must not grow the ring buffer",
                afterFirst, countStoredEvents());

        KeepADBDiagnostics.heartbeatEvent(context, "keep_alive_check", "service", "due",
                "reason=recheck_due " + marker, false);
        assertEquals("a changed heartbeat outcome must still be stored",
                afterFirst + 1, countStoredEvents());
    }

    @Test
    public void debugBuildStoresEveryHeartbeatTickEvenWhenUnchanged() {
        String marker = "marker-" + System.nanoTime();
        KeepADBDiagnostics.heartbeatEvent(context, "keep_alive_check", "service", "waiting",
                "reason=waiting_for_network " + marker, true);
        int afterFirst = countStoredEvents();

        for (int i = 0; i < 3; i++) {
            KeepADBDiagnostics.heartbeatEvent(context, "keep_alive_check", "service", "waiting",
                    "reason=waiting_for_network " + marker, true);
        }
        assertEquals("a debug build must keep every unchanged heartbeat tick",
                afterFirst + 3, countStoredEvents());
    }

    private int countStoredEvents() {
        String export = KeepADBDiagnostics.export(context);
        return export.split("\n", -1).length - 2; // header line + trailing empty split segment
    }
}
