package de.hohnepeople.keepadb;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * #1050: what the webhook client recorded about its reports, split out of {@link
 * KeepADBPreferences}: the #317 report snapshot (URL, endpoint, status, times), the #893 last
 * success, the #923 dispatch marker, the #868 failure episode and the fixed #866/#960 retry series.
 * Same preferences file, keys and values as before.
 */
final class WebhookReportPrefs {
    static final String WEBHOOK_STATUS_NEVER = "never";
    static final String WEBHOOK_STATUS_SUCCESS = "success";
    static final String WEBHOOK_STATUS_DEREGISTERED = "deregistered";
    static final String WEBHOOK_STATUS_FAILED = "failed";
    private static final String KEY_WEBHOOK_LAST_REPORTED = "register_webhook_last_reported";
    private static final String KEY_WEBHOOK_LAST_ENDPOINT = "register_webhook_last_endpoint";
    private static final String KEY_WEBHOOK_LAST_URL = "register_webhook_last_url";
    private static final String KEY_WEBHOOK_LAST_STATUS = "register_webhook_last_status";
    /** #734: epoch-millis of the last SUCCESSFUL report only; never touched by failures or deregistration. */
    private static final String KEY_WEBHOOK_LAST_SUCCESS_AT = "register_webhook_last_success_at";
    /** #893 (Codex review): the URL that accepted the success at {@link #KEY_WEBHOOK_LAST_SUCCESS_AT}. */
    private static final String KEY_WEBHOOK_LAST_SUCCESS_URL = "register_webhook_last_success_url";
    /** #923: the URL of a registering POST whose outcome is not settled yet (see the accessor). */
    private static final String KEY_WEBHOOK_DISPATCHED_URL = "register_webhook_dispatched_url";
    private static final String KEY_WEBHOOK_FAILURE_SINCE = "register_webhook_failure_since";
    private static final String KEY_WEBHOOK_ALERTS_SHOWN = "register_webhook_alerts_shown";

    private WebhookReportPrefs() {}

    static long getWebhookLastReportedAt(Context context) {
        if (context == null) return 0L;
        SharedPreferences prefs = KeepADBPreferences.prefs(context);
        return prefs.getLong(KEY_WEBHOOK_LAST_REPORTED, 0L);
    }

    /**
     * #734: when the last report was actually accepted by the webhook, or 0 if none ever was.
     * {@link #getWebhookLastReportedAt} cannot answer this: a deregistration also moves it. Older
     * installations without the dedicated key fall back to that timestamp only while a reported
     * endpoint is still stored, which is exactly the "last report was a success" state.
     */
    static long getWebhookLastSuccessAt(Context context) {
        if (context == null) return 0L;
        SharedPreferences prefs = KeepADBPreferences.prefs(context);
        // #893 (Codex review): a success belongs to the URL that accepted it, so a replaced
        // destination, a late answer from the previous one or an older version's leftover does
        // not show up as a success for the current URL. Older records name their URL only in the
        // last report snapshot; a time whose URL is not known at all is not claimed either.
        String owner = prefs.getString(KEY_WEBHOOK_LAST_SUCCESS_URL,
                prefs.getString(KEY_WEBHOOK_LAST_URL, null));
        // #1047: compared with the saved URL as every reader sees it (sanitized), not the raw value.
        String saved = KeepADBPreferences.getRegisterWebhookUrl(context);
        if (owner == null || saved == null || !owner.trim().equals(saved)) {
            return 0L;
        }
        if (prefs.contains(KEY_WEBHOOK_LAST_SUCCESS_AT)) {
            return prefs.getLong(KEY_WEBHOOK_LAST_SUCCESS_AT, 0L);
        }
        String endpoint = prefs.getString(KEY_WEBHOOK_LAST_ENDPOINT, null);
        if (endpoint != null && !endpoint.trim().isEmpty()) {
            return prefs.getLong(KEY_WEBHOOK_LAST_REPORTED, 0L);
        }
        return 0L;
    }

    static void setWebhookLastReportedAtNow(Context context) {
        if (context == null) return;
        SharedPreferences prefs = KeepADBPreferences.prefs(context);
        prefs.edit().putLong(KEY_WEBHOOK_LAST_REPORTED, System.currentTimeMillis()).apply();
    }

    static String getWebhookLastReportedEndpoint(Context context) {
        if (context == null) return null;
        SharedPreferences prefs = KeepADBPreferences.prefs(context);
        return prefs.getString(KEY_WEBHOOK_LAST_ENDPOINT, null);
    }

