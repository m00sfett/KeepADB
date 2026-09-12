package de.hohnepeople.keepadb;

/**
 * Boundary around {@link android.net.wifi.WifiManager.MulticastLock} (#359): its acquire()/
 * release()/isHeld() bodies are stripped to no-ops under this project's Robolectric-free,
 * JVM-only unit test setup (AGP's mockable android.jar), so a real lock instance can never prove
 * whether {@code discover()}'s try/finally actually released it on an exception path. Letting a
 * test substitute a fake here mirrors {@link KeepADBScheduler}/{@link KeepADBNsdProbe} (#249).
 */
interface KeepADBMulticastLock {
    void acquire();

    void release();

    boolean isHeld();
}
