package de.hohnepeople.keepadb;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * #1050: the persisted FIFO of pending webhook cleanups (#317, #368), split out of {@link
 * KeepADBPreferences}. Same preferences file and keys as before: the legacy {@code StringSet} under
 * {@link #KEY_WEBHOOK_PENDING_CLEANUP} and its ordered shadow key {@code KEY_WEBHOOK_PENDING_CLEANUP + "_order"}. The retry record of an entry is
 * {@link PendingCleanupRetryRepository}'s; this store does not know it.
 */
final class PendingWebhookCleanupStore {
    static final String KEY_WEBHOOK_PENDING_CLEANUP = "register_webhook_pending_cleanup";

    private PendingWebhookCleanupStore() {}

    /**
     * Upper bound for either pending-cleanup set. Each retry costs one HTTP round trip on the
     * register executor, so the backlog is deliberately small; a full set evicts its OLDEST entry
     * (FIFO, see #368) rather than growing without limit or rejecting the newest one.
     */
    static final int MAX_PENDING_CLEANUPS = 4;

    /**
     * Separator joining entries in the ordered FIFO shadow list (#368). {@code SharedPreferences}
     * string sets have no defined iteration order, so the eviction order can't be derived from the
     * legacy {@code StringSet} keys alone; this ASCII Group Separator control character is joined
     * between entries in a plain {@code String} preference instead, preserving insertion order.
     * Chosen because it cannot appear in a URL.
     */
    private static final String ORDER_SEPARATOR = "\u001D";

    static java.util.Set<String> get(Context context) {
        return getPendingCleanups(context, KEY_WEBHOOK_PENDING_CLEANUP);
    }

    /**
     * Queues {@code url} as a pending cleanup. Returns the entry that had to make room for it --
     * the OLDEST one of a full backlog, see {@link #MAX_PENDING_CLEANUPS} -- or {@code null} when
     * nothing was evicted (blank url, entry already queued, or a free slot). #707: the caller owns
     * whatever else belongs to an evicted entry (its retry record); this class does not know it.
     */
    static String add(Context context, String url) {
        if (url == null || url.trim().isEmpty()) return null;
        return addPendingCleanup(context, KEY_WEBHOOK_PENDING_CLEANUP, url);
    }

    static void remove(Context context, String url) {
        removePendingCleanup(context, KEY_WEBHOOK_PENDING_CLEANUP, url);
    }

    /** The ordered shadow key for a legacy pending-cleanup {@code StringSet} key. */
    private static String orderKeyFor(String key) {
        return key + "_order";
    }

    /**
     * Returns the pending cleanups in FIFO order (oldest first, see #368).
     *
     * <p>Always a fresh, mutable copy. Preferred source is the ordered shadow key written by
     * {@link #persistPendingCleanups}. When that key is missing (e.g. data written by an app
     * version predating #368) but the legacy {@code StringSet} key holds entries, the order is
     * reconstructed best-effort from the set's iteration order -- {@code SharedPreferences}
     * string sets carry no defined ordering, so this is only a starting point, not a guarantee
     * that it matches the original insertion order. It is immediately persisted in the new
     * ordered format so that later evictions are FIFO-correct going forward.
     */
    private static java.util.LinkedHashSet<String> getPendingCleanups(Context context, String key) {
        if (context == null) return new java.util.LinkedHashSet<>();
        SharedPreferences prefs = KeepADBPreferences.prefs(context);
        String orderKey = orderKeyFor(key);
        String joined = prefs.getString(orderKey, null);
        if (joined != null) {
            java.util.LinkedHashSet<String> ordered = new java.util.LinkedHashSet<>();
            if (!joined.isEmpty()) {
                for (String entry : joined.split(ORDER_SEPARATOR, -1)) {
                    if (!entry.isEmpty()) ordered.add(entry);
                }
            }
            return ordered;
        }
        java.util.Set<String> legacy = prefs.getStringSet(key, null);
        java.util.LinkedHashSet<String> migrated = legacy == null
                ? new java.util.LinkedHashSet<>() : new java.util.LinkedHashSet<>(legacy);
        if (!migrated.isEmpty()) {
            persistPendingCleanups(context, key, migrated);
        }
        return migrated;
    }

    /** Persists both the legacy {@code StringSet} key (compat) and the new ordered shadow key. */
    private static void persistPendingCleanups(Context context, String key, java.util.LinkedHashSet<String> pending) {
        if (context == null) return;
        SharedPreferences prefs = KeepADBPreferences.prefs(context);
        String orderKey = orderKeyFor(key);
        if (pending.isEmpty()) {
            prefs.edit().remove(key).remove(orderKey).apply();
            return;
        }
        prefs.edit()
                .putStringSet(key, pending)
                .putString(orderKey, String.join(ORDER_SEPARATOR, pending))
                .apply();
    }

    private static String addPendingCleanup(Context context, String key, String entry) {
        if (context == null) return null;
        java.util.LinkedHashSet<String> pending = getPendingCleanups(context, key);
        if (pending.contains(entry)) return null;
        String evicted = null;
        if (pending.size() >= MAX_PENDING_CLEANUPS) {
            // #368: evict the OLDEST entry (head of insertion order), not the newest, so the
            // newest -- most likely to still be a live orphan registration -- is kept.
            java.util.Iterator<String> oldest = pending.iterator();
            evicted = oldest.next();
            oldest.remove();
        }
        pending.add(entry);
        persistPendingCleanups(context, key, pending);
        return evicted;
    }

    private static void removePendingCleanup(Context context, String key, String entry) {
        if (context == null || entry == null) return;
        java.util.LinkedHashSet<String> pending = getPendingCleanups(context, key);
        if (!pending.remove(entry)) return;
        persistPendingCleanups(context, key, pending);
    }
}
