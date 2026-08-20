package de.moos.wifiadb;

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
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            if (AdbWifiPreferences.isKeepAliveEnabled(context)) {
                AdbWifiService.start(context);
                if (AdbWifiService.isWifiConnected(context)) {
                    if (!AdbWifi.setEnabled(context, true)) {
                        Log.e(TAG, "BootReceiver: Failed to enable WLAN-ADB (WRITE_SECURE_SETTINGS missing?)");
                        AdbWifiNotification.showPermissionMissing(context);
                        return;
                    }
                }
                AdbWifiNotification.refresh(context);
                AdbWifiWidget.refreshAll(context);
            }
        }
    }
}
