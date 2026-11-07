package de.hohnepeople.keepadb;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;


/**
 * The reusable "Trust this network?" decision (#766): one access point, shown with its Wi-Fi name
 * and address, and the answers trust, block this access point, block the Wi-Fi name, or decide
 * later. Hosted as a dialog by {@link NetworkDecisionActivity} and meant to be embedded as well
 * (the network list #762, the setup assistant #761 and the home screen #764 use it); the host only
 * binds an access point and learns the outcome.
 *
 * <p>The view performs the answers itself, through {@link KeepADBNetworkDecision}, so every host
 * behaves the same and tells the user what happened (also when a trust was refused because the
 * network is blocked). "Decide later" and everything the host does besides answering -- Back, a
 * tap outside a dialog, rotating -- write nothing.
 *
 * <p><strong>Privacy (#904).</strong> While the app privacy switch is on, the Wi-Fi name and the
 * address are masked like on every other screen; a deliberate "Show" (button in this view) lifts
 * the mask for the screen that hosts the view, until it is left. Whoever decides can thereby still
 * see whom they are trusting. Whatever the privacy state, a host must never bind this view while
 * the device is locked, and {@link #clear} removes everything again (see {@link
 * NetworkDecisionActivity}).
 */
public final class NetworkDecisionView extends LinearLayout {

    /** What the host learns; the view has already shown its message when this is called. */
    interface Listener {
        /** The user answered, or the answer turned out to be moot because the network is blocked. */
        void onDecided(KeepADBNetworkDecision.Outcome outcome);

        /** The user chose "decide later": nothing was written. */
        void onDecideLater();

        /**
         * The user chose "Show": the screen is revealed and this view has redrawn itself. A host
         * that shows more masked data around the view redraws it here.
         */
        default void onRevealed() {}
    }

    private final View details;
    private final TextView name;
    private final TextView bssid;
    private final Button show;
    private final TextView comfortNote;
    private final Button blockName;

    private KeepADBNetworkDecision.Pending pending;
    private Listener listener;
    /** The reveal scope of the host (#904); a dialog host keeps the default. */
    private String privacyScope = KeepADBPrivacy.SCOPE_DECISION;

    public NetworkDecisionView(Context context) {
        this(context, null);
    }

    public NetworkDecisionView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        LayoutInflater.from(context).inflate(R.layout.view_network_decision, this, true);
        details = findViewById(R.id.decision_details);
        name = findViewById(R.id.decision_name);
        bssid = findViewById(R.id.decision_bssid);
        show = findViewById(R.id.decision_show);
        comfortNote = findViewById(R.id.decision_comfort_note);
        blockName = findViewById(R.id.decision_block_name);

