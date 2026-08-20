package de.moos.wifiadb;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Listens for device boot and initiates keep-alive monitoring and auto-re-enable. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            if (AdbWifiPreferences.isKeepAliveEnabled(context)) {
                AdbWifiService.start(context);
                if (AdbWifiService.isWifiConnected(context)) {
                    AdbWifi.setEnabled(context, true);
                }
                AdbWifiNotification.refresh(context);
                AdbWifiWidget.refreshAll(context);
            }
        }
    }
}
