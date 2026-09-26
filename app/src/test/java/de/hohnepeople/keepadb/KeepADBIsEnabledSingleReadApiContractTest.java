package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.Test;

/**
 * Contract for #582's architectural decision: {@link KeepADB#isEnabledOrNull(android.content.Context,
 * String)} is the only sanctioned way to read {@code adb_wifi_enabled} from production code.
 * {@link KeepADB#isEnabled(android.content.Context)} stays package-private (not {@code private})
 * purely so a handful of existing tests can assert against the raw, working gateway state
 * directly -- see that method's own javadoc -- but no *production* call site may use it, or the
 * equally unguarded {@link KeepADBSettingsGateway#isEnabled}, directly: either one throws
 * {@link SecurityException} on an OEM that restricts the read (#580's discovery), and every call
 * site reached from Android framework callbacks must not propagate that exception.
 *
 * <p>Scope and limits of this contract (deliberately narrow):
 * <ul>
 *     <li>It scans main sources only ({@code app/src/main/java}), never {@code app/src/test}: a
 *     test fake legitimately implements {@link KeepADBSettingsGateway#isEnabled} (that's a method
 *     declaration, not a call, and comments are stripped first anyway), and several existing
 *     tests deliberately call the raw {@link KeepADB#isEnabled} as an assertion helper against a
 *     working fake gateway -- rewriting those for a visibility change is out of scope for #582.</li>
 *     <li>It is a textual grep, not a compiler or bytecode check: a call reached only through
 *     reflection, or through a local variable typed as {@code KeepADBSettingsGateway} obtained by
 *     some indirection other than the literal token {@code gateway}, would not be caught. Given
 *     the {@code gateway} field is private to {@link KeepADB} and no production code currently
 *     holds any other reference to a gateway instance, this is not a realistic gap today.</li>
 *     <li>It does not (and cannot) verify that every caller's <em>fallback</em> for a {@code null}
 *     result is semantically correct for its own display/automatic/readback context -- only that
 *     the unguarded read itself is never reachable. Fallback correctness is covered by the
 *     behavioral tests exercising a permanently throwing gateway (see
 *     KeepADBReadSecurityExceptionFallbackTest and the #582 behavioral tests alongside it).</li>
 * </ul>
 */
public class KeepADBIsEnabledSingleReadApiContractTest {

    private static final Pattern UNSAFE_KEEPADB_READ = Pattern.compile("\\bKeepADB\\s*\\.\\s*isEnabled\\s*\\(");
    private static final Pattern UNSAFE_GATEWAY_READ = Pattern.compile("\\bgateway\\s*\\.\\s*isEnabled\\s*\\(");

    @Test
    public void noProductionCallSiteReadsAdbWifiEnabledOutsideTheSanctionedApi() throws IOException {
        Path mainRoot = projectPath("app/src/main/java/de/hohnepeople/keepadb");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(mainRoot)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java")).toArray(Path[]::new)) {
                if (path.getFileName().toString().equals("KeepADB.java")) {
                    // The one file allowed to hold the raw read itself (isEnabled()'s own body)
                    // and the two write-guarded recovery-pulse readbacks its javadoc documents.
                    continue;
                }
                String source = stripComments(
                        new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
                if (containsMatch(UNSAFE_KEEPADB_READ, source)) {
                    offenders.add(path.getFileName() + ": calls KeepADB.isEnabled( directly --"
                            + " use KeepADB.isEnabledOrNull(ctx, source) instead (#582)");
                }
                if (containsMatch(UNSAFE_GATEWAY_READ, source)) {
                    offenders.add(path.getFileName() + ": calls gateway.isEnabled( directly --"
                            + " use KeepADB.isEnabledOrNull(ctx, source) instead (#582)");
                }
            }
        }
        assertTrue("Found unguarded adb_wifi_enabled read(s) outside KeepADB's sanctioned"
                        + " isEnabledOrNull() API (#582): " + offenders,
                offenders.isEmpty());
    }

    /**
     * KeepADB.java is skipped above, but it holds the most sensitive reads (getState, setEnabled,
     * the readbacks), so pin its raw reads by count instead: the isEnabled() declaration, its
     * gateway.isEnabled() body, the one call inside isEnabledOrNull(), and the two
     * write-guarded recovery-pulse readbacks. Any new raw read in KeepADB.java turns this red.
     */
    @Test
    public void keepADBItselfHoldsOnlyTheDocumentedRawReads() throws IOException {
        Path keepAdb = projectPath("app/src/main/java/de/hohnepeople/keepadb/KeepADB.java");
        String source = stripComments(
                new String(Files.readAllBytes(keepAdb), StandardCharsets.UTF_8));
        Matcher matcher = Pattern.compile("\\bisEnabled\\s*\\(").matcher(source);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        assertEquals("KeepADB.java must not gain a raw isEnabled( read beyond the documented"
                + " five occurrences -- use isEnabledOrNull(ctx, source) (#582)", 5, count);
    }

    private static boolean containsMatch(Pattern pattern, String source) {
        Matcher matcher = pattern.matcher(source);
        return matcher.find();
    }

    /** Removes block and line comments. Mirrors KeepADBToggleSourceContractTest's helper. */
    private static String stripComments(String source) {
        return source
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)//.*$", " ");
    }

    private static Path projectPath(String relativePath) {
        Path directory = Paths.get("").toAbsolutePath();
        while (directory != null && !Files.exists(directory.resolve("settings.gradle"))) {
            directory = directory.getParent();
        }
        if (directory == null) {
            throw new IllegalStateException("Could not locate project root");
        }
        return directory.resolve(relativePath);
    }
}
