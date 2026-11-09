package de.hohnepeople.keepadb;

import android.app.Application;
import android.content.res.Configuration;

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
 *   <li>It redraws the placed widgets when the language or the font scale of this running
 *       process changes ({@link #onConfigurationChanged}): the in-app language on Android 13+
 *       arrives as a configuration change, and the layout of the widget follows the font scale.
 *       A system language change while the process is not running is handled by the widget's
 *       {@code LOCALE_CHANGED} receiver.</li>
 * </ul>
 */
public class KeepADBApplication extends Application {
    private String lastLocales = "";
    private float lastFontScale = 1f;

    @Override
    public void onCreate() {
        super.onCreate();
        remember(getResources().getConfiguration());
        KeepADBPreferences.migratePrivacySplit(this);
        registerActivityLifecycleCallbacks(KeepADBPrivacy.LIFECYCLE);
        KeepADBWidgetRefreshJob.scheduleIfWidgetPlaced(this);
    }

    private void remember(Configuration config) {
        lastLocales = config.getLocales().toLanguageTags();
        lastFontScale = config.fontScale;
    }

    /** Language or font scale changed under a running process: redraw the widgets (draw only). */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        boolean changed = !lastLocales.equals(newConfig.getLocales().toLanguageTags())
                || lastFontScale != newConfig.fontScale;
        remember(newConfig);
        if (changed) KeepADBWidget.refreshAllState(this);
    }
}
