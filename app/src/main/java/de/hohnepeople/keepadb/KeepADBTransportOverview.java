package de.hohnepeople.keepadb;

import android.content.Context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * #538: aggregates the currently verified ADB transports (WLAN/LAN, Tailscale/VPN, USB) into a
 * single, display-ready snapshot. Builds a fresh {@link Snapshot} on every call -- this class
 * holds no state of its own, matching the project convention that every surface reads live state
 * rather than keeping a persistent app state. A transport that stops being verified between two
 * calls simply does not appear in the next snapshot; there is nothing to invalidate.
 *
 * <p>Each entry is only included once its own transport class has actually confirmed ADB
 * reachability -- a bare interface/candidate address is never surfaced as an endpoint:
 * <ul>
 *   <li>WLAN/LAN reuses {@link KeepADBNotification}'s already-verified cached endpoint (its
 *       background reachability probe and mDNS discovery are unchanged by this class).</li>
 *   <li>Tailscale/VPN uses {@link KeepADBVpnTransport}'s own independent verification.</li>
 *   <li>USB reuses {@link KeepADBUsbReceiver}'s system connected+configured+adb broadcast
 *       extras, the same signal the USB notification already relies on.</li>
 * </ul>
 */
final class KeepADBTransportOverview {

    private KeepADBTransportOverview() {}

    /** One aggregation result. {@link #transports} is empty exactly when no transport at all is
     * currently verified; {@link #vpnActiveNotAdbVerified} can be {@code true} independently of
     * that (an active VPN interface that isn't confirmed as an ADB transport is deliberately not
     * an entry in {@link #transports} -- see the #538 acceptance criterion that an active VPN
     * interface must never be presented as an ADB endpoint on its own). */
    static final class Snapshot {
        final List<KeepADBTransportEndpoint> transports;
        final boolean vpnActiveNotAdbVerified;

        Snapshot(List<KeepADBTransportEndpoint> transports, boolean vpnActiveNotAdbVerified) {
            this.transports = transports;
            this.vpnActiveNotAdbVerified = vpnActiveNotAdbVerified;
        }
    }

    interface Listener {
        void onSnapshot(Snapshot snapshot);
    }

    /**
     * Delivers {@link #current(Context)} to {@code listener}, on the calling thread whenever
     * possible -- only hopping to a background worker thread when a Tailscale-range VPN address
     * is actually present, since only then does {@link #current} reach {@link
     * KeepADBVpnTransport#verifyAdbReachable}'s blocking socket connect. The large majority of
     * calls (no active Tailscale-range VPN at all) therefore run synchronously and
     * deterministically, which matters for a caller like {@code MainActivity} that drives this
     * from {@code refresh()} -- itself reachable from many call sites, some already under test
     * without any awareness of this class -- since {@link android.app.Activity#runOnUiThread}
     * already special-cases "already on the UI thread" as an immediate, synchronous call.
     *
     * <p>This duplicates one cheap, side-effect-free {@link
     * KeepADBVpnTransport#findTailscaleIpv4Address} lookup between this dispatch check and
     * {@link #current}'s own call to it, deliberately: keeping {@link #current} as the single
     * aggregation code path (used directly by tests) is worth more than saving one extra
     * {@code ConnectivityManager} query.
     */
    static void currentAsync(Context context, Listener listener) {
        if (context == null || listener == null) return;
        Context appContext = context.getApplicationContext();
        if (KeepADBVpnTransport.findTailscaleIpv4Address(appContext) == null) {
            listener.onSnapshot(current(appContext));
            return;
        }
        Thread worker = new Thread(() -> listener.onSnapshot(current(appContext)),
                "KeepADBTransportOverview");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Synchronous variant -- callers on the main thread must use {@link #currentAsync} instead;
     * this is exposed directly for unit tests and for {@link #currentAsync}'s own worker body.
     */
    static Snapshot current(Context context) {
        List<KeepADBTransportEndpoint> transports = new ArrayList<>();

        if (KeepADBNotification.hasCurrentEndpoint()) {
            transports.add(new KeepADBTransportEndpoint(
                    KeepADBTransportEndpoint.Type.WLAN_LAN,
                    KeepADBNotification.getCurrentHost(),
                    KeepADBNotification.getCurrentPort(),
                    KeepADBNotification.getCurrentEndpointVerifiedAtMs(),
                    false));
        }

        boolean vpnActiveNotAdbVerified = false;
        String tailscaleIp = KeepADBVpnTransport.findTailscaleIpv4Address(context);
        if (tailscaleIp != null) {
            int wlanPort = KeepADBNotification.hasCurrentEndpoint()
                    ? KeepADBNotification.getCurrentPort() : 0;
            if (wlanPort > 0 && KeepADBVpnTransport.verifyAdbReachable(tailscaleIp, wlanPort)) {
                transports.add(new KeepADBTransportEndpoint(
                        KeepADBTransportEndpoint.Type.TAILSCALE_VPN,
                        tailscaleIp, wlanPort, System.currentTimeMillis(), false));
            } else {
                vpnActiveNotAdbVerified = true;
            }
        } else if (KeepADBVpnTransport.hasActiveVpnTransport(context)) {
            vpnActiveNotAdbVerified = true;
        }

        if (KeepADBUsbReceiver.isCurrentlyUsbAdbConnected(context)) {
            transports.add(new KeepADBTransportEndpoint(
                    KeepADBTransportEndpoint.Type.USB, null, 0,
                    System.currentTimeMillis(), false));
        }

        if (!transports.isEmpty()) {
            transports.set(0, transports.get(0).withPrimary(true));
        }
        return new Snapshot(Collections.unmodifiableList(transports), vpnActiveNotAdbVerified);
    }
}
