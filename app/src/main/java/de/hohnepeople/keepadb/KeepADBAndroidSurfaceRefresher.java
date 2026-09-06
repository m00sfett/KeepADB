package de.hohnepeople.keepadb;

import android.content.Context;

/**
 * Production {@link KeepADBSurfaceRefresher}: the exact three calls {@link KeepADB} used to make
 * inline after a successful write, in the same order (service sync, then notification, then
 * widgets).
 */
final class KeepADBAndroidSurfaceRefresher implements KeepADBSurfaceRefresher {
    @Override
    public void refreshAll(Context appContext) {
        KeepADBService.sync(appContext);
        KeepADBNotification.refresh(appContext);
        KeepADBWidget.refreshAll(appContext);
    }
}