    static void setWebhookLastReportedEndpoint(Context context, String endpoint) {
        if (context == null) return;
        SharedPreferences prefs = KeepADBPreferences.prefs(context);
        if (endpoint == null || endpoint.trim().isEmpty()) {
            prefs.edit().remove(KEY_WEBHOOK_LAST_ENDPOINT).apply();
        } else {
            prefs.edit().putString(KEY_WEBHOOK_LAST_ENDPOINT, endpoint.trim()).apply();
        }
    }

    /**
     * Sanitised on read so a legacy URL containing user information cannot be reused by a later
     * cleanup request or written to a log. Production cleanup uses HTTP DELETE at the stored URL.
     */
    static String getWebhookLastReportedUrl(Context context) {
        if (context == null) return null;
        SharedPreferences prefs = KeepADBPreferences.prefs(context);
        return WebhookUrlCodec.sanitize(prefs.getString(KEY_WEBHOOK_LAST_URL, null));
    }

    static void setWebhookLastReportedUrl(Context context, String url) {
        if (context == null) return;
        SharedPreferences prefs = KeepADBPreferences.prefs(context);
        String sanitized = WebhookUrlCodec.sanitize(url);
        if (sanitized == null) {
            prefs.edit().remove(KEY_WEBHOOK_LAST_URL).apply();
        } else {
            prefs.edit().putString(KEY_WEBHOOK_LAST_URL, sanitized).apply();
        }
    }

    /**
     * Returns the last Wifi-ADB webhook result. Missing status is derived from the legacy fields
     * so existing installations keep their previous success/deregistration meaning.
     */
    static String getWebhookLastReportStatus(Context context) {
        if (context == null) return WEBHOOK_STATUS_NEVER;
        SharedPreferences prefs = KeepADBPreferences.prefs(context);
        String stored = prefs.getString(KEY_WEBHOOK_LAST_STATUS, null);
        if (isWebhookReportStatus(stored)) {
            return stored;
        }
        String endpoint = prefs.getString(KEY_WEBHOOK_LAST_ENDPOINT, null);
        if (endpoint != null && !endpoint.trim().isEmpty()) {
            return WEBHOOK_STATUS_SUCCESS;
        }
        return prefs.getLong(KEY_WEBHOOK_LAST_REPORTED, 0L) > 0L
                ? WEBHOOK_STATUS_DEREGISTERED : WEBHOOK_STATUS_NEVER;
    }

    static void setWebhookLastReportStatus(Context context, String status) {
        if (context == null) return;
        SharedPreferences prefs = KeepADBPreferences.prefs(context);
        if (isWebhookReportStatus(status)) {
            prefs.edit().putString(KEY_WEBHOOK_LAST_STATUS, status).apply();
        } else {
            prefs.edit().remove(KEY_WEBHOOK_LAST_STATUS).apply();
        }
    }

    private static boolean isWebhookReportStatus(String status) {
        return WEBHOOK_STATUS_SUCCESS.equals(status)
                || WEBHOOK_STATUS_DEREGISTERED.equals(status)
                || WEBHOOK_STATUS_FAILED.equals(status)
                || WEBHOOK_STATUS_NEVER.equals(status);
    }

    /**
     * #317: writes the whole Wifi-ADB report snapshot through a single {@link SharedPreferences.Editor}.
     *
     * <p>Timestamp, URL, endpoint and status used to be written through four independent
     * {@code apply()} calls, so a crash or LMK kill between two of them could persist a URL
     * without its endpoint (or a success status without the endpoint it refers to). One editor
     * with several {@code put*()} calls is written to the preferences file as one unit.
     *
     * <p>A {@code null} {@code status} leaves the stored status untouched; any other value follows
     * the same validity rule as {@link #setWebhookLastReportStatus(Context, String)}.
     */
    static void setWebhookReportSnapshot(Context context, String url, String endpoint, String status,
            boolean touchTimestamp) {
        if (context == null) return;
        SharedPreferences prefs = KeepADBPreferences.prefs(context);
        SharedPreferences.Editor editor = prefs.edit();
        // #923: every snapshot is written by a settled register transaction, which also settles
        // the dispatch marker; in the same editor transaction, so no crash can split the two.
        editor.remove(KEY_WEBHOOK_DISPATCHED_URL);
        String sanitizedUrl = WebhookUrlCodec.sanitize(url);
        if (sanitizedUrl == null) {
            editor.remove(KEY_WEBHOOK_LAST_URL);
        } else {
            editor.putString(KEY_WEBHOOK_LAST_URL, sanitizedUrl);
        }
        if (endpoint == null) {
            editor.remove(KEY_WEBHOOK_LAST_ENDPOINT);
        } else {
            editor.putString(KEY_WEBHOOK_LAST_ENDPOINT, endpoint);
        }
        if (status != null) {
            if (isWebhookReportStatus(status)) {
                editor.putString(KEY_WEBHOOK_LAST_STATUS, status);
            } else {
                editor.remove(KEY_WEBHOOK_LAST_STATUS);
            }
        }
        if (touchTimestamp) {
            long now = System.currentTimeMillis();
            editor.putLong(KEY_WEBHOOK_LAST_REPORTED, now);
            if (WEBHOOK_STATUS_SUCCESS.equals(status)) {
                editor.putLong(KEY_WEBHOOK_LAST_SUCCESS_AT, now);
                if (sanitizedUrl == null) {
                    editor.remove(KEY_WEBHOOK_LAST_SUCCESS_URL);
                } else {
                    editor.putString(KEY_WEBHOOK_LAST_SUCCESS_URL, sanitizedUrl.trim());
                }
            }
        }
        editor.apply();
    }

