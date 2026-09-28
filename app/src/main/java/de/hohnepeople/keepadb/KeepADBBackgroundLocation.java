package de.hohnepeople.keepadb;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;

/**
 * #616: optional {@code ACCESS_BACKGROUND_LOCATION} ("Allow all the time") for trusted networks.
 *
 * <p>KeepADB never reads or stores the device position. On Android the Wi-Fi identity
 * (SSID/BSSID) is gated behind location permissions; a Keep-Alive service started from the
 * background only sees an unmasked identity with the background grant (#626, #630). Without it the
 * identity stays unreadable there, and {@link KeepADBTrustedNetwork} keeps treating an unreadable
 * identity as untrusted (fail-closed) -- this class never changes that decision.
 *
 * <p>Since Android 11 the background grant cannot be requested through a runtime dialog that
 * offers "Allow all the time"; the user has to pick it on the app's own permission page. This
 * class therefore only reads the grant state and opens the app details settings -- it never
 * grants anything and never changes the trusted-network mode or allowlist.
 */
final class KeepADBBackgroundLocation {
    private KeepADBBackgroundLocation() {
    }

    static boolean isGranted(Context context) {
        return context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * The background grant only matters while trusted-network (allowlist) mode is on; in
     * all-Wi-Fi mode no identity is ever read for a trust decision.
     */
    static boolean isSetupNeeded(Context context) {
        return KeepADBTrustedNetwork.isAllowlistMode(context) && !isGranted(context);
    }

    /** Intent for this app's system details page, where "Location > Allow all the time" lives. */
    static Intent appDetailsIntent(Context context) {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.getPackageName(), null));
    }

    static void openSettings(Activity activity) {
        try {
            activity.startActivity(appDetailsIntent(activity));
        } catch (ActivityNotFoundException | SecurityException ignored) {
            // Some customized devices expose no app details surface; nothing else to offer.
        }
    }
}
