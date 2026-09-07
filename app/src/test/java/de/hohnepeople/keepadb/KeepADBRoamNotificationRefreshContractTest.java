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
 * Regression contract for issue #276: after a Wi-Fi mesh roam (BSSID change, same SSID),
 * {@code KeepADBTileService.updateTile()} picked up the rotated adbd endpoint correctly (it
 * re-verifies on every {@code onStartListening()}), but the persistent notification kept
 * showing the old, now-unreachable port -- live-verified via {@code adb connect} in the issue.
 *
 * <p>Root cause: a same-SSID BSSID-only roam typically keeps the same {@link
 * android.net.Network} object, so {@code ConnectivityManager.NetworkCallback#onAvailable}/{@code
 * #onLost} -- the only callbacks {@link KeepADBService}'s Wi-Fi network callback overrode --
 * never fire for it (confirmed by {@link KeepADBNetwork}'s own javadoc: only {@code
 * onCapabilitiesChanged}/{@code onLinkPropertiesChanged} are guaranteed to fire for every network
 * change, including ones on an already-tracked network). With no event-driven trigger, the
 * notification's cached endpoint was only ever re-verified by the 60s heartbeat
 * ({@code KeepADBService.heartbeatNow()} -&gt; {@code KeepADBNotification.verifyEndpointHealth()}),
 * while the Tile actively re-verifies the instant the user opens Quick Settings -- explaining the
 * observed asymmetry.
 *
 * <p>The fix wires {@code onCapabilitiesChanged} (which does fire on a BSSID roam) to the same
 * {@code verifyEndpointHealth()} re-verification the heartbeat already uses, so the notification
 * self-heals close to immediately instead of waiting up to a minute.
 */
public class KeepADBRoamNotificationRefreshContractTest {

    @Test
    public void capabilitiesChangedReverifiesTheCachedEndpointForARoam() throws IOException {
        String service = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBService.java");
        String callbackBody = methodBody(service,
                "networkCallback = new ConnectivityManager.NetworkCallback() {");

        assertTrue("registerNetworkCallback()'s callback must react to capability changes -- "
                        + "the only platform-guaranteed callback for a same-Network BSSID roam",
                callbackBody.contains(
                        "public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {"));

        String onCapabilitiesChangedBody = methodBody(callbackBody,
                "public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {");

        assertTrue("must re-verify (not just cache) the endpoint on a capabilities change",
                onCapabilitiesChangedBody.contains("KeepADBNotification.verifyEndpointHealth("));
        assertTrue("must stay guarded like the other callbacks so pre-foreground-promotion "
                        + "callbacks are ignored",
                onCapabilitiesChangedBody.contains("foregroundReady"));
    }

    @Test
    public void capabilitiesChangedIsThrottledToAvoidVerifyingOnEveryRssiBlip() throws IOException {
        String service = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBService.java");
        String callbackBody = methodBody(service,
                "networkCallback = new ConnectivityManager.NetworkCallback() {");
        String onCapabilitiesChangedBody = methodBody(callbackBody,
                "public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {");

        assertTrue("onCapabilitiesChanged fires far more often than a real roam (e.g. on every "
                        + "RSSI update), so it must be throttled before re-verifying",
                onCapabilitiesChangedBody.contains("SystemClock.elapsedRealtime()"));
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

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue("Missing: " + signature, start >= 0);
        int openingBrace = source.indexOf('{', start);
        assertTrue("Missing opening brace: " + signature, openingBrace > start);
        int end = findMatchingBrace(source, openingBrace);
        assertTrue("Missing closing brace: " + signature, end > openingBrace);
        return source.substring(start, end + 1);
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
}
