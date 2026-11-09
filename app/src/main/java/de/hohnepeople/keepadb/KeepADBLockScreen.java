package de.hohnepeople.keepadb;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;

/**
 * #920: keeps KeepADB notifications off the lock screen while the user asks for it.
 *
 * <p>Android shows a notification's {@code publicVersion} on the lock screen only while the system
 * hides sensitive content there; when it shows all content, the private card with port, address,
 * Wi-Fi name and the trust buttons is shown. {@link Notification#VISIBILITY_SECRET} is the one
 * visibility that hides a notification on the lock screen whatever that system setting says, and
 * it needs no lock/unlock tracking, so there is no window in which stale details could show.
 * Unlocked, the shade shows the notification as before. Android applies it only with a secure
 * screen lock (PIN, pattern or password).
 *
 * <p>Every KeepADB post goes through {@link #post} or {@link #startForeground}: the option is read
 * and the notification handed to the system under one lock, and {@link #setEnabled} writes the
 * option under the same lock. A post that read the old value has therefore reached the system
 * before the option changes, and the re-render that follows a change (see {@code
 * KeepADBPrivacySwitches}) replaces it. A notification channel the user set to "don't show on the
 * lock screen" stays hidden either way; a channel can only restrict further.
 */
final class KeepADBLockScreen {
    private static final Object LOCK = new Object();

    /** Test-only: runs inside the lock, after the option was read and before the post. */
    static volatile Runnable beforePostForTesting;

    private KeepADBLockScreen() {}

    /** Marks {@code notification} secret while the option is on; returns it for chaining. */
    static Notification apply(Context context, Notification notification) {
        if (notification != null && KeepADBPreferences.isLockScreenPrivacyEnabled(context)) {
            notification.visibility = Notification.VISIBILITY_SECRET;
        }
        return notification;
    }

    /** Posts {@code notification} with the visibility the option asks for. */
    static void post(Context context, NotificationManager manager, int id,
            Notification notification) {
        synchronized (LOCK) {
            apply(context, notification);
            Runnable hook = beforePostForTesting;
            if (hook != null) hook.run();
            manager.notify(id, notification);
        }
    }

    /** {@link Service#startForeground(int, Notification, int)} under the same rule as {@link #post}. */
    static void startForeground(Service service, int id, Notification notification,
            int serviceType) {
        synchronized (LOCK) {
            apply(service, notification);
            service.startForeground(id, notification, serviceType);
        }
    }

    /** Stores the option; never while a post that read the old value is still on its way. */
    static void setEnabled(Context context, boolean enabled) {
        synchronized (LOCK) {
            KeepADBPreferences.setLockScreenPrivacyEnabled(context, enabled);
        }
    }
}
