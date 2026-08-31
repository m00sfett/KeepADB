package de.hohnepeople.keepadb;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Listens for device boot and package replacement and initiates keep-alive monitoring. */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        boolean bootCompleted = Intent.ACTION_BOOT_COMPLETED.equals(action);
        boolean packageReplaced = Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
        if (!bootCompleted && !packageReplaced) return;

        KeepADBUsbReceiver.refresh(context);
        boolean keepAlive = KeepADBPreferences.isKeepAliveEnabled(context);
        String event = packageReplaced ? "package_replaced" : "boot_completed";
        String recoveryEvent = packageReplaced ? "package_recovery" : "boot_recovery";
        KeepADBDiagnostics.event(context, event, "system", "received", "keepAlive=" + keepAlive);
        if (keepAlive) {
            if (!KeepADBService.start(context)) {
                KeepADBDiagnostics.event(context, recoveryEvent, "boot_receiver", "failed", "service_start");
                Log.e(TAG, "BootReceiver: Failed to start KeepADB foreground service");
                return;
            }
            KeepADBDiagnostics.event(context, recoveryEvent, "boot_receiver", "success", "service_start");
        }
    }
}
