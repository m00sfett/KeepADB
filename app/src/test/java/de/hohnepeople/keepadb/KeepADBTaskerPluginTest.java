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
        KeepADBTaskerPlugin.resetQueryStateForTesting();
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
        // #1026: without a requested timeout Tasker neither waits nor reads the variables.
        assertEquals(KeepADBTaskerPlugin.REQUESTED_TIMEOUT_MS,
                result.getIntExtra(KeepADBTaskerPlugin.EXTRA_REQUESTED_TIMEOUT, -1));
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
        assertEquals(KeepADBTaskerPlugin.Request.KEEP_ALIVE_OFF.id(), checked.getTag());
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
            if (request.id().equals(button.getTag())) button.performClick();
        }
    }

    // ---- Condition plugin -------------------------------------------------------------------

    private static Bundle condition(KeepADBTaskerPlugin.Condition c, String token) {
        return KeepADBTaskerPlugin.buildConditionBundle(c, token);
    }

    @Test
    public void conditionBundleRoundTripsAndRejectsGarbage() {
        for (KeepADBTaskerPlugin.Condition c : KeepADBTaskerPlugin.Condition.values()) {
            KeepADBTaskerPlugin.ConditionConfig config =
                    KeepADBTaskerPlugin.parseConditionBundle(condition(c, "tok"));
            assertEquals(c, config.condition);
            assertEquals("tok", config.token);
        }
        assertNull(KeepADBTaskerPlugin.parseConditionBundle(null));
        assertNull("an action bundle is no condition",
                KeepADBTaskerPlugin.parseConditionBundle(valid()));
        assertNull("a condition bundle is no action", KeepADBTaskerPlugin.parseBundle(
                condition(KeepADBTaskerPlugin.Condition.WIFI_ADB_ON, "tok")));
        Bundle unknown = condition(KeepADBTaskerPlugin.Condition.WIFI_ADB_ON, null);
        unknown.putString(KeepADBTaskerPlugin.BUNDLE_KEY_CONDITION, "PHONE_IS_ROOTED");
        assertNull(KeepADBTaskerPlugin.parseConditionBundle(unknown));
        Bundle badToken = condition(KeepADBTaskerPlugin.Condition.WIFI_ADB_ON, null);
        badToken.putLong(KeepADBTaskerPlugin.BUNDLE_KEY_TOKEN, 1L);
        assertNull(KeepADBTaskerPlugin.parseConditionBundle(badToken));
    }

    @Test
    public void conditionIsUnknownWhileTheInterfaceIsOffOrTheBundleIsInvalid() {
        String token = enable();
        fake.wifiOn = true;
        KeepADBAutomation.disable(context);
        KeepADBTaskerPlugin.Outcome off = KeepADBTaskerPlugin.query(context,
                condition(KeepADBTaskerPlugin.Condition.WIFI_ADB_ON, token));
        assertEquals(KeepADBTaskerPlugin.RESULT_CONDITION_UNKNOWN, off.resultCode);
        assertNull("nothing about the state while off", off.variables);

        enable();
        KeepADBTaskerPlugin.Outcome invalid = KeepADBTaskerPlugin.query(context, new Bundle());
        assertEquals(KeepADBTaskerPlugin.RESULT_CONDITION_UNKNOWN, invalid.resultCode);
        assertTrue(fake.calls.isEmpty());
    }

    @Test
    public void wifiAdbOnConditionFollowsTheStateWithoutAToken() {
        enable();
        Bundle bundle = condition(KeepADBTaskerPlugin.Condition.WIFI_ADB_ON, null);
        assertEquals(KeepADBTaskerPlugin.RESULT_CONDITION_UNSATISFIED,
                KeepADBTaskerPlugin.query(context, bundle).resultCode);
        fake.wifiOn = true;
        KeepADBTaskerPlugin.Outcome on = KeepADBTaskerPlugin.query(context, bundle);
        assertEquals(KeepADBTaskerPlugin.RESULT_CONDITION_SATISFIED, on.resultCode);
        assertEquals("on", on.variables.getString(KeepADBTaskerPlugin.VAR_STATE));
        assertNull("no endpoint without a token", on.variables.getString(KeepADBTaskerPlugin.VAR_IP));
        assertTrue("a query switches nothing", fake.calls.isEmpty());
    }

    @Test
    public void endpointKnownNeedsWifiAdbOnAndAnEndpointButNoToken() {
        enable();
        Bundle bundle = condition(KeepADBTaskerPlugin.Condition.ENDPOINT_KNOWN, null);
        fake.wifiOn = false;
        assertEquals("an old endpoint while off does not count",
                KeepADBTaskerPlugin.RESULT_CONDITION_UNSATISFIED,
                KeepADBTaskerPlugin.query(context, bundle).resultCode);
        fake.wifiOn = true;
        fake.endpoint = null;
        assertEquals(KeepADBTaskerPlugin.RESULT_CONDITION_UNSATISFIED,
                KeepADBTaskerPlugin.query(context, bundle).resultCode);
        fake.endpoint = new KeepADBEndpointCoordinator.Snapshot("192.168.1.20", 37000, 1L);
        KeepADBTaskerPlugin.Outcome known = KeepADBTaskerPlugin.query(context, bundle);
        assertEquals(KeepADBTaskerPlugin.RESULT_CONDITION_SATISFIED, known.resultCode);
        assertNull("the yes/no is no endpoint", known.variables.getString(KeepADBTaskerPlugin.VAR_IP));
    }

    @Test
    public void validTokenAddsTheEndpointVariables() {
        String token = enable();
        fake.wifiOn = true;
        KeepADBTaskerPlugin.Outcome outcome = KeepADBTaskerPlugin.query(context,
                condition(KeepADBTaskerPlugin.Condition.ENDPOINT_KNOWN, token));
        assertEquals(KeepADBTaskerPlugin.RESULT_CONDITION_SATISFIED, outcome.resultCode);
        assertEquals("192.168.1.20", outcome.variables.getString(KeepADBTaskerPlugin.VAR_IP));
        assertEquals("37000", outcome.variables.getString(KeepADBTaskerPlugin.VAR_PORT));
    }

    @Test
    public void staleTokenStillAnswersButDropsTheEndpointAndIsCounted() {
        String old = enable();
        fake.wifiOn = true;
        Bundle saved = condition(KeepADBTaskerPlugin.Condition.WIFI_ADB_ON, old);
        KeepADBAutomation.regenerateToken(context);
        KeepADBTaskerPlugin.Outcome outcome = KeepADBTaskerPlugin.query(context, saved);
        assertEquals("the condition needs no token", KeepADBTaskerPlugin.RESULT_CONDITION_SATISFIED,
                outcome.resultCode);
        assertNull(outcome.variables.getString(KeepADBTaskerPlugin.VAR_IP));
        assertEquals("rejected_token", outcome.variables.getString(KeepADBTaskerPlugin.VAR_RESULT));
        KeepADBAutomation.flushRejections(context);
        assertEquals(1, KeepADBPreferences.getAutomationRejectedCount(context));
    }

    @Test
    public void pollingWithAValidTokenWritesOneJournalLinePerWindow() {
        String token = enable();
        Bundle bundle = condition(KeepADBTaskerPlugin.Condition.WIFI_ADB_ON, token);
        for (int i = 0; i < 50; i++) KeepADBTaskerPlugin.query(context, bundle);
        String journal = KeepADBDiagnostics.export(context);
        assertEquals(1, occurrences(journal, "request=STATUS endpoint=true"));
        // A B1a status request with the token is still never throttled.
        KeepADBAutomation.process(context, KeepADBAutomation.ACTION_STATUS, token);
        KeepADBAutomation.process(context, KeepADBAutomation.ACTION_STATUS, token);
        assertEquals(3, occurrences(KeepADBDiagnostics.export(context),
                "request=STATUS endpoint=true"));
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) count++;
        return count;
    }

    @Test
    public void orderedQueryBroadcastReturnsTheConditionResult() {
        enable();
        fake.wifiOn = true;
        Intent query = new Intent(KeepADBTaskerPlugin.ACTION_QUERY_CONDITION)
                .setComponent(new ComponentName(context, KeepADBPluginConditionReceiver.class))
                .putExtra(KeepADBTaskerPlugin.EXTRA_BUNDLE,
                        condition(KeepADBTaskerPlugin.Condition.WIFI_ADB_ON, null));
        AtomicReference<Integer> code = new AtomicReference<>();
        AtomicReference<Bundle> extras = new AtomicReference<>();
        context.sendOrderedBroadcast(query, null, new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                code.set(getResultCode());
                extras.set(getResultExtras(false));
            }
        }, null, Activity.RESULT_CANCELED, null, null);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(Integer.valueOf(KeepADBTaskerPlugin.RESULT_CONDITION_SATISFIED), code.get());
        assertEquals("on", extras.get().getBundle(KeepADBTaskerPlugin.EXTRA_VARIABLES)
                .getString(KeepADBTaskerPlugin.VAR_STATE));
    }

    // ---- REQUEST_QUERY ----------------------------------------------------------------------

    private List<Intent> requestQueries() {
        List<Intent> found = new ArrayList<>();
        for (Intent i : shadowOf((android.app.Application) context).getBroadcastIntents()) {
            if (KeepADBTaskerPlugin.ACTION_REQUEST_QUERY.equals(i.getAction())) found.add(i);
        }
        return found;
    }

    @Test
    public void requestQueryIsSentOnlyWhenEnabledAndOnlyOnAChange() {
        KeepADBTaskerPlugin.requestQueryIfChanged(context);
        assertTrue("nothing while the interface is off", requestQueries().isEmpty());

        enable();
        assertEquals("switching the interface on announces the state", 1, requestQueries().size());
        KeepADBTaskerPlugin.requestQueryIfChanged(context);
        Intent sent = requestQueries().get(0);
        assertNull("no package: every host that registered may hear it", sent.getPackage());
        assertEquals(1, sent.getExtras().size());

        KeepADBTaskerPlugin.requestQueryIfChanged(context);
        assertEquals("same state, no second broadcast", 1, requestQueries().size());

        fake.wifiOn = true;
        KeepADBTaskerPlugin.requestQueryIfChanged(context);
        assertEquals(2, requestQueries().size());

        fake.endpoint = null;
        KeepADBTaskerPlugin.requestQueryIfChanged(context);
        assertEquals("losing the endpoint is a change", 3, requestQueries().size());
    }

    @Test
    public void everySurfaceRefreshChecksForAConditionChange() {
        enable();
        assertEquals(1, requestQueries().size());
        fake.wifiOn = true;
        KeepADBWidget.refreshAllState(context);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(2, requestQueries().size());
    }

    // ---- Codex review of PR #1025 -----------------------------------------------------------

    @Test
    public void switchingTheInterfaceOnAnnouncesTheStateAgain() {
        enable();
        assertEquals("enabling announces the current state", 1, requestQueries().size());
        KeepADBAutomation.disable(context);
        assertEquals("disabling sends nothing", 1, requestQueries().size());
        // Nothing changed meanwhile; a host that got "unknown" while off must still hear about it.
        KeepADBAutomation.enable(context);
        assertEquals(2, requestQueries().size());
    }

    /** An intent whose plugin bundle fails to unparcel, as a foreign sender can build one. */
    private static Intent brokenIntent(String action) {
        return new Intent(action) {
            @Override
            public Bundle getBundleExtra(String name) {
                throw new android.os.BadParcelableException("ClassNotFoundException");
            }
        };
    }

    @Test
    public void anUnreadableFireBundleIsAFailureNotTheHostsInitialCode() {
        enable();
        KeepADBTaskerPlugin.Outcome outcome = KeepADBTaskerPlugin.fireFromIntent(context,
                brokenIntent(KeepADBTaskerPlugin.ACTION_FIRE_SETTING));
        assertEquals(KeepADBTaskerPlugin.RESULT_CODE_FAILED, outcome.resultCode);
        assertEquals("invalid_config", outcome.variables.getString(KeepADBTaskerPlugin.VAR_RESULT));
        assertTrue(fake.calls.isEmpty());
    }

    @Test
    public void anUnreadableQueryBundleIsUnknown() {
        enable();
        assertEquals(KeepADBTaskerPlugin.RESULT_CONDITION_UNKNOWN, KeepADBTaskerPlugin
                .queryFromIntent(context, brokenIntent(KeepADBTaskerPlugin.ACTION_QUERY_CONDITION))
                .resultCode);
    }

    @Test
    public void orderedFireWithAnInvalidBundleOverridesAnInitialSuccessCode() {
        enable();
        Intent fire = new Intent(KeepADBTaskerPlugin.ACTION_FIRE_SETTING)
                .setComponent(new ComponentName(context, KeepADBPluginFireReceiver.class))
                .putExtra(KeepADBTaskerPlugin.EXTRA_BUNDLE, new Bundle());
        AtomicReference<Integer> code = new AtomicReference<>();
        context.sendOrderedBroadcast(fire, null, new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                code.set(getResultCode());
            }
        }, null, Activity.RESULT_OK, null, null);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(Integer.valueOf(KeepADBTaskerPlugin.RESULT_CODE_FAILED), code.get());
    }

    /**
     * Initial result extras that fail to unparcel on first access, as a foreign ordered sender
     * can build them: one entry with a type code no Parcel knows.
     */
    private static Bundle unreadableExtras() {
        android.os.Parcel parcel = android.os.Parcel.obtain();
        try {
            parcel.writeInt(0); // payload length, patched below
            parcel.writeInt(0x4C444E42); // BaseBundle.BUNDLE_MAGIC
            int payloadStart = parcel.dataPosition();
            parcel.writeInt(1); // entry count
            parcel.writeString("key");
            parcel.writeInt(0x7fff); // unknown value type
            int payloadEnd = parcel.dataPosition();
            parcel.setDataPosition(0);
            parcel.writeInt(payloadEnd - payloadStart);
            parcel.setDataPosition(0);
            Bundle bundle = parcel.readBundle(Bundle.class.getClassLoader());
            assertNotNull(bundle);
            return bundle;
        } finally {
            parcel.recycle();
        }
    }

    @Test
    public void unreadableExtrasReallyFailOnFirstAccess() {
        Bundle extras = unreadableExtras();
        try {
            extras.putBundle("probe", new Bundle());
        } catch (RuntimeException expected) {
            return;
        }
        throw new AssertionError("the fixture must fail to unparcel, or the tests below prove "
                + "nothing");
    }

    /** Sends an ordered broadcast with unreadable initial extras; returns code and extras. */
    private Object[] orderedWithUnreadableExtras(Intent intent, int initialCode) {
        Object[] result = new Object[2];
        context.sendOrderedBroadcast(intent, null, new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                result[0] = getResultCode();
                result[1] = getResultExtras(false);
            }
        }, null, initialCode, null, unreadableExtras());
        shadowOf(Looper.getMainLooper()).idle();
        return result;
    }

    @Test
    public void fireReceiverReplacesUnreadableResultExtrasInsteadOfCrashing() {
        String token = enable();
        Intent fire = new Intent(KeepADBTaskerPlugin.ACTION_FIRE_SETTING)
                .setComponent(new ComponentName(context, KeepADBPluginFireReceiver.class))
                .putExtra(KeepADBTaskerPlugin.EXTRA_BUNDLE, KeepADBTaskerPlugin.buildBundle(
                        KeepADBTaskerPlugin.Request.WIFI_ADB_ON, token));
        Object[] result = orderedWithUnreadableExtras(fire, Activity.RESULT_CANCELED);
        assertEquals(KeepADBTaskerPlugin.RESULT_CODE_OK, result[0]);
        Bundle vars = ((Bundle) result[1]).getBundle(KeepADBTaskerPlugin.EXTRA_VARIABLES);
        assertEquals("ok", vars.getString(KeepADBTaskerPlugin.VAR_RESULT));
        assertEquals(List.of("wifi:true"), fake.calls);
    }

    @Test
    public void fireReceiverReportsAFailureForAnUnreadableBundleUnderUnreadableExtras() {
        enable();
        Intent fire = brokenIntent(KeepADBTaskerPlugin.ACTION_FIRE_SETTING)
                .setComponent(new ComponentName(context, KeepADBPluginFireReceiver.class));
        Object[] result = orderedWithUnreadableExtras(fire, Activity.RESULT_OK);
        assertEquals(KeepADBTaskerPlugin.RESULT_CODE_FAILED, result[0]);
        Bundle vars = ((Bundle) result[1]).getBundle(KeepADBTaskerPlugin.EXTRA_VARIABLES);
        assertEquals("invalid_config", vars.getString(KeepADBTaskerPlugin.VAR_RESULT));
        assertTrue(fake.calls.isEmpty());
    }

    @Test
    public void conditionReceiverReplacesUnreadableResultExtrasInsteadOfCrashing() {
        String token = enable();
        Intent query = new Intent(KeepADBTaskerPlugin.ACTION_QUERY_CONDITION)
                .setComponent(new ComponentName(context, KeepADBPluginConditionReceiver.class))
                .putExtra(KeepADBTaskerPlugin.EXTRA_BUNDLE, KeepADBTaskerPlugin
                        .buildConditionBundle(KeepADBTaskerPlugin.Condition.WIFI_ADB_ON, token));
        Object[] result = orderedWithUnreadableExtras(query, Activity.RESULT_OK);
        assertEquals(KeepADBTaskerPlugin.RESULT_CONDITION_UNSATISFIED, result[0]);
        Bundle vars = ((Bundle) result[1]).getBundle(KeepADBTaskerPlugin.EXTRA_VARIABLES);
        assertEquals("off", vars.getString(KeepADBTaskerPlugin.VAR_STATE));
    }

    @Test
    public void conditionReceiverAnswersUnknownForAnUnreadableBundleUnderUnreadableExtras() {
        enable();
        Intent query = brokenIntent(KeepADBTaskerPlugin.ACTION_QUERY_CONDITION)
                .setComponent(new ComponentName(context, KeepADBPluginConditionReceiver.class));
        Object[] result = orderedWithUnreadableExtras(query, Activity.RESULT_OK);
        assertEquals(KeepADBTaskerPlugin.RESULT_CONDITION_UNKNOWN, result[0]);
    }

    // ---- #1026: completion intent ------------------------------------------------------------

    private static final String TASKER_COMPLETION = "intent:1#Intent;scheme=actionid;"
            + "action=net.dinglisch.android.tasker.ACTION_PLUGIN_FINISHED;"
            + "component=net.dinglisch.android.taskerm/.ReceiverStaticAlwaysOn;end";
    private static final String MACRODROID_COMPLETION = "intent://www.macrodroid.com#Intent;"
            + "scheme=http;action=com.arlosoft.macrodroid.SETTING_COMPLETE;component="
            + "com.arlosoft.macrodroid/.action.receivers.LocaleTaskerSettingCompleteReceiver;"
            + "l.action_id=42;end";

    private static KeepADBTaskerPlugin.Outcome okOutcome() {
        Bundle vars = new Bundle();
        vars.putString(KeepADBTaskerPlugin.VAR_RESULT, "ok");
        return new KeepADBTaskerPlugin.Outcome(KeepADBTaskerPlugin.RESULT_CODE_OK, vars);
    }

    /** Registers a receiver of another app, as Android would resolve it. */
    private void installReceiver(String pkg, String cls, boolean exported, String permission) {
        android.content.pm.PackageInfo info = new android.content.pm.PackageInfo();
        info.packageName = pkg;
        info.applicationInfo = new android.content.pm.ApplicationInfo();
        info.applicationInfo.packageName = pkg;
        info.applicationInfo.enabled = true;
        android.content.pm.ActivityInfo receiver = new android.content.pm.ActivityInfo();
        receiver.packageName = pkg;
        receiver.name = cls;
        receiver.exported = exported;
        receiver.enabled = true;
        receiver.permission = permission;
        receiver.applicationInfo = info.applicationInfo;
        info.receivers = new android.content.pm.ActivityInfo[] {receiver};
        shadowOf(context.getPackageManager()).installPackage(info);
    }

    private void installHosts() {
        installReceiver("net.dinglisch.android.taskerm",
                "net.dinglisch.android.taskerm.ReceiverStaticAlwaysOn", true, null);
        installReceiver("com.arlosoft.macrodroid",
                "com.arlosoft.macrodroid.action.receivers.LocaleTaskerSettingCompleteReceiver",
                true, null);
    }

    private Intent completionFor(String uri) {
        Intent fire = new Intent(KeepADBTaskerPlugin.ACTION_FIRE_SETTING);
        if (uri != null) fire.putExtra(KeepADBTaskerPlugin.EXTRA_COMPLETION_INTENT, uri);
        return KeepADBTaskerPlugin.completionIntent(context, fire, okOutcome());
    }

    @Test
    public void completionIntentOfTaskerAndMacroDroidCarriesTheOutcome() {
        installHosts();
        for (String uri : new String[] {TASKER_COMPLETION, MACRODROID_COMPLETION}) {
            Intent completion = completionFor(uri);
            assertNotNull(uri, completion);
            assertEquals(KeepADBTaskerPlugin.RESULT_CODE_OK,
                    completion.getIntExtra(KeepADBTaskerPlugin.EXTRA_RESULT_CODE, -99));
            assertEquals("ok", completion.getBundleExtra(KeepADBTaskerPlugin.EXTRA_VARIABLES)
                    .getString(KeepADBTaskerPlugin.VAR_RESULT));
        }
        Intent macrodroid = completionFor(MACRODROID_COMPLETION);
        assertEquals("com.arlosoft.macrodroid", macrodroid.getComponent().getPackageName());
        assertEquals("com.arlosoft.macrodroid.SETTING_COMPLETE", macrodroid.getAction());
        assertEquals(42L, macrodroid.getLongExtra("action_id", -1L));
    }

    @Test
    public void completionIntentIsRefusedWithoutAForeignExplicitTarget() {
        assertNull(completionFor(null));
        assertNull(completionFor(""));
        assertNull(completionFor("not a uri %%%"));
        // Implicit: could reach any receiver.
        assertNull(completionFor("intent:#Intent;action=x.y.DONE;end"));
        // KeepADB itself: would reach its own non-exported components.
        assertNull(completionFor("intent:#Intent;action=x.y.DONE;component="
                + context.getPackageName() + "/de.hohnepeople.keepadb.KeepADBWidget;end"));
    }

    @Test
    public void completionIntentNeverReachesAProtectedHiddenOrMissingReceiver() {
        // Codex review: KeepADB holds WRITE_SECURE_SETTINGS; Android checks the broadcaster's
        // permissions, so a protected receiver must not be reachable through KeepADB.
        installReceiver("com.example.victim", "com.example.victim.Protected", true,
                "android.permission.WRITE_SECURE_SETTINGS");
        installReceiver("com.example.victim2", "com.example.victim2.Hidden", false, null);
        assertNull(completionFor("intent:#Intent;action=x.y.DONE;"
                + "component=com.example.victim/.Protected;end"));
        assertNull(completionFor("intent:#Intent;action=x.y.DONE;"
                + "component=com.example.victim2/.Hidden;end"));
        assertNull("not installed", completionFor("intent:#Intent;action=x.y.DONE;"
                + "component=com.example.missing/.Done;end"));
        assertNull("host not installed", completionFor(TASKER_COMPLETION));
    }

    @Test
    public void orderedFireWithACompletionIntentEndsOnlyThroughTheCompletion() {
        installHosts();
        String token = enable();
        Intent fire = new Intent(KeepADBTaskerPlugin.ACTION_FIRE_SETTING)
                .setComponent(new ComponentName(context, KeepADBPluginFireReceiver.class))
                .putExtra(KeepADBTaskerPlugin.EXTRA_BUNDLE, KeepADBTaskerPlugin.buildBundle(
                        KeepADBTaskerPlugin.Request.STATUS, token))
                .putExtra(KeepADBTaskerPlugin.EXTRA_COMPLETION_INTENT, TASKER_COMPLETION);
        AtomicReference<Integer> code = new AtomicReference<>();
        AtomicReference<Bundle> extras = new AtomicReference<>();
        context.sendOrderedBroadcast(fire, null, new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                code.set(getResultCode());
                extras.set(getResultExtras(false));
            }
        }, null, Activity.RESULT_OK, null, null);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("ordered reply only acknowledges",
                Integer.valueOf(KeepADBTaskerPlugin.RESULT_CODE_PENDING), code.get());
        assertTrue(extras.get() == null
                || extras.get().getBundle(KeepADBTaskerPlugin.EXTRA_VARIABLES) == null);
        int completions = 0;
        for (Intent i : shadowOf((android.app.Application) context).getBroadcastIntents()) {
            if ("net.dinglisch.android.tasker.ACTION_PLUGIN_FINISHED".equals(i.getAction())) {
                completions++;
                assertEquals(KeepADBTaskerPlugin.RESULT_CODE_OK,
                        i.getIntExtra(KeepADBTaskerPlugin.EXTRA_RESULT_CODE, -99));
            }
        }
        assertEquals("exactly one terminal result", 1, completions);
    }

    @Test
    public void orderedFireWithARefusedCompletionIntentAnswersSynchronously() {
        String token = enable();
        Intent fire = new Intent(KeepADBTaskerPlugin.ACTION_FIRE_SETTING)
                .setComponent(new ComponentName(context, KeepADBPluginFireReceiver.class))
                .putExtra(KeepADBTaskerPlugin.EXTRA_BUNDLE, KeepADBTaskerPlugin.buildBundle(
                        KeepADBTaskerPlugin.Request.STATUS, token))
                .putExtra(KeepADBTaskerPlugin.EXTRA_COMPLETION_INTENT, TASKER_COMPLETION);
        AtomicReference<Integer> code = new AtomicReference<>();
        context.sendOrderedBroadcast(fire, null, new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                code.set(getResultCode());
            }
        }, null, Activity.RESULT_CANCELED, null, null);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(Integer.valueOf(KeepADBTaskerPlugin.RESULT_CODE_OK), code.get());
    }

    @Test
    public void completionIntentDropsGrantsSelectorAndClipData() {
        installReceiver("com.example.host", "com.example.host.Done", true, null);
        // launchFlags 0x43 = grant read + write + persistable URI permission.
        Intent completion = completionFor("intent:#Intent;action=x.y.DONE;launchFlags=0x43;"
                + "component=com.example.host/.Done;"
                + "SEL;action=android.intent.action.VIEW;end");
        assertNotNull(completion);
        assertEquals(0, completion.getFlags());
        assertNull(completion.getSelector());
        assertNull(completion.getClipData());
    }

    @Test
    public void fireReceiverSendsTheCompletionIntentToTheHost() {
        installHosts();
        String token = enable();
        Intent fire = new Intent(KeepADBTaskerPlugin.ACTION_FIRE_SETTING)
                .putExtra(KeepADBTaskerPlugin.EXTRA_BUNDLE, KeepADBTaskerPlugin.buildBundle(
                        KeepADBTaskerPlugin.Request.STATUS, token))
                .putExtra(KeepADBTaskerPlugin.EXTRA_COMPLETION_INTENT, MACRODROID_COMPLETION);
        new KeepADBPluginFireReceiver().onReceive(context, fire);
        Intent sent = null;
        for (Intent i : shadowOf((android.app.Application) context).getBroadcastIntents()) {
            if ("com.arlosoft.macrodroid.SETTING_COMPLETE".equals(i.getAction())) sent = i;
        }
        assertNotNull("completion broadcast", sent);
        assertEquals(KeepADBTaskerPlugin.RESULT_CODE_OK,
                sent.getIntExtra(KeepADBTaskerPlugin.EXTRA_RESULT_CODE, -99));
        assertEquals("ok", sent.getBundleExtra(KeepADBTaskerPlugin.EXTRA_VARIABLES)
                .getString(KeepADBTaskerPlugin.VAR_RESULT));
    }

    @Test
    public void fireReceiverReportsAFailureThroughTheCompletionIntentToo() {
        installHosts();
        enable();
        Intent fire = new Intent(KeepADBTaskerPlugin.ACTION_FIRE_SETTING)
                .putExtra(KeepADBTaskerPlugin.EXTRA_BUNDLE, KeepADBTaskerPlugin.buildBundle(
                        KeepADBTaskerPlugin.Request.STATUS, "wrong-token-wrong-token-wrong"))
                .putExtra(KeepADBTaskerPlugin.EXTRA_COMPLETION_INTENT, TASKER_COMPLETION);
        new KeepADBPluginFireReceiver().onReceive(context, fire);
        Intent sent = null;
        for (Intent i : shadowOf((android.app.Application) context).getBroadcastIntents()) {
            if ("net.dinglisch.android.tasker.ACTION_PLUGIN_FINISHED".equals(i.getAction())) {
                sent = i;
            }
        }
        assertNotNull("completion broadcast", sent);
        assertEquals(KeepADBTaskerPlugin.RESULT_CODE_FAILED,
                sent.getIntExtra(KeepADBTaskerPlugin.EXTRA_RESULT_CODE, -99));
        assertNotNull(sent.getBundleExtra(KeepADBTaskerPlugin.EXTRA_VARIABLES)
                .getString(KeepADBTaskerPlugin.VAR_ERROR_MESSAGE));
    }

    // ---- Condition edit screen --------------------------------------------------------------

    private ActivityController<KeepADBPluginConditionEditActivity> conditionScreen(String caller) {
        Intent intent = new Intent(context, KeepADBPluginConditionEditActivity.class)
                .setAction(KeepADBTaskerPlugin.ACTION_EDIT_CONDITION);
        ActivityController<KeepADBPluginConditionEditActivity> controller =
                Robolectric.buildActivity(KeepADBPluginConditionEditActivity.class, intent);
        shadowOf(controller.get()).setCallingPackage(caller);
        return controller.setup();
    }

    @Test
    public void conditionScreenOffersTheConditionsAndSavesOne() {
        String token = enable();
        KeepADBPluginConditionEditActivity activity = conditionScreen(TASKER).get();
        assertEquals(context.getString(R.string.plugin_condition_label),
                ((android.widget.TextView) activity.findViewById(R.id.plugin_title)).getText()
                        .toString());
        RadioGroup group = activity.findViewById(R.id.plugin_requests);
        assertEquals(KeepADBTaskerPlugin.Condition.values().length, group.getChildCount());
        ((RadioButton) group.getChildAt(1)).performClick();
        activity.findViewById(R.id.plugin_save).performClick();

        assertEquals(Activity.RESULT_OK, shadowOf(activity).getResultCode());
        Intent result = shadowOf(activity).getResultIntent();
        KeepADBTaskerPlugin.ConditionConfig saved = KeepADBTaskerPlugin.parseConditionBundle(
                result.getBundleExtra(KeepADBTaskerPlugin.EXTRA_BUNDLE));
        assertEquals(KeepADBTaskerPlugin.Condition.ENDPOINT_KNOWN, saved.condition);
        assertEquals(token, saved.token);
        assertEquals(context.getString(R.string.plugin_condition_endpoint_known),
                result.getStringExtra(KeepADBTaskerPlugin.EXTRA_BLURB));
    }

    @Test
    public void conditionScreenKeepsTheCallerChecks() {
        enable();
        KeepADBPluginConditionEditActivity none = conditionScreen(null).get();
        assertFalse(none.findViewById(R.id.plugin_save).isEnabled());
        assertEquals(context.getString(R.string.plugin_condition_caller_none),
                ((android.widget.TextView) none.findViewById(R.id.plugin_caller)).getText()
                        .toString());

        KeepADBPluginConditionEditActivity unknown = conditionScreen("com.example.unknown").get();
        unknown.findViewById(R.id.plugin_save).performClick();
        assertNotNull(unknown.getActiveDialog());
        assertEquals(Activity.RESULT_CANCELED, shadowOf(unknown).getResultCode());
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
        assertEquals(List.of(".KeepADBAutomationReceiver", ".KeepADBPluginConditionReceiver",
                ".KeepADBPluginFireReceiver"), exportedReceivers);

        String conditionReceiver = element(manifest, "receiver", ".KeepADBPluginConditionReceiver");
        assertFalse(conditionReceiver.contains("android:permission"));
        assertArrayEquals(new String[] {KeepADBTaskerPlugin.ACTION_QUERY_CONDITION},
                actions(conditionReceiver).toArray(new String[0]));
        String conditionActivity =
                element(manifest, "activity", ".KeepADBPluginConditionEditActivity");
        assertTrue(conditionActivity.contains("android:exported=\"true\""));
        assertArrayEquals(new String[] {KeepADBTaskerPlugin.ACTION_EDIT_CONDITION},
                actions(conditionActivity).toArray(new String[0]));
        assertTrue("REQUEST_QUERY must name the condition's edit activity",
                KeepADBTaskerPlugin.requestQueryIntent()
                        .getStringExtra(KeepADBTaskerPlugin.EXTRA_ACTIVITY)
                        .endsWith(".KeepADBPluginConditionEditActivity"));
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

        KeepADBEndpointCoordinator.Snapshot endpoint =
                new KeepADBEndpointCoordinator.Snapshot("192.168.1.20", 37000, 1L);

        @Override
        public KeepADBEndpointCoordinator.Snapshot endpoint() {
            return endpoint;
        }

        @Override
        public void refreshSurfaces(Context app) {
        }
    }
}
