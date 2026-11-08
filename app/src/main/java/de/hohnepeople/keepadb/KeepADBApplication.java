package de.hohnepeople.keepadb;

import android.app.Application;

/**
 * The application object. It exists for three reasons and holds nothing else:
 * <ul>
 *   <li>It derives the three privacy switches once ({@link
 *       KeepADBPreferences#migratePrivacySplit}) before any component of this process writes a
 *       preference, which is the only moment "nothing stored yet" identifies a new installation.</li>
 *   <li>It ends the per-screen "Show" of the app privacy switch when the screen stops ({@link
 *       KeepADBPrivacy#LIFECYCLE}).</li>
 *   <li>It re-arms the widgets' watch of the Wireless Debugging setting at every process start
 *       (#912), because a force-stop deletes the scheduled job: {@link
 *       KeepADBWidgetRefreshJob#scheduleIfWidgetPlaced}.</li>
 * </ul>
 */
public class KeepADBApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        KeepADBPreferences.migratePrivacySplit(this);
        registerActivityLifecycleCallbacks(KeepADBPrivacy.LIFECYCLE);
        KeepADBWidgetRefreshJob.scheduleIfWidgetPlaced(this);
    }
}
