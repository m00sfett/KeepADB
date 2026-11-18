package de.hohnepeople.keepadb;

import android.content.Context;

/**
 * #1000: what a manual on/off of Wifi-ADB ends (the trust prompt's "Allow" ends no timer). {@link KeepADB#setEnabled} calls this for every
 * manual source before it takes its lock; the toggle facade itself stays ignorant of the force
 * mode (#245: manual controls are never gated, only followed).
 *
 * <ul>
 *   <li>A running Wifi-ADB timer ends on any manual on or off, except the timer's own start
 *       ({@link KeepADBTimer#onManualToggle}).</li>
 *   <li>A running force mode ends on a manual off ({@link KeepADBForceMode#endNow}): whoever
 *       switches off wants it off, so the dangerous mode must not take hold again at the next
 *       "on".</li>
 *   <li>Any manual on (the trust prompt's included) supersedes a timer's outstanding switch-off and
 *       its end notice, a posted one too ({@link KeepADBTimer#onManualOn}, #1008).</li>
 * </ul>
 * The self-pairing cleanup's switch-off is not the user's decision about Wifi-ADB and ends
 * nothing.
 */
final class KeepADBManualToggle {
    private KeepADBManualToggle() {}

    /**
     * After a manual or timer-expiry write went through; see {@link KeepADBTimer#onWriteApplied}.
     * Called under the {@link KeepADB} monitor: a preference write only, nothing else (#1011).
     *
     * <p>#1010: the self-pairing setup is not the user's decision about Wifi-ADB, so its "on"
     * settles no outstanding timer switch-off, exactly as {@link #onManualToggle} ends nothing for
     * it. An "off" the readback confirms still settles one: the obligation is a fact about Wifi-ADB
     * (it must be off), and it is.
     */
    static void onWriteApplied(Context appContext, String source, boolean on, boolean confirmedOff) {
        if (on && KeepADB.SOURCE_SELF_PAIRING.equals(source)) return;
        KeepADBTimer.onWriteApplied(appContext, source, on, confirmedOff);
    }

    /**
     * Whether a timer's switch-off is still owed. A pure preference read: {@link KeepADB} checks it
     * under its own monitor before it writes or records the timer's "off", so a manual "on" that
     * settled the obligation in between is never switched off again (review #1000).
     */
    static boolean isTimerOffOwed(Context appContext) {
        return KeepADBTimer.isShutdownPending(appContext);
    }

    static void onManualToggle(Context appContext, boolean on, String source) {
        if (appContext == null || KeepADB.SOURCE_SELF_PAIRING.equals(source)) return;
        // Review #1000 (maintainer decision): "Allow" on the trust prompt answers a question about
        // the network, not about the limit; a running timer keeps running.
        if (!KeepADB.SOURCE_NETWORK_TRUST_PROMPT.equals(source)) {
            KeepADBTimer.onManualToggle(appContext, on, source);
        }
        // #1008: after the timer branch, which may finish an expired timer and post its notice.
        if (on) KeepADBTimer.onManualOn(appContext);
        if (!on && !KeepADB.SOURCE_TIMER.equals(source)) {
            KeepADBForceMode.endNow(appContext, "manual_off");
        }
    }
}
