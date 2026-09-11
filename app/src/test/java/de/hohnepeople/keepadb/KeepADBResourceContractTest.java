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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Runtime resource contracts backed by Android's compiled resource table. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBResourceContractTest {
    private static final Pattern FORMAT_ARGUMENT = Pattern.compile("%(?:\\d+\\$)?[a-zA-Z]");
    private static final Pattern STRING_RESOURCE = Pattern.compile(
            "(?m)^    resource 0x[0-9a-f]+ string/([^\\s]+)\\s*$");
    private static final Pattern RESOURCE_CONFIGURATION = Pattern.compile(
            "(?m)^      \\(([^)]*)\\)(?:\\s|$)");
    private static final Map<String, String> SUPPORTED_LOCALES = supportedLocales();
    private static final int[] TRANSLATED_CONTRACTS = {
            R.string.notification_permission_panel_title,
            R.string.battery_optimization_body,
            R.string.back,
            R.string.advice_banner_text,
            R.string.settings_language_accessibility,
            R.string.settings_usb_handover_accessibility
    };

    private final Context context = ApplicationProvider.getApplicationContext();
    private Map<String, Map<String, String>> compiledStrings;

    @Test
    public void everyGeneratedStringResolvesInEverySupportedLocale() throws Exception {
        for (String languageTag : SUPPORTED_LOCALES.keySet()) {
            Resources resources = resourcesFor(languageTag);
            for (Field field : stringResourceFields()) {
                int id = field.getInt(null);
                String value = compiledValue(field.getName(), SUPPORTED_LOCALES.get(languageTag));
                assertFalse(languageTag + " returned an empty value for " + field.getName(),
                        value.trim().isEmpty());
                assertEquals(languageTag + " changed format arguments for " + field.getName(),
                        formatArguments(compiledValue(field.getName(), "")), formatArguments(value));
                // All selectable buckets must agree with the actual linked table. Indonesian
                // is checked below with an explicit guard for Robolectric 4.13's fallback.
                if (!"id".equals(languageTag)) {
                    assertEquals(languageTag + "/" + field.getName() + " compiled/runtime mismatch",
                            value, resources.getString(id));
                }
            }
        }
    }

    /**
     * Checks the linked binary resource table rather than comparing rendered text with English.
     * This is the provenance contract: every generated string key has an actual entry in every
     * supported locale bucket, including Indonesian. Robolectric 4.13 cannot select
     * {@code values-id} reliably at runtime, so a runtime-only equality check would silently
     * bless the wrong fallback and would miss this exact failure mode.
     */
    @Test
    public void compiledResourceTableContainsEveryStringKeyInEveryLocaleBucket()
            throws Exception {
        Map<String, Set<String>> configurations = parseStringConfigurations(runAapt2Dump());
        Field[] fields = stringResourceFields();
        assertEquals("The compiled table must expose every generated string key", fields.length,
                configurations.size());

        for (Field field : fields) {
            Set<String> actual = configurations.get(field.getName());
            assertTrue("Missing compiled string resource " + field.getName(), actual != null);
            assertTrue("Missing default value for " + field.getName(), actual.contains(""));
            for (String localeTag : SUPPORTED_LOCALES.values()) {
                assertTrue("Missing " + localeTag + " entry for " + field.getName(),
                        actual.contains(localeTag));
            }
        }
    }

    @Test
    public void translatedContractStringsDoNotSilentlyFallBackToEnglishWhereRuntimeCanSelectThem()
            throws Exception {
        for (String languageTag : SUPPORTED_LOCALES.keySet()) {
            for (int id : TRANSLATED_CONTRACTS) {
                String name = context.getResources().getResourceEntryName(id);
                assertNotEquals(languageTag + " fell back to the default value for "
                                + name,
                        compiledValue(name, ""), compiledValue(name, SUPPORTED_LOCALES.get(languageTag)));
            }
        }
    }

    @Test
    public void formattedAccessibilityStringsAcceptArgumentsInEveryLocale() throws Exception {
        for (String languageTag : SUPPORTED_LOCALES.keySet()) {
            assertTrue(languageTag + " must preserve the language argument",
                    String.format(Locale.forLanguageTag(languageTag),
                            compiledValue("settings_language_accessibility", SUPPORTED_LOCALES.get(languageTag)), "Deutsch")
                            .contains("Deutsch"));
            assertTrue(languageTag + " must preserve the USB handover argument",
                    String.format(Locale.forLanguageTag(languageTag),
                            compiledValue("settings_usb_handover_accessibility", SUPPORTED_LOCALES.get(languageTag)), "Automatic")
                            .contains("Automatic"));
        }
    }

    @Test
    public void indonesianFallbackAndDumpDecodingHaveExplicitProvenance() throws Exception {
        // Guard the known Robolectric 4.13 limitation: when selection changes, remove the
        // exception in the all-key runtime comparison, rather than silently keeping it.
        Resources indonesian = resourcesFor("id");
        for (Field field : stringResourceFields()) {
            assertEquals("Default dump decoding must match Android for " + field.getName(),
                    context.getString(field.getInt(null)), compiledValue(field.getName(), ""));
            assertEquals("Robolectric Indonesian selection changed; revisit the fallback exception",
                    context.getString(field.getInt(null)), indonesian.getString(field.getInt(null)));
        }
        assertNotEquals(compiledValue("settings_language_accessibility", ""),
                compiledValue("settings_language_accessibility", "id"));
    }

    private String compiledValue(String name, String locale) throws Exception {
        return compiledValue(name, locale, new LinkedHashSet<>());
    }

    private String compiledValue(String name, String locale, Set<String> visited) throws Exception {
        assertTrue("Cyclic compiled string alias: " + name, visited.add(name));
        if (compiledStrings == null) {
            compiledStrings = new LinkedHashMap<>();
            String dump = runAapt2Dump();
            Matcher resource = STRING_RESOURCE.matcher(dump);
            Pattern entry = Pattern.compile("(?ms)^      \\(([^)]*)\\) (.*?)(?=\\n      \\(|\\s*\\z)");
            while (resource.find()) {
                int end = dump.indexOf("\n    resource ", resource.end());
                int nextType = dump.indexOf("\n  type ", resource.end());
                if (nextType >= 0 && (end < 0 || nextType < end)) end = nextType;
                String block = dump.substring(resource.end(), end < 0 ? dump.length() : end);
                Map<String, String> values = new LinkedHashMap<>();
                Matcher value = entry.matcher(block);
                // AAPT2 indents continuation lines by six spaces. The all-key runtime
                // comparisons guard this dump-format dependency, including multiline text.
                while (value.find()) {
                    values.put(value.group(1), value.group(2).replace("\n      ", "\n"));
                }
                compiledStrings.put(resource.group(1), values);
            }
        }
        Map<String, String> values = compiledStrings.get(name);
        assertTrue("Missing compiled string " + name, values != null);
        assertTrue("Missing compiled value " + locale + "/" + name, values.containsKey(locale));
        String raw = values.get(locale);
        if (raw.startsWith("@string/")) {
            return compiledValue(raw.substring("@string/".length()), locale, visited);
        }
        assertTrue("Unsupported AAPT2 string encoding: " + locale + "/" + name,
                raw.startsWith("\"") && raw.endsWith("\""));
        return raw.substring(1, raw.length() - 1);
    }

    private Resources resourcesFor(String languageTag) {
        Configuration configuration = new Configuration(context.getResources().getConfiguration());
        configuration.setLocales(new LocaleList(Locale.forLanguageTag(languageTag)));
        return context.createConfigurationContext(configuration).getResources();
    }

    private Field[] stringResourceFields() throws IllegalAccessException {
        List<Field> fields = new ArrayList<>();
        for (Field field : R.string.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == int.class) {
                fields.add(field);
            }
        }
        return fields.toArray(new Field[0]);
    }

    private List<String> formatArguments(String value) {
        List<String> result = new ArrayList<>();
        Matcher matcher = FORMAT_ARGUMENT.matcher(value);
        while (matcher.find()) result.add(matcher.group());
        return result;
    }

    private Map<String, Set<String>> parseStringConfigurations(String dump) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        Matcher resources = STRING_RESOURCE.matcher(dump);
        List<String> names = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        while (resources.find()) {
            names.add(resources.group(1));
            starts.add(resources.start());
        }
        for (int index = 0; index < names.size(); index++) {
            int end = index + 1 < starts.size() ? starts.get(index + 1) : dump.length();
            String block = dump.substring(starts.get(index), end);
            Set<String> configurations = new LinkedHashSet<>();
            Matcher configMatcher = RESOURCE_CONFIGURATION.matcher(block);
            while (configMatcher.find()) configurations.add(configMatcher.group(1));
            result.put(names.get(index), configurations);
        }
        return result;
    }

    private String runAapt2Dump() throws IOException, InterruptedException {
        Path table = projectRoot().resolve(
                "app/build/intermediates/linked_resources_binary_format/debug/processDebugResources/"
                        + "linked-resources-binary-format-debug.ap_");
        assertTrue("Debug linked resource table is required for the provenance contract",
                Files.isRegularFile(table));

        Process process = new ProcessBuilder(aapt2Command(), "dump", "resources", table.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        assertEquals("aapt2 could not inspect the linked debug resource table", 0, exitCode);
        return output;
    }

    private String aapt2Command() {
        List<Path> candidates = new ArrayList<>();
        addAapt2Candidates(candidates, System.getenv("ANDROID_SDK_ROOT"));
        addAapt2Candidates(candidates, System.getenv("ANDROID_HOME"));
        Path localProperties = projectRoot().resolve("local.properties");
        if (Files.isRegularFile(localProperties)) {
            try {
                for (String line : Files.readAllLines(localProperties, StandardCharsets.UTF_8)) {
                    if (line.startsWith("sdk.dir=")) {
                        addAapt2Candidates(candidates,
                                line.substring("sdk.dir=".length()).replace("\\:", ":"));
                    }
                }
            } catch (IOException ignored) {
                // The executable on PATH remains a valid fallback for the same build.
            }
        }
        for (Path candidate : candidates) {
            if (Files.isExecutable(candidate)) return candidate.toString();
        }
        Path commonPath = Paths.get("/usr/bin/aapt2");
        if (Files.isExecutable(commonPath)) return commonPath.toString();
        return "aapt2";
    }

    private void addAapt2Candidates(List<Path> candidates, String sdkRoot) {
        if (sdkRoot == null || sdkRoot.isBlank()) return;
        for (String version : new String[] {"34.0.0", "35.0.0"}) {
            candidates.add(Paths.get(sdkRoot, "build-tools", version, "aapt2"));
        }
    }

    private Path projectRoot() {
        Path directory = Paths.get("").toAbsolutePath();
        while (directory != null && !Files.exists(directory.resolve("settings.gradle"))) {
            directory = directory.getParent();
        }
        if (directory == null) throw new IllegalStateException("Could not locate project root");
        return directory;
    }

    private static Map<String, String> supportedLocales() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("de", "de");
        result.put("es", "es");
        result.put("fr", "fr");
        result.put("pt", "pt");
        result.put("it", "it");
        result.put("nl", "nl");
        result.put("pl", "pl");
        result.put("uk", "uk");
        result.put("ru", "ru");
        result.put("tr", "tr");
        result.put("ar", "ar");
        result.put("hi", "hi");
        result.put("zh-CN", "zh-rCN");
        result.put("zh-TW", "zh-rTW");
        result.put("ja", "ja");
        result.put("ko", "ko");
        result.put("id", "id");
        result.put("vi", "vi");
        return result;
    }
}
