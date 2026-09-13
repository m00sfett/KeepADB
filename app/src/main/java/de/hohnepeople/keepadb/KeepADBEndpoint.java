package de.hohnepeople.keepadb;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.util.Log;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Discovers the active secure wireless-debugging endpoint advertised by adbd.
 * mDNS (NsdManager) is the sole discovery path: the local loopback port-range "quick probe"
 * that used to run alongside it as a best-effort shortcut was removed in #424 after #404 showed
 * on real devices that its TLS-sniff confirmation (formerly {@code probeAdbTlsPort}, removed in
 * #435 once #394's cached-endpoint re-verification -- its last caller -- fell back to the plain
 * {@link #isPortReachable}) never actually matches genuine adbd, so it never delivered an early
 * confirmation -- only up to ~1.2s of worst-case latency per connection attempt for nothing.
 */
final class KeepADBEndpoint {
    private static final String TAG = "KeepADBEndpoint";
    static final String SERVICE_TYPE = "_adb-tls-connect._tcp.";
    private static final long RESOLVE_TIMEOUT_MS = 1500;
    private static final java.util.concurrent.ExecutorService VERIFY_EXECUTOR =
            java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "KeepADBVerifyWorker");
                t.setDaemon(true);
                return t;
            });
    // #412: two timeout budgets exist for this same class of reachability probe (a plain-connect
    // socket check against a candidate ADB endpoint), deliberately *not* unified into one value,
    // because each guards a different call site with its own latency tolerance and trust context:
    //  - NSD_ADDRESS_VERIFY_TIMEOUT_MS (400ms, see isPortReachable() call in the mDNS resolve
    //    path below): runs once per resolved mDNS candidate, not in a tight loop, so it can afford
    //    a somewhat larger margin for a real network round-trip.
    //  - KeepADBNotification's cached-endpoint re-verification (500ms, hardcoded at that call
    //    site since it belongs to that class's own heartbeat cadence): the least time-sensitive
    //    of the two, since it only re-checks an already-cached, previously-working endpoint on a
    //    periodic tick, not a fresh discovery attempt blocking endpoint delivery. Both call sites
    //    use the plain-connect isPortReachable() as of #435 -- the TLS-sniff probeAdbTlsPort()
    //    that used to back the cached-endpoint path was removed once it lost its last caller
    //    (see the class doc above).
    private static final int NSD_ADDRESS_VERIFY_TIMEOUT_MS = 400;
    private static final long RECOVERY_PULSE_DELAY_MS = 5000;
    private static final long RECOVERY_PULSE_OFF_MS = 800;
    // Must stay comfortably above RECOVERY_PULSE_DELAY_MS + RECOVERY_PULSE_OFF_MS (5800ms):
    // the recovery pulse's re-enable only happens at that point, and mDNS still needs to pick
    // up the freshly re-advertised listener afterwards (typically 1-2s per README). Cutting
    // this too close would make the overall timeout fire before the pulse's own fix had a
    // realistic chance to work. Giving up here isn't otherwise fatal: KeepADBNotification's
    // onUnavailable() retries with backoff, so a too-short timeout just costs an extra cycle
    // in the slow-path case, not the endpoint.
    private static final long OVERALL_TIMEOUT_MS = 8_000;

    interface Listener {
        void onEndpoint(String host, int port);
        void onUnavailable();
    }

    private final Context appContext;
    private final KeepADBNsdProbe nsdProbe;
    private final KeepADBScheduler scheduler;
    private final KeepADBMulticastLock multicastLock;
    private NsdManager.DiscoveryListener discoveryListener;
    private final Queue<NsdServiceInfo> resolveQueue = new ArrayDeque<>();
    private boolean resolving;
    private Runnable resolveWatchdogRunnable;
    private long discoveryGeneration;
    private long currentResolveAttemptToken;
    private Listener currentListener;
    private boolean discovering;
    private final AtomicBoolean endpointDelivered = new AtomicBoolean(false);
    private Runnable recoveryPulseRunnable;
    private boolean recoveryPulseEnabled;
    private Runnable overallTimeoutRunnable;

    KeepADBEndpoint(Context context) {
        this(context, defaultNsdProbe(context), new KeepADBAndroidScheduler());
    }

    /** Package-visible so tests can substitute a fake probe/scheduler (#249). */
    KeepADBEndpoint(Context context, KeepADBNsdProbe nsdProbe, KeepADBScheduler scheduler) {
        this(context, nsdProbe, scheduler, null);
    }

    /** Package-visible so tests can additionally substitute a fake multicast lock (#359). */
    KeepADBEndpoint(Context context, KeepADBNsdProbe nsdProbe, KeepADBScheduler scheduler,
            KeepADBMulticastLock multicastLockOverride) {
        appContext = context.getApplicationContext();
        this.nsdProbe = nsdProbe;
        this.scheduler = scheduler;
        if (multicastLockOverride != null) {
            multicastLock = multicastLockOverride;
        } else {
            WifiManager wifiManager = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null) {
                WifiManager.MulticastLock lock =
                        wifiManager.createMulticastLock("de.hohnepeople.keepadb.KeepADBEndpoint");
                lock.setReferenceCounted(false);
                multicastLock = new KeepADBAndroidMulticastLock(lock);
            } else {
                multicastLock = null;
            }
        }
    }

    private static KeepADBNsdProbe defaultNsdProbe(Context context) {
        NsdManager nsdManager = (NsdManager) context.getApplicationContext().getSystemService(Context.NSD_SERVICE);
        return nsdManager == null ? null : new KeepADBAndroidNsdProbe(nsdManager);
    }

    synchronized void discover(Listener listener, boolean allowRecoveryPulse) {
        if (discovering && !endpointDelivered.get()) {
            if (!allowRecoveryPulse || recoveryPulseEnabled) {
                currentListener = listener;
                Log.d(TAG, "discover called while already discovering (gen=" + discoveryGeneration
                        + "); attached listener to active probe");
                return;
            }
            // A global caller joined a Tile-only read. Restart so the global path retains its
            // recovery pulse and full timeout while the abandoned Tile session is invalidated.
            stop();
        }
        if (discovering) {
            stop();
        }
        currentListener = listener;
        if (nsdProbe == null) {
            currentListener = null;
            if (listener != null) {
                listener.onUnavailable();
            }
            return;
        }

        discovering = true;
        recoveryPulseEnabled = allowRecoveryPulse;
        endpointDelivered.set(false);

        if (multicastLock != null && !multicastLock.isHeld()) {
            try {
                multicastLock.acquire();
            } catch (RuntimeException ignored) {
            }
        }

        final long generation = discoveryGeneration;

        // #359: everything from here down to the overall-timeout post below can throw. Without
        // this try/finally, such an exception would leave the multicast lock acquired above held
        // indefinitely -- neither the timeout nor any other path would ever release it, since the
        // timeout runnable itself never got posted. The finally block below calls stop() (its
        // existing full teardown, already used on every other discover()-abort path) so the lock
        // and every other resource acquired in this window is released before the exception is
        // passed on or handled, whichever the caller does.
        boolean timeoutArmed = false;
        try {
            // mDNS discovery -- the sole discovery path (#424 removed the local port-range
            // "quick probe" that used to also run here, see the class doc above).
            discoveryListener = new NsdManager.DiscoveryListener() {
                @Override
                public void onDiscoveryStarted(String serviceType) {
                    Log.d(TAG, "mDNS discovery started");
                }

                @Override
                public void onServiceFound(NsdServiceInfo serviceInfo) {
                    if (!isCurrent(generation) || endpointDelivered.get() || !sameServiceType(serviceInfo.getServiceType())) {
                        return;
                    }
                    synchronized (KeepADBEndpoint.this) {
                        if (!isCurrent(generation) || endpointDelivered.get()) return;
                        resolveQueue.offer(serviceInfo);
                        processNextResolveLocked(generation);
                    }
                }

                @Override
                public void onServiceLost(NsdServiceInfo serviceInfo) {
                }

                @Override
                public void onDiscoveryStopped(String serviceType) {
                }

                @Override
                public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                    Log.w(TAG, "mDNS start discovery failed with code: " + errorCode);
                }

                @Override
                public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                }
            };

            try {
                nsdProbe.discoverServices(SERVICE_TYPE, discoveryListener);
            } catch (RuntimeException e) {
                Log.w(TAG, "Failed to start mDNS service discovery", e);
                discoveryListener = null;
            }

            // 3. #114 safety net: if adb_wifi_enabled is on but nothing was found after a while,
            // adbd may have accepted the toggle mid-teardown of a previous session without ever
            // binding a listener. Pulse it once to force a clean restart, then give mDNS a fresh
            // chance to pick up the new advertisement before giving up entirely.
            if (allowRecoveryPulse) {
                recoveryPulseRunnable = () -> maybeSendRecoveryPulse(generation);
                scheduler.postDelayed(recoveryPulseRunnable, RECOVERY_PULSE_DELAY_MS);
            }
            overallTimeoutRunnable = () -> giveUpIfStillUnresolved(generation);
            scheduler.postDelayed(overallTimeoutRunnable, OVERALL_TIMEOUT_MS);
            timeoutArmed = true;
        } finally {
            if (!timeoutArmed) {
                Log.w(TAG, "gen=" + generation
                        + " discover() failed before the overall timeout was armed; releasing resources");
                stop();
            }
        }
    }

    private static final long RECOVERY_PULSE_COOLDOWN_MS = 20_000;
    // Static, not per-instance: toggling adb_wifi_enabled fires KeepADBService's/MainActivity's
    // ContentObserver, which tears down and recreates the KeepADBEndpoint instance (see
    // KeepADBNotification.stop()/startDiscoveryDirectLocked()). An instance-scoped "already
    // pulsed" flag would reset with every such recreation, causing our own pulse to retrigger
    // itself every ~6.5s in an endless loop that never gave mDNS a real chance to resolve
    // anything -- found live: the recovery pulse fired repeatedly for 40+ seconds straight.
    //
    // #309: a monotonic reading (SystemClock.elapsedRealtime() behind KeepADBScheduler), never
    // System.currentTimeMillis(): a wall-clock jump (NTP correction, timezone/manual clock
    // change) would either park the cooldown arbitrarily far in the future -- suppressing every
    // recovery pulse until real time caught up -- or jump backwards past it and let the pulse
    // fire far more often than the cooldown allows. Seeded a full cooldown in the past so the
    // first pulse is allowed even while elapsedRealtime() is still below the cooldown, i.e.
    // within the first 20s after boot, where a plain 0 would have suppressed it.
    private static volatile long lastRecoveryPulseAtMs = -RECOVERY_PULSE_COOLDOWN_MS;

    // Package-visible so the endpoint-owned recovery guard can be exercised end-to-end without
    // waiting for the discovery watchdog. This is not part of the app's public API.
    void maybeSendRecoveryPulse(long generation) {
        synchronized (this) {
            if (!isCurrent(generation) || endpointDelivered.get()) return;
            if (!KeepADB.isEnabled(appContext) || KeepADB.wasLastExplicitIntentOff(appContext)) return;
        }
        // #296: "trusted network" is a policy decision about an SSID/BSSID allowlist -- it says
        // nothing about whether the device is actually still on a Wi-Fi transport at all right
        // now. Without this, a device that lost Wi-Fi entirely (but still remembers a trusted
        // network from moments ago) would keep pulsing adb_wifi_enabled every
        // RECOVERY_PULSE_COOLDOWN_MS with no Wi-Fi listener for adbd to ever bind on.
        if (!KeepADBService.isWifiConnected(appContext)) {
            Log.i(TAG, "gen=" + generation + " skipping recovery pulse without an active Wi-Fi connection");
            return;
        }
        if (!KeepADBTrustedNetwork.isCurrentNetworkTrusted(appContext)) {
            Log.i(TAG, "gen=" + generation + " skipping recovery pulse on an untrusted Wi-Fi network");
            return;
        }
        long now = scheduler.elapsedRealtimeMs();
        synchronized (KeepADBEndpoint.class) {
            if (now - lastRecoveryPulseAtMs < RECOVERY_PULSE_COOLDOWN_MS) return;
            lastRecoveryPulseAtMs = now;
        }
        Log.w(TAG, "gen=" + generation + " found no adbd listener after " + RECOVERY_PULSE_DELAY_MS
                + "ms while enabled; pulsing adb_wifi_enabled to recover");
        long keepAdbNetworkGeneration = KeepADB.currentNetworkGeneration();
        KeepADB.performRecoveryPulse(appContext,
                context -> isCurrent(generation)
                        && KeepADB.currentNetworkGeneration() == keepAdbNetworkGeneration
                        && KeepADBService.isWifiConnected(context)
                        && KeepADBTrustedNetwork.isCurrentNetworkTrusted(context)
                        && !KeepADB.wasLastExplicitIntentOff(context));
    }

    static synchronized void resetForTesting() {
        lastRecoveryPulseAtMs = -RECOVERY_PULSE_COOLDOWN_MS;
    }

    private void giveUpIfStillUnresolved(long generation) {
        Listener targetListener;
        synchronized (this) {
            if (!isCurrent(generation) || endpointDelivered.get()) return;
            Log.w(TAG, "gen=" + generation + " timed out after " + OVERALL_TIMEOUT_MS + "ms without an endpoint");
            targetListener = currentListener;
            stop();
        }
        if (targetListener != null) {
            targetListener.onUnavailable();
        }
    }

    private void cancelResolveWatchdogLocked() {
        if (resolveWatchdogRunnable != null) {
            scheduler.removeCallbacks(resolveWatchdogRunnable);
            resolveWatchdogRunnable = null;
        }
    }

    private void processNextResolveLocked(long generation) {
        if (resolving || resolveQueue.isEmpty() || !isCurrent(generation) || endpointDelivered.get()) {
            return;
        }
        final NsdServiceInfo nextService = resolveQueue.poll();
        if (nextService == null) {
            return;
        }
        final long attemptToken = ++currentResolveAttemptToken;
        resolving = true;
        cancelResolveWatchdogLocked();
        resolveWatchdogRunnable = () -> {
            synchronized (KeepADBEndpoint.this) {
                if (!isCurrent(generation) || !resolving || currentResolveAttemptToken != attemptToken || endpointDelivered.get()) return;
                resolving = false;
                processNextResolveLocked(generation);
            }
        };
        scheduler.postDelayed(resolveWatchdogRunnable, RESOLVE_TIMEOUT_MS);

        try {
            nsdProbe.resolveService(nextService, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo ignored, int errorCode) {
                    synchronized (KeepADBEndpoint.this) {
                        if (!isCurrent(generation) || currentResolveAttemptToken != attemptToken) return;
                        cancelResolveWatchdogLocked();
                        resolving = false;
                        if (endpointDelivered.get()) return;
                        processNextResolveLocked(generation);
                    }
                }

                @Override
                public void onServiceResolved(NsdServiceInfo resolved) {
                    synchronized (KeepADBEndpoint.this) {
                        if (!isCurrent(generation) || currentResolveAttemptToken != attemptToken || endpointDelivered.get()) {
                            return;
                        }
                        cancelResolveWatchdogLocked();
                        if (resolved.getHost() == null || resolved.getPort() <= 0) {
                            resolving = false;
                            processNextResolveLocked(generation);
                            return;
                        }
                        final InetAddress addr = resolved.getHost();
                        if (!isOwnWifiAddress(appContext, addr)) {
                            Log.w(TAG, "Ignoring mDNS ADB service that is not on our active Wi-Fi address: " + addr);
                            resolving = false;
                            processNextResolveLocked(generation);
                            return;
                        }
                        final String host = addr.getHostAddress();
                        if (host == null || host.isEmpty()) {
                            resolving = false;
                            processNextResolveLocked(generation);
                            return;
                        }
                        final int port = resolved.getPort();
                        VERIFY_EXECUTOR.execute(() -> {
                            // #412: originally switched this to the same TLS-sniffing probe as the
                            // quick probe (#363) so a resolved mDNS candidate would get the same
                            // "any TCP responder" hole closed instead of being trusted on a bare
                            // connect() success. Reverted after #404 proved on a real device that
                            // probeAdbTlsPort() never returns true against genuine adbd -- neither
                            // the timeout nor the EOF branch matches its actual TLS handshake
                            // behavior. Since this mDNS path is the fallback net for the (also
                            // broken, see #404) quick probe, the TLS-sniff switch made the app
                            // unable to find any WLAN ADB endpoint at all -- a real availability
                            // regression. Falling back to a plain isPortReachable() connect is the
                            // deliberate, documented alternative named in #412's own acceptance
                            // criterion ("... or a plain connect for a documented reason
                            // suffices"), until the broader probe-architecture question is settled
                            // in #424. See the constant's own comment above for why this budget
                            // stays separate from the other two.
                            boolean reachable = isPortReachable(addr, port, NSD_ADDRESS_VERIFY_TIMEOUT_MS);
                            Listener targetListener = null;
                            synchronized (KeepADBEndpoint.this) {
                                if (!isCurrent(generation) || currentResolveAttemptToken != attemptToken) {
                                    return;
                                }
                                if (reachable && endpointDelivered.compareAndSet(false, true)) {
                                    resolveQueue.clear();
                                    targetListener = currentListener;
                                    stop();
                                } else {
                                    resolving = false;
                                    if (isCurrent(generation) && !endpointDelivered.get()) {
                                        processNextResolveLocked(generation);
                                    }
                                    return;
                                }
                            }
                            if (targetListener != null) {
                                targetListener.onEndpoint(host, port);
                            }
                        });
                    }
                }
            });
        } catch (RuntimeException ignored) {
            cancelResolveWatchdogLocked();
            resolving = false;
            processNextResolveLocked(generation);
        }
    }

    synchronized void stop() {
        discoveryGeneration++;
        currentResolveAttemptToken++;
        discovering = false;
        currentListener = null;
        cancelResolveWatchdogLocked();
        resolveQueue.clear();
        resolving = false;
        if (recoveryPulseRunnable != null) {
            scheduler.removeCallbacks(recoveryPulseRunnable);
            recoveryPulseRunnable = null;
        }
        recoveryPulseEnabled = false;
        if (overallTimeoutRunnable != null) {
            scheduler.removeCallbacks(overallTimeoutRunnable);
            overallTimeoutRunnable = null;
        }
        NsdManager.DiscoveryListener listener = discoveryListener;
        discoveryListener = null;
        if (multicastLock != null && multicastLock.isHeld()) {
            try {
                multicastLock.release();
            } catch (RuntimeException ignored) {
            }
        }
        if (nsdProbe != null && listener != null) {
            try {
                nsdProbe.stopServiceDiscovery(listener);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private synchronized boolean isCurrent(long generation) {
        return discoveryGeneration == generation;
    }

    private static boolean isPortReachable(InetAddress addr, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(addr, port), timeoutMs);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Package-visible so a previously reported endpoint can be re-verified before reuse. */
    static boolean isPortReachable(String host, int port, int timeoutMs) {
        try {
            return isPortReachable(InetAddress.getByName(host), port, timeoutMs);
        } catch (Exception ignored) {
            return false;
        }
    }

    static String getWifiIpAddress(Context context) {
        if (context == null) return null;
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                WifiInfo info = wm.getConnectionInfo();
                if (info != null) {
                    int ip = info.getIpAddress();
                    if (ip != 0) {
                        return String.format(Locale.US, "%d.%d.%d.%d",
                                (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
                    }
                }
            }
        } catch (Exception ignored) {
        }

        try {
            return KeepADBNetwork.get(context).getWifiIpv4Address();
        } catch (Exception ignored) {
        }
        return null;
    }

    static String formatEndpoint(String host, int port) {
        if (host == null) return ":" + port;
        if (host.contains(":") && !host.startsWith("[")) {
            return "[" + host + "]:" + port;
        }
        return host + ":" + port;
    }

    /**
     * Whether {@code addr} may be registered as a wireless-debugging endpoint at all (#314):
     * it must be an address currently bound to an eligible Wi-Fi network of this device.
     *
     * <p>The predecessor {@code isLocalAddress()} returned {@code true} for <em>every</em>
     * loopback and link-local address before it ever looked at our own interfaces, so an
     * unrelated service -- a neighbour's mDNS responder on an IPv6 link-local address, or any
     * local listener at all -- could be registered as "the" endpoint. Registering a wrong
     * endpoint is worse than registering none: the reported host:port is then handed out as the
     * {@code adb connect} target and blocks the paths that would have found the real listener.
     *
     * <p>Fail-closed by construction: a {@code null} context, an unavailable {@link
     * KeepADBNetwork}, or simply no Wi-Fi connection all yield an empty candidate set and thus
     * {@code false} -- no endpoint is registered rather than a guessed one.
     *
     * <p>Out of scope here (R13 on #314, still open): proving that whatever listens behind the
     * port is really adbd. This gate only proves <em>where</em> the candidate lives, not
     * <em>what</em> it is.
     */
    static boolean isOwnWifiAddress(Context context, InetAddress addr) {
        if (addr == null || context == null) return false;
        try {
            return KeepADBNetwork.get(context).isActiveWifiAddress(addr);
        } catch (Exception ignored) {
        }
        return false;
    }

    private static boolean sameServiceType(String serviceType) {
        if (serviceType == null) return false;
        String normalized = serviceType.trim();
        while (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return "_adb-tls-connect._tcp".equalsIgnoreCase(normalized);
    }
}
