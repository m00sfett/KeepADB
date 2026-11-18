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
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * #954: static contract of the launcher shortcuts. Order and targets are fixed by the maintainer
 * decision of 2026-11-15; the debug build has its own application id, and a shortcut whose
 * {@code android:targetPackage} is not the real application id of the installed build is silently
 * dropped by the launcher, so both variants of the file are checked against {@code
 * app/build.gradle}.
 */
public class KeepADBShortcutsContractTest {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final String NAMESPACE = "de.hohnepeople.keepadb";

    private static final List<String> IDS =
            Arrays.asList("toggle_wifi_adb", "timer_wifi_adb", "wifi_networks", "settings");
    private static final List<String> TARGETS = Arrays.asList(
            NAMESPACE + ".KeepADBToggleShortcutActivity",
            NAMESPACE + ".KeepADBTimerShortcutActivity",
            NAMESPACE + ".NetworkListActivity",
            NAMESPACE + ".SettingsActivity");
    private static final List<String> SHORT_LABELS = Arrays.asList(
            "@string/shortcut_toggle_short", "@string/shortcut_timer_short", "@string/networks_title",
            "@string/action_settings");
    private static final List<String> LONG_LABELS = Arrays.asList(
            "@string/shortcut_toggle_long", "@string/shortcut_timer_long", "@string/networks_title",
            "@string/action_settings");
    private static final List<String> ICONS = Arrays.asList(
            "@drawable/ic_keepadb", "@drawable/ic_timer", "@drawable/ic_wifi", "@drawable/ic_settings_gear");

    @Test
    public void mainFileListsTheFourShortcutsInOrderForTheReleasePackage() throws Exception {
        assertShortcuts("app/src/main/res/xml/shortcuts.xml", applicationId());
    }

    @Test
    public void debugOverlayTargetsTheSuffixedPackage() throws Exception {
        String suffix = gradleValue("applicationIdSuffix");
        assertEquals(".debug", suffix);
        assertShortcuts("app/src/debug/res/xml/shortcuts.xml", applicationId() + suffix);
    }

    @Test
    public void mainActivityPublishesTheShortcutsAndNothingIsExported() throws Exception {
        String manifest = read("app/src/main/AndroidManifest.xml");
        Matcher main = Pattern.compile(
                "<activity\\b[^>]*android:name=\"\\.MainActivity\"[^>]*>(.*?)</activity>",
                Pattern.DOTALL).matcher(manifest);
        assertTrue(main.find());
        assertTrue("MainActivity must carry the android.app.shortcuts meta-data",
                Pattern.compile("<meta-data\\s+android:name=\"android.app.shortcuts\"\\s+"
                        + "android:resource=\"@xml/shortcuts\"\\s*/>").matcher(main.group(1)).find());

        // The launcher starts a shortcut with the identity of this app: no target is exported.
        for (String activity : Arrays.asList(".KeepADBToggleShortcutActivity",
                ".KeepADBTimerShortcutActivity",
                ".NetworkListActivity", ".SettingsActivity")) {
            Matcher m = Pattern.compile("<activity\\b[^>]*android:name=\"" + Pattern.quote(activity)
                    + "\"[^>]*?/?>", Pattern.DOTALL).matcher(manifest);
            assertTrue(activity + " is not declared", m.find());
            String tag = m.group();
            assertTrue(activity + " must stay unexported (#954)",
                    tag.contains("android:exported=\"false\""));
            assertFalse(activity + " must not answer any intent filter by itself",
                    declaresIntentFilter(activity));
        }
    }

    @Test
    public void everyLocaleLabelsTheToggleShortcut() throws IOException {
        List<String> locales = Arrays.asList("", "-ar", "-de", "-es", "-fr", "-hi", "-in", "-it",
                "-ja", "-ko", "-nl", "-pl", "-pt", "-ru", "-tr", "-uk", "-vi", "-zh-rCN",
                "-zh-rTW");
        for (String locale : locales) {
            String xml = read("app/src/main/res/values" + locale + "/strings.xml");
            // #1000: the timer shortcut's long label names the product term as well.
            for (String key : Arrays.asList("shortcut_toggle_short", "shortcut_toggle_long",
                    "shortcut_timer_long")) {
                Matcher m = Pattern.compile("<string name=\"" + key + "\">([^<]+)</string>")
                        .matcher(xml);
                assertTrue("values" + locale + " lacks " + key, m.find());
                assertTrue("values" + locale + "/" + key + " keeps the product term",
                        m.group(1).contains("Wifi-ADB"));
            }
        }
    }

