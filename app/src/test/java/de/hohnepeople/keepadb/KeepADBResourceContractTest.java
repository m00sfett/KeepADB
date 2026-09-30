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
import java.util.IllegalFormatException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Runtime resource contracts backed by Android's compiled resource table. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBResourceContractTest {

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();
    private static final Pattern FORMAT_ARGUMENT = Pattern.compile(
            "%(?:(\\d+)\\$)?([-+# 0,(<]*)(\\d+)?(?:\\.(\\d+))?([tT])?([a-zA-Z%])");
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
    private final Map<String, Object[]> formatArgumentWitnesses = formatArgumentWitnesses();
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

    @Test
    public void repairedTurkishPermissionGuidanceContainsOneInstruction() throws Exception {
        assertEquals("İzin eksik. PC’den bir kez çalıştırın:\n"
                        + "adb shell pm grant %1$s android.permission.WRITE_SECURE_SETTINGS",
                compiledValue("permission_error_toast", "tr"));
    }

    @Test
    public void permissionGuidancePreservesCompiledLineBreaksInEveryLocale() throws Exception {
        String defaultValue = compiledValue("permission_error_toast", "");
        assertEquals("The default permission guidance must contain one line break", 1,
                countLineBreaks(defaultValue));
        for (String languageTag : SUPPORTED_LOCALES.keySet()) {
            assertEquals(languageTag + " changed the compiled permission guidance line breaks",
                    countLineBreaks(defaultValue),
                    countLineBreaks(compiledValue("permission_error_toast",
                            SUPPORTED_LOCALES.get(languageTag))));
        }
    }

    private int countLineBreaks(String value) {
        int count = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == '\n') count++;
        }
        return count;
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

    /**
     * #643: the three identity-unavailable texts must name the permanent fix, the system option
     * "Allow all the time" in that locale's own wording (the same wording the background
     * location panel uses), not only "reopen the app".
     */
    @Test
    public void identityUnavailableTextsNameTheAllowAllTheTimeOptionInEveryLocale()
            throws Exception {
        Map<String, String> phrases = new LinkedHashMap<>();
        phrases.put("", "Allow all the time");
        phrases.put("de", "Immer zulassen");
        phrases.put("es", "Permitir todo el tiempo");
        phrases.put("fr", "Toujours autoriser");
        phrases.put("pt", "Permitir o tempo todo");
        phrases.put("it", "Consenti sempre");
        phrases.put("nl", "Altijd toestaan");
        phrases.put("pl", "Zezwalaj zawsze");
        phrases.put("uk", "Дозволити завжди");
        phrases.put("ru", "Разрешить в любом режиме");
        phrases.put("tr", "Her zaman izin ver");
        phrases.put("ar", "السماح طوال الوقت");
        phrases.put("hi", "हर समय अनुमति दें");
        phrases.put("zh-CN", "始终允许");
        phrases.put("zh-TW", "一律允許");
        phrases.put("ja", "常に許可");
        phrases.put("ko", "항상 허용");
        phrases.put("id", "Izinkan sepanjang waktu");
        phrases.put("vi", "Luôn cho phép");
        String[] names = {"network_prompt_identity_unavailable_text",
                "settings_trusted_network_status_identity_unavailable",
                "status_off_keep_alive_blocked_identity_unavailable"};
        for (Map.Entry<String, String> phrase : phrases.entrySet()) {
            String qualifier = phrase.getKey().isEmpty() ? ""
                    : SUPPORTED_LOCALES.get(phrase.getKey());
            for (String name : names) {
                assertTrue(phrase.getKey() + "/" + name + " must mention '" + phrase.getValue() + "'",
                        compiledValue(name, qualifier).contains(phrase.getValue()));
            }
        }
    }

    /**
     * #654 visual acceptance: the list-inactive hint and the all-networks cause used to quote a
     * truncated mode name ("Only allowed ...") that matched no visible label once the Wi-Fi-name
     * matching renamed the option to "Allowed access points and Wi-Fi names". The hint now takes
     * the visible label as its argument and the cause names no allowed mode at all; no locale may
     * quote a truncated mode name again.
     */
    @Test
    public void modeHintsNeverQuoteATruncatedModeNameInAnyLocale() throws Exception {
        String[] names = {"network_list_inactive_hint", "network_cause_all_wifi"};
        Map<String, String> locales = new LinkedHashMap<>(SUPPORTED_LOCALES);
        locales.put("default", "");
        for (Map.Entry<String, String> locale : locales.entrySet()) {
            for (String name : names) {
                String value = compiledValue(name, locale.getValue());
                assertFalse(locale.getKey() + "/" + name + " quotes a truncated mode name: " + value,
                        value.contains("\u2026") || value.contains("..."));
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
        Set<String> formattedResources = new LinkedHashSet<>();
        for (Field field : stringResourceFields()) {
            String name = field.getName();
            String defaultValue = compiledValue(name, "");
            if (formatArguments(defaultValue).isEmpty()) continue;
            formattedResources.add(name);

            Object[] arguments = formatArgumentWitnesses.get(name);
            assertTrue("Missing real-argument witness for " + name, arguments != null);
            assertEquals(name + " witness must cover every argument position",
                    highestArgumentIndex(formatArguments(defaultValue)), arguments.length);
            assertFormats("default", name, defaultValue, arguments);
            for (String languageTag : SUPPORTED_LOCALES.keySet()) {
                assertFormats(languageTag, name,
                        compiledValue(name, SUPPORTED_LOCALES.get(languageTag)), arguments);
            }
        }
        assertEquals("Every formatted compiled resource needs a real-argument witness",
                formattedResources, new LinkedHashSet<>(formatArgumentWitnesses.keySet()));
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

    private List<FormatArgument> formatArguments(String value) {
        List<FormatArgument> result = new ArrayList<>();
        Matcher matcher = FORMAT_ARGUMENT.matcher(value);
        int nextImplicitArgument = 1;
        int previousArgument = 0;
        while (matcher.find()) {
            char conversion = matcher.group(6).charAt(0);
            if (conversion == '%' || conversion == 'n') continue;

            int argument;
            if (matcher.group(2).indexOf('<') >= 0) {
                if (previousArgument == 0) {
                    throw new IllegalArgumentException("Relative format argument without predecessor: "
                            + matcher.group());
                }
                argument = previousArgument;
            } else if (matcher.group(1) != null) {
                argument = Integer.parseInt(matcher.group(1));
            } else {
                argument = nextImplicitArgument++;
            }
            if (argument < 1) {
                throw new IllegalArgumentException("Format argument indexes start at 1: "
                        + matcher.group());
            }
            previousArgument = argument;
            String type = (matcher.group(5) == null ? "" : matcher.group(5)) + conversion;
            result.add(new FormatArgument(argument, type, matcher.group()));
        }
        return result;
    }

    private int highestArgumentIndex(List<FormatArgument> arguments) {
        int highest = 0;
        for (FormatArgument argument : arguments) highest = Math.max(highest, argument.index);
        return highest;
    }

    private void assertFormats(String languageTag, String name, String value, Object[] arguments) {
        Locale locale = "default".equals(languageTag)
                ? Locale.ROOT : Locale.forLanguageTag(languageTag);
        try {
            String.format(locale, value, arguments);
        } catch (IllegalFormatException exception) {
            throw new AssertionError(languageTag + "/" + name
                    + " cannot be formatted with its real arguments", exception);
        }
    }

    private Map<String, Object[]> formatArgumentWitnesses() {
        Map<String, Object[]> result = new LinkedHashMap<>();
        String stringWitness = context.getPackageName();
        int integerWitness = context.getApplicationInfo().uid;
        long longWitness = integerWitness;
        result.put("endpoint_format", new Object[] {stringWitness, integerWitness});
        result.put("widget_text_connected_format", new Object[] {integerWitness});
        result.put("tile_state_connected_format", new Object[] {stringWitness, integerWitness});
        result.put("permission_error_toast", new Object[] {stringWitness});
        result.put("setup_command", new Object[] {stringWitness});
        result.put("setup_command_multi", new Object[] {stringWitness});
        result.put("notification_text_active", new Object[] {integerWitness, stringWitness});
        result.put("settings_trusted_network_added_toast", new Object[] {stringWitness});
        result.put("wifi_ssids_add_accessibility", new Object[] {stringWitness});
        result.put("wifi_ssids_remove_accessibility", new Object[] {stringWitness});
        result.put("wifi_ssids_added_toast", new Object[] {stringWitness});
        result.put("wifi_ssids_removed_toast", new Object[] {stringWitness});
        result.put("settings_trusted_network_mesh_message", new Object[] {integerWitness, stringWitness});
        result.put("settings_trusted_network_mesh_added_toast", new Object[] {integerWitness});
        result.put("network_prompt_text", new Object[] {stringWitness, stringWitness});
        result.put("settings_trusted_network_blocked_detail",
                new Object[] {stringWitness, stringWitness});
        result.put("network_head_mode", new Object[] {stringWitness});
        result.put("network_list_inactive_hint", new Object[] {stringWitness});
        result.put("network_action_allow_ap_accessibility", new Object[] {stringWitness});
        result.put("network_action_remove_ap_accessibility", new Object[] {stringWitness});
        result.put("network_ssid_state_on", new Object[] {integerWitness});
        result.put("network_ssid_state_no_effect", new Object[] {integerWitness});
        result.put("network_ap_allowed_toast", new Object[] {stringWitness});
        result.put("network_ap_removed_toast", new Object[] {stringWitness});
        result.put("wifi_aps_mesh_label", new Object[] {integerWitness, integerWitness});
        result.put("wifi_aps_show_more_button", new Object[] {integerWitness});
        result.put("webhook_status_hint", new Object[] {
                stringWitness, stringWitness, stringWitness});
        result.put("settings_language_accessibility", new Object[] {stringWitness});
        result.put("usb_profile_selected", new Object[] {stringWitness});
        result.put("usb_profile_edit_action_accessibility", new Object[] {stringWitness});
        result.put("usb_profile_delete_message", new Object[] {stringWitness});
        result.put("usb_profile_delete_action_accessibility", new Object[] {stringWitness});
        result.put("settings_usb_handover_accessibility", new Object[] {stringWitness});
        result.put("settings_version_value", new Object[] {stringWitness});
        result.put("settings_version_code_value", new Object[] {longWitness});
        result.put("issue_report_body", new Object[] {
                stringWitness, stringWitness, stringWitness, stringWitness, stringWitness,
                stringWitness, stringWitness, stringWitness, stringWitness, stringWitness,
                stringWitness, stringWitness, stringWitness});
        result.put("transport_row_format", new Object[] {stringWitness, stringWitness, stringWitness});
        result.put("transport_primary_accessibility_format", new Object[] {stringWitness});
        return result;
    }

    private static final class FormatArgument {
        private final int index;
        private final String type;
        private final String token;

        private FormatArgument(int index, String type, String token) {
            this.index = index;
            this.type = type;
            this.token = token;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof FormatArgument)) return false;
            FormatArgument argument = (FormatArgument) other;
            return index == argument.index && type.equals(argument.type)
                    && token.equals(argument.token);
        }

        @Override
        public int hashCode() {
            int result = index;
            result = 31 * result + type.hashCode();
            return 31 * result + token.hashCode();
        }

        @Override
        public String toString() {
            return token + " -> argument " + index + " (" + type + ")";
        }
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
