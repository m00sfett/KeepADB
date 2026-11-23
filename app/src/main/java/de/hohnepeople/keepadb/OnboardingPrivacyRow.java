package de.hohnepeople.keepadb;

import android.app.Activity;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * #958: the privacy switches of the intro page, collapsed to one row. On the left a summary of
 * what is switched on, on the right a "Change" button; tapping either one (or the summary)
 * opens the switches in place, and the button reads "Done" while they are open. The switches
 * keep their keys and their behavior ({@link KeepADBPrivacySwitches}); only their resting height
 * changed, so the advice above them and the intro text stay visible without scrolling.
 *
 * <p>The row stays on the first page so it still comes before any step that shows a network
 * name (#904).
 */
final class OnboardingPrivacyRow {
    /** The switches the summary counts: app, notifications, lock screen option, widgets. */
    static final int SWITCH_COUNT = 4;

    private final Activity activity;
    private final View section;
    private final TextView summary;
    private final View row;
    private final Button button;
    private final View switches;
    private boolean expanded;

    private OnboardingPrivacyRow(Activity activity, View section, LinearLayout row,
            TextView summary, Button button, View switches, boolean expanded) {
        this.activity = activity;
        this.section = section;
        this.row = row;
        this.summary = summary;
        this.button = button;
        this.switches = switches;
        this.expanded = expanded;
    }

    /** How many of the four privacy switches are on. */
    static int countOn(Activity activity) {
        int on = 0;
        if (KeepADBPreferences.isAppPrivacyEnabled(activity)) on++;
        if (KeepADBPreferences.isNotificationPrivacyEnabled(activity)) on++;
        if (KeepADBPreferences.isLockScreenPrivacyEnabled(activity)) on++;
        if (KeepADBPreferences.isWidgetPrivacyEnabled(activity)) on++;
        return on;
    }

    /** "Privacy: nothing hidden" with every switch off, "Privacy: 2 of 4 on" otherwise. */
    static String summaryText(Activity activity) {
        int on = countOn(activity);
        return on == 0
                ? activity.getString(R.string.onboarding_privacy_summary_off)
                : activity.getString(R.string.onboarding_privacy_summary_on, on, SWITCH_COUNT);
    }

    /**
     * Builds the row and the (initially closed or open) switches below it into one vertical
     * section; {@link #view} is what the caller adds to the page.
     */
    static OnboardingPrivacyRow create(Activity activity, boolean expanded, Runnable onExpandedChanged) {
        LinearLayout section = new LinearLayout(activity);
        section.setOrientation(LinearLayout.VERTICAL);

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(activity, 48));
        row.setClickable(true);
        row.setFocusable(true);

        TextView summary = new TextView(activity);
        summary.setId(R.id.onboarding_privacy_summary);
        summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        summary.setTextColor(activity.getColor(R.color.title_yellow));
        summary.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        summaryParams.setMarginEnd(dp(activity, 12));
        row.addView(summary, summaryParams);

        Button button = new Button(activity);
        button.setId(R.id.onboarding_privacy_toggle);
        button.setBackgroundResource(R.drawable.bg_btn_secondary);
        button.setMinHeight(dp(activity, 48));
        button.setMinWidth(dp(activity, 48));
        button.setPadding(dp(activity, 16), dp(activity, 8), dp(activity, 16), dp(activity, 8));
        button.setTextColor(activity.getColor(R.color.text_yellow));
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        button.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        button.setAllCaps(false);
        row.addView(button, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        section.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View switches = activity.getLayoutInflater().inflate(R.layout.view_privacy_switches,
                section, false);
        section.addView(switches);
        // The switches write at once; their summary follows every change.
        OnboardingPrivacyRow result = new OnboardingPrivacyRow(
                activity, section, row, summary, button, switches, expanded);
        KeepADBPrivacySwitches.bind(activity, switches, result::render);

        View.OnClickListener toggle = v -> {
            result.expanded = !result.expanded;
            result.render();
            if (onExpandedChanged != null) onExpandedChanged.run();
        };
        row.setOnClickListener(toggle);
        button.setOnClickListener(toggle);
        result.render();
        return result;
    }

    View view() {
        return section;
    }

    /** Whether the switches are open; kept across a rotation by the activity. */
    boolean isExpanded() {
        return expanded;
    }

    private void render() {
        String text = summaryText(activity);
        summary.setText(text);
        button.setText(expanded ? R.string.onboarding_done : R.string.onboarding_privacy_change);
        switches.setVisibility(expanded ? View.VISIBLE : View.GONE);
        // TalkBack: the row reads as the summary and announces whether the switches are open.
        row.setContentDescription(text);
        row.setStateDescription(activity.getString(expanded
                ? R.string.card_state_expanded : R.string.card_state_collapsed));
    }

    private static int dp(Activity activity, int value) {
        return KeepADBUiKit.dp(activity, value);
    }
}
