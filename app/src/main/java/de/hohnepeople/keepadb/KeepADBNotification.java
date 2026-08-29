package de.hohnepeople.keepadb;

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
import android.util.Log;

/** Keeps the endpoint notification aligned with the live Wireless Debugging state. */
final class KeepADBNotification {
    private static final String TAG = "KeepADBNotification";
    static final String CHANNEL_ID = "keepadb_endpoint";
    static final int NOTIFICATION_ID = 1;
    private static final long RETRY_DELAY_INITIAL_MS = 2000;
    private static final long RETRY_DELAY_BACKOFF_MS = 5000;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private static KeepADBEndpoint endpoint;
    private static String currentHost;
    private static int currentPort;
    private static EndpointListener endpointListener;
    private static Runnable pendingRetryRunnable;
    private static int retryAttempt;

    interface EndpointListener {
        void onEndpoint(String host, int port);
        void onUnavailable();
    }

    static synchronized String getCurrentHost() {
        return currentHost;
    }

    static synchronized int getCurrentPort() {
        return currentPort;
    }

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
        Context appContext = KeepADBLocaleHelper.wrapContext(context.getApplicationContext());
        NotificationManager manager = appContext.getSystemService(NotificationManager.class);
        if (manager != null) {
            ensureChannel(appContext, manager);
        }
        if (currentHost != null && currentPort > 0) {
            return buildNotification(appContext, currentHost, currentPort);
        } else {
            return buildPlaceholderNotification(appContext,
                    appContext.getString(R.string.notification_title_searching),
                    appContext.getString(R.string.notification_text_searching));
        }
    }

    static synchronized void showPermissionMissing(Context context) {
        Context appContext = KeepADBLocaleHelper.wrapContext(context.getApplicationContext());
        NotificationManager manager = appContext.getSystemService(NotificationManager.class);
        if (manager == null) return;
        ensureChannel(appContext, manager);
        showPlaceholder(appContext, manager,
                appContext.getString(R.string.notification_permission_missing_title),
                appContext.getString(R.string.notification_permission_missing_text));
    }

    static synchronized void invalidateEndpoint(Context context) {
        KeepADBDiagnostics.event(context, "endpoint_invalidated", "network", "success", "cached_endpoint_cleared");
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

    static synchronized void verifyEndpointHealth(Context context) {
        Context appContext = KeepADBLocaleHelper.wrapContext(context.getApplicationContext());
        NotificationManager manager = appContext.getSystemService(NotificationManager.class);
        if (manager == null || currentHost == null || currentPort <= 0) return;
        verifyCachedEndpointAsync(appContext, manager, currentHost, currentPort);
    }

    static synchronized void refresh(Context context) {
        Context appContext = KeepADBLocaleHelper.wrapContext(context.getApplicationContext());
        NotificationManager manager = appContext.getSystemService(NotificationManager.class);
        if (manager == null) return;
        ensureChannel(appContext, manager);

        if (!KeepADB.isEnabled(appContext)) {
            stop(appContext, manager);
            return;
        }

        if (currentHost != null && currentPort > 0) {
            cancelRetryLocked();
            show(appContext, manager, currentHost, currentPort);
            if (endpointListener != null) {
                endpointListener.onEndpoint(currentHost, currentPort);
            }
            // The cached endpoint may be stale: adbd rotates its wireless-debugging port on
            // its own (e.g. after a Wi-Fi roam or an internal restart) without adb_wifi_enabled
            // changing, so the ContentObserver never fires. Without this check the notification
            // kept showing a dead port until the process was killed and relaunched.
            verifyCachedEndpointAsync(appContext, manager, currentHost, currentPort);
            // Otherwise, enabling the webhook while a connection is already cached never
            // reports it: updateEndpointAsync() is normally only reached from a fresh
            // discovery's onEndpoint() callback below, which won't fire again until the next
            // full reconnect (#118). This call is a cheap no-op once already registered.
            KeepADBRegisterClient.updateEndpointAsync(appContext, currentHost, currentPort);
            return;
        }

        if (endpointListener != null) endpointListener.onUnavailable();
        if (KeepADBPreferences.isKeepAliveEnabled(appContext)) {
            showPlaceholder(appContext, manager,
                    appContext.getString(R.string.notification_title_searching),
                    appContext.getString(R.string.notification_text_searching));
        }

        cancelRetryLocked();
        startDiscoveryDirectLocked(appContext, manager);
    }

    private static void verifyCachedEndpointAsync(Context appContext, NotificationManager manager, String host, int port) {
        new Thread(() -> {
            boolean reachable = KeepADBEndpoint.isPortReachable(host, port, 500);
            if (reachable) return;
            Log.w(TAG, "Cached endpoint " + host + ":" + port + " no longer reachable; invalidating and rediscovering");
            synchronized (KeepADBNotification.class) {
                if (!host.equals(currentHost) || port != currentPort) {
                    return; // superseded by a newer refresh/discovery in the meantime
                }
                currentHost = null;
                currentPort = 0;
                if (endpointListener != null) {
                    endpointListener.onUnavailable();
                }
                if (KeepADBPreferences.isKeepAliveEnabled(appContext)) {
                    showPlaceholder(appContext, manager,
                            appContext.getString(R.string.notification_title_searching),
                            appContext.getString(R.string.notification_text_searching));
                }
                cancelRetryLocked();
                startDiscoveryDirectLocked(appContext, manager);
            }
        }, "KeepADBEndpointVerify").start();
    }

    private static void cancelRetryLocked() {
        if (pendingRetryRunnable != null) {
            MAIN_HANDLER.removeCallbacks(pendingRetryRunnable);
            pendingRetryRunnable = null;
        }
    }

    private static void scheduleRetryLocked(Context appContext, NotificationManager manager) {
        if (!KeepADB.isEnabled(appContext)) {
            retryAttempt = 0;
            return;
        }
        cancelRetryLocked();
        long delay = (retryAttempt == 0) ? RETRY_DELAY_INITIAL_MS : RETRY_DELAY_BACKOFF_MS;
        retryAttempt++;
        pendingRetryRunnable = () -> {
            synchronized (KeepADBNotification.class) {
                pendingRetryRunnable = null;
                if (!KeepADB.isEnabled(appContext)) {
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
        if (endpoint == null) endpoint = new KeepADBEndpoint(appContext);
        endpoint.discover(new KeepADBEndpoint.Listener() {
            @Override
            public void onEndpoint(String host, int port) {
                KeepADBDiagnostics.event(appContext, "endpoint_discovered", "nsd_or_probe", "success",
                        "host=" + host + " port=" + port);
                EndpointListener listener;
                synchronized (KeepADBNotification.class) {
                    currentHost = host;
                    currentPort = port;
                    retryAttempt = 0;
                    cancelRetryLocked();
                    listener = endpointListener;
                }
                KeepADBTileService.requestRefresh(appContext);
                if (listener != null) {
                    listener.onEndpoint(host, port);
                }
                show(appContext, manager, host, port);
                KeepADBRegisterClient.updateEndpointAsync(appContext, host, port);
            }

            @Override
            public void onUnavailable() {
                KeepADBDiagnostics.event(appContext, "endpoint_discovered", "nsd_or_probe", "unavailable",
                        "no_live_endpoint");
                EndpointListener listener;
                synchronized (KeepADBNotification.class) {
                    currentHost = null;
                    currentPort = 0;
                    listener = endpointListener;
                    scheduleRetryLocked(appContext, manager);
                }
                if (listener != null) {
                    listener.onUnavailable();
                }
                if (KeepADBPreferences.isKeepAliveEnabled(appContext)) {
                    showPlaceholder(appContext, manager,
                            appContext.getString(R.string.notification_title_searching),
                            appContext.getString(R.string.notification_text_searching));
                } else {
                    manager.cancel(NOTIFICATION_ID);
                }
                KeepADBRegisterClient.markUnavailableAsync(appContext);
            }
        });
    }

    private static synchronized void stop(Context context, NotificationManager manager) {
        KeepADBDiagnostics.event(context, "notification_removed", "notification", "success", "wireless_debugging_off");
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
        KeepADBRegisterClient.markUnavailableAsync(context.getApplicationContext());
    }

    private static void ensureChannel(Context context, NotificationManager manager) {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(context.getString(R.string.notification_channel_desc));
        manager.createNotificationChannel(channel);
    }

    private static boolean hasNotificationPermission(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static void show(Context context, NotificationManager manager, String host, int port) {
        if (!hasNotificationPermission(context)) {
            KeepADBDiagnostics.event(context, "notification_removed", "notification", "skipped", "permission_missing");
            return;
        }
        if (KeepADBPreferences.isNotificationHidden(context)) {
            manager.cancel(NOTIFICATION_ID);
            return;
        }
        Notification notification = buildNotification(context, host, port);
        manager.notify(NOTIFICATION_ID, notification);
    }

    private static void showPlaceholder(Context context, NotificationManager manager, String title, String text) {
        if (!hasNotificationPermission(context)) {
            return;
        }
        if (KeepADBPreferences.isNotificationHidden(context)) {
            manager.cancel(NOTIFICATION_ID);
            return;
        }
        Notification notification = buildPlaceholderNotification(context, title, text);
        manager.notify(NOTIFICATION_ID, notification);
    }

    private static Notification buildNotification(Context context, String host, int port) {
        String displayHost = (host != null && host.contains(":") && !host.startsWith("[")) ? "[" + host + "]" : host;
        String title = context.getString(R.string.notification_title_active);
        String content = context.getString(R.string.notification_text_active, port, displayHost);
        SpannableString styled = new SpannableString(content);
        int portStart = content.indexOf(String.valueOf(port));
        if (portStart >= 0) {
            styled.setSpan(new StyleSpan(Typeface.BOLD), portStart, portStart + String.valueOf(port).length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        Intent intent = new Intent(context, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_keepadb)
                .setContentTitle(title)
                .setContentText(styled)
                .setStyle(new Notification.BigTextStyle().bigText(styled))
                .setContentIntent(pendingIntent)
                .addAction(disableAction(context))
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
        Notification.Builder builder = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_keepadb)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setShowWhen(false);
        if (KeepADB.isEnabled(context)
                && !context.getString(R.string.notification_permission_missing_title).equals(title)) {
            builder.addAction(disableAction(context));
        }
        return builder.build();
    }

    static Notification.Action disableAction(Context context) {
        Intent intent = new Intent(context, KeepADBReceiver.class)
                .setAction(KeepADBReceiver.ACTION_DISABLE);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder(
                null,
                context.getString(R.string.notification_action_disable),
                pendingIntent).build();
    }
}
