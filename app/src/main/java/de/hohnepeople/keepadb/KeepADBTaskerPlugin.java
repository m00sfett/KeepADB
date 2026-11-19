package de.hohnepeople.keepadb;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import java.util.Set;

/**
 * #999: the Locale/Tasker plugin protocol on top of the automation interface (#998). The plugin
 * is a second way to build the same requests: its configuration bundle names one request and
 * carries the access token, and {@link KeepADBPluginFireReceiver} hands both to
 * {@link KeepADBAutomation#process}, so the opt-in, the token check and the network protection are
 * decided in exactly one place.
 *
 * <p>The protocol constants are those of the public Locale plugin API and the Tasker plugin
 * extensions ({@code TaskerPlugin.java}); they are copied here instead of adding a library.
 *
 * <p>Maintainer decisions of 2026-11-19 (#999): the token is stored in the host's configuration
 * in clear text and the edit screen says so; the edit screen names the app it saves for and asks
 * again for an app outside {@link #KNOWN_HOSTS}; the plugin runs synchronously and returns
 * variables; the address and port are returned only with a valid token, like the B1a reply.
 */
final class KeepADBTaskerPlugin {
    private KeepADBTaskerPlugin() {}

    // ---- Locale plugin API ----------------------------------------------------------------
    static final String ACTION_EDIT_SETTING = "com.twofortyfouram.locale.intent.action.EDIT_SETTING";
    static final String ACTION_FIRE_SETTING = "com.twofortyfouram.locale.intent.action.FIRE_SETTING";
    static final String EXTRA_BUNDLE = "com.twofortyfouram.locale.intent.extra.BUNDLE";
    static final String EXTRA_BLURB = "com.twofortyfouram.locale.intent.extra.BLURB";
    static final String ACTION_EDIT_CONDITION =
            "com.twofortyfouram.locale.intent.action.EDIT_CONDITION";
    static final String ACTION_QUERY_CONDITION =
            "com.twofortyfouram.locale.intent.action.QUERY_CONDITION";
    /** Asks the host to query a condition again now; carries no data. */
    static final String ACTION_REQUEST_QUERY = "com.twofortyfouram.locale.intent.action.REQUEST_QUERY";
    /** On {@link #ACTION_REQUEST_QUERY}: the class name of the condition's edit activity. */
    static final String EXTRA_ACTIVITY = "com.twofortyfouram.locale.intent.extra.ACTIVITY";
    static final int RESULT_CONDITION_SATISFIED = 16;
    static final int RESULT_CONDITION_UNSATISFIED = 17;
    static final int RESULT_CONDITION_UNKNOWN = 18;

    // ---- Tasker plugin extensions ---------------------------------------------------------
    static final String EXTRA_HOST_CAPABILITIES =
            "net.dinglisch.android.tasker.extras.HOST_CAPABILITIES";
    static final int CAPABILITY_SETTING_RETURN_VARIABLES = 2;
    static final int CAPABILITY_RELEVANT_VARIABLES = 16;
    static final int CAPABILITY_SETTING_SYNCHRONOUS_EXECUTION = 32;
    /** On the intent returned by the edit screen: the variables the action can set. */
    static final String EXTRA_RELEVANT_VARIABLES = "net.dinglisch.android.tasker.RELEVANT_VARIABLES";
    /** In the result extras of the fire broadcast: the variables and their values. */
    static final String EXTRA_VARIABLES = "net.dinglisch.android.tasker.extras.VARIABLES";
    static final int RESULT_CODE_OK = Activity.RESULT_OK;
    static final int RESULT_CODE_FAILED = Activity.RESULT_FIRST_USER + 1;

    static final String VAR_RESULT = "%kadb_result";
    static final String VAR_STATE = "%kadb_state";
    static final String VAR_KEEP_ALIVE = "%kadb_keep_alive";
    static final String VAR_IP = "%kadb_ip";
    static final String VAR_PORT = "%kadb_port";
    /** Tasker shows this one as the error text of a failed action. */
    static final String VAR_ERROR_MESSAGE = "%errmsg";

    /** {@link #VAR_RESULT} values beyond the B1a results. */
    static final String RESULT_DISABLED = "disabled";
    static final String RESULT_INVALID_CONFIG = "invalid_config";
    private static final String RESULT_VALUES = "ok, rejected_token, rejected_network, "
            + "failed_permission, failed, " + RESULT_DISABLED + ", " + RESULT_INVALID_CONFIG;

