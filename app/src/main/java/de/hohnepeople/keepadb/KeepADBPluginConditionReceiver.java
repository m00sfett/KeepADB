package de.hohnepeople.keepadb;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
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
        try {
            Bundle bundle = intent.getBundleExtra(KeepADBTaskerPlugin.EXTRA_BUNDLE);
            KeepADBTaskerPlugin.Outcome outcome =
                    KeepADBTaskerPlugin.query(context.getApplicationContext(), bundle);
            if (isOrderedBroadcast()) {
                setResultCode(outcome.resultCode);
                if (outcome.variables != null) {
                    getResultExtras(true).putBundle(KeepADBTaskerPlugin.EXTRA_VARIABLES,
                            outcome.variables);
                }
            }
        } catch (RuntimeException e) {
            // A foreign sender can attach extras that fail to unparcel; never crash. The message
            // is not logged: it could echo the sender's extras.
            Log.w(TAG, "Plugin query failed: " + e.getClass().getSimpleName());
            if (isOrderedBroadcast()) setResultCode(KeepADBTaskerPlugin.RESULT_CONDITION_UNKNOWN);
        }
    }
}
