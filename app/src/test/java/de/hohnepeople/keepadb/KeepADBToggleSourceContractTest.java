package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.Test;

/**
 * Contract for issue #310's manual/automatic source split. {@link KeepADB#isManualSource} is the
 * single place that decides whether a toggle is applied at once (an explicit user override) or
 * debounced and revalidated at write time (an automatic order). Its input is a bare string
 * chosen at each call site, so the two halves can drift apart silently -- a new surface passing
 * an unlisted source would be classified automatic and would quietly gain a delay its users can
 * feel. These tests pin both the classification itself and the fact that every source literal
 * actually used in production is one somebody classified on purpose.
 */
public class KeepADBToggleSourceContractTest {

    /** Every source string {@code KeepADB.setEnabled} is called with anywhere in main sources. */
    private static final Set<String> EXPECTED_SOURCES = new LinkedHashSet<>();
    static {
        EXPECTED_SOURCES.add("app");
        EXPECTED_SOURCES.add("tile");
        EXPECTED_SOURCES.add("widget");
        EXPECTED_SOURCES.add("notification");
        EXPECTED_SOURCES.add("usb_handover_manual");
        EXPECTED_SOURCES.add("keep_alive_check");
        EXPECTED_SOURCES.add("content_observer");
        EXPECTED_SOURCES.add("usb_handover");
    }

    @Test
    public void userFacingSurfacesAreManualAndBackgroundOrdersAreNot() {
        assertTrue(KeepADB.isManualSource("app"));
        assertTrue(KeepADB.isManualSource("tile"));
        assertTrue(KeepADB.isManualSource("widget"));
        assertTrue(KeepADB.isManualSource("notification"));
        assertTrue(KeepADB.isManualSource(KeepADB.SOURCE_USB_HANDOVER_MANUAL));

        assertFalse(KeepADB.isManualSource("keep_alive_check"));
        assertFalse(KeepADB.isManualSource("content_observer"));
        // The automatic USB broadcast path must stay distinguishable from the manual tap that
        // used to share this exact string with it.
        assertFalse(KeepADB.isManualSource("usb_handover"));
    }

    @Test
    public void anUnknownSourceFallsBackToTheConservativeAutomaticClassification() {
        assertFalse(KeepADB.isManualSource("some_future_surface"));
        assertFalse(KeepADB.isManualSource(null));
        assertFalse(KeepADB.isManualSource(""));
    }

    @Test
    public void everySourceUsedInProductionIsDeliberatelyClassified() throws IOException {
        Set<String> found = new LinkedHashSet<>();
        int classified = 0;
        int callSites = 0;
        Path mainRoot = projectPath("app/src/main/java/de/hohnepeople/keepadb");
        Pattern call = Pattern.compile(
                "KeepADB\\s*\\.\\s*setEnabled\\s*\\([^;]*?,\\s*(?:\"([a-z_]+)\"|KeepADB\\.(SOURCE_[A-Z_]+))",
                Pattern.DOTALL);
        Pattern anyCall = Pattern.compile("KeepADB\\s*\\.\\s*setEnabled\\s*\\(");
        try (Stream<Path> paths = Files.walk(mainRoot)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java")).toArray(Path[]::new)) {
                // Comments are stripped first: several of them quote a KeepADB.setEnabled(...)
                // call in prose, and matching those would report sources that no code passes.
                String source = stripComments(
                        new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
                Matcher matcher = call.matcher(source);
                while (matcher.find()) {
                    classified++;
                    found.add(matcher.group(1) != null
                            ? matcher.group(1)
                            : constantValue(matcher.group(2)));
                }
                Matcher any = anyCall.matcher(source);
                while (any.find()) {
                    callSites++;
                }
            }
        }
        assertFalse("Found no KeepADB.setEnabled call sites -- the scan pattern is broken",
                found.isEmpty());
        assertEquals("A KeepADB.setEnabled source appeared or vanished. Classify it in"
                        + " KeepADB.MANUAL_SOURCES (or confirm it belongs to the automatic,"
                        + " debounced-and-revalidated path) and update this list.",
                EXPECTED_SOURCES, found);
        // The source-name scan above only sees call sites that pass a literal or a KeepADB.SOURCE_
        // constant. A call site that passes neither -- most importantly the two-argument
        // setEnabled(ctx, on) overload, which silently defaults to the *manual* source "app" and
        // would therefore gain an immediate, unguarded write -- would be invisible to it and the
        // set comparison would still pass. Counting every call site and requiring each one to have
        // been classified closes that hole.
        assertEquals("A KeepADB.setEnabled call site in main sources does not pass a literal"
                        + " source or a KeepADB.SOURCE_ constant, so #310 cannot classify it."
                        + " Note that the two-argument overload defaults to \"app\", i.e. manual.",
                callSites, classified);
    }

    /** Removes block and line comments. Good enough here: no KeepADB source file has a string
     * literal containing a comment marker, so no real call site can be lost this way. */
    private static String stripComments(String source) {
        return source
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)//.*$", " ");
    }

    private static String constantValue(String constantName) {
        if ("SOURCE_USB_HANDOVER_MANUAL".equals(constantName)) {
            return KeepADB.SOURCE_USB_HANDOVER_MANUAL;
        }
        throw new IllegalStateException("Unmapped KeepADB source constant: " + constantName);
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
