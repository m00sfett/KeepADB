package de.hohnepeople.keepadb;

import android.content.Context;

/**
 * Optional USB-ADB -&gt; WLAN-ADB handover (#168). Enabling WLAN-ADB via
 * {@link KeepADB#setEnabled(Context, boolean, String)} already triggers endpoint discovery and
 * register reporting on its own (see {@link KeepADBNotification#refresh(Context)}); this class
 * only decides *when* that call should happen for the USB handover feature.
 *
 * <p>Two entry points, both eventually reaching {@link KeepADB#setEnabled}, never with
 * {@code false} -- this class never disables WLAN-ADB:
 * <ul>
 *   <li>{@link #onRawUsbBroadcast(Context, boolean)} -- AUTOMATIC mode, called only from
 *       {@link KeepADBUsbReceiver#onReceive}'s real {@code USB_STATE} broadcasts.</li>
 *   <li>{@link #handleManualAction(Context)} -- MANUAL mode, called from the USB notification's
 *       "Enable WLAN-ADB" action.</li>
 * </ul>
 */
final class KeepADBUsbHandover {

    // Tracks whether a genuine USB-ADB connect edge has already been handled for the current
    // physical connection session. Mutated only by onRawUsbBroadcastInternal, which is reached
    // only from real onReceive() broadcasts -- never from KeepADBUsbReceiver.refresh(Context)'s
    // re-derived state (used by SettingsActivity after profile edits). That is what stops a
    // same-connection re-refresh (e.g. a profile edit while the cable is still plugged in, long
    // after connecting) from re-triggering AUTOMATIC mode.
    private static volatile boolean connectedEdgeSeen;

    private KeepADBUsbHandover() {}

    /** Call only from {@link KeepADBUsbReceiver#onReceive}, i.e. only for real broadcast edges. */
    static void onRawUsbBroadcast(Context context, boolean connected) {
        Context appContext = context.getApplicationContext();
        String mode = KeepADBPreferences.getUsbWlanHandoverMode(appContext);
        boolean alreadyEnabled = KeepADB.isEnabled(appContext);
        // Deliberately KeepADB.wasLastExplicitIntentOff(), not isUserDisabled(): isUserDisabled()
        // is a one-shot token meant for KeepADBService's content observer alone. It gets consumed
        // (and reset) there for an unrelated Keep-Alive decision, which would silently "unblock"
        // this guard for whichever reader asks second. wasLastExplicitIntentOff() is never
        // consumed by anything, so it can't be starved by that other reader.
        boolean lastIntentOff = KeepADB.wasLastExplicitIntentOff();
        if (onRawUsbBroadcastInternal(connected, mode, alreadyEnabled, lastIntentOff)) {
            KeepADB.setEnabled(appContext, true, "usb_handover");
        }
    }

    /**
     * Pure decision core, exposed package-private for unit testing without any Context. Still
     * mutates (synchronously, deterministically) the connect-edge tracker as its only side
     * effect, so tests can drive a whole connect/disconnect sequence through it directly.
     *
     * @return true iff the caller should now call {@code KeepADB.setEnabled(ctx, true, ...)}.
     */
    static synchronized boolean onRawUsbBroadcastInternal(boolean connected, String mode,
            boolean alreadyEnabled, boolean lastIntentOff) {
        boolean wasConnected = connectedEdgeSeen;
        connectedEdgeSeen = connected;
        boolean isNewConnectEdge = connected && !wasConnected;
        if (!isNewConnectEdge) return false;
        if (!KeepADBPreferences.USB_WLAN_HANDOVER_MODE_AUTOMATIC.equals(mode)) return false;
        // A manual user OFF must never be overridden by this automatic path (#168 acceptance
        // criterion). lastIntentOff reflects KeepADB.wasLastExplicitIntentOff(), i.e. the last
        // explicit KeepADB.setEnabled() intent -- never consumed/reset by anything else (see
        // KeepADB.lastDesiredOn's field comment).
        return !alreadyEnabled && !lastIntentOff;
    }

    /**
     * MANUAL mode notification action. Returns whatever {@link KeepADB#setEnabled} returns, so
     * the caller (the USB notification) can show a clear error instead of implying success on a
     * missing-permission failure.
     */
    static boolean handleManualAction(Context context) {
        return KeepADB.setEnabled(context.getApplicationContext(), true, "usb_handover");
    }

    /** Reset state for unit tests. */
    static synchronized void resetForTesting() {
        connectedEdgeSeen = false;
    }
}