        bindShow();
        findViewById(R.id.decision_trust).setOnClickListener(v -> trust());
        findViewById(R.id.decision_block_ap).setOnClickListener(v -> blockAccessPoint());
        blockName.setOnClickListener(v -> blockName());
        findViewById(R.id.decision_later).setOnClickListener(v -> {
            if (pending != null && listener != null) listener.onDecideLater();
        });
        clear();
    }

    /**
     * Which reveal scope the "Show" of this view lifts (#904): an embedding host names its own
     * page, so a reveal here does not unmask the rest of the screen and vice versa.
     */
    void setPrivacyScope(String scope) {
        privacyScope = scope;
        bindShow();
    }

    private void bindShow() {
        KeepADBPrivacy.bindShowButton(show, privacyScope, () -> {
            if (pending != null) bind(pending, listener);
            if (listener != null) listener.onRevealed();
        });
    }

    private Context shown() {
        return KeepADBPrivacy.scoped(getContext(), privacyScope);
    }

    /** Shows the question for {@code access point}; the answers act on exactly this one. */
    void bind(KeepADBNetworkDecision.Pending accessPoint, Listener decisionListener) {
        pending = accessPoint;
        listener = decisionListener;
        Context context = getContext();
        boolean hasName = accessPoint.canBlockName();
        // #904: masked while the app privacy switch is on and this screen has no "Show".
        Context shown = shown();
        boolean masked = KeepADBNetworkDisplay.hidden(shown);
        String shownName = hasName ? KeepADBNetworkDisplay.ssid(shown, accessPoint.ssid, null) : null;
        name.setText(hasName ? shownName : context.getString(R.string.network_decision_name_hidden));
        bssid.setText(KeepADBNetworkDisplay.bssid(shown, accessPoint.bssid));
        show.setVisibility(masked ? VISIBLE : GONE);
        blockName.setVisibility(hasName ? VISIBLE : GONE);
        if (hasName) {
            blockName.setText(context.getString(R.string.network_decision_block_name, shownName));
        }
        boolean comfort = hasName && KeepADBTrustedNetwork.isTrustByNameEnabled(context);
        comfortNote.setVisibility(comfort ? VISIBLE : GONE);
        if (comfort) {
            comfortNote.setText(context.getString(R.string.network_decision_comfort_note, shownName));
        }
        details.setVisibility(VISIBLE);
    }

    /**
     * Shows or hides "decide later". A host that embeds the question in a page (the network list,
     * #762) has nothing to close, so there the answer would do nothing: it hides the button and
     * the user simply leaves the question unanswered. The dialog host keeps it.
     */
    void setDecideLaterVisible(boolean visible) {
        findViewById(R.id.decision_later).setVisibility(visible ? VISIBLE : GONE);
    }

    /**
     * Removes the access point and every text derived from it, so nothing is left in the view
     * hierarchy. The answers do nothing until {@link #bind} is called again.
     */
    void clear() {
        pending = null;
        name.setText("");
        bssid.setText("");
        comfortNote.setText("");
        comfortNote.setVisibility(GONE);
        blockName.setText("");
        show.setVisibility(GONE);
        details.setVisibility(GONE);
    }

    /** Whether an access point is bound, that is, whether the question is on screen. */
    boolean isBound() {
        return pending != null;
    }

    private void trust() {
        KeepADBNetworkDecision.Pending target = pending;
        if (target == null) return;
        Context context = getContext();
        KeepADBNetworkDecision.TrustResult result = KeepADBNetworkDecision.trust(context, target);
        switch (result.outcome) {
            case TRUSTED:
                toast(context.getString(R.string.network_ap_allowed_toast,
                        KeepADBNetworkDisplay.quoted(shown(), target.ssid == null
                                ? target.bssid : target.ssid, target.bssid)), Toast.LENGTH_SHORT);
                // #812: the cause comes from the toggle result, not from a second permission read.
                if (!result.enabled && result.permissionFailure) {
                    toast(context.getString(R.string.permission_error_toast,
                            context.getPackageName()), Toast.LENGTH_LONG);
                }
                break;
            case TRUST_REFUSED_BLOCKED:
                toast(context.getString(R.string.network_decision_trust_refused_toast),
                        Toast.LENGTH_LONG);
                break;
            default:
                // Nothing was stored (the device reported itself locked): the question stays.
                toast(context.getString(R.string.network_decision_failed_toast), Toast.LENGTH_LONG);
                return;
        }
        notifyDecided(result.outcome);
    }

    private void blockAccessPoint() {
        KeepADBNetworkDecision.Pending target = pending;
        if (target == null) return;
        finishBlock(KeepADBNetworkDecision.blockAccessPoint(getContext(), target.bssid, target.ssid));
    }

    private void blockName() {
        KeepADBNetworkDecision.Pending target = pending;
        if (target == null || !target.canBlockName()) return;
        finishBlock(KeepADBNetworkDecision.blockName(getContext(), target.ssid));
    }

    private void finishBlock(KeepADBNetworkDecision.Outcome outcome) {
        Context context = getContext();
        if (outcome == KeepADBNetworkDecision.Outcome.BLOCK_FAILED) {
            toast(context.getString(R.string.network_decision_failed_toast), Toast.LENGTH_LONG);
            return;
        }
        toast(context.getString(R.string.network_decision_blocked_toast), Toast.LENGTH_SHORT);
        notifyDecided(outcome);
    }

    private void notifyDecided(KeepADBNetworkDecision.Outcome outcome) {
        // Answered: a second tap on a button that is still on screen must not answer again.
        pending = null;
        if (listener != null) listener.onDecided(outcome);
    }

    private void toast(String text, int duration) {
        Toast.makeText(getContext(), text, duration).show();
    }
}
