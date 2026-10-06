package de.hohnepeople.keepadb;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.RadioButton;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * One choice card of the setup assistant (#761, UX concept 7.5): a title, an optional badge and a
 * body, the whole card being the touch target. Android's own radio group cannot manage cards that
 * hold other views, so {@link Group} keeps the exclusive selection.
 *
 * <p>For TalkBack the card is a single radio button: its spoken text is the title, the badge word
 * and the body in that order (the children are not focus targets), and the checked state is the
 * card's own.
 */
final class OnboardingChoiceCard {
    /** The word beside a card's title. The word carries the meaning, the colour only reinforces it. */
    enum Badge { NONE, RECOMMENDED, LESS_SECURE, NOTE }

    final View view;
    final TextView titleView;
    final TextView badgeView;
    final TextView bodyView;
    private final RadioButton indicator;
    private final Badge badge;
    private boolean checked;

    private OnboardingChoiceCard(View view, Badge badge) {
        this.view = view;
        this.badge = badge;
        this.titleView = view.findViewById(R.id.choice_title);
        this.badgeView = view.findViewById(R.id.choice_badge);
        this.bodyView = view.findViewById(R.id.choice_body);
        this.indicator = view.findViewById(R.id.choice_indicator);
    }

    static OnboardingChoiceCard add(Activity host, ViewGroup parent, CharSequence title,
                                    CharSequence body, Badge badge) {
        View card = LayoutInflater.from(host).inflate(R.layout.view_choice_card, parent, false);
        OnboardingChoiceCard result = new OnboardingChoiceCard(card, badge);
        result.titleView.setText(title);
        result.bodyView.setText(body);
        if (badge != Badge.NONE) {
            result.badgeView.setText(badgeLabel(badge));
            result.badgeView.setBackgroundResource(badgeBackground(badge));
            result.badgeView.setTextColor(host.getColor(badgeColor(badge)));
            result.badgeView.setVisibility(View.VISIBLE);
        }
        CharSequence spoken = result.spokenText();
        card.setContentDescription(spoken);
        card.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(RadioButton.class.getName());
                info.setCheckable(true);
                info.setChecked(result.checked);
            }
        });
        parent.addView(card);
        return result;
    }

    boolean isChecked() {
        return checked;
    }

    void setChecked(boolean value) {
        checked = value;
        indicator.setChecked(value);
        view.setActivated(value);
    }

    /** Title, badge word and body as TalkBack reads the card. */
    CharSequence spokenText() {
        StringBuilder text = new StringBuilder(titleView.getText());
        if (badge != Badge.NONE) text.append(", ").append(badgeView.getText());
        text.append(". ").append(bodyView.getText());
        return text;
    }

    private static int badgeLabel(Badge badge) {
        switch (badge) {
            case RECOMMENDED:
                return R.string.onboarding_badge_recommended;
            case LESS_SECURE:
                return R.string.onboarding_badge_less_secure;
            case NOTE:
            default:
                return R.string.onboarding_badge_note;
        }
    }

    private static int badgeBackground(Badge badge) {
        switch (badge) {
            case RECOMMENDED:
                return R.drawable.bg_badge_ok;
            case LESS_SECURE:
                return R.drawable.bg_badge_warn;
            case NOTE:
            default:
                return R.drawable.bg_badge_neutral;
        }
    }

    private static int badgeColor(Badge badge) {
        switch (badge) {
            case RECOMMENDED:
                return R.color.status_ok_green;
            case LESS_SECURE:
                return R.color.text_yellow;
            case NOTE:
            default:
                return R.color.night_muted;
        }
    }

    /** The exclusive selection over a set of cards. */
    static final class Group {
        interface Listener {
            void onSelected(int index);
        }

        private final List<OnboardingChoiceCard> cards = new ArrayList<>();
        private int selected = -1;
        private Listener listener;

        void setListener(Listener listener) {
            this.listener = listener;
        }

        /** Adds the card to the group; a tap on it selects it. */
        void register(OnboardingChoiceCard card) {
            final int index = cards.size();
            cards.add(card);
            card.view.setOnClickListener(v -> select(index));
        }

        int selectedIndex() {
            return selected;
        }

        /** Selects a card and tells the listener, if the selection moved. */
        void select(int index) {
            if (index == selected) return;
            selectQuietly(index);
            if (listener != null) listener.onSelected(index);
        }

        void selectQuietly(int index) {
            selected = index;
            for (int i = 0; i < cards.size(); i++) {
                cards.get(i).setChecked(i == index);
            }
        }
    }
}
