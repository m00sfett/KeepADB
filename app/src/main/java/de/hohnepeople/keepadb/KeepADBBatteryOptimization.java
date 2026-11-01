package de.hohnepeople.keepadb;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;

final class KeepADBBatteryOptimization {
    private KeepADBBatteryOptimization() {
    }

    static boolean isExempt(Context context) {
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        try {
            return powerManager != null
                    && powerManager.isIgnoringBatteryOptimizations(context.getPackageName());
        } catch (SecurityException ignored) {
            return false;
        }
    }

    static void openSettings(Activity activity) {
        Intent settingsIntent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + activity.getPackageName()));
        try {
            activity.startActivity(settingsIntent);
        } catch (ActivityNotFoundException | SecurityException ignored) {
            Intent appDetailsIntent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + activity.getPackageName()));
            try {
                activity.startActivity(appDetailsIntent);
            } catch (ActivityNotFoundException | SecurityException ignoredAgain) {
                // Some customized devices expose neither settings surface.
            }
        }
    }

    /**
     * #891: the system list of all apps' battery optimization, where the user can exempt the VPN
     * app that carries the webhook. KeepADB names no product and queries no package; it only opens
     * the system screen, falling back to the battery saver and then the general settings.
     */
    static void openOptimizationList(Activity activity) {
        startFirst(activity, Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
                Settings.ACTION_BATTERY_SAVER_SETTINGS, Settings.ACTION_SETTINGS);
    }

    /** #891: the system VPN settings, which list the installed VPN apps. */
    static void openVpnSettings(Activity activity) {
        startFirst(activity, Settings.ACTION_VPN_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS,
                Settings.ACTION_SETTINGS);
    }

    private static void startFirst(Activity activity, String... actions) {
        for (String action : actions) {
            try {
                activity.startActivity(new Intent(action));
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
                // Try the next, more general screen.
            }
        }
    }
}
