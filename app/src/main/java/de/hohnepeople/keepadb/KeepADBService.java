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
    private long lastRecheckTime = 0;

    static void sync(Context context) {
        boolean shouldRun = KeepADBPreferences.isKeepAliveEnabled(context)
                && (KeepADB.isEnabled(context) || !KeepADB.wasLastExplicitIntentOff(context));
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

    static boolean isWifiConnected(Context context) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        if (cm != null) {
            for (Network network : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    return true;
                }
            }
        }
        return KeepADBEndpoint.getWifiIpAddress(context) != null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
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
        if (foregroundReady && KeepADB.isEnabled(this)) {
            KeepADBNotification.verifyEndpointHealth(this);
        }
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
                            } else {
                                Log.i(TAG, "Wireless Debugging dropped while Wi-Fi connected; re-enabling...");
                                if (!KeepADB.setEnabled(KeepADBService.this, true, "content_observer")) {
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
                    Log.d(TAG, "NetworkCallback: Wi-Fi network available");
                    KeepADBDiagnostics.event(KeepADBService.this, "wifi_change", "network_callback", "available", "network");
                    recheckAndEnable();
                }

                @Override
                public void onLost(Network network) {
                    if (!foregroundReady) {
                        Log.d(TAG, "Ignoring network loss before foreground promotion");
                        return;
                    }
                    Log.d(TAG, "NetworkCallback: Wi-Fi network lost");
                    KeepADBDiagnostics.event(KeepADBService.this, "wifi_change", "network_callback", "lost", "network");
                    KeepADBNotification.invalidateEndpoint(KeepADBService.this);
                    KeepADBNotification.refresh(KeepADBService.this);
                    KeepADBWidget.refreshAll(KeepADBService.this);
                }
            };
            cm.registerNetworkCallback(request, networkCallback, new Handler(Looper.getMainLooper()));
            isRegisteredNetworkCallback = true;
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
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            if (cm != null) {
                cm.unregisterNetworkCallback(callback);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed to unregister network callback", e);
        }
    }

    private synchronized void recheckAndEnable() {
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
                    Log.i(TAG, "Auto-enabling Wireless Debugging (Wi-Fi connected)");
                    if (!KeepADB.setEnabled(this, true, "keep_alive_check")) {
                        Log.e(TAG, "Failed to auto-enable Wireless Debugging (WRITE_SECURE_SETTINGS missing?)");
                        KeepADBNotification.showPermissionMissing(this);
                        return;
                    }
                }
            }
        }
        KeepADBNotification.refresh(this);
        KeepADBWidget.refreshAll(this);
    }
}
