package de.hohnepeople.keepadb;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/** Production {@link KeepADBScheduler} backed by a main-looper Handler and a real thread. */
final class KeepADBAndroidScheduler implements KeepADBScheduler {
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void postDelayed(Runnable runnable, long delayMs) {
        handler.postDelayed(runnable, delayMs);
    }

    @Override
    public void removeCallbacks(Runnable runnable) {
        handler.removeCallbacks(runnable);
    }

    @Override
    public void runAsync(Runnable runnable) {
        new Thread(runnable, "KeepADBRecoveryPulse").start();
    }

    @Override
    public void sleep(long delayMs) throws InterruptedException {
        Thread.sleep(delayMs);
    }

    @Override
    public long elapsedRealtimeMs() {
        return SystemClock.elapsedRealtime();
    }
}
