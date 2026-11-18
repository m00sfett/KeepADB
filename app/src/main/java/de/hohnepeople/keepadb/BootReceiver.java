package de.hohnepeople.keepadb;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Listens for device boot and package replacement and initiates keep-alive monitoring. It also
 * re-evaluates the force mode (#763) after a restart, an app update and a clock change: alarms do
 * not survive a reboot or an update, and a clock set moves the effective deadline.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_TIME_CHANGED.equals(action)) {
            KeepADBForceMode.restore(context);
            KeepADBTimer.restore(context);
            return;
        }
        boolean bootCompleted = Intent.ACTION_BOOT_COMPLETED.equals(action);
        boolean packageReplaced = Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
        if (!bootCompleted && !packageReplaced) return;

        // #778: the old observation history is dropped once, on the first update or boot.
        KeepADBBssidHistory.discardLegacyOnce(context);
        // #763: independent of Keep-Alive and before it, so an expiry that happened while the
        // device was off is reported even when nothing else starts.
        KeepADBForceMode.restore(context);
        // #1000: before Keep-Alive is looked at. A timer whose time ran out while the device was
        // off (or before this broadcast arrived) is finished here, which records "off" as the
        // last intent, so Keep-Alive below finds nothing to restore. A running timer makes
        // Keep-Alive effective, so the service below restores Wifi-ADB for the time left.
        KeepADBTimer.restore(context);
        boolean keepAlive = KeepADBPreferences.isKeepAliveEffective(context);
        String event = packageReplaced ? "package_replaced" : "boot_completed";
        String recoveryEvent = packageReplaced ? "package_recovery" : "boot_recovery";
        KeepADBDiagnostics.event(context, event, "system", "received", "keepAlive=" + keepAlive);
        if (keepAlive) {
            if (!KeepADBService.shouldRun(context)) {
                KeepADBDiagnostics.event(context, recoveryEvent, "boot_receiver", "skipped", "persisted_intent_off");
                return;
            }
            if (!KeepADBService.start(context)) {
                KeepADBDiagnostics.event(context, recoveryEvent, "boot_receiver", "failed", "service_start");
                Log.e(TAG, "BootReceiver: Failed to start KeepADB foreground service");
                return;
            }
            KeepADBDiagnostics.event(context, recoveryEvent, "boot_receiver", "success", "service_start");
        }
    }
}
