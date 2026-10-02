package de.hohnepeople.keepadb;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
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
        // The manual "Enable Wifi-ADB" notification action must stay ungated: it's a direct
        // user request, not an automatic re-enable, so it must never mention the allowlist.
        String manualActionBody = methodBody(usbHandover, "static boolean handleManualAction(Context context) {");
        assertFalse(manualActionBody.contains("KeepADBTrustedNetwork"));
    }

    /**
     * #625: {@link KeepADBTrustedNetwork} holds no process-wide trust state. Every static field
     * must be a compile-time-style constant ({@code final}); a mutable static -- such as the
     * removed {@code lastVerifiedTrustedSsid} cache or its {@code verifiedTrustObserverActive}
     * gate -- would let one trust decision influence the next and is exactly what this forbids.
     */
    @Test
    public void trustedNetworkPolicyHoldsNoMutableProcessState() {
        for (Field field : KeepADBTrustedNetwork.class.getDeclaredFields()) {
            if (field.isSynthetic() || !Modifier.isStatic(field.getModifiers())) continue;
            assertTrue("Mutable static state in KeepADBTrustedNetwork: " + field.getName(),
                    Modifier.isFinal(field.getModifiers()));
        }
    }

    /**
     * #625: the Wi-Fi {@code NetworkCallback} and its (un)registration no longer invalidate or
     * announce anything to the trust policy -- there is nothing left to invalidate. Network
     * generation (#310) and the {@code availableWifiNetworks} bookkeeping stay exactly where they
     * were; this pins both halves so the removal cannot silently take either with it.
     */
    @Test
    public void networkCallbacksCarryNoTrustCacheInvalidation() throws IOException {
        String service = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBService.java");
        String onAvailable = methodBody(service, "public void onAvailable(Network network) {");
        String onLost = methodBody(service, "public void onLost(Network network) {");
        String register = methodBody(service, "private void registerNetworkCallback() {");
        String unregister = methodBody(service, "private void unregisterNetworkCallback() {");
        for (String body : new String[] { onAvailable, onLost, unregister }) {
            assertFalse("Network callback code must not call into the trust policy's state: "
                    + body.substring(0, body.indexOf('{')), body.contains("KeepADBTrustedNetwork."));
        }
        // registerNetworkCallback() contains onAvailable/onLost; check only its own statements.
        String registerOwn = register.substring(register.indexOf("cm.registerNetworkCallback("));
        assertFalse(registerOwn.contains("KeepADBTrustedNetwork."));
        assertFalse(service.contains("VerifiedTrust"));

        assertTrue(onAvailable.contains("availableWifiNetworks.add(network);"));
        assertTrue(onAvailable.contains("KeepADB.noteNetworkChanged();"));
        assertTrue(onLost.contains("availableWifiNetworks.remove(network);"));
        assertTrue(onLost.contains("KeepADB.noteNetworkChanged();"));
        assertTrue(unregister.contains("availableWifiNetworks.clear();"));
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

    /**
     * #714: the number, the own name and the band of an access point are display only. The trust
     * decision reads the stored BSSID (and the optional exact network name) and nothing else, so
     * none of the methods that decide may mention the own name.
     */
    @Test
    public void trustDecisionsNeverReadTheOwnNameOfAnEntry() throws IOException {
        String trusted = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBTrustedNetwork.java");
        String[] decisions = {
                "static boolean isCurrentNetworkTrusted(Context context) {",
                "static BlockReason getBlockReason(Context context) {",
                "private static boolean isTrusted(Context context, KeepADBNetworkIdentity identity) {",
                "private static boolean matchesAllowlist(Context context, String bssid) {",
                "private static boolean matchesSsidAllowlist(Context context, KeepADBNetworkIdentity identity) {",
        };
        for (String signature : decisions) {
            String body = methodBody(trusted, signature);
            assertFalse(signature + " must not read the own name", body.contains("customName"));
            assertFalse(signature + " must not read the own name", body.contains("_name"));
        }
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
        // #318 split this one out of ENABLED_DISCONNECTED; it is a UI/state-reporting split, not
        // a trust concept, so it must not be read as the allowlist leaking into the state enum.
        assertTrue(enumDeclaration.contains("OFF_KEEP_ALIVE_WAITING"));
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
