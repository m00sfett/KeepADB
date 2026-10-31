package de.hohnepeople.keepadb;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.Locale;

/**
 * #864: optional policy that turns Wireless Debugging off while it is on in a Wi-Fi that is not
 * trusted, is blocked, or cannot be identified -- instead of only notifying (#460). Off by
 * default; nothing changes for an installation that does not switch it on.
 *
 * <p>Rules, as decided for #864:
 * <ul>
 *   <li>It acts only while Keep-Alive is on: the Keep-Alive service is what observes network
 *       changes. It asks the same unified trust evaluation as automatic enable ({@link
 *       KeepADBTrustedNetwork#evaluateCurrent}); a running force mode (#763) allows the network
 *       there and therefore suspends this policy, too.</li>
 *   <li>A readable untrusted or blocked network switches off at once. An unreadable identity is
 *       checked again after {@link KeepADBNetworkTrustPrompt#IDENTITY_RECHECK_DELAYS_MS} (as in
 *       #872), so the few seconds of an access-point roam trigger nothing.</li>
 *   <li>A session the user switched on manually stays on while the Wi-Fi identity is the one it
 *       was switched on in. A different access point, a lost Wi-Fi or Wireless Debugging going
 *       off ends that exemption.</li>
 *   <li>The OFF write ({@link KeepADB#applyPolicyOff}) records no user intent, so Keep-Alive
 *       re-enables as usual once the network is trusted. Every condition is re-checked inside
 *       the toggle lock immediately before the write.</li>
 * </ul>
 * This is defense in depth, not access-point authentication; see docs/trusted-networks.md.
 */
final class KeepADBUntrustedShutdown {
    static final String CHANNEL_ID = "keepadb_untrusted_shutdown";
    static final int NOTIFICATION_ID = 6;
    static final String SOURCE = "untrusted_shutdown";

    private static volatile KeepADBScheduler scheduler = new KeepADBAndroidScheduler();
    /** The scheduled recheck of an unreadable identity, or null; guarded by the class lock. */
    private static Runnable pendingRecheck;

    private KeepADBUntrustedShutdown() {}

    static void setSchedulerForTesting(KeepADBScheduler testScheduler) {
        scheduler = testScheduler;
    }

    static synchronized void resetForTesting() {
        cancelRecheckLocked();
        scheduler = new KeepADBAndroidScheduler();
    }

