package de.hohnepeople.keepadb;

/**
 * Test-only override for {@link KeepADBNetwork#isWifiConnected()} (#303), matching the existing
 * {@link KeepADBNsdProbe}/{@link KeepADBScheduler} seam pattern {@link KeepADBEndpointDiscoveryTest}
 * already uses: production always runs the real transport-capability check in {@link
 * KeepADBNetwork}, and only a test replaces it, via {@link
 * KeepADBNetwork#setWifiConnectivityOverrideForTesting(KeepADBWifiProbe)}, to drive every caller
 * of {@code KeepADBService.isWifiConnected(Context)} (including {@link
 * KeepADBEndpointCoordinator#refreshInternal} and {@code scheduleRetryLocked}) with a deterministic
 * Wi-Fi state instead of a real, singleton {@link android.net.ConnectivityManager}.
 *
 * <p>Placed underneath {@link KeepADBNetwork} rather than as a seam on {@code
 * KeepADBEndpointCoordinator}, so one override drives every {@code isWifiConnected} caller alike.
 * #303 originally chose this spot because source-content contract tests pinned the exact text of
 * those call sites; #596 replaced those pins with behavior tests (see {@link
 * KeepADBWifiGatedDiscoveryContractTest} and {@link KeepADBNetworkContractTest} for what remains
 * static and why), so that constraint no longer applies.
 */
interface KeepADBWifiProbe {
    boolean isWifiConnected();
}
