package de.hohnepeople.keepadb;

import android.app.Activity;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The steps of the setup assistant that act instead of choosing (#767, UX concept 1.3 steps 3 and
 * 4): the permissions Android has to give, and the Wi-Fi to trust. Their actions take effect at
 * once (a permission, a trust), so "Next" has nothing left to write and these steps may be skipped.
 * That is also what keeps the one rule of the assistant true for them: "Next" without input
 * changes no setting ({@link #commit} is empty).
 */
final class OnboardingActionSteps {
    private OnboardingActionSteps() {}

    // ---- Step 3: permissions ----------------------------------------------------------------------

    /**
     * One row per permission, each with its status word and, while open, its one action: the
     * system permission (a command for the computer), notifications (API 33 and later), location
     * to read the Wi-Fi, "Allow all the time" for the background, the battery exemption. The state
     * is read from the platform on every resume, so returning from a system page shows the result
     * without a toast.
     *
     * <p>Whenever this step is in front and the assistant resumes, it calls {@link
     * KeepADBService#sync}, as the home screen does. That is what makes it the target of the
     * "network not readable" deep link (#628, #759): only a foreground start re-promotes a Keep-Alive
     * service that was started from the background.
     */
    static final class Permissions extends OnboardingStep {
        static final String ITEM_SYSTEM = "system";
        static final String ITEM_NOTIFICATIONS = "notifications";
        static final String ITEM_LOCATION = "location";
        static final String ITEM_BACKGROUND_LOCATION = "background_location";
        static final String ITEM_BATTERY = "battery";

        private Activity host;
        private ViewGroup content;
        private String focusItem;
        private boolean moreOpen;
        private boolean built;
        /** The status word each row showed last time, to announce the ones that changed. */
        private final Map<String, String> lastStatus = new HashMap<>();

        Permissions() {
            super(KeepADBOnboarding.Step.PERMISSIONS, R.string.onboarding_permissions_title,
                    R.string.onboarding_permissions_question);
        }

        @Override
        boolean hasSkip() {
            return true;
        }

        @Override
        void setFocusItem(String item) {
            focusItem = item;
        }

        @Override
        void build(Activity activity, ViewGroup container) {
            host = activity;
            content = container;
            render();
        }

        @Override
        void commit(Context context) {
            // Nothing to write: every action of this step took effect when it was taken.
        }

        @Override
        String summary(Context context) {
            int missing = OnboardingPermissions.missingCount(context);
            return missing == 0
                    ? context.getString(R.string.onboarding_permissions_summary_done)
                    : context.getString(R.string.onboarding_permissions_summary_missing, missing);
        }

        @Override
        void onResume(Context context) {
            if (built) render();
            // Like MainActivity.onResume: the service follows the preference, and a Keep-Alive
            // service started from the background is promoted again (#628).
            KeepADBService.sync(context);
        }

        @Override
        void onPermissionResult(int requestCode) {
            if (built) render();
        }

        private void render() {
            built = true;
            content.removeAllViews();
            Map<String, String> status = new HashMap<>();
            boolean keepAlive = KeepADBPreferences.isKeepAliveEnabled(host);
            boolean force = KeepADBForceMode.isActive(host);
            Map<String, View> rows = new HashMap<>();

            // System permission.
            boolean system = OnboardingPermissions.isSystemPermissionGranted(host);
            LinearLayout row = row(rows, status, ITEM_SYSTEM,
                    R.string.onboarding_perm_system_title,
                    system ? Tone.DONE : Tone.MISSING);
            if (!system) addSystemHelp(row);

            // Notifications: a runtime permission from API 33 on, nothing to ask before.
            if (OnboardingPermissions.hasNotificationPermission()) {
                boolean granted = !OnboardingPermissions.isNotificationMissing(host);
                row = row(rows, status, ITEM_NOTIFICATIONS,
                        R.string.notification_permission_panel_title,
                        granted ? Tone.DONE : Tone.MISSING);
                if (!granted) {
                    row.addView(body(host.getString(R.string.notification_permission_panel_body)));
                    row.addView(button(host.getString(
                            OnboardingPermissions.notificationAction(host)
                                    == OnboardingPermissions.Action.OPEN_SETTINGS
                                    ? R.string.notification_permission_settings_button
                                    : R.string.notification_permission_request_button),
                            true, v -> OnboardingPermissions.requestNotifications(host)));
                }
            }

            // Location, to read the Wi-Fi name and access point.
            boolean location = OnboardingPermissions.isLocationGranted(host);
            row = row(rows, status, ITEM_LOCATION, R.string.onboarding_perm_location_title,
                    location ? Tone.DONE : Tone.MISSING);
            if (!location) {
                row.addView(body(host.getString(R.string.onboarding_perm_location_body)));
                row.addView(button(host.getString(
                        OnboardingPermissions.locationAction(host)
                                == OnboardingPermissions.Action.OPEN_SETTINGS
                                ? R.string.location_permission_settings_button
                                : R.string.location_permission_panel_grant_button),
                        true, v -> OnboardingPermissions.requestLocation(host,
                                NetworkListRenderer.REQUEST_LOCATION)));
            }
            if (force) row.addView(muted(host.getString(R.string.onboarding_perm_force_note)));

            // Background detection: "Allow all the time".
            boolean background = KeepADBBackgroundLocation.isGranted(host);
            row = row(rows, status, ITEM_BACKGROUND_LOCATION,
                    R.string.onboarding_perm_background_title,
                    background ? Tone.DONE : keepAlive ? Tone.RECOMMENDED : Tone.OPTIONAL);
            if (!background) {
                if (!keepAlive) {
                    row.addView(muted(host.getString(R.string.onboarding_perm_keep_alive_only)));
                } else {
                    row.addView(body(host.getString(R.string.onboarding_perm_background_body)));
                    if (location) {
                        row.addView(button(host.getString(
                                R.string.location_permission_settings_button), false,
                                v -> KeepADBBackgroundLocation.openSettings(host)));
                    } else {
                        row.addView(muted(host.getString(
                                R.string.onboarding_perm_background_blocked)));
                    }
                }
            }
            if (force) row.addView(muted(host.getString(R.string.onboarding_perm_force_note)));

            // Battery optimization.
            boolean exempt = KeepADBBatteryOptimization.isExempt(host);
            row = row(rows, status, ITEM_BATTERY, R.string.onboarding_perm_battery_title,
                    exempt ? Tone.DONE : keepAlive ? Tone.RECOMMENDED : Tone.OPTIONAL);
            if (!exempt) {
                if (!keepAlive) {
                    row.addView(muted(host.getString(R.string.onboarding_perm_keep_alive_only)));
                } else {
                    row.addView(body(host.getString(R.string.battery_optimization_body)));
                    row.addView(button(host.getString(R.string.battery_optimization_button),
                            false, v -> KeepADBBatteryOptimization.openSettings(host)));
                }
            }

            announceChanges(status);
            lastStatus.clear();
            lastStatus.putAll(status);
            focus(rows);
        }

        /** The command for the computer, with the several-devices and problem help folded away. */
        private void addSystemHelp(LinearLayout row) {
            row.addView(body(host.getString(R.string.onboarding_perm_system_body)));
            String grant = host.getString(R.string.setup_command, host.getPackageName());
            row.addView(command(grant));
            // #791: the command is meant to be pasted at a computer; selecting it by long press
            // is not an obvious way to get it there.
            row.addView(button(host.getString(R.string.onboarding_copy_command), false,
                    v -> copyCommand(grant)));
            row.addView(button(host.getString(R.string.setup_refresh), false, v -> {
                render();
                KeepADBWidget.refreshAll(host);
                KeepADBEndpointCoordinator.refresh(host);
            }));
            TextView toggle = body(host.getString(R.string.onboarding_perm_system_more)
                    + (moreOpen ? "  ▴" : "  ▾"));
            toggle.setTextColor(host.getColor(R.color.text_yellow));
            toggle.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
            toggle.setMinHeight(dp(host, 48));
            toggle.setGravity(Gravity.CENTER_VERTICAL);
            toggle.setClickable(true);
            toggle.setFocusable(true);
            toggle.setStateDescription(host.getString(moreOpen
                    ? R.string.card_state_expanded : R.string.card_state_collapsed));
            toggle.setOnClickListener(v -> {
                moreOpen = !moreOpen;
                render();
            });
            row.addView(toggle);
            if (!moreOpen) return;
            row.addView(body(host.getString(R.string.setup_body)));
            row.addView(label(host.getString(R.string.setup_multi_device_label)));
            row.addView(command(host.getString(R.string.setup_command_multi,
                    host.getPackageName())));
            row.addView(muted(host.getString(R.string.setup_multi_device_hint)));
            row.addView(label(host.getString(R.string.setup_state_offline_title)));
            row.addView(body(host.getString(R.string.setup_state_offline_body)));
            row.addView(label(host.getString(R.string.setup_state_unauthorized_title)));
            row.addView(body(host.getString(R.string.setup_state_unauthorized_body)));
        }

        /** Tells the screen reader which rows changed their status word since the last read. */
        private void announceChanges(Map<String, String> now) {
            for (String line : changedRows(lastStatus, now, host)) {
                content.announceForAccessibility(line);
            }
        }

        /**
         * The rows whose status word differs from last time, as "Title: Status". A row that was not
         * there before (the first read) is not a change.
         */
        static List<String> changedRows(Map<String, String> before, Map<String, String> after,
                                        Context context) {
            List<String> lines = new ArrayList<>();
            for (Map.Entry<String, String> entry : after.entrySet()) {
                String old = before.get(entry.getKey());
                if (old != null && !old.equals(entry.getValue())) {
                    lines.add(rowTitle(context, entry.getKey()) + ": " + entry.getValue());
                }
            }
            return lines;
        }

        private static String rowTitle(Context context, String item) {
            switch (item) {
                case ITEM_SYSTEM:
                    return context.getString(R.string.onboarding_perm_system_title);
                case ITEM_NOTIFICATIONS:
                    return context.getString(R.string.notification_permission_panel_title);
                case ITEM_LOCATION:
                    return context.getString(R.string.onboarding_perm_location_title);
                case ITEM_BACKGROUND_LOCATION:
                    return context.getString(R.string.onboarding_perm_background_title);
                default:
                    return context.getString(R.string.onboarding_perm_battery_title);
            }
        }

        /** A deep link names an item: bring it into view and give it the focus. */
        private void focus(Map<String, View> rows) {
            View target = focusItem == null ? null : rows.get(focusItem);
            if (target == null) return;
            target.setFocusable(true);
            target.setFocusableInTouchMode(true);
            target.requestFocus();
            target.post(() -> target.requestRectangleOnScreen(
                    new android.graphics.Rect(0, 0, target.getWidth(), target.getHeight())));
        }

        private LinearLayout row(Map<String, View> rows, Map<String, String> status, String item,
                                 int titleRes, Tone tone) {
            String statusWord = host.getString(tone.labelRes);
            LinearLayout panel = panel(host);
            panel.addView(titleLine(host, host.getString(titleRes), statusWord, tone));
            content.addView(panel);
            rows.put(item, panel);
            status.put(item, statusWord);
            return panel;
        }

        private TextView body(String text) {
            return text(host, text, 14, R.color.night_text, 8);
        }

        private TextView muted(String text) {
            return text(host, text, 13, R.color.night_muted, 8);
        }

        private TextView label(String text) {
            TextView view = text(host, text, 13, R.color.night_text, 12);
            view.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            return view;
        }

        private Button button(String label, boolean primary, View.OnClickListener listener) {
            return actionButton(host, label, primary, listener);
        }

        private void copyCommand(String command) {
            try {
                android.content.ClipboardManager clipboard =
                        host.getSystemService(android.content.ClipboardManager.class);
                if (clipboard == null) throw new IllegalStateException("Clipboard unavailable");
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                        host.getString(R.string.onboarding_perm_system_title), command));
                // From Android 13 the system confirms a copy itself.
                if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.S_V2) {
                    android.widget.Toast.makeText(host, R.string.onboarding_copy_done,
                            android.widget.Toast.LENGTH_SHORT).show();
                }
            } catch (RuntimeException exception) {
                android.widget.Toast.makeText(host, R.string.feedback_copy_failed,
                        android.widget.Toast.LENGTH_LONG).show();
            }
        }

        private TextView command(String text) {
            TextView view = text(host, text, 13, R.color.text_yellow, 4);
            view.setTypeface(Typeface.MONOSPACE);
            view.setTextIsSelectable(true);
            return view;
        }
    }

    // ---- Step 4: trusted Wi-Fi --------------------------------------------------------------------

    /**
     * The Wi-Fi the device is connected to right now, and the question whether to trust it. The card
     * is the current-network card of the Networks list ({@link
     * NetworkListRenderer#renderCurrentForAssistant}) with the embedded decision of #766 ("Trust
     * this network?"); the step has no dialog and no decision logic of its own.
     *
     * <p>The "trusted Wi-Fi name" of the balanced protection level (decision F5 of #758) is derived
     * from the trusted access points, so there is no second list to fill here.
     */
    static final class Network extends OnboardingStep {
        private Activity host;
        private ViewGroup content;
        private LinearLayout card;
        private NetworkListRenderer renderer;
        private boolean built;

        Network() {
            super(KeepADBOnboarding.Step.NETWORK, R.string.onboarding_network_title,
                    R.string.onboarding_network_question);
        }

        @Override
        boolean hasSkip() {
            return true;
        }

        @Override
        void build(Activity activity, ViewGroup container) {
            host = activity;
            content = container;
            content.removeAllViews();
            if (KeepADBNetworkDisplay.hidden(host)) {
                content.addView(text(host, host.getString(R.string.onboarding_network_privacy_note),
                        13, R.color.night_muted, 0));
            }
            if (KeepADBForceMode.isActive(host)) {
                // Trust and blocks do not apply while force mode is on; the answer still counts.
                content.addView(text(host, host.getString(R.string.networks_force_note), 13,
                        R.color.text_yellow, 0));
            }
            card = new LinearLayout(host);
            card.setOrientation(LinearLayout.VERTICAL);
            // The card is replaced when the answer is given; the screen reader reads the new one.
            card.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            content.addView(card, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            renderer = new NetworkListRenderer(host, card, this::refresh);
            built = true;
            refresh();
        }

        private void refresh() {
            if (renderer != null) renderer.renderCurrentForAssistant();
        }

        @Override
        void commit(Context context) {
            // Nothing to write: trusting or blocking took effect when it was answered.
        }

        @Override
        String summary(Context context) {
            if (KeepADBNetworkDisplay.hidden(context)) {
                return context.getString(R.string.networks_count_hidden);
            }
            KeepADBNetworkIdentity identity = KeepADBNetworkIdentity.current(context);
            KeepADBNetworkList.Snapshot snapshot = KeepADBNetworkList.build(context, identity,
                    identity.isKnown() || KeepADBService.isWifiConnected(context));
            return context.getString(R.string.networks_count, snapshot.trusted, snapshot.blocked);
        }

        @Override
        void onResume(Context context) {
            if (built) refresh();
        }

        @Override
        void onPermissionResult(int requestCode) {
            if (built) refresh();
        }
    }

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

    private static LinearLayout panel(Activity host) {
        LinearLayout panel = new LinearLayout(host);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.bg_panel);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(host, 8);
        panel.setLayoutParams(params);
        return panel;
    }

    /**
     * Title and status word: side by side, or the word below the title on a narrow display or a
     * large font, where two things in a row would cut one of them. Read as one line:
     * "Title, Status".
     */
    private static View titleLine(Activity host, String title, String statusWord, Tone tone) {
        TextView titleView = text(host, title, 16, R.color.night_text, 0);
        titleView.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        titleView.setAccessibilityHeading(true);
        titleView.setContentDescription(title + ", " + statusWord);

        TextView badge = new TextView(host);
        badge.setText(statusWord);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        badge.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        badge.setPadding(dp(host, 6), dp(host, 2), dp(host, 6), dp(host, 2));
        badge.setBackgroundResource(tone.background);
        badge.setTextColor(host.getColor(tone.color));
        // The word is already part of the title's spoken line.
        badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        Configuration config = host.getResources().getConfiguration();
        boolean stacked = config.fontScale >= 1.3f || config.screenWidthDp < 360;
        LinearLayout line = new LinearLayout(host);
        if (stacked) {
            line.setOrientation(LinearLayout.VERTICAL);
            line.addView(titleView);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(host, 4);
            line.addView(badge, params);
        } else {
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.addView(titleView, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMarginStart(dp(host, 8));
            line.addView(badge, params);
        }
        return line;
    }

    private static TextView text(Activity host, String value, int sp, int colorRes, int topDp) {
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

    private static Button actionButton(Activity host, String label, boolean primary,
                                       View.OnClickListener listener) {
        Button button = new Button(host);
        button.setBackgroundResource(primary ? R.drawable.bg_btn_primary : R.drawable.bg_btn_secondary);
        button.setMinHeight(dp(host, 48));
        button.setPadding(dp(host, 16), dp(host, 8), dp(host, 16), dp(host, 8));
        button.setTextColor(host.getColor(primary ? R.color.title_yellow : R.color.text_yellow));
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        button.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        button.setAllCaps(false);
        button.setText(label);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(host, 12);
        button.setLayoutParams(params);
        return button;
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
