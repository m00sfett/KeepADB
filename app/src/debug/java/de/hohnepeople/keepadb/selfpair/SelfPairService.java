package de.hohnepeople.keepadb.selfpair;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.nsd.NsdManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * STAGE 1 TEST HARNESS (#962), debug build only. Keeps the process alive while the user is in
 * Android's pairing dialog, takes the six-digit code through a notification reply, pairs, runs
 * the grant and reports each step to logcat and to {@link SelfPairActivity}.
 */
public final class SelfPairService extends Service {
    static final String ACTION_START = "de.hohnepeople.keepadb.selfpair.START";
    static final String ACTION_CODE = "de.hohnepeople.keepadb.selfpair.CODE";
    static final String ACTION_STOP = "de.hohnepeople.keepadb.selfpair.STOP";
    static final String EXTRA_CODE = "code";
    static final String EXTRA_PAIR_PORT = "pair_port";
    static final String EXTRA_CONNECT_PORT = "connect_port";

    private static final String TAG = "KeepADBSelfPair";
    private static final String CHANNEL = "selfpair_debug";
    private static final int NOTIFICATION_ID = 962;
    private static final String KEY_NAME = "KeepADB";
    private static final long CONNECT_PORT_WAIT_MS = 15_000;

    private static final List<String> LOG = new ArrayList<>();

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Object ports = new Object();
    private LocalAdbDiscovery pairingDiscovery;
    private LocalAdbDiscovery connectDiscovery;
    private int pairPort;
    private int connectPort;
    private boolean busy;

    static synchronized String logText() {
        return String.join("\n", LOG);
    }

    private static synchronized void log(String line) {
        Log.i(TAG, line);
        LOG.add(line);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            start(intent);
        } else if (ACTION_CODE.equals(action)) {
            Bundle reply = RemoteInput.getResultsFromIntent(intent);
            CharSequence code = reply == null ? null : reply.getCharSequence(EXTRA_CODE);
            onCode(code == null ? "" : code.toString().trim());
        }
        return START_NOT_STICKY;
    }

    private void start(Intent intent) {
        synchronized (SelfPairService.class) {
            LOG.clear();
        }
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "Self-pairing test",
                        NotificationManager.IMPORTANCE_HIGH));
        startForeground(NOTIFICATION_ID, notification("Looking for the pairing dialog…", false),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        log("started on API " + Build.VERSION.SDK_INT + " (" + Build.MANUFACTURER + " "
                + Build.MODEL + ")");
        stopDiscovery();
        synchronized (ports) {
            pairPort = intent.getIntExtra(EXTRA_PAIR_PORT, 0);
            connectPort = intent.getIntExtra(EXTRA_CONNECT_PORT, 0);
        }
        NsdManager nsd = getSystemService(NsdManager.class);
        pairingDiscovery = new LocalAdbDiscovery(nsd, LocalAdbDiscovery.PAIRING, port -> {
            synchronized (ports) {
                pairPort = port;
            }
            log("pairing service found on port " + port);
            notifyStatus("Pairing dialog found. Enter the six-digit code.", true);
        });
        connectDiscovery = new LocalAdbDiscovery(nsd, LocalAdbDiscovery.CONNECT, port -> {
            synchronized (ports) {
                connectPort = port;
                ports.notifyAll();
            }
            log("connect service found on port " + port);
        });
        pairingDiscovery.start();
        connectDiscovery.start();
        String code = intent.getStringExtra(EXTRA_CODE);
        if (code != null) {
            onCode(code);
        }
    }

    private void onCode(String code) {
        final int port;
        synchronized (ports) {
            port = pairPort;
            if (busy) {
                return;
            }
            busy = true;
        }
        worker.execute(() -> {
            try {
                run(port, code);
            } finally {
                synchronized (ports) {
                    busy = false;
                }
            }
        });
    }

    private void run(int port, String code) {
        if (!code.matches("\\d{6}")) {
            fail("code must be six digits", true);
            return;
        }
        if (port == 0) {
            fail("no pairing service found; is the pairing dialog open?", true);
            return;
        }
        long start = System.nanoTime();
        try {
            AdbIdentity identity = AdbIdentity.create();
            log("ephemeral key created after " + elapsed(start) + " ms");
            new AdbPairingClient(PlatformTlsChannel.FACTORY).pair(port, code, identity, KEY_NAME);
            log("paired after " + elapsed(start) + " ms");
            notifyStatus("Paired. Granting the permission…", false);

            int connect = awaitConnectPort();
            if (connect == 0) {
                fail("paired, but no connect service found", false);
                return;
            }
            String output = new AdbGrantClient(PlatformTlsChannel.FACTORY)
                    .grant(connect, identity, getPackageName());
            log("grant command finished after " + elapsed(start) + " ms, output: \""
                    + output + "\"");
            boolean granted = checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                    == PackageManager.PERMISSION_GRANTED;
            log("RESULT granted=" + granted);
            notifyStatus(granted ? "Done: permission granted." : "Grant failed: " + output,
                    false);
            stopDiscovery();
            stopForeground(STOP_FOREGROUND_DETACH);
            stopSelf();
        } catch (AdbPairingClient.WrongCodeException e) {
            fail("wrong pairing code", true);
        } catch (Exception e) {
            Log.w(TAG, "self-pairing failed", e);
            fail(e.getClass().getSimpleName() + ": " + e.getMessage(), true);
        }
    }

    private int awaitConnectPort() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CONNECT_PORT_WAIT_MS);
        synchronized (ports) {
            while (connectPort == 0) {
                long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (left <= 0) {
                    break;
                }
                ports.wait(left);
            }
            return connectPort;
        }
    }

    private void fail(String reason, boolean retry) {
        log("FAILED " + reason);
        notifyStatus("Failed: " + reason, retry);
    }

    private static long elapsed(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private void notifyStatus(String text, boolean withReply) {
        getSystemService(NotificationManager.class)
                .notify(NOTIFICATION_ID, notification(text, withReply));
    }

    private Notification notification(String text, boolean withReply) {
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("KeepADB self-pairing test")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setOnlyAlertOnce(false);
        if (withReply) {
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                            ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent reply = PendingIntent.getService(this, 0,
                    new Intent(this, SelfPairService.class).setAction(ACTION_CODE), flags);
            builder.addAction(new Notification.Action.Builder(null, "Enter code", reply)
                    .addRemoteInput(new RemoteInput.Builder(EXTRA_CODE)
                            .setLabel("Six-digit pairing code").build())
                    .build());
        }
        return builder.build();
    }

    private void stopDiscovery() {
        if (pairingDiscovery != null) {
            pairingDiscovery.stop();
            pairingDiscovery = null;
        }
        if (connectDiscovery != null) {
            connectDiscovery.stop();
            connectDiscovery = null;
        }
    }

    @Override
    public void onDestroy() {
        stopDiscovery();
        worker.shutdown();
        super.onDestroy();
    }

    static Intent startIntent(Context context) {
        return new Intent(context, SelfPairService.class).setAction(ACTION_START);
    }
}
