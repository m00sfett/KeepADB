package de.hohnepeople.keepadb;

import android.content.Context;
import android.content.Intent;

/**
 * Which warning cards the home screen shows (#764, UX concept #758 section 5.2). The home screen
 * shows the status and only the real problems; every setup card it used to carry now lives in the
 * setup assistant, and each warning here leads to the matching step of it.
 *
 * <p>The cards, by severity (the force card, W2, belongs to {@link KeepADBForceMode} and is drawn
 * by {@link MainActivity} itself):
 * <ul>
 *   <li><b>W1</b> the system permission is missing; it stands above everything and silences W4
 *       and W5, because without it the rest is secondary.</li>
 *   <li><b>W3</b> a setting that reads "less secure" is left as it is (the force mode has its own
 *       card and is not counted here).</li>
 *   <li><b>W4</b> Keep-Alive is on, but the network cannot be read, so nothing switches on by
 *       itself.</li>
 *   <li><b>W5</b> Keep-Alive is on and a permission it needs for reliable work is missing. W4 and
 *       W5 share one slot (W4 wins), so there are never more than three cards at once.</li>
 * </ul>
 *
 * <p>Pure reads. Display acknowledgments and mutes live in KeepADBWarningState.
 */
final class KeepADBHomeWarnings {
    /** W1: the system permission is missing. */
    final boolean systemPermissionMissing;
    /** W3: how many settings read "less secure" (not counting the force mode); 0 = no card. */
    final int lessSecureCount;
    /** W3: the step of the heaviest marked setting; null when {@link #lessSecureCount} is 0. */
    final KeepADBOnboarding.Step lessSecureStep;
    /** W4: Keep-Alive is paused because the network cannot be read. */
    final boolean keepAlivePaused;
    /** W5: how many permissions Keep-Alive misses; 0 = no card. */
    final int keepAliveLimitedCount;
    /** W5: the item of the permissions step to open first; null when there is no W5. */
    final String keepAliveLimitedItem;

    private KeepADBHomeWarnings(boolean systemPermissionMissing, int lessSecureCount,
            KeepADBOnboarding.Step lessSecureStep, boolean keepAlivePaused,
            int keepAliveLimitedCount, String keepAliveLimitedItem) {
        this.systemPermissionMissing = systemPermissionMissing;
        this.lessSecureCount = lessSecureCount;
        this.lessSecureStep = lessSecureStep;
        this.keepAlivePaused = keepAlivePaused;
        this.keepAliveLimitedCount = keepAliveLimitedCount;
        this.keepAliveLimitedItem = keepAliveLimitedItem;
    }

    boolean showLessSecure() {
        return lessSecureCount > 0;
    }

    boolean showLimited() {
        return keepAliveLimitedCount > 0;
    }

    /** Raw causes, independent of display suppression. Never writes preferences. */
    static java.util.Set<KeepADBWarningState.Reason> reasons(Context context) {
        java.util.Set<KeepADBWarningState.Reason> result = java.util.EnumSet.noneOf(
                KeepADBWarningState.Reason.class);
        // Called under the display-state monitor: do not query the endpoint coordinator here.
        // These are exactly getState's permission/read-failure cases, independent of its endpoint.
        Context app = context.getApplicationContext();
        if (!KeepADB.hasPermission(app) || KeepADB.isEnabledOrNull(app, "get_state") == null) {
            result.add(KeepADBWarningState.Reason.SYSTEM_PERMISSION);
        }
        for (KeepADBOnboarding.LessSecure reason : KeepADBOnboarding.lessSecure(context)) {
            result.add(KeepADBWarningState.Reason.valueOf(reason.name()));
        }
        if (KeepADBPreferences.isKeepAliveEnabled(context)) {
            if (KeepADBService.isWifiConnected(context)
                    && KeepADBTrustedNetwork.getBlockReason(context)
                    == KeepADBTrustedNetwork.BlockReason.IDENTITY_UNAVAILABLE) {
                result.add(KeepADBWarningState.Reason.NETWORK_IDENTITY_UNAVAILABLE);
            }
            if (OnboardingPermissions.hasNotificationPermission()
                    && OnboardingPermissions.isNotificationMissing(context)) {
                result.add(KeepADBWarningState.Reason.NOTIFICATIONS_MISSING);
            }
            if (!KeepADBForceMode.isActive(context)
                    && KeepADBBackgroundLocation.isSetupNeeded(context)) {
                result.add(KeepADBWarningState.Reason.BACKGROUND_LOCATION_MISSING);
            }
            if (!KeepADBBatteryOptimization.isExempt(context)) {
                result.add(KeepADBWarningState.Reason.BATTERY_EXEMPTION_MISSING);
            }
        }
        return result;
    }

