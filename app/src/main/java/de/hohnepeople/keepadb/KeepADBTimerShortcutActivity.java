package de.hohnepeople.keepadb;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.os.Bundle;
import android.widget.Toast;

/**
 * #1000: the "Wifi-ADB with timer" launcher shortcut. No UI: it starts the timer with the default
 * duration ({@link KeepADBTimer#start}), names the duration in a toast and finishes. Starting the
 * timer switches Wifi-ADB on, so it follows the toggle shortcut's lock rule (#954): on a device
 * locked behind a secure lock it waits for a successful unlock, and a cancelled or failed unlock
 * starts nothing. It takes nothing from the intent. Not exported, like the toggle shortcut.
 */
public class KeepADBTimerShortcutActivity extends Activity {

    private boolean handled;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(KeepADBLocaleHelper.wrapContext(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // A recreated instance must never start a second time.
        if (savedInstanceState != null) {
            handled = true;
            finish();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (handled) return;
        handled = true;
        if (KeepADBToggleGate.isDeviceLockedForEnable(this)) {
            KeepADBDiagnostics.event(this, "user_action", "shortcut", "unlock_required",
                    "timer device_locked");
            requestUnlock(this::startAndFinish, () -> {
                if (KeepADBToggleGate.isDeviceLockedForEnable(this)) {
                    KeepADBDiagnostics.event(this, "user_action", "shortcut", "skipped",
                            "timer unlock_failed");
                    finish();
                    return;
                }
                startAndFinish();
            });
            return;
        }
        startAndFinish();
    }

    private void startAndFinish() {
        KeepADBTimer.Duration duration = KeepADBPreferences.getTimerDefaultDuration(this);
        KeepADBDiagnostics.event(this, "user_action", "shortcut", "timer", "duration=" + duration.token);
        KeepADB.ToggleResult result = KeepADBTimer.start(this, duration, KeepADB.SOURCE_SHORTCUT);
        if (result == null) {
            Toast.makeText(this, getString(R.string.toggle_failed_toast), Toast.LENGTH_LONG).show();
        } else if (!result.isSuccess()) {
            KeepADBToggleGate.showToggleErrorToast(this, result);
        } else {
            Toast.makeText(this, getString(R.string.timer_started_toast,
                    KeepADBTimerText.duration(this, duration)), Toast.LENGTH_SHORT).show();
        }
        KeepADBTileService.requestRefresh(this);
        finish();
    }

    /** Seam around the real unlock request so tests can answer it. */
    void requestUnlock(Runnable onUnlocked, Runnable onNotUnlocked) {
        KeyguardManager keyguardManager = getSystemService(KeyguardManager.class);
        if (keyguardManager == null) {
            onNotUnlocked.run();
            return;
        }
        keyguardManager.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
            @Override
            public void onDismissSucceeded() {
                onUnlocked.run();
            }

            @Override
            public void onDismissCancelled() {
                onNotUnlocked.run();
            }

            @Override
            public void onDismissError() {
                onNotUnlocked.run();
            }
        });
    }
}