    /**
     * Automation apps whose package name is trusted without a second question (Q9). Any other
     * caller can still save, after a warning that it receives the token.
     */
    static final Set<String> KNOWN_HOSTS = Set.of(
            "net.dinglisch.android.taskerm",
            "com.arlosoft.macrodroid",
            "com.llamalab.automate",
            "com.twofortyfouram.locale");

    // ---- Configuration bundle -------------------------------------------------------------
    private static final String BUNDLE_KEY_PREFIX = "de.hohnepeople.keepadb.plugin.";
    static final String BUNDLE_KEY_VERSION = BUNDLE_KEY_PREFIX + "VERSION";
    static final String BUNDLE_KEY_REQUEST = BUNDLE_KEY_PREFIX + "REQUEST";
    static final String BUNDLE_KEY_TOKEN = BUNDLE_KEY_PREFIX + "TOKEN";
    /** Only in a condition bundle, instead of {@link #BUNDLE_KEY_REQUEST}. */
    static final String BUNDLE_KEY_CONDITION = BUNDLE_KEY_PREFIX + "CONDITION";
    static final int BUNDLE_VERSION = 1;
    /** Far above the 43 characters of a real token; anything longer is not a token. */
    static final int MAX_TOKEN_LENGTH = 128;

    /** The requests the action plugin offers, in the order of the edit screen. */
    enum Request {
        WIFI_ADB_ON(KeepADBAutomation.ACTION_WIFI_ADB_ON, R.string.plugin_request_wifi_adb_on,
                true),
        WIFI_ADB_OFF(KeepADBAutomation.ACTION_WIFI_ADB_OFF, R.string.plugin_request_wifi_adb_off,
                false),
        WIFI_ADB_TOGGLE(KeepADBAutomation.ACTION_WIFI_ADB_TOGGLE,
                R.string.plugin_request_wifi_adb_toggle, true),
        KEEP_ALIVE_ON(KeepADBAutomation.ACTION_KEEP_ALIVE_ON, R.string.plugin_request_keep_alive_on,
                true),
        KEEP_ALIVE_OFF(KeepADBAutomation.ACTION_KEEP_ALIVE_OFF,
                R.string.plugin_request_keep_alive_off, false),
        /** With the token the status also returns the address and port. */
        STATUS(KeepADBAutomation.ACTION_STATUS, R.string.plugin_request_status, true);

        /** The B1a broadcast action the request maps to. */
        final String action;
        final int label;
        /**
         * Whether the saved configuration carries the token. The two "off" requests need none,
         * so they leave it out: less to leak, and they keep working after a new token.
         */
        final boolean storesToken;

        Request(String action, int label, boolean storesToken) {
            this.action = action;
            this.label = label;
            this.storesToken = storesToken;
        }

        /** The stable id stored in the bundle, the B1a request name (for example "WIFI_ADB_ON"). */
        String id() {
            return KeepADBAutomation.requestName(action);
        }

        static Request fromId(String id) {
            if (id == null) return null;
            for (Request request : values()) {
                if (request.id().equals(id)) return request;
            }
            return null;
        }
    }

    /** One validated configuration: the request and the token saved with it. */
    static final class Config {
        final Request request;
        /** May be null for a request that needs no token. */
        final String token;

        Config(Request request, String token) {
            this.request = request;
            this.token = token;
        }
    }

    static Bundle buildBundle(Request request, String token) {
        Bundle bundle = new Bundle();
        bundle.putInt(BUNDLE_KEY_VERSION, BUNDLE_VERSION);
        bundle.putString(BUNDLE_KEY_REQUEST, request.id());
        if (token != null && request.storesToken) bundle.putString(BUNDLE_KEY_TOKEN, token);
        return bundle;
    }

