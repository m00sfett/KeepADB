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
import android.provider.Settings;
import android.util.Log;

import java.io.IOException;
import java.net.ConnectException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * TEST HARNESS (#962), debug build only. Keeps the process alive while the user is in
 * Android's pairing dialog, takes the six-digit code through a notification reply, pairs, runs
 * the grant and reports each step to logcat and to {@link SelfPairActivity}.
 */
public final class SelfPairService extends Service {
    static final String ACTION_START = "de.hohnepeople.keepadb.selfpair.START";
    static final String ACTION_CODE = "de.hohnepeople.keepadb.selfpair.CODE";
    static final String ACTION_STOP = "de.hohnepeople.keepadb.selfpair.STOP";
    static final String EXTRA_CODE = "code";
    static final String EXTRA_LEAVE_ON = "leave_on";

    private static final String TAG = "KeepADBSelfPair";
    private static final String CHANNEL = "selfpair_debug";
    private static final int NOTIFICATION_ID = 962;
    private static final String KEY_NAME = "KeepADB";
    private static final long CONNECT_PORT_WAIT_MS = 3_000;
    private static final int GRANT_ATTEMPTS = 3;
    private static final long GRANT_RETRY_PAUSE_MS = 500;

    private static final List<String> LOG = new ArrayList<>();

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Object ports = new Object();
    private LocalAdbDiscovery pairingDiscovery;
    private LocalAdbDiscovery connectDiscovery;
    private boolean leaveOn;
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
        synchronized (ports) {
            if (busy) {
                // An attempt is running; its settings must not change underneath it.
                return;
            }
        }
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
        leaveOn = intent.getBooleanExtra(EXTRA_LEAVE_ON, false);
        NsdManager nsd = getSystemService(NsdManager.class);
        pairingDiscovery = new LocalAdbDiscovery(nsd, LocalAdbDiscovery.PAIRING, () -> {
            List<Integer> found = pairingDiscovery == null
                    ? new ArrayList<>() : pairingDiscovery.candidatePorts();
            log("pairing services: " + found);
            if (!found.isEmpty()) {
                notifyStatus("Pairing dialog found. Enter the six-digit code.", true);
            }
        });
        connectDiscovery = new LocalAdbDiscovery(nsd, LocalAdbDiscovery.CONNECT, () -> {
            synchronized (ports) {
                ports.notifyAll();
            }
            log("connect services: " + (connectDiscovery == null
                    ? "[]" : connectDiscovery.candidatePorts().toString()));
        });
        pairingDiscovery.start();
        connectDiscovery.start();
    }

    private void onCode(String code) {
        synchronized (ports) {
            if (busy) {
                return;
            }
            busy = true;
        }
        worker.execute(() -> {
            try {
                run(code);
            } finally {
                synchronized (ports) {
                    busy = false;
                }
            }
        });
    }

    private void run(String code) {
        if (!code.matches("\\d{6}")) {
            fail("code must be six digits", true);
            return;
        }
        LocalAdbDiscovery discovery = pairingDiscovery;
        List<Integer> pairPorts = discovery == null
                ? new ArrayList<>() : discovery.candidatePorts();
        final boolean leaveOnAfterwards = leaveOn;
        if (pairPorts.isEmpty()) {
            fail("no pairing service found; is the pairing dialog open?", true);
            return;
        }
        long start = System.nanoTime();
        try {
            AdbIdentity identity = AdbIdentity.create();
            log("ephemeral key created after " + elapsed(start) + " ms, fingerprint "
                    + identity.fingerprint());
            pair(pairPorts, code, identity);
            log("paired after " + elapsed(start) + " ms");
            notifyStatus("Paired. Granting the permission…", false);

            String output;
            try {
                output = sanitize(grant(identity));
            } catch (IOException | GeneralSecurityException | InterruptedException e) {
                // adbd may have run the command and dropped the connection afterwards, or the
                // service is being stopped; the permission itself decides, below.
                Log.w(TAG, "grant connection failed", e);
                output = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            log("grant command finished after " + elapsed(start) + " ms, output: \""
                    + output + "\"");
            boolean granted = checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                    == PackageManager.PERMISSION_GRANTED;
            log("RESULT granted=" + granted);
            String state = "";
            if (granted && !leaveOnAfterwards) {
                // The user switched Wireless debugging on only for the pairing.
                boolean written =
                        Settings.Global.putInt(getContentResolver(), "adb_wifi_enabled", 0);
                // An unreadable setting counts as "not confirmed off".
                boolean off = written && Settings.Global.getInt(getContentResolver(),
                        "adb_wifi_enabled", -1) == 0;
                log("Wireless debugging off again: written=" + written + ", off=" + off);
                state = off ? "" : " Wireless debugging could not be switched off.";
            }
            notifyStatus(granted
                    ? "Done: permission granted." + state + " The entry \"KeepADB\" with "
                            + "fingerprint " + identity.fingerprint()
                            + " under paired devices can be removed."
                    : "Paired, but the grant failed: " + output, !granted);
            if (granted) {
                stopDiscovery();
                stopForeground(STOP_FOREGROUND_DETACH);
                stopSelf();
            }
        } catch (AdbPairingClient.WrongCodeException e) {
            fail("wrong pairing code", true);
        } catch (Exception e) {
            Log.w(TAG, "self-pairing failed", e);
            fail(e.getClass().getSimpleName() + ": " + e.getMessage(), true);
        }
    }

    /** mDNS may still list a dialog that was closed; a refused connection means try the next. */
    private void pair(List<Integer> candidates, String code, AdbIdentity identity)
            throws IOException, GeneralSecurityException {
        ConnectException refused = null;
        for (int port : candidates) {
            try {
                new AdbPairingClient(Tls13Channel.FACTORY).pair(port, code, identity, KEY_NAME);
                return;
            } catch (ConnectException e) {
                log("pairing port " + port + " is stale");
                refused = e;
            }
        }
        throw refused;
    }

    private String grant(AdbIdentity identity)
            throws IOException, GeneralSecurityException, InterruptedException {
        List<Integer> candidates = awaitConnectPorts();
        if (candidates.isEmpty()) {
            throw new IOException("paired, but no connect service found");
        }
        IOException last = null;
        for (int port : candidates) {
            for (int attempt = 1; attempt <= GRANT_ATTEMPTS; attempt++) {
                try {
                    return new AdbGrantClient(Tls13Channel.FACTORY)
                            .grant(port, identity, getPackageName());
                } catch (ConnectException e) {
                    log("connect port " + port + " is stale");
                    last = e;
                    break;
                } catch (IOException e) {
                    // adbd drops a new connection while it still tears down the previous one.
                    log("grant attempt " + attempt + " on port " + port + " failed: " + e);
                    last = e;
                    Thread.sleep(GRANT_RETRY_PAUSE_MS);
                }
            }
        }
        throw last;
    }

    private List<Integer> awaitConnectPorts() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CONNECT_PORT_WAIT_MS);
        synchronized (ports) {
            while (connectDiscovery.candidatePorts().isEmpty()) {
                long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (left <= 0) {
                    break;
                }
                ports.wait(left);
            }
        }
        List<Integer> found = connectDiscovery.candidatePorts();
        if (found.isEmpty()) {
            long start = System.nanoTime();
            found = LoopbackAdbScan.find();
            log("mDNS reported no connect service; loopback scan found " + found + " in "
                    + elapsed(start) + " ms");
        }
        return found;
    }

    /** Peer-controlled text: keep it short and printable before it reaches a log or the UI. */
    private static String sanitize(String text) {
        String printable = text.replaceAll("[^\\x20-\\x7e]", " ").trim();
        return printable.length() > 200 ? printable.substring(0, 200) + "…" : printable;
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
        // Interrupts waits; a running connection ends at its own deadline at the latest.
        worker.shutdownNow();
        super.onDestroy();
    }

    static Intent startIntent(Context context) {
        return new Intent(context, SelfPairService.class).setAction(ACTION_START);
    }
}
