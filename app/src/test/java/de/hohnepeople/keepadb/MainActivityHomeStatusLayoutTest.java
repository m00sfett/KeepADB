package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.widget.Button;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * #957: the block below the main switch (state, reason, action, endpoint) is one shared include in
 * all six variants of {@code activity_main.xml}, so they cannot drift, and none of it is a text
 * dressed up as a button.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MainActivityHomeStatusLayoutTest {
    private final android.content.Context context = org.robolectric.RuntimeEnvironment.getApplication();

    @org.junit.Before
    public void setUp() {
        context.getSharedPreferences("keepadb_prefs", android.content.Context.MODE_PRIVATE)
                .edit().clear().commit();
        KeepADBOnboarding.markCompleted(context);
        KeepADB.resetForTesting(context);
    }

    @org.junit.After
    public void tearDown() {
        context.getSharedPreferences("keepadb_prefs", android.content.Context.MODE_PRIVATE)
                .edit().clear().commit();
        KeepADB.resetForTesting(context);
    }

    @Test
    public void everyMainLayoutVariantIncludesTheSharedBlockAndNoCopyOfIt() throws IOException {
        int variants = 0;
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(
                root().resolve("app/src/main/res"), "layout*")) {
            for (Path dir : dirs) {
                Path main = dir.resolve("activity_main.xml");
                if (!Files.exists(main)) continue;
                variants++;
                String xml = read(main);
                assertTrue(main + " includes view_home_status", xml.contains("layout=\"@layout/view_home_status\""));
                for (String id : new String[] {"status", "status_reason", "status_action", "endpoint",
                        "endpoint_row"}) {
                    assertFalse(main + " must not define @+id/" + id + " itself",
                            xml.contains("android:id=\"@+id/" + id + "\""));
                }
            }
        }
        assertEquals("the six variants of activity_main.xml", 6, variants);
    }

    @Test
    public void theSharedBlockHasTheLinesAndAnActionButtonInTheWarningRowsStyle() throws IOException {
        String xml = read(root().resolve("app/src/main/res/layout/view_home_status.xml"));
        for (String id : new String[] {"status_summary", "status", "status_reason_row", "status_reason",
                "status_reason_info", "status_action", "endpoint_row", "endpoint"}) {
            assertTrue(id, xml.contains("android:id=\"@+id/" + id + "\""));
        }
        assertTrue("a real Button", xml.contains("<Button\n        android:id=\"@+id/status_action\""));
        assertTrue("the primary button look of the warning rows",
                xml.contains("style=\"@style/Widget.KeepADB.Button.Primary\""));
        assertFalse("no card background on a text", xml.contains("bg_card_clickable"));
    }

    @Test
    public void theHomeStatusIsNoClickableTextWithACardBackgroundAnyMore() throws IOException {
        String source = read(root().resolve(
                "app/src/main/java/de/hohnepeople/keepadb/MainActivity.java"));
        assertFalse(source.contains("bg_card_clickable"));
        assertFalse(source.contains("makeStatusDecisionEntry"));
    }

    @Test
    public void theBlockIsThereOnAPhoneAndOnTablets() {
        for (String qualifiers : new String[] {"", "sw600dp", "w840dp-h600dp", "w600dp-h400dp"}) {
            org.robolectric.RuntimeEnvironment.setQualifiers(qualifiers.isEmpty() ? "+port" : "+" + qualifiers);
            MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
            View status = activity.findViewById(R.id.status);
            assertNotNull(qualifiers, status);
            assertNotNull(qualifiers, activity.findViewById(R.id.status_reason));
            assertTrue(qualifiers, activity.findViewById(R.id.status_action) instanceof Button);
            assertNotNull(qualifiers, activity.findViewById(R.id.endpoint_row));
            assertEquals(qualifiers, activity.findViewById(R.id.endpoint_row),
                    activity.findViewById(R.id.endpoint).getParent());
        }
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static Path root() {
        Path directory = Paths.get("").toAbsolutePath();
        while (directory != null && !Files.exists(directory.resolve("settings.gradle"))) {
            directory = directory.getParent();
        }
        if (directory == null) throw new IllegalStateException("project root not found");
        return directory;
    }
}
