package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Issue #545: the 60s heartbeat re-triggers {@code KeepADBService.recheckAndEnable()} on every
 * tick, which used to write a fresh "keep_alive_check" pair (one "started" preamble call, one
 * mutually-exclusive result call) into the bounded diagnostics ring buffer every time --
 * draining the export's historical coverage to roughly an hour. A release build must coalesce
 * repeated, unchanged heartbeat ticks instead of storing each one, while still storing an
 * actual state change (e.g. "waiting for network" -> "recheck due"); a debug build keeps every
 * tick. Unit tests here always execute under the debug variant's applicationId (see
 * {@code SettingsActivity.isDebugBuild()}), so {@code getPackageName()} alone cannot exercise
 * the release path -- this test therefore drives the package-private {@code debugBuild}
 * overload directly and forces the export's variant through {@link KeepADBBuildFlags}.
 *
 * <p>#566: a debug build no longer stores every tick as its own ring-buffer line; it counts
 * every tick into the 48h {@link KeepADBDiagnosticJournal} instead, see
 * {@link #debugBuildCountsEveryHeartbeatTickInTheJournal()}.
 *
 * <p>Review repair on top of the first version of this fix: coalescing must be keyed
 * independently per call site ({@code slot}), because a single shared "last signature" compared
 * across both the "started" preamble and the result call would alternate between two different
 * values on every tick and never coalesce anything -- the first version of this test only
 * exercised a single outcome repeatedly and missed that. {@link #twoCallsPerTickPatternStillCoalescesOnRelease()}
 * reproduces the real per-tick call pattern to guard against a regression of that bug.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBHeartbeatDiagnosticsThrottleTest {

    private final Context context = RuntimeEnvironment.getApplication();

    @After
    public void resetBuildFlagsAndJournal() {
        KeepADBBuildFlags.setOverrideForTesting(null);
        KeepADBDiagnosticJournal.resetForTesting();
    }

    @Test
    public void releaseBuildCoalescesRepeatedIdenticalHeartbeatTicksButKeepsStateChanges() {
        KeepADBBuildFlags.setOverrideForTesting(false);
        String marker = "marker-" + System.nanoTime();
        String slot = "single-slot-" + marker;
        KeepADBDiagnostics.heartbeatEvent(context, slot, "keep_alive_check", "service", "waiting",
                "reason=waiting_for_network " + marker, false);
        int afterFirst = countStoredEvents();

        for (int i = 0; i < 5; i++) {
            KeepADBDiagnostics.heartbeatEvent(context, slot, "keep_alive_check", "service", "waiting",
                    "reason=waiting_for_network " + marker, false);
        }
        assertEquals("repeated identical heartbeat ticks must not grow the ring buffer",
                afterFirst, countStoredEvents());

        KeepADBDiagnostics.heartbeatEvent(context, slot, "keep_alive_check", "service", "due",
                "reason=recheck_due " + marker, false);
        assertEquals("a changed heartbeat outcome must still be stored",
                afterFirst + 1, countStoredEvents());
    }

    @Test
    public void twoCallsPerTickPatternStillCoalescesOnRelease() {
        KeepADBBuildFlags.setOverrideForTesting(false);
        // Reproduces KeepADBService.recheckAndEnable(): every tick fires a "started" preamble
        // on one slot, then exactly one mutually exclusive result outcome on a second slot.
        String marker = "marker-" + System.nanoTime();
        String startedSlot = "recheck_started-" + marker;
        String resultSlot = "recheck_result-" + marker;

        KeepADBDiagnostics.heartbeatEvent(context, startedSlot, "keep_alive_check", "service",
                "started", "wifiConnected=true adbWifi=false " + marker, false);
        KeepADBDiagnostics.heartbeatEvent(context, resultSlot, "keep_alive_check", "service",
                "waiting", "reason=waiting_for_network " + marker, false);
        int afterFirstTick = countStoredEvents();

        for (int tick = 0; tick < 4; tick++) {
            KeepADBDiagnostics.heartbeatEvent(context, startedSlot, "keep_alive_check", "service",
                    "started", "wifiConnected=true adbWifi=false " + marker, false);
            KeepADBDiagnostics.heartbeatEvent(context, resultSlot, "keep_alive_check", "service",
                    "waiting", "reason=waiting_for_network " + marker, false);
        }
        assertEquals("4 further identical two-call ticks must not grow the ring buffer",
                afterFirstTick, countStoredEvents());

        // A genuine state change on the result outcome must still be stored, without re-storing
        // the still-unchanged "started" preamble.
        KeepADBDiagnostics.heartbeatEvent(context, startedSlot, "keep_alive_check", "service",
                "started", "wifiConnected=true adbWifi=false " + marker, false);
        KeepADBDiagnostics.heartbeatEvent(context, resultSlot, "keep_alive_check", "service",
                "due", "reason=recheck_due " + marker, false);
        assertEquals("a changed result outcome must be stored even though the preamble did not change",
                afterFirstTick + 1, countStoredEvents());
    }

    @Test
    public void debugBuildCountsEveryHeartbeatTickInTheJournal() {
        KeepADBBuildFlags.setOverrideForTesting(true);
        String marker = "marker-" + System.nanoTime();
        String slot = "debug-slot-" + marker;
        for (int i = 0; i < 4; i++) {
            KeepADBDiagnostics.heartbeatEvent(context, slot, "keep_alive_check", "service", "waiting",
                    "reason=waiting_for_network " + marker, true);
        }
        String export = KeepADBDiagnostics.export(context);
        assertTrue(export.startsWith(KeepADBDiagnosticJournal.EXPORT_HEADER));
        assertEquals("unchanged consecutive debug ticks form one journal entry",
                1, countLinesContaining(export, marker));
        assertTrue("every debug tick must still be counted", export.contains("samples=4"));

        KeepADBDiagnostics.heartbeatEvent(context, slot, "keep_alive_check", "service", "due",
                "reason=recheck_due " + marker, true);
        assertEquals("a changed debug outcome must start a new entry",
                2, countLinesContaining(KeepADBDiagnostics.export(context), marker));
    }

    private static int countLinesContaining(String export, String marker) {
        int count = 0;
        for (String line : export.split("\n")) if (line.contains(marker)) count++;
        return count;
    }

    private int countStoredEvents() {
        String export = KeepADBDiagnostics.export(context);
        return export.split("\n", -1).length - 2; // header line + trailing empty split segment
    }
}
