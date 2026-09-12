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
 *       {@link KeepADBUsbReceiver#onReceive}'s real {@code USB_STATE} broadcasts. Gated by
 *       {@link KeepADBTrustedNetwork} (#245) like KeepADBService's other auto re-enable
 *       paths, since it's an automatic action, not a direct user request. Source
 *       {@code "usb_handover"}, i.e. debounced and revalidated at write time (#310).</li>
 *   <li>{@link #handleManualAction(Context)} -- MANUAL mode, called from the USB notification's
 *       "Enable WLAN-ADB" action. A direct, explicit user action, so it is never gated by the
 *       trusted-network allowlist and, since #310, carries its own source
 *       {@link KeepADB#SOURCE_USB_HANDOVER_MANUAL} so KeepADB applies it without delay.</li>
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
        boolean lastIntentOff = KeepADB.wasLastExplicitIntentOff(appContext);
        if (onRawUsbBroadcastInternal(connected, mode, alreadyEnabled, lastIntentOff)) {
            // #245: AUTOMATIC mode auto-enables WLAN-ADB the same way KeepADBService's own
            // auto re-enable paths do, so it must respect the same trusted-network allowlist --
            // otherwise plugging in a USB cable on an untrusted Wi-Fi network would silently
            // bypass the very setting meant to prevent exactly that. handleManualAction() below
            // is a direct, explicit user action and is deliberately never gated.
            //
            // #348: trust alone is not enough. KeepADBTrustedNetwork.MODE_ALL_WIFI trusts
            // unconditionally without ever asking whether a Wi-Fi transport is actually
            // connected right now, so this must independently require one -- otherwise plugging
            // in USB while the phone has no Wi-Fi at all (but still remembers MODE_ALL_WIFI)
            // would auto-enable WLAN-ADB with no listener for adbd to ever bind on.
            if (!KeepADBService.isWifiConnected(appContext)) {
                KeepADBDiagnostics.event(appContext, "usb_handover", "usb", "blocked", "no_wifi_transport");
                return;
            }
            if (!KeepADBTrustedNetwork.isCurrentNetworkTrusted(appContext)) {
                KeepADBDiagnostics.event(appContext, "usb_handover", "usb", "blocked", "untrusted_network");
                return;
            }
            // #310: the check above happens now; the write may happen up to TOGGLE_COOLDOWN_MS
            // later. The same policy is handed along as a guard so it is re-evaluated at write
            // time -- otherwise plugging in the cable just before leaving a trusted network
            // would still enable WLAN-ADB on the network the device switched to.
            KeepADB.setEnabled(appContext, true, "usb_handover",
                    KeepADBUsbHandover::isAutoHandoverStillPermitted);
        }
    }

    /**
     * The automatic handover's re-check for {@link KeepADB.EnableGuard}. Keep-Alive is
     * deliberately not part of it: the USB handover is its own feature with its own mode setting
     * and works with Keep-Alive off. Static and lock-free, since KeepADB invokes it while holding
     * its own monitor (and this class's {@code synchronized} decision core is never on that path).
     *
     * <p>#348: mirrors the active-Wi-Fi-transport gate applied at the initial check in {@link
     * #onRawUsbBroadcast} -- the debounced write this guards may happen after Wi-Fi has since
     * dropped, and {@code MODE_ALL_WIFI} trust alone would not catch that.
     */
    static boolean isAutoHandoverStillPermitted(Context appContext) {
        return KeepADBService.isWifiConnected(appContext)
                && KeepADBTrustedNetwork.isCurrentNetworkTrusted(appContext);
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
     *
     * <p>#310: uses its own source string. It used to share {@code "usb_handover"} with the
     * automatic broadcast path above, which made the two indistinguishable to KeepADB even
     * though only this one is a direct user action -- so a user tap was debounced (and would now
     * additionally be revalidated) like an automatic enable.
     */
    static boolean handleManualAction(Context context) {
        return KeepADB.setEnabled(context.getApplicationContext(), true,
                KeepADB.SOURCE_USB_HANDOVER_MANUAL);
    }

    /** Reset state for unit tests. */
    static synchronized void resetForTesting() {
        connectedEdgeSeen = false;
    }
}
