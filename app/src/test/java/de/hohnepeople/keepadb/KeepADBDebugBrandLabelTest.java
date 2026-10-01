package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
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
            // Strings that start with the brand name take it as %1$s argument, so the debug
            // prefix reaches notification titles, the widget text and the tile error toast too.
            String brand = context.getString(R.string.app_name);
            int[] brandedTexts = {
                    R.string.notification_title_active, R.string.notification_title_disabled,
                    R.string.notification_title_searching,
                    R.string.notification_permission_missing_title,
                    R.string.widget_text_permission_missing, R.string.tile_permission_error};
            for (int id : brandedTexts) {
                String text = context.getString(id, brand);
                assertTrue(locale + " " + context.getResources().getResourceEntryName(id)
                        + " must start with the debug brand label: " + text,
                        text.startsWith("(DBG) KeepADB"));
            }
        }
    }
}
