package de.hohnepeople.keepadb;

import android.content.Context;
import android.util.Log;
import java.util.List;

/**
 * #1054: the two register transactions, moved out of {@link KeepADBRegisterClient}. They run on
 * the client's register executor only (each call sits in one of its tasks) and send their
 * requests outside the client's class monitor. Every read of the local report snapshot and every
 * commit takes that same monitor ({@code synchronized (KeepADBRegisterClient.class)}) and goes
 * through the client's {@code ...Locked} callbacks; the monitor, the operation generation and the
 * executor stay owned by the client. Apart from the episode re-attempt's endpoint probe seam this
 * class holds no state.
 */
final class RegisterTransactions {
    private static final String TAG = "KeepADBRegisterClient";

    /** #881: reachability check of the cached endpoint before an episode re-attempt. */
    interface EndpointProbe {
        boolean isReachable(String endpoint);
    }

    private static final int REATTEMPT_PROBE_WAIT_MILLIS = 400;
    private static volatile EndpointProbe endpointProbe = RegisterTransactions::probeEndpoint;

    private RegisterTransactions() {}

    /**
     * Runs a report on the register executor, after the client found its operation current.
     *
     * @param probeFirst #881: probe the endpoint before reporting it. Codex review: adbd may have
     *         rotated its port without a network callback, and the cached endpoint is only
     *         re-verified asynchronously. An episode re-attempt never reports an address that does
     *         not answer; the endpoint health check reports the new one itself.
     */
    static void update(Context context, String targetUrl, String endpoint, long opGen,
            boolean probeFirst) {
        if (probeFirst && !endpointProbe.isReachable(endpoint)) {
            synchronized (KeepADBRegisterClient.class) {
                if (KeepADBRegisterClient.isCurrent(opGen)) {
                    KeepADBRegisterClient.endUpdateLocked();
                    KeepADBRegisterClient.seriesLocked().giveUp();
                }
            }
            KeepADBDiagnostics.event(context, "register_report", "webhook", "skipped",
                    "method=post reason=endpoint_unreachable reattempt=true");
            return;
        }
        performUpdateTransaction(context, targetUrl, endpoint, opGen);
    }

    /**
     * Runs a withdrawal on the register executor, after the client found its operation current.
     * {@code endpointLoss}: the DELETE belongs to the #562 endpoint-loss series, whose failures
     * count as retries in the #892 history; an explicit unregister always starts at zero.
     */
    static void delete(Context context, String targetUrl, long opGen, boolean endpointLoss) {
        performDeleteTransaction(context, targetUrl, opGen, endpointLoss);
    }

