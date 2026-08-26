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
            KeepADBDiagnostics.event(context, "boot_completed", "system", "received", "keepAlive="
                    + KeepADBPreferences.isKeepAliveEnabled(context));
            if (KeepADBPreferences.isKeepAliveEnabled(context)) {
                if (!KeepADBService.start(context)) {
                    KeepADBDiagnostics.event(context, "boot_recovery", "boot_receiver", "failed", "service_start");
                    Log.e(TAG, "BootReceiver: Failed to start KeepADB foreground service");
                    return;
                }
                KeepADBDiagnostics.event(context, "boot_recovery", "boot_receiver", "success", "service_start");
            }
        }
    }
}
