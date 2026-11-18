package de.hohnepeople.keepadb;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * #998: the one exported entry point of the automation interface. Any installed app can send to
 * it, so it decides nothing itself: {@link KeepADBAutomation} checks the opt-in, the token and the
 * network protection. Every other receiver of the app stays non-exported.
 */
public class KeepADBAutomationReceiver extends BroadcastReceiver {
    private static final String TAG = "KeepADBAutomation";

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            KeepADBAutomation.handle(context, intent);
        } catch (RuntimeException e) {
            // A foreign sender can attach extras that fail to unparcel; that must never crash
            // the app. The message is not logged: it could echo the sender's extras.
            Log.w(TAG, "Automation request failed: " + e.getClass().getSimpleName());
        }
    }
}