    private static void performUpdateTransaction(Context context, String targetUrl, String targetEndpoint, long opGen) {
        PendingCleanupCoordinator.flush(context, targetUrl, false);

        String oldUrl;
        String oldEndpoint;
        synchronized (KeepADBRegisterClient.class) {
            oldUrl = KeepADBRegisterClient.registeredUrlLocked();
            oldEndpoint = KeepADBRegisterClient.registeredEndpointLocked();
        }

        // If URL changed and a prior local report exists, try DELETE at the old URL first
        String unfinishedCleanupUrl = null;
        if (oldUrl != null && !oldUrl.equals(targetUrl) && oldEndpoint != null) {
            TransportResult oldDelete = RegisterTransport.deleteEndpoint(oldUrl);
            // #892 (Codex review): the DELETE at the previous URL belongs in the history too.
            KeepADBReportHistory.unregister(context, oldEndpoint, oldDelete.ok, oldDelete.outcome, 0);
            if (oldDelete.ok) {
                // Clear the matching local report snapshot after the DELETE request reports
                // success. The single-threaded executor prevents a later transaction from
                // overlapping this update.
                synchronized (KeepADBRegisterClient.class) {
                    KeepADBRegisterClient.forgetRegistrationLocked(oldUrl);
                }
            } else {
                // Keep the previous successful report until the replacement POST succeeds. If the
                // new target fails, the UI must still show the last endpoint that was actually
                // reported successfully rather than losing it during this transition.
                Log.w(TAG, "Failed to deregister from old URL "
                        + KeepADBRegisterClient.sanitizeUrl(oldUrl)
                        + " during URL change; keeping the cleanup for a later retry");
                unfinishedCleanupUrl = oldUrl;
            }
        }

        if (!KeepADBRegisterClient.isCurrent(opGen)) return;

        // POST to new target URL
        final String cleanupToRemember = unfinishedCleanupUrl;
        boolean failingBefore = isReportFailing(context);
        int retry;
        synchronized (KeepADBRegisterClient.class) {
            // Codex review: a new series may reset the counter while this request is in flight.
            retry = KeepADBRegisterClient.seriesLocked().retryNumber();
        }
        // Codex review: the budget in effect when the request is sent, not after its answer.
        int maxRetries = KeepADBRegisterClient.retryBudget();
        // #923: persisted before the request, settled after it (below), so a registration made by
        // a POST whose transaction went stale, or whose process died, is still withdrawn later.
        String markerBefore = WebhookReportPrefs.getWebhookDispatchedUrl(context);
        WebhookReportPrefs.setWebhookDispatchedUrl(context, targetUrl);
        TransportResult post = RegisterTransport.postEndpoint(targetUrl, targetEndpoint);
        // #923 (review): only an HTTP answer proves this request registered nothing; an I/O error
        // can come after the body was delivered, so the marker stays as evidence then. A definite
        // rejection restores the marker this POST found (an earlier, unsettled one stays).
        if (!post.ok && post.isHttpAnswer()) {
            WebhookReportPrefs.setWebhookDispatchedUrl(context, markerBefore);
        }
        recordReportOutcome(context, "post", targetEndpoint, post, failingBefore, opGen, retry,
                maxRetries);
        if (post.ok) {
            // #671: the primary POST can block for seconds, during which a disconnect or a newer
            // endpoint may have superseded this transaction. Abort before any secondary transport
            // is sent, so a stale snapshot never goes out for an outdated target. Same outcome as
            // the stale branch of the synchronized block below: no state, preference or listener
            // side effects (the newer operation owns those) -- except #923: the receiver may now
            // hold a registration at targetUrl that no state accounts for; queue its withdrawal.
            if (!KeepADBRegisterClient.isCurrent(opGen)) {
                PendingCleanupCoordinator.rememberPossibleRegistration(context, targetUrl, false);
                return;
            }
            // Consider other verified transport candidates only after the WLAN POST. The local
            // sender allowlist currently holds these candidates back; WLAN success accounting and
            // the stored report snapshot remain owned by this transaction.
            RegisterTransport.reportAdditionalVerifiedTransports(context, targetUrl);
            synchronized (KeepADBRegisterClient.class) {
                if (KeepADBRegisterClient.isCurrent(opGen)) {
                    // Persist pending cleanup before changing the local report snapshot, so a
                    // crash cannot forget the old URL. If the replacement POST failed, the local
                    // snapshot stays with the old target for a later retry. (#707: queuing also
                    // removes the record of an entry a full backlog evicted for it.)
                    if (cleanupToRemember != null) {
                        PendingCleanupCoordinator.queue(context, cleanupToRemember);
                    }
                    // A cleanup queued before this successful POST is stale local work. Drop it
                    // so it cannot issue an outdated DELETE after the newer registration attempt.
                    PendingCleanupCoordinator.removeForResource(context, targetUrl);
                    KeepADBRegisterClient.endUpdateLocked();
                    // #866: success ends the retry series, #868 the failure episode.
                    KeepADBRegisterClient.seriesLocked().cancel(KeepADBRegisterClient.mainHandler());
                    KeepADBWebhookAlert.endEpisode(context);
                    // A successful WLAN report supersedes any pending unavailable retry for the
                    // previous local report.
                    KeepADBRegisterClient.unavailableLocked().reset();
                    KeepADBRegisterClient.commitRegistrationLocked(targetUrl, targetEndpoint);
                    // #317: one editor transaction; four separate apply() calls could be torn apart
                    // by a crash and leave a URL without its endpoint.
                    WebhookReportPrefs.setWebhookReportSnapshot(context, targetUrl, targetEndpoint,
                            WebhookReportPrefs.WEBHOOK_STATUS_SUCCESS, true);
                    // #923: the snapshot (which also clears the dispatch marker) now accounts
                    // for this registration.
                    RegisterStateNotifier.notifyChanged(context);
                    return;
                }
            }
            // #923: superseded during the secondary transports.
            PendingCleanupCoordinator.rememberPossibleRegistration(context, targetUrl, false);
        } else {
            synchronized (KeepADBRegisterClient.class) {
                if (KeepADBRegisterClient.isCurrent(opGen)) {
                    KeepADBRegisterClient.endUpdateLocked();
                    WebhookReportPrefs.setWebhookLastReportStatus(
                            context, WebhookReportPrefs.WEBHOOK_STATUS_FAILED);
                    KeepADBRegisterClient.schedulePostRetryLocked(context, targetUrl, targetEndpoint,
                            opGen);
                    KeepADBWebhookAlert.onReportFailed(context);
                    RegisterStateNotifier.notifyChanged(context);
                }
            }
        }
    }