    /**
     * Reads a configuration bundle from a host. Returns {@code null} for anything that is not a
     * bundle this version wrote: a missing or newer version, an unknown request, a token of the
     * wrong type or length. Keys of other owners are ignored (hosts may add their own).
     */
    static Config parseBundle(Bundle bundle) {
        if (bundle == null) return null;
        try {
            Object version = bundle.get(BUNDLE_KEY_VERSION);
            if (!(version instanceof Integer) || (Integer) version != BUNDLE_VERSION) return null;
            Object requestId = bundle.get(BUNDLE_KEY_REQUEST);
            if (!(requestId instanceof String)) return null;
            Request request = Request.fromId((String) requestId);
            if (request == null) return null;
            Object token = bundle.get(BUNDLE_KEY_TOKEN);
            if (!tokenShapeValid(token)) return null;
            return new Config(request, (String) token);
        } catch (RuntimeException e) {
            // A foreign bundle can fail to unparcel (unknown Parcelable class, bad data).
            return null;
        }
    }

    /** No token at all, or a non-empty string of at most {@link #MAX_TOKEN_LENGTH} characters. */
    private static boolean tokenShapeValid(Object token) {
        if (token == null) return true;
        if (!(token instanceof String)) return false;
        String text = (String) token;
        return !text.isEmpty() && text.length() <= MAX_TOKEN_LENGTH;
    }

    /** The short text the host shows under the action. */
    static String blurb(Context context, Request request) {
        return context.getString(request.label);
    }

    /** Tasker's list of the variables the action sets: "%name\nlabel\ndescription" per entry. */
    static String[] relevantVariables(Context context) {
        return new String[] {
                // The description lists the literal values, the same in every language.
                VAR_RESULT + "\n" + context.getString(R.string.plugin_var_result) + "\n"
                        + RESULT_VALUES,
                VAR_STATE + "\n" + context.getString(R.string.plugin_var_state) + "\n",
                VAR_KEEP_ALIVE + "\n" + context.getString(R.string.plugin_var_keep_alive) + "\n",
                VAR_IP + "\n" + context.getString(R.string.plugin_var_ip) + "\n"
                        + context.getString(R.string.plugin_var_endpoint_description),
                VAR_PORT + "\n" + context.getString(R.string.plugin_var_port) + "\n"
                        + context.getString(R.string.plugin_var_endpoint_description),
        };
    }

    /** The result intent of the edit screen for a saved configuration. */
    static Intent resultIntent(Context context, Request request, String token) {
        Intent result = new Intent();
        result.putExtra(EXTRA_BUNDLE, buildBundle(request, token));
        result.putExtra(EXTRA_BLURB, blurb(context, request));
        result.putExtra(EXTRA_RELEVANT_VARIABLES, relevantVariables(context));
        return result;
    }

    /** What the fire receiver returns to the host for one request. */
    static final class Outcome {
        final int resultCode;
        final Bundle variables;

        Outcome(int resultCode, Bundle variables) {
            this.resultCode = resultCode;
            this.variables = variables;
        }
    }

    /**
     * Runs one fire broadcast through the B1a decision. An invalid bundle and a disabled
     * interface do nothing and return only the failure; everything else returns what B1a would
     * reply, as variables.
     */
    static Outcome fire(Context app, Bundle bundle) {
        Config config = parseBundle(bundle);
        if (config == null) {
            // Throttled like every untokened request: any app can send a broken bundle in a loop.
            KeepADBAutomation.journal(app, "ignored", "reason=invalid_plugin_bundle", true);
            return failure(RESULT_INVALID_CONFIG);
        }
        KeepADBAutomation.Response response =
                KeepADBAutomation.process(app, config.request.action, config.token);
        if (response == null) return failure(RESULT_DISABLED);
        return outcome(response);
    }

    static Outcome outcome(KeepADBAutomation.Response response) {
        Bundle variables = new Bundle();
        variables.putString(VAR_RESULT, response.result.value);
        variables.putString(VAR_STATE, response.wifiAdb ? "on" : "off");
        variables.putString(VAR_KEEP_ALIVE, response.keepAlive ? "on" : "off");
        if (response.hasEndpoint()) {
            variables.putString(VAR_IP, response.host);
            variables.putString(VAR_PORT, String.valueOf(response.port));
        }
        boolean ok = response.result == KeepADBAutomation.Result.OK;
        if (!ok) variables.putString(VAR_ERROR_MESSAGE, response.result.value);
        return new Outcome(ok ? RESULT_CODE_OK : RESULT_CODE_FAILED, variables);
    }

    // ---- Condition plugin -----------------------------------------------------------------

