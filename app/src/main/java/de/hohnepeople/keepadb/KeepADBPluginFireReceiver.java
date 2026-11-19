package de.hohnepeople.keepadb;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
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
        KeepADBTaskerPlugin.Outcome outcome;
        try {
            outcome = KeepADBTaskerPlugin.fireFromIntent(context.getApplicationContext(), intent);
        } catch (RuntimeException e) {
            // Must never crash the app, and never leave the host's initial result code (which
            // may be RESULT_OK) in place. The message is not logged: it could echo the extras.
            Log.w(TAG, "Plugin request failed: " + e.getClass().getSimpleName());
            outcome = new KeepADBTaskerPlugin.Outcome(KeepADBTaskerPlugin.RESULT_CODE_FAILED,
                    null);
        }
        KeepADBTaskerPlugin.publish(this, outcome);
    }
}
