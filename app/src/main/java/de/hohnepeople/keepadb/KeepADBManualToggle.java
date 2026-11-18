package de.hohnepeople.keepadb;

import android.content.Context;

/**
 * #1000: what a manual on/off of Wifi-ADB ends. {@link KeepADB#setEnabled} calls this for every
 * manual source before it takes its lock; the toggle facade itself stays ignorant of the force
 * mode (#245: manual controls are never gated, only followed).
 *
 * <ul>
 *   <li>A running Wifi-ADB timer ends on any manual on or off, except the timer's own start
 *       ({@link KeepADBTimer#onManualToggle}).</li>
 *   <li>A running force mode ends on a manual off ({@link KeepADBForceMode#endNow}): whoever
 *       switches off wants it off, so the dangerous mode must not take hold again at the next
 *       "on".</li>
 * </ul>
 * The self-pairing cleanup's switch-off is not the user's decision about Wifi-ADB and ends
 * nothing.
 */
final class KeepADBManualToggle {
    private KeepADBManualToggle() {}

    static void onManualToggle(Context appContext, boolean on, String source) {
        if (appContext == null || KeepADB.SOURCE_SELF_PAIRING.equals(source)) return;
        KeepADBTimer.onManualToggle(appContext, on, source);
        if (!on && !KeepADB.SOURCE_TIMER.equals(source)) {
            KeepADBForceMode.endNow(appContext, "manual_off");
        }
    }
}
