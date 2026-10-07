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
     * #806: a drain timeout means a register task is still running and would write into the next
     * test's state, so it fails loudly instead of being swallowed.
     */
    static void reset(long drainTimeoutMs) {
        boolean drained = KeepADBRegisterClient.awaitIdleForTesting(drainTimeoutMs);
        KeepADBRegisterClient.resetForTesting();
        if (!drained) {
            throw new AssertionError("KeepADBRegisterClient executor did not drain within "
                    + drainTimeoutMs + " ms; a register task is still running and would leak"
                    + " into the next test");
        }
    }
}
