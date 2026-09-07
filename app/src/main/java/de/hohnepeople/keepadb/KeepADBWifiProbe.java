package de.hohnepeople.keepadb;

/**
 * Test-only override for {@link KeepADBNetwork#isWifiConnected()} (#303), matching the existing
 * {@link KeepADBNsdProbe}/{@link KeepADBScheduler} seam pattern {@link KeepADBEndpointDiscoveryTest}
 * already uses: production always runs the real transport-capability check in {@link
 * KeepADBNetwork}, and only a test replaces it, via {@link
 * KeepADBNetwork#setWifiConnectivityOverrideForTesting(KeepADBWifiProbe)}, to drive every caller
 * of {@code KeepADBService.isWifiConnected(Context)} (including {@link
 * KeepADBNotification#refreshInternal} and {@code scheduleRetryLocked}) with a deterministic
 * Wi-Fi state instead of a real, singleton {@link android.net.ConnectivityManager}.
 *
 * <p>Deliberately placed underneath {@link KeepADBNetwork} rather than as a seam on {@code
 * KeepADBNotification} itself: {@link KeepADBWifiGatedDiscoveryContractTest} and {@link
 * KeepADBNetworkContractTest} already pin the exact source text of every {@code
 * KeepADBService.isWifiConnected(...)} call site and of {@code KeepADBService.isWifiConnected()}'s
 * own method body as a fast regression guard for #296/#250; none of those call sites or that
 * method body change here, so those contract tests keep passing unmodified.
 */
interface KeepADBWifiProbe {
    boolean isWifiConnected();
}
