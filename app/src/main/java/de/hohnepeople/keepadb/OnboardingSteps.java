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
            group.register(OnboardingChoiceCard.add(host, content,
                    host.getString(R.string.onboarding_value_off),
                    host.getString(R.string.onboarding_keep_alive_off_body),
                    OnboardingChoiceCard.Badge.NONE));
            group.register(OnboardingChoiceCard.add(host, content,
                    host.getString(R.string.onboarding_value_on),
                    host.getString(R.string.onboarding_keep_alive_on_body),
                    OnboardingChoiceCard.Badge.NONE));
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
     * "Customize" shows the one single value behind the presets, the comfort switch.
     */
    static final class Protection extends OnboardingStep {
        private final List<KeepADBTrustedNetwork.ProtectionLevel> levels = new ArrayList<>();
        private OnboardingChoiceCard.Group group;
        private Switch comfortSwitch;
        private boolean updatingSwitch;
        private String restored;

        Protection() {
            super(KeepADBOnboarding.Step.PROTECTION, R.string.onboarding_protection_title,
                    R.string.onboarding_protection_question);
        }

        @Override
        void build(Activity host, ViewGroup content) {
            levels.clear();
            group = new OnboardingChoiceCard.Group();
            KeepADBTrustedNetwork.ProtectionLevel stored =
                    KeepADBTrustedNetwork.getProtectionLevel(host);

            if (!KeepADBPreferences.isKeepAliveEnabled(host)) {
                content.addView(note(host, R.string.onboarding_protection_keep_alive_off));
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
            addLevel(host, content, KeepADBTrustedNetwork.ProtectionLevel.MAXIMUM_SECURITY,
                    host.getString(R.string.force_level_maximum),
                    R.string.onboarding_protection_max_body,
                    OnboardingChoiceCard.Badge.RECOMMENDED);
            addLevel(host, content, KeepADBTrustedNetwork.ProtectionLevel.BALANCED,
                    host.getString(R.string.force_level_balanced),
                    R.string.onboarding_protection_balanced_body,
                    stored == KeepADBTrustedNetwork.ProtectionLevel.BALANCED
                            ? OnboardingChoiceCard.Badge.NOTE : OnboardingChoiceCard.Badge.NONE);

            buildCustomize(host, content);

            KeepADBTrustedNetwork.ProtectionLevel start = stored;
            String pendingName = restored;
            restored = null;
            if (pendingName != null) {
                try {
                    KeepADBTrustedNetwork.ProtectionLevel pending =
                            KeepADBTrustedNetwork.ProtectionLevel.valueOf(pendingName);
                    if (levels.contains(pending)) start = pending;
                } catch (IllegalArgumentException ignored) {
                    // An unknown saved value falls back to the stored level.
                }
            }
            group.selectQuietly(levels.indexOf(start));
            syncSwitch();
            group.setListener(index -> syncSwitch());
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

        /** The comfort switch is the same fact as the "Balanced" card, never a third state. */
        private void syncSwitch() {
            updatingSwitch = true;
            comfortSwitch.setChecked(selectedLevel()
                    == KeepADBTrustedNetwork.ProtectionLevel.BALANCED);
            updatingSwitch = false;
        }

        private KeepADBTrustedNetwork.ProtectionLevel selectedLevel() {
            return levels.get(group.selectedIndex());
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
            KeepADBOnboarding.commitProtection(context, selectedLevel());
        }

        @Override
        String summary(Context context) {
            return KeepADBForceNotice.levelLabel(context);
        }

        @Override
        void saveState(Bundle out) {
            if (group != null && group.selectedIndex() >= 0) {
                out.putString(STATE_SELECTION + id.id, selectedLevel().name());
            }
        }

        @Override
        void restoreState(Bundle in) {
            restored = in.getString(STATE_SELECTION + id.id);
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
            group.register(OnboardingChoiceCard.add(host, content,
                    host.getString(R.string.onboarding_value_off),
                    host.getString(R.string.onboarding_details_off_body),
                    OnboardingChoiceCard.Badge.RECOMMENDED));
            group.register(OnboardingChoiceCard.add(host, content,
                    host.getString(R.string.onboarding_value_on),
                    host.getString(R.string.onboarding_details_on_body),
                    stored ? OnboardingChoiceCard.Badge.LESS_SECURE
                            : OnboardingChoiceCard.Badge.NONE));
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
