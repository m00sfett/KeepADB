package de.hohnepeople.keepadb;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Foreground service that monitors Wi-Fi connectivity and wireless debugging state
 * to automatically re-enable Wireless Debugging and push new endpoints to the register.
 */
public class KeepADBService extends Service {
    private static final String TAG = "KeepADBService";

    private ContentObserver adbContentObserver;
    private ConnectivityManager.NetworkCallback networkCallback;
    private boolean isRegisteredObserver = false;
    private boolean isRegisteredNetworkCallback = false;
    private boolean foregroundReady = false;
    // Network callbacks may overlap during a Wi-Fi handover. Keep the callback's own view so a
    // late onLost(old) cannot invalidate the still-live onAvailable(new) connection.
    private final Set<Network> availableWifiNetworks = ConcurrentHashMap.newKeySet();
    private long lastRecheckTime = 0;
    // #276: onCapabilitiesChanged() fires on every routine RSSI update, not just a roam, so the
    // re-verification it triggers is throttled independently of recheckAndEnable()'s own debounce.
    private long lastCapabilitiesRecheckTime = 0;
    private static final long CAPABILITIES_RECHECK_MIN_INTERVAL_MS = 5000;

    static boolean shouldRun(Context context) {
        if (context == null) return false;
        return KeepADBPreferences.isKeepAliveEnabled(context)
                && (KeepADB.isEnabled(context) || !KeepADB.wasLastExplicitIntentOff(context));
    }

    static void sync(Context context) {
        boolean shouldRun = shouldRun(context);
        KeepADBDiagnostics.event(context, "service_sync", "state_change",
                shouldRun ? "start_requested" : "stop_requested",
                "keepAlive=" + KeepADBPreferences.isKeepAliveEnabled(context)
                        + " adbWifi=" + KeepADB.isEnabled(context)
                        + " lastIntentOff=" + KeepADB.wasLastExplicitIntentOff(context));
        KeepADBUsbReceiver.refresh(context);
        if (shouldRun) {
            start(context);
        } else {
            stop(context);
        }
    }

    static boolean start(Context context) {
        Intent intent = new Intent(context, KeepADBService.class);
        try {
            context.startForegroundService(intent);
            KeepADBDiagnostics.event(context, "service_start", "system", "requested", "foreground=true");
            return true;
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to request KeepADB foreground service start", e);
            KeepADBDiagnostics.event(context, "service_start", "system", "failed", "runtime_exception");
            return false;
        }
    }

    static void stop(Context context) {
        Intent intent = new Intent(context, KeepADBService.class);
        context.stopService(intent);
        KeepADBDiagnostics.event(context, "service_stop", "state_change", "requested", "stopService");
    }

    /**
     * The Keep-Alive auto-enable preconditions, re-evaluated as a {@link KeepADB.EnableGuard}
     * immediately before a debounced automatic enable actually writes (#310). Both signals are
     * read fresh: the Keep-Alive setting may have been switched off, and the device may have
     * moved to a network that is no longer on the trusted allowlist, while the enable sat in the
     * TOGGLE_COOLDOWN_MS window. Deliberately static and lock-free -- KeepADB invokes it while
     * holding {@code KeepADB.class}, so taking this service's own monitor here (as the
     * synchronized {@link #recheckAndEnable()} does on the way *in*) would invert the lock order.
     *
     * <p>#245 stays intact: the trust/Keep-Alive policy lives here at the automatic call site,
     * never inside the toggle facade, so a manual toggle can never be gated by either.
     *
     * <p>#348: {@link KeepADBTrustedNetwork#isCurrentNetworkTrusted} alone is not enough. In
     * {@code MODE_ALL_WIFI} it trusts unconditionally, without ever asking whether a Wi-Fi
     * transport is actually connected right now -- a device can keep that mode set while Wi-Fi
     * dropped in the background moments earlier. The active-transport check must gate the
     * decision independently, matching the invariant {@link
     * KeepADBEndpoint#maybeSendRecoveryPulse} already established for the recovery pulse (#296).
     */
    static boolean isAutoEnableStillPermitted(Context context) {
        // #496: re-checked here too, atomically with the write itself (this guard runs inside
        // KeepADB's lock, immediately before the actual Settings.Global call) -- not just at the
        // call sites that decide whether to schedule an attempt in the first place. Closes the
        // narrow race where two automatic triggers both pass the call-site pre-check before
        // either's write lands and the first one's mismatch engages the backoff.
        return KeepADBPreferences.isKeepAliveEnabled(context)
                && isWifiConnected(context)
                && KeepADBTrustedNetwork.isCurrentNetworkTrusted(context)
                && !KeepADB.isAutomaticEnableBackoffBlocked();
    }

