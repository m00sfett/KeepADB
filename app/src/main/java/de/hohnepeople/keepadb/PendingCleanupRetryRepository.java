package de.hohnepeople.keepadb;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * The persisted retry record of one pending register cleanup (#317), extracted from
 * {@link KeepADBRegisterClient} in #701 without any change of behavior.
 *
 * <p>A pending cleanup is a stored webhook URL whose registration could not be deleted yet. The
 * set of those URLs -- the four-entry FIFO, its {@code _order} shadow key and the migration of the
 * legacy {@code StringSet} -- stays in {@link KeepADBPreferences}. This class only keeps, per
 * stored entry, how often its DELETE failed and when the next attempt and the expiry fall due:
 * the key under which that record is stored, reading and writing it, the backoff table and the
 * expiry and attempt budget. An unreachable host is thereby bounded independently of the FIFO cap
 * and cannot block every later register transaction.
 *
 * <p>All times are <em>absolute wall-clock</em> epoch milliseconds, handed in by the caller. They
 * are persisted and must stay meaningful after a reboot, which a monotonic clock would not:
 * {@code SystemClock.elapsedRealtime()} restarts near zero, so a stored 24h expiry would be
 * unreachable until uptime climbed back to the old absolute value. The caller owns the clock
 * ({@code KeepADBRegisterClient.pendingCleanupNow}). The separate in-memory #562 retry gate of
 * {@code markUnavailableAsync} is another retry circle: it is never persisted, runs on the
 * monotonic clock and shares nothing with this class.
 *
 * <p>This class stores, it never schedules or decides about transactions. It holds no state beyond
 * its constants -- no queue, thread, timer, registration cache or lock -- and knows no operation
 * generation. {@link KeepADBRegisterClient} stays the single owner of the flush, the DELETE, the
 * commit monitor, the executor and {@code currentOpGeneration}; it alone decides when an entry is
 * attempted, discarded or dropped as obsolete, and it removes the stored entry itself in
 * {@link KeepADBPreferences}. In production every call happens on the register executor, either
 * outside the client's monitor or inside one of its commit blocks; none of them touches the
 * network.
 *
 * <p>#707: a record belongs to a stored entry and must not outlive it. It could: a full FIFO evicts
 * its oldest entry for a newer one, and a crash can land between removing an entry and removing its
 * record. The client removes the record of an entry it saw evicted, and {@link #removeOrphans}
 * sweeps what an earlier build or such a crash left behind. This class owns the record key, so the
 * scan over the stored keys lives here; which entries are still pending is the caller's knowledge
 * and handed in.
 */
final class PendingCleanupRetryRepository {
    // Deliberately the client's tag: the register log lines of this record stay under one tag.
    private static final String TAG = "KeepADBRegisterClient";
    private static final String PREFS_NAME = "keepadb_prefs";
    private static final String KEY_PENDING_CLEANUP_RETRY_STATE =
            "register_webhook_pending_cleanup_retry_state";
    static final int MAX_ATTEMPTS = 3;
    static final long EXPIRY_MS = 24L * 60L * 60L * 1000L;
    static final long INITIAL_BACKOFF_MS = 30_000L;
    static final long MAX_BACKOFF_MS = 5L * 60L * 1000L;

    private PendingCleanupRetryRepository() {}

    /** The stored retry record of one pending entry; immutable. */
    static final class RetryState {
        final int attempts;
        final long nextAttemptAt;
        final long expiresAt;

        RetryState(int attempts, long nextAttemptAt, long expiresAt) {
            this.attempts = attempts;
            this.nextAttemptAt = nextAttemptAt;
            this.expiresAt = expiresAt;
        }

        boolean isExpired(long now) {
            return now >= expiresAt;
        }

        boolean isExhausted() {
            return attempts >= MAX_ATTEMPTS;
        }

        boolean isDue(long now) {
            return now >= nextAttemptAt;
        }
    }

    /**
     * Reads the record of {@code entry}. An entry without a record, or with a malformed one, reads
     * as a fresh record: no failed attempt, due immediately, expiring {@link #EXPIRY_MS} after
     * {@code now}.
     */
    static RetryState read(Context context, String entry, long now) {
        RetryState fallback = new RetryState(0, now, saturatingAdd(now, EXPIRY_MS));
        if (context == null || entry == null) return fallback;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String stored = prefs.getString(key(entry), null);
        if (stored == null || stored.trim().isEmpty()) return fallback;
        String[] fields = stored.split(",", -1);
        if (fields.length != 3) {
            Log.w(TAG, "Ignoring malformed pending cleanup retry state");
            return fallback;
        }
        try {
            int attempts = Math.max(0, Integer.parseInt(fields[0]));
            long nextAttemptAt = Long.parseLong(fields[1]);
            long expiresAt = Long.parseLong(fields[2]);
            return new RetryState(attempts, nextAttemptAt, expiresAt);
        } catch (NumberFormatException e) {
            Log.w(TAG, "Ignoring malformed pending cleanup retry state");
            return fallback;
        }
    }

    /**
     * Books one more failed DELETE for {@code entry}. Returns {@code true} when that used up the
     * attempt budget: nothing is stored then and the caller drops the entry together with its
     * record ({@link #remove}). Otherwise the next attempt is scheduled by the exponential backoff
     * and the record is written, keeping its original expiry.
     */
    static boolean recordFailure(Context context, String entry, long now) {
        RetryState state = read(context, entry, now);
        int attempts = state.attempts + 1;
        if (attempts >= MAX_ATTEMPTS) {
            return true;
        }
        write(context, entry, new RetryState(attempts,
                saturatingAdd(now, backoffMs(attempts)), state.expiresAt));
        return false;
    }

    static void remove(Context context, String entry) {
        if (context == null || entry == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String key = key(entry);
        if (prefs.getString(key, null) == null) return;
        prefs.edit().remove(key).apply();
    }

    /**
     * #707: removes the stored retry record of every entry that is not in {@code activeEntries}
     * and returns how many records went. Only keys of this record are looked at (the key prefix
     * including its colon); the stored entries, the FIFO keys and every other preference stay
     * untouched. An entry counts as active only when it equals one of {@code activeEntries}
     * exactly: a record belongs to the raw stored entry, so another spelling of the same resource
     * is no match. The value of a record is not looked at, so a malformed one of a dead entry goes
     * too, while the record of an active entry stays as it is. Nothing is removed when the caller
     * does not know its entries ({@code null}). Writes once, and only when something is removed.
     * No network and no decision about any transaction; the caller calls it on the register
     * executor, outside its monitor.
     */
    static int removeOrphans(Context context, String[] activeEntries) {
        if (context == null || activeEntries == null) return 0;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String prefix = key("");
        SharedPreferences.Editor editor = null;
        int removed = 0;
        for (String stored : prefs.getAll().keySet()) {
            if (!stored.startsWith(prefix)) continue;
            if (isActive(stored.substring(prefix.length()), activeEntries)) continue;
            if (editor == null) editor = prefs.edit();
            editor.remove(stored);
            removed++;
        }
        if (editor != null) editor.apply();
        if (removed > 0) {
            Log.i(TAG, "Removed " + removed + " orphaned pending cleanup retry record(s)");
        }
        return removed;
    }

    private static boolean isActive(String entry, String[] activeEntries) {
        for (String active : activeEntries) {
            if (entry.equals(active)) return true;
        }
        return false;
    }

    private static void write(Context context, String entry, RetryState state) {
        if (context == null || entry == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String encoded = state.attempts + "," + state.nextAttemptAt + "," + state.expiresAt;
        prefs.edit().putString(key(entry), encoded).apply();
    }

    private static String key(String entry) {
        return KEY_PENDING_CLEANUP_RETRY_STATE + ":" + entry;
    }

    static long backoffMs(int attempts) {
        long backoff = INITIAL_BACKOFF_MS;
        for (int i = 1; i < attempts && backoff < MAX_BACKOFF_MS; i++) {
            if (backoff > MAX_BACKOFF_MS / 2L) {
                return MAX_BACKOFF_MS;
            }
            backoff *= 2L;
        }
        return Math.min(backoff, MAX_BACKOFF_MS);
    }

    private static long saturatingAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }
}
