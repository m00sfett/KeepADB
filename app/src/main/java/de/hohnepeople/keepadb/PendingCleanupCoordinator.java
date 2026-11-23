package de.hohnepeople.keepadb;

import android.content.Context;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * #1049: the deferred withdrawals of {@link KeepADBRegisterClient} (#317, #707, #923, #934),
 * extracted from the client: the flush that retries queued cleanups before a transaction, the
 * queuing of a possible registration, the due decision over the persisted retry records of
 * {@link PendingCleanupRetryRepository} and the canonical registration resource key.
 *
 * <p>Holds no state besides a test clock and takes no lock of its own. Confinement per method:
 * {@link #flush}, {@link #rememberPossibleRegistration} and {@link #recordFailuresLeftToTransaction}
 * run only on the register executor and never under the client's class monitor, because the flush
 * sends DELETE requests through {@link RegisterTransport#deleteEndpoint}; they read the client's
 * registered URL through its synchronized accessor. {@link #queue}, {@link #removeForResource} and
 * {@link #hasDue} are pure preference bookkeeping that sends nothing; the client also calls them
 * under its monitor (the update commit block, the endpoint-loss decision).
 */
final class PendingCleanupCoordinator {
    // Same tag as the client, so existing log filters keep matching.
    private static final String TAG = "KeepADBRegisterClient";
    private static volatile Long nowForTesting;

    private PendingCleanupCoordinator() {}

    /**
     * #317: retries cleanups that a previous URL change could not deliver. Runs on the register
     * executor before the transaction that triggered it, so a lost cleanup is retried at the next
     * register activity (endpoint change, deregistration) rather than being forgotten. The backlog
     * is capped by {@link PendingWebhookCleanupStore#MAX_PENDING_CLEANUPS}.
     *
     * <p>#707: first sweeps the retry records whose entry is no longer pending (left behind by an
     * eviction of an earlier build, or by a crash between removing an entry and its record). That
     * is local bookkeeping on the preferences file: it sends nothing and changes no entry.
     *
     * @param targetUrl the URL the transaction that runs next sends its own request to
     * @param deleting whether that transaction is a withdrawal (DELETE) rather than an update
     * @return #934: the due entries left to that transaction, which deletes or posts their
     *         resource itself; it books a failure on them if its own request fails
     */
    static List<String> flush(Context context, String targetUrl, boolean deleting) {
        List<String> leftToTransaction = new ArrayList<>();
        if (context == null) return leftToTransaction;
        PendingCleanupRetryRepository.removeOrphans(context,
                PendingWebhookCleanupStore.get(context).toArray(new String[0]));
        // #923: a dispatch marker left here is a POST whose process died before its outcome was
        // settled (every POST that returns settles its own marker on this executor first).
        String interrupted = WebhookReportPrefs.getWebhookDispatchedUrl(context);
        // A withdrawing transaction always queues it; an update keeps it while it is the target.
        if (interrupted != null) rememberPossibleRegistration(context, interrupted, deleting);
        // #923: the resource the transaction that runs next sends its own DELETE or POST to; a
        // DELETE goes to the registered URL when there is one (as in the delete transaction).
        String inUseUrl = targetUrl;
        if (deleting) {
            String registered = KeepADBRegisterClient.registeredUrl();
            if (registered != null) inUseUrl = registered;
        }
        for (String url : PendingWebhookCleanupStore.get(context)) {
            // #710: an earlier iteration may already have removed this snapshot entry (another
            // spelling of the same resource); skip it so no redundant DELETE or orphan record.
            if (!PendingWebhookCleanupStore.get(context).contains(url)) {
                continue;
            }
            String sanitizedUrl = sanitize(url);
            if (hasLiveRegistrationAt(sanitizedUrl)) {
                removeForResource(context, sanitizedUrl);
                continue;
            }
            // #934: decide first, so an expired or spent entry is discarded even when it is in use.
            PendingCleanupRetryRepository.RetryState due = due(context, url);
            if (due == null) {
                continue;
            }
            // #923: the transaction that runs next sends its own DELETE or POST to this resource
            // and settles the entry with it (#934: a failed DELETE is booked on the entry); a
            // second request from here would only duplicate it.
            if (inUseUrl != null && sameRegistrationResource(sanitizedUrl, inUseUrl)) {
                leftToTransaction.add(url);
                continue;
            }
            boolean deleted = false;
            if (sanitizedUrl != null) {
                // The first DELETE ran with the URL change; every deferred attempt is a retry.
                int retry = due.attempts + 1;
                TransportResult result = RegisterTransport.deleteEndpoint(sanitizedUrl);
                deleted = result.ok;
                // #892 (Codex review): a deferred cleanup is an unregister attempt as well; its
                // endpoint is no longer known, and the URL is never recorded.
                KeepADBReportHistory.unregister(context, "", deleted, result.outcome, retry,
                        PendingCleanupRetryRepository.MAX_ATTEMPTS);
            }
            if (deleted) {
                removeForResource(context, sanitizedUrl);
            } else {
                recordFailure(context, url);
            }
        }
        return leftToTransaction;
    }

    /**
     * #923: {@code url} may hold a registration that the local report snapshot does not record --
     * a POST that succeeded after its transaction went stale, one whose answer was lost, or one
     * interrupted by process death. If the snapshot names that resource, the next withdrawal
     * targets it anyway and the dispatch marker is cleared. If it is still the saved URL with
     * synchronization on and nothing is being withdrawn, the marker is kept as durable evidence
     * (its next successful report clears it, any later withdrawal queues it). Otherwise it is
     * queued as a pending cleanup (#317: persisted, retried with backoff, withdrawn at the next
     * register activity) and the marker is cleared.
     *
     * @param withdrawing the caller is a DELETE transaction (opt-out, endpoint loss)
     */
    static void rememberPossibleRegistration(Context context, String url, boolean withdrawing) {
        String registered = KeepADBRegisterClient.registeredUrl();
        if (registered != null && sameRegistrationResource(url, registered)) {
            WebhookReportPrefs.setWebhookDispatchedUrl(context, null);
            return;
        }
        boolean stillTheTarget = KeepADBPreferences.isRegisterWebhookEnabled(context)
                && sameRegistrationResource(url, KeepADBPreferences.getRegisterWebhookUrl(context));
        if (stillTheTarget && !withdrawing) {
            // Queuing it now would also evict an older cleanup from a full backlog.
            WebhookReportPrefs.setWebhookDispatchedUrl(context, url);
            return;
        }
        queue(context, url);
        WebhookReportPrefs.setWebhookDispatchedUrl(context, null);
    }

    /**
     * Queues {@code url} as a pending cleanup. #707: a full backlog evicts its oldest entry for it;
     * that entry is gone for good, so its retry record goes with it -- left behind, it would leak
     * and be inherited if the same entry is queued again.
     */
    static void queue(Context context, String url) {
        String evicted = PendingWebhookCleanupStore.add(context, url);
        if (evicted != null) PendingCleanupRetryRepository.remove(context, evicted);
    }

    /**
     * #923 (review R3): whether a queued withdrawal would be attempted now -- the decision of
     * {@link #due} without its side effects (an expired or spent entry is discarded there, by the
     * flush, not here).
     */
    static boolean hasDue(Context context) {
        long now = now();
        for (String entry : PendingWebhookCleanupStore.get(context)) {
            PendingCleanupRetryRepository.RetryState state = read(context, entry, now);
            if (!state.isExpired(now) && !state.isExhausted() && state.isDue(now)) return true;
        }
        return false;
    }

    /** #934: books a failed attempt on every due entry the flush left to the transaction. */
    static void recordFailuresLeftToTransaction(Context context, List<String> leftToTransaction) {
        if (context == null || leftToTransaction == null) return;
        Set<String> pending = PendingWebhookCleanupStore.get(context);
        for (String entry : leftToTransaction) {
            if (pending.contains(entry)) recordFailure(context, entry);
        }
    }

    /**
     * Drops queued cleanup requests for the same canonical URL after a later request succeeds.
     * Legacy stored URLs may contain userinfo or fragments, so compare sanitized resource keys
     * while removing the exact stored entry.
     */
    static void removeForResource(Context context, String targetUrl) {
        if (context == null || targetUrl == null) return;
        String targetKey = registrationResourceKey(targetUrl);
        if (targetKey == null) return;

        for (String rawUrl : PendingWebhookCleanupStore.get(context)) {
            if (targetKey.equals(registrationResourceKey(rawUrl))) {
                PendingWebhookCleanupStore.remove(context, rawUrl);
                PendingCleanupRetryRepository.remove(context, rawUrl);
            }
        }
    }

    static boolean sameRegistrationResource(String leftUrl, String rightUrl) {
        String leftKey = registrationResourceKey(leftUrl);
        String rightKey = registrationResourceKey(rightUrl);
        return leftKey != null && leftKey.equals(rightKey);
    }

    /**
     * The canonical resource a URL registers at: lower-case scheme and host, explicit default
     * port, raw path without trailing slashes, no userinfo, query or fragment.
     */
    static String registrationResourceKey(String rawUrl) {
        String sanitizedUrl = sanitize(rawUrl);
        if (sanitizedUrl == null) return null;
        try {
            java.net.URI uri = new java.net.URI(sanitizedUrl);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) return sanitizedUrl;
            scheme = scheme.toLowerCase(Locale.ROOT);
            host = host.toLowerCase(Locale.ROOT);
            if (host.indexOf(':') >= 0) {
                host = "[" + host + "]";
            }
            int port = uri.getPort();
            if (port < 0) {
                port = "https".equals(scheme) ? 443 : 80;
            }
            String path = uri.getRawPath();
            if (path == null || path.isEmpty()) path = "/";
            while (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            return scheme + "://" + host + ":" + port + path;
        } catch (Exception ignored) {
            return sanitizedUrl;
        }
    }

    /**
     * #576 (NET-02 / part B): wall-clock time for the *persisted* pending-cleanup queue (#317).
     * Deliberately kept on {@link System#currentTimeMillis()} rather than a monotonic clock:
     * {@code nextAttemptAt}/{@code expiresAt} are written to SharedPreferences and must survive an
     * app or device restart, but {@code SystemClock.elapsedRealtime()} resets to (near) zero on
     * every reboot, which would make a stored 24h expiry (or backoff) effectively unreachable after
     * a reboot -- a far worse regression than the wall-clock-jump risk this gate accepts. The
     * in-memory #562 gate has no such persistence and uses the monotonic clock, see {@link
     * UnavailableDeleteBackoff#now()}. The record itself reads no clock and only receives this.
     */
    static long now() {
        Long testNow = nowForTesting;
        return testNow != null ? testNow : System.currentTimeMillis();
    }

    static void setNowForTesting(Long now) {
        nowForTesting = now;
    }

    /**
     * A pending cleanup is bounded independently of the four-entry FIFO cap in {@link
     * KeepADBPreferences}: each entry gets a persisted expiry, attempt budget and exponential
     * backoff, so an unreachable host cannot block every later register transaction. The record
     * and its numbers live in {@link PendingCleanupRetryRepository} (#701); this class keeps the
     * decision when an entry is attempted or discarded, and the clock.
     */
    private static PendingCleanupRetryRepository.RetryState due(Context context, String entry) {
        long now = now();
        PendingCleanupRetryRepository.RetryState state = read(context, entry, now);
        if (state.isExpired(now)) {
            discard(context, entry, "expired");
            return null;
        }
        if (state.isExhausted()) {
            discard(context, entry, "attempt_limit");
            return null;
        }
        return state.isDue(now) ? state : null;
    }

    /** The one place a retry record is read (#701). */
    private static PendingCleanupRetryRepository.RetryState read(Context context, String entry,
            long now) {
        return PendingCleanupRetryRepository.read(context, entry, now);
    }

    private static void recordFailure(Context context, String entry) {
        if (PendingCleanupRetryRepository.recordFailure(context, entry, now())) {
            discard(context, entry, "attempt_limit");
        }
    }

    private static void discard(Context context, String entry, String reason) {
        if (entry == null) return;
        PendingWebhookCleanupStore.remove(context, entry);
        PendingCleanupRetryRepository.remove(context, entry);
        Log.w(TAG, "Dropping pending register cleanup (" + reason + "): "
                + KeepADBRegisterClient.sanitizeUrl(entry));
    }

    private static boolean hasLiveRegistrationAt(String cleanupUrl) {
        if (cleanupUrl == null || cleanupUrl.trim().isEmpty()) {
            return false;
        }
        return sameRegistrationResource(cleanupUrl, KeepADBRegisterClient.registeredUrl());
    }

    /**
     * #377: legacy pending entries may contain userinfo even though new entries are sanitised on
     * write. Keep the stored entry unchanged so exact set removal still works, and only use the
     * sanitised URL for the outgoing cleanup request.
     */
    private static String sanitize(String rawUrl) {
        String sanitized = WebhookUrlCodec.sanitize(rawUrl);
        return sanitized == null || sanitized.trim().isEmpty() ? null : sanitized;
    }
}
