package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
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
 * #770, #965: pins the glossary of the UX concept (section 6) per locale. Retired terms must not
 * come back in any string, and the cross-locale rules hold in every language: one word for
 * "unknown"; "Wifi-ADB" and "Keep-Alive" are the one spelling of KeepADB's switch and of the
 * automatic re-enabling; Android's own localized label of the setting never appears in app
 * strings (the app says "Wifi-ADB"); the endpoint is "Port &lt;port&gt; @ &lt;ip&gt;" and the
 * narrow surfaces show "Port &lt;port&gt;". The docs, README and store texts follow the same rules.
 * Reads the source files, so no resource compilation is involved.
 */
public class KeepADBGlossaryContractTest {
    private static final Pattern STRING =
            Pattern.compile("<string name=\"([^\"]+)\"[^>]*>(.*?)</string>", Pattern.DOTALL);

    /**
     * Android's own setting "Wireless debugging" as the system names it in each language (the
     * locale suffix is the value-directory suffix, "" is English). KeepADB's switch is "Wifi-ADB"
     * (#965); this label must not appear in any app string.
     * Declined forms of one language are separated by a bar.
     */
    private static final Map<String, String> ANDROID_NAME = new LinkedHashMap<>();

    /**
     * #962: the one string that has to use Android's label. It sends the user into Android's
     * developer options to tap that very entry, so it must be found under the name shown there.
     */
    private static final String ANDROID_LABEL_ON_PURPOSE = "selfpair_step_1";

    static {
        ANDROID_NAME.put("", "Wireless debugging");
        ANDROID_NAME.put("-ar", "تصحيح الأخطاء اللاسلكي");
        ANDROID_NAME.put("-de", "Drahtloses Debugging");
        ANDROID_NAME.put("-es", "Depuración inalámbrica");
        ANDROID_NAME.put("-fr", "Débogage sans fil");
        ANDROID_NAME.put("-hi", "वायरलेस डीबगिंग");
        ANDROID_NAME.put("-in", "Debugging nirkabel");
        ANDROID_NAME.put("-it", "Debug wireless");
        ANDROID_NAME.put("-ja", "ワイヤレスデバッグ");
        ANDROID_NAME.put("-ko", "무선 디버깅");
        ANDROID_NAME.put("-nl", "Draadloze foutopsporing");
        ANDROID_NAME.put("-pl", "Debugowanie bezprzewodowe");
        ANDROID_NAME.put("-pt", "Depuração sem fio");
        ANDROID_NAME.put("-ru", "Беспроводная отладка|Беспроводную отладку");
        ANDROID_NAME.put("-tr", "Kablosuz hata ayıklama");
        ANDROID_NAME.put("-uk", "Бездротове налагодження");
        ANDROID_NAME.put("-vi", "Gỡ lỗi không dây");
        ANDROID_NAME.put("-zh-rCN", "无线调试");
        ANDROID_NAME.put("-zh-rTW", "無線偵錯");
    }

    /** The strings that used to quote Android's label; they now say "Wifi-ADB" instead. */
    private static final List<String> FORMERLY_ANDROID_NAME_KEYS = Arrays.asList(
            "connect_help_step_phone", "setup_state_offline_body", "toggle_subtext",
            "status_off_keep_alive_blocked_recovery_backoff", "home_reason_backoff");

    /**
     * Old and variant spellings that must not return, in any locale (English wording in code,
     * docs and store texts as well): the product terms are "Wifi-ADB" and "Keep-Alive" (#965).
     */
    private static final List<String> VARIANT_SPELLINGS = Arrays.asList(
            "Wi-Fi ADB", "WiFi ADB", "Wifi ADB", "WiFi-ADB", "Wi-Fi-ADB", "Wireless ADB",
            "wireless ADB", "Keep alive", "keep alive", "Keep-alive", "keep-alive", "KeepAlive",
            "Keepalive", "Keep persistently active", "Networks list", "network list");

    /** Retired terms per locale directory suffix ("" is the English reference). */
    private static final Map<String, List<String>> RETIRED = new LinkedHashMap<>();

