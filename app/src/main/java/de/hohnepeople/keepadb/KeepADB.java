package de.hohnepeople.keepadb;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

/**
 * Reads/writes Android's "Wireless debugging" toggle (Settings.Global.adb_wifi_enabled) and
 * coordinates the surrounding orchestration: debounced writes, intent-token cancellation of
 * stale writes, recovery pulses, and refreshing the notification/widget/service surfaces once a
 * change actually lands.
 *
 * Per issue #248, this class is now the coordinator: the platform-independent decision core
 * (user-intent tracking, intent tokens, debounce timing) lives in {@link KeepADBToggleState} and
 * is unit-testable without any Android framework dependency, Settings.Global access is isolated
 * in {@link AdbWifiSettingsGateway}, and persisted preferences live in {@link KeepADBPreferences}.
 * This class wires those together and remains the only place here that still needs Context,
 * Handler, and Settings.Global.
 */
final class KeepADB {
    private static final String TAG = "KeepADB";
    static final String KEY = "adb_wifi_enabled";

    static final long TOGGLE_COOLDOWN_MS = KeepADBToggleState.TOGGLE_COOLDOWN_MS;
    static final long RECOVERY_PULSE_OFF_MS = KeepADBToggleState.RECOVERY_PULSE_OFF_MS;

    private static Handler toggleHandler;

    private static synchronized Handler toggleHandler() {
        if (toggleHandler == null) {
            toggleHandler = new Handler(Looper.getMainLooper());
        }
        return toggleHandler;
    }

    // Platform-independent toggle/recovery decision core (issue #248). Static because KeepADB is
    // a package-wide static facade invoked from many independent Android entry points (receivers,
    // service, tile, widget, activity) that share no object graph or DI container -- the state
    // must outlive any single caller and stay reachable from all of them. Its lifecycle mirrors
    // toggleHandler above: created once per process, only ever reset via resetForTesting().
    private static final KeepADBToggleState toggleState = new KeepADBToggleState();

    private static Runnable pendingToggleRunnable;

    private KeepADB() {}

    static boolean isEnabled(Context ctx) {
        return AdbWifiSettingsGateway.isEnabled(ctx, KEY);
    }

    static boolean isUserDisabled() {
        return toggleState.isUserDisabled();
    }

    /**
     * Non-consumed counterpart to {@link #isUserDisabled()}: reflects the on/off state of the
     * last explicit {@link #setEnabled} call and is never reset as a side effect of an unrelated
     * {@link #consumeUserDisabled()} read. See {@link KeepADBToggleState}'s field comment for why
     * two independent flags exist.
     */
    static boolean wasLastExplicitIntentOff() {
        return toggleState.wasLastExplicitIntentOff();
    }

    static boolean wasLastExplicitIntentOff(Context ctx) {
        if (ctx == null) return wasLastExplicitIntentOff();
        boolean prefOn = KeepADBPreferences.getLastDesiredOn(ctx.getApplicationContext());
        if (!prefOn) {
            toggleState.forceLastDesiredOn(false);
            return true;
        }
        return wasLastExplicitIntentOff();
    }

    enum State {
        PERMISSION_MISSING,
        OFF,
        ENABLED_DISCONNECTED,
        ENABLED_CONNECTED
    }

