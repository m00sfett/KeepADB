package de.hohnepeople.keepadb.selfpair;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

import javax.crypto.KeyAgreement;

/**
 * A TLS 1.3 server for negative tests of {@link Tls13Channel} (#1041). It runs the real
 * handshake (TLS_AES_128_GCM_SHA256, secp256r1, rsa_pss_rsae_sha256, no client authentication)
 * with the app's own key schedule and record cipher, and breaks exactly one thing on request.
 * Without a fault the client completes its handshake, which the positive test pins: otherwise
 * every negative test could pass for the wrong reason.
 */
final class ScriptedTls13Server implements AutoCloseable {
    /** The one thing the server does wrong. */
    enum Fault {
        NONE,
        /** A second ServerHello, encrypted, where EncryptedExtensions belongs. */
        SECOND_SERVER_HELLO,
        /** No Certificate: CertificateVerify follows EncryptedExtensions. */
        OMIT_CERTIFICATE,
        /** Finished where CertificateVerify belongs. */
        FINISHED_BEFORE_CERTIFICATE_VERIFY,
        /** A CertificateVerify signature with one bit flipped. */
        CORRUPT_SIGNATURE,
        /** Signed with the client's context string instead of the server's. */
        WRONG_SIGNATURE_CONTEXT,
        /** A valid PSS signature labelled rsa_pkcs1_sha256, which the client did not offer. */
        UNOFFERED_SIGNATURE_SCHEME,
        /** A Finished MAC with one bit flipped. */
        CORRUPT_FINISHED,
        /** A key share whose y coordinate is off by one: not on the curve. */
        KEY_SHARE_OFF_CURVE,
        /** A key share in compressed form (format byte 3, 33 bytes). */
        KEY_SHARE_COMPRESSED,
        /** EncryptedExtensions as a plaintext record after the handshake keys are in place. */
        PLAINTEXT_AFTER_KEYS,
        /** A ChangeCipherSpec inside a protected record. */
        ENCRYPTED_CHANGE_CIPHER_SPEC,
        /** A protected record whose inner plaintext is all zeros: no content type. */
        NO_INNER_CONTENT_TYPE
    }

    private static final int HS_SERVER_HELLO = 2;
    private static final int HS_ENCRYPTED_EXTENSIONS = 8;
    private static final int HS_CERTIFICATE = 11;
    private static final int HS_CERTIFICATE_VERIFY = 15;
    private static final int HS_FINISHED = 20;

    private final ServerSocket listener;
    private final AdbIdentity identity;
    private final Fault fault;
    private final ByteArrayOutputStream transcript = new ByteArrayOutputStream();
    private final CompletableFuture<Void> done;

    ScriptedTls13Server(AdbIdentity identity, Fault fault) throws IOException {
        this.identity = identity;
        this.fault = fault;
        this.listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        this.done = CompletableFuture.runAsync(this::serve, TestThreads.EXECUTOR);
    }

    /** A client socket connected to this server, with a read timeout. */
    Socket connect() throws IOException {
        Socket socket = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
        socket.setSoTimeout(10_000);
        return socket;
    }

    @Override
    public void close() throws IOException {
        listener.close();
        done.cancel(true);
    }

    private void serve() {
        try (Socket peer = listener.accept()) {
            peer.setSoTimeout(10_000);
            handshake(peer.getInputStream(), peer.getOutputStream());
            // Keep the connection open until the client is done with it.
            InputStream in = peer.getInputStream();
            while (in.read() >= 0) {
                // drain
            }
        } catch (IOException | GeneralSecurityException e) {
            // The client hung up, which is what most tests expect.
        }
    }

