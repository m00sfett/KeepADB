package de.hohnepeople.keepadb;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.os.Bundle;

/**
 * #954: the "Toggle Wifi-ADB" launcher shortcut. It has no UI: it switches Wireless Debugging the
 * way the Quick Settings tile does and finishes, without a confirmation (maintainer decision of
 * 2026-11-15, the same as the tile and the widget).
 *
 * <p>It takes nothing from the intent. The target state comes from the shared click semantics
 * ({@link KeepADB#desiredOnForClick}), and it enforces what the tile enforces:
 * <ul>
 *   <li>an enable on a device that is locked behind a secure lock waits for a successful unlock
 *       ({@link KeyguardManager#requestDismissKeyguard}, the activity counterpart of the tile's
 *       {@code unlockAndRun}); a cancelled or failed unlock toggles nothing, and a disable is
 *       immediate, as on the tile (#586);</li>
 *   <li>after the unlock the state is derived again and the continuation only ever enables
 *       ({@link KeepADBToggleGate#enableStillWanted});</li>
 *   <li>a missing {@code WRITE_SECURE_SETTINGS} or a rejected write is reported with the tile's
 *       toast ({@link KeepADBToggleGate#showToggleErrorToast}).</li>
 * </ul>
 * Not exported: the launcher starts a shortcut with the identity of the app that publishes it.
 */
public class KeepADBToggleShortcutActivity extends Activity {

    private boolean handled;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(KeepADBLocaleHelper.wrapContext(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // A recreated instance (process death, configuration change while the unlock prompt was
        // up) must never toggle a second time.
        if (savedInstanceState != null) {
            handled = true;
            finish();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The keyguard request needs a visible activity, so everything starts here, once.
        if (handled) return;
        handled = true;
        toggle();
    }

    private void toggle() {
        boolean want = KeepADB.desiredOnForToggle(KeepADB.getState(this));
        if (want && KeepADBToggleGate.isDeviceLockedForEnable(this)) {
            KeepADBDiagnostics.event(this, "user_action", "shortcut", "unlock_required",
                    "device_locked");
            requestUnlock(this::enableAfterUnlock, this::unlockNotCompleted);
            return;
        }
        KeepADBDiagnostics.event(this, "user_action", "shortcut", want ? "enable" : "disable",
                "tap");
        applyToggle(want);
        finish();
    }

    /** Runs only after the system reported a successful unlock; it never disables. */
    private void enableAfterUnlock() {
        if (KeepADBToggleGate.enableStillWanted(this, "shortcut")) {
            KeepADBDiagnostics.event(this, "user_action", "shortcut", "enable", "after_unlock");
            applyToggle(true);
        }
        finish();
    }

    /**
     * The unlock was cancelled or the system could not ask for it. Nothing is toggled, unless the
     * device turns out not to be locked behind a credential any more (the keyguard was already
     * gone), which is the case the tile's {@code unlockAndRun} treats as unlocked too.
     */
    private void unlockNotCompleted() {
        if (KeepADBToggleGate.isDeviceLockedForEnable(this)) {
            KeepADBDiagnostics.event(this, "user_action", "shortcut", "skipped", "unlock_failed");
            finish();
            return;
        }
        enableAfterUnlock();
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

    private void applyToggle(boolean want) {
        KeepADB.ToggleResult result = KeepADB.setEnabled(this, want, KeepADB.SOURCE_SHORTCUT);
        if (!result.isSuccess()) {
            KeepADBToggleGate.showToggleErrorToast(this, result);
        }
        KeepADBService.sync(this);
        KeepADBWidget.refreshAll(this);
        KeepADBEndpointCoordinator.refresh(this);
        KeepADBTileService.requestRefresh(this);
    }
}
