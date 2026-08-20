package de.moos.wifiadb;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

/**
 * Foreground service that monitors Wi-Fi connectivity and wireless debugging state
 * to automatically re-enable WLAN-ADB and push new endpoints to the register.
 */
public class AdbWifiService extends Service {
    private static final String TAG = "AdbWifiService";

    private ContentObserver adbContentObserver;
    private ConnectivityManager.NetworkCallback networkCallback;
    private boolean isRegisteredObserver = false;
    private boolean isRegisteredNetworkCallback = false;

    static void sync(Context context) {
        if (AdbWifiPreferences.isKeepAliveEnabled(context)) {
            start(context);
        } else {
            stop(context);
        }
    }

    static void start(Context context) {
        Intent intent = new Intent(context, AdbWifiService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    static void stop(Context context) {
        Intent intent = new Intent(context, AdbWifiService.class);
        context.stopService(intent);
    }

    static boolean isWifiConnected(Context context) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        if (cm == null) return false;
        Network activeNetwork = cm.getActiveNetwork();
        if (activeNetwork == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(activeNetwork);
        return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        registerAdbObserver();
        registerNetworkCallback();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(AdbWifiNotification.NOTIFICATION_ID, AdbWifiNotification.getServiceNotification(this));
        recheckAndEnable();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        unregisterAdbObserver();
        unregisterNetworkCallback();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    private void registerAdbObserver() {
        if (isRegisteredObserver) return;
        try {
            adbContentObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    super.onChange(selfChange, uri);
                    Log.d(TAG, "ContentObserver: adb_wifi_enabled changed");
                    if (AdbWifiPreferences.isKeepAliveEnabled(AdbWifiService.this)) {
                        if (isWifiConnected(AdbWifiService.this) && !AdbWifi.isEnabled(AdbWifiService.this)) {
                            Log.i(TAG, "WLAN-ADB dropped while Wi-Fi connected; re-enabling...");
                            AdbWifi.setEnabled(AdbWifiService.this, true);
                        }
                    }
                    AdbWifiNotification.refresh(AdbWifiService.this);
                    AdbWifiWidget.refreshAll(AdbWifiService.this);
                }
            };
            getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(AdbWifi.KEY),
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
                    AdbWifiNotification.refresh(AdbWifiService.this);
                    AdbWifiWidget.refreshAll(AdbWifiService.this);
                }
            };
            cm.registerNetworkCallback(request, networkCallback);
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

    private void recheckAndEnable() {
        if (AdbWifiPreferences.isKeepAliveEnabled(this)) {
            if (isWifiConnected(this)) {
                if (!AdbWifi.isEnabled(this)) {
                    Log.i(TAG, "Auto-enabling WLAN-ADB (Wi-Fi connected)");
                    AdbWifi.setEnabled(this, true);
                }
            }
        }
        AdbWifiNotification.refresh(this);
        AdbWifiWidget.refreshAll(this);
    }
}
