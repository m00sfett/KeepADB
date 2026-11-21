package de.hohnepeople.keepadb;

import android.content.Context;

import java.text.DateFormat;
import java.util.Date;

/**
 * The words for the webhook's state, shared by the home status panel and the Settings section
 * header (#1034), so both say the same thing.
 */
final class KeepADBWebhookStatusText {
    private KeepADBWebhookStatusText() {
    }

    /** When the endpoint was last reported: a date and time, "failed" or "never". */
    static String lastReported(Context context, int dateStyle, int timeStyle) {
        String reportStatus = KeepADBPreferences.getWebhookLastReportStatus(context);
        if (KeepADBPreferences.WEBHOOK_STATUS_FAILED.equals(reportStatus)) {
            return context.getString(R.string.webhook_status_failed);
        }
        long lastReportedAt = KeepADBPreferences.getWebhookLastReportedAt(context);
        if (lastReportedAt <= 0) {
            return context.getString(R.string.webhook_status_never);
        }
        return DateFormat.getDateTimeInstance(dateStyle, timeStyle,
                context.getResources().getConfiguration().getLocales().get(0))
                .format(new Date(lastReportedAt));
    }

    /**
     * #1034: the one line under the Settings section title: not set up, off, or on with the
     * time of the last report.
     */
    static String headerStatus(Context context) {
        if (!KeepADBPreferences.isValidWebhookUrl(KeepADBPreferences.getRegisterWebhookUrl(context))) {
            return context.getString(R.string.webhook_status_unconfigured);
        }
        if (!KeepADBPreferences.isRegisterWebhookEnabled(context)) {
            return context.getString(R.string.webhook_status_disabled);
        }
        return context.getString(R.string.settings_webhook_header_status_on,
                lastReported(context, DateFormat.SHORT, DateFormat.SHORT));
    }
}
