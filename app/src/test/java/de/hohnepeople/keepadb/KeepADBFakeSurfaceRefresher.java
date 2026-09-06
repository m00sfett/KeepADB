package de.hohnepeople.keepadb;

import android.content.Context;

/**
 * Recording {@link KeepADBSurfaceRefresher} fake (#248): counts the surface fan-outs a toggle
 * triggers without any of them reaching the framework, so a test can assert that a successful
 * write refreshes the surfaces and a superseded or failed one does not.
 */
final class KeepADBFakeSurfaceRefresher implements KeepADBSurfaceRefresher {
    int refreshCount;

    @Override
    public void refreshAll(Context appContext) {
        refreshCount++;
    }
}