    /**
     * #352: the synchronous {@code KeepADBEndpoint.getWifiIpAddress()} fallback below reads a
     * {@code WifiInfo} snapshot that can be stale -- e.g. briefly still reporting a just-dropped
     * connection's address. Falling back to it unconditionally would let that staleness produce
     * a false "connected" when {@link KeepADBNetwork}'s live, callback-driven state has already
     * and correctly answered "no". The fallback is therefore only trusted while this tracker's
     * negative answer is not "disconnected" but "unknown".
     *
     * <p>Review repair on top of #352/#390: that condition used to be {@link
     * KeepADBNetwork#isWifiCallbackRegistered()} alone, which covered only one of the two ways
     * the tracker can fail to know. Registration succeeds synchronously, but the framework
     * delivers the first callback asynchronously, so between {@link KeepADBNetwork#get} and that
     * first delivery the tracked maps are empty for reasons that have nothing to do with Wi-Fi
     * being off -- and "registered" was already true. The first call in a process therefore
     * deterministically answered "no Wi-Fi" even on a connected network, which since #348 gates
     * automatic re-enable and USB handover, and also drives the tile/notification state. #390
     * introduced exactly the right predicate for this ({@link
     * KeepADBNetwork#isWifiTrackingAuthoritative()}, registered <em>and</em> observed) but applied
     * it only to the endpoint-address path; this call site now uses it too.
     */
    static boolean isWifiConnected(Context context) {
        if (KeepADBNetwork.get(context).isWifiConnected()) {
            return true;
        }
        if (KeepADBNetwork.get(context).isWifiTrackingAuthoritative()) {
            return false;
        }
        return KeepADBEndpoint.getWifiIpAddress(context) != null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // #496: a fresh service instance is one of the explicit triggers that reopens the
        // automatic re-enable backoff, so a restart (whether user-initiated or after the system
        // killed the process) always gets a clean, un-blocked first attempt.
        KeepADB.resetAutomaticEnableBackoff();
        KeepADBUsbReceiver.refresh(this);
        long lastHeartbeat = KeepADBPreferences.getServiceLastHeartbeat(this);
        if (lastHeartbeat > 0) {
            long gapMs = System.currentTimeMillis() - lastHeartbeat;
            KeepADBDiagnostics.event(this, "service_create", "lifecycle", "restarted", "heartbeatGapMs=" + gapMs);
            Log.i(TAG, "onCreate: service (re)started, " + gapMs + "ms since last heartbeat"
                    + (gapMs > 90_000 ? " -- process was likely killed and restarted by the system" : ""));
        } else {
            KeepADBDiagnostics.event(this, "service_create", "lifecycle", "first_start", "no_prior_heartbeat");
            Log.i(TAG, "onCreate: service starting for the first time (no prior heartbeat)");
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        KeepADBDiagnostics.event(this, "service_start_command", "lifecycle", "received",
                "startId=" + startId + " flags=" + flags);
        Log.d(TAG, "onStartCommand startId=" + startId + " flags=" + flags);
        try {
            startForeground(KeepADBNotification.NOTIFICATION_ID,
                    KeepADBNotification.getServiceNotification(this));
        } catch (RuntimeException e) {
            KeepADBDiagnostics.event(this, "service_start_command", "lifecycle", "failed",
                    "foreground_promotion_exception");
            failForegroundStart(startId, e);
            return START_NOT_STICKY;
        }
        foregroundReady = true;
        KeepADBDiagnostics.event(this, "service_start_command", "lifecycle", "ready", "foreground=true");
        if (!shouldRun(this)) {
            Log.i(TAG, "onStartCommand: service should not run; stopping foreground mode");
            KeepADBDiagnostics.event(this, "service_start_command", "lifecycle", "stopped", "should_not_run");
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        heartbeatNow();
        registerAdbObserver();
        registerNetworkCallback();
        startHeartbeatTicker();
        recheckAndEnable();
        return START_STICKY;
    }

    private void failForegroundStart(int startId, RuntimeException cause) {
        foregroundReady = false;
        stopHeartbeatTicker();
        unregisterAdbObserver();
        unregisterNetworkCallback();
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (RuntimeException cleanupError) {
            Log.w(TAG, "Failed to remove foreground notification after startup failure", cleanupError);
        }
        boolean stopRequested = stopSelfResult(startId);
        Log.e(TAG, "Failed to enter foreground mode; cleanup requested=" + stopRequested, cause);
        KeepADBDiagnostics.event(this, "service_start_command", "lifecycle", "stopped",
                "foreground_promotion_failed cleanupRequested=" + stopRequested);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        KeepADBDiagnostics.event(this, "service_destroy", "lifecycle", "started", "foregroundReady=" + foregroundReady);
        Log.w(TAG, "onDestroy: service stopping");
        foregroundReady = false;
        stopHeartbeatTicker();
        unregisterAdbObserver();
        unregisterNetworkCallback();
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (RuntimeException cleanupError) {
            Log.w(TAG, "Failed to remove foreground notification on destroy", cleanupError);
        }
        KeepADBDiagnostics.event(this, "service_destroy", "lifecycle", "completed", "foreground=false");
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        KeepADBDiagnostics.event(this, "service_task_removed", "lifecycle", "received", "task_swiped");
        Log.w(TAG, "onTaskRemoved: app task was swiped away from recents");
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        Log.w(TAG, "onTrimMemory: level=" + level + " -- system is reclaiming memory, may kill this process next");
        KeepADBDiagnostics.event(this, "service_trim_memory", "system", "received", "level=" + level);
    }

    private void heartbeatNow() {
        KeepADBPreferences.setServiceLastHeartbeatNow(this);
        if (!foregroundReady) return;
        if (KeepADB.isEnabled(this)) {
            KeepADBNotification.verifyEndpointHealth(this);
            return;
        }
        // #536: the heartbeat is the long-lived timer that re-triggers a due automatic recheck
        // once the #496 backoff window elapses -- a stored blockedUntil alone never re-fires
        // anything on its own, it only ever gets asked about by whichever event happens to fire
        // next. recheckAndEnable() re-derives every gate itself (Wi-Fi state, trust, the backoff)
        // so this is always safe to call on every tick, whether the backoff is still blocking,
        // already open, or Keep-Alive isn't even armed.
        recheckAndEnable();
    }

    private static final long HEARTBEAT_INTERVAL_MS = 60_000;
    private final Handler heartbeatHandler = new Handler(Looper.getMainLooper());
    private Runnable heartbeatRunnable;

    private void startHeartbeatTicker() {
        stopHeartbeatTicker();
        heartbeatRunnable = () -> {
            heartbeatNow();
            heartbeatHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS);
        };
        heartbeatHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS);
    }