    static boolean hasPermission(Context ctx) {
        if (ctx == null) return false;
        return ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    static State getState(Context ctx) {
        if (ctx == null) return State.OFF;
        Context appContext = ctx.getApplicationContext();
        if (!hasPermission(appContext)) {
            return State.PERMISSION_MISSING;
        }
        boolean enabled = isEnabled(appContext);
        if (!enabled) {
            if (KeepADBPreferences.isKeepAliveEnabled(appContext) && !wasLastExplicitIntentOff(appContext)) {
                return State.ENABLED_DISCONNECTED;
            }
            return State.OFF;
        }
        if (!KeepADBService.isWifiConnected(appContext)) {
            return State.ENABLED_DISCONNECTED;
        }
        if (KeepADBNotification.hasCurrentEndpoint()) {
            return State.ENABLED_CONNECTED;
        }
        return State.ENABLED_DISCONNECTED;
    }

    /**
     * Schaltet Wireless Debugging um.
     * Bei schnellen wiederholten Aufrufen innerhalb TOGGLE_COOLDOWN_MS wird die letzte
     * gewünschte Absicht debounced eingeplant.
     */
    static boolean setEnabled(Context ctx, boolean on) {
        return setEnabled(ctx, on, "app");
    }

    static boolean setEnabled(Context ctx, boolean on, String source) {
        Context appContext = ctx.getApplicationContext();
        boolean observed = isEnabled(appContext);
        String eventName = diagnosticEventName(source);
        if (!hasPermission(appContext)) {
            KeepADBDiagnostics.event(appContext, eventName, source, "failed",
                    "desired=" + on + " observed=" + observed + " reason=permission_missing");
            return false;
        }

        long token;
        long delayMs = 0;
        synchronized (KeepADB.class) {
            KeepADBToggleState.ToggleDecision decision =
                    toggleState.requestToggle(on, SystemClock.elapsedRealtime());
            token = decision.token;
            KeepADBPreferences.setLastDesiredOn(appContext, on);
            if (pendingToggleRunnable != null) {
                toggleHandler().removeCallbacks(pendingToggleRunnable);
                pendingToggleRunnable = null;
            }
            if (!decision.isImmediate()) {
                delayMs = decision.delayMs;
                final long scheduledToken = token;
                pendingToggleRunnable = () -> applyNow(appContext, on, source, scheduledToken);
                toggleHandler().postDelayed(pendingToggleRunnable, delayMs);
            }
        }
        KeepADBDiagnostics.event(appContext, eventName, source,
                delayMs > 0 ? "scheduled" : "accepted",
                "intentId=" + token + " desired=" + on + " observed=" + observed
                        + (delayMs > 0 ? " delayMs=" + delayMs : ""));
        if (delayMs > 0) return true;
        return applyNow(appContext, on, source, token);
    }

    private static synchronized boolean applyNow(Context appContext, boolean on, String source, long token) {
        String eventName = diagnosticEventName(source);
        if (!toggleState.isCurrentIntent(token)) {
            KeepADBDiagnostics.event(appContext, eventName, source, "cancelled",
                    "intentId=" + token + " reason=newer_intent");
            return false; // Superseded by a newer toggle intent
        }
        pendingToggleRunnable = null;
        try {
            boolean writeAccepted = AdbWifiSettingsGateway.writeEnabled(appContext, KEY, on);
            toggleState.recordApplied(on, SystemClock.elapsedRealtime());
            KeepADBPreferences.setLastDesiredOn(appContext, on);
            boolean actual = isEnabled(appContext);
            KeepADBDiagnostics.event(appContext, eventName, source,
                    writeAccepted && actual == on ? "success" : "state_mismatch",
                    "intentId=" + token + " desired=" + on + " actual=" + actual
                            + " writeAccepted=" + writeAccepted);
            refreshSurfaces(appContext);
            return true;
        } catch (SecurityException e) {
            Log.e(TAG, "Missing WRITE_SECURE_SETTINGS when applying toggle", e);
            KeepADBDiagnostics.event(appContext, eventName, source, "failed",
                    "intentId=" + token + " reason=security_exception");
            return false;
        }
    }

    private static String diagnosticEventName(String source) {
        return "content_observer".equals(source) || "keep_alive_check".equals(source)
                ? "recovery_attempt" : "toggle_attempt";
    }

    /**
     * Führt einen Recovery-Puls (AUS -> PAUSE -> AN) kontrolliert aus.
     * Bricht ab, wenn der Nutzer während des Pulses manuell ausgeschaltet hat.
     */
    static void performRecoveryPulse(Context ctx) {
        Context appContext = ctx.getApplicationContext();
        boolean observed = isEnabled(appContext);
        if (!hasPermission(appContext)) {
            KeepADBDiagnostics.event(appContext, "recovery_attempt", "endpoint", "failed",
                    "stage=request observed=" + observed + " reason=permission_missing");
            return;
        }

        final long pulseToken;
        synchronized (KeepADB.class) {
            if (toggleState.isUserDisabled() || wasLastExplicitIntentOff(appContext) || !isEnabled(appContext)) {
                KeepADBDiagnostics.event(appContext, "recovery_attempt", "endpoint", "skipped",
                        "stage=request observed=" + observed + " reason=user_disabled_or_state_off");
                return;
            }
            pulseToken = toggleState.beginPulse();
        }
        KeepADBDiagnostics.event(appContext, "recovery_attempt", "endpoint", "started",
                "intentId=" + pulseToken + " observed=" + observed);

        new Thread(() -> {
            try {
                boolean writeAccepted = AdbWifiSettingsGateway.writeEnabled(appContext, KEY, false);
                toggleState.recordAppliedTime(SystemClock.elapsedRealtime());
                boolean actual = isEnabled(appContext);
                KeepADBDiagnostics.event(appContext, "recovery_state", "endpoint",
                        writeAccepted && !actual ? "success" : "state_mismatch",
                        "intentId=" + pulseToken + " stage=disable actual=" + actual
                                + " writeAccepted=" + writeAccepted);
            } catch (SecurityException e) {
                KeepADBDiagnostics.event(appContext, "recovery_attempt", "endpoint", "failed",
                        "intentId=" + pulseToken + " stage=disable reason=security_exception");
                return;
            }

            try {
                Thread.sleep(RECOVERY_PULSE_OFF_MS);
            } catch (InterruptedException ignored) {
            }

            synchronized (KeepADB.class) {
                if (!toggleState.isCurrentIntent(pulseToken) || toggleState.isUserDisabled()
                        || wasLastExplicitIntentOff(appContext)) {
                    Log.i(TAG, "Recovery pulse cancelled by newer user intent");
                    KeepADBDiagnostics.event(appContext, "recovery_attempt", "endpoint", "cancelled",
                            "intentId=" + pulseToken + " reason=newer_user_intent");
                    return;
                }
            }

            try {
                boolean writeAccepted = AdbWifiSettingsGateway.writeEnabled(appContext, KEY, true);
                toggleState.recordAppliedTime(SystemClock.elapsedRealtime());
                boolean actual = isEnabled(appContext);
                KeepADBDiagnostics.event(appContext, "recovery_attempt", "endpoint",
                        writeAccepted && actual ? "success" : "state_mismatch",
                        "intentId=" + pulseToken + " stage=enable actual=" + actual
                                + " writeAccepted=" + writeAccepted);
                refreshSurfaces(appContext);
            } catch (SecurityException ignored) {
                KeepADBDiagnostics.event(appContext, "recovery_attempt", "endpoint", "failed",
                        "intentId=" + pulseToken + " stage=enable reason=security_exception");
            }
        }, "KeepADBRecoveryPulse").start();
    }

    /**
     * Refreshes every surface that mirrors the toggle state after a write actually lands. Kept as
     * one explicit step, deliberately separate from the write itself: an output effect, not a
     * state-model responsibility (issue #248).
     */
    private static void refreshSurfaces(Context appContext) {
        KeepADBService.sync(appContext);
        KeepADBNotification.refresh(appContext);
        KeepADBWidget.refreshAll(appContext);
    }

    /** Consumes and returns whether the last disable was user-initiated (vs. an external drop). */
    static synchronized boolean consumeUserDisabled() {
        return toggleState.consumeUserDisabled();
    }

    /** Reset state for unit tests. */
    static synchronized void resetForTesting() {
        if (pendingToggleRunnable != null && toggleHandler != null) {
            toggleHandler.removeCallbacks(pendingToggleRunnable);
            pendingToggleRunnable = null;
        }
        toggleState.reset();
    }

    static synchronized void resetForTesting(Context ctx) {
        resetForTesting();
        if (ctx != null) {
            KeepADBPreferences.setLastDesiredOn(ctx.getApplicationContext(), true);
        }
    }
}