    static {
        RETIRED.put("", Arrays.asList("Background access", "network identity", "Allowed access point",
                "allowed list", "allowed access points", "Keep persistently active"));
        RETIRED.put("-de", Arrays.asList("Zugangspunkt", "Netzwerkname", "Freigabeliste", "reigegeben",
                "Endpoint", "Hintergrundzugriff", "AP-Wechsel", "vertrauenswürdig", "Dauerhaft aktiv halten", "Netzwerkliste"));
        RETIRED.put("-es", Arrays.asList("Acceso en segundo plano", "de permitidos",
                "puntos de acceso permitidos", "Punto de acceso permitido", "endpoint", "Mantener siempre activo", "Mantener activo", "lista Redes"));
        RETIRED.put("-fr", Arrays.asList("Accès en arrière-plan", "d’autorisés",
                "points d’accès autorisés", "Point d’accès autorisé", "de confiance", "Maintenir actif en permanence"));
        RETIRED.put("-it", Arrays.asList("attendibil", "Accesso in background", "dei consentiti",
                "access point", "Mantieni sempre attivo", "elenco Reti"));
        RETIRED.put("-pt", Arrays.asList("confiáve", "Acesso em segundo plano", "de permitidos", "Manter sempre ativo", "lista Redes"));
        RETIRED.put("-nl", Arrays.asList("toegangspunt", "Achtergrondtoegang", "toegestane",
                "draadloos debuggen", "Draadloos debuggen", "Permanent actief houden", "lijst Netwerken"));
        RETIRED.put("-pl", Arrays.asList("punktów dostępu", "punkt dostępu", "Dozwolon", "listy dozwolonych", "dozwolonych punktów", "Dostęp w tle", "Utrzymuj stale aktywne", "liście Sieci"));
        RETIRED.put("-ru", Arrays.asList("Разрешённ", "разрешённ", "Фоновый доступ", "эндпоинт", "Поддерживать постоянно активным", "списке сетей"));
        RETIRED.put("-tr", Arrays.asList("Arka plan erişimi", "izin verilen erişim", "Sürekli aktif tut", "Ağlar listesi"));
        RETIRED.put("-uk", Arrays.asList("Дозволені точки", "Дозволена точка", "списку дозволених",
                "Фоновий доступ", "Підтримувати постійно активним", "списку мереж"));
        RETIRED.put("-vi", Arrays.asList("đáng tin cậy", "Truy cập nền", "Endpoint",
                "danh sách được phép", "Luôn duy trì hoạt động", "danh sách Mạng"));
        RETIRED.put("-in", Arrays.asList("tepercaya", "Akses latar belakang", "daftar yang diizinkan", "Pertahankan selalu aktif", "daftar Jaringan"));
        RETIRED.put("-ja", Arrays.asList("許可リスト", "バックグラウンドアクセス", "信頼できるネットワーク", "常にアクティブに保つ", "ネットワーク一覧"));
        RETIRED.put("-ko", Arrays.asList("허용 목록", "백그라운드 액세스", "지속적으로 활성 상태 유지", "네트워크 목록"));
        RETIRED.put("-zh-rCN", Arrays.asList("受信任", "允许列表", "后台访问", "保持持久启用", "保持常驻", "网络列表"));
        RETIRED.put("-zh-rTW", Arrays.asList("受信任", "允許清單", "背景存取", "保持永久啟用", "保持常駐", "網路清單"));
        RETIRED.put("-ar", Arrays.asList("المسموح", "الوصول في الخلفية", "إبقاء مفعلًا باستمرار", "قائمة الشبكات"));
        RETIRED.put("-hi", Arrays.asList("विश्वसनीय", "अनुमति सूची", "बैकग्राउंड एक्सेस", "एक्सेस पॉइंट", "लगातार सक्रिय रखें", "नेटवर्क सूची"));
    }

    /** The webhook plain-text warning talks about a trusted LAN, not about Keep-Alive trust. */
    private static final String DE_EXEMPT_KEY = "settings_webhook_cleartext_warning";

    @Test
    public void retiredTermsStayOutOfEveryLocale() throws IOException {
        for (Map.Entry<String, List<String>> locale : RETIRED.entrySet()) {
            for (Map.Entry<String, String> entry : strings(locale.getKey()).entrySet()) {
                for (String term : locale.getValue()) {
                    if (DE_EXEMPT_KEY.equals(entry.getKey())) continue;
                    assertFalse("values" + locale.getKey() + "/" + entry.getKey()
                                    + " still uses retired term '" + term + "': " + entry.getValue(),
                            entry.getValue().contains(term));
                }
            }
        }
    }

    /** #957: "ON (Connected)" read as "a computer is connected"; the state never says that. */
    @Test
    public void noStateOfTheSwitchIsCalledConnectedOrDisconnected() throws IOException {
        Map<String, String> english = strings("");
        assertFalse(english.containsKey("status_on"));
        assertFalse(english.containsKey("status_enabled_disconnected"));
        for (String key : new String[] {"home_state_on", "home_state_off", "home_reason_no_wifi"}) {
            String value = english.get(key);
            assertFalse(key + ": " + value, value.contains("onnected"));
        }
    }

