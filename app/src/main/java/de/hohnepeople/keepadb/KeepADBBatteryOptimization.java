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
}
