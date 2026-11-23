package de.hohnepeople.keepadb;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The Wi-Fi the device is connected to right now, and the question whether to trust it. The card
 * is the current-network card of the Networks list ({@link
 * NetworkListRenderer#renderCurrentForAssistant}) with the embedded decision of #766 ("Trust
 * this network?"); the step has no dialog and no decision logic of its own.
 *
 * <p>The "trusted Wi-Fi name" of the balanced protection level (decision F5 of #758) is derived
 * from the trusted access points, so there is no second list to fill here.
 */
final class OnboardingNetworkStep extends OnboardingStep {
    private Activity host;
    private ViewGroup content;
    private LinearLayout card;
    private TextView privacyNote;
    private NetworkListRenderer renderer;
    private boolean built;

    OnboardingNetworkStep() {
        super(KeepADBOnboarding.Step.NETWORK, R.string.onboarding_network_title,
                R.string.onboarding_network_question);
    }

    @Override
    boolean hasSkip() {
        return true;
    }

    @Override
    void build(Activity activity, ViewGroup container) {
        host = activity;
        content = container;
        content.removeAllViews();
        // #904: shown while the app privacy switch masks this card; "Show" lifts it.
        privacyNote = OnboardingActionSteps.text(host, host.getString(R.string.onboarding_network_privacy_note),
                13, R.color.night_muted, 0);
        content.addView(privacyNote);
        if (KeepADBForceMode.isActive(host)) {
            // Trust and blocks do not apply while force mode is on; the answer still counts.
            content.addView(OnboardingActionSteps.text(host, host.getString(R.string.networks_force_note), 13,
                    R.color.text_yellow, 0));
        }
        card = new LinearLayout(host);
        card.setOrientation(LinearLayout.VERTICAL);
        // The card is replaced when the answer is given; the screen reader reads the new one.
        card.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        renderer = new NetworkListRenderer(host, card, this::refresh);
        built = true;
        refresh();
    }

    private void refresh() {
        if (privacyNote != null) {
            privacyNote.setVisibility(KeepADBNetworkDisplay.hidden(
                    KeepADBPrivacy.scoped(host, KeepADBPrivacy.SCOPE_ASSISTANT))
                    ? View.VISIBLE : View.GONE);
        }
        if (renderer != null) renderer.renderCurrentForAssistant();
    }

    @Override
    void commit(Context context) {
        // Nothing to write: trusting or blocking took effect when it was answered.
    }

    @Override
    String summary(Context context) {
        if (KeepADBNetworkDisplay.hidden(context)) {
            return context.getString(R.string.networks_count_hidden);
        }
        KeepADBNetworkIdentity identity = KeepADBNetworkIdentity.current(context);
        KeepADBNetworkList.Snapshot snapshot = KeepADBNetworkList.build(context, identity,
                identity.isKnown() || KeepADBService.isWifiConnected(context));
        return context.getString(R.string.networks_count, snapshot.trusted, snapshot.blocked);
    }

    @Override
    void onResume(Context context) {
        if (built) refresh();
    }

    @Override
    void onPermissionResult(int requestCode) {
        if (built) refresh();
    }
}
