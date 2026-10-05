package de.hohnepeople.keepadb;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

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
 * <p><strong>Privacy.</strong> The Wi-Fi name and the address are shown even while the privacy mode
 * is on: whoever decides has to see whom they are trusting (decision of #758). That is a deliberate
 * exception for this view only; lists and status lines keep hiding them. It follows that a host
 * must never bind this view while the device is locked, and {@link #clear} removes everything again
 * (see {@link NetworkDecisionActivity}).
 */
public final class NetworkDecisionView extends LinearLayout {

    /** What the host learns; the view has already shown its message when this is called. */
    interface Listener {
        /** The user answered, or the answer turned out to be moot because the network is blocked. */
        void onDecided(KeepADBNetworkDecision.Outcome outcome);

        /** The user chose "decide later": nothing was written. */
        void onDecideLater();
    }

    private final View details;
    private final TextView name;
    private final TextView bssid;
    private final TextView comfortNote;
    private final Button blockName;

    private KeepADBNetworkDecision.Pending pending;
    private Listener listener;

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
        comfortNote = findViewById(R.id.decision_comfort_note);
        blockName = findViewById(R.id.decision_block_name);

        findViewById(R.id.decision_trust).setOnClickListener(v -> trust());
        findViewById(R.id.decision_block_ap).setOnClickListener(v -> blockAccessPoint());
        blockName.setOnClickListener(v -> blockName());
        findViewById(R.id.decision_later).setOnClickListener(v -> {
            if (pending != null && listener != null) listener.onDecideLater();
        });
        clear();
    }

    /** Shows the question for {@code access point}; the answers act on exactly this one. */
    void bind(KeepADBNetworkDecision.Pending accessPoint, Listener decisionListener) {
        pending = accessPoint;
        listener = decisionListener;
        Context context = getContext();
        boolean hasName = accessPoint.canBlockName();
        name.setText(hasName ? accessPoint.ssid : context.getString(R.string.network_decision_name_hidden));
        bssid.setText(accessPoint.bssid.toUpperCase(Locale.ROOT));
        blockName.setVisibility(hasName ? VISIBLE : GONE);
        if (hasName) {
            blockName.setText(context.getString(R.string.network_decision_block_name, accessPoint.ssid));
        }
        boolean comfort = hasName && KeepADBTrustedNetwork.isTrustByNameEnabled(context);
        comfortNote.setVisibility(comfort ? VISIBLE : GONE);
        if (comfort) {
            comfortNote.setText(context.getString(R.string.network_decision_comfort_note, accessPoint.ssid));
        }
        details.setVisibility(VISIBLE);
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
                        KeepADBNetworkDisplay.quoted(context, target.ssid == null
                                ? target.bssid : target.ssid, target.bssid)), Toast.LENGTH_SHORT);
                if (!result.enabled && !hasSecureSettingsPermission(context)) {
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
        finishBlock(KeepADBNetworkDecision.blockAccessPoint(getContext(), target.bssid));
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

    private static boolean hasSecureSettingsPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void toast(String text, int duration) {
        Toast.makeText(getContext(), text, duration).show();
    }
}
