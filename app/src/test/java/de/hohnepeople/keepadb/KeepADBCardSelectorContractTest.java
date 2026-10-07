package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * #818: static contract over {@code bg_card_clickable.xml}. A row that is selected (deep-link mark)
 * and focused (keyboard / TalkBack ring) must look different from a row that is only focused and
 * from a row that is only selected. Resolution follows the Android rule: the first item whose
 * listed states are all set wins. The visual check on the emulator is a separate acceptance step.
 */
public class KeepADBCardSelectorContractTest {

    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    @Test
    public void selectedAndFocusedDiffersFromFocusedOnlyAndSelectedOnly() throws Exception {
        String both = look(Set.of("selected", "focused"));
        String focused = look(Set.of("focused"));
        String selected = look(Set.of("selected"));
        assertNotEquals("selected+focused must not collapse into focused", focused, both);
        assertNotEquals("selected+focused must not collapse into selected", selected, both);
        assertNotEquals("focused and selected must stay apart", focused, selected);
    }

    @Test
    public void focusedAndSelectedStatesDifferInMoreThanStrokeWidth() throws Exception {
        String[] focused = look(Set.of("focused")).split("\\|");
        String[] selected = look(Set.of("selected")).split("\\|");
        // fill|color|width: at least fill or color must differ, not only the width
        assertNotEquals(focused[0] + focused[1], selected[0] + selected[1]);
    }

    @Test
    public void defaultItemStaysLast() throws Exception {
        NodeList items = selector().getElementsByTagName("item");
        Element last = (Element) items.item(items.getLength() - 1);
        assertEquals(0, last.getAttributes().getLength());
    }

    private static String look(Set<String> active) throws Exception {
        NodeList items = selector().getElementsByTagName("item");
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            if (matches(item, active)) {
                Element shape = (Element) item.getElementsByTagName("shape").item(0);
                Element solid = (Element) shape.getElementsByTagName("solid").item(0);
                Element stroke = (Element) shape.getElementsByTagName("stroke").item(0);
                assertNotNull(solid);
                assertNotNull(stroke);
                return solid.getAttributeNS(ANDROID_NS, "color") + "|"
                        + stroke.getAttributeNS(ANDROID_NS, "color") + "|"
                        + stroke.getAttributeNS(ANDROID_NS, "width");
            }
        }
        throw new IllegalStateException("No item matches " + active);
    }

    private static boolean matches(Element item, Set<String> active) {
        for (String state : new String[] {"pressed", "focused", "selected"}) {
            String attr = "state_" + state;
            if (item.hasAttributeNS(ANDROID_NS, attr)
                    && Boolean.parseBoolean(item.getAttributeNS(ANDROID_NS, attr)) != active.contains(state)) {
                return false;
            }
        }
        return true;
    }

    private static Element selector() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document doc = factory.newDocumentBuilder()
                .parse(root().resolve("app/src/main/res/drawable/bg_card_clickable.xml").toFile());
        return doc.getDocumentElement();
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
