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
        Context appContext = ctx.getApplicationContext();
        if (!hasPermission(appContext)) return false;

        long token;
        synchronized (KeepADB.class) {
            token = ++currentIntentToken;
            userDisabled = !on;
            if (pendingToggleRunnable != null) {
                toggleHandler().removeCallbacks(pendingToggleRunnable);
                pendingToggleRunnable = null;
            }
            long sinceLastMs = SystemClock.elapsedRealtime() - lastAppliedChangeMs;
            if (sinceLastMs < TOGGLE_COOLDOWN_MS) {
                long delayMs = TOGGLE_COOLDOWN_MS - sinceLastMs;
                final long scheduledToken = token;
                pendingToggleRunnable = () -> applyNow(appContext, on, scheduledToken);
                toggleHandler().postDelayed(pendingToggleRunnable, delayMs);
                return true;
            }
        }
        return applyNow(appContext, on, token);
    }

    private static synchronized boolean applyNow(Context appContext, boolean on, long token) {
        if (token != currentIntentToken) {
            return false; // Superseded by a newer toggle intent
        }
        pendingToggleRunnable = null;
        try {
            Settings.Global.putInt(appContext.getContentResolver(), KEY, on ? 1 : 0);
            userDisabled = !on;
            lastAppliedChangeMs = SystemClock.elapsedRealtime();
            KeepADBNotification.refresh(appContext);
            KeepADBWidget.refreshAll(appContext);
            return true;
        } catch (SecurityException e) {
            Log.e(TAG, "Missing WRITE_SECURE_SETTINGS when applying toggle", e);
            return false;
        }
    }

    /**
     * Führt einen Recovery-Puls (AUS -> PAUSE -> AN) kontrolliert aus.
     * Bricht ab, wenn der Nutzer während des Pulses manuell ausgeschaltet hat.
     */
    static void performRecoveryPulse(Context ctx) {
        Context appContext = ctx.getApplicationContext();
        if (!hasPermission(appContext)) return;

        final long pulseToken;
        synchronized (KeepADB.class) {
            if (userDisabled || !isEnabled(appContext)) return;
            pulseToken = ++currentIntentToken;
        }

        new Thread(() -> {
            try {
                Settings.Global.putInt(appContext.getContentResolver(), KEY, 0);
                lastAppliedChangeMs = SystemClock.elapsedRealtime();
            } catch (SecurityException e) {
                return;
            }

            try {
                Thread.sleep(RECOVERY_PULSE_OFF_MS);
            } catch (InterruptedException ignored) {
            }

            synchronized (KeepADB.class) {
                if (pulseToken != currentIntentToken || userDisabled) {
                    Log.i(TAG, "Recovery pulse cancelled by newer user intent");
                    return;
                }
            }

            try {
                Settings.Global.putInt(appContext.getContentResolver(), KEY, 1);
                lastAppliedChangeMs = SystemClock.elapsedRealtime();
                KeepADBNotification.refresh(appContext);
                KeepADBWidget.refreshAll(appContext);
            } catch (SecurityException ignored) {
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
        lastAppliedChangeMs = 0;
        currentIntentToken = 0;
    }
}
