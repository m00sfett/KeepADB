package de.hohnepeople.keepadb.selfpair;

import java.io.IOException;
import java.net.Socket;

/**
 * Cancellation of one self-pairing attempt (#1038). A flag alone is not enough: the pairing and
 * grant clients spend most of their time blocked in a connect or a read, where they never look
 * at it. So {@link #cancel} also closes the socket that is currently in use, and the one step
 * that must not begin after a cancel, sending the grant command, is decided under the same lock
 * as the cancel itself.
 *
 * <p>The lock is only ever held for a decision, never across I/O, so a cancel from the main
 * thread returns at once even while a write is stuck (Codex review of #1038).
 */
final class Cancellation {
    /** Thrown where an operation stops because the attempt was cancelled. */
    static final class CancelledException extends IOException {
        CancelledException() {
            super("cancelled");
        }
    }

    private boolean cancelled;
    private boolean commandStarted;
    private Socket active;

    /** Cancels; closes the socket in use, if any. Safe to call more than once; never blocks. */
    void cancel() {
        Socket socket;
        synchronized (this) {
            cancelled = true;
            socket = active;
            active = null;
            notifyAll();
        }
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
                // Closed anyway.
            }
        }
    }

    synchronized boolean isCancelled() {
        return cancelled;
    }

    /** Makes {@code socket} the one a cancel closes; refuses if already cancelled. */
    synchronized void attach(Socket socket) throws CancelledException {
        if (cancelled) {
            throw new CancelledException();
        }
        active = socket;
    }

    synchronized void detach(Socket socket) {
        if (active == socket) {
            active = null;
        }
    }

    /**
     * Marks the grant command as started, unless the attempt is cancelled. Called right before
     * the command is written: a cancel either comes first, and the command is never written, or
     * comes after, and the command may have reached the peer (a cancel during the write closes
     * the socket, which cuts it off at some point).
     */
    synchronized void startCommand() throws CancelledException {
        if (cancelled) {
            throw new CancelledException();
        }
        commandStarted = true;
    }

    /** Whether the grant command may have reached the peer. */
    synchronized boolean commandStarted() {
        return commandStarted;
    }

    /** Waits up to {@code millis}; returns early on a cancel. */
    synchronized void pause(long millis) throws InterruptedException {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        while (!cancelled) {
            long left = (deadline - System.nanoTime()) / 1_000_000L;
            if (left <= 0) {
                return;
            }
            wait(left);
        }
    }
}
