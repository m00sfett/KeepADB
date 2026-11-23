package de.hohnepeople.keepadb;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * #1048: the warning cards of the home screen, split out of {@link MainActivity}: W1/W3/W4/W5 from
 * {@link KeepADBHomeWarnings}, the force card W2 (#763), the security triangle, the feedback bar
 * after a card was closed (undo, mute, its timeout #889 and swipe #906), the expanded state (#963)
 * and the mute choice. Their state survives a rotation through {@link #save}/{@link #restore}.
 */
final class HomeWarningsController {
    /** #889: the feedback bar after closing a card goes away by itself, like a snackbar. */
    static final int FEEDBACK_TIMEOUT_MS = 10_000;
    private static final String STATE_EXPANDED = "warnings_expanded";
    private static final String STATE_RETURN_TO_TRIANGLE = "warnings_return_to_triangle";
    private static final String STATE_CLOSED_CARD = "warnings_closed_card";
    private static final String STATE_CLOSED_REASONS = "warnings_closed_reasons";
    private static final String STATE_CLOSED_EPISODE = "warnings_closed_episode";
    private static final String STATE_CLOSED_REASON_EPISODES = "warnings_closed_reason_episodes";

    private final Activity activity;
    private final Runnable refresh;
    /** Whether the screen is in front; focus moves only then (after returning from the list). */
    private final BooleanSupplier surfaceActive;
    private final View warningSystem;
    private final View warningLessSecure;
    private final View warningPaused;
    private final View warningLimited;
    private final View forceWarningPanel;
    private final TextView forceWarningText;
    private final KeepADBSwipeDismissLayout feedbackBar;
    private final TextView feedbackText;
    private final View muteButton;
    private final View triangle;

    private KeepADBWarningState.Snapshot warningSnapshot;
    private KeepADBWarningState.Snapshot dismissedSnapshot;
    private KeepADBWarningState.Card dismissedCard;
    private boolean returningFromWarnings;
    /**
     * #963: the warnings whose explanation is open. A warning that is not shown is not in the set,
     * so one that goes away and comes back starts collapsed; the set survives the periodic refresh
     * and a rotation.
     */
    private final EnumSet<KeepADBWarningState.Card> expandedWarnings =
            EnumSet.noneOf(KeepADBWarningState.Card.class);
    /** #1047: the mute choice, dismissed with the activity so it cannot leak its window. */
    private AlertDialog activeMuteDialog;
    private final Runnable expireFeedback = () -> {
        dismissedCard = null;
        renderFeedback();
    };

    HomeWarningsController(Activity activity, Runnable refresh, BooleanSupplier surfaceActive) {
        this.activity = activity;
        this.refresh = refresh;
        this.surfaceActive = surfaceActive;
        warningSystem = activity.findViewById(R.id.warning_system);
        warningLessSecure = activity.findViewById(R.id.warning_less_secure);
        warningPaused = activity.findViewById(R.id.warning_paused);
        warningLimited = activity.findViewById(R.id.warning_limited);
        forceWarningPanel = activity.findViewById(R.id.force_warning_panel);
        forceWarningText = activity.findViewById(R.id.force_warning_text);
        feedbackBar = activity.findViewById(R.id.warning_feedback);
        feedbackText = activity.findViewById(R.id.warning_feedback_text);
        muteButton = activity.findViewById(R.id.warning_mute);
        triangle = activity.findViewById(R.id.btn_security_warnings);
        // #906: swiping the feedback bar away ends it early, like its timeout; the card stays closed.
        feedbackBar.setOnDismissListener(expireFeedback::run);
        activity.findViewById(R.id.btn_force_end).setOnClickListener(v -> {
            if (KeepADBForceMode.endNow(activity)) KeepADBForceNotice.showEndedToast(activity);
            refresh.run();
        });
        // #959: the force row is on the Protection page.
        activity.findViewById(R.id.btn_force_settings).setOnClickListener(
                v -> activity.startActivity(ProtectionActivity.forceIntent(activity)));
        triangle.setOnClickListener(v -> {
            returningFromWarnings = true;
            activity.startActivity(new Intent(activity, WarningsActivity.class));
        });
        activity.findViewById(R.id.warning_undo).setOnClickListener(v -> {
            if (dismissedCard != null && KeepADBWarningState.undo(activity, dismissedCard, dismissedSnapshot)) {
                KeepADBWarningState.Card restored = dismissedCard;
                dismissedCard = null;
                render();
                View card = cardView(restored);
                if (card.getVisibility() == View.VISIBLE) focus(card);
                else focus(activity.findViewById(R.id.btn_open_settings));
            }
        });
        muteButton.setOnClickListener(v -> chooseMutes());
    }

    // ---- State across a rotation ----

    void restore(Bundle saved) {
        if (saved == null) return;
        restoreFeedback(saved);
        restoreExpanded(saved);
        returningFromWarnings = saved.getBoolean(STATE_RETURN_TO_TRIANGLE);
        if (dismissedCard != null) armFeedbackTimeout();
    }

    void save(Bundle outState) {
        if (dismissedCard != null) {
            outState.putString(STATE_CLOSED_CARD, dismissedCard.name());
            ArrayList<String> reasons = new ArrayList<>();
            for (KeepADBWarningState.Reason reason : dismissedSnapshot.reasons(dismissedCard)) reasons.add(reason.name());
            outState.putStringArrayList(STATE_CLOSED_REASONS, reasons);
            outState.putString(STATE_CLOSED_EPISODE, dismissedSnapshot.forceEpisode);
            Bundle episodes = new Bundle();
            for (KeepADBWarningState.Reason reason : dismissedSnapshot.reasons(dismissedCard)) {
                episodes.putString(reason.name(), dismissedSnapshot.reasonEpisodes.get(reason));
            }
            outState.putBundle(STATE_CLOSED_REASON_EPISODES, episodes);
        }
        outState.putBoolean(STATE_RETURN_TO_TRIANGLE, returningFromWarnings);
        ArrayList<String> expanded = new ArrayList<>();
        for (KeepADBWarningState.Card card : expandedWarnings) expanded.add(card.name());
        outState.putStringArrayList(STATE_EXPANDED, expanded);
    }

    void destroy() {
        feedbackBar.removeCallbacks(expireFeedback);
        // #906: a swipe-out animation still running must not dismiss anything after destruction.
        feedbackBar.resetSwipe();
        if (activeMuteDialog != null) activeMuteDialog.dismiss();
        activeMuteDialog = null;
    }

    /** Test hook (#1047): the mute choice while it is open, or null. */
    AlertDialog activeMuteDialog() {
        return activeMuteDialog;
    }

    // ---- Rendering ----

    /**
     * #763: the warning card while the force mode is on, with its end ("until 14:30" within today,
     * otherwise with the date) or "no end time". Read from the pure state, so it is gone at the
     * deadline even before the expiry transition ran.
     */
    void renderForceWarning() {
        KeepADBForceMode.Status force = KeepADBForceMode.status(activity);
        forceWarningPanel.setVisibility(force == null ? View.GONE : View.VISIBLE);
        if (force == null) return;
        String text = force.isUnlimited() ? activity.getString(R.string.force_card_text_unlimited)
                : activity.getString(R.string.force_card_text_until, KeepADBForceMode.formatEnd(activity, force));
        if (!TextUtils.equals(forceWarningText.getText(), text)) forceWarningText.setText(text);
        // #963: the remaining time is state, not explanation: it stays in the collapsed row.
        String status = force.isUnlimited() ? activity.getString(R.string.force_status_unlimited)
                : activity.getString(R.string.force_status_until, KeepADBForceMode.formatEnd(activity, force));
        String title = activity.getString(R.string.force_card_title);
        android.text.SpannableStringBuilder line = new android.text.SpannableStringBuilder(title)
                .append('\n').append(status);
        int from = title.length() + 1;
        line.setSpan(new android.text.style.RelativeSizeSpan(0.8f), from, line.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        line.setSpan(new android.text.style.ForegroundColorSpan(activity.getColor(R.color.night_muted)),
                from, line.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ((KeepADBWarningRow) forceWarningPanel.findViewById(R.id.warning_row))
                .setTitle(line, title + ", " + status);
    }

    /**
     * #764: the warning cards. The force card (W2) is drawn by {@link #renderForceWarning}; the
     * others come from {@link KeepADBHomeWarnings}, each leading to the assistant's matching step.
     */
    void render() {
        warningSnapshot = KeepADBWarningState.observe(activity);
        bind(warningSystem, KeepADBWarningState.Card.SYSTEM,
                R.string.home_warning_system_title, activity.getString(R.string.home_warning_system_text),
                R.string.home_warning_action_guide,
                () -> activity.startActivity(KeepADBHomeWarnings.systemIntent(activity)));
        bind(warningLessSecure, KeepADBWarningState.Card.LESS_SECURE,
                R.string.home_warning_less_secure_title,
                reasonText(KeepADBWarningState.Card.LESS_SECURE),
                R.string.home_warning_action_review,
                () -> activity.startActivity(KeepADBWarningState.reviewIntent(activity,
                        warningSnapshot.reasons(KeepADBWarningState.Card.LESS_SECURE).iterator().next())));
        bind(warningPaused, KeepADBWarningState.Card.PAUSED,
                R.string.home_warning_paused_title, activity.getString(R.string.home_warning_paused_text),
                R.string.home_warning_action_fix,
                () -> activity.startActivity(KeepADBNetworkTrustPrompt.identityUnavailableFixIntent(activity)));
        bind(warningLimited, KeepADBWarningState.Card.LIMITED,
                R.string.home_warning_limited_title,
                reasonText(KeepADBWarningState.Card.LIMITED),
                R.string.home_warning_action_fix,
                () -> activity.startActivity(KeepADBWarningState.reviewIntent(activity,
                        warningSnapshot.reasons(KeepADBWarningState.Card.LIMITED).iterator().next())));
        forceWarningPanel.setVisibility(warningSnapshot.visible.contains(KeepADBWarningState.Card.FORCE)
                ? View.VISIBLE : View.GONE);
        bindExpansion(forceWarningPanel, KeepADBWarningState.Card.FORCE);
        bindDismiss(warningSystem, KeepADBWarningState.Card.SYSTEM, R.string.home_warning_system_title);
        bindDismiss(warningLessSecure, KeepADBWarningState.Card.LESS_SECURE, R.string.home_warning_less_secure_title);
        bindDismiss(warningPaused, KeepADBWarningState.Card.PAUSED, R.string.home_warning_paused_title);
        bindDismiss(warningLimited, KeepADBWarningState.Card.LIMITED, R.string.home_warning_limited_title);
        bindDismiss(forceWarningPanel, KeepADBWarningState.Card.FORCE, R.string.force_card_title);
        int count = warningSnapshot.security().size();
        triangle.setVisibility(count == 0 ? View.GONE : View.VISIBLE);
        triangle.setContentDescription(activity.getResources().getQuantityString(R.plurals.warnings_count, count, count));
        renderFeedback();
        if (returningFromWarnings && surfaceActive.getAsBoolean()) {
            returningFromWarnings = false;
            View target = count == 0 ? activity.findViewById(R.id.btn_open_settings) : triangle;
            target.post(() -> focus(target));
        }
    }

    private String reasonText(KeepADBWarningState.Card card) {
        List<String> labels = new ArrayList<>();
        if (card == KeepADBWarningState.Card.LIMITED) {
            labels.add(activity.getString(R.string.home_warning_limited_text));
        }
        for (KeepADBWarningState.Reason reason : warningSnapshot.reasons(card)) labels.add(activity.getString(reason.label));
        return TextUtils.join("\n", labels);
    }

    private void bindDismiss(View card, KeepADBWarningState.Card kind, int title) {
        View close = card.findViewById(kind == KeepADBWarningState.Card.FORCE
                ? R.id.force_warning_dismiss : R.id.home_warning_dismiss);
        close.setContentDescription(activity.getString(R.string.warnings_close, activity.getString(title)));
        KeepADBWarningState.Snapshot shown = warningSnapshot;
        close.setOnClickListener(v -> {
            if (!KeepADBWarningState.dismiss(activity, kind, shown)) return;
            // #906: a swipe of the previous bar still animating must not dismiss this new one.
            feedbackBar.resetSwipe();
            dismissedCard = kind;
            dismissedSnapshot = shown;
            render();
            focus(feedbackText);
            armFeedbackTimeout();
        });
    }

    /**
     * #889: (re)starts the timeout of the feedback bar. Android lengthens it for users who asked
     * for more time to react (accessibility setting), as for any message with controls.
     */
    private void armFeedbackTimeout() {
        feedbackBar.removeCallbacks(expireFeedback);
        AccessibilityManager accessibility = activity.getSystemService(AccessibilityManager.class);
        int timeout = accessibility == null ? FEEDBACK_TIMEOUT_MS
                : accessibility.getRecommendedTimeoutMillis(FEEDBACK_TIMEOUT_MS,
                        AccessibilityManager.FLAG_CONTENT_TEXT | AccessibilityManager.FLAG_CONTENT_CONTROLS);
        feedbackBar.postDelayed(expireFeedback, timeout);
    }

    private static void focus(View view) {
        view.setFocusable(true);
        // Preserve normal first-tap activation after returning keyboard/accessibility focus.
        view.requestFocusFromTouch();
        view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
    }

    private View cardView(KeepADBWarningState.Card card) {
        switch (card) {
            case SYSTEM: return warningSystem;
            case FORCE: return forceWarningPanel;
            case LESS_SECURE: return warningLessSecure;
            case PAUSED: return warningPaused;
            default: return warningLimited;
        }
    }

    private static int title(KeepADBWarningState.Card card) {
        switch (card) {
            case SYSTEM: return R.string.home_warning_system_title;
            case FORCE: return R.string.force_card_title;
            case LESS_SECURE: return R.string.home_warning_less_secure_title;
            case PAUSED: return R.string.home_warning_paused_title;
            default: return R.string.home_warning_limited_title;
        }
    }

    private void renderFeedback() {
        feedbackBar.setVisibility(dismissedCard == null ? View.GONE : View.VISIBLE);
        if (dismissedCard == null) {
            feedbackBar.removeCallbacks(expireFeedback);
            // #906: put a swiped-away bar back in place while hidden, never during a drag.
            feedbackBar.resetSwipe();
            return;
        }
        String text = activity.getString(R.string.warnings_closed, activity.getString(title(dismissedCard)));
        if (!TextUtils.equals(feedbackText.getText(), text)) feedbackText.setText(text);
        muteButton.setVisibility(mutableClosedReasons().isEmpty() ? View.GONE : View.VISIBLE);
    }

    private List<KeepADBWarningState.Reason> mutableClosedReasons() {
        List<KeepADBWarningState.Reason> reasons = new ArrayList<>();
        if (dismissedCard == null) return reasons;
        for (KeepADBWarningState.Reason reason : dismissedSnapshot.reasons(dismissedCard)) {
            if (reason.mutable && warningSnapshot.active.contains(reason) && !warningSnapshot.muted.contains(reason)
                    && KeepADBWarningState.sameEpisode(dismissedSnapshot, warningSnapshot, reason)) {
                reasons.add(reason);
            }
        }
        return reasons;
    }

    private void chooseMutes() {
        List<KeepADBWarningState.Reason> reasons = mutableClosedReasons();
        if (reasons.isEmpty()) return;
        if (reasons.size() == 1) {
            muteClosed(reasons.get(0));
            render();
            return;
        }
        // A scrolling native list, with no default selections, can grow with the font size.
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        // #905: a custom view gets no dialog inset of its own; use the title's 24 dp so the list
        // and the primary button keep the same margin as the title and the Cancel button.
        int inset = (int) (24 * activity.getResources().getDisplayMetrics().density);
        content.setPadding(inset, 0, inset, 0);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);
        List<CheckBox> choices = new ArrayList<>();
        for (KeepADBWarningState.Reason reason : reasons) {
            CheckBox choice = new CheckBox(activity);
            choice.setText(reason.label);
            choice.setMinHeight((int) (48 * activity.getResources().getDisplayMetrics().density));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.topMargin = (int) (8 * activity.getResources().getDisplayMetrics().density);
            content.addView(choice, params);
            choices.add(choice);
        }
        // #896: the app's primary button (muted while nothing is selected), not the platform default.
        Button apply = new Button(activity, null, 0, R.style.Widget_KeepADB_Button_Primary);
        apply.setText(R.string.warnings_mute_selected);
        apply.setEnabled(false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.topMargin = (int) (16 * activity.getResources().getDisplayMetrics().density);
        params.gravity = Gravity.END;
        content.addView(apply, params);
        for (CheckBox choice : choices) choice.setOnCheckedChangeListener((button, checked) -> {
            boolean any = false;
            for (CheckBox option : choices) any |= option.isChecked();
            apply.setEnabled(any);
        });
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(R.string.warnings_mute)
                .setView(scroll).setNegativeButton(android.R.string.cancel, null).create();
        apply.setOnClickListener(v -> {
            for (int i = 0; i < reasons.size(); i++) if (choices.get(i).isChecked()) muteClosed(reasons.get(i));
            dialog.dismiss();
            render();
            focus(feedbackText);
        });
        if (activeMuteDialog != null) activeMuteDialog.dismiss();
        activeMuteDialog = dialog;
        dialog.setOnDismissListener(d -> {
            if (activeMuteDialog == d) activeMuteDialog = null;
        });
        dialog.show();
    }

    private void muteClosed(KeepADBWarningState.Reason reason) {
        KeepADBWarningState.muteClosed(activity, reason, dismissedCard, dismissedSnapshot);
    }

    private void restoreFeedback(Bundle saved) {
        String name = saved.getString(STATE_CLOSED_CARD);
        if (name == null) return;
        try {
            dismissedCard = KeepADBWarningState.Card.valueOf(name);
            Set<KeepADBWarningState.Reason> reasons = EnumSet.noneOf(KeepADBWarningState.Reason.class);
            ArrayList<String> names = saved.getStringArrayList(STATE_CLOSED_REASONS);
            if (names != null) for (String reason : names) reasons.add(KeepADBWarningState.Reason.valueOf(reason));
            Map<KeepADBWarningState.Reason, String> episodes = new EnumMap<>(KeepADBWarningState.Reason.class);
            Bundle savedEpisodes = saved.getBundle(STATE_CLOSED_REASON_EPISODES);
            if (savedEpisodes != null) for (KeepADBWarningState.Reason reason : reasons) {
                String episode = savedEpisodes.getString(reason.name());
                if (episode != null) episodes.put(reason, episode);
            }
            dismissedSnapshot = new KeepADBWarningState.Snapshot(reasons,
                    EnumSet.noneOf(KeepADBWarningState.Reason.class),
                    EnumSet.noneOf(KeepADBWarningState.Card.class), saved.getString(STATE_CLOSED_EPISODE, ""), episodes);
        } catch (IllegalArgumentException invalid) { dismissedCard = null; }
    }

    private void bind(View card, KeepADBWarningState.Card kind, int titleRes, String text,
            int actionRes, Runnable onAction) {
        boolean visible = warningSnapshot.visible.contains(kind);
        card.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) {
            // #963: a warning that goes away and returns starts collapsed.
            expandedWarnings.remove(kind);
            return;
        }
        String title = activity.getString(titleRes);
        ((KeepADBWarningRow) card.findViewById(R.id.warning_row)).setTitle(title, title);
        TextView body = card.findViewById(R.id.home_warning_text);
        if (!TextUtils.equals(body.getText(), text)) body.setText(text);
        Button action = card.findViewById(R.id.home_warning_action);
        action.setText(actionRes);
        action.setOnClickListener(v -> onAction.run());
        bindExpansion(card, kind);
    }

    /**
     * #963: a tap on the row (not on its buttons) opens or closes the explanation below it. The
     * state lives in {@link #expandedWarnings}, so a refresh that binds the card again keeps it.
     */
    private void bindExpansion(View card, KeepADBWarningState.Card kind) {
        if (card.getVisibility() != View.VISIBLE) {
            expandedWarnings.remove(kind);
            return;
        }
        KeepADBWarningRow row = card.findViewById(R.id.warning_row);
        row.setOnClickListener(v -> {
            if (!expandedWarnings.remove(kind)) expandedWarnings.add(kind);
            showExpansion(card, kind);
        });
        showExpansion(card, kind);
    }

    private void showExpansion(View card, KeepADBWarningState.Card kind) {
        boolean expanded = expandedWarnings.contains(kind);
        ((KeepADBWarningRow) card.findViewById(R.id.warning_row)).setExpanded(expanded);
        card.findViewById(R.id.warning_details).setVisibility(expanded ? View.VISIBLE : View.GONE);
    }

    private void restoreExpanded(Bundle saved) {
        ArrayList<String> names = saved.getStringArrayList(STATE_EXPANDED);
        if (names == null) return;
        for (String name : names) {
            try {
                expandedWarnings.add(KeepADBWarningState.Card.valueOf(name));
            } catch (IllegalArgumentException ignored) {
                // A name from another version: that warning simply starts collapsed.
            }
        }
    }
}