    /**
     * The conditions the plugin offers. Both read only the on/off state and whether an endpoint
     * is known, which need no token (decision Q5); the saved token only adds the address and port
     * to the returned variables.
     */
    enum Condition {
        WIFI_ADB_ON("WIFI_ADB_ON", R.string.plugin_condition_wifi_adb_on),
        ENDPOINT_KNOWN("ENDPOINT_KNOWN", R.string.plugin_condition_endpoint_known);

        final String id;
        final int label;

        Condition(String id, int label) {
            this.id = id;
            this.label = label;
        }

        static Condition fromId(String id) {
            if (id == null) return null;
            for (Condition condition : values()) {
                if (condition.id.equals(id)) return condition;
            }
            return null;
        }

        boolean isMet(boolean wifiAdb, boolean endpointKnown) {
            return this == WIFI_ADB_ON ? wifiAdb : wifiAdb && endpointKnown;
        }
    }

    /** One validated condition configuration. */
    static final class ConditionConfig {
        final Condition condition;
        /** May be null: the condition then returns no address and port. */
        final String token;

        ConditionConfig(Condition condition, String token) {
            this.condition = condition;
            this.token = token;
        }
    }

    static Bundle buildConditionBundle(Condition condition, String token) {
        Bundle bundle = new Bundle();
        bundle.putInt(BUNDLE_KEY_VERSION, BUNDLE_VERSION);
        bundle.putString(BUNDLE_KEY_CONDITION, condition.id);
        if (token != null) bundle.putString(BUNDLE_KEY_TOKEN, token);
        return bundle;
    }

