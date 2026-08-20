package de.moos.wifiadb;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StyleSpan;

/** Keeps the endpoint notification aligned with the live Wireless Debugging state. */
final class AdbWifiNotification {
    private static final String CHANNEL_ID = "adb_wifi_endpoint";
    private static final int NOTIFICATION_ID = 1;

    private static AdbWifiEndpoint endpoint;
    private static String currentHost;
    private static int currentPort;
    private static EndpointListener endpointListener;

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

    static synchronized void refresh(Context context) {
        Context appContext = context.getApplicationContext();
        NotificationManager manager = appContext.getSystemService(NotificationManager.class);
        if (manager == null) return;
        ensureChannel(manager);

        if (!AdbWifi.isEnabled(appContext)) {
            stop(manager);
            return;
        }

        if (endpoint == null) endpoint = new AdbWifiEndpoint(appContext);
        currentHost = null;
        currentPort = 0;
        if (endpointListener != null) endpointListener.onUnavailable();
        endpoint.discover(new AdbWifiEndpoint.Listener() {
            @Override
            public void onEndpoint(String host, int port) {
                currentHost = host;
                currentPort = port;
                if (endpointListener != null) endpointListener.onEndpoint(host, port);
                show(appContext, manager, host, port);
            }

            @Override
            public void onUnavailable() {
                currentHost = null;
                currentPort = 0;
                if (endpointListener != null) endpointListener.onUnavailable();
                manager.cancel(NOTIFICATION_ID);
            }
        });
    }

    private static synchronized void stop(NotificationManager manager) {
        if (endpoint != null) {
            endpoint.stop();
            endpoint = null;
        }
        currentHost = null;
        currentPort = 0;
        if (endpointListener != null) endpointListener.onUnavailable();
        manager.cancel(NOTIFICATION_ID);
    }

    private static void ensureChannel(NotificationManager manager) {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "WLAN-ADB-Endpoint", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Aktueller Port und die IP-Adresse von WLAN-ADB");
        manager.createNotificationChannel(channel);
    }

    private static void show(Context context, NotificationManager manager, String host, int port) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        String title = "WLAN-ADB: Port " + port + " @ " + host;
        String content = "Port " + port + " @ " + host;
        SpannableString styled = new SpannableString(content);
        int portStart = "Port ".length();
        styled.setSpan(new StyleSpan(Typeface.BOLD), portStart, portStart + String.valueOf(port).length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        Notification notification = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_adb)
                .setContentTitle(title)
                .setContentText(styled)
                .setStyle(new Notification.BigTextStyle().bigText(styled))
                .setOngoing(true)
                .setShowWhen(false)
                .build();
        manager.notify(NOTIFICATION_ID, notification);
    }
}
