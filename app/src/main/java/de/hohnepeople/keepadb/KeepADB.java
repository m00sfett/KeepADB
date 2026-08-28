package de.hohnepeople.keepadb;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

/** Liest/schreibt Androids "Wireless debugging"-Schalter (Settings.Global.adb_wifi_enabled). */
final class KeepADB {
    private static final String TAG = "KeepADB";
    static final String KEY = "adb_wifi_enabled";

    // #114: a bare rapid off/on write can catch the system's AdbService mid-teardown of the
    // previous session. Debouncing actual writes by this cooldown gives adbd time to finish
    // tearing down before it sees the next transition.
    static final long TOGGLE_COOLDOWN_MS = 1500;
    static final long RECOVERY_PULSE_OFF_MS = 800;

    private static Handler toggleHandler;

    private static synchronized Handler toggleHandler() {
        if (toggleHandler == null) {
            toggleHandler = new Handler(Looper.getMainLooper());
        }
        return toggleHandler;
    }

    // Set right after a user-initiated disable, consumed once by KeepADBService's
    // keep-alive observer so it doesn't immediately re-enable a deliberate shutoff.
    private static volatile boolean userDisabled;

    // Independent, non-consumed counterpart to userDisabled: the on/off state of the last
    // explicit setEnabled() call. userDisabled is a one-shot token with exactly one intended
    // consumer (KeepADBService's content observer, deciding "stop recovering" vs. "recover").
    // #168 added a second, independent reader of that same field (KeepADBUsbHandover's "did the
    // user just turn this off?" guard) -- two independent consumers of a one-shot token is a
    // bug: whichever reads first "uses it up" for the other. Confirmed on real hardware: a
    // manual off -> content-observer's consumeUserDisabled() (unrelated Keep-Alive decision,
    // resets userDisabled as a side effect) -> a later genuine USB reconnect wrongly saw
    // isUserDisabled()==false and re-enabled WLAN-ADB despite the explicit manual off.
    // lastDesiredOn fixes this by never being consumed -- only ever overwritten by the next
    // setEnabled() call -- so a read here can't starve any other reader.
    //
    // performRecoveryPulse() deliberately does NOT update this field: it only ever runs when
    // userDisabled is false, i.e. the last explicit intent was already "on", so the pulse is a
    // same-state bounce (on -> brief off -> on) rather than a new intent and can never need to
    // flip this. The content observer's own recovery re-enable (source="content_observer") DOES
    // go through setEnabled(ctx, true, ...) like any other caller and is treated as a legitimate
    // "intent is now on" update: Keep-Alive's whole purpose is to restore the on-state, so an
    // automatic recovery re-enable is as much an intent as a manual tap.
    private static volatile boolean lastDesiredOn = true;
    private static volatile long lastAppliedChangeMs = 0;
    private static volatile long currentIntentToken = 0;
    private static Runnable pendingToggleRunnable;

    private KeepADB() {}

    static boolean isEnabled(Context ctx) {
        return Settings.Global.getInt(ctx.getContentResolver(), KEY, 0) == 1;
    }

    static boolean isUserDisabled() {
        return userDisabled;
    }

    /**
     * Non-consumed counterpart to {@link #isUserDisabled()}: reflects the on/off state of the
     * last explicit {@link #setEnabled} call and is never reset as a side effect of an unrelated
     * {@link #consumeUserDisabled()} read. See the {@code lastDesiredOn} field comment for why
     * two independent flags exist.
     */
    static boolean wasLastExplicitIntentOff() {
        return !lastDesiredOn;
    }

    private static boolean hasPermission(Context ctx) {
        return ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
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
            token = ++currentIntentToken;
            userDisabled = !on;
            lastDesiredOn = on;
            if (pendingToggleRunnable != null) {
                toggleHandler().removeCallbacks(pendingToggleRunnable);
                pendingToggleRunnable = null;
            }
            long sinceLastMs = SystemClock.elapsedRealtime() - lastAppliedChangeMs;
            if (sinceLastMs < TOGGLE_COOLDOWN_MS) {
                delayMs = TOGGLE_COOLDOWN_MS - sinceLastMs;
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
        if (token != currentIntentToken) {
            KeepADBDiagnostics.event(appContext, eventName, source, "cancelled",
                    "intentId=" + token + " reason=newer_intent");
            return false; // Superseded by a newer toggle intent
        }
        pendingToggleRunnable = null;
        try {
            boolean writeAccepted = Settings.Global.putInt(
                    appContext.getContentResolver(), KEY, on ? 1 : 0);
            userDisabled = !on;
            lastDesiredOn = on;
            lastAppliedChangeMs = SystemClock.elapsedRealtime();
            boolean actual = isEnabled(appContext);
            KeepADBDiagnostics.event(appContext, eventName, source,
                    writeAccepted && actual == on ? "success" : "state_mismatch",
                    "intentId=" + token + " desired=" + on + " actual=" + actual
                            + " writeAccepted=" + writeAccepted);
            KeepADBService.sync(appContext);
            KeepADBNotification.refresh(appContext);
            KeepADBWidget.refreshAll(appContext);
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
            if (userDisabled || !isEnabled(appContext)) {
                KeepADBDiagnostics.event(appContext, "recovery_attempt", "endpoint", "skipped",
                        "stage=request observed=" + observed + " reason=user_disabled_or_state_off");
                return;
            }
            pulseToken = ++currentIntentToken;
        }
        KeepADBDiagnostics.event(appContext, "recovery_attempt", "endpoint", "started",
                "intentId=" + pulseToken + " observed=" + observed);

        new Thread(() -> {
            try {
                boolean writeAccepted = Settings.Global.putInt(appContext.getContentResolver(), KEY, 0);
                lastAppliedChangeMs = SystemClock.elapsedRealtime();
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
                if (pulseToken != currentIntentToken || userDisabled) {
                    Log.i(TAG, "Recovery pulse cancelled by newer user intent");
                    KeepADBDiagnostics.event(appContext, "recovery_attempt", "endpoint", "cancelled",
                            "intentId=" + pulseToken + " reason=newer_user_intent");
                    return;
                }
            }

            try {
                boolean writeAccepted = Settings.Global.putInt(appContext.getContentResolver(), KEY, 1);
                lastAppliedChangeMs = SystemClock.elapsedRealtime();
                boolean actual = isEnabled(appContext);
                KeepADBDiagnostics.event(appContext, "recovery_attempt", "endpoint",
                        writeAccepted && actual ? "success" : "state_mismatch",
                        "intentId=" + pulseToken + " stage=enable actual=" + actual
                                + " writeAccepted=" + writeAccepted);
                KeepADBService.sync(appContext);
                KeepADBNotification.refresh(appContext);
                KeepADBWidget.refreshAll(appContext);
            } catch (SecurityException ignored) {
                KeepADBDiagnostics.event(appContext, "recovery_attempt", "endpoint", "failed",
                        "intentId=" + pulseToken + " stage=enable reason=security_exception");
            }
        }, "KeepADBRecoveryPulse").start();
    }

    /** Consumes and returns whether the last disable was user-initiated (vs. an external drop). */
    static synchronized boolean consumeUserDisabled() {
        boolean was = userDisabled;
        userDisabled = false;
        return was;
    }

    /** Reset state for unit tests. */
    static synchronized void resetForTesting() {
        if (pendingToggleRunnable != null && toggleHandler != null) {
            toggleHandler.removeCallbacks(pendingToggleRunnable);
            pendingToggleRunnable = null;
        }
        userDisabled = false;
        lastDesiredOn = true;
        lastAppliedChangeMs = 0;
        currentIntentToken = 0;
    }
}
