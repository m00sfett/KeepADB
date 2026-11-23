package de.hohnepeople.keepadb;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The views shared by the setup assistant steps that act instead of choosing (#767, UX concept 1.3
 * steps 3 and 4): {@link OnboardingPermissionsStep} (the permissions Android has to give) and
 * {@link OnboardingNetworkStep} (the Wi-Fi to trust). Their actions take effect at once (a
 * permission, a trust), so "Next" has nothing left to write and these steps may be skipped; that
 * keeps the one rule of the assistant true for them: "Next" without input changes no setting.
 * #1051: the steps are classes of their own; this class keeps the row status word ({@link Tone})
 * and the row builders on top of {@link KeepADBUiKit}.
 */
final class OnboardingActionSteps {
    private OnboardingActionSteps() {}





    // ---- Views ------------------------------------------------------------------------------------

    /** The status word of a row: shown as text, the colour only reinforces it. */
    enum Tone {
        DONE(R.string.onboarding_badge_done, R.drawable.bg_badge_ok, R.color.status_ok_green),
        MISSING(R.string.onboarding_badge_missing, R.drawable.bg_badge_warn, R.color.text_yellow),
        RECOMMENDED(R.string.onboarding_badge_recommended, R.drawable.bg_badge_neutral,
                R.color.night_muted),
        OPTIONAL(R.string.onboarding_badge_optional, R.drawable.bg_badge_neutral,
                R.color.night_muted);

        final int labelRes;
        final int background;
        final int color;

        Tone(int labelRes, int background, int color) {
            this.labelRes = labelRes;
            this.background = background;
            this.color = color;
        }
    }

    static LinearLayout panel(Activity host) {
        LinearLayout panel = KeepADBUiKit.panel(host, R.drawable.bg_panel);
        ((LinearLayout.LayoutParams) panel.getLayoutParams()).topMargin = dp(host, 8);
        return panel;
    }

    /**
     * Title and status word: side by side, or the word below the title on a narrow display or a
     * large font, where two things in a row would cut one of them. Read as one line:
     * "Title, Status".
     */
    static View titleLine(Activity host, String title, String statusWord, Tone tone) {
        TextView titleView = text(host, title, 16, R.color.night_text, 0);
        titleView.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        titleView.setAccessibilityHeading(true);
        titleView.setContentDescription(title + ", " + statusWord);

        // The word is already part of the title's spoken line.
        TextView badge = KeepADBUiKit.badge(host, statusWord, tone.background, tone.color);
        return KeepADBUiKit.titleLine(host, titleView, badge);
    }

    static TextView body(Activity host, String text) {
        return text(host, text, 14, R.color.night_text, 8);
    }

    static TextView muted(Activity host, String text) {
        return text(host, text, 13, R.color.night_muted, 8);
    }

    static TextView label(Activity host, String text) {
        TextView view = text(host, text, 13, R.color.night_text, 12);
        view.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        return view;
    }

    static Button button(Activity host, String label, boolean primary,
            View.OnClickListener listener) {
        return actionButton(host, label, primary, listener);
    }

    static TextView text(Activity host, String value, int sp, int colorRes, int topDp) {
        TextView view = new TextView(host);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(host.getColor(colorRes));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(host, topDp);
        view.setLayoutParams(params);
        return view;
    }

    static Button actionButton(Activity host, String label, boolean primary,
                                       View.OnClickListener listener) {
        return KeepADBUiKit.actionButton(host, label, primary, listener, 12);
    }

    static int dp(Context context, int value) {
        return KeepADBUiKit.dp(context, value);
    }
}
