package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/** #1001: export and import of the settings file. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBSettingsBackupTest {
    private static final String URL = "https://hooks.example.test/register/s20";
    private Context context;
    private SharedPreferences prefs;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        prefs = context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
    }

    /** A device with something set in every exported area. */
    private void configureDevice() {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALLOWLIST);
        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:01", "Home", "Home");
        KeepADBTrustedNetwork.Entry office =
                KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:02", "Office", "Office");
        KeepADBTrustedNetwork.setCustomName(context, office.id, "Desk");
        KeepADBTrustedNetwork.setTrustByNameEnabled(context, true);
        KeepADBTrustedNetwork.setSecurityCheckEnabled(context, true);
        KeepADBNetworkBlocklist.blockBssid(context, "11:22:33:44:55:66", "Cafe", "Corner cafe");
        KeepADBNetworkBlocklist.blockSsid(context, "Airport");
        KeepADBPreferences.setUntrustedShutdownEnabled(context, true);
        KeepADBPreferences.setOpenNetworkShutdownEnabled(context, true);
        KeepADBPreferences.setNotificationPrivacyEnabled(context, true);
        KeepADBPreferences.setLockScreenPrivacyEnabled(context, true);
        KeepADBPreferences.setKeepDisplayOnEnabled(context, true);
        KeepADBPreferences.setNotificationHidden(context, true);
        KeepADBPreferences.setPeriodicWebhookReportEnabled(context, true);
        KeepADBPreferences.setPeriodicWebhookInterval(context, KeepADBPeriodicReport.Interval.MIN_30);
        KeepADBPreferences.setRegisterWebhookUrl(context, URL);
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        // Not settings: must never travel in the file.
        KeepADBPreferences.setKeepAliveEnabled(context, true);
        prefs.edit()
                .putString("automation_token", "secret-token-value")
                .putLong("service_last_heartbeat", 123L)
                .putString("force_state", "{}")
                .commit();
    }

    private Map<String, Object> managed() {
        Map<String, Object> out = new HashMap<>();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            if (KeepADBSettingsBackup.ruleFor(e.getKey()) != null) out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    private String exportNow(boolean withUrl) throws Exception {
        return KeepADBSettingsBackup.export(context, withUrl, 1_000L, "test");
    }

    @Test
    public void roundTripRestoresEveryExportedSetting() throws Exception {
        configureDevice();
        String file = exportNow(true);
        Map<String, Object> before = managed();

        prefs.edit().clear().commit(); // the reinstall
        KeepADBSettingsBackup.apply(context, KeepADBSettingsBackup.parse(file));

        assertEquals(before, managed());
        assertEquals(2, KeepADBTrustedNetwork.getEntries(context).size());
        assertTrue(KeepADBNetworkBlocklist.isBssidBlocked(context, "11:22:33:44:55:66"));
        assertEquals("Corner cafe",
                KeepADBNetworkBlocklist.getBlockedAccessPoint(context, "11:22:33:44:55:66").customName);
        assertTrue(KeepADBNetworkBlocklist.isSsidBlocked(context, "Airport"));
        assertTrue(KeepADBPreferences.isUntrustedShutdownEnabled(context));
        assertTrue(KeepADBPreferences.isNotificationPrivacyEnabled(context));
        assertEquals(KeepADBPeriodicReport.Interval.MIN_30,
                KeepADBPreferences.getPeriodicWebhookInterval(context));
        assertEquals(URL, KeepADBPreferences.getRegisterWebhookUrl(context));
    }

    @Test
    public void exportNeverContainsSecretsOrRunTimeState() throws Exception {
        configureDevice();
        String file = exportNow(true);
        assertFalse(file.contains("automation_token"));
        assertFalse(file.contains("secret-token-value"));
        assertFalse(file.contains("token"));
        assertFalse(file.contains("keep_alive_enabled"));
        assertFalse(file.contains("register_webhook_enabled"));
        assertFalse(file.contains("service_last_heartbeat"));
        assertFalse(file.contains("force_state"));
        for (KeepADBSettingsBackup.Rule rule : KeepADBSettingsBackup.RULES) {
            assertFalse(rule.key.pattern(), rule.key.pattern().toLowerCase().contains("token"));
        }
    }

    @Test
    public void theTimerDefaultTravelsButARunningTimerDoesNot() throws Exception {
        // #1000: the preselected duration is a setting; the running timer is run-time state.
        KeepADBPreferences.setTimerDefaultDuration(context, KeepADBTimer.Duration.HOURS_4);
        prefs.edit().putString(KeepADBTimer.KEY_STATE, "1;3600000;1800000000000;5;41").commit();
        String file = exportNow(false);
        assertTrue(file.contains("timer_default_duration"));
        assertFalse(file.contains(KeepADBTimer.KEY_STATE));

        prefs.edit().clear().commit();
        KeepADBSettingsBackup.apply(context, KeepADBSettingsBackup.parse(file));
        assertEquals(KeepADBTimer.Duration.HOURS_4, KeepADBPreferences.getTimerDefaultDuration(context));
        assertFalse(prefs.contains(KeepADBTimer.KEY_STATE));
    }

    @Test
    public void exportCanLeaveTheWebhookUrlOut() throws Exception {
        configureDevice();
        String file = exportNow(false);
        assertFalse(file.contains("hooks.example.test"));
        assertFalse(KeepADBSettingsBackup.parse(file).hasWebhookUrl());
    }

    @Test
    public void importNeverTurnsKeepAliveOrWebhookSyncOn() throws Exception {
        configureDevice();
        String file = exportNow(true);
        prefs.edit().clear().commit();
        KeepADBSettingsBackup.ApplyResult result =
                KeepADBSettingsBackup.apply(context, KeepADBSettingsBackup.parse(file));
        assertTrue(result.webhookUrlChanged);
        assertFalse(KeepADBPreferences.isKeepAliveEnabled(context));
        assertFalse(KeepADBPreferences.isRegisterWebhookEnabled(context));
    }

    @Test
    public void networkNamesWithControlCharactersSurviveTheRoundTrip() throws Exception {
        // The app accepts such a name, so it must neither block the export nor the import.
        KeepADBNetworkBlocklist.blockSsid(context, "Cafe\nGuest");
        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:03", "Tab\tName", "Tab\tName");
        String file = exportNow(false);
        prefs.edit().clear().commit();
        assertTrue(KeepADBSettingsBackup.apply(context, KeepADBSettingsBackup.parse(file)).saved);
        assertTrue(KeepADBNetworkBlocklist.isSsidBlocked(context, "Cafe\nGuest"));
        assertEquals(1, KeepADBTrustedNetwork.getEntries(context).size());
    }

    @Test
    public void differentUrlIsKeptOutWhileWebhookSyncIsOn() throws Exception {
        configureDevice();
        String file = exportNow(true);
        String other = "https://other.example.test/register/s20";
        KeepADBPreferences.setRegisterWebhookUrl(context, other);
        KeepADBPreferences.setRegisterWebhookEnabled(context, true);
        KeepADBSettingsBackup.ApplyResult result =
                KeepADBSettingsBackup.apply(context, KeepADBSettingsBackup.parse(file));
        assertTrue(result.saved);
        assertTrue(result.webhookUrlKept);
        assertFalse(result.webhookUrlChanged);
        assertEquals(other, KeepADBPreferences.getRegisterWebhookUrl(context));
        assertTrue(KeepADBPreferences.isRegisterWebhookEnabled(context));
        // Everything else from the file still applies.
        assertEquals(2, KeepADBTrustedNetwork.getEntries(context).size());
    }

    @Test
    public void differentUrlIsKeptOutWhileAWithdrawalIsOutstanding() throws Exception {
        configureDevice();
        String file = exportNow(true);
        String old = "https://other.example.test/register/s20";
        KeepADBPreferences.setRegisterWebhookUrl(context, old);
        KeepADBPreferences.setRegisterWebhookEnabled(context, false);
        // The last report went to the stored URL and was never withdrawn.
        WebhookReportPrefs.setWebhookLastReportedUrl(context, old);
        KeepADBSettingsBackup.ApplyResult result =
                KeepADBSettingsBackup.apply(context, KeepADBSettingsBackup.parse(file));
        assertTrue(result.webhookUrlKept);
        assertEquals(old, KeepADBPreferences.getRegisterWebhookUrl(context));
    }

    @Test
    public void differentUrlReplacesAnIdleOneAndStaysOff() throws Exception {
        configureDevice();
        String file = exportNow(true);
        KeepADBPreferences.setRegisterWebhookUrl(context, "https://other.example.test/register/s20");
        KeepADBPreferences.setRegisterWebhookEnabled(context, false);
        KeepADBSettingsBackup.ApplyResult result =
                KeepADBSettingsBackup.apply(context, KeepADBSettingsBackup.parse(file));
        assertTrue(result.webhookUrlChanged);
        assertFalse(result.webhookUrlKept);
        assertEquals(URL, KeepADBPreferences.getRegisterWebhookUrl(context));
        assertFalse(KeepADBPreferences.isRegisterWebhookEnabled(context));
    }

    @Test
    public void fileWithoutUrlKeepsTheStoredWebhook() throws Exception {
        configureDevice();
        String file = exportNow(false);
        KeepADBSettingsBackup.ApplyResult result =
                KeepADBSettingsBackup.apply(context, KeepADBSettingsBackup.parse(file));
        assertFalse(result.webhookUrlChanged);
        assertEquals(URL, KeepADBPreferences.getRegisterWebhookUrl(context));
        assertTrue(KeepADBPreferences.isRegisterWebhookEnabled(context));
    }

    @Test
    public void sameUrlKeepsWebhookSyncOn() throws Exception {
        configureDevice();
        String file = exportNow(true);
        KeepADBSettingsBackup.ApplyResult result =
                KeepADBSettingsBackup.apply(context, KeepADBSettingsBackup.parse(file));
        assertFalse(result.webhookUrlChanged);
        assertTrue(KeepADBPreferences.isRegisterWebhookEnabled(context));
    }

    @Test
    public void importReplacesInsteadOfMerging() throws Exception {
        configureDevice();
        String file = exportNow(true);
        KeepADBTrustedNetwork.addBssid(context, "aa:bb:cc:dd:ee:99", "Extra", "Extra");
        KeepADBNetworkBlocklist.blockSsid(context, "Hotel");
        KeepADBSettingsBackup.apply(context, KeepADBSettingsBackup.parse(file));
        assertEquals(2, KeepADBTrustedNetwork.getEntries(context).size());
        assertFalse(KeepADBNetworkBlocklist.isSsidBlocked(context, "Hotel"));
        // State outside the file is left alone.
        assertTrue(KeepADBPreferences.isKeepAliveEnabled(context));
        assertEquals("secret-token-value", prefs.getString("automation_token", null));
    }

    @Test
    public void unknownKeysAreIgnored() throws Exception {
        configureDevice();
        JSONObject root = new JSONObject(exportNow(true));
        root.getJSONObject("settings").put("some_future_setting", true);
        root.getJSONObject("settings").put("keep_alive_enabled", true);
        root.put("extra", "x");
        KeepADBSettingsBackup.Snapshot snapshot = KeepADBSettingsBackup.parse(root.toString());
        assertFalse(snapshot.values.containsKey("some_future_setting"));
        assertFalse(snapshot.values.containsKey("keep_alive_enabled"));
    }

    @Test
    public void foreignFilesAreRejected() {
        assertRejected("not json at all", KeepADBSettingsBackup.Error.NOT_A_SETTINGS_FILE);
        assertRejected("[]", KeepADBSettingsBackup.Error.NOT_A_SETTINGS_FILE);
        assertRejected("{\"format\":\"other-app\",\"version\":1,\"settings\":{}}",
                KeepADBSettingsBackup.Error.NOT_A_SETTINGS_FILE);
        assertRejected("{\"format\":\"keepadb-settings\",\"version\":1}",
                KeepADBSettingsBackup.Error.NOT_A_SETTINGS_FILE);
        assertRejected("{\"format\":\"keepadb-settings\",\"version\":\"1\",\"settings\":{}}",
                KeepADBSettingsBackup.Error.NOT_A_SETTINGS_FILE);
    }

    @Test
    public void otherVersionsAreRejected() {
        assertRejected("{\"format\":\"keepadb-settings\",\"version\":2,\"settings\":{}}",
                KeepADBSettingsBackup.Error.UNSUPPORTED_VERSION);
        assertRejected("{\"format\":\"keepadb-settings\",\"version\":0,\"settings\":{}}",
                KeepADBSettingsBackup.Error.UNSUPPORTED_VERSION);
    }

    @Test
    public void malformedValuesAreRejected() {
        assertInvalid("\"untrusted_shutdown_enabled\":\"yes\"");
        assertInvalid("\"trusted_network_mode\":\"everything\"");
        assertInvalid("\"register_webhook_periodic_interval\":\"7m\"");
        assertInvalid("\"timer_default_duration\":\"99d\"");
        assertInvalid("\"register_webhook_url\":\"ftp://example.test\"");
        assertInvalid("\"register_webhook_url\":\"not a url\"");
        assertInvalid("\"trusted_network_ids\":\"1,x\"");
        assertInvalid("\"trusted_network_next_id\":-1");
        assertInvalid("\"blocked_ssids\":\"Airport\"");
        assertInvalid("\"blocked_ssids\":[1]");
        assertInvalid("\"blocked_ssids\":[\"\"]");
        // The blocklist looks addresses up in lower case; another form would never match.
        assertInvalid("\"blocked_bssids\":[\"AA:BB:CC:DD:EE:FF\"]");
        assertInvalid("\"blocked_bssids\":[\" aa:bb:cc:dd:ee:ff \"]");
        assertInvalid("\"blocked_ssids\":[\"<unknown ssid>\"]");
    }

    @Test
    public void inconsistentListsAreRejected() {
        // An entry without its address.
        assertInvalid("\"trusted_network_ids\":\"1\",\"trusted_network_next_id\":2");
        // A field of an entry that is not listed.
        assertInvalid("\"trusted_network_ids\":\"\",\"trusted_network_5_bssid\":\"aa:bb:cc:dd:ee:05\"");
        // The next free ID is not above the listed ones.
        assertInvalid("\"trusted_network_ids\":\"3\",\"trusted_network_next_id\":3,"
                + "\"trusted_network_3_bssid\":\"aa:bb:cc:dd:ee:03\"");
        // A name for an access point that is not blocked.
        assertInvalid("\"blocked_bssids\":[],\"blocked_bssid_name_aa:bb:cc:dd:ee:07\":\"X\"");
    }

    @Test
    public void rejectedFileChangesNothing() throws Exception {
        configureDevice();
        Map<String, ?> before = new HashMap<>(prefs.getAll());
        JSONObject root = new JSONObject(exportNow(true));
        root.getJSONObject("settings").put("trusted_network_mode", "everything");
        try {
            KeepADBSettingsBackup.apply(context, KeepADBSettingsBackup.parse(root.toString()));
            fail("an invalid file must be rejected");
        } catch (KeepADBSettingsBackup.ImportException expected) {
            assertEquals(KeepADBSettingsBackup.Error.INVALID_VALUE, expected.error);
        }
        assertEquals(before, prefs.getAll());
    }

    @Test
    public void oversizedFileIsRejected() {
        StringBuilder big = new StringBuilder("{\"format\":\"keepadb-settings\",\"x\":\"");
        while (big.length() <= KeepADBSettingsBackup.MAX_FILE_BYTES) big.append("aaaaaaaaaa");
        big.append("\"}");
        assertRejected(big.toString(), KeepADBSettingsBackup.Error.TOO_LARGE);
    }

    @Test
    public void exportSkipsLeftoverNamesOfLiftedBlocks() throws Exception {
        configureDevice();
        // What an older app version could leave behind after lifting a block.
        prefs.edit().putString("blocked_bssid_name_77:77:77:77:77:77", "Old").commit();
        String file = exportNow(true);
        assertFalse(file.contains("77:77:77:77:77:77"));
        KeepADBSettingsBackup.parse(file); // still a valid file
    }

    @Test
    public void emptyDeviceExportsAValidFile() throws Exception {
        String file = exportNow(true);
        KeepADBSettingsBackup.Snapshot snapshot = KeepADBSettingsBackup.parse(file);
        assertEquals(0, snapshot.trustedAccessPointCount());
        assertEquals(0, snapshot.blockedCount());
        assertEquals(KeepADBTrustedNetwork.MODE_ALLOWLIST,
                snapshot.values.get("trusted_network_mode"));
    }

    @Test
    public void importedLevelIsNotRewrittenByTheMigrations() throws Exception {
        KeepADBTrustedNetwork.setMode(context, KeepADBTrustedNetwork.MODE_ALL_WIFI);
        KeepADBPreferences.setWidgetPrivacyEnabled(context, true);
        String file = exportNow(false);
        prefs.edit().clear().commit();
        KeepADBSettingsBackup.apply(context, KeepADBSettingsBackup.parse(file));
        assertEquals(KeepADBTrustedNetwork.MODE_ALL_WIFI, KeepADBTrustedNetwork.getMode(context));
        assertTrue(KeepADBPreferences.isWidgetPrivacyEnabled(context));
    }

    @Test
    public void snapshotCountsNetworks() throws Exception {
        configureDevice();
        KeepADBSettingsBackup.Snapshot snapshot = KeepADBSettingsBackup.parse(exportNow(true));
        assertEquals(2, snapshot.trustedAccessPointCount());
        assertEquals(2, snapshot.blockedCount());
        assertEquals(new HashSet<>(Arrays.asList("Airport")), snapshot.values.get("blocked_ssids"));
        assertNull(snapshot.values.get("keep_alive_enabled"));
    }

    private static void assertInvalid(String settingsBody) {
        assertRejected("{\"format\":\"keepadb-settings\",\"version\":1,\"settings\":{"
                + settingsBody + "}}", KeepADBSettingsBackup.Error.INVALID_VALUE);
    }

    private static void assertRejected(String content, KeepADBSettingsBackup.Error expected) {
        try {
            KeepADBSettingsBackup.parse(content);
            fail("accepted: " + content.substring(0, Math.min(80, content.length())));
        } catch (KeepADBSettingsBackup.ImportException e) {
            assertEquals(content.substring(0, Math.min(80, content.length())), expected, e.error);
        }
    }
}
