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

    /**
     * Runs every recheck at its due time. Advance step by step, never by the total in one go:
     * {@link KeepADBFakeScheduler#advanceTo} schedules a follow-up from the already advanced
     * clock, so one large step would run only the first recheck and hide the rest.
     */
    static void runAllChecks(KeepADBFakeScheduler scheduler) {
        for (long delay : KeepADBNetworkTrustPrompt.IDENTITY_RECHECK_DELAYS_MS) {
            scheduler.advanceBy(delay);
        }
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
        runAllChecks(scheduler);
        return scheduler;
    }
}