    /** Parsed, so a comment or other whitespace before a filter cannot hide it. */
    private static boolean declaresIntentFilter(String activityName) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document document = factory.newDocumentBuilder()
                .parse(Paths.get(root().toString(), "app/src/main/AndroidManifest.xml").toFile());
        NodeList activities = document.getElementsByTagName("activity");
        for (int i = 0; i < activities.getLength(); i++) {
            Element activity = (Element) activities.item(i);
            if (activityName.equals(activity.getAttributeNS(ANDROID, "name"))) {
                return activity.getElementsByTagName("intent-filter").getLength() > 0;
            }
        }
        throw new AssertionError(activityName + " is not declared");
    }

    private static void assertShortcuts(String file, String expectedPackage) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document document = factory.newDocumentBuilder()
                .parse(Paths.get(root().toString(), file).toFile());
        document.getDocumentElement().normalize();
        NodeList shortcuts = document.getElementsByTagName("shortcut");
        assertEquals(file + ": four shortcuts (#1000 added the timer)", 4, shortcuts.getLength());
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < shortcuts.getLength(); i++) {
            Element shortcut = (Element) shortcuts.item(i);
            ids.add(shortcut.getAttributeNS(ANDROID, "shortcutId"));
            assertEquals(file + " #" + i + " short label", SHORT_LABELS.get(i),
                    shortcut.getAttributeNS(ANDROID, "shortcutShortLabel"));
            assertEquals(file + " #" + i + " long label", LONG_LABELS.get(i),
                    shortcut.getAttributeNS(ANDROID, "shortcutLongLabel"));
            assertEquals(file + " #" + i + " icon", ICONS.get(i),
                    shortcut.getAttributeNS(ANDROID, "icon"));
            assertTrue(file + " #" + i + " enabled",
                    "true".equals(shortcut.getAttributeNS(ANDROID, "enabled")));
            NodeList intents = shortcut.getElementsByTagName("intent");
            assertEquals(1, intents.getLength());
            Element intent = (Element) intents.item(0);
            assertEquals(file + " #" + i + " action", "android.intent.action.VIEW",
                    intent.getAttributeNS(ANDROID, "action"));
            assertEquals(file + " #" + i + ": targetPackage must be the application id of the"
                            + " build the file ships in", expectedPackage,
                    intent.getAttributeNS(ANDROID, "targetPackage"));
            assertEquals(file + " #" + i + " targetClass", TARGETS.get(i),
                    intent.getAttributeNS(ANDROID, "targetClass"));
            String icon = ICONS.get(i).substring("@drawable/".length());
            assertTrue("icon drawable " + icon + " exists",
                    Files.exists(root().resolve("app/src/main/res/drawable/" + icon + ".xml")));
        }
        assertEquals(IDS, ids);
    }

    private static String applicationId() throws IOException {
        return gradleValue("applicationId");
    }

    private static String gradleValue(String name) throws IOException {
        Matcher m = Pattern.compile("\\b" + name + "\\s+'([^']+)'").matcher(
                read("app/build.gradle"));
        assertTrue(name + " not found in app/build.gradle", m.find());
        return m.group(1);
    }

    private static String read(String relativePath) throws IOException {
        return new String(Files.readAllBytes(root().resolve(relativePath)), StandardCharsets.UTF_8);
    }

    private static Path root() {
        Path directory = Paths.get("").toAbsolutePath();
        while (directory != null && !Files.exists(directory.resolve("settings.gradle"))) {
            directory = directory.getParent();
        }
        if (directory == null) {
            throw new IllegalStateException("Could not locate project root");
        }
        return directory;
    }
}
