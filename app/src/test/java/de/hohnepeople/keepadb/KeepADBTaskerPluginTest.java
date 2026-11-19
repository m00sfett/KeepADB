package de.hohnepeople.keepadb;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.widget.RadioButton;
import android.widget.RadioGroup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;

/** #999: the Tasker/Locale action plugin on top of the automation interface. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeepADBTaskerPluginTest {
    private static final String TASKER = "net.dinglisch.android.taskerm";

    private Context context;
    private FakeActions fake;
    private final AtomicLong clock = new AtomicLong(1_000_000L);

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        fake = new FakeActions();
        KeepADBAutomation.setActionsForTesting(fake);
        KeepADBAutomation.setClockForTesting(clock::get);
        KeepADBAutomation.resetFloodStateForTesting();
        KeepADBDiagnosticJournal.resetForTesting();
    }

    @After
    public void tearDown() {
        KeepADBAutomation.setActionsForTesting(null);
        KeepADBAutomation.setClockForTesting(null);
        KeepADBAutomation.resetFloodStateForTesting();
    }

    private String enable() {
        KeepADBAutomation.enable(context);
        return KeepADBPreferences.getAutomationToken(context);
    }

    // ---- Bundle validation ------------------------------------------------------------------

    @Test
    public void bundleRoundTripsEveryRequest() {
        for (KeepADBTaskerPlugin.Request request : KeepADBTaskerPlugin.Request.values()) {
            KeepADBTaskerPlugin.Config config = KeepADBTaskerPlugin.parseBundle(
                    KeepADBTaskerPlugin.buildBundle(request, "secret-token"));
            assertNotNull(request.name(), config);
            assertEquals(request, config.request);
            assertEquals(request.name(), request.storesToken ? "secret-token" : null, config.token);
        }
    }

    @Test
    public void onlyRequestsThatNeedTheTokenStoreIt() {
        assertTrue(KeepADBTaskerPlugin.Request.WIFI_ADB_ON.storesToken);
        assertTrue(KeepADBTaskerPlugin.Request.WIFI_ADB_TOGGLE.storesToken);
        assertTrue(KeepADBTaskerPlugin.Request.KEEP_ALIVE_ON.storesToken);
        assertTrue("the status returns the endpoint only with the token",
                KeepADBTaskerPlugin.Request.STATUS.storesToken);
        assertFalse(KeepADBTaskerPlugin.buildBundle(KeepADBTaskerPlugin.Request.WIFI_ADB_OFF, "t")
                .containsKey(KeepADBTaskerPlugin.BUNDLE_KEY_TOKEN));
        assertFalse(KeepADBTaskerPlugin.buildBundle(KeepADBTaskerPlugin.Request.KEEP_ALIVE_OFF, "t")
                .containsKey(KeepADBTaskerPlugin.BUNDLE_KEY_TOKEN));
    }

    @Test
    public void requestIdsAreTheFrozenB1aRequestNames() {
        assertEquals("WIFI_ADB_ON", KeepADBTaskerPlugin.Request.WIFI_ADB_ON.id());
        assertEquals("WIFI_ADB_OFF", KeepADBTaskerPlugin.Request.WIFI_ADB_OFF.id());
        assertEquals("WIFI_ADB_TOGGLE", KeepADBTaskerPlugin.Request.WIFI_ADB_TOGGLE.id());
        assertEquals("KEEP_ALIVE_ON", KeepADBTaskerPlugin.Request.KEEP_ALIVE_ON.id());
        assertEquals("KEEP_ALIVE_OFF", KeepADBTaskerPlugin.Request.KEEP_ALIVE_OFF.id());
        assertEquals("STATUS", KeepADBTaskerPlugin.Request.STATUS.id());
    }

    @Test
    public void invalidBundlesAreRejected() {
        assertNull(KeepADBTaskerPlugin.parseBundle(null));
        assertNull(KeepADBTaskerPlugin.parseBundle(new Bundle()));

        Bundle newer = valid();
        newer.putInt(KeepADBTaskerPlugin.BUNDLE_KEY_VERSION, KeepADBTaskerPlugin.BUNDLE_VERSION + 1);
        assertNull("a newer version is not guessed at", KeepADBTaskerPlugin.parseBundle(newer));

        Bundle versionAsString = valid();
        versionAsString.putString(KeepADBTaskerPlugin.BUNDLE_KEY_VERSION, "1");
        assertNull(KeepADBTaskerPlugin.parseBundle(versionAsString));

        Bundle unknown = valid();
        unknown.putString(KeepADBTaskerPlugin.BUNDLE_KEY_REQUEST, "FORMAT_PHONE");
        assertNull(KeepADBTaskerPlugin.parseBundle(unknown));

        Bundle fullAction = valid();
        fullAction.putString(KeepADBTaskerPlugin.BUNDLE_KEY_REQUEST, KeepADBAutomation.ACTION_WIFI_ADB_ON);
        assertNull("only the short id is accepted", KeepADBTaskerPlugin.parseBundle(fullAction));

        Bundle tokenAsNumber = valid();
        tokenAsNumber.putInt(KeepADBTaskerPlugin.BUNDLE_KEY_TOKEN, 42);
        assertNull(KeepADBTaskerPlugin.parseBundle(tokenAsNumber));

        Bundle emptyToken = valid();
        emptyToken.putString(KeepADBTaskerPlugin.BUNDLE_KEY_TOKEN, "");
        assertNull(KeepADBTaskerPlugin.parseBundle(emptyToken));

        Bundle longToken = valid();
        longToken.putString(KeepADBTaskerPlugin.BUNDLE_KEY_TOKEN,
                "x".repeat(KeepADBTaskerPlugin.MAX_TOKEN_LENGTH + 1));
        assertNull(KeepADBTaskerPlugin.parseBundle(longToken));
    }

    @Test
    public void foreignKeysOfTheHostAreIgnored() {
        Bundle bundle = valid();
        bundle.putString("net.dinglisch.android.tasker.extras.VARIABLE_REPLACE_KEYS", "x");
        bundle.putBoolean("com.example.host.FLAG", true);
        assertNotNull(KeepADBTaskerPlugin.parseBundle(bundle));
    }

    private static Bundle valid() {
        return KeepADBTaskerPlugin.buildBundle(KeepADBTaskerPlugin.Request.WIFI_ADB_ON, "token");
    }

    // ---- Firing through the B1a decision ----------------------------------------------------

    @Test
    public void disabledInterfaceDoesNothingAndSaysSo() {
        String token = enable();
        KeepADBAutomation.disable(context);
        KeepADBTaskerPlugin.Outcome outcome = KeepADBTaskerPlugin.fire(context,
                KeepADBTaskerPlugin.buildBundle(KeepADBTaskerPlugin.Request.WIFI_ADB_ON, token));
        assertEquals(KeepADBTaskerPlugin.RESULT_CODE_FAILED, outcome.resultCode);
        assertEquals("disabled", outcome.variables.getString(KeepADBTaskerPlugin.VAR_RESULT));
        assertEquals("disabled", outcome.variables.getString(KeepADBTaskerPlugin.VAR_ERROR_MESSAGE));
        assertNull("no state while the interface is off",
                outcome.variables.getString(KeepADBTaskerPlugin.VAR_STATE));
        assertTrue(fake.calls.isEmpty());
    }

    @Test
    public void invalidBundleDoesNothing() {
        enable();
        KeepADBTaskerPlugin.Outcome outcome = KeepADBTaskerPlugin.fire(context, new Bundle());
        assertEquals(KeepADBTaskerPlugin.RESULT_CODE_FAILED, outcome.resultCode);
        assertEquals("invalid_config", outcome.variables.getString(KeepADBTaskerPlugin.VAR_RESULT));
        assertTrue(fake.calls.isEmpty());
    }

    @Test
    public void onWithTheCurrentTokenSwitchesAndReturnsTheEndpoint() {
        String token = enable();
        KeepADBTaskerPlugin.Outcome outcome = KeepADBTaskerPlugin.fire(context,
                KeepADBTaskerPlugin.buildBundle(KeepADBTaskerPlugin.Request.WIFI_ADB_ON, token));
        assertEquals(List.of("wifi:true"), fake.calls);
        assertNotNull("the network protection guard is passed on", fake.lastGuard);
        assertEquals(KeepADBTaskerPlugin.RESULT_CODE_OK, outcome.resultCode);
        Bundle vars = outcome.variables;
        assertEquals("ok", vars.getString(KeepADBTaskerPlugin.VAR_RESULT));
        assertEquals("on", vars.getString(KeepADBTaskerPlugin.VAR_STATE));
        assertEquals("off", vars.getString(KeepADBTaskerPlugin.VAR_KEEP_ALIVE));
        assertEquals("192.168.1.20", vars.getString(KeepADBTaskerPlugin.VAR_IP));
        assertEquals("37000", vars.getString(KeepADBTaskerPlugin.VAR_PORT));
        assertNull(vars.getString(KeepADBTaskerPlugin.VAR_ERROR_MESSAGE));
    }

    @Test
    public void aNewTokenInvalidatesSavedOnConfigurations() {
        String old = enable();
        Bundle saved = KeepADBTaskerPlugin.buildBundle(KeepADBTaskerPlugin.Request.WIFI_ADB_ON, old);
        KeepADBAutomation.regenerateToken(context);
        KeepADBTaskerPlugin.Outcome outcome = KeepADBTaskerPlugin.fire(context, saved);
        assertTrue(fake.calls.isEmpty());
        assertEquals(KeepADBTaskerPlugin.RESULT_CODE_FAILED, outcome.resultCode);
        assertEquals("rejected_token", outcome.variables.getString(KeepADBTaskerPlugin.VAR_RESULT));
        assertEquals("rejected_token",
                outcome.variables.getString(KeepADBTaskerPlugin.VAR_ERROR_MESSAGE));
        assertNull("no endpoint without a valid token",
                outcome.variables.getString(KeepADBTaskerPlugin.VAR_IP));
        KeepADBAutomation.flushRejections(context);
        assertEquals("counted like a B1a rejection", 1,
                KeepADBPreferences.getAutomationRejectedCount(context));
    }

    @Test
    public void offConfigurationsKeepWorkingAfterANewToken() {
        String old = enable();
        fake.wifiOn = true;
        Bundle saved = KeepADBTaskerPlugin.buildBundle(KeepADBTaskerPlugin.Request.WIFI_ADB_OFF, old);
        KeepADBAutomation.regenerateToken(context);
        KeepADBTaskerPlugin.Outcome outcome = KeepADBTaskerPlugin.fire(context, saved);
        assertEquals(List.of("wifi:false"), fake.calls);
        assertEquals(KeepADBTaskerPlugin.RESULT_CODE_OK, outcome.resultCode);
        assertEquals("off", outcome.variables.getString(KeepADBTaskerPlugin.VAR_STATE));
    }

    @Test
    public void networkRejectionIsAFailureWithTheReason() {
        String token = enable();
        fake.networkAllowed = false;
        KeepADBTaskerPlugin.Outcome outcome = KeepADBTaskerPlugin.fire(context,
                KeepADBTaskerPlugin.buildBundle(KeepADBTaskerPlugin.Request.WIFI_ADB_TOGGLE, token));
        assertTrue(fake.calls.isEmpty());
        assertEquals(KeepADBTaskerPlugin.RESULT_CODE_FAILED, outcome.resultCode);
        assertEquals("rejected_network",
                outcome.variables.getString(KeepADBTaskerPlugin.VAR_RESULT));
    }

    @Test
    public void statusWithoutTokenReturnsTheStateButNoEndpoint() {
        enable();
        fake.wifiOn = true;
        Bundle bundle = new Bundle();
        bundle.putInt(KeepADBTaskerPlugin.BUNDLE_KEY_VERSION, KeepADBTaskerPlugin.BUNDLE_VERSION);
        bundle.putString(KeepADBTaskerPlugin.BUNDLE_KEY_REQUEST, "STATUS");
        KeepADBTaskerPlugin.Outcome outcome = KeepADBTaskerPlugin.fire(context, bundle);
        assertEquals(KeepADBTaskerPlugin.RESULT_CODE_OK, outcome.resultCode);
        assertEquals("on", outcome.variables.getString(KeepADBTaskerPlugin.VAR_STATE));
        assertNull(outcome.variables.getString(KeepADBTaskerPlugin.VAR_IP));
        assertNull(outcome.variables.getString(KeepADBTaskerPlugin.VAR_PORT));
        assertTrue(fake.calls.isEmpty());
    }

    @Test
    public void keepAliveOnNeedsTheToken() {
        String token = enable();
        Bundle withoutToken = new Bundle();
        withoutToken.putInt(KeepADBTaskerPlugin.BUNDLE_KEY_VERSION, KeepADBTaskerPlugin.BUNDLE_VERSION);
        withoutToken.putString(KeepADBTaskerPlugin.BUNDLE_KEY_REQUEST, "KEEP_ALIVE_ON");
        assertEquals(KeepADBTaskerPlugin.RESULT_CODE_FAILED,
                KeepADBTaskerPlugin.fire(context, withoutToken).resultCode);
        assertTrue(fake.calls.isEmpty());
        assertEquals(KeepADBTaskerPlugin.RESULT_CODE_OK, KeepADBTaskerPlugin.fire(context,
                KeepADBTaskerPlugin.buildBundle(KeepADBTaskerPlugin.Request.KEEP_ALIVE_ON, token))
                .resultCode);
        assertEquals(List.of("keepalive:true"), fake.calls);
    }

    @Test
    public void orderedFireBroadcastReturnsResultCodeAndVariables() {
        String token = enable();
        Intent fire = new Intent(KeepADBTaskerPlugin.ACTION_FIRE_SETTING)
                .setComponent(new ComponentName(context, KeepADBPluginFireReceiver.class))
                .putExtra(KeepADBTaskerPlugin.EXTRA_BUNDLE, KeepADBTaskerPlugin.buildBundle(
                        KeepADBTaskerPlugin.Request.WIFI_ADB_ON, token));
        AtomicReference<Integer> code = new AtomicReference<>();
        AtomicReference<Bundle> extras = new AtomicReference<>();
        context.sendOrderedBroadcast(fire, null, new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                code.set(getResultCode());
                extras.set(getResultExtras(false));
            }
        }, null, Activity.RESULT_CANCELED, null, null);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(Integer.valueOf(KeepADBTaskerPlugin.RESULT_CODE_OK), code.get());
        Bundle vars = extras.get().getBundle(KeepADBTaskerPlugin.EXTRA_VARIABLES);
        assertEquals("ok", vars.getString(KeepADBTaskerPlugin.VAR_RESULT));
        assertEquals("on", vars.getString(KeepADBTaskerPlugin.VAR_STATE));
        assertEquals(List.of("wifi:true"), fake.calls);
    }

    @Test
    public void receiverIgnoresOtherActionsAndSurvivesBrokenIntents() {
        String token = enable();
        KeepADBPluginFireReceiver receiver = new KeepADBPluginFireReceiver();
        receiver.onReceive(context, null);
        receiver.onReceive(context, new Intent(KeepADBAutomation.ACTION_WIFI_ADB_ON)
                .putExtra(KeepADBTaskerPlugin.EXTRA_BUNDLE, KeepADBTaskerPlugin.buildBundle(
                        KeepADBTaskerPlugin.Request.WIFI_ADB_ON, token)));
        receiver.onReceive(context, new Intent(KeepADBTaskerPlugin.ACTION_FIRE_SETTING)
                .putExtra(KeepADBTaskerPlugin.EXTRA_BUNDLE, "not a bundle"));
        assertTrue(fake.calls.isEmpty());
    }

    // ---- Edit screen ------------------------------------------------------------------------

    private ActivityController<KeepADBPluginEditActivity> editScreen(String caller, Bundle previous) {
        Intent intent = new Intent(context, KeepADBPluginEditActivity.class)
                .setAction(KeepADBTaskerPlugin.ACTION_EDIT_SETTING);
        if (previous != null) intent.putExtra(KeepADBTaskerPlugin.EXTRA_BUNDLE, previous);
        ActivityController<KeepADBPluginEditActivity> controller =
                Robolectric.buildActivity(KeepADBPluginEditActivity.class, intent);
        shadowOf(controller.get()).setCallingPackage(caller);
        return controller.setup();
    }

    @Test
    public void disabledInterfaceShowsTheHintAndSavesNothing() {
        KeepADBPluginEditActivity activity = editScreen(TASKER, null).get();
        assertEquals(View.VISIBLE, activity.findViewById(R.id.plugin_disabled).getVisibility());
        assertEquals(View.VISIBLE, activity.findViewById(R.id.plugin_open_settings).getVisibility());
        assertEquals(View.GONE, activity.findViewById(R.id.plugin_config).getVisibility());
        assertFalse(activity.findViewById(R.id.plugin_save).isEnabled());
        activity.findViewById(R.id.plugin_save).performClick();
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).getResultCode());
    }

    @Test
    public void openSettingsLeadsToTheAutomationCard() {
        KeepADBPluginEditActivity activity = editScreen(TASKER, null).get();
        activity.findViewById(R.id.plugin_open_settings).performClick();
        Intent started = shadowOf(activity).getNextStartedActivity();
        assertEquals(SettingsActivity.class.getName(), started.getComponent().getClassName());
        assertTrue(started.getBooleanExtra(SettingsActivity.EXTRA_FOCUS_AUTOMATION, false));
    }

    @Test
    public void knownHostSavesTheChosenRequestWithTheCurrentToken() {
        String token = enable();
        KeepADBPluginEditActivity activity = editScreen(TASKER, null).get();
        selectRequest(activity, KeepADBTaskerPlugin.Request.WIFI_ADB_TOGGLE);
        activity.findViewById(R.id.plugin_save).performClick();

        assertEquals(Activity.RESULT_OK, shadowOf(activity).getResultCode());
        Intent result = shadowOf(activity).getResultIntent();
        KeepADBTaskerPlugin.Config saved = KeepADBTaskerPlugin.parseBundle(
                result.getBundleExtra(KeepADBTaskerPlugin.EXTRA_BUNDLE));
        assertEquals(KeepADBTaskerPlugin.Request.WIFI_ADB_TOGGLE, saved.request);
        assertEquals(token, saved.token);
        assertEquals(context.getString(R.string.plugin_request_wifi_adb_toggle),
                result.getStringExtra(KeepADBTaskerPlugin.EXTRA_BLURB));
        String[] relevant = result.getStringArrayExtra(KeepADBTaskerPlugin.EXTRA_RELEVANT_VARIABLES);
        assertEquals(5, relevant.length);
        assertTrue(relevant[0].startsWith(KeepADBTaskerPlugin.VAR_RESULT + "\n"));
        assertTrue(activity.isFinishing());
    }

    @Test
    public void unknownCallerMustConfirmBeforeItGetsTheToken() {
        enable();
        KeepADBPluginEditActivity activity = editScreen("com.example.unknown", null).get();
        activity.findViewById(R.id.plugin_save).performClick();
        AlertDialog dialog = activity.getActiveDialog();
        assertNotNull("an unknown app needs a second question", dialog);
        assertTrue(dialog.isShowing());
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).getResultCode());
        assertTrue(shadowOf(dialog).getMessage().toString().contains("com.example.unknown"));

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(Activity.RESULT_OK, shadowOf(activity).getResultCode());
    }

    @Test
    public void unknownCallerCancellingKeepsTheTokenBack() {
        enable();
        KeepADBPluginEditActivity activity = editScreen("com.example.unknown", null).get();
        activity.findViewById(R.id.plugin_save).performClick();
        activity.getActiveDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).getResultCode());
        assertNull(shadowOf(activity).getResultIntent());
    }

    @Test
    public void withoutACallerNothingCanBeSaved() {
        enable();
        KeepADBPluginEditActivity activity = editScreen(null, null).get();
        assertFalse(activity.findViewById(R.id.plugin_save).isEnabled());
        activity.findViewById(R.id.plugin_save).performClick();
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).getResultCode());
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
    }

    @Test
    public void editingPreselectsTheSavedRequest() {
        String token = enable();
        KeepADBPluginEditActivity activity = editScreen(TASKER, KeepADBTaskerPlugin.buildBundle(
                KeepADBTaskerPlugin.Request.KEEP_ALIVE_OFF, token)).get();
        RadioGroup group = activity.findViewById(R.id.plugin_requests);
        RadioButton checked = group.findViewById(group.getCheckedRadioButtonId());
        assertEquals(KeepADBTaskerPlugin.Request.KEEP_ALIVE_OFF, checked.getTag());
    }

    @Test
    public void aDisabledInterfaceAtSaveTimeGivesNoToken() {
        enable();
        KeepADBPluginEditActivity activity = editScreen(TASKER, null).get();
        KeepADBAutomation.disable(context);
        activity.findViewById(R.id.plugin_save).performClick();
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).getResultCode());
        assertFalse(activity.findViewById(R.id.plugin_save).isEnabled());
    }

    private static void selectRequest(KeepADBPluginEditActivity activity,
                                      KeepADBTaskerPlugin.Request request) {
        RadioGroup group = activity.findViewById(R.id.plugin_requests);
        for (int i = 0; i < group.getChildCount(); i++) {
            RadioButton button = (RadioButton) group.getChildAt(i);
            if (button.getTag() == request) button.performClick();
        }
    }

    // ---- Manifest contract ------------------------------------------------------------------

    @Test
    public void pluginComponentsAreExportedWithExactlyTheLocaleActions() throws IOException {
        String manifest = new String(Files.readAllBytes(
                root().resolve("app/src/main/AndroidManifest.xml")), StandardCharsets.UTF_8);

        String receiver = element(manifest, "receiver", ".KeepADBPluginFireReceiver");
        assertTrue(receiver.contains("android:exported=\"true\""));
        assertFalse("a permission would make it unreachable for Tasker",
                receiver.contains("android:permission"));
        assertArrayEquals(new String[] {KeepADBTaskerPlugin.ACTION_FIRE_SETTING},
                actions(receiver).toArray(new String[0]));

        String activity = element(manifest, "activity", ".KeepADBPluginEditActivity");
        assertTrue(activity.contains("android:exported=\"true\""));
        assertArrayEquals(new String[] {KeepADBTaskerPlugin.ACTION_EDIT_SETTING},
                actions(activity).toArray(new String[0]));

        List<String> exportedReceivers = new ArrayList<>();
        Matcher receivers = Pattern.compile("<receiver[\\s\\S]*?</receiver>").matcher(manifest);
        while (receivers.find()) {
            String r = receivers.group();
            if (!r.contains("android:exported=\"true\"")) continue;
            Matcher name = Pattern.compile("android:name=\"([^\"]+)\"").matcher(r);
            assertTrue(name.find());
            exportedReceivers.add(name.group(1));
        }
        exportedReceivers.sort(null);
        assertEquals(List.of(".KeepADBAutomationReceiver", ".KeepADBPluginFireReceiver"),
                exportedReceivers);
    }

    private static String element(String manifest, String tag, String name) {
        Matcher matcher = Pattern.compile("<" + tag + "[\\s\\S]*?</" + tag + ">").matcher(manifest);
        while (matcher.find()) {
            if (matcher.group().contains("android:name=\"" + name + "\"")) return matcher.group();
        }
        throw new AssertionError(name + " not declared");
    }

    private static List<String> actions(String element) {
        Matcher matcher = Pattern.compile("<action android:name=\"([^\"]+)\"").matcher(element);
        List<String> actions = new ArrayList<>();
        while (matcher.find()) actions.add(matcher.group(1));
        return actions;
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
        public boolean isEnablePending() {
            return false;
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
