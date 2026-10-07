package de.hohnepeople.keepadb;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/**
 * Handles explicit KeepADB broadcast actions (such as the notification disable action).
 */
public final class KeepADBReceiver extends BroadcastReceiver {
    static final String ACTION_DISABLE = "de.hohnepeople.keepadb.ACTION_DISABLE";
    /** #734: the notification's Keep-Alive on/off action. */
    static final String ACTION_TOGGLE_KEEP_ALIVE = "de.hohnepeople.keepadb.ACTION_TOGGLE_KEEP_ALIVE";
    /** #446: the user allowed the access point the trust prompt named. */
    static final String ACTION_TRUST_NETWORK = "de.hohnepeople.keepadb.ACTION_TRUST_NETWORK";
    /**
     * #766: the user blocked the access point the trust prompt named. It replaces #446's
     * "dismiss" action, which only closed the notification while its label promised a block (N1).
     */
    static final String ACTION_BLOCK_NETWORK = "de.hohnepeople.keepadb.ACTION_BLOCK_NETWORK";
    /** #763: the notification's "End force mode" action. The safe direction, no question asked. */
    static final String ACTION_FORCE_END = "de.hohnepeople.keepadb.ACTION_FORCE_END";
    /** #763: the expiry alarm. Only re-evaluates; it cannot start or extend the force mode. */
    static final String ACTION_FORCE_EXPIRE = "de.hohnepeople.keepadb.ACTION_FORCE_EXPIRE";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        if (ACTION_DISABLE.equals(action)) {
            handleDisableAction(context);
        } else if (ACTION_TOGGLE_KEEP_ALIVE.equals(action)) {
            handleToggleKeepAliveAction(context);
        } else if (ACTION_TRUST_NETWORK.equals(action)) {
            handleTrustNetworkAction(context,
                    intent.getStringExtra(KeepADBNetworkTrustPrompt.EXTRA_BSSID),
                    intent.getStringExtra(KeepADBNetworkTrustPrompt.EXTRA_LABEL));
        } else if (ACTION_BLOCK_NETWORK.equals(action)) {
            handleBlockNetworkAction(context,
                    intent.getStringExtra(KeepADBNetworkTrustPrompt.EXTRA_BSSID));
        } else if (ACTION_FORCE_END.equals(action)) {
            handleForceEndAction(context);
        } else if (ACTION_FORCE_EXPIRE.equals(action)) {
            KeepADBForceMode.restore(context);
        }
    }

    /**
     * #763: "End force mode" from the notification. {@link KeepADBForceMode#endNow} reports false
     * when there was nothing to end (already over, or it just ran out), in which case no "ended by
     * you" toast is shown.
     */
    static boolean handleForceEndAction(Context context) {
        KeepADBDiagnostics.event(context, "user_action", "notification", "force_end", "action_button");
        boolean ended = KeepADBForceMode.endNow(context);
        if (ended) KeepADBForceNotice.showEndedToast(context);
        return ended;
    }

    static boolean handleDisableAction(Context context) {
        KeepADBDiagnostics.event(context, "user_action", "notification", "disable", "action_button");
        Context localizedContext = KeepADBLocaleHelper.wrapContext(context);
        boolean success = KeepADB.setEnabled(context, false, "notification");
        if (!success) {
            try {
                Toast.makeText(context,
                        localizedContext.getString(R.string.permission_error_toast, context.getPackageName()),
                        Toast.LENGTH_LONG).show();
            } catch (RuntimeException ignored) {
            }
        }
        KeepADBService.sync(context);
        KeepADBEndpointCoordinator.refresh(context);
        KeepADBWidget.refreshAll(context);
        return success;
    }

    /**
     * #734: flips the real Keep-Alive setting, lets the service/widgets follow and redraws the card
     * so its status line and the action label match the new state. Turning Keep-Alive on only
     * lets the service's normal guarded path (Wi-Fi, trust, backoff) act; nothing is written to
     * Wireless Debugging here.
     */
    static boolean handleToggleKeepAliveAction(Context context) {
        boolean want = !KeepADBPreferences.isKeepAliveEnabled(context);
        KeepADBDiagnostics.event(context, "user_action", "notification",
                want ? "enable" : "disable", "keep_alive_action");
        KeepADBPreferences.setKeepAliveEnabled(context, want);
        KeepADBService.sync(context);
        KeepADBEndpointCoordinator.refresh(context);
        KeepADBWidget.refreshAll(context);
        return want;
    }

    /**
     * #446: the user explicitly allowed the access point the prompt named. The BSSID goes into
     * the allowlist through {@link KeepADBTrustedNetwork#addBssid} -- the very same entry point
     * the manual "add current network" button and the mesh convenience use, so there is exactly
     * one way a network can become trusted.
     *
     * @return true if Wireless Debugging was actually turned on by this call.
     */
    static boolean handleTrustNetworkAction(Context context, String bssid, String label) {
        String cleanBssid = bssid == null ? "" : bssid.trim();
        // #578: defense in depth against this action reaching the receiver while the device is
        // locked. Notification.Action#setAuthenticationRequired (API 31+) already asks the
        // platform to reauthenticate before the PendingIntent fires, but that flag is enforced by
        // SystemUI -- not by this app -- does not exist below API 31 (minSdk 30), and there is no
        // way for this receiver to tell whether a given OEM lock screen actually honored it.
        // isDeviceLocked() is used rather than isKeyguardLocked(): the risk here is specifically
        // that credentials were required and never supplied, which is exactly what
        // isDeviceLocked() reports. isKeyguardLocked() stays true for a device with no secure lock
        // configured at all (swipe-only) until the lock screen is swiped away, which would gate a
        // device with no authentication to bypass in the first place -- a false positive against
        // a user who deliberately chose not to secure their device.
        KeyguardManager keyguardManager = context.getSystemService(KeyguardManager.class);
        if (keyguardManager != null && keyguardManager.isDeviceLocked()) {
            KeepADBDiagnostics.event(context, "user_action", "network_trust_prompt", "blocked",
                    "device_locked");
            // Keep the question open rather than silently dropping the tap: re-post the exact
            // same prompt so the user can decide once they unlock, instead of the notification
            // just disappearing with nothing trusted and no way to retry short of roaming off
            // and back onto the access point.
            KeepADBNetworkTrustPrompt.reshow(context, cleanBssid, label);
            return false;
        }
        KeepADBNetworkTrustPrompt.cancel(context);
        // Fail closed on anything that isn't a real, matchable access point identifier: storing a
        // placeholder BSSID would make every later unidentifiable network compare equal to it.
        if (cleanBssid.isEmpty()
                || KeepADBNetworkIdentity.REDACTED_BSSID.equalsIgnoreCase(cleanBssid)
                || KeepADBNetworkIdentity.UNSET_BSSID.equalsIgnoreCase(cleanBssid)) {
            KeepADBDiagnostics.event(context, "user_action", "network_trust_prompt", "failed",
                    "invalid_bssid");
            return false;
        }
        // #760: trusting never lifts a block -- only an explicit unblock does. A prompt raised
        // before the block (or an in-app confirmation opened from it) may still be tapped; it
        // then changes nothing.
        if (isBlockedTarget(context, cleanBssid, label)) {
            KeepADBDiagnostics.event(context, "user_action", "network_trust_prompt", "blocked",
                    "network_blocked");
            return false;
        }
        KeepADBDiagnostics.event(context, "user_action", "network_trust_prompt", "allowed",
                "bssid=" + cleanBssid);
        return trustBssidAndAttemptConnect(context, cleanBssid, label);
    }

    /**
     * #470: trusts {@code bssid} and immediately attempts the connection that being untrusted
     * was blocking -- shared by {@link #handleTrustNetworkAction} (the notification's "allow"
     * action) and {@link SettingsActivity}'s in-app trust confirmation for that same prompt
     * (#598), so a trust decision taken on the prompt itself takes effect right away. The
     * Settings list and card actions do not use this path any more: since #654 they only grant
     * the allowance, see {@link #allowBssidOnly}.
     *
     * <p>Also cancels/clears the notification prompt either way: once a network is trusted from
     * anywhere in the app, the question the prompt was asking no longer applies and it must not
     * remain visible.
     *
     * <p>The subsequent enable is gated by {@link KeepADBService#isAutoEnableStillPermittedIgnoringBackoff},
     * not performed unconditionally: this can run arbitrarily late relative to when the access
     * point was actually seen (a tapped notification), and by then the device may have roamed to
     * a <em>different</em> untrusted access point or dropped Wi-Fi entirely. Turning Wireless
     * Debugging on there would extend the user's consent for this access point to one they never
     * saw. If the guard says no, the allowlist entry still stands and the normal Keep-Alive path
     * enables as soon as the device is back on it.
     *
     * @return true if Wireless Debugging was actually turned on by this call.
     */
    static boolean trustBssidAndAttemptConnect(Context context, String bssid, String label) {
        recordTrust(context, bssid, label);

        boolean enabled = false;
        // #670: the explicit tap must not be blocked by the automatic-retry backoff.
        if (KeepADBService.isAutoEnableStillPermittedIgnoringBackoff(context)) {
            enabled = KeepADB.setEnabled(context, true, KeepADB.SOURCE_NETWORK_TRUST_PROMPT);
            if (!enabled) {
                KeepADBNotification.showPermissionMissing(context);
            }
        } else {
            KeepADBDiagnostics.event(context, "user_action", "network_trust_prompt", "skipped",
                    "auto_enable_not_permitted");
        }
        KeepADBService.sync(context);
        KeepADBEndpointCoordinator.refresh(context);
        KeepADBWidget.refreshAll(context);
        return enabled;
    }

    /**
     * #654: the grant-only path of the Settings surfaces (the current-connection action, the
     * allowed, observed and recently-prevented lists, the mesh offer). Allowing an access point
     * afterwards grants exactly that and nothing else: it writes the allowlist entry and clears
     * the question it answers, but never switches Wireless Debugging on itself. What happens next
     * is decided by the regular Keep-Alive path from the current connection, mode and settings;
     * {@link KeepADBService#sync} only lets that path look again (a service start ends in its
     * normal {@code recheckAndEnable()}), it does not bypass any of its guards.
     *
     * @return the allowlist entry, or null if {@code bssid} was blank and nothing was stored.
     */
    static KeepADBTrustedNetwork.Entry allowBssidOnly(Context context, String bssid, String label) {
        return allowBssidOnly(context, bssid, label, null);
    }

    /**
     * #796: {@link #allowBssidOnly(Context, String, String)} for a caller that knows the Wi-Fi name
     * of the access point from somewhere other than the label (the name stored with an earlier
     * block of it). {@code knownSsid} is stored with the entry as display data only: it takes no
     * part in the block check (it may be stale), and the label, and with it every trust decision,
     * stays what the caller passed.
     */
    static KeepADBTrustedNetwork.Entry allowBssidOnly(Context context, String bssid, String label,
                                                      String knownSsid) {
        KeepADBTrustedNetwork.Entry entry = recordTrust(context, bssid, label, knownSsid);
        // #760: a refused (blocked) grant has already been reported as such by recordTrust.
        if (!isBlockedTarget(context, bssid, label)) {
            KeepADBDiagnostics.event(context, "user_action", "network_allow", "allowed", "grant_only");
        }
        KeepADBService.sync(context);
        KeepADBEndpointCoordinator.refresh(context);
        KeepADBWidget.refreshAll(context);
        return entry;
    }

    /**
     * #760: whether {@code bssid} or the Wi-Fi name carried by {@code label} (the SSID or, without
     * one, the BSSID itself) is blocked.
     */
    private static boolean isBlockedTarget(Context context, String bssid, String label) {
        return KeepADBNetworkBlocklist.isBlocked(context, bssid,
                KeepADBTrustedNetwork.ssidFromLabel(label, bssid));
    }

    private static KeepADBTrustedNetwork.Entry recordTrust(Context context, String bssid, String label) {
        return recordTrust(context, bssid, label, null);
    }

    private static KeepADBTrustedNetwork.Entry recordTrust(Context context, String bssid, String label,
                                                           String knownSsid) {
        // #760: the choke point of every trust write that goes through this class (notification
        // action, in-app confirmation, list and card actions, mesh offer). A block wins over trust
        // and is lifted only explicitly -- never as a side effect of trusting the same network --
        // so nothing is stored here; the caller sees the same null as for a blank BSSID.
        if (isBlockedTarget(context, bssid, label)) {
            KeepADBDiagnostics.event(context, "user_action", "network_allow", "blocked",
                    "network_blocked");
            return null;
        }
        KeepADBTrustedNetwork.Entry entry =
                KeepADBTrustedNetwork.addBssid(context, bssid, label, knownSsid);
        KeepADBBlockedNetworkHistory.remove(context, bssid);
        KeepADBNetworkTrustPrompt.cancel(context);
        // #474: only forget the marker for the access point just trusted -- a global clear would
        // also silently drop the anti-spam history for a different, still-untrusted access point
        // that has its own pending prompt (e.g. trusting a historical AP from MainActivity while
        // the currently-connected, untrusted AP still awaits its own decision).
        KeepADBNetworkTrustPrompt.clearPromptState(context, bssid);
        return entry;
    }

    /**
     * #766: the user chose "block" on the prompt: the access point it named is blocked, so
     * KeepADB never switches Wireless Debugging on there by itself and never asks about it again
     * (#760). Only the access point is blocked, not its Wi-Fi name; the name block is a separate
     * choice of the decision dialog.
     *
     * <p>Blocking is the safe direction -- it only ever takes automatic actions away -- so unlike
     * the trust action this one is not gated on the lock state. The lock-screen version of the
     * notification carries no actions at all. A block is lifted only explicitly, never by trusting
     * (#760); the user interface for that is the network list (#762).
     *
     * @return true if the access point is blocked afterwards.
     */
    static boolean handleBlockNetworkAction(Context context, String bssid) {
        KeepADBNetworkDecision.Outcome outcome =
                KeepADBNetworkDecision.blockAccessPoint(context, bssid);
        boolean blocked = outcome == KeepADBNetworkDecision.Outcome.BLOCKED_ACCESS_POINT;
        if (blocked) {
            try {
                Toast.makeText(context, KeepADBLocaleHelper.wrapContext(context)
                        .getString(R.string.network_decision_blocked_toast), Toast.LENGTH_SHORT)
                        .show();
            } catch (RuntimeException ignored) {
            }
        }
        return blocked;
    }
}
