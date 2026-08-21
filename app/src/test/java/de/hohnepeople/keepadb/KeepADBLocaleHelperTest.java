package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class KeepADBLocaleHelperTest {

    @Test
    public void testSupportedLanguagesCount() {
        assertEquals(20, KeepADBLocaleHelper.SUPPORTED_LANGUAGES.length);
        assertEquals("", KeepADBLocaleHelper.SUPPORTED_LANGUAGES[0].tag);
    }

    @Test
    public void testMatchSupportedTag() {
        assertEquals("de", KeepADBLocaleHelper.matchSupportedTag("de"));
        assertEquals("de", KeepADBLocaleHelper.matchSupportedTag("de-DE"));
        assertEquals("en", KeepADBLocaleHelper.matchSupportedTag("en-US"));
        assertEquals("en", KeepADBLocaleHelper.matchSupportedTag("en-GB"));
        assertEquals("es", KeepADBLocaleHelper.matchSupportedTag("es-ES"));
        assertEquals("fr", KeepADBLocaleHelper.matchSupportedTag("fr-FR"));
        assertEquals("ja", KeepADBLocaleHelper.matchSupportedTag("ja-JP"));
        assertEquals("zh-CN", KeepADBLocaleHelper.matchSupportedTag("zh-CN"));
        assertEquals("zh-TW", KeepADBLocaleHelper.matchSupportedTag("zh-TW"));
        assertEquals("", KeepADBLocaleHelper.matchSupportedTag(""));
        assertEquals("", KeepADBLocaleHelper.matchSupportedTag(null));
    }

    @Test
    public void testEndonymsPresent() {
        for (int i = 1; i < KeepADBLocaleHelper.SUPPORTED_LANGUAGES.length; i++) {
            KeepADBLocaleHelper.LanguageItem item = KeepADBLocaleHelper.SUPPORTED_LANGUAGES[i];
            assertNotNull(item.tag);
            assertTrue(!item.tag.isEmpty());
            assertNotNull(item.endonym);
            assertTrue(!item.endonym.isEmpty());
        }
    }
}
