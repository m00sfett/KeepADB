package de.hohnepeople.keepadb;

import android.os.SystemClock;

/**
 * #1049: the endpoint-loss DELETE gate of {@link KeepADBRegisterClient#markUnavailableAsync}
 * (#562 backoff, #576 coalescing), extracted from the client.
 *
 * <p>Not thread-safe on purpose: the client calls every method under its class monitor, which
 * also guards the operation generation this gate is compared against. It sends nothing, schedules
 * nothing and persists nothing.
 */
final class UnavailableDeleteBackoff {
    /**
     * #562: coordinated retry staffage. Repeated notification/service refreshes and network
     * callbacks used to re-issue an immediate DELETE on every call (observed as 51 failed DELETEs,
     * mostly ~60s apart, in a single P60 run). The first attempt is still immediate; each
     * subsequent attempt after a failure waits at least the matching entry here, then settles into
     * {@link #MAX_BACKOFF_MS} once the table is exhausted. User-approved staffing from issue #562:
     * 5s, 10s, 15s, 30s, 1min, 3min, then <=5min forever. No age-based cutoff: retries continue
     * until a DELETE succeeds or a newer report supersedes it.
     */
    static final long[] RETRY_BACKOFFS_MS = {
            5_000L, 10_000L, 15_000L, 30_000L, 60_000L, 180_000L
    };
    static final long MAX_BACKOFF_MS = 5L * 60L * 1000L;

    /** #562: consecutive endpoint-loss DELETE failures, and when the next one may run. */
    private int attempts;
    private long nextAttemptAt;
    /**
     * #576 part C: the opGen of the endpoint-loss DELETE that is queued or running, or {@code 0}.
     * Repeated calls (toggle path, lifecycle observer, service_sync all reacting to the same
     * disconnect within milliseconds) coalesce into the one already outstanding; the device log
     * showed two successful DELETEs 13-14ms apart for a single manual toggle.
     *
     * <p>Deliberately an opGen, not a plain flag: coalescing only applies while nothing newer has
     * been dispatched. A newer registration or disconnect bumps the client's generation, which
     * breaks the equality in {@link #coalesces} although this field has not changed yet, so that
     * later call is never swallowed by an older DELETE for a different state.
     */
    private long inFlightOpGen;
    private Long nowForTesting;

    /** #576 part C: a DELETE for exactly the current operation is already queued or running. */
    boolean coalesces(long currentOpGeneration) {
        return inFlightOpGen != 0L && inFlightOpGen == currentOpGeneration;
    }

    /**
     * #562: only gates repeat calls once a previous attempt actually failed -- the very first
     * request for a given unavailability is always immediate ("sofortiger Erstversuch" in the
     * approved staffing).
     */
    boolean isWaiting() {
        return attempts > 0 && now() < nextAttemptAt;
    }

    void dispatched(long opGen) {
        inFlightOpGen = opGen;
    }

    /**
     * Releases the coalescing gate, but only if it still points at {@code opGen}: a newer call may
     * already have overwritten it with its own opGen, and that call then releases it itself.
     */
    void finished(long opGen) {
        if (inFlightOpGen == opGen) inFlightOpGen = 0L;
    }

    void reset() {
        attempts = 0;
        nextAttemptAt = 0L;
    }

    void recordFailure() {
        attempts++;
        nextAttemptAt = PendingCleanupRetryRepository.saturatingAdd(now(), backoffMs(attempts));
    }

    int attempts() {
        return attempts;
    }

    long nextAttemptAt() {
        return nextAttemptAt;
    }

    /**
     * #576 (NET-02 / part B): monotonic clock. This state is never persisted -- it resets with the
     * process anyway -- so {@link SystemClock#elapsedRealtime()} resetting on reboot is harmless,
     * while it protects the gate from a wall-clock jump (NTP correction, manual change) that could
     * otherwise lock it for an arbitrary duration (#309 argued the same for the endpoint cooldown).
     * The persisted pending-cleanup queue deliberately uses the wall clock instead, see {@link
     * PendingCleanupCoordinator#now()}.
     */
    long now() {
        Long testNow = nowForTesting;
        return testNow != null ? testNow : SystemClock.elapsedRealtime();
    }

    static long backoffMs(int consecutiveFailures) {
        if (consecutiveFailures <= 0) return 0L;
        if (consecutiveFailures <= RETRY_BACKOFFS_MS.length) {
            return RETRY_BACKOFFS_MS[consecutiveFailures - 1];
        }
        return MAX_BACKOFF_MS;
    }

    void setNowForTesting(Long now) {
        nowForTesting = now;
    }

    void resetForTesting() {
        reset();
        inFlightOpGen = 0L;
        nowForTesting = null;
    }
}
