package de.hohnepeople.keepadb;

import android.os.Handler;

/**
 * #1049: the POST retry series of {@link KeepADBRegisterClient} (#866, #867, #881, #960),
 * extracted from the client: which URL/endpoint pair is being reported, its scheduled retry, the
 * debounced resync and the sparse episode re-attempt.
 *
 * <p>Not thread-safe on purpose: the client calls every method under its class monitor, which
 * also guards the in-flight flag and the operation generation. The timers run on the handler the
 * client passes in; this class sends nothing.
 */
final class RegisterRetrySeries {
    /** #881: the sparse re-attempt while a failure episode lasts and nothing else reports. */
    static final long EPISODE_REATTEMPT_INTERVAL_MS = 15L * 60L * 1000L;

    /**
     * #866: the current series -- the pair whose report is queued, running, waiting for its next
     * retry or, once {@link #exhausted}, given up on. A repeated call for the same pair joins it
     * instead of dispatching a parallel POST or postponing the scheduled retry. Only a different
     * pair, a network event (#867), an explicit user action (#869) or a configuration change starts
     * a new series.
     */
    private String url;
    private String endpoint;
    private boolean exhausted;
    /** #881: the current series is an episode re-attempt: one request, never a retry. */
    private boolean singleShot;
    /** #866: retries already scheduled in the current series; 0 before its first failure. */
    private int retryNumber;
    /** #866: the one scheduled retry of the current series, or null. */
    private Runnable pendingRetry;
    /** #867: the one debounced resync waiting on the handler, or null. */
    private Runnable pendingResync;
    /** #881: elapsed realtime when the series was given up on, or of the last episode re-attempt. */
    private long lastGiveUpOrReattemptAt;
    /**
     * #960: the retries of a series are fixed ({@link WebhookReportPrefs#WEBHOOK_MAX_RETRIES}).
     * Tests that want a shorter series set a lower budget; negative means the fixed series. Read
     * outside the monitor when a request records the budget it was sent with, hence volatile.
     */
    private volatile int retryBudgetForTesting = -1;

    /** #866: a call for this pair joins the series instead of starting a new one. */
    boolean joins(String url, String endpoint, boolean inFlight) {
        return url.equals(this.url) && endpoint.equals(this.endpoint)
                && (inFlight || pendingRetry != null || exhausted);
    }

    /** #866: starts a new series for the pair; drops the scheduled retry of the previous one. */
    void start(Handler handler, String url, String endpoint) {
        cancel(handler);
        this.url = url;
        this.endpoint = endpoint;
    }

    /** #866: drops the scheduled retry and the series state. */
    void cancel(Handler handler) {
        if (pendingRetry != null) {
            handler.removeCallbacks(pendingRetry);
            pendingRetry = null;
        }
        retryNumber = 0;
        singleShot = false;
        url = null;
        endpoint = null;
        exhausted = false;
    }

    /**
     * #866: after a failed POST, schedules {@code retry} as the next attempt of the series, or
     * marks the series exhausted after the fixed number of retries (#960) or after an episode
     * re-attempt (#881).
     *
     * <p>#870: deliberately a handler timer on uptime, unlike the alert's alarm. The series is short
     * and bounded, and a retry that waits through deep sleep loses nothing: the network event on
     * wake-up (#867) starts a fresh report anyway.
     *
     * @return whether a retry was scheduled
     */
    boolean scheduleRetry(Handler handler, Runnable retry, long elapsedRealtime) {
        if (singleShot || retryNumber >= retryBudget()) {
            exhausted = true;
            lastGiveUpOrReattemptAt = elapsedRealtime;
            return false;
        }
        retryNumber++;
        pendingRetry = retry;
        handler.postDelayed(retry, WebhookReportPrefs.webhookRetryDelayMs(retryNumber));
        return true;
    }

    /** Claims the scheduled retry for {@code self}; false when it was replaced or cancelled. */
    boolean takeRetry(Runnable self) {
        if (pendingRetry != self) return false;
        pendingRetry = null;
        return true;
    }

    Runnable pendingRetry() {
        return pendingRetry;
    }

    /** #881: the endpoint probe of an episode re-attempt failed; the series is given up on. */
    void giveUp() {
        exhausted = true;
    }

    /** #867: replaces any waiting resync with {@code resync}, run after {@code delayMs}. */
    void replaceResync(Handler handler, Runnable resync, long delayMs) {
        dropResync(handler);
        pendingResync = resync;
        handler.postDelayed(resync, delayMs);
    }

    void dropResync(Handler handler) {
        if (pendingResync != null) {
            handler.removeCallbacks(pendingResync);
            pendingResync = null;
        }
    }

    /** Claims the waiting resync for {@code self}; false when it was replaced or dropped. */
    boolean takeResync(Runnable self) {
        if (pendingResync != self) return false;
        pendingResync = null;
        return true;
    }

    boolean hasPendingResync() {
        return pendingResync != null;
    }

    /**
     * #881/#884: whether an episode re-attempt may start now: the series was given up on (or none
     * exists, as after a process restart during an episode), no resync is waiting, and the last
     * give-up or re-attempt is at least {@link #EPISODE_REATTEMPT_INTERVAL_MS} ago.
     */
    boolean reattemptDue(long elapsedRealtime) {
        if ((!exhausted && url != null) || pendingResync != null) return false;
        return elapsedRealtime - lastGiveUpOrReattemptAt >= EPISODE_REATTEMPT_INTERVAL_MS;
    }

    /** #881: the series just started is an episode re-attempt: one request, never a retry. */
    void markReattempt(long elapsedRealtime) {
        lastGiveUpOrReattemptAt = elapsedRealtime;
        singleShot = true;
    }

    int retryNumber() {
        return retryNumber;
    }

    boolean isExhausted() {
        return exhausted;
    }

    int retryBudget() {
        int override = retryBudgetForTesting;
        return override >= 0 ? Math.min(override, WebhookReportPrefs.WEBHOOK_MAX_RETRIES)
                : WebhookReportPrefs.WEBHOOK_MAX_RETRIES;
    }

    void setRetryBudgetForTesting(int retries) {
        retryBudgetForTesting = retries;
    }

    void resetForTesting(Handler handler) {
        retryBudgetForTesting = -1;
        lastGiveUpOrReattemptAt = 0L;
        cancel(handler);
        dropResync(handler);
    }
}
