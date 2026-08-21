package de.moos.wifiadb;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StyleSpan;

/** Keeps the endpoint notification aligned with the live Wireless Debugging state. */
final class AdbWifiNotification {
    static final String CHANNEL_ID = "adb_wifi_endpoint";
    static final int NOTIFICATION_ID = 1;
    private static final long RETRY_DELAY_INITIAL_MS = 2000;
    private static final long RETRY_DELAY_BACKOFF_MS = 5000;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private static AdbWifiEndpoint endpoint;
    private static String currentHost;
    private static int currentPort;
    private static EndpointListener endpointListener;
    private static Runnable pendingRetryRunnable;
    private static int retryAttempt;

    interface EndpointListener {
        void onEndpoint(String host, int port);
        void onUnavailable();
    }

    private AdbWifiNotification() {}

    static synchronized void setEndpointListener(EndpointListener listener) {
        endpointListener = listener;
        if (currentHost != null) {
            listener.onEndpoint(currentHost, currentPort);
        } else {
            listener.onUnavailable();
        }
    }

    static synchronized void clearEndpointListener() {
        endpointListener = null;
    }

    static synchronized Notification getServiceNotification(Context context) {
        Context appContext = context.getApplicationContext();
        NotificationManager manager = appContext.getSystemService(NotificationManager.class);
        if (manager != null) {
            ensureChannel(manager);
        }
        if (currentHost != null && currentPort > 0) {
            return buildNotification(appContext, currentHost, currentPort);
        } else {
            return buildPlaceholderNotification(appContext, "WLAN-ADB aktiv halten", "Überwacht WLAN-Verbindung und WLAN-ADB-Status");
        }
    }

    static synchronized void showPermissionMissing(Context context) {
        Context appContext = context.getApplicationContext();
        NotificationManager manager = appContext.getSystemService(NotificationManager.class);
        if (manager == null) return;
        ensureChannel(manager);
        showPlaceholder(appContext, manager, "WLAN-ADB: Berechtigung fehlt",
                "WRITE_SECURE_SETTINGS am PC per adb shell pm grant vergeben");
    }

    static synchronized void invalidateEndpoint() {
        cancelRetryLocked();
        retryAttempt = 0;
        if (endpoint != null) {
            endpoint.stop();
        }
        currentHost = null;
        currentPort = 0;
        if (endpointListener != null) {
            endpointListener.onUnavailable();
        }
    }

    static synchronized void refresh(Context context) {
        Context appContext = context.getApplicationContext();
        NotificationManager manager = appContext.getSystemService(NotificationManager.class);
        if (manager == null) return;
        ensureChannel(manager);

        if (!AdbWifi.isEnabled(appContext)) {
            stop(appContext, manager);
            return;
        }

        if (currentHost != null && currentPort > 0) {
            cancelRetryLocked();
            show(appContext, manager, currentHost, currentPort);
            if (endpointListener != null) {
                endpointListener.onEndpoint(currentHost, currentPort);
            }
            return;
        }

        if (endpointListener != null) endpointListener.onUnavailable();
        if (AdbWifiPreferences.isKeepAliveEnabled(appContext)) {
            showPlaceholder(appContext, manager, "WLAN-ADB: Endpoint wird gesucht …", "Endpoint wird im lokalen Netzwerk aufgelöst");
        }

        cancelRetryLocked();
        startDiscoveryDirectLocked(appContext, manager);
    }

    private static void cancelRetryLocked() {
        if (pendingRetryRunnable != null) {
            MAIN_HANDLER.removeCallbacks(pendingRetryRunnable);
            pendingRetryRunnable = null;
        }
    }

