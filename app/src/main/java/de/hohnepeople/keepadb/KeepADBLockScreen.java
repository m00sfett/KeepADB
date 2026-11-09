package de.hohnepeople.keepadb;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.service.notification.StatusBarNotification;

/**
 * #920: keeps KeepADB notifications off the lock screen while the user asks for it.
 *
 * <p>Android shows a notification's {@code publicVersion} on the lock screen only while the system
 * hides sensitive content there; when it shows all content, the private card with port, address,
 * Wi-Fi name and the trust buttons is shown. {@link Notification#VISIBILITY_SECRET} is the one
 * visibility that hides a notification on the lock screen whatever that system setting says, and
 * it needs no lock/unlock tracking, so there is no window in which stale details could show.
 * Unlocked, the shade shows the notification as before.
 *
 * <p>Every KeepADB post goes through {@link #apply}. A notification channel the user set to
 * "don't show on the lock screen" stays hidden either way; a channel can only restrict further.
 */
final class KeepADBLockScreen {
    private KeepADBLockScreen() {}

    /** Marks {@code notification} secret while the option is on; returns it for chaining. */
    static Notification apply(Context context, Notification notification) {
        if (notification != null && KeepADBPreferences.isLockScreenPrivacyEnabled(context)) {
            notification.visibility = Notification.VISIBILITY_SECRET;
        }
        return notification;
    }

    /**
     * Re-posts the KeepADB notifications that are visible now with the visibility the option asks
     * for, so a change takes effect at once and not only at each notification's next posting. The
     * copies alert only once: re-posting must not ring or vibrate again.
     */
    static void applyToPosted(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        StatusBarNotification[] posted;
        try {
            posted = manager.getActiveNotifications();
        } catch (RuntimeException e) {
            return;
        }
        boolean secret = KeepADBPreferences.isLockScreenPrivacyEnabled(context);
        for (StatusBarNotification sbn : posted) {
            Notification notification = sbn.getNotification();
            if (notification == null) continue;
            notification.visibility = secret
                    ? Notification.VISIBILITY_SECRET : Notification.VISIBILITY_PRIVATE;
            notification.flags |= Notification.FLAG_ONLY_ALERT_ONCE;
            manager.notify(sbn.getTag(), sbn.getId(), notification);
        }
    }
}
