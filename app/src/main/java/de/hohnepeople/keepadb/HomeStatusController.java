package de.hohnepeople.keepadb;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.TextView;

/**
 * #1048: the state line, reason line and action button of the home screen's Wifi-ADB card
 * (#957), split out of {@link MainActivity}: why Keep-Alive waits (#458), the pure status model
 * from {@link KeepADBHomeStatus}, the details dialog and where each action leads.
 */
final class HomeStatusController {

    /** What the actions of the status need from the screen. */
    interface Actions {
        /** #957: "Retry" runs exactly the enable path of the main switch. */
        void retryEnable();

        /** A toggle the status started failed. */
        void showToggleError(KeepADB.ToggleResult result);

        /** Redraw the screen and every other surface. */
        void refreshEverything();
    }

    /**
     * #458: why {@link KeepADB.State#OFF_KEEP_ALIVE_WAITING} currently applies, for the status
     * card only. Deliberately not on {@link KeepADB} itself: {@code
     * KeepADBTrustedNetworkContractTest#keepAdbFacadeNeverReferencesTheAllowlist} pins the toggle
     * facade as never referencing {@link KeepADBTrustedNetwork} (issue #245) -- the trust check
     * belongs at the call site that wants to explain a decision, not inside the facade.
     */
    enum KeepAliveWaitingDetail {
        /** No Wi-Fi transport connected at all -- the literal "waiting for the network" case. */
        WIFI_DISCONNECTED,
        /** Wi-Fi is connected, but the network isn't on the trusted allowlist (#245). */
        BLOCKED_UNTRUSTED_NETWORK,
        /** Wi-Fi is connected, but its identity can't be read (missing Location permission). */
        BLOCKED_IDENTITY_UNAVAILABLE,
        /** #997: Wi-Fi is connected, but it is open or behind a captive portal. */
        BLOCKED_OPEN_NETWORK,
        /**
         * #496: Wi-Fi is connected and trusted, but the last automatic enable's write was
         * accepted while its readback stayed off -- most commonly because Android's own
         * "always allow Wireless Debugging on this network" pairing dialog was never confirmed.
         * Automatic retries are paused; see {@link KeepADBRecoveryBackoff}.
         */
        BLOCKED_RECOVERY_BACKOFF
    }

    private final Activity activity;
    private final Actions actions;
    private final TextView status;
    private final View reasonRow;
    private final TextView reason;
    private final View reasonInfo;
    private final Button action;
    private AlertDialog activeDetailsDialog;
    /** #1048: the explanation the reason row is bound to; rebinding the same one allocates nothing. */
    private int boundExplanationRes;
    private KeepADBHomeStatus.Action boundAction;

    HomeStatusController(Activity activity, Actions actions) {
        this.activity = activity;
        this.actions = actions;
        status = activity.findViewById(R.id.status);
        reasonRow = activity.findViewById(R.id.status_reason_row);
        reason = activity.findViewById(R.id.status_reason);
        reasonInfo = activity.findViewById(R.id.status_reason_info);
        action = activity.findViewById(R.id.status_action);
    }

    void destroy() {
        if (activeDetailsDialog != null) activeDetailsDialog.dismiss();
        activeDetailsDialog = null;
    }

    /**
     * Only meaningful while {@link KeepADB#getState} returned
     * {@link KeepADB.State#OFF_KEEP_ALIVE_WAITING}. Reuses the same connectivity/trust checks the
     * automatic re-enable call sites already gate on ({@link KeepADBService#isWifiConnected} and
     * {@link KeepADBTrustedNetwork#getBlockReason}), so this can never disagree with why
     * Keep-Alive itself didn't re-enable.
     */
    static KeepAliveWaitingDetail resolveKeepAliveWaitingDetail(Context context) {
        if (!KeepADBService.isWifiConnected(context)) {
            return KeepAliveWaitingDetail.WIFI_DISCONNECTED;
        }
        KeepADBTrustedNetwork.BlockReason reason = KeepADBTrustedNetwork.getBlockReason(context);
        if (reason == KeepADBTrustedNetwork.BlockReason.IDENTITY_UNAVAILABLE) {
            return KeepAliveWaitingDetail.BLOCKED_IDENTITY_UNAVAILABLE;
        }
        if (reason == KeepADBTrustedNetwork.BlockReason.UNTRUSTED_NETWORK) {
            return KeepAliveWaitingDetail.BLOCKED_UNTRUSTED_NETWORK;
        }
        if (reason == KeepADBTrustedNetwork.BlockReason.OPEN_NETWORK) {
            return KeepAliveWaitingDetail.BLOCKED_OPEN_NETWORK;
        }
        // #496: trusted network, but the automatic re-enable backoff is currently blocking
        // further attempts after an accepted-write-ineffective-readback cycle.
        if (KeepADB.isAutomaticEnableBackoffBlocked()) {
            return KeepAliveWaitingDetail.BLOCKED_RECOVERY_BACKOFF;
        }
        // Trusted (or all-Wi-Fi mode) but still waiting -- e.g. the debounce window hasn't fired
        // yet. Nothing wrong to explain; render the generic waiting text.
        return KeepAliveWaitingDetail.WIFI_DISCONNECTED;
    }

