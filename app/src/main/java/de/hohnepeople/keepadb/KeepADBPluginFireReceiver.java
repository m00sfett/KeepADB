package de.hohnepeople.keepadb;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * #999: the exported "fire" entry point of the Tasker/Locale action plugin. Like
 * {@link KeepADBAutomationReceiver} it decides nothing itself: {@link KeepADBTaskerPlugin#fire}
 * validates the bundle and hands the request to {@link KeepADBAutomation#process}. On an ordered
 * broadcast (Tasker's synchronous execution) it returns the result code and the variables.
 */
public class KeepADBPluginFireReceiver extends BroadcastReceiver {
    private static final String TAG = "KeepADBPlugin";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !KeepADBTaskerPlugin.ACTION_FIRE_SETTING.equals(intent.getAction())) {
            return;
        }
        try {
            Bundle bundle = intent.getBundleExtra(KeepADBTaskerPlugin.EXTRA_BUNDLE);
            KeepADBTaskerPlugin.Outcome outcome =
                    KeepADBTaskerPlugin.fire(context.getApplicationContext(), bundle);
            if (isOrderedBroadcast()) {
                setResultCode(outcome.resultCode);
                getResultExtras(true).putBundle(KeepADBTaskerPlugin.EXTRA_VARIABLES,
                        outcome.variables);
            }
        } catch (RuntimeException e) {
            // A foreign sender can attach extras that fail to unparcel; that must never crash
            // the app. The message is not logged: it could echo the sender's extras.
            Log.w(TAG, "Plugin request failed: " + e.getClass().getSimpleName());
        }
    }
}
