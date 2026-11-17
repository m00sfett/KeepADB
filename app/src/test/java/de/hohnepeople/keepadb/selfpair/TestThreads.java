package de.hohnepeople.keepadb.selfpair;

import java.util.concurrent.Executor;

/**
 * Runs the fake peers of the self-pairing tests on threads of their own. The common pool is
 * shared with every other test in the JVM and can be busy, which showed as handshake timeouts.
 */
final class TestThreads {
    static final Executor EXECUTOR = task -> {
        Thread thread = new Thread(task, "selfpair-test-peer");
        thread.setDaemon(true);
        thread.start();
    };

    private TestThreads() {
    }
}