    private static void scheduleRetryLocked(Context appContext, NotificationManager manager) {
        if (!AdbWifi.isEnabled(appContext)) {
            retryAttempt = 0;
            return;
        }
        cancelRetryLocked();
        long delay = (retryAttempt == 0) ? RETRY_DELAY_INITIAL_MS : RETRY_DELAY_BACKOFF_MS;
        retryAttempt++;
        pendingRetryRunnable = () -> {
            synchronized (AdbWifiNotification.class) {
                pendingRetryRunnable = null;
                if (!AdbWifi.isEnabled(appContext)) {
                    retryAttempt = 0;
                    return;
                }
                if (currentHost != null && currentPort > 0) {
                    retryAttempt = 0;
                    return;
                }
                startDiscoveryDirectLocked(appContext, manager);
            }
        };
        MAIN_HANDLER.postDelayed(pendingRetryRunnable, delay);
    }

    private static void startDiscoveryDirectLocked(Context appContext, NotificationManager manager) {
        if (endpoint == null) endpoint = new AdbWifiEndpoint(appContext);
        endpoint.discover(new AdbWifiEndpoint.Listener() {
            @Override
            public void onEndpoint(String host, int port) {
                EndpointListener listener;
                synchronized (AdbWifiNotification.class) {
                    currentHost = host;
                    currentPort = port;
                    retryAttempt = 0;
                    cancelRetryLocked();
                    listener = endpointListener;
                }
                if (listener != null) {
                    listener.onEndpoint(host, port);
                }
                show(appContext, manager, host, port);
                AdbWifiRegisterClient.updateEndpointAsync(appContext, host, port);
            }

            @Override
            public void onUnavailable() {
                EndpointListener listener;
                synchronized (AdbWifiNotification.class) {
                    currentHost = null;
                    currentPort = 0;
                    listener = endpointListener;
                    scheduleRetryLocked(appContext, manager);
                }
                if (listener != null) {
                    listener.onUnavailable();
                }
                if (AdbWifiPreferences.isKeepAliveEnabled(appContext)) {
                    showPlaceholder(appContext, manager, "WLAN-ADB: Endpoint wird gesucht …", "Endpoint wird im lokalen Netzwerk aufgelöst");
                } else {
                    manager.cancel(NOTIFICATION_ID);
                }
                AdbWifiRegisterClient.markUnavailableAsync(appContext);
            }
        });
    }

    private static synchronized void stop(Context context, NotificationManager manager) {
        cancelRetryLocked();
        retryAttempt = 0;
        if (endpoint != null) {
            endpoint.stop();
            endpoint = null;
        }
        currentHost = null;
        currentPort = 0;
        if (endpointListener != null) endpointListener.onUnavailable();
        manager.cancel(NOTIFICATION_ID);
        AdbWifiRegisterClient.markUnavailableAsync(context.getApplicationContext());
    }

    private static void ensureChannel(NotificationManager manager) {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "WLAN-ADB-Endpoint", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Aktueller Port und die IP-Adresse von WLAN-ADB");
        manager.createNotificationChannel(channel);
    }

    private static boolean hasNotificationPermission(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static void show(Context context, NotificationManager manager, String host, int port) {
        if (!hasNotificationPermission(context)) {
            return;
        }
        Notification notification = buildNotification(context, host, port);
        manager.notify(NOTIFICATION_ID, notification);
    }

    private static void showPlaceholder(Context context, NotificationManager manager, String title, String text) {
        if (!hasNotificationPermission(context)) {
            return;
        }
        Notification notification = buildPlaceholderNotification(context, title, text);
        manager.notify(NOTIFICATION_ID, notification);
    }

    private static Notification buildNotification(Context context, String host, int port) {
        String title = "WLAN-ADB: Port " + port + " @ " + host;
        String content = "Port " + port + " @ " + host;
        SpannableString styled = new SpannableString(content);
        int portStart = "Port ".length();
        styled.setSpan(new StyleSpan(Typeface.BOLD), portStart, portStart + String.valueOf(port).length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        Intent intent = new Intent(context, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_adb)
                .setContentTitle(title)
                .setContentText(styled)
                .setStyle(new Notification.BigTextStyle().bigText(styled))
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setShowWhen(false)
                .build();
    }

    private static Notification buildPlaceholderNotification(Context context, String title, String text) {
        Intent intent = new Intent(context, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_adb)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setShowWhen(false)
                .build();
    }
}
