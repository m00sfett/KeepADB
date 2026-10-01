package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.Test;

/**
 * #690: the "(DBG)" marker may only come from src/debug. Anything in src/main (or a release
 * source set) would leak it into the release app. Static source contract; the built APKs are
 * additionally checked by bin/check-variant-labels.
 */
public class KeepADBVariantLabelContractTest {

    @Test
    public void mainAndReleaseSourceSetsNeverContainTheDebugMarker() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String set : new String[] {"app/src/main", "app/src/release"}) {
            Path dir = projectPath(set);
            if (!Files.exists(dir)) continue;
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)
                        .filter(f -> f.toString().endsWith(".xml"))::iterator) {
                    String text = new String(Files.readAllBytes(file));
                    if (text.contains("(DBG)")) offenders.add(file.toString());
                }
            }
        }
        assertTrue("The (DBG) marker must live in src/debug only: " + offenders,
                offenders.isEmpty());
    }

    @Test
    public void debugOverlayDefinesTheMarkerForEveryBrandString() throws IOException {
        String strings = new String(Files.readAllBytes(
                projectPath("app/src/debug/res/values/strings.xml")));
        for (String key : new String[] {"app_name", "title_keepadb"}) {
            assertTrue("debug overlay must define " + key,
                    strings.contains("name=\"" + key + "\" translatable=\"false\">(DBG) KeepADB<"));
        }
        String manifest = new String(Files.readAllBytes(
                projectPath("app/src/debug/AndroidManifest.xml")));
        assertTrue(manifest.contains("android:label=\"(DBG) KeepADB\""));
        String main = new String(Files.readAllBytes(
                projectPath("app/src/main/res/values/strings.xml")));
        assertTrue(main.contains("name=\"app_name\" translatable=\"false\">KeepADB<"));
        assertFalse(main.contains("DBG"));
        assertEquals("only the debug overlay and its manifest may carry the marker", 0,
                countFilesWithMarker("app/src/main"));
    }

    private static int countFilesWithMarker(String set) throws IOException {
        int count = 0;
        try (Stream<Path> files = Files.walk(projectPath(set))) {
            for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".xml"))::iterator) {
                if (new String(Files.readAllBytes(file)).contains("DBG")) count++;
            }
        }
        return count;
    }

    private static Path projectPath(String relativePath) {
        Path directory = Paths.get("").toAbsolutePath();
        while (directory != null && !Files.exists(directory.resolve("settings.gradle"))) {
            directory = directory.getParent();
        }
        if (directory == null) throw new IllegalStateException("Could not locate project root");
        return directory.resolve(relativePath);
    }
}
