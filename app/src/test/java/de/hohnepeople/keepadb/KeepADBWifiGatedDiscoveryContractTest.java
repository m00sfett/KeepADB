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
 * <p>#596 (codequality review CQ-03): this class used to hold four source-content assertions --
 * grepping method bodies for snippet presence/order/occurrence count instead of observing runtime
 * behavior, so a semantically equivalent refactor could turn them red, and a coincidental text
 * match could turn them green without the guard actually working. Three were migrated to real
 * behavior tests that drive the production entry points end-to-end and observe the actual
 * outcome instead:
 * <ul>
 *   <li>{@code refreshInternalChecksWifiConnectionBeforeStartingDiscovery} ->
 *       {@link KeepADBWifiGatedDiscoveryBehaviorTest#refreshNeverStartsDiscoveryWithoutAnActiveWifiConnection()}
 *       / {@link KeepADBWifiGatedDiscoveryBehaviorTest#refreshStartsDiscoveryOnceWifiIsConnected()}
 *       (already existed pre-#596).</li>
 *   <li>{@code scheduleRetryLockedAbortsWithoutAnActiveWifiConnection} -> the three
 *       {@code retry*} tests added to {@link KeepADBWifiGatedDiscoveryBehaviorTest} by #596,
 *       which drive a real discovery attempt to {@code onUnavailable()} via the {@link
 *       KeepADBFakeNsdProbe}/{@link KeepADBFakeScheduler} seam and observe whether a second
 *       discovery attempt actually starts after the retry delay, both with and without Wi-Fi
 *       dropping at each of the two check sites.</li>
 *   <li>{@code recoveryPulseChecksTheActualWifiTransportInAdditionToTrustedNetwork} -> the two
 *       {@code pulseIs*} tests #596 added to {@link KeepADBEndpointRecoveryPulseBehaviorTest},
 *       which observe whether {@code maybeSendRecoveryPulse()} actually attempts its AUS write
 *       (or not) for each combination of Wi-Fi connectivity and trusted-network state. The pure
 *       *order* between those two checks was dropped rather than replaced: both are early returns
 *       with no side effect in between, so their relative order has no observable runtime
 *       difference for a test to pin -- the source-order assertion proved nothing beyond "both
 *       checks are textually present", which the two behavior tests above already prove more
 *       directly (each check independently blocks the pulse regardless of the other).</li>
 * </ul>
 *
 * <p>The remaining {@link #reconnectAndRoamPathsInKeepADBServiceRemainUntouched()} stays a
 * source-content contract deliberately: it is a regression guard against a *future* change
 * re-introducing a Wi-Fi gate in front of {@code KeepADBService}'s two already-event-driven
 * paths (see its own javadoc). Half of what it protects -- the {@code onAvailable()} ->
 * {@code recheckAndEnable()} call -- is meanwhile also exercised behaviorally through a real
 * {@code ConnectivityManager.NetworkCallback} in {@link
 * KeepADBServiceLifecycleRobolectricTest#networkCallbackPromptsForAnUntrustedAccessPointEvenWhileAlreadyActive()}
 * (that test's untrusted-network prompt only fires if {@code recheckAndEnable()} actually ran).
 * The other half -- {@code onCapabilitiesChanged()} -> {@code KeepADBNotification.verifyEndpointHealth()}
 * -- has no behavioral equivalent: driving it through a real {@code NetworkCallback} spawns a raw
 * background verification {@code Thread} with a real socket check, which {@link
 * KeepADBNotificationRobolectricTest#wifiNetworkCallbackIsRegisteredAgainstARealConnectivityManager()}
 * already documents as deliberately out of scope for Robolectric here (hang/flake risk); a fix
 * would need a materially larger DI change than this issue's bounded scope allows. Kept as an
 * intentionally static architecture check per AGENTS.md/#286: its purpose (no gate added in
 * front of these two calls) is clearly named and a text check is well suited to it -- the
 * property under test is literally "the call site's text is unconditional", not a runtime value.
 */
public class KeepADBWifiGatedDiscoveryContractTest {

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
