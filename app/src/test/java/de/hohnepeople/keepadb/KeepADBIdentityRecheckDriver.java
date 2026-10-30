package de.hohnepeople.keepadb;

import android.content.Context;

/**
 * Drives the #872 confirmation of an unreadable identity for tests that only care about the
 * notification it ends in: Keep-Alive is on and wants Wireless Debugging, Wi-Fi stays connected,
 * the block is reported once, and the virtual clock runs through every recheck delay. The identity the test set up stays as it is, so the
 * confirmation ends exactly as it would for a lasting problem.
 *
 * <p>Installs a {@link KeepADBFakeScheduler} and a connected Wi-Fi override; {@link
 * KeepADBNetworkResetRule} restores both after the test.
 */
final class KeepADBIdentityRecheckDriver {
    private KeepADBIdentityRecheckDriver() {}

    /** Total wait of the confirmation: the sum of every recheck delay. */
    static long totalDelayMs() {
        long total = 0;
        for (long delay : KeepADBNetworkTrustPrompt.IDENTITY_RECHECK_DELAYS_MS) total += delay;
        return total;
    }

    /**
     * Reports the block and lets the confirmation run to its end.
     *
     * @return the scheduler that ran it, for further assertions.
     */
    static KeepADBFakeScheduler blockAndConfirm(Context context) {
        KeepADBFakeScheduler scheduler = new KeepADBFakeScheduler();
        KeepADBNetworkTrustPrompt.setSchedulerForTesting(scheduler);
        KeepADBNetwork.setWifiConnectivityOverrideForTesting(() -> true);
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        KeepADBPreferences.setLastDesiredOn(context, true);
        KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(context);
        for (long delay : KeepADBNetworkTrustPrompt.IDENTITY_RECHECK_DELAYS_MS) {
            scheduler.advanceBy(delay);
        }
        return scheduler;
    }
}
