package de.hohnepeople.keepadb;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** Scrollable security reasons and the explicit, reversible per-reason mute controls. */
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
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.ground));
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(16), dp(8), dp(16), dp(8));
        Button back = new Button(this);
        back.setText(R.string.back);
        back.setMinHeight(dp(48));
        back.setOnClickListener(v -> finish());
        header.addView(back);
        heading = label(getIntent().getBooleanExtra(EXTRA_MUTES, false)
                ? getString(R.string.warnings_muted) : getString(R.string.warnings_title));
        heading.setTextSize(22);
        heading.setAccessibilityHeading(true);
        header.addView(heading);
        root.addView(header);
        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setPadding(dp(16), dp(8), dp(16), dp(16));
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        KeepADBWindowInsets.apply(getWindow(), header, scroll);
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
    private TextView label(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(16);
        label.setTextColor(getColor(R.color.night_text));
        label.setFocusable(true);
        return label;
    }
    private void spaced(View view) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(8);
        content.addView(view, params);
    }
    private void focus(View view) {
        view.setFocusableInTouchMode(true);
        view.requestFocus();
        view.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
    }
    private void render() {
        content.removeAllViews();
        KeepADBWarningState.Snapshot snapshot = KeepADBWarningState.observe(this);
        View returnTarget = heading;
        if (getIntent().getBooleanExtra(EXTRA_MUTES, false)) {
            spaced(label(getString(R.string.warnings_muted_hint)));
            for (KeepADBWarningState.Reason reason : KeepADBWarningState.Reason.values()) {
                if (!reason.mutable) continue;
                boolean muted = snapshot.muted.contains(reason);
                Switch control = new Switch(this);
                control.setText(reason.label);
                control.setTextSize(16);
                control.setTextColor(getColor(R.color.night_text));
                control.setMinHeight(dp(48));
                control.setChecked(muted);
                control.setEnabled(muted || snapshot.active.contains(reason));
                control.setStateDescription(getString(muted ? R.string.warnings_muted_state
                        : R.string.warnings_unmuted_state));
                spaced(control);
                if (!control.isEnabled()) spaced(label(getString(R.string.warnings_inactive)));
                control.setOnClickListener(v -> {
                    KeepADBWarningState.mute(this, reason, control.isChecked());
                    returnReason = reason.id;
                    reviewing = true;
                    render();
                });
                if (reason.id.equals(returnReason)) returnTarget = control;
            }
            Button reset = new Button(this);
            reset.setText(R.string.warnings_reset);
            reset.setMinHeight(dp(48));
            reset.setEnabled(!snapshot.muted.isEmpty());
            reset.setOnClickListener(v -> {
                KeepADBWarningState.resetMutes(this);
                returnReason = null;
                reviewing = true;
                render();
            });
            spaced(reset);
        } else {
            for (KeepADBWarningState.Reason reason : snapshot.security()) {
                TextView text = label(getString(reason.label));
                spaced(text);
                Button review = new Button(this);
                review.setText(R.string.home_warning_action_review);
                review.setContentDescription(getString(R.string.home_warning_action_review)
                        + ": " + getString(reason.label));
                review.setMinHeight(dp(48));
                spaced(review);
                review.setOnClickListener(v -> {
                    returnReason = reason.id;
                    reviewing = true;
                    startActivity(KeepADBWarningState.reviewIntent(this, reason));
                });
                if (reason.id.equals(returnReason)) returnTarget = text;
            }
            if (snapshot.security().isEmpty()) spaced(label(getString(R.string.warnings_none)));
        }
        if (reviewing) {
            reviewing = false;
            View target = returnTarget;
            target.post(() -> focus(target));
        }
    }
}
