package de.hohnepeople.keepadb;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

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
 *
 * <p>The owed "on" is also stored ({@link KeepADBPreferences#getRestartOnPendingAt}, Codex review
 * of #1029): if the process dies during the pause, the next process start finishes the restart
 * ({@link #resumeAfterProcessStart}). Every manual decision clears the record, in any process
 * ({@link #onManualDecision}, called for every manual toggle), so a newer decision always wins. A
 * record older than {@link #RESUME_WINDOW_MS}, or from before a reboot, is dropped and Wifi-ADB
 * stays off: a restart that late would surprise the user.
 */
final class KeepADBRestart {
    /** Time between the "off" and the "on", long enough for adbd to stop its listener. */
    static final long PAUSE_MS = 1_000L;
    /** How long after the "off" a new process still finishes an interrupted restart. */
    static final long RESUME_WINDOW_MS = 60_000L;
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
        // After the off: its own manual-decision hook must not clear the record it precedes.
        long at = SystemClock.elapsedRealtime();
        KeepADBPreferences.setRestartOnPendingAt(app, at);
        final long generation = KeepADB.explicitIntentGeneration();
        MAIN.postDelayed(() -> {
            if (KeepADB.explicitIntentGeneration() != generation
                    || KeepADBPreferences.getRestartOnPendingAt(app) != at) {
                KeepADBDiagnostics.event(app, "user_action", "app", "restart",
                        "stage=on skipped reason=newer_decision");
                return;
            }
            switchOn(app, "in_process");
        }, PAUSE_MS);
        return off;
    }

    /** Called for every manual toggle: a newer decision voids an owed restart "on". */
    static void onManualDecision(Context appContext) {
        if (KeepADBPreferences.getRestartOnPendingAt(appContext) >= 0) {
            KeepADBPreferences.clearRestartOnPending(appContext);
        }
    }

    /** Finishes a restart a process death interrupted; called once per process start. */
    static void resumeAfterProcessStart(Context context) {
        Context app = context.getApplicationContext();
        long at = KeepADBPreferences.getRestartOnPendingAt(app);
        if (at < 0) return;
        long now = SystemClock.elapsedRealtime();
        if (now < at || now - at > RESUME_WINDOW_MS) {
            KeepADBPreferences.clearRestartOnPending(app);
            KeepADBDiagnostics.event(app, "user_action", "app", "restart",
                    "stage=on skipped reason=" + (now < at ? "rebooted" : "expired"));
            return;
        }
        long delay = Math.max(0L, at + PAUSE_MS - now);
        MAIN.postDelayed(() -> {
            if (KeepADBPreferences.getRestartOnPendingAt(app) != at) return; // decided meanwhile
            switchOn(app, "resumed");
        }, delay);
    }

    private static void switchOn(Context app, String how) {
        // Cleared first: the "on" below is itself a manual decision and must not be repeated.
        KeepADBPreferences.clearRestartOnPending(app);
        KeepADB.ToggleResult on = KeepADB.setEnabled(app, true, "app");
        KeepADBDiagnostics.event(app, "user_action", "app", "restart",
                "stage=on " + how + " result=" + on);
        KeepADBService.sync(app);
        KeepADBWidget.refreshAllState(app);
    }
}
