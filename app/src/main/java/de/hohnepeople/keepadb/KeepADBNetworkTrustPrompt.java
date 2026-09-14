package de.hohnepeople.keepadb;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;

/**
 * Asks the user -- once per access point, not once per heartbeat -- whether a newly seen,
 * untrusted Wi-Fi access point should become trusted (#446, #450).
 *
 * <p>Before this, automatic Keep-Alive re-enable on an unlisted network failed silently: the only
 * trace was an {@code outcome=blocked detail=untrusted_network} diagnostics event nobody sees
 * until adb has already stopped working. This class turns that block into a notification with an
 * explicit "allow"/"block" choice, and records the access point in {@link
 * KeepADBBlockedNetworkHistory} so it stays reviewable in Settings afterwards.
 *
 * <p><strong>Never fails open.</strong> Nothing here trusts a network on its own; only the user's
 * explicit tap on the allow action reaches {@link KeepADBTrustedNetwork#addBssid}, which is the
 * same entry point the manual "add current network" button and the mesh convenience already use.
 * Showing, updating, throttling or cancelling the prompt has no effect on the allowlist.
 *
 * <h2>Throttling</h2>
 * Both block sites in {@link KeepADBService} are reached repeatedly -- the content observer on
 * every {@code adb_wifi_enabled} change and {@code recheckAndEnable()} on every 60s heartbeat --
 * so the raised prompt is remembered per BSSID in {@code keepadb_prefs} and not raised again for
 * the same access point. It is persisted rather than kept in a static so a service restart (boot,
 * process death) does not re-alert for an access point the user already answered.
 *
 * <p>#450: A bounded history of recently prompted BSSIDs is retained (up to {@link
 * #MAX_PROMPTED_BSSIDS}) so roaming/flapping between two untrusted access points does not cause
 * repeated notification alerts on every switch.
 *
 * <p>The suppression deliberately expires after {@link #PROMPT_REPEAT_INTERVAL_MS}: a permanent
 * "don't ask again" that no user action can clear would be a silently switched-off alarm, and the
 * block it hides is exactly the failure the issue is about. Declining therefore only suppresses
 * the next prompts, it never creates a persistent blocklist entry -- there is no such concept, and
 * none is needed: an access point that is not on the allowlist is already blocked.
 */
final class KeepADBNetworkTrustPrompt {
    static final String CHANNEL_ID = "keepadb_network_prompt";
    static final int NOTIFICATION_ID = 3;

    static final String EXTRA_BSSID = "de.hohnepeople.keepadb.extra.PROMPT_BSSID";
    static final String EXTRA_LABEL = "de.hohnepeople.keepadb.extra.PROMPT_LABEL";

    private static final String PREFS_NAME = "keepadb_prefs";
    private static final String KEY_PROMPTED_BSSID = "network_prompt_bssid";
    private static final String KEY_PROMPTED_AT = "network_prompt_at";
    static final String KEY_PROMPTED_HISTORY = "network_prompt_history";

    /** Maximum number of recently prompted BSSIDs remembered to prevent flapping (#450). */
    static final int MAX_PROMPTED_BSSIDS = 8;

    /** How long a raised prompt suppresses further prompts for the same access point. */
    static final long PROMPT_REPEAT_INTERVAL_MS = 6L * 60L * 60L * 1000L;

    private static final int REQUEST_CODE_TRUST = 10;
    private static final int REQUEST_CODE_DISMISS = 11;

    private static final class PromptEntry {
        final String bssid;
        final long promptedAt;

        PromptEntry(String bssid, long promptedAt) {
            this.bssid = bssid;
            this.promptedAt = promptedAt;
        }
    }

    private KeepADBNetworkTrustPrompt() {}

