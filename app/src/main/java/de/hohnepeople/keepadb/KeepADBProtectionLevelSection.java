package de.hohnepeople.keepadb;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * #959: the protection level on the Protection page, chosen directly there. This replaces three
 * controls of one value: the level line that opened the setup assistant, the "Also trust by
 * Wi-Fi name" switch of the Settings card and the same switch in the Wi-Fi networks list.
 *
 * <p>Two presets can be chosen, "Maximum security" and "Balanced". A previous setting ("All
 * Wi-Fi networks" or the old name list) is shown first, marked and selected for as long as it is
 * stored; it is never a target, and a preset is the way back from it. Nothing is decided here:
 * a tap writes through {@link KeepADBOnboarding#commitProtection}, the same rule the setup
 * assistant uses (the by-name value keeps its key and meaning, a previous list is switched off
 * and not emptied), and what is selected is derived from the stored policy by {@link
 * KeepADBTrustedNetwork#getProtectionLevel} on every {@link #refresh()}. So the cards cannot
 * drift away from the stored value, and opening the page writes nothing.
 *
 * <p>The cards use OnClick through the group's selection and never a checked-change listener on a
 * refresh: {@link #refresh()} selects quietly.
 */
final class KeepADBProtectionLevelSection {
    private final Activity activity;
    private final Runnable onChanged;
    private final View panel;
    private final View forceNote;
    private final OnboardingChoiceCard.Group group = new OnboardingChoiceCard.Group();
    private final List<KeepADBTrustedNetwork.ProtectionLevel> levels = new ArrayList<>();
    private final List<OnboardingChoiceCard> cards = new ArrayList<>();

    KeepADBProtectionLevelSection(Activity activity, Runnable onChanged) {
        this.activity = activity;
        this.onChanged = onChanged;
        this.panel = activity.findViewById(R.id.protection_level_panel);
        this.forceNote = activity.findViewById(R.id.protection_level_force_note);
        ViewGroup options = activity.findViewById(R.id.protection_level_options);

        // The previous settings come first, as in the assistant; they show only while stored.
        add(options, KeepADBTrustedNetwork.ProtectionLevel.LEGACY_ALL_WIFI,
                activity.getString(R.string.force_level_legacy_all),
                R.string.onboarding_protection_legacy_all_body,
                OnboardingChoiceCard.Badge.LESS_SECURE);
        add(options, KeepADBTrustedNetwork.ProtectionLevel.LEGACY_NAME_LIST,
                activity.getString(R.string.force_level_legacy_names),
                R.string.onboarding_protection_legacy_names_body,
                OnboardingChoiceCard.Badge.NOTE);
        add(options, KeepADBTrustedNetwork.ProtectionLevel.MAXIMUM_SECURITY,
                activity.getString(R.string.force_level_maximum),
                R.string.onboarding_protection_max_body, OnboardingChoiceCard.Badge.NONE);
        add(options, KeepADBTrustedNetwork.ProtectionLevel.BALANCED,
                activity.getString(R.string.force_level_balanced),
                R.string.onboarding_protection_balanced_body, OnboardingChoiceCard.Badge.NONE);
        group.setListener(this::onCardSelected);
    }

    private void add(ViewGroup options, KeepADBTrustedNetwork.ProtectionLevel level, String title,
                     int bodyRes, OnboardingChoiceCard.Badge badge) {
        levels.add(level);
        OnboardingChoiceCard card = OnboardingChoiceCard.add(activity, options, title,
                activity.getString(bodyRes), badge);
        cards.add(card);
        group.register(card);
    }

    View getPanel() {
        return panel;
    }

    /** The card of the stored level: where a focus request puts the screen reader. */
    View selectedCard() {
        int index = group.selectedIndex();
        return index < 0 ? null : cards.get(index).view;
    }

    /** Re-renders from the stored policy; writes nothing. */
    void refresh() {
        KeepADBTrustedNetwork.ProtectionLevel stored =
                KeepADBTrustedNetwork.getProtectionLevel(activity);
        for (int i = 0; i < levels.size(); i++) {
            KeepADBTrustedNetwork.ProtectionLevel level = levels.get(i);
            boolean previous = level == KeepADBTrustedNetwork.ProtectionLevel.LEGACY_ALL_WIFI
                    || level == KeepADBTrustedNetwork.ProtectionLevel.LEGACY_NAME_LIST;
            cards.get(i).view.setVisibility(!previous || level == stored
                    ? View.VISIBLE : View.GONE);
        }
        group.selectQuietly(levels.indexOf(stored));
        // The force mode overrides trust while it runs; the level applies once it ends.
        forceNote.setVisibility(KeepADBForceMode.isActive(activity) ? View.VISIBLE : View.GONE);
    }

    private void onCardSelected(int index) {
        KeepADBTrustedNetwork.ProtectionLevel wanted = levels.get(index);
        if (wanted != KeepADBTrustedNetwork.ProtectionLevel.MAXIMUM_SECURITY
                && wanted != KeepADBTrustedNetwork.ProtectionLevel.BALANCED) {
            // A previous setting is shown, not chosen; put the selection back on the stored one.
            refresh();
            return;
        }
        if (KeepADBTrustedNetwork.getProtectionLevel(activity) != wanted) {
            KeepADBOnboarding.commitProtection(activity, wanted);
            KeepADBDiagnostics.event(activity, "user_action", "settings_protection", "select",
                    wanted.name().toLowerCase(Locale.ROOT));
        }
        onChanged.run();
    }
}
