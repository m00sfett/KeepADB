package de.hohnepeople.keepadb;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * #999: the exported "query" entry point of the Tasker/Locale condition plugin. It decides
 * nothing itself: {@link KeepADBTaskerPlugin#query} validates the bundle and reads the state
 * through {@link KeepADBAutomation#process}. The host sends an ordered broadcast and reads the
 * satisfied/unsatisfied/unknown result code and the variables.
 */
public class KeepADBPluginConditionReceiver extends BroadcastReceiver {
    private static final String TAG = "KeepADBPlugin";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null
                || !KeepADBTaskerPlugin.ACTION_QUERY_CONDITION.equals(intent.getAction())) {
            return;
        }
        KeepADBTaskerPlugin.Outcome outcome;
        try {
            outcome = KeepADBTaskerPlugin.queryFromIntent(context.getApplicationContext(), intent);
        } catch (RuntimeException e) {
            // Never crash; the message is not logged: it could echo the sender's extras.
            Log.w(TAG, "Plugin query failed: " + e.getClass().getSimpleName());
            outcome = new KeepADBTaskerPlugin.Outcome(
                    KeepADBTaskerPlugin.RESULT_CONDITION_UNKNOWN, null);
        }
        if (isOrderedBroadcast()) {
            setResultCode(outcome.resultCode);
            if (outcome.variables != null) {
                getResultExtras(true).putBundle(KeepADBTaskerPlugin.EXTRA_VARIABLES,
                        outcome.variables);
            }
        }
    }
}