    /**
     * Called from every site that blocks automatic re-enable because the current network is not
     * trusted. Records the access point for the Settings transparency list and raises the prompt
     * unless one was already raised for this access point recently.
     *
     * @return true if a notification was actually posted by this call.
     */
    static boolean onBlockedByUntrustedNetwork(Context context) {
        if (context == null) return false;
        Context appContext = context.getApplicationContext();
        KeepADBNetworkIdentity identity = KeepADBNetworkIdentity.current(appContext);
        // An unreadable identity is not actionable: there is no BSSID the user could allow, so a
        // prompt would offer a choice that cannot be carried out. Settings already explains this
        // state via settings_trusted_network_status_identity_unavailable.
        if (!identity.isKnown()) {
            KeepADBDiagnostics.event(appContext, "network_trust_prompt", "trusted_network",
                    "skipped", "identity_unavailable");
            return false;
        }
        long now = System.currentTimeMillis();
        KeepADBBlockedNetworkHistory.record(appContext, identity, now);

        if (!shouldPrompt(appContext, identity.bssid, now)) {
            KeepADBDiagnostics.event(appContext, "network_trust_prompt", "trusted_network",
                    "throttled", "already_prompted");
            return false;
        }
        String label = labelFor(identity);
        boolean shown = show(appContext, identity.bssid, label);
        if (shown) {
            markPrompted(appContext, identity.bssid, now);
            KeepADBDiagnostics.event(appContext, "network_trust_prompt", "trusted_network",
                    "shown", "bssid=" + identity.bssid);
        }
        return shown;
    }

    /** SSID if readable, otherwise the BSSID -- the identifier shown to the user. */
    static String labelFor(KeepADBNetworkIdentity identity) {
        String ssid = identity.displaySsid();
        return (ssid == null || ssid.isEmpty()) ? identity.bssid : ssid;
    }

    /**
     * Whether a prompt for {@code bssid} may be raised now: not while the remembered prompt is
     * for the same access point and still within {@link #PROMPT_REPEAT_INTERVAL_MS}. A remembered
     * timestamp in the future (the user moved the clock backwards) is treated as expired rather
     * than as suppressing forever.
     */
    static boolean shouldPrompt(Context context, String bssid, long nowMillis) {
        if (bssid == null || bssid.trim().isEmpty()) return false;
        String cleanBssid = bssid.trim();
        SharedPreferences preferences = prefs(context);
        List<PromptEntry> history = readPromptHistory(preferences);
        for (PromptEntry entry : history) {
            if (entry.bssid.equalsIgnoreCase(cleanBssid)) {
                long elapsed = nowMillis - entry.promptedAt;
                return elapsed < 0 || elapsed >= PROMPT_REPEAT_INTERVAL_MS;
            }
        }
        return true;
    }

    private static void markPrompted(Context context, String bssid, long nowMillis) {
        if (bssid == null || bssid.trim().isEmpty()) return;
        String cleanBssid = bssid.trim();
        SharedPreferences preferences = prefs(context);
        List<PromptEntry> history = readPromptHistory(preferences);
        history.removeIf(entry -> entry.bssid.equalsIgnoreCase(cleanBssid));
        history.add(new PromptEntry(cleanBssid, nowMillis));
        while (history.size() > MAX_PROMPTED_BSSIDS) {
            history.remove(0);
        }
        writePromptHistory(preferences, history);
    }

    private static List<PromptEntry> readPromptHistory(SharedPreferences preferences) {
        List<PromptEntry> result = new ArrayList<>();
        String raw = preferences.getString(KEY_PROMPTED_HISTORY, null);
        if (raw != null && !raw.trim().isEmpty()) {
            for (String token : raw.split(",")) {
                int pipeIndex = token.indexOf('|');
                if (pipeIndex > 0 && pipeIndex < token.length() - 1) {
                    String bssid = token.substring(0, pipeIndex).trim();
                    try {
                        long at = Long.parseLong(token.substring(pipeIndex + 1).trim());
                        if (!bssid.isEmpty()) {
                            result.add(new PromptEntry(bssid, at));
                        }
                    } catch (NumberFormatException ignored) {
                        // ignore malformed timestamp
                    }
                }
            }
            if (!result.isEmpty()) return result;
        }
        // Backward-compatibility fallback to single-key pair
        String legacyBssid = preferences.getString(KEY_PROMPTED_BSSID, null);
        if (legacyBssid != null && !legacyBssid.trim().isEmpty()) {
            long legacyAt = preferences.getLong(KEY_PROMPTED_AT, 0L);
            result.add(new PromptEntry(legacyBssid.trim(), legacyAt));
        }
        return result;
    }