    private void handshake(InputStream in, OutputStream out)
            throws IOException, GeneralSecurityException {
        byte[] clientHello = readPlainHandshake(in);
        transcript.write(clientHello);
        Parsed hello = parseClientHello(clientHello);

        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair ephemeral = generator.generateKeyPair();
        ECParameterSpec curve = ((ECPublicKey) ephemeral.getPublic()).getParams();
        ECPoint clientPoint = new ECPoint(
                new BigInteger(1, Arrays.copyOfRange(hello.keyShare, 1, 33)),
                new BigInteger(1, Arrays.copyOfRange(hello.keyShare, 33, 65)));

        byte[] serverHello = handshakeMessage(HS_SERVER_HELLO, serverHelloBody(
                hello.sessionId, keyShare((ECPublicKey) ephemeral.getPublic())));
        transcript.write(serverHello);
        writeRecord(out, 22, serverHello);
        if (fault == Fault.KEY_SHARE_OFF_CURVE || fault == Fault.KEY_SHARE_COMPRESSED) {
            return;
        }

        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(ephemeral.getPrivate());
        agreement.doPhase(KeyFactory.getInstance("EC")
                .generatePublic(new ECPublicKeySpec(clientPoint, curve)), true);
        byte[] handshakeSecret = Tls13KeySchedule.handshakeSecret(agreement.generateSecret());
        byte[] serverSecret = Tls13KeySchedule.deriveSecret(handshakeSecret, "s hs traffic",
                Tls13KeySchedule.sha256(transcript.toByteArray()));
        Tls13RecordCipher cipher = new Tls13RecordCipher(serverSecret);

        byte[] encryptedExtensions = handshakeMessage(HS_ENCRYPTED_EXTENSIONS, new byte[] {0, 0});
        switch (fault) {
            case SECOND_SERVER_HELLO:
                writeProtected(out, cipher, 22, serverHello);
                return;
            case PLAINTEXT_AFTER_KEYS:
                writeRecord(out, 22, encryptedExtensions);
                return;
            case ENCRYPTED_CHANGE_CIPHER_SPEC:
                writeProtected(out, cipher, 20, new byte[] {1});
                return;
            case NO_INNER_CONTENT_TYPE:
                // seal() appends the type byte; type 0 after zero content leaves only zeros.
                writeProtected(out, cipher, 0, new byte[] {0, 0});
                return;
            default:
                break;
        }
        transcript.write(encryptedExtensions);
        ByteArrayOutputStream flight = new ByteArrayOutputStream();
        flight.write(encryptedExtensions);

        if (fault != Fault.OMIT_CERTIFICATE) {
            byte[] der = identity.certificate.getEncoded();
            byte[] entry = Bytes.concat(u24(der.length), der, new byte[] {0, 0});
            byte[] certificate = handshakeMessage(HS_CERTIFICATE,
                    Bytes.concat(new byte[] {0}, u24(entry.length), entry));
            transcript.write(certificate);
            flight.write(certificate);
        }

        if (fault == Fault.FINISHED_BEFORE_CERTIFICATE_VERIFY) {
            flight.write(finished(serverSecret));
            writeProtected(out, cipher, 22, flight.toByteArray());
            return;
        }

        String context = fault == Fault.WRONG_SIGNATURE_CONTEXT
                ? "TLS 1.3, client CertificateVerify" : "TLS 1.3, server CertificateVerify";
        byte[] signature = sign(context, Tls13KeySchedule.sha256(transcript.toByteArray()));
        if (fault == Fault.CORRUPT_SIGNATURE) {
            signature[signature.length / 2] ^= 1;
        }
        int scheme = fault == Fault.UNOFFERED_SIGNATURE_SCHEME ? 0x0401 : 0x0804;
        byte[] verify = handshakeMessage(HS_CERTIFICATE_VERIFY,
                Bytes.concat(u16(scheme), u16(signature.length), signature));
        transcript.write(verify);
        flight.write(verify);

        byte[] finished = finished(serverSecret);
        if (fault == Fault.CORRUPT_FINISHED) {
            finished[finished.length - 1] ^= 1;
        }
        flight.write(finished);
        writeProtected(out, cipher, 22, flight.toByteArray());
    }

    private byte[] finished(byte[] serverSecret) throws GeneralSecurityException {
        return handshakeMessage(HS_FINISHED, Tls13KeySchedule.finished(serverSecret,
                Tls13KeySchedule.sha256(transcript.toByteArray())));
    }

