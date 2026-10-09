package de.hohnepeople.keepadb;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** The steps of the setup assistant that choose between stored settings (#761, UX concept 1.3). */
final class OnboardingSteps {
    private OnboardingSteps() {}

    private static final String STATE_SELECTION = "selection_";
    private static final String FORCE_TOKEN = "FORCE";

    // ---- Step 1: Keep-Alive -----------------------------------------------------------------------

    /**
     * "Should Wireless Debugging switch itself back on?" Off first, then on; the stored value is
     * preselected, a new installation therefore starts on "Off" (decision F2 of #758).
     */
    static final class KeepAlive extends OnboardingStep {
        private static final int OFF = 0;
        private static final int ON = 1;
        private OnboardingChoiceCard.Group group;
        private TextView notificationHint;
        private int restored = -1;

        KeepAlive() {
            super(KeepADBOnboarding.Step.KEEP_ALIVE, R.string.settings_section_keep_alive,
                    R.string.onboarding_keep_alive_question);
        }

        @Override
        void build(Activity host, ViewGroup content) {
            group = new OnboardingChoiceCard.Group();
            ViewGroup comparison = OnboardingChoiceCard.comparison(host, content);
            group.register(OnboardingChoiceCard.add(host, comparison,
                    host.getString(R.string.onboarding_value_off),
                    host.getString(R.string.onboarding_keep_alive_off_body),
                    OnboardingChoiceCard.Badge.NONE));
            group.register(OnboardingChoiceCard.add(host, comparison,
                    host.getString(R.string.onboarding_value_on),
                    host.getString(R.string.onboarding_keep_alive_on_body),
                    OnboardingChoiceCard.Badge.NONE));
            OnboardingChoiceCard.finishComparison(comparison);
            boolean stored = KeepADBPreferences.isKeepAliveEnabled(host);

            // Keep-Alive runs as a foreground service and needs its permanent notification (API 33
            // and later); the permission is asked in the permissions step, which this hint names.
            // Alone, without the sequence, the step has no "next" to point at.
            notificationHint = new TextView(host);
            notificationHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            notificationHint.setTextColor(host.getColor(R.color.text_yellow));
            notificationHint.setText(host.getString(R.string.onboarding_keep_alive_notification_hint,
                    OnboardingActivity.stepNumber(KeepADBOnboarding.Step.PERMISSIONS)));
            notificationHint.setVisibility(View.GONE);
            ViewGroup.MarginLayoutParams hintParams = new ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hintParams.topMargin = (int) (12 * host.getResources().getDisplayMetrics().density);
            content.addView(notificationHint, hintParams);
            group.setListener(index -> updateHint(host));

            group.selectQuietly(restored >= 0 ? restored : (stored ? ON : OFF));
            restored = -1;
            updateHint(host);
        }

        /** Only while "On" is chosen and the notification permission is still missing. */
        private void updateHint(Context context) {
            boolean show = !standalone && group != null && group.selectedIndex() == ON
                    && OnboardingPermissions.isNotificationMissing(context);
            notificationHint.setVisibility(show ? View.VISIBLE : View.GONE);
        }

        @Override
        void commit(Context context) {
            if (group == null) return;
            KeepADBOnboarding.commitKeepAlive(context, group.selectedIndex() == ON);
        }

        @Override
        String summary(Context context) {
            return context.getString(KeepADBPreferences.isKeepAliveEnabled(context)
                    ? R.string.onboarding_value_on : R.string.onboarding_value_off);
        }

        @Override
        void saveState(Bundle out) {
            if (group != null) out.putInt(STATE_SELECTION + id.id, group.selectedIndex());
        }

        @Override
        void restoreState(Bundle in) {
            restored = in.getInt(STATE_SELECTION + id.id, -1);
        }
    }

    // ---- Step 2: protection level -----------------------------------------------------------------

    /**
     * Where Keep-Alive may switch on. Two presets can be chosen; a stored previous setting ("in all
     * Wi-Fi networks", the old name list) is shown first, preselected and marked, and stays chosen
     * unless the user picks a preset: this step is the way back from those settings (#769, #761).
     * "Customize" shows the one single value behind the presets, the comfort switch, and the force
     * row of the Settings.
     *
     * <p>The third card, "Maximum convenience (force)" (#768), is no stored level: a tap on it does
     * not select it but opens the confirmation dialog of the force mode (#763), which alone starts
     * it. The card is selected only once the force mode runs; cancelling leaves the earlier card
     * chosen. Moving from the card to a level, then leaving the step, ends the force mode.
     */
    static final class Protection extends OnboardingStep {
        private final List<KeepADBTrustedNetwork.ProtectionLevel> levels = new ArrayList<>();
        private OnboardingChoiceCard.Group group;
        private Switch comfortSwitch;
        private boolean updatingSwitch;
        private String restored;
        private Bundle restoredForce;
        private int forceIndex = -1;
        /** The card the user has settled on: where a cancelled force dialog returns to. */
        private int settledIndex;
        private OnboardingChoiceCard forceCard;
        private KeepADBForceSection forceSection;
        private View comfortNote;
        private TextView keepAliveNote;
        private Activity host;

        Protection() {
            super(KeepADBOnboarding.Step.PROTECTION, R.string.onboarding_protection_title,
                    R.string.onboarding_protection_question);
        }

        @Override
        void build(Activity host, ViewGroup content) {
            this.host = host;
            levels.clear();
            group = new OnboardingChoiceCard.Group();
            KeepADBTrustedNetwork.ProtectionLevel stored =
                    KeepADBTrustedNetwork.getProtectionLevel(host);

            keepAliveNote = null;
            if (!KeepADBPreferences.isKeepAliveEnabled(host)) {
                keepAliveNote = note(host, R.string.onboarding_protection_keep_alive_off);
                content.addView(keepAliveNote);
            }
            if (stored == KeepADBTrustedNetwork.ProtectionLevel.LEGACY_ALL_WIFI) {
                addLevel(host, content, stored, host.getString(R.string.force_level_legacy_all),
                        R.string.onboarding_protection_legacy_all_body,
                        OnboardingChoiceCard.Badge.LESS_SECURE);
            } else if (stored == KeepADBTrustedNetwork.ProtectionLevel.LEGACY_NAME_LIST) {
                addLevel(host, content, stored, host.getString(R.string.force_level_legacy_names),
                        R.string.onboarding_protection_legacy_names_body,
                        OnboardingChoiceCard.Badge.NOTE);
            }
            ViewGroup comparison = OnboardingChoiceCard.comparison(host, content);
            addLevel(host, comparison, KeepADBTrustedNetwork.ProtectionLevel.MAXIMUM_SECURITY,
                    host.getString(R.string.force_level_maximum),
                    R.string.onboarding_protection_max_body,
                    OnboardingChoiceCard.Badge.RECOMMENDED);
            addLevel(host, comparison, KeepADBTrustedNetwork.ProtectionLevel.BALANCED,
                    host.getString(R.string.force_level_balanced),
                    R.string.onboarding_protection_balanced_body,
                    stored == KeepADBTrustedNetwork.ProtectionLevel.BALANCED
                            ? OnboardingChoiceCard.Badge.NOTE : OnboardingChoiceCard.Badge.NONE);

            OnboardingChoiceCard.finishComparison(comparison);

            forceIndex = levels.size();
            forceCard = OnboardingChoiceCard.add(host, content,
                    host.getString(R.string.onboarding_protection_force_title),
                    forceBody(), forceBadge());
            group.register(forceCard);

            buildCustomize(host, content);
            forceSection = new KeepADBForceSection(host, this::onForceChanged);
            forceSection.refresh();
            if (restoredForce != null) {
                forceSection.restore(restoredForce);
                restoredForce = null;
            }

            KeepADBTrustedNetwork.ProtectionLevel start = stored;
            String pendingName = restored;
            restored = null;
            boolean forceStart = KeepADBForceMode.isActive(host) && pendingName == null;
            if (pendingName != null && !FORCE_TOKEN.equals(pendingName)) {
                try {
                    KeepADBTrustedNetwork.ProtectionLevel pending =
                            KeepADBTrustedNetwork.ProtectionLevel.valueOf(pendingName);
                    if (levels.contains(pending)) start = pending;
                } catch (IllegalArgumentException ignored) {
                    // An unknown saved value falls back to the stored level.
                }
            }
            boolean forcePending = FORCE_TOKEN.equals(pendingName) && KeepADBForceMode.isActive(host);
            settledIndex = forceStart || forcePending ? forceIndex : levels.indexOf(start);
            group.selectQuietly(settledIndex);
            syncSwitch();
            group.setListener(this::onCardSelected);
        }

        private void onCardSelected(int index) {
            if (index == forceIndex) {
                // Not a choice yet: only the dialog can start the mode (and set this card).
                group.selectQuietly(settledIndex);
                forceSection.getDialog().show();
            } else {
                settledIndex = index;
            }
            syncSwitch();
        }

        /** The dialog confirmed, or the force mode was ended from the row: follow the real state. */
        private void onForceChanged() {
            if (KeepADBForceMode.isActive(host)) {
                settledIndex = forceIndex;
            } else if (settledIndex == forceIndex) {
                int stored = levels.indexOf(KeepADBTrustedNetwork.getProtectionLevel(host));
                settledIndex = Math.max(stored, 0);
            }
            group.selectQuietly(settledIndex);
            forceCard.update(host, forceBadge(), forceBody());
            if (keepAliveNote != null && KeepADBPreferences.isKeepAliveEnabled(host)) {
                keepAliveNote.setVisibility(View.GONE);
            }
            forceSection.refresh();
            syncSwitch();
        }

        private String forceBody() {
            KeepADBForceMode.Status status = KeepADBForceMode.status(host);
            if (status == null) {
                String body = host.getString(R.string.onboarding_protection_force_body);
                if (!KeepADBPreferences.isKeepAliveEnabled(host)) {
                    body += " " + host.getString(R.string.onboarding_protection_force_keep_alive);
                }
                return body;
            }
            return status.isUnlimited() ? host.getString(R.string.force_status_unlimited)
                    : host.getString(R.string.force_status_until,
                            KeepADBForceMode.formatEnd(host, status));
        }

        private OnboardingChoiceCard.Badge forceBadge() {
            return KeepADBForceMode.isActive(host) ? OnboardingChoiceCard.Badge.LESS_SECURE
                    : OnboardingChoiceCard.Badge.NOT_RECOMMENDED;
        }

        @Override
        void onDestroy() {
            if (forceSection != null) forceSection.destroy();
        }

        private void addLevel(Activity host, ViewGroup content,
                              KeepADBTrustedNetwork.ProtectionLevel level, String title,
                              int bodyRes, OnboardingChoiceCard.Badge badge) {
            levels.add(level);
            group.register(OnboardingChoiceCard.add(host, content, title,
                    host.getString(bodyRes), badge));
        }

        private void buildCustomize(Activity host, ViewGroup content) {
            View customize = LayoutInflater.from(host)
                    .inflate(R.layout.onboarding_protection_customize, content, false);
            content.addView(customize);
            final TextView toggle = customize.findViewById(R.id.onboarding_customize_toggle);
            final View panel = customize.findViewById(R.id.onboarding_customize_panel);
            comfortSwitch = customize.findViewById(R.id.onboarding_comfort_switch);
            comfortNote = customize.findViewById(R.id.onboarding_force_comfort_note);
            toggle.setOnClickListener(v -> {
                boolean open = panel.getVisibility() != View.VISIBLE;
                panel.setVisibility(open ? View.VISIBLE : View.GONE);
                toggle.setText(open ? R.string.onboarding_customize_hide
                        : R.string.onboarding_customize_show);
            });
            comfortSwitch.setOnCheckedChangeListener((button, checked) -> {
                if (updatingSwitch) return;
                group.select(levels.indexOf(checked
                        ? KeepADBTrustedNetwork.ProtectionLevel.BALANCED
                        : KeepADBTrustedNetwork.ProtectionLevel.MAXIMUM_SECURITY));
            });
        }

        /**
         * The comfort switch is the same fact as the "Balanced" card, never a third state. While the
         * force card is chosen it has no effect (the mode overrides trust) and says so.
         */
        private void syncSwitch() {
            boolean force = group.selectedIndex() == forceIndex;
            KeepADBTrustedNetwork.ProtectionLevel level = force
                    ? KeepADBTrustedNetwork.getProtectionLevel(host) : selectedLevel();
            updatingSwitch = true;
            comfortSwitch.setChecked(level == KeepADBTrustedNetwork.ProtectionLevel.BALANCED);
            updatingSwitch = false;
            comfortSwitch.setEnabled(!force);
            comfortNote.setVisibility(force ? View.VISIBLE : View.GONE);
        }

        /** The chosen level card, or null while the force card is chosen. */
        private KeepADBTrustedNetwork.ProtectionLevel selectedLevel() {
            int index = group.selectedIndex();
            return index >= 0 && index < levels.size() ? levels.get(index) : null;
        }

        private static TextView note(Activity host, int textRes) {
            TextView note = new TextView(host);
            note.setText(textRes);
            note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            note.setTextColor(host.getColor(R.color.night_muted));
            return note;
        }

        @Override
        void commit(Context context) {
            if (group == null || group.selectedIndex() < 0) return;
            KeepADBTrustedNetwork.ProtectionLevel level = selectedLevel();
            // The force card is selected only while the mode runs, and the dialog wrote it already.
            if (level == null) return;
            // A level chosen after the mode was started takes the choice back: the safe direction.
            if (KeepADBForceMode.endNow(context)) KeepADBForceNotice.showEndedToast(context);
            KeepADBOnboarding.commitProtection(context, level);
        }

        @Override
        String summary(Context context) {
            if (KeepADBForceMode.isActive(context)) {
                return context.getString(R.string.onboarding_protection_force_title);
            }
            return KeepADBForceNotice.levelLabel(context);
        }

        @Override
        void saveState(Bundle out) {
            if (group != null && group.selectedIndex() >= 0) {
                KeepADBTrustedNetwork.ProtectionLevel level = selectedLevel();
                out.putString(STATE_SELECTION + id.id, level == null ? FORCE_TOKEN : level.name());
            }
            if (forceSection != null) forceSection.saveState(out);
        }

        @Override
        void restoreState(Bundle in) {
            restored = in.getString(STATE_SELECTION + id.id);
            restoredForce = new Bundle();
            restoredForce.putAll(in);
        }
    }

    // ---- Step 5: lock screen ----------------------------------------------------------------------

    /**
     * Whether notifications carry addresses and network names: the existing {@code
     * notification_details_enabled}. The presets do not set it (they describe network security
     * only), so "Off" is simply the stored default.
     */
    static final class Details extends OnboardingStep {
        private static final int OFF = 0;
        private static final int ON = 1;
        private OnboardingChoiceCard.Group group;
        private int restored = -1;

        Details() {
            super(KeepADBOnboarding.Step.DETAILS, R.string.onboarding_details_title,
                    R.string.onboarding_details_question);
        }

        @Override
        void build(Activity host, ViewGroup content) {
            boolean stored = KeepADBPreferences.isNotificationDetailsEnabled(host);
            group = new OnboardingChoiceCard.Group();
            ViewGroup comparison = OnboardingChoiceCard.comparison(host, content);
            group.register(OnboardingChoiceCard.add(host, comparison,
                    host.getString(R.string.onboarding_value_off),
                    host.getString(R.string.onboarding_details_off_body),
                    OnboardingChoiceCard.Badge.RECOMMENDED));
            group.register(OnboardingChoiceCard.add(host, comparison,
                    host.getString(R.string.onboarding_value_on),
                    host.getString(R.string.onboarding_details_on_body),
                    stored ? OnboardingChoiceCard.Badge.LESS_SECURE
                            : OnboardingChoiceCard.Badge.NONE));
            OnboardingChoiceCard.finishComparison(comparison);
            group.selectQuietly(restored >= 0 ? restored : (stored ? ON : OFF));
            restored = -1;
        }

        @Override
        void commit(Context context) {
            if (group == null) return;
            KeepADBOnboarding.commitNotificationDetails(context, group.selectedIndex() == ON);
        }

        @Override
        String summary(Context context) {
            return context.getString(KeepADBPreferences.isNotificationDetailsEnabled(context)
                    ? R.string.onboarding_details_value_on : R.string.onboarding_details_value_off);
        }

        @Override
        void saveState(Bundle out) {
            if (group != null) out.putInt(STATE_SELECTION + id.id, group.selectedIndex());
        }

        @Override
        void restoreState(Bundle in) {
            restored = in.getInt(STATE_SELECTION + id.id, -1);
        }
    }
}
