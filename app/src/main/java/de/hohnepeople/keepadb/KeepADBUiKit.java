package de.hohnepeople.keepadb;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * #1051: the programmatic view builders shared by the screens that build their cards in code
 * (the network list, the setup assistant steps): one rounded {@link #dp}, the panel, the status
 * badge, the responsive title line and the primary/secondary/plain buttons. Stateless; every
 * method takes the context it builds with.
 */
final class KeepADBUiKit {
    private KeepADBUiKit() {}

    /** {@code value} dp in pixels, rounded to the nearest pixel. */
    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /** A vertical, full-width card on {@code background}. */
    static LinearLayout panel(Context context, int background) {
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(background);
        panel.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return panel;
    }

    /** Plain text in {@code sp} and {@code colorRes}. */
    static TextView text(Context context, String value, int sp, int colorRes) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextColor(context.getColor(colorRes));
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return view;
    }

    /** Text in the condensed bold face of titles and labels. */
    static TextView condensed(Context context, String value, int sp, int colorRes) {
        TextView view = text(context, value, sp, colorRes);
        view.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        return view;
    }

    /**
     * A status word on its badge background. The word is part of the row's spoken line, so the
     * badge is no accessibility node of its own.
     */
    static TextView badge(Context context, String label, int background, int colorRes) {
        TextView view = new TextView(context);
        view.setText(label);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        view.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        view.setPadding(dp(context, 6), dp(context, 2), dp(context, 6), dp(context, 2));
        view.setBackgroundResource(background);
        view.setTextColor(context.getColor(colorRes));
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    /** Whether a title and its badge stack: a large font or a narrow display would cut one. */
    static boolean stacksTitleAndBadge(Context context) {
        Configuration config = context.getResources().getConfiguration();
        return config.fontScale >= 1.3f || config.screenWidthDp < 360;
    }

    /** Title and badge: side by side, or the badge below the title on a narrow or large display. */
    static LinearLayout titleLine(Context context, TextView title, TextView badge) {
        LinearLayout line = new LinearLayout(context);
        if (stacksTitleAndBadge(context)) {
            line.setOrientation(LinearLayout.VERTICAL);
            line.addView(title);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(context, 4);
            line.addView(badge, params);
        } else {
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.addView(title, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMarginStart(dp(context, 8));
            line.addView(badge, params);
        }
        return line;
    }

    /** A full-width primary or secondary button, 48 dp high, {@code topMarginDp} below the last. */
    static Button actionButton(Context context, String label, boolean primary,
            View.OnClickListener listener, int topMarginDp) {
        Button button = new Button(context);
        button.setBackgroundResource(primary ? R.drawable.bg_btn_primary : R.drawable.bg_btn_secondary);
        button.setMinHeight(dp(context, 48));
        button.setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8));
        button.setTextColor(context.getColor(primary ? R.color.title_yellow : R.color.text_yellow));
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        button.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        button.setAllCaps(false);
        button.setText(label);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(context, topMarginDp);
        button.setLayoutParams(params);
        return button;
    }

    /** A full-width borderless button for the least prominent action, 8 dp below the last. */
    static Button plainButton(Context context, String label, View.OnClickListener listener) {
        Button button = new Button(context, null, android.R.attr.borderlessButtonStyle);
        button.setMinHeight(dp(context, 48));
        button.setTextColor(context.getColor(R.color.night_muted));
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        button.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        button.setAllCaps(false);
        button.setText(label);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(context, 8);
        button.setLayoutParams(params);
        return button;
    }

    /**
     * A tappable fold line ("label ▾/▴") in the accent color, 48 dp high, with its expanded state
     * for TalkBack.
     */
    static TextView foldToggle(TextView toggle, Context context, String label, boolean open,
            View.OnClickListener listener) {
        toggle.setText(label + (open ? "  ▴" : "  ▾"));
        toggle.setTextColor(context.getColor(R.color.text_yellow));
        toggle.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        toggle.setMinHeight(dp(context, 48));
        toggle.setGravity(Gravity.CENTER_VERTICAL);
        toggle.setClickable(true);
        toggle.setFocusable(true);
        toggle.setStateDescription(context.getString(open
                ? R.string.card_state_expanded : R.string.card_state_collapsed));
        toggle.setOnClickListener(listener);
        return toggle;
    }
}
