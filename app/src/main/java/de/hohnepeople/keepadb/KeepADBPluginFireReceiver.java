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
        // #1026: hosts that pass a completion intent (Tasker with a timeout, MacroDroid) get the
        // outcome there, and an ordered broadcast only RESULT_CODE_PENDING, so there is exactly
        // one terminal result (Tasker's protocol). Hosts without one (Automate) get the ordered
        // result as before.
        Intent completion = KeepADBTaskerPlugin.completionIntent(
                context.getApplicationContext(), intent, outcome);
        if (completion == null) {
            KeepADBTaskerPlugin.publish(this, outcome);
            return;
        }
        if (isOrderedBroadcast()) {
            // Finish the ordered broadcast first, so the PENDING reply is on its way before the
            // completion that ends the action.
            PendingResult pending = goAsync();
            pending.setResultCode(KeepADBTaskerPlugin.RESULT_CODE_PENDING);
            pending.finish();
        }
        try {
            context.sendBroadcast(completion);
        } catch (RuntimeException e) {
            Log.w(TAG, "Completion broadcast failed: " + e.getClass().getSimpleName());
        }
    }
}
