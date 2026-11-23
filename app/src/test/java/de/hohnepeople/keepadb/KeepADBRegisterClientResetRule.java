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
 *
 * <p>#1020: between those resets every test starts with {@link #OFFLINE_TRANSPORT}, which refuses
 * each request at once without a socket or a DNS lookup. A test that saves a webhook URL without
 * a fake of its own used to reach the real {@link KeepADBHttpTransport}: on CI the address was
 * unreachable, the 2000 ms connect timeout ran into the 2000 ms drain below and the drain failed
 * at random; on a developer machine on the tailnet the request reached the real register server.
 * A test that needs a transport sets its own fake, which replaces this one. A test must not call
 * {@code KeepADBRegisterClient.resetHttpTransport()} in its {@code @After}: that runs before this
 * rule drains, so a request still queued would go out through the real transport.
 */
final class KeepADBRegisterClientResetRule extends ExternalResource {

    private static final long DRAIN_TIMEOUT_MS = 2000;

    /** #1020: refuses every request without touching the network, as an unreachable server would. */
    static final RegisterTransport.HttpTransport OFFLINE_TRANSPORT =
            new RegisterTransport.HttpTransport() {
                @Override
                public TransportResult postJson(String targetUrl, String payload, String logLabel) {
                    return TransportResult.failure("OfflineTestTransport");
                }

                @Override
                public TransportResult delete(String targetUrl) {
                    return TransportResult.failure("OfflineTestTransport");
                }
            };

    @Override
    protected void before() {
        reset();
        KeepADBRegisterClient.setHttpTransport(OFFLINE_TRANSPORT);
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
