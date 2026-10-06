package de.hohnepeople.keepadb;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Remnant of the former observation history of which BSSIDs had been seen under which SSID (#266,
 * #461, #721). Since #769 nothing writes that history, since #778 what was left of it is discarded
 * once on update, and since #788 nothing reads it either: the mesh offer, the name fallback of the
 * network list and the stored bands are gone. What stays is the one-time discard, which a device
 * that updates from an older version still needs.
 */
final class KeepADBBssidHistory {
    private static final String PREFS_NAME = "keepadb_prefs";
    private static final String PREFIX = "bssid_history_";

    /**
     * Bookkeeping of the one-time discard (#778). Deliberately not under {@link #PREFIX}, so no
     * history clearing can remove it.
     */
    static final String KEY_LEGACY_DISCARDED = "observation_history_discarded";

    private KeepADBBssidHistory() {}

    /**
     * Discards the old observation history once (#778). Since #769 nothing writes it any more, and
     * the user decided that what is left is dropped on update: every {@code bssid_history_*} key,
     * i.e. the SSID ids, the BSSIDs and the stored bands. Nothing else in {@code keepadb_prefs} is
     * touched. Runs once: the marker is written in the same commit, and a later call returns at
     * once. A fresh installation has nothing to delete and only gets the marker, which the
     * existing-install check ignores ({@link KeepADBOnboarding#isExistingInstall}).
     *
     * <p>Called from the update/boot receiver and the home screen.
     *
     * @return true if this call performed the discard.
     */
    static boolean discardLegacyOnce(Context context) {
        if (context == null) return false;
        SharedPreferences preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (preferences.contains(KEY_LEGACY_DISCARDED)) return false;
        SharedPreferences.Editor editor = preferences.edit();
        for (String key : preferences.getAll().keySet()) {
            if (key != null && key.startsWith(PREFIX)) editor.remove(key);
        }
        return editor.putBoolean(KEY_LEGACY_DISCARDED, true).commit();
    }
}