    /** Reads the model for {@code appState} and draws it. */
    void render(KeepADB.State appState, boolean configured) {
        render(resolve(appState, configured));
    }

    /**
     * #957: reads what {@link KeepADBHomeStatus} needs from the platform and the network rules; the
     * mapping itself is pure. The reads happen only where they matter, as before.
     */
    private KeepADBHomeStatus.Model resolve(KeepADB.State appState, boolean configured) {
        boolean pending = configured && KeepADB.isTogglePending();
        KeepAliveWaitingDetail waiting = null;
        KeepADBHomeStatus.Network network = KeepADBHomeStatus.Network.NONE;
        if (!configured || pending) {
            // The state alone says it.
        } else if (appState == KeepADB.State.OFF_KEEP_ALIVE_WAITING) {
            // #458: distinguish real "no Wi-Fi yet" waiting from Keep-Alive being blocked by an
            // untrusted network or an unreadable network identity, so the status never looks
            // like KeepADB simply failed to notice a live Wi-Fi connection.
            waiting = resolveKeepAliveWaitingDetail(activity);
            if (waiting == KeepAliveWaitingDetail.BLOCKED_UNTRUSTED_NETWORK) {
                network = currentNetworkIsBlocked()
                        ? KeepADBHomeStatus.Network.BLOCKED : KeepADBHomeStatus.Network.UNDECIDED;
            }
        } else if (appState == KeepADB.State.ENABLED_DISCONNECTED
                || appState == KeepADB.State.ENABLED_CONNECTED) {
            // #949: while Wireless Debugging is on, Keep-Alive is enabled and the device sits on a
            // Wi-Fi that is not trusted (or blocked), the status says so and leads to the decision
            // (or the list). Without it, joining a public Wi-Fi with debugging already on left
            // Home without any way in.
            if (KeepADBPreferences.isKeepAliveEnabled(activity)
                    && KeepADBTrustedNetwork.getBlockReason(activity)
                            == KeepADBTrustedNetwork.BlockReason.UNTRUSTED_NETWORK) {
                network = currentNetworkIsBlocked()
                        ? KeepADBHomeStatus.Network.BLOCKED : KeepADBHomeStatus.Network.UNDECIDED;
            }
        }
        return KeepADBHomeStatus.of(appState, pending, waiting, network,
                KeepADBService.isWifiConnected(activity));
    }

    /** #790: a network the user blocked is not "not trusted yet". */
    private boolean currentNetworkIsBlocked() {
        return KeepADBTrustedNetwork.evaluate(activity, KeepADBNetworkIdentity.current(activity)).isBlocked();
    }

    /**
     * #957: state line, reason line and action button. The state and the reason sit in one polite
     * live region (see view_home_status.xml), so a change of either is announced as one message;
     * text that did not change is not set again, so the periodic refresh stays silent. The reason
     * is a tap target only where a long explanation exists, and then says so: an info mark, the
     * role of a button and "Show details" as its click label for TalkBack.
     */
    private void render(KeepADBHomeStatus.Model model) {
        setTextIfChanged(status, activity.getString(model.stateRes));
        if (!model.hasReason()) {
            reasonRow.setVisibility(View.GONE);
            clearReasonDetails();
        } else {
            reasonRow.setVisibility(View.VISIBLE);
            setTextIfChanged(reason, activity.getString(model.reasonRes));
            if (model.hasExplanation()) {
                bindReasonDetails(model.explanationRes);
            } else {
                clearReasonDetails();
            }
        }
        if (model.action == KeepADBHomeStatus.Action.NONE) {
            action.setVisibility(View.GONE);
            action.setOnClickListener(null);
            boundAction = null;
        } else {
            action.setText(model.action.labelRes);
            action.setVisibility(View.VISIBLE);
            if (boundAction != model.action) {
                KeepADBHomeStatus.Action bound = model.action;
                action.setOnClickListener(v -> run(bound));
                boundAction = bound;
            }
        }
    }

