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
 * Static contract for issue #245: the trusted-network allowlist must only ever gate
 * *automatic* re-enable call sites, never a manual/explicit toggle -- and must not require
 * a new {@code KeepADB.State} value, since the exhaustive switches in {@link
 * KeepADBTileService} and {@link KeepADBWidget} (plus {@link KeepADBMultiStateContractTest})
 * would need updating for every existing and future surface if it did.
 */
public class KeepADBTrustedNetworkContractTest {

    @Test
    public void automaticReEnableCallSitesAreGuarded() throws IOException {
        String service = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBService.java");
        String endpoint = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBEndpoint.java");
        String usbHandover = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBUsbHandover.java");

        assertTrue(service.contains("KeepADBTrustedNetwork.isCurrentNetworkTrusted(this)"));
        assertTrue(service.contains("KeepADBTrustedNetwork.isCurrentNetworkTrusted(KeepADBService.this)"));
        assertTrue(endpoint.contains("KeepADBTrustedNetwork.isCurrentNetworkTrusted(appContext)"));
        assertTrue(usbHandover.contains("KeepADBTrustedNetwork.isCurrentNetworkTrusted(appContext)"));
        // The manual "Enable WLAN-ADB" notification action must stay ungated: it's a direct
        // user request, not an automatic re-enable, so it must never mention the allowlist.
        String manualActionBody = methodBody(usbHandover, "static boolean handleManualAction(Context context) {");
        assertFalse(manualActionBody.contains("KeepADBTrustedNetwork"));
    }

    @Test
    public void networkLossInvalidatesVerifiedTrustBeforeAnyEarlyReturn() throws IOException {
        String service = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBService.java");
        String onLost = methodBody(service, "public void onLost(Network network) {");
        // Ordering alone would also accept an invalidation guarded by a condition.
        // Require the production call as the first statement, outside any conditional.
        String statements = onLost.substring(onLost.indexOf('{') + 1)
                .replaceAll("(?s)/\\*.*?\\*/|//[^\\r\\n]*", "").trim();
        assertTrue("Trust invalidation must be unconditional at callback entry",
                statements.startsWith("KeepADBTrustedNetwork.forgetVerifiedTrust();"));
        int invalidation = onLost.indexOf("KeepADBTrustedNetwork.forgetVerifiedTrust();");
        assertTrue("onLost must discard verified trust", invalidation >= 0);
        // Locate the anchors before comparing against them: a plain "invalidation < indexOf(...)"
        // silently turns a vanished anchor into -1 and then reports a misleading ordering
        // failure, when the real cause is that the construct this test orders against is gone.
        int foregroundGate = onLost.indexOf("if (!foregroundReady)");
        assertTrue("onLost no longer contains the foreground-ready gate this test orders "
                + "against -- update this contract test to the new control flow", foregroundGate >= 0);
        int earlyReturn = onLost.indexOf("return;");
        assertTrue("onLost no longer contains an early return this test orders against -- "
                + "update this contract test to the new control flow", earlyReturn >= 0);
        assertTrue("Trust invalidation must precede the foreground gate",
                invalidation < foregroundGate);
        assertTrue("Trust invalidation must precede any early return",
                invalidation < earlyReturn);
    }

    /**
     * #354: {@code ConnectivityManager} gives no ordering guarantee between {@code onLost(old)}
     * and {@code onAvailable(new)}. Invalidating only in {@code onLost} (#313) therefore left a
     * window in which {@code onAvailable} -&gt; {@code recheckAndEnable()} -&gt; {@code
     * isCurrentNetworkTrusted()} evaluated the new connection against the old connection's cache.
     * The invalidation must be unconditional and first, i.e. ahead of every statement in the
     * callback that can reach the cache.
     */
    @Test
    public void networkAvailabilityInvalidatesVerifiedTrustBeforeAnyTrustRead() throws IOException {
        String service = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBService.java");
        String onAvailable = methodBody(service, "public void onAvailable(Network network) {");
        String statements = onAvailable.substring(onAvailable.indexOf('{') + 1)
                .replaceAll("(?s)/\\*.*?\\*/|//[^\\r\\n]*", "").trim();
        assertTrue("Trust invalidation must be the unconditional first statement of onAvailable",
                statements.startsWith("KeepADBTrustedNetwork.forgetVerifiedTrust();"));
        int invalidation = onAvailable.indexOf("KeepADBTrustedNetwork.forgetVerifiedTrust();");
        assertTrue("onAvailable must discard verified trust", invalidation >= 0);
        // Locate the anchor first, so a vanished recheck call reports its own cause instead of
        // a misleading ordering failure (same reasoning as the onLost test above).
        int recheck = onAvailable.indexOf("recheckAndEnable();");
        assertTrue("onAvailable no longer calls recheckAndEnable() -- update this contract test "
                + "to the new control flow", recheck >= 0);
        assertTrue("Trust invalidation must precede the recheck that reads the trust cache",
                invalidation < recheck);
    }

