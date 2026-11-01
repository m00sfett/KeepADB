package de.hohnepeople.keepadb;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Scrollable security reasons and the explicit, reversible per-reason mute controls. #897: the
 * header bar of the other secondary screens, each reason as a home-style warning card, the mutes
 * in one panel.
 */
public final class WarningsActivity extends Activity {
    static final String EXTRA_MUTES = "warning_mutes";
    private LinearLayout content;
    private TextView heading;
    private String returnReason;
    private boolean reviewing;

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(KeepADBLocaleHelper.wrapContext(base));
    }
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (saved != null) {
            returnReason = saved.getString("warning_return_reason");
            reviewing = saved.getBoolean("warning_reviewing");
        }
        setContentView(R.layout.activity_warnings);
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        heading = findViewById(R.id.warnings_heading);
        heading.setText(getIntent().getBooleanExtra(EXTRA_MUTES, false)
                ? R.string.warnings_muted : R.string.warnings_title);
        content = findViewById(R.id.warnings_content);
        KeepADBWindowInsets.apply(getWindow(), findViewById(R.id.header_bar),
                findViewById(R.id.warnings_scroll));
    }
    @Override protected void onResume() {
        super.onResume();
        KeepADBForceMode.finishIfExpired(this);
        KeepADBForceMode.setStateListener(this::render);
        render();
    }
    @Override protected void onPause() {
        KeepADBForceMode.clearStateListener();
        super.onPause();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("warning_return_reason", returnReason);
        out.putBoolean("warning_reviewing", reviewing);
    }
    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
    /** Body copy in the card text style; {@code muted} for secondary notes. */
    private TextView label(String text, boolean muted) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(muted ? 13 : 14);
        label.setLineSpacing(dp(3), 1f);
        label.setTextColor(getColor(muted ? R.color.night_muted : R.color.night_text));
        label.setFocusable(true);
        return label;
    }
    private void add(LinearLayout parent, View view, int width, int topMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, -2);
        params.topMargin = dp(topMarginDp);
        parent.addView(view, params);
    }
    private void focus(View view) {
        view.setFocusable(true);
        view.requestFocusFromTouch();
        view.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
    }
    private void render() {
        content.removeAllViews();
        KeepADBWarningState.Snapshot snapshot = KeepADBWarningState.observe(this);
        View returnTarget = heading;
        if (getIntent().getBooleanExtra(EXTRA_MUTES, false)) {
            add(content, label(getString(R.string.warnings_muted_hint), false), -1, 0);
            LinearLayout panel = new LinearLayout(this);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setBackgroundResource(R.drawable.bg_panel);
            add(content, panel, -1, 16);
            boolean first = true;
            for (KeepADBWarningState.Reason reason : KeepADBWarningState.Reason.values()) {
                if (!reason.mutable) continue;
                boolean muted = snapshot.muted.contains(reason);
                Switch control = new Switch(this);
                control.setText(reason.label);
                // The Settings toggle look.
                control.setTypeface(android.graphics.Typeface.create("sans-serif-condensed",
                        android.graphics.Typeface.NORMAL));
                control.setTextSize(17);
                // Yellow while it can be switched, muted like a disabled button when it cannot.
                control.setTextColor(getColorStateList(R.color.btn_text_secondary));
                control.setMinHeight(dp(48));
                control.setChecked(muted);
                control.setEnabled(muted || snapshot.active.contains(reason));
                control.setStateDescription(getString(muted ? R.string.warnings_muted_state
                        : R.string.warnings_unmuted_state));
                add(panel, control, -1, first ? 0 : 8);
                first = false;
                if (!control.isEnabled()) {
                    add(panel, label(getString(R.string.warnings_inactive), true), -1, 0);
                }
                control.setOnClickListener(v -> {
                    KeepADBWarningState.mute(this, reason, control.isChecked());
                    returnReason = reason.id;
                    reviewing = true;
                    render();
                });
                if (reason.id.equals(returnReason)) returnTarget = control;
            }
            Button reset = new Button(this, null, 0, R.style.Widget_KeepADB_Button_Secondary);
            reset.setText(R.string.warnings_reset);
            reset.setEnabled(!snapshot.muted.isEmpty());
            reset.setOnClickListener(v -> {
                KeepADBWarningState.resetMutes(this);
                returnReason = null;
                reviewing = true;
                render();
            });
            LinearLayout.LayoutParams resetParams = new LinearLayout.LayoutParams(-2, -2);
            resetParams.topMargin = dp(16);
            resetParams.gravity = android.view.Gravity.END;
            content.addView(reset, resetParams);
        } else {
            LayoutInflater inflater = LayoutInflater.from(this);
            boolean first = true;
            for (KeepADBWarningState.Reason reason : snapshot.security()) {
                // The home warning card, without its text line and close button: here the
                // reason is the whole message, and closing belongs to the home screen.
                View card = inflater.inflate(R.layout.view_home_warning, content, false);
                TextView text = card.findViewById(R.id.home_warning_title);
                text.setText(reason.label);
                text.setFocusable(true);
                card.findViewById(R.id.home_warning_text).setVisibility(View.GONE);
                card.findViewById(R.id.home_warning_dismiss).setVisibility(View.GONE);
                Button review = card.findViewById(R.id.home_warning_action);
                review.setText(R.string.home_warning_action_review);
                review.setContentDescription(getString(R.string.home_warning_action_review)
                        + ": " + getString(reason.label));
                add(content, card, -1, first ? 0 : 12);
                first = false;
                review.setOnClickListener(v -> {
                    returnReason = reason.id;
                    reviewing = true;
                    startActivity(KeepADBWarningState.reviewIntent(this, reason));
                });
                if (reason.id.equals(returnReason)) returnTarget = text;
            }
            if (snapshot.security().isEmpty()) {
                add(content, label(getString(R.string.warnings_none), true), -1, 0);
            }
        }
        if (reviewing) {
            reviewing = false;
            View target = returnTarget;
            target.post(() -> focus(target));
        }
    }
}
