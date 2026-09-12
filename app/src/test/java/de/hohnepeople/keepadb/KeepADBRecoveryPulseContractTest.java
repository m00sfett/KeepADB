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
 * a structural property, so it is pinned structurally here. The endpoint cooldown's clock source
 * has no behavioural test at all: {@code maybeSendRecoveryPulse} is private and reachable only
 * behind {@code KeepADBService.isWifiConnected} / {@code KeepADBTrustedNetwork} framework calls,
 * so this contract is what keeps the cooldown off the wall clock.
 */
public class KeepADBRecoveryPulseContractTest {

    @Test
    public void everyRecoveryPulseWriteIsGuardedInsideTheSameLock() throws IOException {
        String source = read("app/src/main/java/de/hohnepeople/keepadb/KeepADB.java");
        String body = methodBody(source,
                "static void performRecoveryPulse(Context ctx, EnableGuard guard)");

        assertEquals("performRecoveryPulse must write exactly twice (off, then on)",
                2, count(body, "gateway.write("));

        int guarded = 0;
        int searchFrom = 0;
        while (true) {
            int lockStart = body.indexOf("synchronized (KeepADB.class) {", searchFrom);
            if (lockStart < 0) break;
            String lockBody = body.substring(lockStart,
                    findMatchingBraceEnd(body, body.indexOf('{', lockStart)));
            searchFrom = lockStart + 1;
            if (!lockBody.contains("gateway.write(")) continue;
            assertTrue("a locked write must re-check the pulse token first: " + lockBody,
                    lockBody.indexOf("pulseSuperseded(appContext, pulseToken)")
                            < lockBody.indexOf("gateway.write("));
            guarded += count(lockBody, "gateway.write(");
        }
        assertEquals("both pulse writes must sit inside a synchronized (KeepADB.class) block",
                2, guarded);
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
