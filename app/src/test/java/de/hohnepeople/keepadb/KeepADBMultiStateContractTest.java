package de.hohnepeople.keepadb;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

public class KeepADBMultiStateContractTest {

    @Test
    public void tileHandlesAllOperationalStates() throws IOException {
        String tile = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBTileService.java");
        assertTrue(tile.contains("case PERMISSION_MISSING:"));
        assertTrue(tile.contains("case OFF:"));
        assertTrue(tile.contains("case ENABLED_DISCONNECTED:"));
        assertTrue(tile.contains("case ENABLED_CONNECTED:"));
        assertTrue(tile.contains("tile.setSubtitle("));
        assertTrue(tile.contains("R.drawable.ic_keepadb_disconnected"));
        assertTrue(tile.contains("requestRefresh(Context context)"));
    }

    @Test
    public void widgetHandlesAllOperationalStates() throws IOException {
        String widget = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBWidget.java");
        assertTrue(widget.contains("case PERMISSION_MISSING:"));
        assertTrue(widget.contains("case OFF:"));
        assertTrue(widget.contains("case ENABLED_DISCONNECTED:"));
        assertTrue(widget.contains("case ENABLED_CONNECTED:"));
    }

    @Test
    public void stringResourcesContainMultiStateStrings() throws IOException {
        String defaultStrings = read("app/src/main/res/values/strings.xml");
        String germanStrings = read("app/src/main/res/values-de/strings.xml");

        assertTrue(defaultStrings.contains("name=\"status_enabled_disconnected\""));
        assertTrue(defaultStrings.contains("name=\"tile_state_disconnected\""));
        assertTrue(defaultStrings.contains("name=\"tile_state_connected\""));
        assertTrue(defaultStrings.contains("name=\"widget_text_disconnected\""));

        assertTrue(germanStrings.contains("name=\"status_enabled_disconnected\""));
        assertTrue(germanStrings.contains("name=\"tile_state_disconnected\""));
        assertTrue(germanStrings.contains("name=\"tile_state_connected\""));
        assertTrue(germanStrings.contains("name=\"widget_text_disconnected\""));
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
