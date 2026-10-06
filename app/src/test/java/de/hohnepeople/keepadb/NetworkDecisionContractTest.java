package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.Test;

/**
 * #766: static contracts of the decision component that no behavior test can see: who may write
 * the blocklist, which flags the new activity must not carry, and that the component stays
 * embeddable. A source scan is crude, but each rule here is the kind that is broken by adding one
 * innocent line elsewhere.
 */
public class NetworkDecisionContractTest {

    private static final String MAIN = "app/src/main/java/de/hohnepeople/keepadb/";

    /**
     * The user's "never" is written in one place. A second writer (a list, a tile, a service) would
     * be a second way to block that skips the placeholder rules and the follow-up (history, prompt)
     * and could bring back the fail-open or fail-silent cases #760/#766 closed.
     */
    @Test
    public void onlyTheDecisionLogicWritesTheBlocklist() throws IOException {
        List<String> writers = new ArrayList<>();
        try (Stream<Path> files = Files.list(root().resolve(MAIN))) {
            for (Path file : (Iterable<Path>) files::iterator) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".java") || name.equals("KeepADBNetworkBlocklist.java")) continue;
                String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                if (source.contains("KeepADBNetworkBlocklist.blockBssid(")
                        || source.contains("KeepADBNetworkBlocklist.blockSsid(")) {
                    writers.add(name);
                }
            }
        }
        assertEquals(List.of("KeepADBNetworkDecision.java"), writers);
    }

    /** Neither the prompt's notification nor any activity may react to a swipe or open over the keyguard. */
    @Test
    public void nothingInTheAppShowsOverTheLockScreenOrRunsOnASwipe() throws IOException {
        try (Stream<Path> files = Files.list(root().resolve(MAIN))) {
            for (Path file : (Iterable<Path>) files::iterator) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".java")) continue;
                String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                assertFalse(name + " must not turn the screen on or show over the keyguard",
                        source.contains("setShowWhenLocked(") || source.contains("setTurnScreenOn(")
                                || source.contains("FLAG_SHOW_WHEN_LOCKED")
                                || source.contains("FLAG_TURN_SCREEN_ON")
                                || source.contains("FLAG_DISMISS_KEYGUARD"));
            }
        }
        String prompt = read(MAIN + "KeepADBNetworkTrustPrompt.java");
        assertFalse("A swipe decides nothing: the prompt has no delete intent",
                prompt.contains("setDeleteIntent("));
    }

    /**
     * The activity decides nothing by itself: it asks the keyguard before it binds a name, and its
     * only writes go through the view's answers. Pinned as source because Robolectric's keyguard is a
     * shadow, so the production call itself is what must stay.
     */
    @Test
    public void theActivityChecksTheKeyguardBeforeBindingAndClearsOnStop() throws IOException {
        String activity = read(MAIN + "NetworkDecisionActivity.java");

        assertTrue(activity.contains("isKeyguardLocked()"));
        assertTrue(activity.indexOf("isKeyguardLocked()") < activity.indexOf("decisionView.bind("));
        assertTrue("The name must be removed when the activity stops", activity.contains("onStop()")
                && activity.substring(activity.indexOf("onStop()")).contains("decisionView.clear()"));
        assertTrue(activity.contains("setFinishOnTouchOutside(true)"));
        assertFalse("The activity must not write trust or blocks itself",
                activity.contains("KeepADBTrustedNetwork.add") || activity.contains("KeepADBNetworkBlocklist"));
    }

    /** Embeddable: the view belongs to no activity and carries its own layout. */
    @Test
    public void theDecisionViewIsReusableAndKnowsNoHostActivity() throws IOException {
        String view = code(read(MAIN + "NetworkDecisionView.java"));

        assertFalse(view.contains("android.app.Activity"));
        assertFalse(view.contains("NetworkDecisionActivity"));
        assertTrue(view.contains("public NetworkDecisionView(Context context, AttributeSet attrs)"));
        assertTrue(view.contains("R.layout.view_network_decision"));
        String hostLayout = read("app/src/main/res/layout/activity_network_decision.xml");
        assertTrue("The dialog host embeds the very same view",
                hostLayout.contains("de.hohnepeople.keepadb.NetworkDecisionView"));
    }

    /** The dialog theme closes on a tap outside; the activity says so in code as well. */
    @Test
    public void theDialogThemeClosesOnATapOutsideAndInheritsADialogTheme() throws IOException {
        String themes = read("app/src/main/res/values/themes.xml");
        int start = themes.indexOf("name=\"Theme.KeepADB.Dialog\"");
        assertTrue(start >= 0);
        String style = themes.substring(start, themes.indexOf("</style>", start));
        assertTrue(style, style.contains("parent=\"android:style/Theme.Material.Dialog\""));
        assertTrue(style, style.contains("android:windowCloseOnTouchOutside\">true<"));
        String manifest = read("app/src/main/AndroidManifest.xml");
        assertTrue(manifest.contains("android:theme=\"@style/Theme.KeepADB.Dialog\""));
    }

    /** The source without comment lines, so a documentation reference is not mistaken for a dependency. */
    private static String code(String source) {
        StringBuilder out = new StringBuilder();
        for (String line : source.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("//")) continue;
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static String read(String relativePath) throws IOException {
        return new String(Files.readAllBytes(root().resolve(relativePath)), StandardCharsets.UTF_8);
    }

    private static Path root() {
        Path directory = Paths.get("").toAbsolutePath();
        while (directory != null && !Files.exists(directory.resolve("settings.gradle"))) {
            directory = directory.getParent();
        }
        if (directory == null) throw new IllegalStateException("Could not locate project root");
        return directory;
    }
}
