package de.hohnepeople.keepadb.selfpair;

import android.net.nsd.NsdManager;
import android.util.Log;

import java.io.IOException;
import java.net.ConnectException;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * One attempt to let the app grant itself {@code WRITE_SECURE_SETTINGS} through the device's own
 * Wireless debugging (#962). This is the only door into the package: it finds the device's ADB
 * services, pairs with the code the user read off Android's pairing dialog, and runs the one
 * grant command.
 *
 * <p>It decides nothing about the outcome. Whether the permission is there, and what happens to
 * Wireless debugging afterwards, is the caller's business and has to be checked by the caller
 * after every {@link #run}, however it ended: {@code adbd} may execute the command and drop the
 * connection before saying so.
 */
public final class SelfPairSession {
    /** Why an attempt did not complete. */
    public enum Failure {
        /** The pairing dialog is not open (any more). */
        NO_PAIRING_DIALOG,
        /** The code is not the one the dialog shows. */
        WRONG_CODE,
        /** Pairing failed for another reason. */
        PAIRING_FAILED,
        /** Paired, but no ADB service was found to run the command. */
        NO_ADB_SERVICE,
        /** Paired, but the command's connection failed; it may still have run. */
        GRANT_UNCONFIRMED,
        /** The command ran and printed an error. */
        GRANT_REFUSED,
        /** Cancelled before the grant ran. */
        CANCELLED
    }

    /** The outcome of {@link #run}. */
    public static final class Result {
        /** {@code null} if the command ran and printed nothing. */
        public final Failure failure;
        /** Fingerprint of the key as Android lists it under paired devices; {@code null} if not paired. */
        public final String fingerprint;

        public Result(Failure failure, String fingerprint) {
            this.failure = failure;
            this.fingerprint = fingerprint;
        }
    }

    /** Called on an arbitrary thread whenever the pairing dialog appears or disappears. */
    public interface Listener {
        void onPairingDialogChanged(boolean visible);
    }

    /**
     * Called on the thread of {@link #run} the moment the key is registered with the device,
     * before the grant command can be sent. Whatever the caller needs in place before a grant
     * may happen belongs here, and it can still call the grant off.
     */
    public interface PairedListener {
        /** Returns whether the grant may go ahead; {@code false} ends the attempt as cancelled. */
        boolean onPaired(String fingerprint);
    }

    private static final String TAG = "KeepADBSelfPair";
    private static final String KEY_NAME = "KeepADB";
    private static final long CONNECT_PORT_WAIT_MS = 3_000;
    private static final int GRANT_ATTEMPTS = 3;
    private static final long GRANT_RETRY_PAUSE_MS = 500;

    private final String packageName;
    private final LocalAdbDiscovery pairing;
    private final LocalAdbDiscovery connect;
    private final Object connectChanged = new Object();
    private volatile boolean cancelled;
    private volatile String pairedFingerprint;

    public SelfPairSession(NsdManager nsd, String packageName, Listener listener) {
        this.packageName = packageName;
        this.pairing = new LocalAdbDiscovery(nsd, LocalAdbDiscovery.PAIRING,
                () -> listener.onPairingDialogChanged(pairingDialogVisible()));
        this.connect = new LocalAdbDiscovery(nsd, LocalAdbDiscovery.CONNECT, () -> {
            synchronized (connectChanged) {
                connectChanged.notifyAll();
            }
        });
    }

    /** Starts looking for the device's own ADB services. */
    public void start() {
        pairing.start();
        connect.start();
    }

    /** Stops looking; safe to call more than once. */
    public void stop() {
        pairing.stop();
        connect.stop();
    }

    /** Cancels an ongoing attempt. */
    public void cancel() {
        cancelled = true;
        synchronized (connectChanged) {
            connectChanged.notifyAll();
        }
    }

    /** Fingerprint of the key if pairing succeeded, even if grant failed or was cancelled. */
    public String pairedFingerprint() {
        return pairedFingerprint;
    }

    public boolean pairingDialogVisible() {
        return !pairing.candidatePorts().isEmpty();
    }

    /** Six ASCII digits, as Android's pairing dialog shows them. */
    public static boolean isCode(String text) {
        return text != null && text.matches("[0-9]{6}");
    }

    /**
     * Pairs with {@code code}, tells {@code onPaired} (may be null), and runs the grant. Blocks;
     * call it off the main thread. One call at a time.
     */
    public Result run(String code, PairedListener onPaired) throws InterruptedException {
        if (!isCode(code)) {
            return new Result(Failure.WRONG_CODE, null);
        }
        List<Integer> pairPorts = pairing.candidatePorts();
        if (pairPorts.isEmpty()) {
            return new Result(Failure.NO_PAIRING_DIALOG, null);
        }
        AdbIdentity identity;
        String fingerprint;
        try {
            identity = AdbIdentity.create();
            fingerprint = identity.fingerprint();
        } catch (GeneralSecurityException e) {
            Log.w(TAG, "cannot create a key", e);
            return new Result(Failure.PAIRING_FAILED, null);
        }
        Failure pairFailure = pair(pairPorts, code, identity);
        if (pairFailure != null) {
            return new Result(pairFailure, null);
        }
        pairedFingerprint = fingerprint;
        if (onPaired != null && !onPaired.onPaired(fingerprint)) {
            return new Result(Failure.CANCELLED, fingerprint);
        }
        if (cancelled) {
            return new Result(Failure.CANCELLED, fingerprint);
        }
        return new Result(grant(identity), fingerprint);
    }

    /** mDNS may still list a dialog that was closed; a refused connection means try the next. */
    private Failure pair(List<Integer> candidates, String code, AdbIdentity identity) {
        Failure failure = Failure.NO_PAIRING_DIALOG;
        for (int port : candidates) {
            try {
                new AdbPairingClient(Tls13Channel.FACTORY).pair(port, code, identity, KEY_NAME);
                return null;
            } catch (ConnectException e) {
                failure = Failure.NO_PAIRING_DIALOG;
            } catch (AdbPairingClient.WrongCodeException e) {
                return Failure.WRONG_CODE;
            } catch (IOException | GeneralSecurityException e) {
                Log.w(TAG, "pairing failed: " + e);
                return Failure.PAIRING_FAILED;
            }
        }
        return failure;
    }

    private Failure grant(AdbIdentity identity) throws InterruptedException {
        if (cancelled) {
            return Failure.CANCELLED;
        }
        List<Integer> candidates = awaitConnectPorts();
        if (cancelled) {
            return Failure.CANCELLED;
        }
        if (candidates.isEmpty()) {
            Log.w(TAG, "no ADB connect service found");
            return Failure.NO_ADB_SERVICE;
        }
        Failure failure = Failure.NO_ADB_SERVICE;
        for (int port : candidates) {
            for (int attempt = 1; attempt <= GRANT_ATTEMPTS; attempt++) {
                if (cancelled) {
                    return Failure.CANCELLED;
                }
                try {
                    String output = new AdbGrantClient(Tls13Channel.FACTORY)
                            .grant(port, identity, packageName);
                    if (output.isEmpty()) {
                        return null;
                    }
                    // Peer text stays out of the log's detail; its length says enough.
                    Log.w(TAG, "grant printed " + output.length() + " characters");
                    return Failure.GRANT_REFUSED;
                } catch (ConnectException e) {
                    Log.w(TAG, "grant connect refused on port " + port);
                    break;
                } catch (IOException | GeneralSecurityException e) {
                    // adbd drops a new connection while it still tears down the previous one,
                    // and it may drop this one after the command already ran.
                    Log.w(TAG, "grant attempt " + attempt + " failed: " + e);
                    failure = Failure.GRANT_UNCONFIRMED;
                    Thread.sleep(GRANT_RETRY_PAUSE_MS);
                }
            }
        }
        return failure;
    }

    private List<Integer> awaitConnectPorts() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CONNECT_PORT_WAIT_MS);
        synchronized (connectChanged) {
            while (connect.candidatePorts().isEmpty() && !cancelled) {
                long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (left <= 0) {
                    break;
                }
                connectChanged.wait(left);
            }
        }
        if (cancelled) {
            return Collections.emptyList();
        }
        List<Integer> found = connect.candidatePorts();
        // Android 11 never reports a connect service that was announced before the discovery
        // started; then look for adbd on loopback directly.
        return found.isEmpty() ? LoopbackAdbScan.find(() -> cancelled) : found;
    }
}
