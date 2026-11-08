package de.hohnepeople.keepadb;

import android.app.Application;

/**
 * The application object. It exists for two reasons of #904 and one of #911 and holds nothing else:
 * <ul>
 *   <li>It derives the three privacy switches once ({@link
 *       KeepADBPreferences#migratePrivacySplit}) before any component of this process writes a
 *       preference, which is the only moment "nothing stored yet" identifies a new installation.</li>
 *   <li>It ends the per-screen "Show" of the app privacy switch when the screen stops ({@link
 *       KeepADBPrivacy#LIFECYCLE}).</li>
 *   <li>It retries a webhook withdrawal that an opt-out left outstanding because the process died
 *       or the request failed ({@link KeepADBRegisterClient#resumeInterruptedWithdrawal}); a
 *       cheap preference read when nothing is outstanding.</li>
 * </ul>
 */
public class KeepADBApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        KeepADBPreferences.migratePrivacySplit(this);
        registerActivityLifecycleCallbacks(KeepADBPrivacy.LIFECYCLE);
        KeepADBRegisterClient.resumeInterruptedWithdrawal(this);
    }
}