    /** The warnings that hold right now. */
    static KeepADBHomeWarnings evaluate(Context context) {
        // The same read that disables the main switch, so card and switch can never disagree.
        boolean system = KeepADB.getState(context) == KeepADB.State.PERMISSION_MISSING;

        int lessSecure = 0;
        KeepADBOnboarding.Step lessSecureStep = null;
        for (KeepADBOnboarding.LessSecure value : KeepADBOnboarding.lessSecure(context)) {
            if (value == KeepADBOnboarding.LessSecure.FORCE_MODE) continue;
            lessSecure++;
            if (lessSecureStep == null) lessSecureStep = stepOf(value);
        }

        boolean keepAlive = KeepADBPreferences.isKeepAliveEnabled(context);
        boolean paused = false;
        int limited = 0;
        String limitedItem = null;
        if (keepAlive && !system) {
            paused = KeepADBService.isWifiConnected(context)
                    && KeepADBTrustedNetwork.getBlockReason(context)
                            == KeepADBTrustedNetwork.BlockReason.IDENTITY_UNAVAILABLE;
            if (!paused) {
                if (OnboardingPermissions.hasNotificationPermission()
                        && OnboardingPermissions.isNotificationMissing(context)) {
                    limited++;
                    limitedItem = OnboardingActionSteps.Permissions.ITEM_NOTIFICATIONS;
                }
                // Without force: the background grant only matters where trust is decided.
                if (!KeepADBForceMode.isActive(context)
                        && KeepADBBackgroundLocation.isSetupNeeded(context)) {
                    limited++;
                    if (limitedItem == null) {
                        limitedItem = OnboardingActionSteps.Permissions.ITEM_BACKGROUND_LOCATION;
                    }
                }
                if (!KeepADBBatteryOptimization.isExempt(context)) {
                    limited++;
                    if (limitedItem == null) {
                        limitedItem = OnboardingActionSteps.Permissions.ITEM_BATTERY;
                    }
                }
            }
        }
        return new KeepADBHomeWarnings(system, lessSecure, lessSecureStep, paused, limited,
                limitedItem);
    }

    /** The assistant step that changes a marked setting. */
    private static KeepADBOnboarding.Step stepOf(KeepADBOnboarding.LessSecure value) {
        switch (value) {
            case NOTIFICATION_DETAILS:
                return KeepADBOnboarding.Step.DETAILS;
            case WEBHOOK_CLEARTEXT:
                return KeepADBOnboarding.Step.WEBHOOK;
            default:
                return KeepADBOnboarding.Step.PROTECTION;
        }
    }

    // ---- Where each card leads ---------------------------------------------------------------

    /** W1: the permissions step, at the row with the command for the computer. */
    static Intent systemIntent(Context context) {
        return OnboardingActivity.stepIntent(context, KeepADBOnboarding.Step.PERMISSIONS)
                .putExtra(OnboardingActivity.EXTRA_FOCUS_ITEM,
                        OnboardingActionSteps.Permissions.ITEM_SYSTEM);
    }

    /** W3: the step of the marked setting. */
    Intent lessSecureIntent(Context context) {
        return OnboardingActivity.stepIntent(context, lessSecureStep);
    }

    /** W5: the permissions step, at the first missing row. */
    Intent limitedIntent(Context context) {
        return OnboardingActivity.stepIntent(context, KeepADBOnboarding.Step.PERMISSIONS)
                .putExtra(OnboardingActivity.EXTRA_FOCUS_ITEM, keepAliveLimitedItem);
    }
}
