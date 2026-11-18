package de.hohnepeople.keepadb;

/**
 * #957: what the home screen says about the main switch, split into a short state, at most one
 * reason line and at most one action. Pure mapping from the already-resolved app state to string
 * resources and an action kind: no {@code Context}, no platform reads, so every case is a plain
 * unit test ({@code KeepADBHomeStatusTest}). {@link MainActivity} resolves the inputs and renders
 * the result; the state logic itself ({@link KeepADB.State}, when Keep-Alive is blocked) is not
 * decided here.
 *
 * <p>The long sentences of the former single status text ({@code status_*},
 * {@code status_on_network_*}) are not shown inline any more; where one exists it is the
 * {@link Model#explanationRes details} behind a tap on the reason line.
 */
final class KeepADBHomeStatus {
    private KeepADBHomeStatus() {}

    /** The one thing the user can do about the reason, as a button below it. */
    enum Action {
        NONE(0),
        /** An undecided Wi-Fi: the trust decision, or the Networks list when nothing is askable. */
        DECIDE(R.string.networks_recent_decide),
        /** A blocked Wi-Fi: the Networks list, where the block can be lifted. */
        NETWORKS(R.string.home_action_networks),
        /** Network not readable: where the "Keep-Alive paused" warning leads. */
        FIX(R.string.home_warning_action_fix),
        /** Dialog of Android not confirmed: the switch's own path. */
        RETRY(R.string.home_action_retry),
        /** Permission missing: where the permission warning leads. */
        INSTRUCTIONS(R.string.home_warning_action_guide);

        /** The button label, or 0 for {@link #NONE}. */
        final int labelRes;

        Action(int labelRes) {
            this.labelRes = labelRes;
        }
    }

    /**
     * How the current Wi-Fi stands where it matters: while Keep-Alive is paused or stays off
     * because of it. {@link #NONE} everywhere else.
     */
    enum Network {
        NONE,
        /** Not decided yet (not trusted). */
        UNDECIDED,
        /** Blocked by the user. */
        BLOCKED
    }

    /** What is shown. A resource of 0 means "nothing to show" for that part. */
    static final class Model {
        final int stateRes;
        final int reasonRes;
        /** The long sentence behind the reason line, or 0 when the reason needs none. */
        final int explanationRes;
        final Action action;

        Model(int stateRes, int reasonRes, int explanationRes, Action action) {
            this.stateRes = stateRes;
            this.reasonRes = reasonRes;
            this.explanationRes = explanationRes;
            this.action = action;
        }

        boolean hasReason() {
            return reasonRes != 0;
        }

        boolean hasExplanation() {
            return explanationRes != 0;
        }
    }

    /**
     * @param appState the state of {@link KeepADB#getState}
     * @param togglePending a scheduled but not yet written toggle (#318)
     * @param waiting why {@link KeepADB.State#OFF_KEEP_ALIVE_WAITING} applies; only read in that
     *        state (may be null otherwise)
     * @param network the current Wi-Fi where it is the reason: for
     *        {@link MainActivity.KeepAliveWaitingDetail#BLOCKED_UNTRUSTED_NETWORK} and, while it is
     *        on, when Keep-Alive would not switch it back on at this Wi-Fi (#949); otherwise
     *        {@link Network#NONE}
     */
    static Model of(KeepADB.State appState, boolean togglePending,
                    MainActivity.KeepAliveWaitingDetail waiting, Network network) {
        if (appState == KeepADB.State.PERMISSION_MISSING) {
            return new Model(R.string.home_state_setup, R.string.home_reason_permission, 0,
                    Action.INSTRUCTIONS);
        }
        if (togglePending) {
            return new Model(R.string.status_pending, 0, 0, Action.NONE);
        }
        if (appState == KeepADB.State.OFF) {
            return new Model(R.string.home_state_off, 0, 0, Action.NONE);
        }
        if (appState == KeepADB.State.OFF_KEEP_ALIVE_WAITING) {
            return waitingModel(waiting, network);
        }
        // ON, with Wi-Fi or without.
        boolean connected = appState != KeepADB.State.ENABLED_DISCONNECTED;
        if (network == Network.BLOCKED) {
            return new Model(R.string.home_state_on, R.string.home_reason_stays_off_blocked,
                    R.string.status_on_network_blocked, Action.NETWORKS);
        }
        if (network == Network.UNDECIDED) {
            return new Model(R.string.home_state_on, R.string.home_reason_stays_off_untrusted,
                    R.string.status_on_network_untrusted, Action.DECIDE);
        }
        return new Model(R.string.home_state_on,
                connected ? 0 : R.string.home_reason_no_wifi, 0, Action.NONE);
    }

    private static Model waitingModel(MainActivity.KeepAliveWaitingDetail waiting, Network network) {
        if (waiting == MainActivity.KeepAliveWaitingDetail.BLOCKED_UNTRUSTED_NETWORK) {
            // #790: a network the user blocked is not "not trusted yet": say so, and lead to the
            // list where the block can be lifted instead of to a question with no answer.
            return network == Network.BLOCKED
                    ? new Model(R.string.home_state_off, R.string.home_reason_blocked,
                            R.string.status_off_keep_alive_blocked_by_user, Action.NETWORKS)
                    : new Model(R.string.home_state_off, R.string.home_reason_untrusted,
                            R.string.status_off_keep_alive_blocked_untrusted, Action.DECIDE);
        }
        if (waiting == MainActivity.KeepAliveWaitingDetail.BLOCKED_OPEN_NETWORK) {
            // #997: neither trust nor a block can change this; only another Wi-Fi or Force mode.
            return new Model(R.string.home_state_off, R.string.home_reason_open_network,
                    R.string.status_off_keep_alive_blocked_open_network, Action.NONE);
        }
        if (waiting == MainActivity.KeepAliveWaitingDetail.BLOCKED_IDENTITY_UNAVAILABLE) {
            return new Model(R.string.home_state_off, R.string.home_reason_identity,
                    R.string.status_off_keep_alive_blocked_identity_unavailable, Action.FIX);
        }
        if (waiting == MainActivity.KeepAliveWaitingDetail.BLOCKED_RECOVERY_BACKOFF) {
            // #496: an automatic enable's write was accepted but its readback never flipped on,
            // most commonly because Android's own dialog was never confirmed. Automatic retries
            // are paused; the switch itself is the sanctioned manual retry (KeepADB#setEnabled).
            return new Model(R.string.home_state_off, R.string.home_reason_backoff,
                    R.string.status_off_keep_alive_blocked_recovery_backoff, Action.RETRY);
        }
        return new Model(R.string.home_state_off, R.string.home_reason_waiting,
                R.string.status_off_keep_alive_waiting, Action.NONE);
    }
}
