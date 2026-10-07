package de.hohnepeople.keepadb;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

/**
 * What the setup assistant's permissions step reads and asks (#767, UX concept 1.3 step 3). The
 * state is always read from the platform, never stored; the one thing remembered is that a
 * permission was asked for before, which is what tells "denied once" from "denied for good".
 *
 * <h2>The three cases of a runtime permission</h2>
 * <ul>
 *   <li><b>Not asked yet / denied once</b>: the button asks the system ({@link Action#REQUEST}).</li>
 *   <li><b>Denied for good</b> (asked before, and Android no longer shows a rationale, so it will
 *       not show the dialog again): the button opens the page where the user can still grant it
 *       ({@link Action#OPEN_SETTINGS}). Asking again would do nothing and leave a dead button.</li>
 *   <li><b>Granted</b>: no button.</li>
 * </ul>
 *
 * <h2>API 30 against 33 and later</h2>
 * The notification permission is a runtime permission only from Android 13 (API 33). Below it,
 * notifications are allowed by default: there is nothing to ask, and {@link
 * #isNotificationMissing} is false. The location permission and the "Allow all the time" grant
 * behave the same from API 30 on.
 */
final class OnboardingPermissions {
    /** What the button of an open runtime permission does. */
    enum Action { REQUEST, OPEN_SETTINGS }

    /** Same key and same preferences file (the activity's own) as the networks list, #762. */
    static final String PREF_LOCATION_REQUESTED = "networks_location_requested";
    private static final String PREF_NOTIFICATION_REQUESTED = "notification_permission_requested";

    /**
     * #797: one app-wide "asked before" marker for the location permission. The per-activity
     * markers above and in {@link KeepADBNetworkCard} stay as they are (existing installs hold
     * them); this file only adds what they cannot know, that another screen already asked. It is
     * not part of {@code keepadb_prefs}, so it never counts as a stored setting of an existing
     * installation ({@link KeepADBOnboarding#isExistingInstall}).
     */
    private static final String SHARED_ASKS_FILE = "keepadb_permission_asks";
    private static final String KEY_LOCATION_ASKED = "location_asked";

    static final int REQUEST_NOTIFICATIONS = 7671;

    private OnboardingPermissions() {}

    /** The pure decision: asked before and no rationale left means the system will not ask again. */
    static Action decide(boolean askedBefore, boolean rationaleAvailable) {
        return askedBefore && !rationaleAvailable ? Action.OPEN_SETTINGS : Action.REQUEST;
    }

    // ---- System permission ------------------------------------------------------------------------

    static boolean isSystemPermissionGranted(Context context) {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    // ---- Notifications ----------------------------------------------------------------------------

    /** Whether this Android version has a notification permission to ask for (API 33 and later). */
    static boolean hasNotificationPermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
    }

    static boolean isNotificationMissing(Context context) {
        return hasNotificationPermission()
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED;
    }

    static Action notificationAction(Activity activity) {
        return decide(prefs(activity).getBoolean(PREF_NOTIFICATION_REQUESTED, false),
                hasNotificationPermission() && activity.shouldShowRequestPermissionRationale(
                        Manifest.permission.POST_NOTIFICATIONS));
    }

    /** Asks for the notification permission, or opens the notification settings if asking is over. */
    static void requestNotifications(Activity activity) {
        if (!hasNotificationPermission()) return;
        if (notificationAction(activity) == Action.OPEN_SETTINGS) {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName());
            open(activity, intent);
            return;
        }
        prefs(activity).edit().putBoolean(PREF_NOTIFICATION_REQUESTED, true).apply();
        activity.requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS},
                REQUEST_NOTIFICATIONS);
    }

    // ---- Location ---------------------------------------------------------------------------------

    /** Wi-Fi name and access point are only readable with the precise location permission. */
    static boolean isLocationGranted(Context context) {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    static Action locationAction(Activity activity) {
        return locationAction(activity, prefs(activity).getBoolean(PREF_LOCATION_REQUESTED, false));
    }

    /**
     * Same decision for a screen that keeps its own marker ({@code askedInOwnFile}); a request made
     * from any other screen counts as well, so the first tap here is never an idle tap on a
     * permission the system will not ask for again (#797).
     */
    static Action locationAction(Activity activity, boolean askedInOwnFile) {
        boolean asked = askedInOwnFile || sharedAsks(activity).getBoolean(KEY_LOCATION_ASKED, false);
        return decide(asked, activity.shouldShowRequestPermissionRationale(
                Manifest.permission.ACCESS_FINE_LOCATION));
    }

    /** Records an asking of the location permission for every screen (#797). */
    static void markLocationAsked(Activity activity) {
        sharedAsks(activity).edit().putBoolean(KEY_LOCATION_ASKED, true).apply();
    }

    /**
     * Asks for the location permission with {@code requestCode}, or opens this app's system page if
     * asking is over. Shared by the permissions step and the networks list, so both behave alike.
     */
    static void requestLocation(Activity activity, int requestCode) {
        if (locationAction(activity) == Action.OPEN_SETTINGS) {
            openAppDetails(activity);
            return;
        }
        prefs(activity).edit().putBoolean(PREF_LOCATION_REQUESTED, true).apply();
        markLocationAsked(activity);
        activity.requestPermissions(new String[] {Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION}, requestCode);
    }

    static void openAppDetails(Activity activity) {
        open(activity, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", activity.getPackageName(), null)));
    }

    // ---- Summary ----------------------------------------------------------------------------------

    /**
     * How many of the permissions that block KeepADB are missing: the system permission, the
     * notification permission (API 33 and later) and the location permission. The background grant
     * and the battery exemption are recommendations and never counted.
     */
    static int missingCount(Context context) {
        int missing = 0;
        if (!isSystemPermissionGranted(context)) missing++;
        if (isNotificationMissing(context)) missing++;
        if (!isLocationGranted(context)) missing++;
        return missing;
    }

    private static SharedPreferences sharedAsks(Activity activity) {
        return activity.getSharedPreferences(SHARED_ASKS_FILE, Context.MODE_PRIVATE);
    }

    private static SharedPreferences prefs(Activity activity) {
        return activity.getPreferences(Context.MODE_PRIVATE);
    }

    private static void open(Activity activity, Intent intent) {
        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException ignored) {
            // Some customized devices expose no such surface; nothing else to offer.
        }
    }
}