    /** #965: one name for KeepADB's switch, one for the automation, one for the list of networks. */
    @Test
    public void theProductTermsAreSpelledTheSameInEveryLocale() throws IOException {
        for (String locale : RETIRED.keySet()) {
            Map<String, String> values = strings(locale);
            String where = "values" + locale + "/";
            for (String key : new String[] {"toggle_label", "widget_action_adb"}) {
                assertEquals(where + key, "Wifi-ADB", values.get(key));
            }
            for (String key : new String[] {"keep_alive_toggle", "settings_section_keep_alive",
                    "widget_action_keep_alive"}) {
                assertEquals(where + key, "Keep-Alive", values.get(key));
            }
            for (String key : new String[] {"home_state_on", "home_state_off",
                    "notification_title_active", "notification_title_disabled",
                    "notification_action_disable", "shortcut_toggle_short",
                    "shortcut_toggle_long", "status_off_keep_alive_waiting"}) {
                assertTrue(where + key + " names the switch: " + values.get(key),
                        values.get(key).contains("Wifi-ADB"));
            }
            assertEquals(where + "home_action_networks leads to the list and names it as it is",
                    values.get("networks_title"), values.get("home_action_networks"));
        }
        assertFalse(strings("").containsKey("tile_state_connected_format"));
    }

    /**
     * #965: the app never quotes Android's own label of the wireless debugging setting. Its
     * general name is "Wifi-ADB" in every locale, and the strings that used to name Android's
     * label (developer options hint, offline hint, switch subtext, backoff texts) say it now.
     */
    @Test
    public void appStringsNameTheSettingWifiAdbNotAndroidsLabel() throws IOException {
        for (Map.Entry<String, String> locale : ANDROID_NAME.entrySet()) {
            Map<String, String> values = strings(locale.getKey());
            for (String form : locale.getValue().split("\\|")) {
                String needle = form.toLowerCase(java.util.Locale.ROOT);
                for (Map.Entry<String, String> entry : values.entrySet()) {
                    if (ANDROID_LABEL_ON_PURPOSE.equals(entry.getKey())) continue;
                    assertFalse("values" + locale.getKey() + "/" + entry.getKey()
                                    + " quotes Android's label '" + form + "': " + entry.getValue(),
                            entry.getValue().toLowerCase(java.util.Locale.ROOT).contains(needle));
                }
            }
            for (String key : FORMERLY_ANDROID_NAME_KEYS) {
                assertTrue("values" + locale.getKey() + "/" + key + " must say Wifi-ADB",
                        values.get(key).contains("Wifi-ADB"));
            }
        }
    }

    /** #965: no variant spelling of the terms, in any locale. */
    @Test
    public void noVariantSpellingOfTheProductTermsInAnyLocale() throws IOException {
        Pattern keepAlive = Pattern.compile("(?i)keep[ -]?alive");
        for (String locale : RETIRED.keySet()) {
            for (Map.Entry<String, String> entry : strings(locale).entrySet()) {
                String where = "values" + locale + "/" + entry.getKey() + ": " + entry.getValue();
                for (String variant : VARIANT_SPELLINGS) {
                    assertFalse(where + " uses '" + variant + "'", entry.getValue().contains(variant));
                }
                Matcher m = keepAlive.matcher(entry.getValue());
                while (m.find()) {
                    assertEquals(where + " spells Keep-Alive differently", "Keep-Alive", m.group());
                }
            }
        }
    }

    /** #965: one endpoint format; the narrow surfaces (tile, small widget) show the port only. */
    @Test
    public void theEndpointIsPortAtIpAndTheNarrowSurfacesShowThePortOnly() throws IOException {
        String joiner = " @ %2$s";
        for (String locale : RETIRED.keySet()) {
            Map<String, String> values = strings(locale);
            String full = values.get("endpoint_port_at_ip");
            assertTrue("values" + locale + ": " + full, full.contains("%1$d") && full.endsWith(joiner));
            assertEquals("values" + locale + ": the port-only form is the full form without the address",
                    full.substring(0, full.length() - joiner.length()), values.get("endpoint_port_only"));
            for (Map.Entry<String, String> entry : values.entrySet()) {
                String where = "values" + locale + "/" + entry.getKey() + ": " + entry.getValue();
                assertFalse(where + " shows the endpoint as ip:port", entry.getValue().contains("%1$s:%2$d"));
                assertFalse(where + " shows the endpoint as ip:port", entry.getValue().contains("IP:"));
                if (!"settings_webhook_url_hint".equals(entry.getKey())) {
                    assertFalse(where + " shows the endpoint as host:port",
                            entry.getValue().contains("host:port"));
                }
            }
        }
    }

