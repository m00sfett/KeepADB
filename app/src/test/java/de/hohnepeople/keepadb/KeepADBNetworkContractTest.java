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
 */
public class KeepADBNetworkContractTest {

    @Test
    public void deprecatedGetAllNetworksIsRemovedFromTheNormalConnectivityPath() throws IOException {
        String service = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBService.java");
        String endpoint = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBEndpoint.java");

        assertFalse(service.contains("getAllNetworks"));
        assertFalse(endpoint.contains("getAllNetworks"));

        assertTrue(service.contains("KeepADBNetwork.get(context).isWifiConnected()"));
        assertTrue(endpoint.contains("KeepADBNetwork.get(context).getWifiIpv4Address()"));
        assertTrue(endpoint.contains("KeepADBNetwork.get(context).isKnownLocalAddress(addr)"));
    }

    @Test
    public void isWifiConnectedKeepsTheWifiIpAddressFallback() throws IOException {
        String service = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBService.java");
        String methodBody = methodBody(service, "static boolean isWifiConnected(Context context) {");

        assertTrue(methodBody.contains("KeepADBNetwork.get(context).isWifiConnected()"));
        assertTrue(methodBody.contains("KeepADBEndpoint.getWifiIpAddress(context) != null"));
    }

    @Test
    public void networkTrackingAvoidsTheApi31OnlyClearCapabilitiesCall() throws IOException {
        String network = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBNetwork.java");
        // NetworkRequest.Builder#clearCapabilities() was added in API 31; minSdk is 30, so
        // using it unconditionally would throw NoSuchMethodError on real API 30 devices even
        // though it compiles fine and passes unit tests against the (API 35) mockable jar.
        assertFalse(network.contains("clearCapabilities()"));
        assertTrue(network.contains("registerDefaultNetworkCallback"));
        assertTrue(network.contains("addTransportType(NetworkCapabilities.TRANSPORT_WIFI)"));
        assertTrue(network.contains("hasTransport(NetworkCapabilities.TRANSPORT_WIFI)"));
        assertTrue(network.contains("!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)"));
        assertTrue(network.contains("resetForTesting"));
    }

    private static String methodBody(String source, String signature) {
        int methodStart = source.indexOf(signature);
        assertTrue("Missing method: " + signature, methodStart >= 0);
        int openingBrace = source.indexOf('{', methodStart);
        assertTrue("Missing opening brace: " + signature, openingBrace > methodStart);
        int methodEnd = findMatchingBrace(source, openingBrace);
        assertTrue("Missing closing brace: " + signature, methodEnd > openingBrace);
        return source.substring(methodStart, methodEnd + 1);
    }

    private static int findMatchingBrace(String source, int openingBrace) {
        int depth = 0;
        for (int i = openingBrace; i < source.length(); i++) {
            char current = source.charAt(i);
            if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
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