    private void stopHeartbeatTicker() {
        if (heartbeatRunnable != null) {
            heartbeatHandler.removeCallbacks(heartbeatRunnable);
            heartbeatRunnable = null;
        }
    }

    private void registerAdbObserver() {
        if (isRegisteredObserver) return;
        try {
            adbContentObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    super.onChange(selfChange, uri);
                    Log.d(TAG, "ContentObserver: adb_wifi_enabled changed");
                    KeepADBDiagnostics.event(KeepADBService.this, "state_observed", "content_observer",
                            "changed", "adbWifi=" + KeepADB.isEnabled(KeepADBService.this));
                    if (KeepADB.isEnabled(KeepADBService.this)) {
                        // #496: the readback now reflects "on" -- e.g. the user confirmed
                        // Android's pairing dialog independently -- so the automatic-enable
                        // backoff reopens instead of waiting out its fallback timer. Ahead of the
                        // foreground gate, like the network-generation bookkeeping in the
                        // NetworkCallback below.
                        //
                        // #500: no longer unconditional. Our own accepted-but-reverted write also
                        // surfaces here as a momentary "on", and resetting on it cleared the very
                        // backoff that write had just engaged -- so the following revert looked
                        // like a fresh unblocked cycle and the 1.5s retry loop continued. While an
                        // attempt of ours is awaiting its verdict, only that verdict decides.
                        KeepADB.noteObservedEnabled();
                    }
                    if (!foregroundReady) {
                        Log.d(TAG, "Ignoring state change before foreground promotion");
                        return;
                    }
                    if (KeepADBPreferences.isKeepAliveEnabled(KeepADBService.this)) {
                        if (isWifiConnected(KeepADBService.this) && !KeepADB.isEnabled(KeepADBService.this)) {
                            if (KeepADB.consumeUserDisabled() || KeepADB.wasLastExplicitIntentOff(KeepADBService.this)) {
                                Log.i(TAG, "Wireless Debugging manually disabled by user; stopping service");
                                KeepADBDiagnostics.event(KeepADBService.this, "recovery_or_stop", "content_observer",
                                        "stopped", "user_disabled");
                                stop(KeepADBService.this);
                                KeepADBNotification.refresh(KeepADBService.this);
                                KeepADBWidget.refreshAll(KeepADBService.this);
                                return;
                            } else if (!KeepADBTrustedNetwork.isCurrentNetworkTrusted(KeepADBService.this)) {
                                Log.i(TAG, "Wireless Debugging dropped on an untrusted Wi-Fi network; not auto re-enabling");
                                KeepADBDiagnostics.event(KeepADBService.this, "recovery_or_stop", "content_observer",
                                        "blocked", "untrusted_network");
                                // #446: the block used to be silent. Ask once per access point
                                // instead; the prompt itself never trusts anything.
                                KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(KeepADBService.this);
                            } else if (KeepADB.isAutomaticEnableBackoffBlocked()) {
                                // #496: the previous automatic attempt's write was accepted but
                                // its readback never flipped on -- wait for one of the recognized
                                // reset triggers instead of retrying on every ContentObserver
                                // callback, which is exactly the fast loop this bounds (a write
                                // the OS accepts and then reverts re-fires this very callback).
                                Log.i(TAG, "Automatic re-enable paused after a readback mismatch"
                                        + " (#496); waiting for a reset trigger");
                                KeepADBDiagnostics.event(KeepADBService.this, "recovery_or_stop", "content_observer",
                                        "blocked", "reason=recovery_backoff_active");
                            } else {
                                Log.i(TAG, "Wireless Debugging dropped while Wi-Fi connected; re-enabling...");
                                if (!KeepADB.setEnabled(KeepADBService.this, true, "content_observer",
                                        KeepADBService::isAutoEnableStillPermitted)) {
                                    Log.e(TAG, "Failed to auto-enable Wireless Debugging (WRITE_SECURE_SETTINGS missing?)");
                                    KeepADBNotification.showPermissionMissing(KeepADBService.this);
                                    return;
                                }
                            }
                        } else if (!KeepADB.isEnabled(KeepADBService.this)) {
                            if (KeepADB.consumeUserDisabled() || KeepADB.wasLastExplicitIntentOff(KeepADBService.this)) {
                                Log.i(TAG, "Wireless Debugging explicitly disabled by user; stopping service");
                                KeepADBDiagnostics.event(KeepADBService.this, "recovery_or_stop", "content_observer",
                                        "stopped", "user_disabled");
                                stop(KeepADBService.this);
                            } else {
                                Log.i(TAG, "Wireless Debugging dropped while Wi-Fi disconnected; keeping service alive for reconnect");
                                KeepADBDiagnostics.event(KeepADBService.this, "recovery_or_stop", "content_observer",
                                        "waiting_wifi", "keep_alive_active");
                            }
                        }
                    } else if (!KeepADB.isEnabled(KeepADBService.this)) {
                        stop(KeepADBService.this);
                    }
                    KeepADBNotification.refresh(KeepADBService.this);
                    KeepADBWidget.refreshAll(KeepADBService.this);
                }
            };
            getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(KeepADB.KEY),
                    false,
                    adbContentObserver);
            isRegisteredObserver = true;
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to register content observer for adb_wifi_enabled", e);
            KeepADBDiagnostics.event(this, "content_observer", "service", "failed", "registration_exception");
        }
    }

    private void unregisterAdbObserver() {
        if (!isRegisteredObserver || adbContentObserver == null) return;
        ContentObserver observer = adbContentObserver;
        isRegisteredObserver = false;
        adbContentObserver = null;
        try {
            getContentResolver().unregisterContentObserver(observer);
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed to unregister content observer", e);
        }
    }

    private void registerNetworkCallback() {
        if (isRegisteredNetworkCallback) return;
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            if (cm == null) return;
            NetworkRequest request = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build();
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    // #354: ConnectivityManager does not guarantee that onLost(old) is delivered
                    // before onAvailable(new). Relying on onLost alone (#313) left a window in
                    // which recheckAndEnable() below evaluated the *new* connection against a
                    // cache still describing the *old* one -- and a masked BSSID plus a matching
                    // SSID would then have inherited trust it never earned. Invalidating here as
                    // well closes that window from the other side. Deliberate consequence,
                    // decided on the issue: the #270 masked-BSSID convenience does not survive a
                    // reconnect, so a fresh BSSID verification is required afterwards. Must stay
                    // the first statement -- everything below can read the cache.
                    KeepADBTrustedNetwork.forgetVerifiedTrust();
                    if (network != null) {
                        availableWifiNetworks.add(network);
                    }
                    Log.d(TAG, "NetworkCallback: Wi-Fi network available");
                    // #310: advance the generation *before* recheckAndEnable() plans a new
                    // intent, so the new intent is stamped with the network it was planned for
                    // and every intent planned for the previous network is now stale.
                    long generation = KeepADB.noteNetworkChanged();
                    KeepADBDiagnostics.event(KeepADBService.this, "wifi_change", "network_callback",
                            "available", "network generation=" + generation);
                    recheckAndEnable();
                    checkNetworkTrustWhileActive();
                }

                @Override
                public void onLost(Network network) {
                    // #313: a masked reconnect must require fresh BSSID verification,
                    // even when foreground promotion has not completed.
                    KeepADBTrustedNetwork.forgetVerifiedTrust();
                    if (network != null) {
                        availableWifiNetworks.remove(network);
                    }
                    if (!availableWifiNetworks.isEmpty()) {
                        Log.d(TAG, "Ignoring network loss while another Wi-Fi network remains");
                        return;
                    }
                    // #310: unconditional, and ahead of the foregroundReady gate -- a pending
                    // automatic enable can outlive foreground promotion state, and losing the
                    // network invalidates it either way.
                    long generation = KeepADB.noteNetworkChanged();
                    if (!foregroundReady) {
                        Log.d(TAG, "Ignoring network loss before foreground promotion");
                        return;
                    }
                    Log.d(TAG, "NetworkCallback: Wi-Fi network lost");
                    KeepADBDiagnostics.event(KeepADBService.this, "wifi_change", "network_callback",
                            "lost", "network generation=" + generation);
                    KeepADBNotification.invalidateEndpoint(KeepADBService.this);
                    // #349: invalidate the local endpoint and its discovery generation before
                    // queueing remote cleanup. This prevents stale local work from re-registering
                    // the lost endpoint; markUnavailableAsync() then retains a failed cleanup's
                    // last-known registration and reports the error through the register listener.
                    // Refresh comes last so it cannot race ahead of either invalidation step.
                    KeepADBRegisterClient.markUnavailableAsync(KeepADBService.this);
                    KeepADBNotification.refresh(KeepADBService.this);
                    KeepADBWidget.refreshAll(KeepADBService.this);
                }

                // Issue #276: a same-SSID mesh roam (new BSSID, same physical Wi-Fi Network
                // object) fires neither onAvailable() nor onLost() above, so adbd rotating its
                // wireless-debugging port on such a roam previously went undetected until the
                // next 60s heartbeat (KeepADBNotification.verifyEndpointHealth() via
                // heartbeatNow()) -- KeepADBTileService picked up the new endpoint immediately
                // because it re-verifies on every onStartListening(), which is what made the
                // notification lag behind it. onCapabilitiesChanged() is documented (see
                // KeepADBNetwork) to fire at least once on registration for an already-existing
                // network, and is the established Android mechanism for reporting capability
                // changes -- including a roam -- on an already-tracked network, so re-verifying
                // here closes that gap. Throttled since it also fires for routine RSSI updates,
                // not just roams.
                //
                // #310: deliberately does NOT advance KeepADB's network generation. It cannot
                // distinguish a roam from an RSSI update, so counting it would cancel perfectly
                // valid automatic enables at random on a busy link -- the exact false-negative
                // the throttle above already exists to bound. A genuine network change reaches
                // onAvailable()/onLost(); a same-network roam to another BSSID is still caught
                // at write time by the trust re-check in isAutoEnableStillPermitted().
                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                    if (!foregroundReady) return;
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastCapabilitiesRecheckTime < CAPABILITIES_RECHECK_MIN_INTERVAL_MS) return;
                    lastCapabilitiesRecheckTime = now;
                    Log.d(TAG, "NetworkCallback: Wi-Fi capabilities changed; re-verifying cached endpoint");
                    KeepADBDiagnostics.event(KeepADBService.this, "wifi_change", "network_callback",
                            "capabilities_changed", "reverify_triggered");
                    KeepADBNotification.verifyEndpointHealth(KeepADBService.this);
                    checkNetworkTrustWhileActive();
                }
            };
            cm.registerNetworkCallback(request, networkCallback, new Handler(Looper.getMainLooper()));
            isRegisteredNetworkCallback = true;
            // #354: only now is an invalidator actually live, so only now may the masked-BSSID
            // fallback be offered. Deliberately after the register call -- the reverse order
            // would open a window that advertises an observer which isn't watching yet. A failed
            // registration falls into the catch below and leaves the flag false (fail closed).
            KeepADBTrustedNetwork.setVerifiedTrustObserverActive(true);
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to register network callback", e);
            KeepADBDiagnostics.event(this, "wifi_change", "network_callback", "failed", "registration_exception");
        }
    }

    private void unregisterNetworkCallback() {
        if (!isRegisteredNetworkCallback || networkCallback == null) return;
        ConnectivityManager.NetworkCallback callback = networkCallback;
        isRegisteredNetworkCallback = false;
        networkCallback = null;
        availableWifiNetworks.clear();
        // #354: ahead of the actual unregister call (and of anything that can throw), so the
        // fallback is withdrawn before the observer stops watching, never after.
        KeepADBTrustedNetwork.setVerifiedTrustObserverActive(false);
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            if (cm != null) {
                cm.unregisterNetworkCallback(callback);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed to unregister network callback", e);
        }
    }

    /**
     * #460: {@link #recheckAndEnable()} and the content observer above only ever ask the trust
     * question while Wireless Debugging is currently off and Keep-Alive is deciding whether to
     * turn it back on. That left a gap: roaming onto a new, untrusted access point (or one whose
     * identity has become unreadable) while it was already on raised nothing, and the user found
     * out only after the connection eventually dropped and the silent auto re-enable block hit.
     * This fires from the network callback regardless of that decision, reusing the exact same
     * throttled prompt in {@link KeepADBNetworkTrustPrompt} -- so it never re-alerts for an
     * access point (or identity-unavailable state) already answered or recently shown, and never
     * disables anything on its own; it is purely a notification.
     */
    private void checkNetworkTrustWhileActive() {
        if (!foregroundReady) return;
        if (!KeepADB.isEnabled(this)) return;
        if (!isWifiConnected(this)) return;
        if (KeepADBTrustedNetwork.isCurrentNetworkTrusted(this)) return;
        KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(this);
    }

    synchronized void recheckAndEnable() {
        if (!foregroundReady) {
            Log.d(TAG, "Ignoring recheck before foreground promotion");
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastRecheckTime < 300) {
            Log.d(TAG, "recheckAndEnable skipped (<300ms since last check)");
            return;
        }
        lastRecheckTime = now;
        KeepADBDiagnostics.event(this, "keep_alive_check", "service", "started",
                "wifiConnected=" + isWifiConnected(this) + " adbWifi=" + KeepADB.isEnabled(this));
        if (KeepADBPreferences.isKeepAliveEnabled(this) && !KeepADB.wasLastExplicitIntentOff(this)) {
            if (isWifiConnected(this)) {
                if (!KeepADB.isEnabled(this)) {
                    if (!KeepADBTrustedNetwork.isCurrentNetworkTrusted(this)) {
                        Log.i(TAG, "Wi-Fi connected but network is untrusted; not auto-enabling");
                        KeepADBDiagnostics.event(this, "keep_alive_check", "service", "blocked", "untrusted_network");
                        // #446: same prompt as the content-observer path above. It is throttled
                        // per access point, so the 60s heartbeat cannot turn it into spam.
                        KeepADBNetworkTrustPrompt.onBlockedByUntrustedNetwork(this);
                        KeepADBNotification.refresh(this);
                        KeepADBWidget.refreshAll(this);
                        return;
                    }
                    if (KeepADB.isAutomaticEnableBackoffBlocked()) {
                        // #496/#536: the previous automatic attempt's write was accepted but its
                        // readback never flipped on (e.g. Android's own Wireless Debugging
                        // pairing dialog was never confirmed). Deferred, not abandoned: this same
                        // heartbeat tick is what will fire the actual retry once the #536 two-stage
                        // window (~2 minutes, then capped at 5) elapses -- no separate re-trigger
                        // needed, and no write happens in the meantime.
                        Log.i(TAG, "Automatic re-enable paused after a readback mismatch (#496); "
                                + "retry deferred until the backoff window elapses");
                        KeepADBDiagnostics.event(this, "keep_alive_check", "service", "blocked",
                                "reason=recovery_backoff_active");
                        KeepADBNotification.refresh(this);
                        KeepADBWidget.refreshAll(this);
                        return;
                    }
                    // #536: distinct from the "blocked" outcome above -- the backoff window (if
                    // any) has elapsed and a fresh automatic attempt is due right now, whether
                    // this is the very first one or a scheduled retry.
                    KeepADBDiagnostics.event(this, "keep_alive_check", "service", "due",
                            "reason=recheck_due");
                    Log.i(TAG, "Auto-enabling Wireless Debugging (Wi-Fi connected)");
                    if (!KeepADB.setEnabled(this, true, "keep_alive_check",
                            KeepADBService::isAutoEnableStillPermitted)) {
                        Log.e(TAG, "Failed to auto-enable Wireless Debugging (WRITE_SECURE_SETTINGS missing?)");
                        KeepADBNotification.showPermissionMissing(this);
                        return;
                    }
                }
            } else {
                // #536: the third diagnosable waiting state -- no Wi-Fi transport at all, so
                // there is nothing yet to retry against. Distinct from "blocked" (network present,
                // backoff active) and from "due" (network present, attempting now).
                KeepADBDiagnostics.event(this, "keep_alive_check", "service", "waiting",
                        "reason=waiting_for_network");
            }
        }
        KeepADBNotification.refresh(this);
        KeepADBWidget.refreshAll(this);
    }

    ContentObserver getAdbContentObserverForTesting() {
        return adbContentObserver;
    }
}