    private static void setTextIfChanged(TextView view, CharSequence text) {
        if (!TextUtils.equals(view.getText(), text)) view.setText(text);
    }

    private void bindReasonDetails(int explanationRes) {
        // #1048: the periodic refresh binds the same explanation again; nothing to rebuild then.
        if (boundExplanationRes == explanationRes) return;
        boundExplanationRes = explanationRes;
        reasonInfo.setVisibility(View.VISIBLE);
        reasonRow.setMinimumHeight((int) (48 * activity.getResources().getDisplayMetrics().density));
        reasonRow.setClickable(true);
        reasonRow.setFocusable(true);
        reasonRow.setOnClickListener(v -> showDetails(explanationRes));
        reasonRow.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_CLICK,
                        activity.getString(R.string.home_reason_details_action)));
            }
        });
    }

    private void clearReasonDetails() {
        boundExplanationRes = 0;
        reasonInfo.setVisibility(View.GONE);
        reasonRow.setMinimumHeight(0);
        reasonRow.setOnClickListener(null);
        reasonRow.setClickable(false);
        reasonRow.setFocusable(false);
        reasonRow.setAccessibilityDelegate(null);
    }

    /** The long sentence of the reason, as before the split, in a plain dialog with one button. */
    private void showDetails(int explanationRes) {
        if (activeDetailsDialog != null) activeDetailsDialog.dismiss();
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setMessage(explanationRes)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog.setOnDismissListener(d -> {
            if (activeDetailsDialog == d) activeDetailsDialog = null;
        });
        activeDetailsDialog = dialog;
        dialog.show();
    }

    /**
     * The targets of the action button: the decision or the Networks list as the status line led
     * before (#764, #790), "Fix" where the "Keep-Alive paused" warning leads, "Retry" the enable path of
     * the main switch and "Instructions" where the permission warning leads.
     */
    private void run(KeepADBHomeStatus.Action selected) {
        switch (selected) {
            case DECIDE:
            case NETWORKS:
                activity.startActivity(entryIntent());
                break;
            case FIX:
                activity.startActivity(KeepADBNetworkTrustPrompt.identityUnavailableFixIntent(activity));
                break;
            case RETRY:
                // Retry only ever enables: the state may have flipped to on since it was drawn, and
                // the switch's click semantics would then switch it off.
                actions.retryEnable();
                break;
            case INSTRUCTIONS:
                activity.startActivity(KeepADBHomeWarnings.systemIntent(activity));
                break;
            case RESTART:
                // #1027: a plain manual off and, after a short pause, a manual on.
                KeepADB.ToggleResult restart = KeepADBRestart.restart(activity);
                if (!restart.isSuccess()) actions.showToggleError(restart);
                actions.refreshEverything();
                break;
            default:
                break;
        }
    }

    /**
     * #764 (UX concept 5.3): "this Wi-Fi isn't trusted" is the shortest way from "why does it not
     * switch on?" to the answer, so its action opens the trust decision for the access point the
     * device is on. It only carries the BSSID as a selector, like the notification does; the
     * dialog resolves everything else itself. #790: where the network is already decided
     * (blocked, or trusted in the meantime) there is no question left, so the tap opens the
     * Networks list instead, whose top card is the current network. Read when tapped, so a
     * decision in between counts.
     */
    private Intent entryIntent() {
        KeepADBNetworkIdentity identity = KeepADBNetworkIdentity.current(activity);
        KeepADBTrustedNetwork.Decision decision = KeepADBTrustedNetwork.evaluate(activity, identity);
        boolean decided = decision.isBlocked()
                || decision == KeepADBTrustedNetwork.Decision.TRUSTED_ACCESS_POINT
                || decision == KeepADBTrustedNetwork.Decision.TRUSTED_NAME;
        // #949: an undecided network the app has no record of (no prompt was raised for it yet)
        // would only end in "expired"; the list's current card asks the same question.
        boolean askable = !decided && KeepADBNetworkDecision.resolve(activity, identity.bssid).status
                == KeepADBNetworkDecision.Status.PENDING;
        return askable ? KeepADBNetworkTrustPrompt.decisionIntent(activity, identity.bssid)
                : NetworkListActivity.intent(activity, identity.bssid);
    }
}
