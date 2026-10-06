package de.hohnepeople.keepadb;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The last step of the setup assistant (#768, UX concept 1.3 step 6): the register webhook. The
 * form is the one of the Settings ({@code view_webhook_form}, driven by {@link KeepADBWebhookForm}),
 * so URL validation, the cleartext warning and the save, enable and clear paths are the same code,
 * not a copy.
 *
 * <p>Like the other action steps it writes through its own buttons, at once, and nothing else: "Next"
 * and "Skip" never save, enable or clear anything, a half-typed URL included. There is no default
 * URL; the webhook belongs to this installation alone (#64).
 */
final class OnboardingWebhookStep extends OnboardingStep {
    private KeepADBWebhookForm form;
    private TextView lessSecure;
    private Bundle restored;

    OnboardingWebhookStep() {
        super(KeepADBOnboarding.Step.WEBHOOK, R.string.webhook_status_title,
                R.string.onboarding_webhook_question);
    }

    @Override
    boolean hasSkip() {
        return true;
    }

    @Override
    void build(Activity host, ViewGroup content) {
        lessSecure = marker(host);
        content.addView(lessSecure);
        View root = LayoutInflater.from(host).inflate(R.layout.onboarding_webhook, content, false);
        content.addView(root);
        form = new KeepADBWebhookForm(host, root, this::refreshMarker);
        if (restored != null) {
            form.restoreDraft(restored);
            restored = null;
        }
        form.ensureDraftInitialized();
        form.refreshVisual();
        refreshMarker();
    }

    /** "Less secure" only while the webhook is on and reports over plain http (UX concept 1.5). */
    private void refreshMarker() {
        if (lessSecure == null) return;
        Context context = lessSecure.getContext();
        boolean cleartext = KeepADBOnboarding.lessSecure(context)
                .contains(KeepADBOnboarding.LessSecure.WEBHOOK_CLEARTEXT);
        lessSecure.setVisibility(cleartext ? View.VISIBLE : View.GONE);
        if (form != null) form.refreshVisual();
    }

    private static TextView marker(Activity host) {
        TextView view = new TextView(host);
        view.setText(R.string.onboarding_badge_less_secure);
        view.setBackgroundResource(R.drawable.bg_badge_warn);
        view.setTextColor(host.getColor(R.color.text_yellow));
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        view.setTypeface(android.graphics.Typeface.create("sans-serif-condensed",
                android.graphics.Typeface.BOLD));
        float density = host.getResources().getDisplayMetrics().density;
        int h = (int) (6 * density + 0.5f);
        int v = (int) (2 * density + 0.5f);
        view.setPadding(h, v, h, v);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = (int) (8 * density + 0.5f);
        view.setLayoutParams(params);
        view.setVisibility(View.GONE);
        return view;
    }

    /** Everything the form wrote is already stored; "Next" has nothing left to write. */
    @Override
    void commit(Context context) {}

    @Override
    String summary(Context context) {
        return context.getString(KeepADBPreferences.isRegisterWebhookEnabled(context)
                ? R.string.onboarding_value_on : R.string.onboarding_value_off);
    }

    @Override
    void saveState(Bundle out) {
        if (form != null) form.saveState(out);
    }

    @Override
    void restoreState(Bundle in) {
        if (in.containsKey(KeepADBWebhookForm.STATE_WEBHOOK_DRAFT_URL)) {
            restored = new Bundle();
            restored.putString(KeepADBWebhookForm.STATE_WEBHOOK_DRAFT_URL,
                    in.getString(KeepADBWebhookForm.STATE_WEBHOOK_DRAFT_URL));
        }
    }
}