    /** Like {@link #parseBundle}, for a condition bundle. */
    static ConditionConfig parseConditionBundle(Bundle bundle) {
        if (bundle == null) return null;
        try {
            Object version = bundle.get(BUNDLE_KEY_VERSION);
            if (!(version instanceof Integer) || (Integer) version != BUNDLE_VERSION) return null;
            Object id = bundle.get(BUNDLE_KEY_CONDITION);
            if (!(id instanceof String)) return null;
            Condition condition = Condition.fromId((String) id);
            if (condition == null) return null;
            Object token = bundle.get(BUNDLE_KEY_TOKEN);
            if (!tokenShapeValid(token)) return null;
            return new ConditionConfig(condition, (String) token);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static Intent conditionResultIntent(Context context, Condition condition, String token) {
        Intent result = new Intent();
        result.putExtra(EXTRA_BUNDLE, buildConditionBundle(condition, token));
        result.putExtra(EXTRA_BLURB, context.getString(condition.label));
        result.putExtra(EXTRA_RELEVANT_VARIABLES, relevantVariables(context));
        return result;
    }

    /**
     * Answers one condition query through the B1a status decision. Unknown while the interface
     * is off or for an invalid bundle, so the host keeps its last state; otherwise satisfied or
     * not, with the status variables. A token that is no longer valid is counted like any wrong
     * token and only drops the address and port: the condition itself needs none.
     */
    static Outcome query(Context app, Bundle bundle) {
        ConditionConfig config = parseConditionBundle(bundle);
        if (config == null) {
            KeepADBAutomation.journal(app, "ignored", "reason=invalid_plugin_bundle", true);
            return new Outcome(RESULT_CONDITION_UNKNOWN, null);
        }
        KeepADBAutomation.Response response = KeepADBAutomation.process(app,
                KeepADBAutomation.ACTION_STATUS, config.token, true);
        if (response == null) return new Outcome(RESULT_CONDITION_UNKNOWN, null);
        boolean met = config.condition.isMet(response.wifiAdb, response.endpointKnown);
        Bundle variables = outcome(response).variables;
        // The variables describe the state; the condition answer is the result code.
        variables.remove(VAR_ERROR_MESSAGE);
        return new Outcome(met ? RESULT_CONDITION_SATISFIED : RESULT_CONDITION_UNSATISFIED,
                variables);
    }

    // ---- REQUEST_QUERY --------------------------------------------------------------------

    private static final Object QUERY_LOCK = new Object();
    /** The last pair announced, {@code null} before the first: {wifiAdb, endpointKnown}. */
    private static boolean[] lastAnnounced;

    /**
     * Tells the automation apps to query the KeepADB conditions again when what they answer
     * changed. Called on every surface refresh; it sends nothing while the interface is off, and
     * nothing when Wifi-ADB and the endpoint are as last announced. The broadcast carries no data,
     * only the name of the condition's edit activity, as the Locale API defines.
     */
    static void requestQueryIfChanged(Context context) {
        Context app = context.getApplicationContext();
        if (!KeepADBAutomation.isEnabled(app)) {
            synchronized (QUERY_LOCK) {
                lastAnnounced = null;
            }
            return;
        }
        boolean[] state = KeepADBAutomation.conditionState(app);
        synchronized (QUERY_LOCK) {
            if (lastAnnounced != null && lastAnnounced[0] == state[0]
                    && lastAnnounced[1] == state[1]) {
                return;
            }
            lastAnnounced = state;
        }
        app.sendBroadcast(requestQueryIntent());
    }

    /**
     * The interface was switched on or off (Codex review of PR #1025): forget the last
     * announcement, so switching on announces the current state at once (a host that got
     * "unknown" meanwhile queries again) and a later switch-on never compares against a stale
     * pair. Switching off sends nothing.
     */
    static void onInterfaceSwitched(Context context) {
        synchronized (QUERY_LOCK) {
            lastAnnounced = null;
        }
        requestQueryIfChanged(context);
    }

    // ---- Receiver entry points ------------------------------------------------------------

    /**
     * The fire receiver's work, safe against any intent: an extra that fails to unparcel is a
     * failure with {@link #RESULT_INVALID_CONFIG}, never the host's initial result code (Codex
     * review of PR #1025: an ordered broadcast started with RESULT_OK reported success).
     */
    static Outcome fireFromIntent(Context app, Intent intent) {
        Bundle bundle;
        try {
            bundle = intent.getBundleExtra(EXTRA_BUNDLE);
        } catch (RuntimeException e) {
            KeepADBAutomation.journal(app, "ignored", "reason=invalid_plugin_bundle", true);
            return failure(RESULT_INVALID_CONFIG);
        }
        return fire(app, bundle);
    }

    /** Like {@link #fireFromIntent}: a broken intent is an unknown condition. */
    static Outcome queryFromIntent(Context app, Intent intent) {
        Bundle bundle;
        try {
            bundle = intent.getBundleExtra(EXTRA_BUNDLE);
        } catch (RuntimeException e) {
            KeepADBAutomation.journal(app, "ignored", "reason=invalid_plugin_bundle", true);
            return new Outcome(RESULT_CONDITION_UNKNOWN, null);
        }
        return query(app, bundle);
    }

    /**
     * Hands {@code outcome} back to the sender of an ordered broadcast. The initial result
     * extras belong to that sender and may fail to unparcel on first access; they are then
     * replaced by fresh extras that carry only our variables, so the receiver never crashes and
     * the outcome still arrives (Codex review of PR #1025).
     */
    static void publish(BroadcastReceiver receiver, Outcome outcome) {
        if (!receiver.isOrderedBroadcast()) {
            return;
        }
        receiver.setResultCode(outcome.resultCode);
        if (outcome.variables == null) {
            return;
        }
        try {
            receiver.getResultExtras(true).putBundle(EXTRA_VARIABLES, outcome.variables);
        } catch (RuntimeException e) {
            // The message is not logged: it could echo the sender's extras.
            Log.w("KeepADBPlugin", "Replacing unreadable result extras: "
                    + e.getClass().getSimpleName());
            Bundle fresh = new Bundle();
            fresh.putBundle(EXTRA_VARIABLES, outcome.variables);
            receiver.setResultExtras(fresh);
        }
    }

    static Intent requestQueryIntent() {
        return new Intent(ACTION_REQUEST_QUERY)
                .putExtra(EXTRA_ACTIVITY, KeepADBPluginConditionEditActivity.class.getName());
    }

    static void resetQueryStateForTesting() {
        synchronized (QUERY_LOCK) {
            lastAnnounced = null;
        }
    }

    private static Outcome failure(String result) {
        Bundle variables = new Bundle();
        variables.putString(VAR_RESULT, result);
        variables.putString(VAR_ERROR_MESSAGE, result);
        return new Outcome(RESULT_CODE_FAILED, variables);
    }
}
