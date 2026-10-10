package de.hohnepeople.keepadb;

import org.junit.rules.ExternalResource;

/**
 * #785: {@link KeepADBRegisterClient} keeps its registration state, op generation, transport and
 * single-thread executor in static fields that live for the whole JVM. A Robolectric test that
 * starts {@link MainActivity} reaches {@code KeepADBRegisterClient.markUnavailableAsync} via
 * {@code onResume}; if an earlier test class left a registered URL/endpoint behind, that call
 * dispatches an asynchronous DELETE which overwrites the persisted report status (for example
 * {@code failed} becomes {@code deregistered}) after the test has set it up.
 *
 * <p>This rule drains the register executor first (a trailing task of a previous test must not
 * write into this test's preferences) and then resets the static state, both before and after
 * each test method, so a test no longer depends on whether earlier tests cleaned up after
 * themselves.
 */
final class KeepADBRegisterClientResetRule extends ExternalResource {

    private static final long DRAIN_TIMEOUT_MS = 2000;

    @Override
    protected void before() {
        reset();
    }

    @Override
    protected void after() {
        reset();
    }

    static void reset() {
        reset(DRAIN_TIMEOUT_MS);
    }

    /**
     * #806/#855: a drain timeout means a register task is still running and could write into the
     * next test's preferences. Keep its static state and transport intact until it has finished.
     */
    static void reset(long drainTimeoutMs) {
        awaitIdle(drainTimeoutMs);
        KeepADBRegisterClient.resetForTesting();
    }

    /** Drains queued client work before a test fixture clears preferences or replaces its transport. */
    static void awaitIdle() {
        awaitIdle(DRAIN_TIMEOUT_MS);
    }

    static void awaitIdle(long drainTimeoutMs) {
        if (!KeepADBRegisterClient.awaitIdleForTesting(drainTimeoutMs)) {
            throw new AssertionError("KeepADBRegisterClient executor did not drain within "
                    + drainTimeoutMs + " ms; a register task is still running and may write"
                    + " into later test preferences");
        }
    }
}
