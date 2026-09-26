package de.hohnepeople.keepadb;

import android.app.KeyguardManager;
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
            handleHandoverEnableAction(context);
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

    /**
     * #588: the MANUAL "Enable WLAN-ADB" notification action. Switching Wireless Debugging on
     * requires an unlocked device, like the trust action (#578) and the tile (#586).
     *
     * @return true if Wireless Debugging was actually turned on by this call.
     */
    static boolean handleHandoverEnableAction(Context context) {
        // Defense in depth: KeepADBUsbNotification#handoverAction also sets
        // setAuthenticationRequired (API 31+), but that is enforced by SystemUI, not by this app,
        // does not exist below API 31 (minSdk 30), and this receiver cannot tell whether a given
        // OEM lock screen honored it. isDeviceLocked() rather than isKeyguardLocked(), for the
        // same reason as KeepADBReceiver#handleTrustNetworkAction: only a secured lock screen whose
        // credential was never supplied counts; a swipe-only device has nothing to bypass.
        KeyguardManager keyguardManager = context.getSystemService(KeyguardManager.class);
        if (keyguardManager != null && keyguardManager.isDeviceLocked()) {
            KeepADBDiagnostics.event(context, "user_action", KeepADB.SOURCE_USB_HANDOVER_MANUAL,
                    "blocked", "device_locked");
            // Not a failure: WLAN-ADB is untouched, so lastHandoverActionFailed (whose text points
            // at the permission) is left as it was. Re-post the notification instead so the action
            // stays available to tap again after unlocking -- there is no unlock listener. USB is
            // still connected here for the same reason reportManualActionResult assumes it.
            KeepADBUsbNotification.refresh(context, true);
            return false;
        }
        boolean success = KeepADBUsbHandover.handleManualAction(context);
        KeepADBUsbNotification.reportManualActionResult(context, success);
        return success;
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

    /**
     * #538: synchronous verified-USB-ADB-connected check for the transport overview, driven by
     * the same sticky-broadcast query as {@link #refresh(Context)} -- "verified" here means the
     * system itself reports connected+configured+adb, not merely that a cable is plugged in (see
     * {@link #isUsbAdbConnected(Intent)}).
     */
    static boolean isCurrentlyUsbAdbConnected(Context context) {
        if (context == null) return false;
        try {
            IntentFilter filter = new IntentFilter(ACTION_USB_STATE);
            Intent state = context.registerReceiver(null, filter);
            return isUsbAdbConnected(state);
        } catch (Exception e) {
            return false;
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
