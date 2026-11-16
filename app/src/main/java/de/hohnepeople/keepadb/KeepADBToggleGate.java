package de.hohnepeople.keepadb;

import android.app.KeyguardManager;
import android.content.Context;
import android.widget.Toast;

/**
 * #586, #954: the parts of a one-tap Wireless Debugging toggle that the Quick Settings tile and
 * the launcher shortcut share, so neither surface can drift from the other: when an enable has to
 * wait for an unlock, what an unlock callback may still do, and how a failed toggle is reported.
 */
final class KeepADBToggleGate {
    private KeepADBToggleGate() {}

    /**
     * #586: true only when a secure lock is configured and its credential has not been supplied
     * yet, which is exactly the case the unlock gate exists for (same criterion as {@code
     * KeepADBReceiver#handleTrustNetworkAction}, #578). {@code KeyguardManager#isKeyguardLocked}
     * is deliberately not used: it is also true for a swipe-only lock screen, and would force an
     * unlock prompt while a trust agent keeps the device unlocked.
     */
    static boolean isDeviceLockedForEnable(Context context) {
        KeyguardManager keyguardManager = context.getSystemService(KeyguardManager.class);
        return keyguardManager != null && keyguardManager.isDeviceLocked();
    }

    /**
     * #586: called after a successful unlock. The target state is derived again instead of
     * reusing the {@code want} from the tap: Keep-Alive, another surface or a second tap may have
     * switched wireless debugging on while the unlock prompt was showing, and the continuation
     * must never turn into a disable. True only while the state still asks for an enable; the
     * skip is recorded under {@code surface}.
     */
    static boolean enableStillWanted(Context context, String surface) {
        KeepADB.State state = KeepADB.getState(context);
        if (KeepADB.desiredOnForClick(state)) {
            return true;
        }
        KeepADBDiagnostics.event(context, "user_action", surface, "skipped",
                "after_unlock state=" + state);
        return false;
    }

    /**
     * #318: distinguishes a missing permission from a Settings.Global write that was rejected
     * despite the permission being granted, instead of blaming setup for both. {@code context}
     * should already carry the app language (the tile and the activities wrap theirs).
     * The brand prefix is passed in (not hardcoded) so debug builds show "(DBG) KeepADB" (#690).
     * #795: the cause comes from the toggle result instead of a second permission read.
     * #817: every permission cause (also a SecurityException) points at the grant.
     */
    static void showToggleErrorToast(Context context, KeepADB.ToggleResult result) {
        Toast.makeText(context, result.isPermissionFailure()
                        ? context.getString(R.string.tile_permission_error,
                                context.getString(R.string.app_name))
                        : context.getString(R.string.toggle_failed_toast),
                Toast.LENGTH_LONG).show();
    }
}
