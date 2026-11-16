package de.hohnepeople.keepadb;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * #955: the help texts under the persistent-notification switch name the privacy switch that
 * really controls the connection details, with the exact label and section title each locale
 * shows, instead of pointing "below" at a row that does not exist.
 */
public class KeepADBNotificationHelpTextContractTest {
    private static final Pattern STRING =
            Pattern.compile("<string name=\"([^\"]+)\"[^>]*>(.*?)</string>", Pattern.DOTALL);
    private static final List<String> LOCALES = Arrays.asList("", "-ar", "-de", "-es", "-fr",
            "-hi", "-in", "-it", "-ja", "-ko", "-nl", "-pl", "-pt", "-ru", "-tr", "-uk", "-vi",
            "-zh-rCN", "-zh-rTW");
    private static final List<String> HELP_KEYS = Arrays.asList(
            "settings_hide_notification_subtext", "settings_hide_notification_subtext_keepalive");

    @Test
    public void everyLocaleNamesThePrivacySwitchAndItsSection() throws IOException {
        for (String locale : LOCALES) {
            Map<String, String> values = strings(locale);
            String label = values.get("privacy_notifications_title");
            String section = values.get("privacy_switches_title");
            assertTrue("values" + locale + ": privacy switch label missing", label != null);
            for (String key : HELP_KEYS) {
                String text = values.get(key);
                assertTrue("values" + locale + "/" + key + " must name the switch '" + label + "'",
                        text.contains(label));
                assertTrue("values" + locale + "/" + key + " must name the section '" + section
                        + "'", text.contains(section));
            }
        }
    }

    @Test
    public void englishNoLongerPointsBelow() throws IOException {
        Map<String, String> values = strings("");
        for (String key : HELP_KEYS) {
            assertFalse(key + " still points at a row below", values.get(key).contains("below"));
            assertFalse(key + " still says 'separately'", values.get(key).contains("separately"));
        }
    }

    private static Map<String, String> strings(String locale) throws IOException {
        Path res = Paths.get("src/main/res");
        if (!Files.isDirectory(res)) res = Paths.get("app/src/main/res");
        String xml = new String(Files.readAllBytes(
                res.resolve("values" + locale).resolve("strings.xml")), StandardCharsets.UTF_8);
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = STRING.matcher(xml);
        while (m.find()) out.put(m.group(1), m.group(2));
        return out;
    }
}
