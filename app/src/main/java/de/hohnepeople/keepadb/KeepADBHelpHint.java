package de.hohnepeople.keepadb;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.widget.TextView;

/**
 * #959: one help text of the Protection page as a single line with "More". Collapsed, the view
 * shows a one-line summary followed by "More"; a tap shows the full text followed by "Less". The
 * full text is the unchanged text the setting always had, only the first look is short.
 *
 * <p>The whole view is the touch target, so TalkBack reads it as one button: the spoken text is
 * the line that is showing and the state is announced as expanded or collapsed. The expansion
 * lives only in this instance (like the Settings cards, #471): a fresh screen starts collapsed.
 */
final class KeepADBHelpHint {
    private final TextView view;
    private final Runnable onToggle;
    private CharSequence shortText = "";
    private CharSequence fullText = "";
    private boolean expanded;

    /**
     * @param onToggle called after a tap, once the view shows the other form; may be null.
     */
    KeepADBHelpHint(TextView view, Runnable onToggle) {
        this.view = view;
        this.onToggle = onToggle;
        view.setClickable(true);
        view.setFocusable(true);
        view.setOnClickListener(v -> {
            expanded = !expanded;
            render();
            if (this.onToggle != null) this.onToggle.run();
        });
    }

    /** Sets the one-line text and the full text; keeps the current expansion. */
    void set(CharSequence shortText, CharSequence fullText) {
        this.shortText = shortText;
        this.fullText = fullText;
        render();
    }

    boolean isExpanded() {
        return expanded;
    }

    private void render() {
        Context context = view.getContext();
        CharSequence body = expanded ? fullText : shortText;
        CharSequence label = context.getString(expanded ? R.string.hint_less : R.string.hint_more);
        SpannableStringBuilder text = new SpannableStringBuilder(body);
        text.append("  ");
        int start = text.length();
        text.append(label);
        text.setSpan(new ForegroundColorSpan(context.getColor(R.color.text_yellow)), start,
                text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        view.setText(text);
        view.setStateDescription(context.getString(expanded
                ? R.string.card_state_expanded : R.string.card_state_collapsed));
    }
}
