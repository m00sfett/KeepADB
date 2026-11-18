package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** #998: the automation interface, its security decisions and its contract. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBAutomationTest {

    private Context context;
    private FakeActions fake;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        fake = new FakeActions();
        KeepADBAutomation.setActionsForTesting(fake);
    }

    @After
    public void tearDown() {
        KeepADBAutomation.setActionsForTesting(null);
    }

    private String enable() {
        KeepADBAutomation.enable(context);
        return KeepADBPreferences.getAutomationToken(context);
    }

    // ---- Opt-in -----------------------------------------------------------------------------

    @Test
    public void interfaceIsOffByDefaultAndIgnoresEveryRequest() {
        assertFalse(KeepADBAutomation.isEnabled(context));
        for (String action : allActions()) {
            assertNull(action, KeepADBAutomation.process(context, action, "anything"));
        }
        assertTrue("nothing may be switched while the interface is off", fake.calls.isEmpty());
        assertEquals("ignored requests are no rejections", 0,
                KeepADBPreferences.getAutomationRejectedCount(context));
    }

    @Test
    public void disabledInterfaceIgnoresEvenAValidToken() {
        String token = enable();
        KeepADBAutomation.disable(context);
        assertNull(KeepADBAutomation.process(context, KeepADBAutomation.ACTION_WIFI_ADB_ON, token));
        assertTrue(fake.calls.isEmpty());
    }

    @Test
    public void unknownActionIsIgnored() {
        String token = enable();
        assertNull(KeepADBAutomation.process(context, "de.hohnepeople.keepadb.action.FOO", token));
        assertNull(KeepADBAutomation.process(context, KeepADBAutomation.ACTION_RESULT, token));
        assertNull(KeepADBAutomation.process(context, null, token));
        assertTrue(fake.calls.isEmpty());
    }

    // ---- Token ------------------------------------------------------------------------------

    @Test
    public void onWithoutOrWithWrongTokenIsRejectedAndCounted() {
        String token = enable();
        KeepADBAutomation.Response missing =
                KeepADBAutomation.process(context, KeepADBAutomation.ACTION_WIFI_ADB_ON, null);
        KeepADBAutomation.Response wrong =
                KeepADBAutomation.process(context, KeepADBAutomation.ACTION_WIFI_ADB_ON, token + "x");
        assertEquals(KeepADBAutomation.Result.REJECTED_TOKEN, missing.result);
        assertEquals(KeepADBAutomation.Result.REJECTED_TOKEN, wrong.result);
        assertFalse(fake.calls.contains("wifi:true"));
        assertEquals(2, KeepADBPreferences.getAutomationRejectedCount(context));
        assertTrue(KeepADBPreferences.getAutomationLastRejectedAt(context) > 0);
    }

    @Test
    public void toggleAndKeepAliveOnNeedTheToken() {
        enable();
        fake.wifiOn = true; // a toggle would switch off, it still needs the token
        assertEquals(KeepADBAutomation.Result.REJECTED_TOKEN, KeepADBAutomation.process(context,
                KeepADBAutomation.ACTION_WIFI_ADB_TOGGLE, null).result);
        assertEquals(KeepADBAutomation.Result.REJECTED_TOKEN, KeepADBAutomation.process(context,
                KeepADBAutomation.ACTION_KEEP_ALIVE_ON, null).result);
        assertTrue(fake.calls.isEmpty());
    }

    @Test
    public void offWorksWithoutToken() {
        enable();
        fake.wifiOn = true;
        KeepADBAutomation.Response wifiOff =
                KeepADBAutomation.process(context, KeepADBAutomation.ACTION_WIFI_ADB_OFF, null);
        KeepADBAutomation.Response keepAliveOff =
                KeepADBAutomation.process(context, KeepADBAutomation.ACTION_KEEP_ALIVE_OFF, null);
        assertEquals(KeepADBAutomation.Result.OK, wifiOff.result);
        assertEquals(KeepADBAutomation.Result.OK, keepAliveOff.result);
        assertTrue(fake.calls.contains("wifi:false"));
        assertTrue(fake.calls.contains("keepalive:false"));
        assertNull("an off without token never carries a guard", fake.lastGuard);
        assertEquals(0, KeepADBPreferences.getAutomationRejectedCount(context));
    }

    @Test
    public void onWithTokenOnAnAllowedNetworkSwitchesOnWithAGuard() {
        String token = enable();
        KeepADBAutomation.Response response =
                KeepADBAutomation.process(context, KeepADBAutomation.ACTION_WIFI_ADB_ON, token);
        assertEquals(KeepADBAutomation.Result.OK, response.result);
        assertTrue(fake.calls.contains("wifi:true"));
        assertNotNull("the enable is re-checked inside the toggle lock", fake.lastGuard);
        assertTrue(fake.lastGuard.stillApplies(context));
        fake.networkAllowed = false;
        assertFalse("a network change before the write cancels it",
                fake.lastGuard.stillApplies(context));
        fake.networkAllowed = true;
        KeepADBAutomation.disable(context);
        assertFalse("switching the interface off before the write cancels it",
                fake.lastGuard.stillApplies(context));
    }

    @Test
    public void keepAliveOnWithTokenOnlySetsKeepAlive() {
        String token = enable();
        assertEquals(KeepADBAutomation.Result.OK, KeepADBAutomation.process(context,
                KeepADBAutomation.ACTION_KEEP_ALIVE_ON, token).result);
        assertEquals(1, fake.calls.size());
        assertEquals("keepalive:true", fake.calls.get(0));
    }

    @Test
    public void toggleWithTokenFollowsTheCurrentState() {
        String token = enable();
        fake.wifiOn = false;
        KeepADBAutomation.process(context, KeepADBAutomation.ACTION_WIFI_ADB_TOGGLE, token);
        fake.wifiOn = true;
        KeepADBAutomation.process(context, KeepADBAutomation.ACTION_WIFI_ADB_TOGGLE, token);
        assertEquals(List.of("wifi:true", "wifi:false"), fake.calls);
    }

    @Test
    public void regeneratingInvalidatesTheOldTokenAndResetsTheCounter() {
        String old = enable();
        KeepADBAutomation.process(context, KeepADBAutomation.ACTION_WIFI_ADB_ON, "wrong");
        String fresh = KeepADBAutomation.regenerateToken(context);
        assertNotEquals(old, fresh);
        assertEquals(0, KeepADBPreferences.getAutomationRejectedCount(context));
        assertEquals(KeepADBAutomation.Result.REJECTED_TOKEN, KeepADBAutomation.process(context,
                KeepADBAutomation.ACTION_WIFI_ADB_ON, old).result);
        assertEquals(KeepADBAutomation.Result.OK, KeepADBAutomation.process(context,
                KeepADBAutomation.ACTION_WIFI_ADB_ON, fresh).result);
    }

    @Test
    public void tokenIsGeneratedOnFirstEnableAndKeptWhenSwitchedOff() {
        assertNull(KeepADBPreferences.getAutomationToken(context));
        String first = enable();
        assertNotNull(first);
        KeepADBAutomation.disable(context);
        assertEquals(first, KeepADBPreferences.getAutomationToken(context));
        assertEquals("re-enabling keeps existing automations working", first, enable());
    }

    @Test
    public void tokenIs32RandomBytesInBase64Url() {
        String token = KeepADBAutomation.generateToken(new SecureRandom());
        assertEquals(43, token.length());
        assertTrue(token, token.matches("[A-Za-z0-9_-]{43}"));
        assertNotEquals(token, KeepADBAutomation.generateToken(new SecureRandom()));
    }

    @Test
    public void tokenComparison() {
        assertTrue(KeepADBAutomation.tokenMatches("abc", "abc"));
        assertFalse(KeepADBAutomation.tokenMatches("abc", "abd"));
        assertFalse(KeepADBAutomation.tokenMatches("abc", "ab"));
        assertFalse(KeepADBAutomation.tokenMatches("abc", null));
        assertFalse(KeepADBAutomation.tokenMatches(null, null));
        assertFalse("an empty stored token never matches", KeepADBAutomation.tokenMatches("", ""));
    }

    // ---- Network protection -----------------------------------------------------------------

    @Test
    public void onIsRefusedOnANetworkThatDoesNotAllowAnAutomaticEnable() {
        String token = enable();
        fake.networkAllowed = false;
        assertEquals(KeepADBAutomation.Result.REJECTED_NETWORK, KeepADBAutomation.process(context,
                KeepADBAutomation.ACTION_WIFI_ADB_ON, token).result);
        fake.wifiOn = false;
        assertEquals(KeepADBAutomation.Result.REJECTED_NETWORK, KeepADBAutomation.process(context,
                KeepADBAutomation.ACTION_WIFI_ADB_TOGGLE, token).result);
        assertFalse(fake.calls.contains("wifi:true"));
        assertEquals("a network refusal is not a token rejection", 0,
                KeepADBPreferences.getAutomationRejectedCount(context));
    }

    @Test
    public void offIsAllowedOnAnyNetwork() {
        enable();
        fake.networkAllowed = false;
        assertEquals(KeepADBAutomation.Result.OK, KeepADBAutomation.process(context,
                KeepADBAutomation.ACTION_WIFI_ADB_OFF, null).result);
    }

    @Test
    public void toggleResultsMapToTheContract() {
        assertEquals(KeepADBAutomation.Result.OK,
                KeepADBAutomation.mapToggleResult(KeepADB.ToggleResult.APPLIED));
        assertEquals(KeepADBAutomation.Result.OK,
                KeepADBAutomation.mapToggleResult(KeepADB.ToggleResult.SCHEDULED));
        assertEquals(KeepADBAutomation.Result.FAILED_PERMISSION,
                KeepADBAutomation.mapToggleResult(KeepADB.ToggleResult.PERMISSION_MISSING));
        assertEquals(KeepADBAutomation.Result.FAILED_PERMISSION,
                KeepADBAutomation.mapToggleResult(KeepADB.ToggleResult.SECURITY_EXCEPTION));
        assertEquals(KeepADBAutomation.Result.REJECTED_NETWORK,
                KeepADBAutomation.mapToggleResult(KeepADB.ToggleResult.GUARD_ABORTED));
        assertEquals(KeepADBAutomation.Result.FAILED,
                KeepADBAutomation.mapToggleResult(KeepADB.ToggleResult.WRITE_REJECTED));
    }

    @Test
    public void automationIsAManualSource() {
        assertTrue(KeepADB.isManualSource(KeepADB.SOURCE_AUTOMATION));
    }

    // ---- Status and reply -------------------------------------------------------------------

    @Test
    public void statusWithoutTokenHasNoEndpoint() {
        enable();
        fake.wifiOn = true;
        KeepADBAutomation.Response response =
                KeepADBAutomation.process(context, KeepADBAutomation.ACTION_STATUS, null);
        assertEquals(KeepADBAutomation.Result.OK, response.result);
        assertTrue(response.wifiAdb);
        assertFalse(response.hasEndpoint());
        assertTrue(fake.calls.isEmpty());
    }

    @Test
    public void statusWithTokenCarriesTheEndpoint() {
        String token = enable();
        fake.wifiOn = true;
        KeepADBAutomation.Response response =
                KeepADBAutomation.process(context, KeepADBAutomation.ACTION_STATUS, token);
        assertEquals("192.168.1.20", response.host);
        assertEquals(37000, response.port);
    }

    @Test
    public void statusWithWrongTokenIsRejectedWithoutEndpoint() {
        String token = enable();
        fake.wifiOn = true;
        KeepADBAutomation.Response response =
                KeepADBAutomation.process(context, KeepADBAutomation.ACTION_STATUS, token + "x");
        assertEquals(KeepADBAutomation.Result.REJECTED_TOKEN, response.result);
        assertFalse(response.hasEndpoint());
        assertEquals(1, KeepADBPreferences.getAutomationRejectedCount(context));
    }

    @Test
    public void replyIsExplicitAndCarriesNoToken() {
        KeepADBAutomation.Response withEndpoint = new KeepADBAutomation.Response("STATUS",
                KeepADBAutomation.Result.OK, true, false, "10.0.0.2", 40000);
        Intent reply = KeepADBAutomation.replyIntent(withEndpoint, "net.dinglisch.android.taskerm");
        assertEquals(KeepADBAutomation.ACTION_RESULT, reply.getAction());
        assertEquals("net.dinglisch.android.taskerm", reply.getPackage());
        assertEquals("STATUS", reply.getStringExtra(KeepADBAutomation.EXTRA_REQUEST));
        assertEquals("ok", reply.getStringExtra(KeepADBAutomation.EXTRA_RESULT));
        assertTrue(reply.getBooleanExtra(KeepADBAutomation.EXTRA_WIFI_ADB, false));
        assertEquals("10.0.0.2", reply.getStringExtra(KeepADBAutomation.EXTRA_IP));
        assertEquals(40000, reply.getIntExtra(KeepADBAutomation.EXTRA_PORT, 0));
        assertFalse(reply.hasExtra(KeepADBAutomation.EXTRA_TOKEN));

        Intent plain = KeepADBAutomation.replyIntent(new KeepADBAutomation.Response("STATUS",
                KeepADBAutomation.Result.OK, false, false, null, 0), "com.example.app");
        assertFalse(plain.hasExtra(KeepADBAutomation.EXTRA_IP));
        assertFalse(plain.hasExtra(KeepADBAutomation.EXTRA_PORT));
    }

    @Test
    public void replyPackageValidation() {
        assertTrue(KeepADBAutomation.isValidReplyPackage("net.dinglisch.android.taskerm"));
        assertTrue(KeepADBAutomation.isValidReplyPackage("com.arlosoft.macrodroid"));
        assertFalse(KeepADBAutomation.isValidReplyPackage("tasker"));
        assertFalse(KeepADBAutomation.isValidReplyPackage("com.example/.Receiver"));
        assertFalse(KeepADBAutomation.isValidReplyPackage(""));
        assertFalse(KeepADBAutomation.isValidReplyPackage(null));
    }

    // ---- The token never leaves the app -----------------------------------------------------

    @Test
    public void tokenIsAbsentFromTheDiagnosticsExport() {
        String token = enable();
        KeepADBAutomation.process(context, KeepADBAutomation.ACTION_WIFI_ADB_ON, token);
        KeepADBAutomation.process(context, KeepADBAutomation.ACTION_WIFI_ADB_ON, token + "x");
        KeepADBAutomation.process(context, KeepADBAutomation.ACTION_STATUS, token);
        String export = KeepADBDiagnostics.export(context);
        assertFalse(export.contains(token));
        assertFalse(export.contains(token.substring(0, 12)));
    }

    @Test
    public void automationStateIsNotPartOfTheSettingsBackup() throws Exception {
        enable();
        KeepADBPreferences.recordAutomationRejection(context, 1L);
        SharedPreferences prefs = context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE);
        Map<String, Object> stored = new HashMap<>(prefs.getAll());
        String export = KeepADBSettingsBackup.export(stored, true, 0L, "test");
        assertFalse(export.contains(KeepADBPreferences.KEY_AUTOMATION_ENABLED));
        assertFalse(export.contains(KeepADBPreferences.KEY_AUTOMATION_TOKEN));
        assertFalse(export.contains(KeepADBPreferences.getAutomationToken(context)));
        assertFalse(export.contains("automation"));
    }

    @Test
    public void onlyTheAutomationClassesAndThePreferencesReadTheToken() throws IOException {
        List<String> readers = new ArrayList<>();
        Path sources = root().resolve("app/src/main/java/de/hohnepeople/keepadb");
        try (Stream<Path> files = Files.walk(sources)) {
            for (Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                if (text.contains("getAutomationToken") || text.contains("KEY_AUTOMATION_TOKEN")) {
                    readers.add(file.getFileName().toString());
                }
            }
        }
        readers.sort(null);
        assertEquals("the token must not reach logs, diagnostics, webhooks or exports",
                List.of("KeepADBAutomation.java", "KeepADBAutomationSection.java",
                        "KeepADBPreferences.java"), readers);
    }

    // ---- Manifest contract ------------------------------------------------------------------

    @Test
    public void automationReceiverIsTheOnlyExportedReceiverAndOwnsExactlyTheContractActions()
            throws IOException {
        String manifest = new String(Files.readAllBytes(
                root().resolve("app/src/main/AndroidManifest.xml")), StandardCharsets.UTF_8);
        Matcher receivers = Pattern.compile("<receiver[\\s\\S]*?</receiver>").matcher(manifest);
        List<String> exported = new ArrayList<>();
        String automation = null;
        while (receivers.find()) {
            String receiver = receivers.group();
            if (receiver.contains("android:exported=\"true\"")) exported.add(receiver);
            if (receiver.contains("android:name=\".KeepADBAutomationReceiver\"")) automation = receiver;
        }
        assertEquals("exactly one exported receiver", 1, exported.size());
        assertNotNull(automation);
        assertEquals(automation, exported.get(0));
        assertFalse("no permission would make it unreachable for Tasker",
                automation.contains("android:permission"));
        Matcher actions = Pattern.compile("<action android:name=\"([^\"]+)\"").matcher(automation);
        List<String> declared = new ArrayList<>();
        while (actions.find()) declared.add(actions.group(1));
        List<String> expected = new ArrayList<>(allActions());
        declared.sort(null);
        expected.sort(null);
        assertEquals(expected, declared);
    }

    private static List<String> allActions() {
        return List.of(KeepADBAutomation.ACTION_WIFI_ADB_ON, KeepADBAutomation.ACTION_WIFI_ADB_OFF,
                KeepADBAutomation.ACTION_WIFI_ADB_TOGGLE, KeepADBAutomation.ACTION_KEEP_ALIVE_ON,
                KeepADBAutomation.ACTION_KEEP_ALIVE_OFF, KeepADBAutomation.ACTION_STATUS);
    }

    private static Path root() {
        Path directory = Paths.get("").toAbsolutePath();
        while (directory != null && !Files.exists(directory.resolve("settings.gradle"))) {
            directory = directory.getParent();
        }
        if (directory == null) throw new IllegalStateException("Could not locate project root");
        return directory;
    }

    private static final class FakeActions implements KeepADBAutomation.Actions {
        final List<String> calls = new ArrayList<>();
        boolean networkAllowed = true;
        boolean wifiOn;
        KeepADB.EnableGuard lastGuard;

        @Override
        public boolean networkAllowsEnable(Context app) {
            return networkAllowed;
        }

        @Override
        public KeepADB.ToggleResult setWifiAdb(Context app, boolean on, KeepADB.EnableGuard guard) {
            calls.add("wifi:" + on);
            lastGuard = guard;
            wifiOn = on;
            return KeepADB.ToggleResult.APPLIED;
        }

        @Override
        public boolean isWifiAdbOn(Context app) {
            return wifiOn;
        }

        @Override
        public void setKeepAlive(Context app, boolean on) {
            calls.add("keepalive:" + on);
        }

        @Override
        public KeepADBEndpointCoordinator.Snapshot endpoint() {
            return new KeepADBEndpointCoordinator.Snapshot("192.168.1.20", 37000, 1L);
        }

        @Override
        public void refreshSurfaces(Context app) {
        }
    }
}
