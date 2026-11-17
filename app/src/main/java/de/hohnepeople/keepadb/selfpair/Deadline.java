package de.hohnepeople.keepadb.selfpair;

import java.io.Closeable;
import java.io.IOException;
import java.net.Socket;

/**
 * Bounds a whole network operation (#962). A socket timeout only limits one read, so a peer that
 * keeps sending something could hold a connection open forever; this closes the socket when the
 * time is up, which fails every pending read and write.
 */
final class Deadline implements Closeable {
    private final Thread watcher;
    private volatile boolean expired;

    private Deadline(Socket socket, long millis) {
        watcher = new Thread(() -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                return;
            }
            expired = true;
            try {
                socket.close();
            } catch (IOException e) {
                // Nothing left to do.
            }
        }, "selfpair-deadline");
        watcher.setDaemon(true);
        watcher.start();
    }

    static Deadline closeAfter(Socket socket, long millis) {
        return new Deadline(socket, millis);
    }

    boolean expired() {
        return expired;
    }

    @Override
    public void close() {
        watcher.interrupt();
    }
}
