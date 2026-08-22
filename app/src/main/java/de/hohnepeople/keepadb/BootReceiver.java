package de.hohnepeople.keepadb;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Listens for device boot and initiates keep-alive monitoring and auto-re-enable. */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            if (KeepADBPreferences.isKeepAliveEnabled(context)) {
                if (!KeepADBService.start(context)) {
                    Log.e(TAG, "BootReceiver: Failed to start KeepADB foreground service");
                    return;
                }
            }
        }
    }
}
