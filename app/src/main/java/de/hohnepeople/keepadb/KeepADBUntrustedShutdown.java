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
 *   <li>A session the user switched on manually in KeepADB (app, tile, widget, notification,
 *       USB handover action) stays on while the phone is on the access point
 *       (readable BSSID) it was switched on at. Any other readable access point -- trusted ones
 *       included --, a lost Wi-Fi or Wireless Debugging going off ends that exemption for good.
 *       An unreadable identity never proves the same access point: a manual enable on it grants
 *       no exemption, and while the identity is unreadable an existing one does not apply. A
 *       restart of the Keep-Alive service ends an older exemption ({@link #onObservationResumed}).
 *       Switching Wireless Debugging on in Android's own settings is not a KeepADB action and
 *       grants no exemption (decision on the Codex review).</li>
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
        endExemptionOnOtherAccessPoint(app);
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
        // Only a readable access point can carry an exemption (see the class javadoc).
        String bssid = on ? currentBssidOrNull(app) : null;
        if (bssid == null) {
            KeepADBPreferences.clearUntrustedShutdownManualBssid(app);
        } else {
            KeepADBPreferences.setUntrustedShutdownManualBssid(app, bssid, scheduler.elapsedRealtimeMs());
        }
        if (!on) return;
        if (bssid != null) {
            cancelRecheck();
            return;
        }
        // Codex review: no exemption could be established, so a manual enable on an unreadable
        // identity starts (or keeps) the usual confirmation instead of waiting for the heartbeat.
        // Only scheduled here -- this runs inside KeepADB's lock; the recheck decides later.
        if (KeepADBPreferences.isUntrustedShutdownEnabled(app)
                && KeepADBPreferences.isKeepAliveEnabled(app)) {
            synchronized (KeepADBUntrustedShutdown.class) {
                if (pendingRecheck == null) scheduleRecheckLocked(app, 0);
            }
        }
    }

    /** The Keep-Alive service stops observing: pending confirmations end with it. */
    static void onObservationStopped() {
        cancelRecheck();
    }

    /** Wi-Fi lost or Wireless Debugging observed off: a manual exemption ends here. */
    static void onSessionEnded(Context context) {
        Context app = context.getApplicationContext();
        KeepADBPreferences.clearUntrustedShutdownManualBssid(app);
        cancelRecheck();
    }

    /**
     * A tap may itself start the service, so an exemption recorded this recently belongs to the
     * session that is starting now.
     */
    static final long OBSERVATION_RESUME_GRACE_MS = 10_000L;

    /**
     * Codex review: called when the Keep-Alive service (re)starts. While it did not run, a lost
     * Wi-Fi or Wireless Debugging going off went unobserved, so an exemption recorded before this
     * start can no longer prove that its session is still the same one; it is cleared. A record
     * from before a reboot (elapsed realtime ahead of now) is cleared as well.
     */
    static void onObservationResumed(Context context) {
        Context app = context.getApplicationContext();
        if (KeepADBPreferences.getUntrustedShutdownManualBssid(app) == null) return;
        long at = KeepADBPreferences.getUntrustedShutdownManualAt(app);
        long now = scheduler.elapsedRealtimeMs();
        if (at < 0 || at > now || now - at > OBSERVATION_RESUME_GRACE_MS) {
            KeepADBPreferences.clearUntrustedShutdownManualBssid(app);
        }
    }

    /** The conditions every decision and the final write depend on, apart from the network. */
    private static boolean preconditionsHold(Context app) {
        return KeepADBPreferences.isUntrustedShutdownEnabled(app)
                && KeepADBPreferences.isKeepAliveEnabled(app)
                && KeepADBService.isWifiConnected(app)
                && Boolean.TRUE.equals(KeepADB.isEnabledOrNull(app, SOURCE));
    }

    /**
     * The full policy, re-derived; used as the guard of the OFF write. An identity that turned
     * unreadable since a readable untrusted decision aborts the write ({@code
     * unreadableConfirmed} false): it gets its own confirmation first, see {@link #shutDown}.
     */
    private static boolean stillApplies(Context app, boolean unreadableConfirmed) {
        if (!preconditionsHold(app) || isManuallyExempt(app)) return false;
        KeepADBTrustedNetwork.Decision decision = KeepADBTrustedNetwork.evaluateCurrent(app);
        if (decision.allowsAutomaticEnable) return false;
        return unreadableConfirmed || decision != KeepADBTrustedNetwork.Decision.IDENTITY_UNAVAILABLE;
    }

    static boolean isManuallyExempt(Context app) {
        String recorded = KeepADBPreferences.getUntrustedShutdownManualBssid(app);
        String current = currentBssidOrNull(app);
        return recorded != null && !recorded.isEmpty() && recorded.equals(current);
    }

    /**
     * A readable access point other than the recorded one ends the exemption for good, whatever
     * its trust: coming back to the first access point later must not revive it. An unreadable
     * identity proves nothing either way and leaves the record alone.
     */
    private static void endExemptionOnOtherAccessPoint(Context app) {
        String recorded = KeepADBPreferences.getUntrustedShutdownManualBssid(app);
        if (recorded == null) return;
        String current = currentBssidOrNull(app);
        if (recorded.isEmpty() || (current != null && !recorded.equals(current))) {
            KeepADBPreferences.clearUntrustedShutdownManualBssid(app);
        }
    }

    /** The current BSSID in lower case, or null when the identity cannot be read. */
    private static String currentBssidOrNull(Context app) {
        KeepADBNetworkIdentity identity = KeepADBNetworkIdentity.current(app);
        return identity.isKnown() ? identity.bssid.toLowerCase(Locale.ROOT) : null;
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
        endExemptionOnOtherAccessPoint(app);
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
        boolean unreadableConfirmed = decision == KeepADBTrustedNetwork.Decision.IDENTITY_UNAVAILABLE;
        KeepADB.ToggleResult result = KeepADB.applyPolicyOff(app, SOURCE,
                guarded -> stillApplies(guarded, unreadableConfirmed));
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
            case GUARD_ABORTED:
                // The identity may have turned unreadable since the readable decision: that is a
                // possible roam, so it gets the same confirmation as any unreadable identity.
                if (!unreadableConfirmed && preconditionsHold(app) && !isManuallyExempt(app)
                        && KeepADBTrustedNetwork.evaluateCurrent(app)
                        == KeepADBTrustedNetwork.Decision.IDENTITY_UNAVAILABLE) {
                    synchronized (KeepADBUntrustedShutdown.class) {
                        if (pendingRecheck == null) scheduleRecheckLocked(app, 0);
                    }
                }
                break;
            default:
                // Superseded: the situation changed, the next trigger decides again.
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
