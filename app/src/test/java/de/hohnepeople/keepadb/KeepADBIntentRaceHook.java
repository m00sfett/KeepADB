package de.hohnepeople.keepadb;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Test helper (#817): runs an action exactly once at the moment {@link KeepADB#setEnabled} has
 * taken its intent token and persists the requested intent, i.e. inside the window in which a
 * newer intent or a network change makes the request lose before {@code applyNow()} runs. That is
 * the only way to reach {@code SUPERSEDED} and {@code GUARD_ABORTED} through a real caller.
 *
 * <p>It listens for the first change of the persisted last-desired-on intent (forgotten when the
 * hook is armed, so any write is a change), which {@code setEnabled} writes right after taking its
 * token. The listener runs synchronously on the
 * writing (main) thread, inside the toggle lock, so a nested call re-enters it. The helper keeps
 * the listener strongly referenced; {@link #close()} unregisters it.
 */
final class KeepADBIntentRaceHook implements AutoCloseable {
    private final SharedPreferences prefs;
    private final SharedPreferences.OnSharedPreferenceChangeListener listener;
    private boolean fired;

    private KeepADBIntentRaceHook(Context context, Runnable action) {
        prefs = context.getSharedPreferences("keepadb_prefs", Context.MODE_PRIVATE);
        listener = (sharedPreferences, key) -> {
            if (fired || !"last_desired_on".equals(key)) return;
            fired = true;
            action.run();
        };
        // Forget the stored intent so that the request under test counts as a change of it.
        prefs.edit().remove("last_desired_on").commit();
        prefs.registerOnSharedPreferenceChangeListener(listener);
    }

    /** Arms the hook; the next change of the persisted intent runs {@code action} once. */
    static KeepADBIntentRaceHook arm(Context context, Runnable action) {
        return new KeepADBIntentRaceHook(context, action);
    }

    /** Whether the toggle request under test actually passed through the armed window. */
    boolean fired() {
        return fired;
    }

    @Override
    public void close() {
        prefs.unregisterOnSharedPreferenceChangeListener(listener);
    }
}
