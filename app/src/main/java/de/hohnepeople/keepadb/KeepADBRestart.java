package de.hohnepeople.keepadb;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/**
 * #1027: Home's "Restart Wifi-ADB" when Wi-Fi is up but Android does not announce the port.
 * A restart makes adbd announce it again. It is deliberately a plain manual "off" followed by a
 * manual "on", through the same path as the main switch, so every usual rule applies: a running
 * timer or Force mode ends, the "on" is the user's own decision (with its exemption on an
 * untrusted Wi-Fi), and network protection, Keep-Alive and the shutdown rules see two ordinary
 * manual toggles. An earlier version reused the background recovery pulse; guarding that pulse
 * against every network, timer and exemption edge case did not converge (Codex reviews of #1029).
 *
 * <p>The "on" follows after {@link #PAUSE_MS} and is dropped when any newer explicit decision was
 * accepted meanwhile ({@link KeepADB#explicitIntentGeneration()}), so a tap elsewhere wins.
 */
final class KeepADBRestart {
    /** Time between the "off" and the "on", long enough for adbd to stop its listener. */
    static final long PAUSE_MS = 1_000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private KeepADBRestart() {}

    /** Switches Wifi-ADB off now and on again after the pause; returns the result of the off. */
    static KeepADB.ToggleResult restart(Context context) {
        Context app = context.getApplicationContext();
        KeepADBDiagnostics.event(app, "user_action", "app", "restart", "stage=off");
        KeepADB.ToggleResult off = KeepADB.setEnabled(app, false, "app");
        KeepADBService.sync(app);
        if (!off.isSuccess()) {
            return off;
        }
        final long generation = KeepADB.explicitIntentGeneration();
        MAIN.postDelayed(() -> {
            if (KeepADB.explicitIntentGeneration() != generation) {
                KeepADBDiagnostics.event(app, "user_action", "app", "restart",
                        "stage=on skipped reason=newer_decision");
                return;
            }
            KeepADB.ToggleResult on = KeepADB.setEnabled(app, true, "app");
            KeepADBDiagnostics.event(app, "user_action", "app", "restart", "stage=on result=" + on);
            KeepADBService.sync(app);
            KeepADBWidget.refreshAllState(app);
        }, PAUSE_MS);
        return off;
    }
}
