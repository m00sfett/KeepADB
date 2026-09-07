package de.hohnepeople.keepadb;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Regression contract for issue #285: a mesh-roam reverification triggered from {@code
 * KeepADBService.onCapabilitiesChanged()} and both outcomes of {@code
 * KeepADBNotification.verifyCachedEndpointAsync()} previously only reached logcat via {@code
 * Log.d}/{@code Log.w}, leaving no persisted {@code KeepADBDiag} trail to reconstruct a roam or
 * an endpoint-staleness decision after the fact. The fix adds a {@code
 * KeepADBDiagnostics.event(...)} call at each of these three points, subject to the diagnostics
 * class's existing redaction and ring-buffer bounds.
 */
public class KeepADBReverifyDiagnosticsContractTest {

    @Test
    public void capabilitiesChangedRecordsAPersistedDiagnosticsEvent() throws IOException {
        String service = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBService.java");
        String callbackBody = methodBody(service,
                "networkCallback = new ConnectivityManager.NetworkCallback() {");
        String onCapabilitiesChangedBody = methodBody(callbackBody,
                "public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {");

        assertTrue("must persist a diagnostics event when a capabilities change triggers "
                        + "reverification, not just Log.d",
                onCapabilitiesChangedBody.contains("KeepADBDiagnostics.event("));
        assertTrue(onCapabilitiesChangedBody.contains("\"wifi_change\""));
        assertTrue(onCapabilitiesChangedBody.contains("\"capabilities_changed\""));
        assertTrue("the diagnostics call must precede the actual reverification trigger",
                onCapabilitiesChangedBody.indexOf("KeepADBDiagnostics.event(")
                        < onCapabilitiesChangedBody.indexOf("KeepADBNotification.verifyEndpointHealth("));
    }

    @Test
    public void verifyCachedEndpointRecordsAReachableEvent() throws IOException {
        String body = verifyCachedEndpointAsyncBody();

        int reachableBranch = body.indexOf("if (reachable) {");
        int branchEnd = body.indexOf("Log.w(TAG,", reachableBranch);
        assertTrue(reachableBranch >= 0);
        assertTrue(branchEnd > reachableBranch);
        String reachableBody = body.substring(reachableBranch, branchEnd);

        assertTrue("the still-reachable outcome must be persisted, not silently dropped",
                reachableBody.contains("KeepADBDiagnostics.event("));
        assertTrue(reachableBody.contains("\"endpoint_verified\""));
        assertTrue(reachableBody.contains("\"reachable\""));
        assertTrue("detail must carry the verified host and port",
                reachableBody.contains("host=\" + host") && reachableBody.contains("port=\" + port"));
    }

    @Test
    public void verifyCachedEndpointRecordsAStaleInvalidatedEvent() throws IOException {
        String body = verifyCachedEndpointAsyncBody();

        int staleLog = body.indexOf("no longer reachable; invalidating and rediscovering");
        assertTrue(staleLog >= 0);
        String staleBody = body.substring(staleLog);

        assertTrue("the stale/invalidated outcome must be persisted, not just Log.w",
                staleBody.contains("KeepADBDiagnostics.event("));
        assertTrue(staleBody.contains("\"endpoint_verified\""));
        assertTrue(staleBody.contains("\"stale_invalidated\""));
        assertTrue("detail must carry the invalidated host and port",
                staleBody.contains("host=\" + host") && staleBody.contains("port=\" + port"));
        assertTrue("the diagnostics call must come before the cached endpoint is cleared",
                staleBody.indexOf("KeepADBDiagnostics.event(") < staleBody.indexOf("currentHost = null;"));
    }

    private static String verifyCachedEndpointAsyncBody() throws IOException {
        String notification = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBNotification.java");
        return methodBody(notification, "private static void verifyCachedEndpointAsync(");
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
