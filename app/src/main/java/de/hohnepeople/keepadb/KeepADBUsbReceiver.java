package de.hohnepeople.keepadb;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

/** Receives Android's USB state broadcast and filters it to configured USB-ADB links. */
public final class KeepADBUsbReceiver extends BroadcastReceiver {
    static final String ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE";
    // #168: internal-only action for the USB notification's MANUAL "Enable WLAN-ADB" button,
    // dispatched via an explicit PendingIntent.getBroadcast (like KeepADBWidget's TOGGLE action).
    static final String ACTION_HANDOVER_ENABLE = "de.hohnepeople.keepadb.USB_HANDOVER_ENABLE";
    private static final String EXTRA_CONNECTED = "connected";
    private static final String EXTRA_CONFIGURED = "configured";
    private static final String EXTRA_ADB = "adb";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (ACTION_HANDOVER_ENABLE.equals(action)) {
            boolean success = KeepADBUsbHandover.handleManualAction(context);
            KeepADBUsbNotification.reportManualActionResult(context, success);
            return;
        }
        if (!ACTION_USB_STATE.equals(action)) return;
        boolean connected = isUsbAdbConnected(intent);
        // Real broadcast edge: the only place allowed to feed the AUTOMATIC-mode connect-edge
        // tracker. KeepADBUsbReceiver.refresh(Context) below re-derives `connected` from a sticky
        // broadcast query for UI-triggered re-renders (profile create/switch/edit) and must NOT
        // reach onRawUsbBroadcast, or an unrelated profile edit while still connected would look
        // like a fresh connect and could re-fire automatic mode.
        KeepADBUsbHandover.onRawUsbBroadcast(context, connected);
        refresh(context, connected);
    }

    static void refresh(Context context) {
        if (context == null) return;
        try {
            IntentFilter filter = new IntentFilter(ACTION_USB_STATE);
            Intent state = context.registerReceiver(null, filter);
            refresh(context, isUsbAdbConnected(state));
        } catch (Exception e) {
            refresh(context, false);
        }
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
