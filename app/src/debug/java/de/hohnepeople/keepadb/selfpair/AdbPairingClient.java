package de.hohnepeople.keepadb.selfpair;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;

import javax.crypto.AEADBadTagException;

/**
 * Client side of ADB's pairing protocol against {@code adbd} on this device (#962); mirrors
 * {@code pairing_connection.cpp}. Registers the identity's public key with the device.
 */
final class AdbPairingClient {
    /** The pairing code was wrong (or the peer is not the dialog the code came from). */
    static final class WrongCodeException extends IOException {
        WrongCodeException() {
            super("pairing code rejected");
        }
    }

    private static final byte[] CLIENT_NAME = Bytes.concat(Bytes.ascii("adb pair client"),
            new byte[1]);
    private static final byte[] SERVER_NAME = Bytes.concat(Bytes.ascii("adb pair server"),
            new byte[1]);
    private static final String EXPORT_LABEL = "adb-label\0";
    private static final int EXPORT_SIZE = 64;
    private static final byte VERSION = 1;
    private static final byte TYPE_SPAKE2_MSG = 0;
    private static final byte TYPE_PEER_INFO = 1;
    private static final byte PEER_INFO_RSA_PUB_KEY = 0;
    private static final int PEER_INFO_SIZE = 8192;
    private static final int MAX_PAYLOAD = 2 * PEER_INFO_SIZE;
    private static final int TIMEOUT_MS = 10_000;

    private final TlsChannel.Factory tls;

    AdbPairingClient(TlsChannel.Factory tls) {
        this.tls = tls;
    }

    void pair(int port, String code, AdbIdentity identity, String name)
            throws IOException, GeneralSecurityException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port),
                    TIMEOUT_MS);
            socket.setSoTimeout(TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            try (TlsChannel channel = tls.handshake(socket, identity)) {
                exchange(channel, code, identity.adbPublicKey(name));
            }
        } finally {
            socket.close();
        }
    }

    private static void exchange(TlsChannel channel, String code, byte[] adbPublicKey)
            throws IOException, GeneralSecurityException {
        InputStream in = channel.input();
        OutputStream out = channel.output();
        byte[] password = Bytes.concat(Bytes.ascii(code),
                channel.exportKeyingMaterial(EXPORT_LABEL, EXPORT_SIZE));

        Spake2 spake = new Spake2(CLIENT_NAME, SERVER_NAME);
        writePacket(out, TYPE_SPAKE2_MSG, spake.generateMessage(password));
        byte[] theirMessage = readPacket(in, TYPE_SPAKE2_MSG);
        PairingCipher cipher;
        try {
            cipher = new PairingCipher(spake.processMessage(theirMessage));
        } catch (IllegalArgumentException e) {
            throw new IOException("peer sent an invalid SPAKE2 message", e);
        }

        byte[] peerInfo = new byte[PEER_INFO_SIZE];
        peerInfo[0] = PEER_INFO_RSA_PUB_KEY;
        if (adbPublicKey.length > PEER_INFO_SIZE - 1) {
            throw new IllegalArgumentException("public key too long");
        }
        System.arraycopy(adbPublicKey, 0, peerInfo, 1, adbPublicKey.length);
        writePacket(out, TYPE_PEER_INFO, cipher.encrypt(peerInfo));

        byte[] sealed = readPacket(in, TYPE_PEER_INFO);
        byte[] theirInfo;
        try {
            theirInfo = cipher.decrypt(sealed);
        } catch (AEADBadTagException e) {
            throw new WrongCodeException();
        }
        if (theirInfo.length != PEER_INFO_SIZE) {
            throw new IOException("unexpected peer info size " + theirInfo.length);
        }
    }

    private static void writePacket(OutputStream out, byte type, byte[] payload)
            throws IOException {
        ByteBuffer header = ByteBuffer.allocate(6);
        header.put(VERSION).put(type).putInt(payload.length);
        out.write(Bytes.concat(header.array(), payload));
        out.flush();
    }

    private static byte[] readPacket(InputStream in, byte expectedType) throws IOException {
        ByteBuffer header = ByteBuffer.wrap(Bytes.readFully(in, 6));
        byte version = header.get();
        byte type = header.get();
        int length = header.getInt();
        if (version != VERSION || type != expectedType || length <= 0 || length > MAX_PAYLOAD) {
            throw new IOException("unexpected pairing packet: version " + version + ", type "
                    + type + ", length " + length);
        }
        return Bytes.readFully(in, length);
    }
}
