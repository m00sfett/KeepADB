package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * #736: the network, webhook, USB-ADB and diagnostics settings cards each carry a decorative
 * header icon. Static contract over {@code activity_settings.xml}: the icon exists, points at an
 * existing drawable, is hidden from accessibility and has no spoken label, while the adjacent
 * title keeps its string reference.
 */
public class KeepADBSettingsHeaderIconContractTest {

    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    private static final String[][] HEADERS = {
            // header id, expected title string, expected icon drawable
            {"settings_network_beta_header", "settings_section_network", "ic_settings_network"},
            {"settings_webhook_header", "settings_webhook_title", "ic_settings_sync"},
            {"settings_usb_adb_header", "settings_section_usb_adb", "ic_usb"},
            {"settings_diagnostics_header", "settings_diagnostics_title", "ic_settings_diagnostics"},
    };

    @Test
    public void everySectionHeaderCarriesItsOwnIcon() throws Exception {
        Document layout = layout();
        for (String[] header : HEADERS) {
            Element icon = iconOf(headerOf(layout, header[0]));
            assertNotNull("Header " + header[0] + " needs an ImageView icon", icon);
            assertEquals("@drawable/" + header[2], icon.getAttributeNS(ANDROID_NS, "src"));
            assertTrue("Drawable " + header[2] + " must exist", drawable(header[2]).exists());
        }
    }

    @Test
    public void headerIconsAreDecorativeAndTitlesStayIntact() throws Exception {
        Document layout = layout();
        for (String[] header : HEADERS) {
            Element headerView = headerOf(layout, header[0]);
            Element icon = iconOf(headerView);
            assertNotNull("Header " + header[0] + " needs an ImageView icon", icon);
            assertEquals(header[0] + " icon must be hidden from accessibility", "no",
                    icon.getAttributeNS(ANDROID_NS, "importantForAccessibility"));
            assertFalse(header[0] + " icon must not carry a spoken label",
                    icon.hasAttributeNS(ANDROID_NS, "contentDescription"));
            assertTrue(header[0] + " title must keep its string reference",
                    containsTitle(headerView, "@string/" + header[1]));
        }
    }

    @Test
    public void mainScreenKeepsTheWifiGlyph() throws Exception {
        assertTrue(drawable("ic_wifi").exists());
        assertFalse("Settings network icon must not be the shared Wi-Fi glyph",
                HEADERS[0][2].equals("ic_wifi"));
    }

    @Test
    public void usbTitleFollowsLayoutDirectionLikeOtherTitles() throws Exception {
        // #742: "USB-ADB" is a Latin proper name; without explicit alignment it is left-aligned
        // in RTL. Titles with a localized string must keep their (absent) alignment untouched.
        Document layout = layout();
        Element usbTitle = titleOf(headerOf(layout, "settings_usb_adb_header"), "@string/settings_section_usb_adb");
        assertEquals("viewStart", usbTitle.getAttributeNS(ANDROID_NS, "textAlignment"));
        for (String[] header : HEADERS) {
            if ("settings_usb_adb_header".equals(header[0])) {
                continue;
            }
            Element other = titleOf(headerOf(layout, header[0]), "@string/" + header[1]);
            assertFalse(header[0] + " title alignment must stay unchanged",
                    other.hasAttributeNS(ANDROID_NS, "textAlignment"));
        }
    }

    private static Element titleOf(Element header, String stringRef) {
        NodeList texts = header.getElementsByTagName("TextView");
        for (int i = 0; i < texts.getLength(); i++) {
            Element text = (Element) texts.item(i);
            if (stringRef.equals(text.getAttributeNS(ANDROID_NS, "text"))) {
                return text;
            }
        }
        throw new AssertionError("Title not found: " + stringRef);
    }

    private static Element headerOf(Document layout, String id) {
        NodeList all = layout.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element element = (Element) all.item(i);
            if (("@+id/" + id).equals(element.getAttributeNS(ANDROID_NS, "id"))) {
                return element;
            }
        }
        throw new AssertionError("Header not found: " + id);
    }

    private static Element iconOf(Element header) {
        for (Node child = header.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element && "ImageView".equals(((Element) child).getTagName())) {
                return (Element) child;
            }
        }
        return null;
    }

    private static boolean containsTitle(Element header, String stringRef) {
        NodeList texts = header.getElementsByTagName("TextView");
        for (int i = 0; i < texts.getLength(); i++) {
            if (stringRef.equals(((Element) texts.item(i)).getAttributeNS(ANDROID_NS, "text"))) {
                return true;
            }
        }
        return false;
    }

    private static Document layout() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder()
                .parse(root().resolve("app/src/main/res/layout/activity_settings.xml").toFile());
    }

    private static File drawable(String name) throws Exception {
        return root().resolve("app/src/main/res/drawable/" + name + ".xml").toFile();
    }

    private static Path root() throws Exception {
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
