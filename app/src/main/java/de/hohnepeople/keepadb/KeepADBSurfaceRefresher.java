package de.hohnepeople.keepadb;

import android.content.Context;

/**
 * Boundary around the output effects a completed toggle fans out to (#248): the foreground
 * service sync plus the notification and widget refreshes. Isolating them here means
 * {@link KeepADB} orchestrates one named effect instead of reaching into three Android-facing
 * singletons inline, and a test can substitute a recording fake to assert *that* the surfaces
 * were refreshed without any of them actually touching the framework.
 */
interface KeepADBSurfaceRefresher {
    /** Pushes the freshly applied toggle state out to every KeepADB surface. */
    void refreshAll(Context appContext);
}
