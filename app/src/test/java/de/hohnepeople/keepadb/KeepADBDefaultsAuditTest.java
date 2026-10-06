package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import androidx.test.core.app.ApplicationProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowWifiInfo;

import de.hohnepeople.keepadb.KeepADBTrustedNetwork.Decision;
import de.hohnepeople.keepadb.KeepADBTrustedNetwork.ProtectionLevel;

/**
 * #765: the defaults audit of a new installation, as an executable table.
 *
 * <p>Two rules are pinned here, each from both sides:
 * <ul>
 *   <li><b>A new installation starts on the safe value.</b> Every setting reads its audited
 *       default when nothing is stored, the audit table is complete (a new preference key that is
 *       not classified here fails the build), reading the defaults writes nothing (apart from the
 *       one-time network-mode flag of #760), and the acting paths (network trust, boot start, USB
 *       handover) really behave on those defaults.</li>
 *   <li><b>An existing installation keeps what it stored.</b> A stored value that is looser than
 *       the default stays looser, a stored value that is stricter than a (convenience) default stays
 *       stricter, and a value stored explicitly equal to the default stays stored. Nothing is
 *       rewritten by reading.</li>
 * </ul>
 * The stored side is written with the literal key strings, not through the setters, so renaming a
 * key (which would silently reset every installation to the default) fails here too.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBDefaultsAuditTest {

    private static final String UNKNOWN_BSSID = "aa:bb:cc:dd:ee:99";

    @Rule
    public final KeepADBNetworkResetRule keepADBNetworkResetRule = new KeepADBNetworkResetRule();

    private final Application app = ApplicationProvider.getApplicationContext();

    /** The only key a plain read of the defaults may persist (#760, one-time default of the policy). */
    private static final Set<String> MODE_FLAG_KEYS = new TreeSet<>(Arrays.asList(
            "trusted_network_mode", "trusted_network_mode_initialized"));

    /** What a key is, for the audit. */
    private enum Kind {
        /** A user-visible setting whose default has a security meaning; the default is the safe value. */
        SECURITY,
        /** A user-visible setting whose default is a convenience or presentation choice, justified in docs/defaults.md. */
        CONVENIENCE,
        /** A "do not show this card again" flag; presentation only. */
        DISMISS_STATE,
        /** Runtime state kept across process death; not a setting. */
        RUNTIME_STATE
    }

    private static final class Setting {
        final String key;
        final Kind kind;
        final Object defaultValue;
        final Object otherValue;
        final Function<Context, Object> reader;

        Setting(String key, Kind kind, Object defaultValue, Object otherValue,
                Function<Context, Object> reader) {
            this.key = key;
            this.kind = kind;
            this.defaultValue = defaultValue;
            this.otherValue = otherValue;
            this.reader = reader;
        }

        /** Stores {@code value} the way the app stores it: a literal key, the stored type. */
        void store(SharedPreferences.Editor editor, Object value) {
            if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else editor.putString(key, (String) value);
        }
    }

    /**
     * The audit table. Order is the order of docs/defaults.md. Adding a setting means adding a row
     * here and in the document; {@link #everyPreferenceKeyInTheSourcesIsClassifiedByTheAudit} fails
     * until both exist.
     */
    private static final List<Setting> SETTINGS = Collections.unmodifiableList(Arrays.asList(
            // Automatic enabling
            new Setting("keep_alive_enabled", Kind.SECURITY, false, true,
                    c -> KeepADBPreferences.isKeepAliveEnabled(c)),
            new Setting("trusted_network_mode", Kind.SECURITY, "allowlist", "all_wifi",
                    c -> KeepADBTrustedNetwork.getMode(c)),
            new Setting("trust_by_name", Kind.SECURITY, false, true,
                    c -> KeepADBTrustedNetwork.isTrustByNameEnabled(c)),
            new Setting("trusted_network_ssid_matching", Kind.SECURITY, false, true,
                    c -> KeepADBTrustedNetwork.isSsidMatchingEnabled(c)),
            new Setting("usb_wlan_handover_mode", Kind.SECURITY, "off", "automatic",
                    c -> KeepADBPreferences.getUsbWlanHandoverMode(c)),
            // Data leaving the device or the lock screen
            new Setting("register_webhook_enabled", Kind.SECURITY, false, true,
                    c -> KeepADBPreferences.isRegisterWebhookEnabled(c)),
            new Setting("register_webhook_url", Kind.SECURITY, null, "https://example.com/hook",
                    c -> KeepADBPreferences.getRegisterWebhookUrl(c)),
            new Setting("notification_details_enabled", Kind.SECURITY, false, true,
                    c -> KeepADBPreferences.isNotificationDetailsEnabled(c)),
            new Setting("wifi_aps_feature_enabled", Kind.SECURITY, false, true,
                    c -> KeepADBPreferences.isWifiApsFeatureEnabled(c)),
            // Presentation and convenience
            new Setting("privacy_mode_enabled", Kind.CONVENIENCE, false, true,
                    c -> KeepADBPreferences.isPrivacyModeEnabled(c)),
            new Setting("hide_notification_enabled", Kind.CONVENIENCE, false, true,
                    c -> KeepADBPreferences.isNotificationHidden(c)),
            new Setting("keep_display_on_enabled", Kind.CONVENIENCE, false, true,
                    c -> KeepADBPreferences.isKeepDisplayOnEnabled(c)),
            new Setting("usb_notification_enabled", Kind.CONVENIENCE, false, true,
                    c -> KeepADBUsbProfile.isNotificationEnabled(c)),
            new Setting("usb_profile_notification_enabled", Kind.CONVENIENCE, true, false,
                    c -> KeepADBUsbProfile.isProfileNotificationEnabled(c)),
            new Setting("app_language", Kind.CONVENIENCE, "", "de",
                    c -> KeepADBPreferences.getAppLanguage(c)),
            // Dismiss flag of the start screen (#764: the cards of the setup steps are gone)
            new Setting("advice_banner_visible", Kind.DISMISS_STATE, true, false,
                    c -> KeepADBPreferences.isAdviceBannerVisible(c)),
            // Not a setting: the persisted last explicit on/off intent
            new Setting("last_desired_on", Kind.RUNTIME_STATE, true, false,
                    c -> KeepADBPreferences.getLastDesiredOn(c))));

    /**
     * Preference keys that hold the user's own records or bookkeeping, not a setting: nothing to
     * default (absent = empty), nothing to audit beyond "not written by a read".
     */
    private static final Set<String> RECORD_KEYS = Collections.unmodifiableSet(new TreeSet<>(Arrays.asList(
            "register_webhook_last_reported", "register_webhook_last_endpoint",
            "register_webhook_last_url", "register_webhook_last_status",
            "register_webhook_last_success_at", "register_webhook_pending_cleanup",
            "register_webhook_pending_cleanup_retry_state", "service_last_heartbeat",
            "trusted_network_next_id", "trusted_network_ids", "trusted_network_mode_initialized",
            "trusted_ssid_next_id", "trusted_ssid_ids", "blocked_bssids", "blocked_ssids",
            "usb_profile_next_id", "usb_profile_selected_id", "usb_profile_ids",
            "network_prompt_bssid", "network_prompt_at", "network_prompt_history",
            "bssid_history_next_id", "bssid_history_ssid_ids", "blocked_network_entries",
            "events", "location_permission_requested", "notification_permission_requested",
            "force_state", "force_expired_notice_pending", "force_expired_reason",
            "onboarding_completed_version", "onboarding_existing_install")));

    @Before
    public void setUp() {
        prefs().edit().clear().commit();
        KeepADB.resetForTesting();
        KeepADBUsbHandover.resetForTesting();
    }

    @After
    public void tearDown() {
        prefs().edit().clear().commit();
        KeepADB.resetForTesting();
        KeepADBUsbHandover.resetForTesting();
    }

    // --- New installation: the audited defaults ----------------------------------------------------

    @Test
    public void aNewInstallationReadsTheAuditedDefaultOfEverySetting() {
        for (Setting setting : SETTINGS) {
            assertEquals("default of " + setting.key, setting.defaultValue, setting.reader.apply(app));
        }
    }

    @Test
    public void everySecuritySettingDefaultsToTheValueThatEnablesNothingAndSharesNothing() {
        // The safe side, spelled out per setting so a flipped default cannot hide in the table above:
        // no automatic enabling, no wide network policy, no data off the device or onto the lock screen.
        assertFalse(KeepADBPreferences.isKeepAliveEnabled(app));
        assertEquals(KeepADBTrustedNetwork.MODE_ALLOWLIST, KeepADBTrustedNetwork.getMode(app));
        assertTrue(KeepADBTrustedNetwork.isAllowlistMode(app));
        assertFalse(KeepADBTrustedNetwork.isTrustByNameEnabled(app));
        assertFalse(KeepADBTrustedNetwork.isSsidMatchingEnabled(app));
        assertFalse(KeepADBTrustedNetwork.hasActiveLegacyNameGrants(app));
        assertEquals(ProtectionLevel.MAXIMUM_SECURITY, KeepADBTrustedNetwork.getProtectionLevel(app));
        assertEquals(KeepADBPreferences.USB_WLAN_HANDOVER_MODE_OFF,
                KeepADBPreferences.getUsbWlanHandoverMode(app));
        assertFalse(KeepADBPreferences.isRegisterWebhookEnabled(app));
        assertNull(KeepADBPreferences.getRegisterWebhookUrl(app));
        assertFalse(KeepADBPreferences.isNotificationDetailsEnabled(app));
        assertFalse(KeepADBPreferences.isWifiApsFeatureEnabled(app));
        // ...and the lists a trust decision reads start empty.
        assertTrue(KeepADBTrustedNetwork.getEntries(app).isEmpty());
        assertTrue(KeepADBTrustedNetwork.getSsidEntries(app).isEmpty());
        assertTrue(KeepADBNetworkBlocklist.isEmpty(app));
        assertTrue(KeepADBUsbProfile.getProfiles(app).isEmpty());
    }

    @Test
    public void everySettingWithASecurityMeaningHasADefaultThatDiffersFromItsLooserValue() {
        // Guards the table itself: a SECURITY row must name a real alternative to the default.
        for (Setting setting : SETTINGS) {
            if (setting.kind != Kind.SECURITY) continue;
            assertTrue(setting.key + " needs a distinct stored alternative",
                    setting.otherValue != null && !setting.otherValue.equals(setting.defaultValue));
        }
    }

    @Test
    public void readingTheDefaultsPersistsNothingExceptTheOneTimeNetworkModeFlag() {
        for (Setting setting : SETTINGS) setting.reader.apply(app);
        KeepADBTrustedNetwork.evaluateCurrent(app);

        // A default is computed, never written back: otherwise a later change of a default would
        // already have moved every installation that merely opened the app (#760 persists the one
        // network policy deliberately, for exactly that reason).
        assertEquals(MODE_FLAG_KEYS, new TreeSet<>(prefs().getAll().keySet()));
        assertEquals("allowlist", prefs().getString("trusted_network_mode", null));
        assertTrue(prefs().getBoolean("trusted_network_mode_initialized", false));
    }

    // --- New installation: the acting paths behave on the defaults -----------------------------------

    @Test
    public void aNewInstallationNeverReEnablesOnAnUnknownOrUnreadableNetwork() {
        connectTo("Cafe", UNKNOWN_BSSID);
        assertEquals(Decision.UNKNOWN_NETWORK, KeepADBTrustedNetwork.evaluateCurrent(app));
        assertFalse(KeepADBTrustedNetwork.isCurrentNetworkTrusted(app));

        connectTo("Cafe", KeepADBNetworkIdentity.REDACTED_BSSID);
        assertEquals(Decision.IDENTITY_UNAVAILABLE, KeepADBTrustedNetwork.evaluateCurrent(app));
        assertFalse(KeepADBTrustedNetwork.isCurrentNetworkTrusted(app));

        // Control: the same device, same network, after the user trusts exactly that access point.
        connectTo("Cafe", UNKNOWN_BSSID);
        KeepADBTrustedNetwork.addBssid(app, UNKNOWN_BSSID, "Cafe");
        assertEquals(Decision.TRUSTED_ACCESS_POINT, KeepADBTrustedNetwork.evaluateCurrent(app));
        assertTrue(KeepADBTrustedNetwork.isCurrentNetworkTrusted(app));
    }

    @Test
    public void aNewInstallationDoesNotTrustASameNamedAccessPointOfATrustedOne() {
        // The comfort switch and the legacy name list are both off: a copied name is not trust.
        KeepADBTrustedNetwork.addBssid(app, "aa:bb:cc:dd:ee:01", "Home");
        connectTo("Home", UNKNOWN_BSSID);
        assertEquals(Decision.UNKNOWN_NETWORK, KeepADBTrustedNetwork.evaluateCurrent(app));
    }

    @Test
    public void aNewInstallationStartsNoServiceAtBootOrPackageReplacement() {
        new BootReceiver().onReceive(app, new Intent(Intent.ACTION_BOOT_COMPLETED));
        new BootReceiver().onReceive(app, new Intent(Intent.ACTION_MY_PACKAGE_REPLACED));

        assertNull("no Keep-Alive service without the user's opt-in",
                shadowOf(app).getNextStartedService());
        assertFalse(KeepADBService.shouldRun(app));
    }

    @Test
    public void theBootStartIsReachableOnlyThroughTheStoredKeepAliveOptIn() {
        // Control for the test above: the same receiver does start the service once the user opted in,
        // so "nothing started" on a new installation is the default's doing and not a dead test.
        prefs().edit().putBoolean("keep_alive_enabled", true).commit();

        new BootReceiver().onReceive(app, new Intent(Intent.ACTION_BOOT_COMPLETED));

        assertNotNull(shadowOf(app).getNextStartedService());
    }

    @Test
    public void aNewInstallationPlansNoUsbHandoverOnAConnectEdge() {
        String mode = KeepADBPreferences.getUsbWlanHandoverMode(app);

        assertFalse(KeepADBUsbHandover.onRawUsbBroadcastInternal(true, mode, false, false));

        // Control: the automatic mode, stored, does plan one on the same fresh edge.
        KeepADBUsbHandover.resetForTesting();
        assertTrue(KeepADBUsbHandover.onRawUsbBroadcastInternal(true, "automatic", false, false));
    }

    // --- Existing installation: stored values stay, in both directions --------------------------------

    @Test
    public void aStoredValueThatDiffersFromTheDefaultStaysWhateverDirectionItMovesIn() {
        // Looser than a security default (Keep-Alive on, all networks, webhook on, details on...) and
        // stricter than a convenience default (a dismissed card, the profile notification off, the
        // intent "off") are the same fact: the stored value wins and reading changes nothing.
        SharedPreferences.Editor editor = prefs().edit();
        for (Setting setting : SETTINGS) setting.store(editor, setting.otherValue);
        editor.commit();
        Map<String, Object> before = new TreeMap<>(prefs().getAll());

        for (Setting setting : SETTINGS) {
            assertEquals("stored value of " + setting.key, setting.otherValue, setting.reader.apply(app));
        }
        KeepADBTrustedNetwork.evaluateCurrent(app);

        Map<String, Object> after = new TreeMap<>(prefs().getAll());
        // The only write is the one-time initialized flag of an installation that never had one.
        Map<String, Object> expected = new TreeMap<>(before);
        expected.put("trusted_network_mode_initialized", true);
        assertEquals(expected, after);
    }

    @Test
    public void aValueStoredExplicitlyEqualToTheDefaultStaysStored() {
        SharedPreferences.Editor editor = prefs().edit();
        for (Setting setting : SETTINGS) setting.store(editor, setting.defaultValue);
        editor.commit();
        Map<String, Object> before = new TreeMap<>(prefs().getAll());

        for (Setting setting : SETTINGS) {
            assertEquals("stored default of " + setting.key, setting.defaultValue, setting.reader.apply(app));
        }

        Map<String, Object> expected = new TreeMap<>(before);
        expected.put("trusted_network_mode_initialized", true);
        assertEquals(expected, new TreeMap<>(prefs().getAll()));
    }

    @Test
    public void eachSettingKeepsItsStoredValueIndependentOfTheOthers() {
        // One at a time, so a default that leaks into a neighbor's read cannot cancel out in the
        // combined test above.
        for (Setting setting : SETTINGS) {
            prefs().edit().clear().commit();
            SharedPreferences.Editor editor = prefs().edit();
            setting.store(editor, setting.otherValue);
            editor.commit();

            for (Setting other : SETTINGS) {
                Object expected = other == setting ? other.otherValue : other.defaultValue;
                // The network policy default is persisted by its first read: a stored loosening of a
                // different key must not move it, and the stored mode itself must stay.
                assertEquals(other.key + " with only " + setting.key + " stored",
                        expected, other.reader.apply(app));
            }
        }
    }

    @Test
    public void anExistingAllNetworksPolicyStaysAndAnExistingAllowlistStaysAcrossTheFirstRead() {
        // No initialized flag: an installation as 1.9.28 left it, read for the first time by this version.
        prefs().edit().putString("trusted_network_mode", "all_wifi").commit();
        assertEquals("all_wifi", KeepADBTrustedNetwork.getMode(app));
        assertEquals(ProtectionLevel.LEGACY_ALL_WIFI, KeepADBTrustedNetwork.getProtectionLevel(app));
        assertEquals("all_wifi", prefs().getString("trusted_network_mode", null));

        prefs().edit().clear().putString("trusted_network_mode", "allowlist").commit();
        assertEquals("allowlist", KeepADBTrustedNetwork.getMode(app));
        assertEquals("allowlist", prefs().getString("trusted_network_mode", null));
    }

    @Test
    public void anExistingInstallationOnTheWidePolicyStillTrustsAnUnknownNetworkUnchanged() {
        // The behavior behind the stored value, not just the value: no silent tightening of the
        // decision either (the full migration matrix is KeepADBTrustMigrationTest).
        prefs().edit().putString("trusted_network_mode", "all_wifi").commit();
        connectTo("Cafe", UNKNOWN_BSSID);

        assertEquals(Decision.LEGACY_ALL_WIFI, KeepADBTrustedNetwork.evaluateCurrent(app));
        assertTrue(KeepADBTrustedNetwork.isCurrentNetworkTrusted(app));
    }

    @Test
    public void anExistingKeepAliveOptInAndUsbModeSurviveTheBootPath() {
        prefs().edit()
                .putBoolean("keep_alive_enabled", true)
                .putString("usb_wlan_handover_mode", "manual")
                .commit();

        new BootReceiver().onReceive(app, new Intent(Intent.ACTION_MY_PACKAGE_REPLACED));

        assertTrue(prefs().getBoolean("keep_alive_enabled", false));
        assertEquals("manual", prefs().getString("usb_wlan_handover_mode", null));
    }

    @Test
    public void aDamagedStoredHandoverModeReadsAsOffNotAsTheLooserMode() {
        prefs().edit().putString("usb_wlan_handover_mode", "garbage").commit();
        assertEquals("off", KeepADBPreferences.getUsbWlanHandoverMode(app));
    }

    // --- The audit stays complete ---------------------------------------------------------------------

    @Test
    public void everyPreferenceKeyInTheSourcesIsClassifiedByTheAudit() throws IOException {
        Set<String> classified = new HashSet<>(RECORD_KEYS);
        for (Setting setting : SETTINGS) classified.add(setting.key);

        Set<String> unclassified = new TreeSet<>();
        for (Map.Entry<String, String> constant : preferenceKeyConstants().entrySet()) {
            if (!classified.contains(constant.getValue())) {
                unclassified.add(constant.getKey() + " = \"" + constant.getValue() + "\"");
            }
        }
        // A new key needs a row in SETTINGS (a setting with a default) or in RECORD_KEYS (user data or
        // bookkeeping), and a line in docs/defaults.md. Deciding that is the audit.
        assertTrue("unclassified preference keys: " + unclassified, unclassified.isEmpty());
    }

    @Test
    public void everyAuditedKeyStillExistsInTheSources() throws IOException {
        // The other direction: a renamed or removed key must not leave a stale row that pins nothing.
        Set<String> inSources = new HashSet<>(preferenceKeyConstants().values());
        Set<String> stale = new TreeSet<>();
        for (Setting setting : SETTINGS) if (!inSources.contains(setting.key)) stale.add(setting.key);
        for (String key : RECORD_KEYS) if (!inSources.contains(key)) stale.add(key);
        assertTrue("audited keys without a constant in the sources: " + stale, stale.isEmpty());
    }

    @Test
    public void theDefaultsDocumentNamesEverySettingKey() throws IOException {
        String document = read("docs/defaults.md");
        List<String> missing = new ArrayList<>();
        for (Setting setting : SETTINGS) {
            if (!document.contains("`" + setting.key + "`")) missing.add(setting.key);
        }
        assertTrue("docs/defaults.md lacks: " + missing, missing.isEmpty());
    }

    // --- helpers --------------------------------------------------------------------------------------

    /** Constant name to key string for every preference key constant in the main sources. */
    private static Map<String, String> preferenceKeyConstants() throws IOException {
        Path root = projectRoot().resolve("app/src/main/java/de/hohnepeople/keepadb");
        Pattern constant = Pattern.compile(
                "static\\s+final\\s+String\\s+((?:KEY_[A-Z0-9_]+)|LOCATION_PERMISSION_REQUESTED"
                        + "|NOTIFICATION_PERMISSION_REQUESTED|PREF_NOTIFICATION_REQUESTED)"
                        + "\\s*=\\s*\"([^\"]+)\"\\s*;");
        Map<String, String> found = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(root)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java")).sorted()::iterator) {
                String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                Matcher matcher = constant.matcher(source);
                while (matcher.find()) {
                    found.put(file.getFileName() + "#" + matcher.group(1), matcher.group(2));
                }
            }
        }
        assertFalse("found no preference key constants; the scan root is wrong", found.isEmpty());
        return found;
    }

    private static String read(String relativePath) throws IOException {
        return new String(Files.readAllBytes(projectRoot().resolve(relativePath)), StandardCharsets.UTF_8);
    }

    private static Path projectRoot() {
        Path directory = Paths.get("").toAbsolutePath();
        while (directory != null && !Files.exists(directory.resolve("settings.gradle"))) {
            directory = directory.getParent();
        }
        if (directory == null) throw new IllegalStateException("Could not locate project root");
        return directory;
    }

    private void connectTo(String ssid, String bssid) {
        WifiManager wifiManager = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = ShadowWifiInfo.newInstance();
        shadowOf(info).setSSID(ssid);
        shadowOf(info).setBSSID(bssid);
        shadowOf(wifiManager).setConnectionInfo(info);
    }

    private SharedPreferences prefs() {
        return app.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE);
    }
}
