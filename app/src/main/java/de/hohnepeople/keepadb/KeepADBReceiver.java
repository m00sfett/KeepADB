package de.hohnepeople.keepadb;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/**
 * Handles explicit KeepADB broadcast actions (such as the notification disable action).
 */
public final class KeepADBReceiver extends BroadcastReceiver {
    static final String ACTION_DISABLE = "de.hohnepeople.keepadb.ACTION_DISABLE";
    /** #446: the user allowed the access point the trust prompt named. */
    static final String ACTION_TRUST_NETWORK = "de.hohnepeople.keepadb.ACTION_TRUST_NETWORK";
    /** #446: the user declined; only the prompt goes away, nothing is trusted. */
    static final String ACTION_DISMISS_NETWORK_PROMPT =
            "de.hohnepeople.keepadb.ACTION_DISMISS_NETWORK_PROMPT";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        if (ACTION_DISABLE.equals(action)) {
            handleDisableAction(context);
        } else if (ACTION_TRUST_NETWORK.equals(action)) {
            handleTrustNetworkAction(context,
                    intent.getStringExtra(KeepADBNetworkTrustPrompt.EXTRA_BSSID),
                    intent.getStringExtra(KeepADBNetworkTrustPrompt.EXTRA_LABEL));
        } else if (ACTION_DISMISS_NETWORK_PROMPT.equals(action)) {
            handleDismissNetworkPromptAction(context);
        }
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
        KeepADBNotification.refresh(context);
        KeepADBWidget.refreshAll(context);
        return success;
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
        KeepADBDiagnostics.event(context, "user_action", "network_trust_prompt", "allowed",
                "bssid=" + cleanBssid);
        return trustBssidAndAttemptConnect(context, cleanBssid, label);
    }

    /**
     * #470: trusts {@code bssid} and immediately attempts the connection that being untrusted
     * was blocking -- shared by {@link #handleTrustNetworkAction} (the notification's "allow"
     * action) and {@link MainActivity}'s per-access-point trust button, so trusting a network
     * from the main screen never requires opening the notification first.
     *
     * <p>Also cancels/clears the notification prompt either way: once a network is trusted from
     * anywhere in the app, the question the prompt was asking no longer applies and it must not
     * remain visible.
     *
     * <p>The subsequent enable is gated by {@link KeepADBService#isAutoEnableStillPermitted},
     * not performed unconditionally: this can run arbitrarily late relative to when the access
     * point was actually seen (a tapped notification, a non-current row in the main screen's
     * access-point list), and by then the device may have roamed to a <em>different</em>
     * untrusted access point or dropped Wi-Fi entirely. Turning Wireless Debugging on there would
     * extend the user's consent for this access point to one they never saw. If the guard says
     * no, the allowlist entry still stands and the normal Keep-Alive path enables as soon as the
     * device is back on it.
     *
     * @return true if Wireless Debugging was actually turned on by this call.
     */
    static boolean trustBssidAndAttemptConnect(Context context, String bssid, String label) {
        KeepADBTrustedNetwork.addBssid(context, bssid, label);
        KeepADBBlockedNetworkHistory.remove(context, bssid);
        KeepADBNetworkTrustPrompt.cancel(context);
        // #474: only forget the marker for the access point just trusted -- a global clear would
        // also silently drop the anti-spam history for a different, still-untrusted access point
        // that has its own pending prompt (e.g. trusting a historical AP from MainActivity while
        // the currently-connected, untrusted AP still awaits its own decision).
        KeepADBNetworkTrustPrompt.clearPromptState(context, bssid);

        boolean enabled = false;
        if (KeepADBService.isAutoEnableStillPermitted(context)) {
            enabled = KeepADB.setEnabled(context, true, KeepADB.SOURCE_NETWORK_TRUST_PROMPT);
            if (!enabled) {
                KeepADBNotification.showPermissionMissing(context);
            }
        } else {
            KeepADBDiagnostics.event(context, "user_action", "network_trust_prompt", "skipped",
                    "auto_enable_not_permitted");
        }
        KeepADBService.sync(context);
        KeepADBNotification.refresh(context);
        KeepADBWidget.refreshAll(context);
        return enabled;
    }

    /**
     * #446: the user declined. Nothing is trusted and nothing is blocklisted -- an access point
     * that is not on the allowlist is already blocked. The only effect is that the prompt goes
     * away and is not raised again for this access point until {@link
     * KeepADBNetworkTrustPrompt#PROMPT_REPEAT_INTERVAL_MS} has passed (the marker was written
     * when the prompt was raised, so this handler only has to stop showing it).
     */
    static void handleDismissNetworkPromptAction(Context context) {
        KeepADBDiagnostics.event(context, "user_action", "network_trust_prompt", "declined",
                "action_button");
        KeepADBNetworkTrustPrompt.cancel(context);
    }
}
