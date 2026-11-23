package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Source-level contracts for the recovery pulse (#309) that the behavioural tests cannot reach.
 *
 * <p>{@link KeepADBToggleSchedulingTest} and {@link KeepADBRecoveryPulseInterruptionTest} prove
 * the two pulse stages behave correctly; what they cannot prove is that every future gateway
 * write added to {@code performRecoveryPulse} stays inside the lock that also holds its guard --
 * a structural property, so it is pinned structurally here. The endpoint's guard behavior is
 * covered by {@link KeepADBEndpointRecoveryPulseBehaviorTest}; this contract separately keeps
 * the cooldown tied to a monotonic clock.
 */
public class KeepADBRecoveryPulseContractTest {

    @Test
    public void everyRecoveryPulseWriteIsGuardedInsideTheSameLock() throws IOException {
        String source = read("app/src/main/java/de/hohnepeople/keepadb/KeepADB.java");
        String body = methodBody(source,
                "static void performRecoveryPulse(Context ctx, EnableGuard guard)");
        // #1052: both stages run through one helper; the pulse itself never writes.
        String stage = methodBody(source, "private static PulseStage runPulseStage(");

        assertEquals("the pulse writes only through its stage helper", 0,
                count(body, "gateway.write("));
        assertEquals("performRecoveryPulse must run exactly two stages", 2,
                count(body, "runPulseStage("));
        assertTrue("first off, then on",
                body.indexOf("runPulseStage(appContext, pulseToken, guard, false)")
                        < body.indexOf("runPulseStage(appContext, pulseToken, guard, true)"));
        assertTrue(body.indexOf("runPulseStage(appContext, pulseToken, guard, false)") >= 0);
        assertEquals("a stage writes exactly once", 1, count(stage, "gateway.write("));

        int guarded = 0;
        int searchFrom = 0;
        while (true) {
            int lockStart = stage.indexOf("synchronized (KeepADB.class) {", searchFrom);
            if (lockStart < 0) break;
            String lockBody = stage.substring(lockStart,
                    findMatchingBraceEnd(stage, stage.indexOf('{', lockStart)));
            searchFrom = lockStart + 1;
            if (!lockBody.contains("gateway.write(")) continue;
            assertTrue("a locked write must re-check the pulse token first: " + lockBody,
                    lockBody.indexOf("pulseSuperseded(appContext, pulseToken)")
                            < lockBody.indexOf("gateway.write("));
            assertTrue("and the guard: " + lockBody,
                    lockBody.indexOf("guardStillApplies(guard, appContext)")
                            < lockBody.indexOf("gateway.write("));
            guarded += count(lockBody, "gateway.write(");
        }
        assertEquals("the stage write must sit inside a synchronized (KeepADB.class) block",
                1, guarded);
        // #1052 review: the STATE-01 refresh of a cancelled enable stage stays inside the lock.
        String lock = stage.substring(stage.indexOf("synchronized (KeepADB.class) {"));
        assertEquals("both cancellation branches of the enable stage refresh under the lock", 2,
                count(lock.substring(0, lock.indexOf("gateway.write(")),
                        "if (on) surfaces.refreshAll(appContext);"));
    }

    @Test
    public void endpointRecoveryCooldownUsesAMonotonicClock() throws IOException {
        String source = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBEndpoint.java");
        String body = methodBody(source, "void maybeSendRecoveryPulse(long generation)");

        assertTrue("the cooldown must be measured against the monotonic scheduler clock",
                body.contains("scheduler.elapsedRealtimeMs()"));
        assertFalse("a wall-clock reading can jump backwards or forwards and break the cooldown",
                body.contains("System.currentTimeMillis()"));
        assertTrue("the seeded start value must let the first pulse through right after boot",
                source.contains("lastRecoveryPulseAtMs = -RECOVERY_PULSE_COOLDOWN_MS"));
        assertTrue("the recovery pulse must capture the KeepADB network generation",
                body.contains("KeepADB.currentNetworkGeneration()"));
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue("Could not find " + signature, start >= 0);
        return source.substring(start, findMatchingBraceEnd(source, source.indexOf('{', start)));
    }

    private static int findMatchingBraceEnd(String source, int openBraceIndex) {
        int depth = 0;
        for (int i = openBraceIndex; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            if (c == '}') {
                depth--;
                if (depth == 0) return i + 1;
            }
        }
        throw new IllegalStateException("Unbalanced braces from index " + openBraceIndex);
    }

    private static int count(String haystack, String needle) {
        int found = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            found++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return found;
    }

    private static Path projectPath(String relativePath) {
        Path directory = Paths.get("").toAbsolutePath();
        while (directory != null && !Files.exists(directory.resolve("settings.gradle"))) {
            directory = directory.getParent();
        }
        if (directory == null) {
            throw new IllegalStateException("Could not locate project root");
        }
        return directory.resolve(relativePath);
    }

    private static String read(String relativePath) throws IOException {
        return new String(Files.readAllBytes(projectPath(relativePath)), StandardCharsets.UTF_8);
    }
}
