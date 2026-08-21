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
    private long lastRecheckTime = 0;

    static void sync(Context context) {
        if (KeepADBPreferences.isKeepAliveEnabled(context)) {
            start(context);
        } else {
            stop(context);
        }
    }

    static void start(Context context) {
        Intent intent = new Intent(context, KeepADBService.class);
        context.startForegroundService(intent);
    }

    static void stop(Context context) {
        Intent intent = new Intent(context, KeepADBService.class);
        context.stopService(intent);
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
        long lastHeartbeat = KeepADBPreferences.getServiceLastHeartbeat(this);
        if (lastHeartbeat > 0) {
            long gapMs = System.currentTimeMillis() - lastHeartbeat;
            Log.i(TAG, "onCreate: service (re)started, " + gapMs + "ms since last heartbeat"
                    + (gapMs > 90_000 ? " -- process was likely killed and restarted by the system" : ""));
        } else {
            Log.i(TAG, "onCreate: service starting for the first time (no prior heartbeat)");
        }
        heartbeatNow();
        KeepADB.consumeUserDisabled();
        registerAdbObserver();
        registerNetworkCallback();
        startHeartbeatTicker();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand startId=" + startId + " flags=" + flags);
        startForeground(KeepADBNotification.NOTIFICATION_ID, KeepADBNotification.getServiceNotification(this));
        heartbeatNow();
        recheckAndEnable();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        Log.w(TAG, "onDestroy: service stopping");
        stopHeartbeatTicker();
        unregisterAdbObserver();
        unregisterNetworkCallback();
        stopForeground(STOP_FOREGROUND_REMOVE);
        KeepADB.consumeUserDisabled();
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.w(TAG, "onTaskRemoved: app task was swiped away from recents");
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        Log.w(TAG, "onTrimMemory: level=" + level + " -- system is reclaiming memory, may kill this process next");
    }

    private void heartbeatNow() {
        KeepADBPreferences.setServiceLastHeartbeatNow(this);
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
                    if (KeepADBPreferences.isKeepAliveEnabled(KeepADBService.this)) {
                        if (isWifiConnected(KeepADBService.this) && !KeepADB.isEnabled(KeepADBService.this)) {
                            if (KeepADB.consumeUserDisabled()) {
                                Log.i(TAG, "Wireless Debugging manually disabled by user; ignoring keep-alive re-enable for this drop");
                                KeepADBNotification.refresh(KeepADBService.this);
                                KeepADBWidget.refreshAll(KeepADBService.this);
                                return;
                            } else {
                                Log.i(TAG, "Wireless Debugging dropped while Wi-Fi connected; re-enabling...");
                                if (!KeepADB.setEnabled(KeepADBService.this, true)) {
                                    Log.e(TAG, "Failed to auto-enable Wireless Debugging (WRITE_SECURE_SETTINGS missing?)");
                                    KeepADBNotification.showPermissionMissing(KeepADBService.this);
                                    return;
                                }
                            }
                        }
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
        }
    }

    private void unregisterAdbObserver() {
        if (!isRegisteredObserver || adbContentObserver == null) return;
        try {
            getContentResolver().unregisterContentObserver(adbContentObserver);
            isRegisteredObserver = false;
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
                    recheckAndEnable();
                }

                @Override
                public void onLost(Network network) {
                    Log.d(TAG, "NetworkCallback: Wi-Fi network lost");
                    KeepADBNotification.invalidateEndpoint();
                    KeepADBNotification.refresh(KeepADBService.this);
                    KeepADBWidget.refreshAll(KeepADBService.this);
                }
            };
            cm.registerNetworkCallback(request, networkCallback, new Handler(Looper.getMainLooper()));
            isRegisteredNetworkCallback = true;
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to register network callback", e);
        }
    }

    private void unregisterNetworkCallback() {
        if (!isRegisteredNetworkCallback || networkCallback == null) return;
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            if (cm != null) {
                cm.unregisterNetworkCallback(networkCallback);
            }
            isRegisteredNetworkCallback = false;
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed to unregister network callback", e);
        }
    }

    private synchronized void recheckAndEnable() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastRecheckTime < 300) {
            Log.d(TAG, "recheckAndEnable skipped (<300ms since last check)");
            return;
        }
        lastRecheckTime = now;
        if (KeepADBPreferences.isKeepAliveEnabled(this)) {
            if (isWifiConnected(this)) {
                if (!KeepADB.isEnabled(this)) {
                    Log.i(TAG, "Auto-enabling Wireless Debugging (Wi-Fi connected)");
                    if (!KeepADB.setEnabled(this, true)) {
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
