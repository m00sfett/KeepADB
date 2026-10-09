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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * #770: pins the glossary of the UX concept (section 6) per locale. Retired terms must not come
 * back in any string, and the cross-locale rules (one word for "unknown", Wireless Debugging
 * instead of "Wifi-ADB" outside the short notification forms) hold in every language. Reads the
 * source files, so no resource compilation is involved.
 */
public class KeepADBGlossaryContractTest {
    private static final Pattern STRING =
            Pattern.compile("<string name=\"([^\"]+)\"[^>]*>(.*?)</string>", Pattern.DOTALL);

    /** Short forms of "Wifi-ADB" that the glossary allows (notification titles and actions). */
    private static final Set<String> WIFI_ADB_ALLOWED = new HashSet<>(Arrays.asList(
            "notification_title_active", "notification_title_disabled",
            "usb_notification_enable_wlan_handover", "notification_action_disable"));

    /** Retired terms per locale directory suffix ("" is the English reference). */
    private static final Map<String, List<String>> RETIRED = new LinkedHashMap<>();

    static {
        RETIRED.put("", Arrays.asList("Background access", "network identity", "Allowed access point",
                "allowed list", "allowed access points"));
        RETIRED.put("-de", Arrays.asList("Zugangspunkt", "Netzwerkname", "Freigabeliste", "reigegeben",
                "Endpoint", "Hintergrundzugriff", "AP-Wechsel", "vertrauenswürdig"));
        RETIRED.put("-es", Arrays.asList("Acceso en segundo plano", "de permitidos",
                "puntos de acceso permitidos", "Punto de acceso permitido", "endpoint"));
        RETIRED.put("-fr", Arrays.asList("Accès en arrière-plan", "d’autorisés",
                "points d’accès autorisés", "Point d’accès autorisé", "de confiance"));
        RETIRED.put("-it", Arrays.asList("attendibil", "Accesso in background", "dei consentiti",
                "access point"));
        RETIRED.put("-pt", Arrays.asList("confiáve", "Acesso em segundo plano", "de permitidos"));
        RETIRED.put("-nl", Arrays.asList("toegangspunt", "Achtergrondtoegang", "toegestane",
                "draadloos debuggen", "Draadloos debuggen"));
        RETIRED.put("-pl", Arrays.asList("punktów dostępu", "punkt dostępu", "Dozwolon", "listy dozwolonych", "dozwolonych punktów", "Dostęp w tle"));
        RETIRED.put("-ru", Arrays.asList("Разрешённ", "разрешённ", "Фоновый доступ", "эндпоинт"));
        RETIRED.put("-tr", Arrays.asList("Arka plan erişimi", "izin verilen erişim"));
        RETIRED.put("-uk", Arrays.asList("Дозволені точки", "Дозволена точка", "списку дозволених",
                "Фоновий доступ"));
        RETIRED.put("-vi", Arrays.asList("đáng tin cậy", "Truy cập nền", "Endpoint",
                "danh sách được phép"));
        RETIRED.put("-in", Arrays.asList("tepercaya", "Akses latar belakang", "daftar yang diizinkan"));
        RETIRED.put("-ja", Arrays.asList("許可リスト", "バックグラウンドアクセス", "信頼できるネットワーク"));
        RETIRED.put("-ko", Arrays.asList("허용 목록", "백그라운드 액세스"));
        RETIRED.put("-zh-rCN", Arrays.asList("受信任", "允许列表", "后台访问"));
        RETIRED.put("-zh-rTW", Arrays.asList("受信任", "允許清單", "背景存取"));
        RETIRED.put("-ar", Arrays.asList("المسموح", "الوصول في الخلفية"));
        RETIRED.put("-hi", Arrays.asList("विश्वसनीय", "अनुमति सूची", "बैकग्राउंड एक्सेस", "एक्सेस पॉइंट"));
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

    @Test
    public void wifiAdbOnlyAppearsInTheAllowedShortForms() throws IOException {
        for (String locale : RETIRED.keySet()) {
            for (Map.Entry<String, String> entry : strings(locale).entrySet()) {
                if (WIFI_ADB_ALLOWED.contains(entry.getKey())) continue;
                assertFalse("values" + locale + "/" + entry.getKey() + " uses 'Wifi-ADB'",
                        entry.getValue().contains("Wifi-ADB"));
            }
        }
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
