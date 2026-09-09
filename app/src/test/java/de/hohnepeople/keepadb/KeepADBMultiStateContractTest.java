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
        assertTrue(tile.contains("case OFF_KEEP_ALIVE_WAITING:"));
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
        assertTrue(widget.contains("case OFF_KEEP_ALIVE_WAITING:"));
        assertTrue(widget.contains("case ENABLED_DISCONNECTED:"));
        assertTrue(widget.contains("case ENABLED_CONNECTED:"));
    }

    @Test
    public void tileDifferentiatesSearchingFromGenuineDisconnectedState() throws IOException {
        // Regression test for issue #267 (1): before the fix, ENABLED_DISCONNECTED was mapped
        // unconditionally to R.string.tile_state_disconnected ("Nicht verbunden"), even though
        // that state is reached while WLAN-ADB is still on (or Keep-Alive is waiting to turn it
        // back on) and a fresh endpoint is being searched for -- which reads as misleadingly
        // "off" to the user.
        String tile = read("app/src/main/java/de/hohnepeople/keepadb/KeepADBTileService.java");
        String updateTileBody = methodBody(tile, "private void updateTile() {");
        String disconnectedCase = updateTileBody.substring(
                updateTileBody.indexOf("case ENABLED_DISCONNECTED:"),
                updateTileBody.indexOf("case ENABLED_CONNECTED:"));
        String helperBody = methodBody(tile, "private boolean isSearchingForEndpoint() {");

        assertTrue("The ENABLED_DISCONNECTED tile branch must consult the searching helper",
                disconnectedCase.contains("isSearchingForEndpoint()"));
        assertTrue("The searching branch must still fall back to tile_state_disconnected",
                disconnectedCase.contains("R.string.tile_state_disconnected"));
        assertTrue("The searching branch must offer tile_state_searching as the transitional text",
                disconnectedCase.contains("R.string.tile_state_searching"));
        assertTrue("WLAN-ADB off (Keep-Alive still waiting) must read as searching",
                helperBody.contains("!KeepADB.isEnabled(this)"));
        assertTrue("WLAN-ADB on with Wi-Fi connected (mDNS discovery in flight) must read as searching",
                helperBody.contains("KeepADBService.isWifiConnected(this)"));
    }

    private static String methodBody(String source, String signature) {
        int methodStart = source.indexOf(signature);
        assertTrue("Missing method: " + signature, methodStart >= 0);
        int openingBrace = source.indexOf('{', methodStart);
        assertTrue("Missing opening brace: " + signature, openingBrace > methodStart);
        int methodEnd = findMatchingBrace(source, openingBrace);
        assertTrue("Missing closing brace: " + signature, methodEnd > openingBrace);
        return source.substring(methodStart, methodEnd + 1);
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

    @Test
    public void stringResourcesContainMultiStateStrings() throws IOException {
        String defaultStrings = read("app/src/main/res/values/strings.xml");
        String germanStrings = read("app/src/main/res/values-de/strings.xml");

        assertTrue(defaultStrings.contains("name=\"status_enabled_disconnected\""));
        assertTrue(defaultStrings.contains("name=\"tile_state_disconnected\""));
        assertTrue(defaultStrings.contains("name=\"tile_state_searching\""));
        assertTrue(defaultStrings.contains("name=\"tile_state_connected\""));
        assertTrue(defaultStrings.contains("name=\"widget_text_disconnected\""));

        assertTrue(germanStrings.contains("name=\"status_enabled_disconnected\""));
        assertTrue(germanStrings.contains("name=\"tile_state_disconnected\""));
        assertTrue(germanStrings.contains("name=\"tile_state_searching\""));
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
