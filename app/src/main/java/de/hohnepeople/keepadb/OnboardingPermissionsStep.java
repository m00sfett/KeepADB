package de.hohnepeople.keepadb;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
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
final class OnboardingPermissionsStep extends OnboardingStep {
    static final String ITEM_SYSTEM = "system";
    static final String ITEM_NOTIFICATIONS = "notifications";
    static final String ITEM_LOCATION = "location";
    static final String ITEM_BACKGROUND_LOCATION = "background_location";
    static final String ITEM_BATTERY = "battery";

    private Activity host;
    private ViewGroup content;
    private String focusItem;
    private boolean built;
    /** #1051: the three ways to the system permission, with their own state. */
    private final OnboardingSystemPermissionHelp systemHelp =
            new OnboardingSystemPermissionHelp(this::render);
    private final Runnable selfPairObserver = () -> {
        if (built && host instanceof OnboardingActivity
                && ((OnboardingActivity) host).isShowing(this)) {
            render();
        }
    };
    /** The status word each row showed last time, to announce the ones that changed. */
    private final Map<String, String> lastStatus = new HashMap<>();

    OnboardingPermissionsStep() {
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
        systemHelp.attach(activity);
        KeepADBSelfPairService.observe(selfPairObserver);
        render();
    }

    @Override
    void onDestroy() {
        KeepADBSelfPairService.stopObserving(selfPairObserver);
        built = false;
    }

    @Override
    void saveState(Bundle out) {
        systemHelp.save(out);
    }

    @Override
    void restoreState(Bundle in) {
        systemHelp.restore(in);
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
        // Back from the notification settings with the permission: go on where it stopped.
        systemHelp.continuePendingStart();
        if (built) render();
        // #962: a grant that landed after its run had ended is settled when the user is back.
        KeepADBSelfPairCleanup.reconcileAtStart(context);
        // Like MainActivity.onResume: the service follows the preference, and a Keep-Alive
        // service started from the background is promoted again (#628).
        KeepADBService.sync(context);
    }

    @Override
    void onPermissionResult(int requestCode) {
        if (requestCode == OnboardingPermissions.REQUEST_NOTIFICATIONS) {
            systemHelp.continuePendingStart();
        }
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
                system ? OnboardingActionSteps.Tone.DONE : OnboardingActionSteps.Tone.MISSING);
        if (system) {
            systemHelp.addResult(row);
        } else {
            systemHelp.addTo(row);
        }

        // Notifications: a runtime permission from API 33 on, nothing to ask before.
        if (OnboardingPermissions.hasNotificationPermission()) {
            boolean granted = !OnboardingPermissions.isNotificationMissing(host);
            row = row(rows, status, ITEM_NOTIFICATIONS,
                    R.string.notification_permission_panel_title,
                    granted ? OnboardingActionSteps.Tone.DONE : OnboardingActionSteps.Tone.MISSING);
            if (!granted) {
                row.addView(OnboardingActionSteps.body(host, host.getString(R.string.notification_permission_panel_body)));
                row.addView(OnboardingActionSteps.button(host, host.getString(
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
                location ? OnboardingActionSteps.Tone.DONE : OnboardingActionSteps.Tone.MISSING);
        if (!location) {
            row.addView(OnboardingActionSteps.body(host, host.getString(R.string.onboarding_perm_location_body)));
            row.addView(OnboardingActionSteps.button(host, host.getString(
                    OnboardingPermissions.locationAction(host)
                            == OnboardingPermissions.Action.OPEN_SETTINGS
                            ? R.string.location_permission_settings_button
                            : R.string.location_permission_panel_grant_button),
                    true, v -> OnboardingPermissions.requestLocation(host,
                            NetworkListRenderer.REQUEST_LOCATION)));
        }
        if (force) row.addView(OnboardingActionSteps.muted(host, host.getString(R.string.onboarding_perm_force_note)));

        // Background detection: "Allow all the time".
        boolean background = KeepADBBackgroundLocation.isGranted(host);
        row = row(rows, status, ITEM_BACKGROUND_LOCATION,
                R.string.onboarding_perm_background_title,
                background ? OnboardingActionSteps.Tone.DONE : keepAlive ? OnboardingActionSteps.Tone.RECOMMENDED : OnboardingActionSteps.Tone.OPTIONAL);
        if (!background) {
            if (!keepAlive) {
                row.addView(OnboardingActionSteps.muted(host, host.getString(R.string.onboarding_perm_keep_alive_only)));
            } else {
                row.addView(OnboardingActionSteps.body(host, host.getString(R.string.onboarding_perm_background_body)));
                if (location) {
                    row.addView(OnboardingActionSteps.button(host, host.getString(
                            R.string.location_permission_settings_button), false,
                            v -> KeepADBBackgroundLocation.openSettings(host)));
                } else {
                    row.addView(OnboardingActionSteps.muted(host, host.getString(
                            R.string.onboarding_perm_background_blocked)));
                }
            }
        }
        if (force) row.addView(OnboardingActionSteps.muted(host, host.getString(R.string.onboarding_perm_force_note)));

        // Battery optimization.
        boolean exempt = KeepADBBatteryOptimization.isExempt(host);
        row = row(rows, status, ITEM_BATTERY, R.string.onboarding_perm_battery_title,
                exempt ? OnboardingActionSteps.Tone.DONE : keepAlive ? OnboardingActionSteps.Tone.RECOMMENDED : OnboardingActionSteps.Tone.OPTIONAL);
        if (!exempt) {
            if (!keepAlive) {
                row.addView(OnboardingActionSteps.muted(host, host.getString(R.string.onboarding_perm_keep_alive_only)));
            } else {
                row.addView(OnboardingActionSteps.body(host, host.getString(R.string.battery_optimization_body)));
                row.addView(OnboardingActionSteps.button(host, host.getString(R.string.battery_optimization_button),
                        false, v -> KeepADBBatteryOptimization.openSettings(host)));
            }
        }
        // #891: the VPN app that carries the webhook needs the same exemption.
        if (KeepADBPreferences.isRegisterWebhookEnabled(host)) {
            row.addView(OnboardingActionSteps.muted(host, host.getString(R.string.onboarding_perm_battery_vpn_note)));
        }

        announceChanges(status);
        lastStatus.clear();
        lastStatus.putAll(status);
        focus(rows);
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
                             int titleRes, OnboardingActionSteps.Tone tone) {
        String statusWord = host.getString(tone.labelRes);
        LinearLayout panel = OnboardingActionSteps.panel(host);
        panel.addView(OnboardingActionSteps.titleLine(host, host.getString(titleRes), statusWord, tone));
        content.addView(panel);
        rows.put(item, panel);
        status.put(item, statusWord);
        return panel;
    }

}
