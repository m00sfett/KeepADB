package de.hohnepeople.keepadb;

import android.app.Application;
import android.content.res.Configuration;

/**
 * The application object. It exists for these reasons and holds nothing else:
 * <ul>
 *   <li>It stores the default of the open-network rule once ({@link
 *       KeepADBPreferences#migrateOpenNetworkShutdown}, #997): on for a new installation, off for
 *       an existing one.</li>
 *   <li>It derives the three privacy switches once ({@link
 *       KeepADBPreferences#migratePrivacySplit}) before any component of this process writes a
 *       preference, which is the only moment "nothing stored yet" identifies a new installation.</li>
 *   <li>It deletes what the removed USB-ADB area left behind (#956: stored settings and host
 *       profiles, the notification channel and a notification still showing), directly after
 *       that. Every component of the app (screens, boot receiver, service, tile, widgets) runs in
 *       this process, so whichever one is the first after an update, this runs before it. The
 *       work is idempotent and writes nothing once the old data is gone. The same goes for the
 *       two retry settings of the webhook (#960), whose selectors are gone, and for the dismiss
 *       flag of the advice card that left the home screen (#958).</li>
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
        // #997 before the privacy split: both need "nothing stored yet" to see a new installation.
        KeepADBPreferencesMigrations.migrateOpenNetworkShutdown(this);
        KeepADBPreferencesMigrations.migratePrivacySplit(this);
        KeepADBPreferencesMigrations.removeObsoleteUsbKeys(this);
        KeepADBPreferencesMigrations.removeObsoleteWebhookRetryKeys(this);
        KeepADBPreferencesMigrations.removeObsoleteAdviceBannerKey(this);
        KeepADBNotification.removeObsoleteUsbNotification(this);
        // #1000: the timer posts its end notice through this sink (a seam for tests).
        KeepADBTimer.setNoticeSink(KeepADBTimerNotice.SINK);
        // #1000 (Codex review): a timer's switch-off that a process death interrupted.
        KeepADBTimer.settleShutdown(this);
        // #1027: a Home restart whose "on" a process death interrupted.
        KeepADBRestart.resumeAfterProcessStart(this);
        KeepADBSelfPairCleanup.setObserver(KeepADBSelfPairService::onBackgroundCleanup);
        KeepADBSelfPairCleanup.reconcileAtStart(this);
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