    /**
     * #965: the docs, the README and the store texts use the same terms. Android's setting appears
     * in quotation marks only, "Wifi-ADB" and "Keep-Alive" are spelled one way, the endpoint is
     * "Port &lt;port&gt; @ &lt;ip&gt;" (the payload of the webhook and the adb command stay technical).
     * The archive, the design notes and the release notes under docs/ are history and not checked.
     */
    @Test
    public void docsAndStoreTextsFollowTheTerms() throws IOException {
        Path root = projectRoot();
        List<Path> files = new java.util.ArrayList<>();
        files.add(root.resolve("README.md"));
        files.add(root.resolve("SECURITY.md"));
        files.add(root.resolve("CONTRIBUTING.md"));
        try (java.util.stream.Stream<Path> list = Files.list(root.resolve("docs"))) {
            list.filter(f -> f.toString().endsWith(".md")).sorted().forEach(files::add);
        }
        try (java.util.stream.Stream<Path> list = Files.list(root.resolve("fastlane/metadata/android"))) {
            for (Path dir : (Iterable<Path>) list.sorted()::iterator) {
                for (String name : new String[] {"full_description.txt", "short_description.txt", "title.txt"}) {
                    if (Files.isRegularFile(dir.resolve(name))) files.add(dir.resolve(name));
                }
            }
        }
        Pattern keepAlive = Pattern.compile("(?i)keep[ -]?alive");
        Pattern android = Pattern.compile("(?i)wireless[ -]debugging");
        Pattern hostPort = Pattern.compile("(?i)\\b(ip|host):port");
        for (Path file : files) {
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            String name = root.relativize(file).toString();
            for (String variant : VARIANT_SPELLINGS) {
                assertFalse(name + " uses '" + variant + "'", text.contains(variant));
            }
            Matcher k = keepAlive.matcher(text);
            while (k.find()) {
                assertEquals(name + " spells Keep-Alive differently near: " + context(text, k.start()),
                        "Keep-Alive", k.group());
            }
            Matcher a = android.matcher(text);
            while (a.find()) {
                int before = a.start() - 1;
                assertTrue(name + " names Android's setting without quotation marks near: "
                                + context(text, a.start()),
                        before >= 0 && (text.charAt(before) == '"' || text.charAt(before) == '“'));
            }
            Matcher h = hostPort.matcher(text);
            while (h.find()) {
                assertTrue(name + " shows an endpoint as host:port near: " + context(text, h.start()),
                        name.endsWith("webhook-register.md") || name.endsWith("CONTRIBUTING.md"));
            }
        }
    }

    private static String context(String text, int at) {
        return text.substring(Math.max(0, at - 40), Math.min(text.length(), at + 60)).replace('\n', ' ');
    }

    private static Path projectRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        return Files.isDirectory(cwd.resolve("app/src")) ? cwd : cwd.getParent();
    }

    @Test
    public void unknownHasOneWordPerLocale() throws IOException {
        for (String locale : RETIRED.keySet()) {
            Map<String, String> values = strings(locale);
            assertEquals("values" + locale + ": 'unknown' differs between card status and badge",
                    values.get("networks_badge_unknown"), values.get("network_status_not_allowed"));
        }
    }

    @Test
    public void backgroundDetectionTitleIsUsedForTheBackgroundLabels() throws IOException {
        for (String locale : RETIRED.keySet()) {
            if (locale.isEmpty()) continue; // English wording differs ("Detection in the background")
            Map<String, String> values = strings(locale);
            String term = values.get("onboarding_perm_background_title");
            assertTrue("values" + locale + ": missing background title", term != null && !term.isEmpty());
            // The settings button must name the same capability as the assistant step title.
            String stem = term.length() > 6 ? term.substring(0, term.length() - 2) : term;
            assertTrue("values" + locale + ": network_background_setup_button does not use '" + stem + "'",
                    values.get("network_background_setup_button").toLowerCase(java.util.Locale.ROOT)
                            .contains(stem.toLowerCase(java.util.Locale.ROOT)));
        }
    }

    private static Map<String, String> strings(String locale) throws IOException {
        Path res = Paths.get("src/main/res");
        if (!Files.isDirectory(res)) res = Paths.get("app/src/main/res");
        Path file = res.resolve("values" + locale).resolve("strings.xml");
        String xml = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = STRING.matcher(xml);
        while (m.find()) out.put(m.group(1), m.group(2));
        assertTrue(file + " parsed no strings", !out.isEmpty());
        return out;
    }
}
