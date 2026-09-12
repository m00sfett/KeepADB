package de.hohnepeople.keepadb;

import android.net.wifi.WifiManager;

/** Production {@link KeepADBMulticastLock} backed by a real {@link WifiManager.MulticastLock}. */
final class KeepADBAndroidMulticastLock implements KeepADBMulticastLock {
    private final WifiManager.MulticastLock delegate;

    KeepADBAndroidMulticastLock(WifiManager.MulticastLock delegate) {
        this.delegate = delegate;
    }

    @Override
    public void acquire() {
        delegate.acquire();
    }

    @Override
    public void release() {
        delegate.release();
    }

    @Override
    public boolean isHeld() {
        return delegate.isHeld();
    }
}