    /**
     * Applies the policy to the current state. Called from the service's network callbacks and
     * heartbeat and when the setting is switched on; safe to call at any time, it re-derives
     * every condition itself.
     */
    static void evaluate(Context context, String trigger) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        if (!preconditionsHold(app)) {
            cancelRecheck();
            return;
        }
        KeepADBTrustedNetwork.Decision decision = KeepADBTrustedNetwork.evaluateCurrent(app);
        if (decision.allowsAutomaticEnable || isManuallyExempt(app)) {
            cancelRecheck();
            return;
        }
        if (decision == KeepADBTrustedNetwork.Decision.IDENTITY_UNAVAILABLE) {
            synchronized (KeepADBUntrustedShutdown.class) {
                if (pendingRecheck != null) return; // a confirmation is already running
                scheduleRecheckLocked(app, 0);
            }
            KeepADBDiagnostics.event(app, "untrusted_shutdown", trigger, "confirming",
                    "reason=identity_unavailable");
            return;
        }
        cancelRecheck();
        shutDown(app, decision);
    }

    /** #864: called by {@link KeepADB} after a manual toggle was applied. */
    static void onManualToggleApplied(Context context, boolean on) {
        Context app = context.getApplicationContext();
        KeepADBPreferences.setUntrustedShutdownManualBssid(app, on ? currentIdentityKey(app) : null);
        if (on) cancelRecheck();
    }

    /** Wi-Fi lost or Wireless Debugging observed off: a manual exemption ends here. */
    static void onSessionEnded(Context context) {
        Context app = context.getApplicationContext();
        KeepADBPreferences.setUntrustedShutdownManualBssid(app, null);
        cancelRecheck();
    }

    /** The conditions every decision and the final write depend on, apart from the network. */
    private static boolean preconditionsHold(Context app) {
        return KeepADBPreferences.isUntrustedShutdownEnabled(app)
                && KeepADBPreferences.isKeepAliveEnabled(app)
                && KeepADBService.isWifiConnected(app)
                && Boolean.TRUE.equals(KeepADB.isEnabledOrNull(app, SOURCE));
    }

    /** The full policy, re-derived; used as the guard of the OFF write. */
    private static boolean stillApplies(Context app) {
        return preconditionsHold(app)
                && !KeepADBTrustedNetwork.evaluateCurrent(app).allowsAutomaticEnable
                && !isManuallyExempt(app);
    }

    static boolean isManuallyExempt(Context app) {
        String recorded = KeepADBPreferences.getUntrustedShutdownManualBssid(app);
        return recorded != null && recorded.equals(currentIdentityKey(app));
    }

    /** The current BSSID in lower case, or "" when the identity cannot be read. */
    private static String currentIdentityKey(Context app) {
        KeepADBNetworkIdentity identity = KeepADBNetworkIdentity.current(app);
        return identity.isKnown() ? identity.bssid.toLowerCase(Locale.ROOT) : "";
    }

    private static void scheduleRecheckLocked(Context app, int attempt) {
        long delayMs = KeepADBNetworkTrustPrompt.IDENTITY_RECHECK_DELAYS_MS[attempt];
        Runnable recheck = new Runnable() {
            @Override
            public void run() {
                runRecheck(app, attempt, this);
            }
        };
        pendingRecheck = recheck;
        scheduler.postDelayed(recheck, delayMs);
    }

    /**
     * Only the hand-over of {@link #pendingRecheck} happens under the class lock; the state reads
     * run outside it, because {@link #onManualToggleApplied} takes this lock while {@link KeepADB}
     * holds its own.
     */
    private static void runRecheck(Context app, int attempt, Runnable self) {
        synchronized (KeepADBUntrustedShutdown.class) {
            if (pendingRecheck != self) return;
            pendingRecheck = null;
        }
        if (!preconditionsHold(app) || isManuallyExempt(app)) return;
        KeepADBTrustedNetwork.Decision decision = KeepADBTrustedNetwork.evaluateCurrent(app);
        if (decision.allowsAutomaticEnable) {
            KeepADBDiagnostics.event(app, "untrusted_shutdown", "recheck", "resolved",
                    "attempt=" + (attempt + 2));
            return;
        }
        if (decision == KeepADBTrustedNetwork.Decision.IDENTITY_UNAVAILABLE
                && attempt + 1 < KeepADBNetworkTrustPrompt.IDENTITY_RECHECK_DELAYS_MS.length) {
            synchronized (KeepADBUntrustedShutdown.class) {
                if (pendingRecheck == null) scheduleRecheckLocked(app, attempt + 1);
            }
            return;
        }
        shutDown(app, decision);
    }

    private static void cancelRecheck() {
        synchronized (KeepADBUntrustedShutdown.class) {
            cancelRecheckLocked();
        }
    }

    private static void cancelRecheckLocked() {
        if (pendingRecheck != null) {
            scheduler.removeCallbacks(pendingRecheck);
            pendingRecheck = null;
        }
    }

    private static void shutDown(Context app, KeepADBTrustedNetwork.Decision decision) {
        KeepADB.ToggleResult result = KeepADB.applyPolicyOff(app, SOURCE,
                KeepADBUntrustedShutdown::stillApplies);
        KeepADBDiagnostics.event(app, "untrusted_shutdown", "policy", result.name().toLowerCase(Locale.ROOT),
                "decision=" + decision.name().toLowerCase(Locale.ROOT));
        switch (result) {
            case APPLIED:
                notify(app, R.string.untrusted_shutdown_off_title, R.string.untrusted_shutdown_off_text);
                break;
            case PERMISSION_MISSING:
            case SECURITY_EXCEPTION:
            case WRITE_REJECTED:
                notify(app, R.string.untrusted_shutdown_failed_title,
                        R.string.untrusted_shutdown_failed_text);
                break;
            default:
                // Aborted or superseded: the situation changed, the next trigger decides again.
                break;
        }
    }

    private static void notify(Context app, int titleRes, int textRes) {
        Context localized = KeepADBLocaleHelper.wrapContext(app);
        NotificationManager manager = localized.getSystemService(NotificationManager.class);
        if (manager == null || !hasNotificationPermission(localized)) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                localized.getString(R.string.untrusted_shutdown_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(localized.getString(R.string.untrusted_shutdown_channel_desc));
        manager.createNotificationChannel(channel);
        String title = localized.getString(titleRes);
        String text = localized.getString(textRes);
        PendingIntent open = PendingIntent.getActivity(localized, NOTIFICATION_ID,
                new Intent(localized, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        manager.notify(NOTIFICATION_ID, new Notification.Builder(localized, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_keepadb)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build());
    }

    private static boolean hasNotificationPermission(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }
}
