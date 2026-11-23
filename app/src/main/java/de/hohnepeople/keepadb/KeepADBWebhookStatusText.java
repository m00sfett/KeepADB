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

    static final String URL_CLIP_LABEL = "Webhook URL";
    static final String ENDPOINT_CLIP_LABEL = "Wifi-ADB endpoint";

    /**
     * #1036: the stored URL as it is copied, scheme to end, character for character; null when
     * there is none.
     */
    static String copyableUrl(String storedUrl) {
        return storedUrl == null || storedUrl.trim().isEmpty() ? null : storedUrl.trim();
    }

    /**
     * #1036: the last reported endpoint as it is copied, {@code host:port} only. It is stored as
     * {@link KeepADBEndpoint#formatEndpoint} wrote it, so an IPv6 literal is already bracketed.
     * Null when nothing is reported (the placeholders "none yet" and "deregistered" get no copy).
     */
    static String copyableEndpoint(String storedEndpoint) {
        return storedEndpoint == null || storedEndpoint.trim().isEmpty() ? null
                : storedEndpoint.trim();
    }

    /**
     * #1036: copies the real value, not the masked one shown on screen: a tap is an explicit
     * action. Not flagged sensitive (maintainer decision in #1036). The diagnostics event names
     * only what was copied, never the value.
     */
    static boolean copy(Context context, String label, String value, String what) {
        if (value == null) return false;
        KeepADBDiagnostics.event(context, "user_action", "app", "copy", what);
        return KeepADBClipboard.copy(context, label, value, false, R.string.webhook_copy_done);
    }

    /** When the endpoint was last reported: a date and time, "failed" or "never". */
    static String lastReported(Context context, int dateStyle, int timeStyle) {
        String reportStatus = WebhookReportPrefs.getWebhookLastReportStatus(context);
        if (WebhookReportPrefs.WEBHOOK_STATUS_FAILED.equals(reportStatus)) {
            return context.getString(R.string.webhook_status_failed);
        }
        long lastReportedAt = WebhookReportPrefs.getWebhookLastReportedAt(context);
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
        if (!WebhookUrlCodec.isValid(KeepADBPreferences.getRegisterWebhookUrl(context))) {
            return context.getString(R.string.webhook_status_unconfigured);
        }
        if (!KeepADBPreferences.isRegisterWebhookEnabled(context)) {
            return context.getString(R.string.webhook_status_disabled);
        }
        return context.getString(R.string.settings_webhook_header_status_on,
                lastReported(context, DateFormat.SHORT, DateFormat.SHORT));
    }
}
