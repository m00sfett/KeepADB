package de.hohnepeople.keepadb;

import android.content.Context;
import android.util.Log;
import de.hohnepeople.keepadb.selfpair.LoopbackAdbScan;
import java.net.InetAddress;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * #1030: finds adbd's Wifi-ADB endpoint without mDNS. On some devices adbd stops announcing
 * {@code _adb-tls-connect._tcp} after a while although its port stays open, so mDNS alone finds
 * nothing until Wifi-ADB restarts.
 *
 * <p>Order: first the port remembered in this boot gets one ADB handshake; only if that fails
 * does the loopback range get scanned, at most once per {@link #SCAN_COOLDOWN_MS} and never twice
 * at the same time. A port counts only when it answers ADB's connect message with the TLS upgrade
 * request ({@code STLS}), only when exactly one port does, and only when it is also reachable on
 * the device's own Wi-Fi address -- that address is what gets reported.
 *
 * <p>Blocking; never call {@link #find} on the main thread.
 */
final class KeepADBPortFallback {
    private static final String TAG = "KeepADBPortFallback";
    static final long SCAN_COOLDOWN_MS = 60_000L;
    private static final int WIFI_VERIFY_TIMEOUT_MS = 400;

    /** The socket and network work, replaceable in tests. */
    interface Prober {
        /** This device's active Wi-Fi IPv4 address, or {@code null}. */
        String ownWifiHost(Context context);

        boolean offersAdbOverTls(int port);

        List<Integer> scan(BooleanSupplier cancelled) throws InterruptedException;

        boolean isReachable(String host, int port);
    }

    static final Prober LOOPBACK = new Prober() {
        @Override
        public String ownWifiHost(Context context) {
            String host = KeepADBEndpoint.getWifiIpAddress(context);
            if (host == null) return null;
            try {
                return KeepADBEndpoint.isOwnWifiAddress(context, InetAddress.getByName(host))
                        ? host : null;
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        public boolean offersAdbOverTls(int port) {
            return LoopbackAdbScan.offersAdbOverTls(port);
        }

        @Override
        public List<Integer> scan(BooleanSupplier cancelled) throws InterruptedException {
            return LoopbackAdbScan.findEphemeral(cancelled);
        }

        @Override
        public boolean isReachable(String host, int port) {
            return KeepADBEndpoint.isPortReachable(host, port, WIFI_VERIFY_TIMEOUT_MS);
        }
    };

    /** An endpoint found without mDNS. */
    static final class Result {
        final String host;
        final int port;

        Result(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    private static Prober prober = LOOPBACK;
    private static KeepADBDeadline.Clock clock = KeepADBDeadline.SYSTEM_CLOCK;
    private static boolean scanned;
    private static long lastScanAtMs;
    private static boolean scanning;

    private KeepADBPortFallback() {
    }

    static synchronized void setProberForTesting(Prober testProber) {
        prober = testProber != null ? testProber : LOOPBACK;
    }

    static synchronized void setClockForTesting(KeepADBDeadline.Clock testClock) {
        clock = testClock != null ? testClock : KeepADBDeadline.SYSTEM_CLOCK;
    }

    static synchronized void resetForTesting() {
        prober = LOOPBACK;
        clock = KeepADBDeadline.SYSTEM_CLOCK;
        scanned = false;
        lastScanAtMs = 0;
        scanning = false;
    }

    /** Remembers the port adbd was found on, together with the boot it belongs to. */
    static void remember(Context context, int port) {
        if (context == null || port <= 0) return;
        Context app = context.getApplicationContext();
        int boot = currentClock().bootCount(app);
        if (boot < 0) {
            KeepADBPreferences.clearLastAdbPort(app);
            return;
        }
        if (KeepADBPreferences.getLastAdbPort(app) == port
                && KeepADBPreferences.getLastAdbPortBoot(app) == boot) {
            return;
        }
        KeepADBPreferences.setLastAdbPort(app, port, boot);
    }

    /** The port remembered in this boot, or -1 (a different or unreadable boot counter drops it). */
    static int rememberedPort(Context context) {
        Context app = context.getApplicationContext();
        int port = KeepADBPreferences.getLastAdbPort(app);
        if (port <= 0) return -1;
        int boot = currentClock().bootCount(app);
        if (boot < 0 || boot != KeepADBPreferences.getLastAdbPortBoot(app)) {
            KeepADBPreferences.clearLastAdbPort(app);
            return -1;
        }
        return port;
    }

    /** Looks for the live endpoint without mDNS; {@code null} when none is found. */
    static Result find(Context context, BooleanSupplier cancelled) {
        Context app = context.getApplicationContext();
        Prober activeProber = currentProber();
        String host = activeProber.ownWifiHost(app);
        if (host == null) {
            return null;
        }
        int remembered = rememberedPort(app);
        if (remembered > 0 && !cancelled.getAsBoolean()
                && activeProber.offersAdbOverTls(remembered)
                && activeProber.isReachable(host, remembered)) {
            KeepADBDiagnostics.event(app, "endpoint_fallback", "remembered_port", "found",
                    "port=" + remembered);
            return new Result(host, remembered);
        }
        if (cancelled.getAsBoolean() || !claimScan()) {
            return null;
        }
        List<Integer> found;
        try {
            found = activeProber.scan(cancelled);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (RuntimeException e) {
            Log.w(TAG, "Loopback scan failed", e);
            return null;
        } finally {
            releaseScan();
        }
        if (cancelled.getAsBoolean()) {
            return null;
        }
        if (found.size() != 1) {
            KeepADBDiagnostics.event(app, "endpoint_fallback", "loopback_scan",
                    found.isEmpty() ? "none" : "ambiguous", "candidates=" + found.size());
            return null;
        }
        int port = found.get(0);
        if (!activeProber.isReachable(host, port)) {
            KeepADBDiagnostics.event(app, "endpoint_fallback", "loopback_scan",
                    "not_on_wifi", "port=" + port);
            return null;
        }
        KeepADBDiagnostics.event(app, "endpoint_fallback", "loopback_scan", "found",
                "port=" + port);
        // Remembered only by the caller's winning delivery, never by a superseded search.
        return new Result(host, port);
    }

    private static synchronized boolean claimScan() {
        if (scanning) return false;
        long now = clock.elapsedMs();
        if (scanned && now >= lastScanAtMs && now - lastScanAtMs < SCAN_COOLDOWN_MS) {
            return false;
        }
        scanned = true;
        lastScanAtMs = now;
        scanning = true;
        return true;
    }

    private static synchronized void releaseScan() {
        scanning = false;
    }

    private static synchronized Prober currentProber() {
        return prober;
    }

    private static synchronized KeepADBDeadline.Clock currentClock() {
        return clock;
    }
}
