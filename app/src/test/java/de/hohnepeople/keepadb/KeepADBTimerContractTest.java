package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * #1011: static contracts of the Wifi-ADB timer (#1000) that behavior tests do not pin.
 *
 * <ul>
 *   <li>Every decision whether Keep-Alive applies reads the effective gate (stored setting or a
 *       running timer), never the stored setting alone; swapping one back stays green in the
 *       behavior tests that do not run a timer through that site.</li>
 *   <li>The write hook {@link KeepADB} calls under its monitor stays a pure preference write: no
 *       surface refresh, no transition, no notification, no further toggle.</li>
 * </ul>
 */
public class KeepADBTimerContractTest {
    private static final String MAIN = "app/src/main/java/de/hohnepeople/keepadb/";

    /** Decision sites and how many effective reads each makes; a swap changes the count. */
    private static final Map<String, Integer> EFFECTIVE_SITES = new LinkedHashMap<>();

    static {
        EFFECTIVE_SITES.put("KeepADBEndpointCoordinator.java", 3);
        EFFECTIVE_SITES.put("KeepADBWidgetState.java", 1);
        EFFECTIVE_SITES.put("KeepADBSelfPairCleanup.java", 1);
        EFFECTIVE_SITES.put("KeepADBNetworkTrustPrompt.java", 1);
        EFFECTIVE_SITES.put("KeepADBService.java", 5);
        EFFECTIVE_SITES.put("KeepADBUntrustedShutdown.java", 2);
        EFFECTIVE_SITES.put("BootReceiver.java", 1);
        EFFECTIVE_SITES.put("KeepADB.java", 1);
    }

    /**
     * Sites that may read the stored setting, with the reason: the widget tile shows what its tap
     * flips (#1007). Every other decision site reads it never.
     */
    private static final Map<String, Integer> STORED_READS_ALLOWED = new LinkedHashMap<>();

    static {
        STORED_READS_ALLOWED.put("KeepADBWidgetState.java", 1);
    }

    @Test
    public void everyKeepAliveDecisionSiteReadsTheEffectiveGate() throws IOException {
        for (Map.Entry<String, Integer> site : EFFECTIVE_SITES.entrySet()) {
            String source = read(MAIN + site.getKey());
            assertEquals(site.getKey() + " must decide on isKeepAliveEffective (#1000)",
                    (int) site.getValue(), count(source, "KeepADBPreferences.isKeepAliveEffective("));
            int stored = count(source, "KeepADBPreferences.isKeepAliveEnabled(");
            assertEquals(site.getKey() + " must not decide on the stored setting alone",
                    (int) STORED_READS_ALLOWED.getOrDefault(site.getKey(), 0), stored);
        }
    }

    @Test
    public void theWidgetReadsTheStoredSettingOnlyForTheTileItFlips() throws IOException {
        String source = read(MAIN + "KeepADBWidgetState.java");
        int at = source.indexOf("KeepADBPreferences.isKeepAliveEnabled(");
        assertTrue(at >= 0);
        String projection = methodBody(source, "static KeepADBWidgetState project(Context context, boolean");
        assertTrue("The stored read feeds the state's keepAliveSetting",
                projection.contains("KeepADBPreferences.isKeepAliveEnabled(app), availability"));
    }

    @Test
    public void theWriteHookUnderTheKeepAdbMonitorIsAPurePreferenceWrite() throws IOException {
        String toggle = read(MAIN + "KeepADBManualToggle.java");
        String timer = read(MAIN + "KeepADBTimer.java");
        List<String> hookPath = Arrays.asList(
                methodBody(toggle, "static void onWriteApplied("),
                methodBody(timer, "static void onWriteApplied("),
                methodBody(timer, "private static void supersedeEnd("),
                methodBody(timer, "private static void clearShutdownPending("));
        List<String> forbidden = Arrays.asList("refresh", "Surfaces", "KeepADBService",
                "KeepADBWidget", "Notification", "cancelExpired", "setEnabled(", "finishIfExpired",
                "settleShutdown", "scheduleExpiryAlarm", "cancelExpiryAlarm", "deliverPendingNotice",
                "KeepADBForceMode", "Handler", "KeepADBDiagnostics");
        for (String body : hookPath) {
            for (String call : forbidden) {
                assertFalse("The hook runs under the KeepADB monitor; no '" + call + "' in:\n" + body,
                        body.contains(call));
            }
        }
    }

    @Test
    public void keepAdbCallsTheWriteHookOnlyInsideApplyNow() throws IOException {
        String source = read(MAIN + "KeepADB.java");
        assertEquals(1, count(source, "KeepADBManualToggle.onWriteApplied("));
        String applyNow = methodBody(source, "private static synchronized ToggleResult applyNow(");
        assertTrue(applyNow.contains("KeepADBManualToggle.onWriteApplied("));
    }

    private static int count(String source, String needle) {
        int count = 0;
        for (int at = source.indexOf(needle); at >= 0; at = source.indexOf(needle, at + 1)) count++;
        return count;
    }

    private static String methodBody(String source, String signature) {
        int methodStart = source.indexOf(signature);
        assertTrue("Missing method: " + signature, methodStart >= 0);
        int openingBrace = source.indexOf('{', methodStart);
        int depth = 0;
        for (int i = openingBrace; i < source.length(); i++) {
            char current = source.charAt(i);
            if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return source.substring(methodStart, i + 1);
            }
        }
        throw new AssertionError("Missing closing brace: " + signature);
    }

    private static Path projectPath(String relativePath) {
        Path directory = Paths.get("").toAbsolutePath();
        while (directory != null && !Files.exists(directory.resolve("settings.gradle"))) {
            directory = directory.getParent();
        }
        if (directory == null) throw new IllegalStateException("Could not locate project root");
        return directory.resolve(relativePath);
    }

    private static String read(String relativePath) throws IOException {
        return new String(Files.readAllBytes(projectPath(relativePath)), StandardCharsets.UTF_8);
    }
}
