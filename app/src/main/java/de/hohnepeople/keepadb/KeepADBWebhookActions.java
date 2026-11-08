package de.hohnepeople.keepadb;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/**
 * #911: the PendingIntents a widget attaches to its webhook controls. Every one is an explicit
 * intent to a component of this app and {@link PendingIntent#FLAG_IMMUTABLE}, so no other app can
 * redirect or fill in one; the broadcasts go to the non-exported {@link KeepADBReceiver}.
 *
 * <p>Which control to offer comes from the shared state ({@link KeepADBWidgetState.Webhook}):
 * "Report now" when {@code reportNow} is {@code READY}, the setup when it is {@code NEEDS_SETUP}.
 * A widget never needs to guess: the receiver re-checks everything at the moment of the tap.
 */
final class KeepADBWebhookActions {
    // Distinct from the webhook alert's own requests (see KeepADBWebhookAlert), so a widget's
    // PendingIntent never replaces the notification's.
    private static final int REQUEST_CODE_REPORT_NOW = 61;
    private static final int REQUEST_CODE_SYNC_ENABLE = 62;
    private static final int REQUEST_CODE_SYNC_DISABLE = 63;
    private static final int REQUEST_CODE_SETUP = 64;

    private KeepADBWebhookActions() {}

    /** One deliberate report of the current endpoint; works with automatic sync off as well. */
    static PendingIntent reportNow(Context context) {
        return broadcast(context, KeepADBReceiver.ACTION_WEBHOOK_REPORT_NOW, REQUEST_CODE_REPORT_NOW);
    }

    /** Sets automatic synchronization to the given state (never a flip). */
    static PendingIntent setAutomaticSync(Context context, boolean enable) {
        return enable
                ? broadcast(context, KeepADBReceiver.ACTION_WEBHOOK_SYNC_ENABLE, REQUEST_CODE_SYNC_ENABLE)
                : broadcast(context, KeepADBReceiver.ACTION_WEBHOOK_SYNC_DISABLE, REQUEST_CODE_SYNC_DISABLE);
    }

    /** Opens the settings at the webhook form: the way out of "no valid destination". */
    static PendingIntent setup(Context context) {
        Context app = context.getApplicationContext();
        Intent intent = new Intent(app, SettingsActivity.class)
                .putExtra(SettingsActivity.EXTRA_FOCUS_WEBHOOK, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(app, REQUEST_CODE_SETUP, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent broadcast(Context context, String action, int requestCode) {
        Context app = context.getApplicationContext();
        Intent intent = new Intent(app, KeepADBReceiver.class).setAction(action);
        return PendingIntent.getBroadcast(app, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