    // ---- #866: bounded retry series for a failed endpoint-report POST (fixed since #960). ----

    /** Retries per series, after the first attempt. */
    static final int WEBHOOK_MAX_RETRIES = 4;
    /** Delay before retry 1..4 of a series: 5, 15, 30 and 60 seconds. */
    private static final long[] WEBHOOK_RETRY_DELAYS_MS = {5_000L, 15_000L, 30_000L, 60_000L};

    /** Delay before retry {@code retryNumber} (1-based) of the fixed series, in milliseconds. */
    static long webhookRetryDelayMs(int retryNumber) {
        int index = Math.max(1, Math.min(WEBHOOK_RETRY_DELAYS_MS.length, retryNumber)) - 1;
        return WEBHOOK_RETRY_DELAYS_MS[index];
    }

    /** Epoch-millis of the first failed report of the current failure episode, or 0 without one. */
    static long getWebhookFailureSince(Context context) {
        if (context == null) return 0L;
        SharedPreferences prefs = KeepADBPreferences.prefs(context);
        return prefs.getLong(KEY_WEBHOOK_FAILURE_SINCE, 0L);
    }

    static int getWebhookAlertsShown(Context context) {
        if (context == null) return 0;
        SharedPreferences prefs = KeepADBPreferences.prefs(context);
        return prefs.getInt(KEY_WEBHOOK_ALERTS_SHOWN, 0);
    }

    /** One editor transaction, so an episode start is never stored without its alert count. */
    static void setWebhookFailureEpisode(Context context, long failureSince, int alertsShown) {
        if (context == null) return;
        SharedPreferences prefs = KeepADBPreferences.prefs(context);
        SharedPreferences.Editor editor = prefs.edit();
        if (failureSince <= 0L) {
            editor.remove(KEY_WEBHOOK_FAILURE_SINCE).remove(KEY_WEBHOOK_ALERTS_SHOWN);
        } else {
            editor.putLong(KEY_WEBHOOK_FAILURE_SINCE, failureSince)
                    .putInt(KEY_WEBHOOK_ALERTS_SHOWN, alertsShown);
        }
        editor.apply();
    }

    /**
     * #923: the destination of a registering POST that has been sent but whose outcome nothing has
     * settled yet, or {@code null}. Written synchronously before the request, so a process that
     * dies while the POST is in flight still knows where it may have registered. Like the pending
     * cleanups, the raw URL is kept (the DELETE needs it) and sanitized only where it is used.
     */
    static String getWebhookDispatchedUrl(Context context) {
        if (context == null) return null;
        String url = KeepADBPreferences.prefs(context)
                .getString(KEY_WEBHOOK_DISPATCHED_URL, null);
        return url == null || url.trim().isEmpty() ? null : url;
    }

    /** Writes or clears the #923 dispatch marker with {@code commit()}: it must land before the POST. */
    static void setWebhookDispatchedUrl(Context context, String url) {
        if (context == null) return;
        SharedPreferences.Editor editor =
                KeepADBPreferences.prefs(context).edit();
        if (url == null || url.trim().isEmpty()) {
            editor.remove(KEY_WEBHOOK_DISPATCHED_URL);
        } else {
            editor.putString(KEY_WEBHOOK_DISPATCHED_URL, url);
        }
        editor.commit();
    }
}
