package de.hohnepeople.keepadb;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Regression contract for issue #296: {@code KeepADBNotification.refreshInternal()} started
 * discovery (and {@code scheduleRetryLocked()} kept re-scheduling it with 2s/5s backoff)
 * unconditionally as soon as {@code KeepADB.isEnabled()} was true, with no check of the actual
 * Wi-Fi connection state. Without Wi-Fi, adbd's wireless-debugging listener can never be
 * reachable, so this was a guaranteed-to-fail discovery attempt followed by an unbounded retry
 * loop -- including right after a service restart (process kill, reboot) while Wi-Fi was already
 * off. {@code KeepADBEndpoint.maybeSendRecoveryPulse()} had the same gap: it gated on the
 * "trusted network" allowlist policy only, which says nothing about whether the device is still
 * on a Wi-Fi transport at all.
 *
 * <p>Chosen test style: a source-content contract, matching the existing pattern this codebase
 * already uses for equivalent internal-state-machine guarantees (see
 * {@link KeepADBRoamNotificationRefreshContractTest}). All three fixed methods are private
 * static/instance methods of classes that manage process-wide static discovery/retry state via a
 * real {@code KeepADBEndpoint} they construct internally (not the injectable
 * {@code KeepADBNsdProbe}/{@code KeepADBScheduler} seam {@link KeepADBEndpointDiscoveryTest} uses)
 * -- there is no seam today to observe "did startDiscoveryDirectLocked() actually get skipped"
 * from outside without either reflection-driven poking at static fields (fragile, and already
 * avoided for exactly this reason per {@link KeepADBEndpointDiscoveryTest}'s own class javadoc)
 * or a larger DI refactor that is out of scope for this fix.
 *
 * <p>The fix reuses the existing {@code KeepADBService.isWifiConnected(Context)} check (already
 * used by {@code recheckAndEnable()}/the content observer) rather than introducing a second,
 * duplicate Wi-Fi check.
 */
public class KeepADBWifiGatedDiscoveryContractTest {

    @Test
    public void refreshInternalChecksWifiConnectionBeforeStartingDiscovery() throws IOException {
        String source = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBNotification.java");
        String refreshInternalBody = methodBody(source,
                "private static synchronized void refreshInternal(Context context, Object discoveryOwner) {");

        int wifiCheckIndex = refreshInternalBody.indexOf("KeepADBService.isWifiConnected(appContext)");
        int startDiscoveryIndex = refreshInternalBody.indexOf("startDiscoveryDirectLocked(appContext, manager, discoveryOwner);");

        assertTrue("refreshInternal() must check the active Wi-Fi connection state before "
                        + "starting discovery -- there is nothing for adbd's listener to be "
                        + "reachable on otherwise",
                wifiCheckIndex >= 0);
        assertTrue("startDiscoveryDirectLocked() must still be reachable for the connected case",
                startDiscoveryIndex >= 0);
        assertTrue("the Wi-Fi check must gate the discovery start, i.e. appear before it in "
                        + "source order",
                wifiCheckIndex < startDiscoveryIndex);
    }

    @Test
    public void scheduleRetryLockedAbortsWithoutAnActiveWifiConnection() throws IOException {
        String source = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBNotification.java");
        String scheduleRetryLockedBody = methodBody(source,
                "private static void scheduleRetryLocked(Context appContext, NotificationManager manager) {");

        long wifiCheckOccurrences = countOccurrences(scheduleRetryLockedBody,
                "KeepADBService.isWifiConnected(appContext)");

        assertTrue("scheduleRetryLocked() must abort the retry chain (both when first asked to "
                        + "schedule, and inside the scheduled runnable itself) once there is no "
                        + "active Wi-Fi connection -- otherwise the 2s/5s backoff loop runs "
                        + "forever with Wi-Fi off",
                wifiCheckOccurrences >= 2);
    }

    @Test
    public void recoveryPulseChecksTheActualWifiTransportInAdditionToTrustedNetwork() throws IOException {
        String source = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBEndpoint.java");
        String methodBody = methodBody(source, "private void maybeSendRecoveryPulse(long generation) {");

        int wifiCheckIndex = methodBody.indexOf("KeepADBService.isWifiConnected(appContext)");
        int trustedNetworkCheckIndex = methodBody.indexOf("KeepADBTrustedNetwork.isCurrentNetworkTrusted(appContext)");

        assertTrue("maybeSendRecoveryPulse() must check the actual Wi-Fi transport in addition "
                        + "to the trusted-network allowlist policy -- 'trusted network' says "
                        + "nothing about whether Wi-Fi is even connected right now",
                wifiCheckIndex >= 0);
        assertTrue("the existing trusted-network gate must remain in place (#245/#270 unaffected)",
                trustedNetworkCheckIndex >= 0);
        assertTrue("the Wi-Fi transport check must gate the pulse before the trusted-network "
                        + "check, i.e. appear first in source order",
                wifiCheckIndex < trustedNetworkCheckIndex);
    }

    /**
     * Regression guard: the event-driven reconnect path (#22/#192) and the mesh-roam
     * re-verification path (#276/#285) must stay untouched by this fix -- they are already
     * correctly Wi-Fi-state-driven by construction (they only ever fire in response to a real
     * platform {@code NetworkCallback} event), unlike the polling/backoff-based discovery retry
     * and recovery pulse this issue actually targets.
     */
    @Test
    public void reconnectAndRoamPathsInKeepADBServiceRemainUntouched() throws IOException {
        String service = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBService.java");
        String callbackBody = methodBody(service, "networkCallback = new ConnectivityManager.NetworkCallback() {");

        String onAvailableBody = methodBody(callbackBody, "public void onAvailable(Network network) {");
        assertTrue("#22/#192: Wi-Fi becoming available must still trigger recheckAndEnable() "
                        + "directly, with no added Wi-Fi gate in front of it",
                onAvailableBody.contains("recheckAndEnable();"));

        String onCapabilitiesChangedBody = methodBody(callbackBody,
                "public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {");
        assertTrue("#276/#285: a mesh roam (onCapabilitiesChanged) must still re-verify the "
                        + "cached endpoint directly, with no added Wi-Fi gate in front of it",
                onCapabilitiesChangedBody.contains("KeepADBNotification.verifyEndpointHealth("));
    }

    private static long countOccurrences(String haystack, String needle) {
        long count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
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
