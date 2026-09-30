package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * #690: the debug build must mark its brand label with "(DBG)" in every locale. A locale-specific
 * brand string in src/main would shadow the debug overlay in values/, so the brand strings live
 * only in values/ (translatable="false"); the tile label is an alias of app_name.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBDebugBrandLabelTest {

    private static final String[] LOCALES = {
            "en", "ar", "de", "es", "fr", "hi", "id", "it", "ja", "ko", "nl", "pl", "pt", "ru",
            "tr", "uk", "vi", "zh-rCN", "zh-rTW"};

    @Test
    public void debugBrandLabelCarriesPrefixInEveryLocale() {
        Context probe = RuntimeEnvironment.getApplication();
        assumeTrue("debug variant only", probe.getPackageName().endsWith(".debug"));
        for (String locale : LOCALES) {
            RuntimeEnvironment.setQualifiers("+" + locale);
            Context context = RuntimeEnvironment.getApplication();
            assertEquals("app_name in " + locale, "(DBG) KeepADB",
                    context.getString(R.string.app_name));
            assertEquals("title_keepadb in " + locale, "(DBG) KeepADB",
                    context.getString(R.string.title_keepadb));
            assertEquals("tile_label in " + locale, "(DBG) KeepADB",
                    context.getString(R.string.tile_label));
        }
    }
}