    /**
     * #354, secondary finding: {@code onLost}/{@code onAvailable} only fire while {@link
     * KeepADBService} has its callback registered, yet {@code KeepADBEndpoint} and {@code
     * KeepADBUsbHandover} read the trust policy on paths that do not require a running service.
     * The masked-BSSID fallback is therefore gated on a live invalidator, and this pins that
     * KeepADBService is what actually declares -- and withdraws -- that state.
     */
    @Test
    public void theMaskedBssidFallbackIsTiedToTheLiveNetworkCallback() throws IOException {
        String service = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBService.java");
        String register = methodBody(service, "private void registerNetworkCallback() {");
        String unregister = methodBody(service, "private void unregisterNetworkCallback() {");
        assertTrue("registerNetworkCallback() must announce the live invalidator",
                register.contains("KeepADBTrustedNetwork.setVerifiedTrustObserverActive(true);"));
        assertTrue("unregisterNetworkCallback() must withdraw it again",
                unregister.contains("KeepADBTrustedNetwork.setVerifiedTrustObserverActive(false);"));

        // Ordering in both methods must keep the unsafe combination ("fallback offered, nobody
        // watching") impossible: announce only after registering, withdraw before unregistering.
        int registerCall = register.indexOf("cm.registerNetworkCallback(");
        assertTrue("registerNetworkCallback() no longer registers the callback the way this "
                + "contract test orders against", registerCall >= 0);
        assertTrue("The fallback must not be announced before the callback is registered",
                registerCall < register.indexOf("KeepADBTrustedNetwork.setVerifiedTrustObserverActive(true);"));
        int unregisterCall = unregister.indexOf("cm.unregisterNetworkCallback(");
        assertTrue("unregisterNetworkCallback() no longer unregisters the callback the way this "
                + "contract test orders against", unregisterCall >= 0);
        assertTrue("The fallback must be withdrawn before the callback stops watching",
                unregister.indexOf("KeepADBTrustedNetwork.setVerifiedTrustObserverActive(false);")
                        < unregisterCall);
    }

    private static String methodBody(String source, String signature) {
        int methodStart = source.indexOf(signature);
        assertTrue("Missing method: " + signature, methodStart >= 0);
        int openingBrace = source.indexOf('{', methodStart);
        assertTrue("Missing opening brace: " + signature, openingBrace > methodStart);
        int depth = 0;
        int methodEnd = -1;
        for (int i = openingBrace; i < source.length(); i++) {
            char current = source.charAt(i);
            if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                methodEnd = i;
                break;
            }
        }
        assertTrue("Missing closing brace: " + signature, methodEnd > openingBrace);
        return source.substring(methodStart, methodEnd + 1);
    }

    @Test
    public void keepAdbFacadeNeverReferencesTheAllowlist() throws IOException {
        // KeepADB.setEnabled()/applyNow() must keep unconditionally honoring an explicit
        // call -- the guard belongs at call sites that decide to auto-invoke it, not inside
        // the toggle facade itself, so manual toggling is never affected by network trust.
        String keepAdb = read("app/src/main/java/de/hohnepeople/keepadb/KeepADB.java");
        assertFalse(keepAdb.contains("KeepADBTrustedNetwork"));
    }

    @Test
    public void noNewStateEnumValueWasIntroduced() throws IOException {
        String keepAdb = read("app/src/main/java/de/hohnepeople/keepadb/KeepADB.java");
        int enumStart = keepAdb.indexOf("enum State {");
        assertTrue(enumStart >= 0);
        String enumDeclaration = keepAdb.substring(enumStart, keepAdb.indexOf('}', enumStart) + 1);
        assertTrue(enumDeclaration.contains("PERMISSION_MISSING"));
        assertTrue(enumDeclaration.contains("ENABLED_DISCONNECTED"));
        assertTrue(enumDeclaration.contains("ENABLED_CONNECTED"));
        assertFalse(enumDeclaration.contains("UNTRUSTED"));
        assertFalse(enumDeclaration.contains("BLOCKED"));
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