    private static void writePromptHistory(SharedPreferences preferences, List<PromptEntry> entries) {
        SharedPreferences.Editor editor = preferences.edit();
        if (entries == null || entries.isEmpty()) {
            editor.remove(KEY_PROMPTED_HISTORY)
                    .remove(KEY_PROMPTED_BSSID)
                    .remove(KEY_PROMPTED_AT)
                    .apply();
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (PromptEntry entry : entries) {
            if (sb.length() > 0) sb.append(',');
            sb.append(entry.bssid).append('|').append(entry.promptedAt);
        }
        PromptEntry last = entries.get(entries.size() - 1);
        editor.putString(KEY_PROMPTED_HISTORY, sb.toString())
                .putString(KEY_PROMPTED_BSSID, last.bssid)
                .putLong(KEY_PROMPTED_AT, last.promptedAt)
                .apply();
    }

    /** Forgets the remembered prompt history so the next block on any access point prompts again. */
    static void clearPromptState(Context context) {
        prefs(context).edit()
                .remove(KEY_PROMPTED_HISTORY)
                .remove(KEY_PROMPTED_BSSID)
                .remove(KEY_PROMPTED_AT)
                .apply();
    }

    static void cancel(Context context) {
        NotificationManager manager = context.getApplicationContext()
                .getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(NOTIFICATION_ID);
    }

    private static boolean show(Context context, String bssid, String label) {
        Context localized = KeepADBLocaleHelper.wrapContext(context);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || !hasNotificationPermission(context)) {
            KeepADBDiagnostics.event(context, "network_trust_prompt", "trusted_network", "skipped",
                    "permission_missing");
            return false;
        }
        ensureChannel(localized, manager);
        String text = localized.getString(R.string.network_prompt_text, label, bssid);
        Intent contentIntent = new Intent(context, SettingsActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        Notification notification = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_keepadb)
                .setContentTitle(localized.getString(R.string.network_prompt_title))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(PendingIntent.getActivity(context, 0, contentIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .setCategory(Notification.CATEGORY_STATUS)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .addAction(action(context, localized.getString(R.string.network_prompt_allow),
                        KeepADBReceiver.ACTION_TRUST_NETWORK, REQUEST_CODE_TRUST, bssid, label))
                .addAction(action(context, localized.getString(R.string.network_prompt_block),
                        KeepADBReceiver.ACTION_DISMISS_NETWORK_PROMPT, REQUEST_CODE_DISMISS,
                        bssid, label))
                .build();
        manager.notify(NOTIFICATION_ID, notification);
        return true;
    }

    private static Notification.Action action(Context context, String title, String action,
            int requestCode, String bssid, String label) {
        // The receiver is not exported and the PendingIntent is IMMUTABLE, so the BSSID these
        // extras carry cannot be substituted by another app on its way back to us.
        Intent intent = new Intent(context, KeepADBReceiver.class)
                .setAction(action)
                .putExtra(EXTRA_BSSID, bssid)
                .putExtra(EXTRA_LABEL, label);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder(null, title, pendingIntent).build();
    }

    /**
     * Own channel rather than {@link KeepADBNotification#CHANNEL_ID}: that channel is
     * {@code IMPORTANCE_LOW} because it carries the permanent, deliberately silent foreground
     * service notification, and a question the user has to answer before adb works again must be
     * allowed to surface. {@code IMPORTANCE_DEFAULT} also keeps the two independently mutable by
     * the user -- silencing the always-present endpoint notification must not silence this prompt.
     */
    private static void ensureChannel(Context context, NotificationManager manager) {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.network_prompt_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(context.getString(R.string.network_prompt_channel_desc));
        manager.createNotificationChannel(channel);
    }

    private static boolean hasNotificationPermission(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