    private static void performDeleteTransaction(Context context, String targetUrl, long opGen,
            boolean endpointLoss) {
        List<String> cleanupsLeftHere = PendingCleanupCoordinator.flush(context, targetUrl, true);

        String urlToDelete;
        synchronized (KeepADBRegisterClient.class) {
            String registered = KeepADBRegisterClient.registeredUrlLocked();
            urlToDelete = (registered != null) ? registered : targetUrl;
            if (urlToDelete == null || urlToDelete.trim().isEmpty()) {
                KeepADBRegisterClient.endUpdateLocked();
                KeepADBRegisterClient.clearRegistrationLocked();
                KeepADBRegisterClient.unavailableLocked().reset();
                WebhookReportPrefs.setWebhookReportSnapshot(context, null, null, null, false);
                RegisterStateNotifier.notifyChanged(context);
                return;
            }
        }

        boolean failingBefore = isReportFailing(context);
        String endpointToDelete;
        int retry;
        synchronized (KeepADBRegisterClient.class) {
            endpointToDelete = KeepADBRegisterClient.registeredEndpointLocked();
            // Codex review: captured before the request, and only for the endpoint-loss series;
            // its counter outlives the series, and an explicit unregister is none of its retries.
            retry = endpointLoss ? KeepADBRegisterClient.unavailableLocked().attempts() : 0;
        }
        TransportResult delete = RegisterTransport.deleteEndpoint(urlToDelete);
        recordReportOutcome(context, "delete", endpointToDelete, delete, failingBefore,
                opGen, retry, KeepADBReportHistory.NO_MAX);
        if (delete.ok) {
            // Record the successful DELETE response before the generation check, so a newer
            // operation does not leave the local report snapshot pointing at the old URL.
            synchronized (KeepADBRegisterClient.class) {
                KeepADBRegisterClient.forgetRegistrationLocked(urlToDelete);
            }
            PendingCleanupCoordinator.removeForResource(context, urlToDelete);
            synchronized (KeepADBRegisterClient.class) {
                if (KeepADBRegisterClient.isCurrent(opGen)) {
                    KeepADBRegisterClient.endUpdateLocked();
                    KeepADBRegisterClient.unavailableLocked().reset();
                    WebhookReportPrefs.setWebhookReportSnapshot(context, null, null,
                            WebhookReportPrefs.WEBHOOK_STATUS_DEREGISTERED, true);
                    RegisterStateNotifier.notifyChanged(context);
                }
            }
        } else {
            // #934: a due queued withdrawal of this resource that the flush left to this DELETE
            // has its failure booked, so its budget, backoff and expiry apply; an entry that was
            // not due is not charged for the transaction's own (#562-paced) attempt.
            PendingCleanupCoordinator.recordFailuresLeftToTransaction(context, cleanupsLeftHere);
            synchronized (KeepADBRegisterClient.class) {
                if (KeepADBRegisterClient.isCurrent(opGen)) {
                    KeepADBRegisterClient.endUpdateLocked();
                    KeepADBRegisterClient.unavailableLocked().recordFailure();
                    WebhookReportPrefs.setWebhookLastReportStatus(
                            context, WebhookReportPrefs.WEBHOOK_STATUS_FAILED);
                    RegisterStateNotifier.notifyChanged(context);
                }
            }
        }
    }

    private static boolean isReportFailing(Context context) {
        return WebhookReportPrefs.WEBHOOK_STATUS_FAILED.equals(
                WebhookReportPrefs.getWebhookLastReportStatus(context));
    }

    /**
     * #881: a diagnostics event for every failed primary report and for the success that ends a
     * failure episode, so an episode can be explained afterwards (timeout, refused, unknown host,
     * HTTP status). Routine successes add no diagnostics event, and the event carries no URL and
     * no endpoint. #892: every attempt also goes into the separate, app-private report history,
     * which does keep the endpoint (never the URL); the #317 report snapshot stays one write.
     */
    private static void recordReportOutcome(Context context, String method, String endpoint,
            TransportResult result, boolean failingBefore, long opGen, int retry, int maxRetries) {
        boolean superseded = !KeepADBRegisterClient.isCurrent(opGen);
        boolean ok = result.ok;
        if ("delete".equals(method)) {
            KeepADBReportHistory.unregister(context, endpoint, ok, result.outcome, retry);
        } else {
            KeepADBReportHistory.report(context, endpoint, ok, result.outcome, retry, maxRetries);
        }
        if (ok && !failingBefore) return;
        // A success of a superseded transaction ends nothing: the newer operation owns the
        // report status (Codex review).
        if (ok && superseded) return;
        KeepADBDiagnostics.event(context, "register_report", "webhook", ok ? "success" : "failed",
                "method=" + method + " " + result.outcome + " retry=" + retry);
    }

    /** Parses {@code host:port} or {@code [v6]:port} as written by {@link KeepADBEndpoint#formatEndpoint}. */
    private static boolean probeEndpoint(String endpoint) {
        if (endpoint == null) return false;
        int colon = endpoint.lastIndexOf(':');
        if (colon <= 0) return false;
        String host = endpoint.substring(0, colon);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        try {
            int port = Integer.parseInt(endpoint.substring(colon + 1));
            return KeepADBEndpoint.isPortReachable(host, port, REATTEMPT_PROBE_WAIT_MILLIS);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    static void setEndpointProbeForTesting(EndpointProbe probe) {
        endpointProbe = probe != null ? probe : RegisterTransactions::probeEndpoint;
    }
}
