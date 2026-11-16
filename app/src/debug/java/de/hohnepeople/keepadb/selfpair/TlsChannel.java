package de.hohnepeople.keepadb.selfpair;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.security.GeneralSecurityException;

/**
 * A TLS 1.3 connection to {@code adbd}, reduced to what self-pairing needs (#962). The seam
 * between stage 1 (platform TLS plus a restricted platform call for the exporter) and stage 2
 * (own client on public APIs only).
 */
interface TlsChannel extends Closeable {
    InputStream input();

    OutputStream output();

    /** RFC 8446 section 7.5 exporter with an empty context. */
    byte[] exportKeyingMaterial(String label, int length) throws GeneralSecurityException;

    /** Runs the TLS handshake over an already connected socket and takes ownership of it. */
    interface Factory {
        TlsChannel handshake(Socket socket, AdbIdentity identity)
                throws IOException, GeneralSecurityException;
    }
}
