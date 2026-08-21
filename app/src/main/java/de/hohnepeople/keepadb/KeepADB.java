package de.hohnepeople.keepadb;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;

/** Liest/schreibt Androids "Wireless debugging"-Schalter (Settings.Global.adb_wifi_enabled). */
final class KeepADB {
    static final String KEY = "adb_wifi_enabled";

    // #114: a bare rapid off/on write can catch the system's AdbService mid-teardown of the
    // previous session, which then accepts adb_wifi_enabled=1 without adbd ever binding a
    // listener. Debouncing every actual write by this cooldown gives adbd time to finish
    // tearing down before it sees the next transition, instead of only recovering after the
    // fact (see KeepADBEndpoint's fast-probe recovery pulse, which stays as a fallback for
    // toggles that don't go through this class, e.g. an external app or `adb shell`).
    private static final long TOGGLE_COOLDOWN_MS = 1500;
    // Lazily created: a static Handler field would eagerly touch Looper.getMainLooper() during
    // class init, which crashes in a plain JVM unit test that never calls setEnabled().
    private static Handler toggleHandler;

    private static synchronized Handler toggleHandler() {
        if (toggleHandler == null) {
            toggleHandler = new Handler(Looper.getMainLooper());
        }
        return toggleHandler;
    }

    // Set right after a successful user-initiated disable, consumed once by KeepADBService's
    // keep-alive observer so it doesn't immediately re-enable a deliberate shutoff.
    private static volatile boolean userDisabled;
    private static volatile long lastAppliedChangeMs = Long.MIN_VALUE;
    private static Runnable pendingToggleRunnable;

    private KeepADB() {}

    static boolean isEnabled(Context ctx) {
        return Settings.Global.getInt(ctx.getContentResolver(), KEY, 0) == 1;
    }

    private static boolean hasPermission(Context ctx) {
        return ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * @return true bei Erfolg (die Änderung wurde geschrieben oder zur debounced Anwendung
     * eingeplant), false wenn WRITE_SECURE_SETTINGS fehlt.
     */
    static boolean setEnabled(Context ctx, boolean on) {
        Context appContext = ctx.getApplicationContext();
        synchronized (KeepADB.class) {
            if (pendingToggleRunnable != null) {
                toggleHandler().removeCallbacks(pendingToggleRunnable);
                pendingToggleRunnable = null;
            }
            long sinceLastMs = SystemClock.elapsedRealtime() - lastAppliedChangeMs;
            if (sinceLastMs < TOGGLE_COOLDOWN_MS) {
                if (!hasPermission(appContext)) return false;
                long delayMs = TOGGLE_COOLDOWN_MS - sinceLastMs;
                pendingToggleRunnable = () -> applyNow(appContext, on);
                toggleHandler().postDelayed(pendingToggleRunnable, delayMs);
                return true;
            }
        }
        return applyNow(appContext, on);
    }

    private static synchronized boolean applyNow(Context appContext, boolean on) {
        pendingToggleRunnable = null;
        try {
            Settings.Global.putInt(appContext.getContentResolver(), KEY, on ? 1 : 0);
            userDisabled = !on;
            lastAppliedChangeMs = SystemClock.elapsedRealtime();
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    /** Consumes and returns whether the last disable was user-initiated (vs. an external drop). */
    static boolean consumeUserDisabled() {
        boolean was = userDisabled;
        userDisabled = false;
        return was;
    }
}
