package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.Test;

/**
 * #763: static contracts around the force mode that behavior tests cannot see because they are
 * about what does <em>not</em> exist: a second way to start it, a gate that writes, a manual
 * control that consults it, a component that is exported or holds a new permission.
 */
public class KeepADBForceContractTest {
    private static final String MAIN = "app/src/main/java/de/hohnepeople/keepadb/";

    @Test
    public void onlyTheDialogCanStartTheForceMode() throws IOException {
        List<String> callers = new ArrayList<>();
        try (Stream<Path> files = Files.list(projectPath(MAIN))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList())) {
                String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                if (source.contains("KeepADBForceMode.activate(")) callers.add(file.getFileName().toString());
            }
        }
        assertEquals("No intent, receiver, migration or assistant step may start or extend the mode"
                + " behind the warnings: " + callers, Arrays.asList("KeepADBForceDialog.java"), callers);
    }

    @Test
    public void manualControlsAndTheToggleFacadeNeverConsultTheForceMode() throws IOException {
        for (String file : new String[] {"KeepADB.java", "KeepADBTileService.java", "KeepADBWidget.java",
                "KeepADBToggleState.java"}) {
            String source = read(MAIN + file);
            assertFalse(file + " must not know the force mode (#245: manual controls are never gated)",
                    source.contains("KeepADBForce"));
        }
        String usbHandover = read(MAIN + "KeepADBUsbHandover.java");
        String manualAction = methodBody(usbHandover, "static boolean handleManualAction(Context context) {");
        assertFalse(manualAction.contains("KeepADBForce"));
    }

    @Test
    public void theGateAndTheRenderersOnlyReadThePureStateAndNeverTransition() throws IOException {
        // These run under the KeepADB / coordinator monitors (the write-time guard, the notification
        // rendering). A transition refreshes surfaces and would take those monitors in the other
        // order, so they may only read.
        String[] transitions = {"finishIfExpired(", "endNow(", "activate(", "restore(", "setStateListener("};
        for (String file : new String[] {"KeepADBTrustedNetwork.java", "KeepADBNotification.java",
                "KeepADBNetworkCardState.java", "KeepADBEndpointCoordinator.java"}) {
            String source = read(MAIN + file);
            for (String transition : transitions) {
                assertFalse(file + " must not call KeepADBForceMode." + transition,
                        source.contains("KeepADBForceMode." + transition));
            }
        }
        String notice = read(MAIN + "KeepADBForceNotice.java");
        String activeLine = methodBody(notice, "static String activeLine(Context context) {");
        assertTrue(activeLine.contains("KeepADBForceMode.status(context)"));
        assertFalse(activeLine.contains("finishIfExpired"));
        String service = read(MAIN + "KeepADBService.java");
        String guard = methodBody(service, "static boolean isAutoEnableStillPermittedIgnoringBackoff(Context context) {");
        assertFalse("The write-time guard reads the trust model, which reads the pure state",
                guard.contains("KeepADBForceMode"));
        String trust = read(MAIN + "KeepADBTrustedNetwork.java");
        String evaluateCurrent = methodBody(trust, "static Decision evaluateCurrent(Context context) {");
        assertTrue(evaluateCurrent.contains("KeepADBForceMode.isActive(context)"));
        String evaluate = methodBody(trust, "static Decision evaluate(Context context, KeepADBNetworkIdentity identity) {");
        assertFalse("evaluate() also answers 'is this prompt's network blocked?' and has no overlay",
                evaluate.contains("KeepADBForce"));
    }

    @Test
    public void theForceModeHoldsNoStateInMemoryOnlyItsClockAndListener() {
        Set<String> mutableStatics = new HashSet<>();
        for (Field field : KeepADBForceMode.class.getDeclaredFields()) {
            if (field.isSynthetic() || !Modifier.isStatic(field.getModifiers())) continue;
            if (!Modifier.isFinal(field.getModifiers())) mutableStatics.add(field.getName());
        }
        assertEquals("A cached copy of the state would not survive process death, and would"
                + " disagree with the preferences it is read from",
                new HashSet<>(Arrays.asList("clock", "stateListener")), mutableStatics);
    }

    @Test
    public void theRequestCodesOfTheForceModeAreDistinctAndInTheirOwnBlock() {
        int[] codes = {KeepADBForceMode.REQUEST_CODE_END, KeepADBForceMode.REQUEST_CODE_EXPIRY_ALARM,
                KeepADBForceMode.REQUEST_CODE_EXPIRED_CONTENT, KeepADBForceMode.REQUEST_CODE_EXPIRED_TURN_OFF};
        Set<Integer> distinct = new HashSet<>();
        for (int code : codes) {
            assertTrue("In the reserved block 20..23: " + code, code >= 20 && code <= 23);
            assertTrue("Distinct: " + code, distinct.add(code));
        }
    }

    @Test
    public void theManifestAddsNoExportedComponentAndNoPermission() throws IOException {
        String manifest = read("app/src/main/AndroidManifest.xml");
        String boot = section(manifest, "<receiver\n            android:name=\".BootReceiver\"");
        assertTrue(boot.contains("android:exported=\"false\""));
        assertTrue("A clock set moves the effective deadline", boot.contains("android.intent.action.TIME_SET"));
        String receiver = section(manifest, "<receiver\n            android:name=\".KeepADBReceiver\"");
        assertTrue(receiver.contains("android:exported=\"false\""));
        assertTrue(receiver.contains("de.hohnepeople.keepadb.ACTION_FORCE_END"));
        assertTrue(receiver.contains("de.hohnepeople.keepadb.ACTION_FORCE_EXPIRE"));
        assertFalse("The expiry alarm is inexact on purpose; no exact alarm permission",
                manifest.contains("SCHEDULE_EXACT_ALARM") || manifest.contains("USE_EXACT_ALARM"));
        assertEquals("The force mode adds no exported component: still exactly the launcher activity"
                + " and the quick settings tile", 2, countOccurrences(manifest, "android:exported=\"true\""));
    }

    @Test
    public void everyRestartPathRestoresTheForceMode() throws IOException {
        String boot = read(MAIN + "BootReceiver.java");
        assertEquals("clock set, and boot / app update", 2, countOccurrences(boot, "KeepADBForceMode.restore(context);"));
        assertTrue(boot.contains("Intent.ACTION_TIME_CHANGED.equals(action)"));
        String service = read(MAIN + "KeepADBService.java");
        assertTrue("The service heartbeat finishes an expired mode",
                methodBody(service, "private void heartbeatNow() {").contains("KeepADBForceMode.finishIfExpired(this);"));
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        for (int index = text.indexOf(needle); index >= 0; index = text.indexOf(needle, index + 1)) count++;
        return count;
    }

    private static String section(String manifest, String start) {
        int from = manifest.indexOf(start);
        assertTrue("Missing manifest section: " + start, from >= 0);
        return manifest.substring(from, manifest.indexOf("</receiver>", from));
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
