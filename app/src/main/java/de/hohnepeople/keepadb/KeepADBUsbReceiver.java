package de.hohnepeople.keepadb;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

/** Receives Android's USB state broadcast and filters it to configured USB-ADB links. */
public final class KeepADBUsbReceiver extends BroadcastReceiver {
    static final String ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE";
    private static final String EXTRA_CONNECTED = "connected";
    private static final String EXTRA_CONFIGURED = "configured";
    private static final String EXTRA_ADB = "adb";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_USB_STATE.equals(intent.getAction())) return;
        refresh(context, isUsbAdbConnected(intent));
    }

    static void refresh(Context context) {
        IntentFilter filter = new IntentFilter(ACTION_USB_STATE);
        Intent state = context.registerReceiver(null, filter);
        refresh(context, isUsbAdbConnected(state));
    }

    private static void refresh(Context context, boolean connected) {
        KeepADBUsbNotification.refresh(context, connected);
    }

    private static boolean isUsbAdbConnected(Intent intent) {
        return intent != null
                && intent.getBooleanExtra(EXTRA_CONNECTED, false)
                && intent.getBooleanExtra(EXTRA_CONFIGURED, false)
                && intent.getBooleanExtra(EXTRA_ADB, false);
    }
}
