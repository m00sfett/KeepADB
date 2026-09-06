package de.hohnepeople.keepadb;

/**
 * Boundary around the timing/threading primitives behind KeepADB's toggle debounce and
 * recovery pulse (#248): a main-looper delay queue, a background thread, a sleep, and a
 * monotonic clock. Letting a test substitute a fake here is what makes the debounce-supersede
 * and recovery-pulse-cancellation logic deterministically testable (#249) without real timing.
 */
interface KeepADBScheduler {
    void postDelayed(Runnable runnable, long delayMs);

    void removeCallbacks(Runnable runnable);

    void runAsync(Runnable runnable);

    void sleep(long delayMs) throws InterruptedException;

    long elapsedRealtimeMs();
}
