package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * #593: One UI (s20, 1080 px) gives each of the three USB notification actions an equally wide
 * slot that fits about 8 Latin/Cyrillic/Arabic characters and truncates anything longer. This
 * pins every locale's three action labels to that per-label budget, counting CJK/Hangul as two
 * columns (so at most 4 such characters) and combining marks (e.g. Devanagari vowel signs) as
 * zero. It is a length rule, not a rendering measurement: glyph widths still vary per font.
 */
public class KeepADBUsbActionLabelBudgetTest {
    static final int SLOT_BUDGET = 8;
    private static final String[] ACTION_KEYS = {
            "usb_notification_enable_wlan_handover",
            "usb_notification_switch_profile",
            "usb_notification_new_profile",
    };

    static int columns(String label) {
        int width = 0;
        for (int i = 0; i < label.length(); ) {
            int cp = label.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            if (type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
                    || type == Character.ENCLOSING_MARK) {
                continue;
            }
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            boolean wide = script == Character.UnicodeScript.HAN
                    || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA
                    || script == Character.UnicodeScript.HANGUL;
            width += wide ? 2 : 1;
        }
        return width;
    }

    @Test
    public void columnRuleCountsWideScriptsTwiceAndMarksNotAtAll() {
        assertEquals(8, columns("WLAN-ADB"));
        assertEquals(4, columns("切换"));
        assertEquals(3, columns("बदलें"));
        assertTrue(columns("WLAN-ADB an") > SLOT_BUDGET);
        assertTrue(columns("プロファイル切替") > SLOT_BUDGET);
    }

    @Test
    public void everyUsbActionLabelFitsItsSlotInEveryLocale() throws IOException {
        List<String> violations = new ArrayList<>();
        int checked = 0;
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(resDirectory(), "values*")) {
            for (Path dir : dirs) {
                Path strings = dir.resolve("strings.xml");
                if (!Files.exists(strings)) continue;
                String xml = new String(Files.readAllBytes(strings), StandardCharsets.UTF_8);
                for (String key : ACTION_KEYS) {
                    Matcher m = Pattern.compile("name=\"" + key + "\">([^<]*)<").matcher(xml);
                    if (!m.find()) continue;
                    checked++;
                    String label = m.group(1);
                    if (columns(label) > SLOT_BUDGET) {
                        violations.add(dir.getFileName() + "/" + key + " = \"" + label + "\" ("
                                + columns(label) + " columns)");
                    }
                }
            }
        }
        assertTrue("expected the three labels in all 19 locales, checked " + checked, checked >= 57);
        assertFalse("USB action labels over the " + SLOT_BUDGET + "-column slot budget: " + violations,
                !violations.isEmpty());
    }

    private static Path resDirectory() {
        Path directory = Paths.get("").toAbsolutePath();
        while (directory != null && !Files.exists(directory.resolve("settings.gradle"))) {
            directory = directory.getParent();
        }
        if (directory == null) throw new IllegalStateException("Could not locate project root");
        return directory.resolve("app/src/main/res");
    }
}
