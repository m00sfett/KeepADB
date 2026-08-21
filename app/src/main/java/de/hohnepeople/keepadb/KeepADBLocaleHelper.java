package de.hohnepeople.keepadb;

import android.app.Activity;
import android.app.LocaleManager;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;

import java.util.Locale;

/** Helper for managing in-app language selection and per-app locales. */
public final class KeepADBLocaleHelper {

    public static final class LanguageItem {
        public final String tag;
        public final String endonym;

        public LanguageItem(String tag, String endonym) {
            this.tag = tag;
            this.endonym = endonym;
        }
    }

    public static final LanguageItem[] SUPPORTED_LANGUAGES = new LanguageItem[]{
            new LanguageItem("", ""), // System default (resolved dynamically)
            new LanguageItem("en", "English"),
            new LanguageItem("de", "Deutsch"),
            new LanguageItem("es", "Español"),
            new LanguageItem("fr", "Français"),
            new LanguageItem("pt", "Português"),
            new LanguageItem("it", "Italiano"),
            new LanguageItem("nl", "Nederlands"),
            new LanguageItem("pl", "Polski"),
            new LanguageItem("uk", "Українська"),
            new LanguageItem("ru", "Русский"),
            new LanguageItem("tr", "Türkçe"),
            new LanguageItem("ar", "العربية"),
            new LanguageItem("hi", "हिन्दी"),
            new LanguageItem("zh-CN", "中文 (简体)"),
            new LanguageItem("zh-TW", "中文 (繁體)"),
            new LanguageItem("ja", "日本語"),
            new LanguageItem("ko", "한국어"),
            new LanguageItem("id", "Bahasa Indonesia"),
            new LanguageItem("vi", "Tiếng Việt")
    };

    private KeepADBLocaleHelper() {}

    public static String getSelectedLanguageTag(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            LocaleManager lm = context.getSystemService(LocaleManager.class);
            if (lm == null) {
                return "";
            }
            LocaleList locales = lm.getApplicationLocales();
            if (!locales.isEmpty()) {
                Locale primary = locales.get(0);
                return matchSupportedTag(primary.toLanguageTag());
            }
            // API 33+ deliberately does not migrate the pre-33 preference. The old value is
            // discarded so a later downgrade cannot resurrect a stale app-language choice.
            KeepADBPreferences.setAppLanguage(context, "");
            return "";
        }
        return KeepADBPreferences.getAppLanguage(context);
    }

    public static String getLanguageDisplayName(Context context, String tag) {
        if (tag == null || tag.isEmpty()) {
            return context.getString(R.string.settings_language_system_default);
        }
        for (LanguageItem item : SUPPORTED_LANGUAGES) {
            if (!item.tag.isEmpty() && isMatchingLocaleTag(item.tag, tag)) {
                return item.endonym;
            }
        }
        return Locale.forLanguageTag(tag).getDisplayName(Locale.forLanguageTag(tag));
    }

    public static void setAppLanguage(Activity activity, String languageTag) {
        String safeTag = languageTag == null ? "" : languageTag.trim();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            LocaleManager lm = activity.getSystemService(LocaleManager.class);
            if (lm == null) {
                return;
            }
            if (safeTag.isEmpty()) {
                lm.setApplicationLocales(LocaleList.getEmptyLocaleList());
            } else {
                lm.setApplicationLocales(LocaleList.forLanguageTags(safeTag));
            }
            // API 33+ owns the locale in LocaleManager; discard the pre-33 fallback value.
            KeepADBPreferences.setAppLanguage(activity, "");
            return;
        }

        KeepADBPreferences.setAppLanguage(activity, safeTag);
        activity.recreate();
    }

    public static Context wrapContext(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return context;
        }
        String tag = KeepADBPreferences.getAppLanguage(context);
        if (tag == null || tag.isEmpty()) {
            return context;
        }
        Locale locale = Locale.forLanguageTag(tag);
        Locale.setDefault(locale);
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.setLocale(locale);
        config.setLayoutDirection(locale);
        return context.createConfigurationContext(config);
    }

    static boolean isSelectedLanguageApplied(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return true;
        }
        String selectedTag = KeepADBPreferences.getAppLanguage(context);
        if (selectedTag == null || selectedTag.isEmpty()) {
            Locale current = context.getResources().getConfiguration().getLocales().get(0);
            Locale system = Resources.getSystem().getConfiguration().getLocales().get(0);
            return isMatchingLocaleTag(system.toLanguageTag(), current.toLanguageTag());
        }
        Locale current = context.getResources().getConfiguration().getLocales().get(0);
        return isMatchingLocaleTag(selectedTag, current.toLanguageTag());
    }

    public static String matchSupportedTag(String tag) {
        if (tag == null || tag.isEmpty()) {
            return "";
        }
        for (LanguageItem item : SUPPORTED_LANGUAGES) {
            if (!item.tag.isEmpty() && isMatchingLocaleTag(item.tag, tag)) {
                return item.tag;
            }
        }
        return tag;
    }

    private static boolean isMatchingLocaleTag(String supportedTag, String targetTag) {
        if (supportedTag.equalsIgnoreCase(targetTag)) {
            return true;
        }
        Locale supp = Locale.forLanguageTag(supportedTag);
        Locale targ = Locale.forLanguageTag(targetTag);
        if (!supp.getLanguage().equalsIgnoreCase(targ.getLanguage())) {
            return false;
        }
        if (!supp.getCountry().isEmpty()) {
            return supp.getCountry().equalsIgnoreCase(targ.getCountry());
        }
        return true;
    }
}
