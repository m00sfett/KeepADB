package de.hohnepeople.keepadb;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Static contract for issue #250: the deprecated (API 31+) {@code
 * ConnectivityManager.getAllNetworks()} enumeration must not remain on the normal
 * connectivity path in {@link KeepADBService} or {@link KeepADBEndpoint} -- both now delegate
 * to the single {@link KeepADBNetwork} tracker, which uses callback-based tracking instead.
 *
 * <p>#596 (codequality review CQ-03) reduced this class: it used to also assert that specific
 * delegation calls (e.g. {@code "KeepADBNetwork.get(context).isWifiConnected()"}) are textually
 * present in {@code KeepADBService}/{@code KeepADBEndpoint}, and a whole separate method
 * re-asserted {@code isWifiConnected()}'s fallback structure the same way. Both kinds of
 * assertion proved the call sites are spelled out in source, not that they actually delegate at
 * runtime. Removed in favor of existing/added behavior coverage:
 * <ul>
 *   <li>{@code KeepADBService.isWifiConnected()}'s delegation and its synchronous {@code
 *       WifiInfo} fallback (the removed {@code isWifiConnectedKeepsTheWifiIpAddressFallback}) are
 *       already driven end-to-end, with a real Robolectric {@code ConnectivityManager}/{@code
 *       WifiManager}, by {@link KeepADBNetworkRobustnessBehaviorTest} and {@link
 *       KeepADBStartupRaceWifiFallbackTest}.</li>
 *   <li>{@code KeepADBEndpoint}'s delegation to {@code KeepADBNetwork.getWifiIpv4Address()}/{@code
 *       isActiveWifiAddress()} (the removed {@code assertTrue(endpoint.contains(...))} lines) had
 *       no prior behavioral equivalent -- {@link KeepADBActiveWifiAddressStalenessTest} and
 *       {@link KeepADBEndpointAddressBindingTest} only ever exercised {@link KeepADBNetwork}'s own
 *       decision logic directly, never through {@link KeepADBEndpoint}'s production entry points.
 *       {@link KeepADBEndpointNetworkDelegationBehaviorTest} (#596, new) closes that gap.</li>
 * </ul>
 * The remaining {@code getAllNetworks} absence check and {@link
 * #networkTrackingAvoidsTheApi31OnlyClearCapabilitiesCall()} stay intentionally static: both are
 * bans on specific deprecated/API-31-only platform methods for a minSdk-30 app, not assertions
 * about a computed runtime value -- Robolectric's shadow layer (pinned to API 34 for these tests,
 * #286) would happily run either banned call without the {@code NoSuchMethodError} a real API 30
 * device throws, so no behavior test at this project's current test layer could ever observe the
 * regression these two bans exist to catch.
 */
public class KeepADBNetworkContractTest {

    @Test
    public void deprecatedGetAllNetworksIsRemovedFromTheNormalConnectivityPath() throws IOException {
        String service = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBService.java");
        String endpoint = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBEndpoint.java");

        assertFalse(service.contains("getAllNetworks"));
        assertFalse(endpoint.contains("getAllNetworks"));
    }

    /** #676: the VPN transport uses KeepADBNetwork's callback tracking, not the deprecated enumeration. */
    @Test
    public void vpnTransportUsesCallbackTrackingInsteadOfGetAllNetworks() throws IOException {
        String vpnTransport = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBVpnTransport.java");
        String network = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBNetwork.java");

        assertFalse(vpnTransport.contains("getAllNetworks"));
        // A default request requires NET_CAPABILITY_NOT_VPN, which no VPN network has; without
        // removing it the VPN callback would never fire on a real device.
        assertTrue(network.contains("addTransportType(NetworkCapabilities.TRANSPORT_VPN)"));
        assertTrue(network.contains("removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)"));
    }

    @Test
    public void networkTrackingAvoidsTheApi31OnlyClearCapabilitiesCall() throws IOException {
        String network = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBNetwork.java");
        // NetworkRequest.Builder#clearCapabilities() was added in API 31; minSdk is 30, so
        // using it unconditionally would throw NoSuchMethodError on real API 30 devices even
        // though it compiles fine and passes unit tests against the (API 35) mockable jar.
        // Checked as an actual call (a leading dot), not a bare substring match, since the
        // class javadoc legitimately mentions the method name itself to explain why it's
        // avoided (via a "Builder#clearCapabilities()" javadoc {@code} link, not a "."-call).
        assertFalse(network.contains(".clearCapabilities("));
        assertTrue(network.contains("registerDefaultNetworkCallback"));
        assertTrue(network.contains("addTransportType(NetworkCapabilities.TRANSPORT_WIFI)"));
        assertTrue(network.contains("hasTransport(NetworkCapabilities.TRANSPORT_WIFI)"));
        assertTrue(network.contains("!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)"));
        assertTrue(network.contains("resetForTesting"));
    }

    private static String read(String relativePath) throws IOException {
        Path directory = Paths.get("").toAbsolutePath();
        while (directory != null && !Files.exists(directory.resolve("settings.gradle"))) {
            directory = directory.getParent();
        }
        if (directory == null) {
            throw new IllegalStateException("Could not locate project root");
        }
        return new String(Files.readAllBytes(directory.resolve(relativePath)), StandardCharsets.UTF_8);
    }
}
