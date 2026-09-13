package de.hohnepeople.keepadb;

import org.junit.rules.ExternalResource;

/**
 * #401: {@link KeepADBNetwork#get(android.content.Context)} is a process-wide singleton that
 * silently keeps returning its first instance -- bound to whichever {@code Context} first called
 * {@code get()} -- for the rest of the JVM's lifetime, ignoring every later caller's {@code
 * Context}. {@link KeepADBService#isWifiConnected(android.content.Context)} and two {@link
 * KeepADBEndpoint} methods call {@code KeepADBNetwork.get(context)} internally, so any Robolectric
 * test that exercises those methods instantiates the singleton incidentally, without the test
 * author ever mentioning {@code KeepADBNetwork} in their test. Left uncleaned, that instance leaks
 * into whichever unrelated test runs next in the same JVM, binding it to a foreign {@code
 * Context}/{@code ConnectivityManager} shadow and producing test-order-dependent flakiness (see
 * issue #401 for the observed failure).
 *
 * <p>Every Robolectric test in this project applies this rule so {@link
 * KeepADBNetwork#resetForTesting()} runs both before and after each test method, regardless of
 * whether that test touches {@code KeepADBNetwork} directly. Running it before the test (not just
 * after) additionally makes each test independent of whether a *previous* test cleaned up after
 * itself.
 */
final class KeepADBNetworkResetRule extends ExternalResource {

    @Override
    protected void before() {
        KeepADBNetwork.resetForTesting();
    }

    @Override
    protected void after() {
        KeepADBNetwork.resetForTesting();
    }
}