    private byte[] keyShare(ECPublicKey key) {
        byte[] x = fixed(key.getW().getAffineX());
        byte[] y = fixed(key.getW().getAffineY());
        if (fault == Fault.KEY_SHARE_OFF_CURVE) {
            y = fixed(key.getW().getAffineY().add(BigInteger.ONE));
        }
        if (fault == Fault.KEY_SHARE_COMPRESSED) {
            return Bytes.concat(new byte[] {3}, x);
        }
        return Bytes.concat(new byte[] {4}, x, y);
    }

    private static byte[] serverHelloBody(byte[] sessionId, byte[] point) {
        byte[] random = new byte[32];
        Arrays.fill(random, (byte) 0x42);
        byte[] extensions = Bytes.concat(
                u16(43), u16(2), u16(0x0304),
                u16(51), u16(4 + point.length), u16(0x0017), u16(point.length), point);
        return Bytes.concat(u16(0x0303), random, new byte[] {(byte) sessionId.length}, sessionId,
                u16(0x1301), new byte[] {0}, u16(extensions.length), extensions);
    }

    private byte[] sign(String context, byte[] transcriptHash) throws GeneralSecurityException {
        byte[] padding = new byte[64];
        Arrays.fill(padding, (byte) 0x20);
        Signature signer = Signature.getInstance("RSASSA-PSS");
        signer.setParameter(new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256,
                32, 1));
        signer.initSign(identity.privateKey);
        signer.update(Bytes.concat(padding, context.getBytes(StandardCharsets.US_ASCII),
                new byte[1], transcriptHash));
        return signer.sign();
    }

    /** The client's first flight: one plaintext handshake record holding the ClientHello. */
    private static byte[] readPlainHandshake(InputStream in) throws IOException {
        byte[] header = Bytes.readFully(in, 5);
        if (header[0] != 22) {
            throw new IOException("expected a handshake record");
        }
        return Bytes.readFully(in, ((header[3] & 0xff) << 8) | (header[4] & 0xff));
    }

    private static Parsed parseClientHello(byte[] message) throws IOException {
        int p = 4 + 2 + 32;
        int sessionLength = message[p] & 0xff;
        byte[] sessionId = Arrays.copyOfRange(message, p + 1, p + 1 + sessionLength);
        p += 1 + sessionLength;
        p += 2 + u16At(message, p);
        p += 1 + (message[p] & 0xff);
        int end = p + 2 + u16At(message, p);
        p += 2;
        while (p < end) {
            int type = u16At(message, p);
            int length = u16At(message, p + 2);
            if (type == 51) {
                // client_shares length, group, key_exchange length, key_exchange
                int keyLength = u16At(message, p + 8);
                return new Parsed(sessionId,
                        Arrays.copyOfRange(message, p + 10, p + 10 + keyLength));
            }
            p += 4 + length;
        }
        throw new IOException("ClientHello without key_share");
    }

    private static void writeRecord(OutputStream out, int type, byte[] content)
            throws IOException {
        out.write(Bytes.concat(new byte[] {(byte) type, 3, 3}, u16(content.length), content));
        out.flush();
    }

    private static void writeProtected(OutputStream out, Tls13RecordCipher cipher, int type,
            byte[] content) throws IOException, GeneralSecurityException {
        out.write(cipher.seal(type, content, 0, content.length));
        out.flush();
    }

    private static byte[] handshakeMessage(int type, byte[] body) {
        return Bytes.concat(new byte[] {(byte) type}, u24(body.length), body);
    }

    private static byte[] u16(int value) {
        return new byte[] {(byte) (value >> 8), (byte) value};
    }

    private static byte[] u24(int value) {
        return new byte[] {(byte) (value >> 16), (byte) (value >> 8), (byte) value};
    }

    private static int u16At(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
    }

    private static byte[] fixed(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] out = new byte[32];
        int copy = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - copy, out, 32 - copy, copy);
        return out;
    }

    private static final class Parsed {
        final byte[] sessionId;
        final byte[] keyShare;

        Parsed(byte[] sessionId, byte[] keyShare) {
            this.sessionId = sessionId;
            this.keyShare = keyShare;
        }
    }
}
