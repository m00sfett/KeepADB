package de.hohnepeople.keepadb.selfpair;

import java.io.IOException;
import java.net.Socket;

/**
 * Cancellation of one self-pairing attempt (#1038). A flag alone is not enough: the pairing and
 * grant clients spend most of their time blocked in a connect or a read, where they never look
 * at it. So {@link #cancel} also closes the socket that is currently in use, and the one step
 * that must not happen after a cancel, sending the grant command, runs under the same lock as
 * the cancel itself.
 */
final class Cancellation {
    /** Thrown where an operation stops because the attempt was cancelled. */
    static final class CancelledException extends IOException {
        CancelledException() {
            super("cancelled");
        }
    }

    /** An action that must either run completely before a cancel or not at all. */
    interface Step {
        void run() throws IOException;
    }

    private boolean cancelled;
    private Socket active;

    /** Cancels; closes the socket in use, if any. Safe to call more than once. */
    void cancel() {
        Socket socket;
        synchronized (this) {
            cancelled = true;
            socket = active;
            active = null;
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
     * Runs {@code step} unless the attempt is cancelled. A cancel waits for a running step, so
     * either the step completed before the cancel or it never starts.
     */
    synchronized void runUnlessCancelled(Step step) throws IOException {
        if (cancelled) {
            throw new CancelledException();
        }
        step.run();
    }
}
