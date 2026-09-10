package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.LocaleList;

import androidx.test.core.app.ApplicationProvider;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Runtime resource contracts resolved through Android's Resources implementation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBResourceContractTest {
    private static final Pattern FORMAT_ARGUMENT = Pattern.compile("%(?:\\d+\\$)?[a-zA-Z]");
    private static final String[] LOCALE_TAGS = {
            "de", "es", "fr", "pt", "it", "nl", "pl", "uk", "ru", "tr", "ar", "hi",
            "zh-CN", "zh-TW", "ja", "ko", "id", "vi"
    };
    private static final String[] FALLBACK_CHECK_LOCALE_TAGS = {
            "de", "es", "fr", "pt", "it", "nl", "pl", "uk", "ru", "tr", "ar", "hi",
            "zh-CN", "zh-TW", "ja", "ko", "vi"
    };
    private static final int[] TRANSLATED_CONTRACTS = {
            R.string.notification_permission_panel_title,
            R.string.battery_optimization_body,
            R.string.back,
            R.string.advice_banner_text,
            R.string.settings_language_accessibility,
            R.string.settings_usb_handover_accessibility
    };

    private final Context context = ApplicationProvider.getApplicationContext();

    @Test
    public void everyGeneratedStringResolvesInEverySupportedLocale() throws IllegalAccessException {
        for (String languageTag : LOCALE_TAGS) {
            Resources resources = resourcesFor(languageTag);
            for (Field field : R.string.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) || field.getType() != int.class) continue;
                int id = field.getInt(null);
                String value = resources.getString(id);
                assertFalse(languageTag + " returned an empty value for " + field.getName(),
                        value.trim().isEmpty());
                assertEquals(languageTag + " changed format arguments for " + field.getName(),
                        formatArguments(context.getString(id)), formatArguments(value));
            }
        }
    }

    @Test
    public void localizedContractStringsDoNotSilentlyFallBackToEnglish()
            throws IllegalAccessException {
        // Robolectric 4.13 maps the Android values-id bucket to its default locale. Indonesian
        // remains covered by the all-ID resolution/format pass above; this strict provenance
        // assertion is limited to buckets the test runtime can select correctly.
        for (String languageTag : FALLBACK_CHECK_LOCALE_TAGS) {
            Resources resources = resourcesFor(languageTag);
            for (int id : TRANSLATED_CONTRACTS) {
                assertNotEquals(languageTag + " fell back to the default value for "
                                + context.getResources().getResourceEntryName(id),
                        context.getString(id), resources.getString(id));
            }
        }
    }

    @Test
    public void formattedAccessibilityStringsAcceptArgumentsInEveryLocale() {
        for (String languageTag : LOCALE_TAGS) {
            Resources resources = resourcesFor(languageTag);
            assertTrue(languageTag + " must preserve the language argument",
                    resources.getString(R.string.settings_language_accessibility, "Deutsch")
                            .contains("Deutsch"));
            assertTrue(languageTag + " must preserve the USB handover argument",
                    resources.getString(R.string.settings_usb_handover_accessibility, "Automatic")
                            .contains("Automatic"));
        }
    }

    private Resources resourcesFor(String languageTag) {
        Configuration configuration = new Configuration(context.getResources().getConfiguration());
        configuration.setLocales(new LocaleList(Locale.forLanguageTag(languageTag)));
        return context.createConfigurationContext(configuration).getResources();
    }

    private List<String> formatArguments(String value) {
        List<String> result = new ArrayList<>();
        Matcher matcher = FORMAT_ARGUMENT.matcher(value);
        while (matcher.find()) result.add(matcher.group());
        return result;
    }
}
