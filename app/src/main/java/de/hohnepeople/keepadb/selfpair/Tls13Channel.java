package de.hohnepeople.keepadb.selfpair;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECFieldFp;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.EllipticCurve;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.crypto.KeyAgreement;

/**
 * A minimal TLS 1.3 client (RFC 8446) built on public crypto APIs only (#962, stage 2). It exists
 * for one reason: ADB pairing needs the TLS exporter, which the platform socket does not expose
 * publicly.
 *
 * <p>Deliberately narrow. One cipher suite (TLS_AES_128_GCM_SHA256), one group (secp256r1), RSA-PSS
 * signatures, no resumption, no early data, no HelloRetryRequest, no KeyUpdate. Anything outside
 * that fails the connection. The peer is always {@code adbd} on the same device, which runs
 * BoringSSL and supports all of it.
 *
 * <p>The peer's certificate is not validated against anything: {@code adbd} presents a
 * self-signed certificate nobody can check. The client only verifies that the peer holds the
 * certificate's key. Pairing is authenticated one layer up, by SPAKE2 bound to this session's
 * exporter; the connection that runs the grant carries no secret and authenticates the client.
 */
final class Tls13Channel implements TlsChannel {
    static final Factory FACTORY = Tls13Channel::new;

    private static final int TYPE_CHANGE_CIPHER_SPEC = 20;
    private static final int TYPE_ALERT = 21;
    private static final int TYPE_HANDSHAKE = 22;
    private static final int TYPE_APPLICATION_DATA = 23;

    private static final int HS_SERVER_HELLO = 2;
    private static final int HS_NEW_SESSION_TICKET = 4;
    private static final int HS_ENCRYPTED_EXTENSIONS = 8;
    private static final int HS_CERTIFICATE = 11;
    private static final int HS_CERTIFICATE_REQUEST = 13;
    private static final int HS_CERTIFICATE_VERIFY = 15;
    private static final int HS_FINISHED = 20;

    private static final int EXT_SUPPORTED_GROUPS = 10;
    private static final int EXT_SIGNATURE_ALGORITHMS = 13;
    private static final int EXT_SUPPORTED_VERSIONS = 43;
    private static final int EXT_KEY_SHARE = 51;

    private static final int SUITE_AES_128_GCM_SHA256 = 0x1301;
    private static final int GROUP_SECP256R1 = 0x0017;
    private static final int SIG_RSA_PSS_RSAE_SHA256 = 0x0804;
    private static final int SIG_RSA_PSS_RSAE_SHA384 = 0x0805;
    private static final int SIG_RSA_PSS_RSAE_SHA512 = 0x0806;
    private static final int TLS_1_2 = 0x0303;
    private static final int TLS_1_3 = 0x0304;

    private static final int ALERT_CLOSE_NOTIFY = 0;
    private static final int ALERT_USER_CANCELED = 90;

    private static final int MAX_HANDSHAKE_MESSAGE = 64 * 1024;
    private static final int MAX_CHANGE_CIPHER_SPECS = 4;
    private static final int MAX_IDLE_RECORDS = 16;
    private static final int MIN_RSA_BITS = 2048;
    private static final byte[] HELLO_RETRY_REQUEST_RANDOM = Ed25519.hex(
            "cf21ad74e59a6111be1d8c021e65b891c2a211167abb8c5e079e09e2c8a8339c");

    private final Socket socket;
    private final InputStream rawIn;
    private final OutputStream rawOut;
    private final ByteArrayOutputStream transcript = new ByteArrayOutputStream();
    private final ByteArrayOutputStream handshakeBuffer = new ByteArrayOutputStream();
    private Tls13RecordCipher readCipher;
    private Tls13RecordCipher writeCipher;
    private byte[] exporterMasterSecret;
    private X509Certificate peerCertificate;
    private boolean handshakeComplete;
    private int changeCipherSpecs;
    private byte[] applicationData = new byte[0];
    private int applicationOffset;
    private boolean endOfStream;
    private int idleRecords;
    private boolean closed;

    private Tls13Channel(Socket socket, AdbIdentity identity)
            throws IOException, GeneralSecurityException {
        this.socket = socket;
        this.rawIn = socket.getInputStream();
        this.rawOut = socket.getOutputStream();
        handshake(identity);
    }

    // ---------------------------------------------------------------- handshake

    private void handshake(AdbIdentity identity) throws IOException, GeneralSecurityException {
        SecureRandom random = new SecureRandom();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"), random);
        KeyPair ephemeral = generator.generateKeyPair();
        ECParameterSpec curve = ((ECPublicKey) ephemeral.getPublic()).getParams();
        byte[] sessionId = new byte[32];
        random.nextBytes(sessionId);

        byte[] clientHello = clientHello(random, sessionId, (ECPublicKey) ephemeral.getPublic());
        transcript.write(clientHello, 0, clientHello.length);
        writePlainRecord(TYPE_HANDSHAKE, clientHello);

        byte[] serverHello = expectHandshake(HS_SERVER_HELLO);
        ECPoint peerPoint = parseServerHello(body(serverHello), sessionId, curve);
        transcript.write(serverHello, 0, serverHello.length);

        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(ephemeral.getPrivate());
        agreement.doPhase(KeyFactory.getInstance("EC")
                .generatePublic(new ECPublicKeySpec(peerPoint, curve)), true);
        byte[] handshakeSecret = Tls13KeySchedule.handshakeSecret(agreement.generateSecret());
        byte[] helloHash = transcriptHash();
        byte[] clientHandshakeSecret =
                Tls13KeySchedule.deriveSecret(handshakeSecret, "c hs traffic", helloHash);
        byte[] serverHandshakeSecret =
                Tls13KeySchedule.deriveSecret(handshakeSecret, "s hs traffic", helloHash);
        switchReadKeys(serverHandshakeSecret);

        byte[] encryptedExtensions = expectHandshake(HS_ENCRYPTED_EXTENSIONS);
        parseEncryptedExtensions(body(encryptedExtensions));
        transcript.write(encryptedExtensions, 0, encryptedExtensions.length);

        byte[] message = readHandshakeMessage();
        byte[] certificateRequestContext = null;
        if (type(message) == HS_CERTIFICATE_REQUEST) {
            certificateRequestContext = parseCertificateRequest(body(message));
            transcript.write(message, 0, message.length);
            message = readHandshakeMessage();
        }
        requireType(message, HS_CERTIFICATE);
        peerCertificate = parseCertificate(body(message));
        transcript.write(message, 0, message.length);

        message = expectHandshake(HS_CERTIFICATE_VERIFY);
        verifyServerSignature(body(message), peerCertificate.getPublicKey(), transcriptHash());
        transcript.write(message, 0, message.length);

        message = expectHandshake(HS_FINISHED);
        byte[] expected = Tls13KeySchedule.finished(serverHandshakeSecret, transcriptHash());
        if (!MessageDigest.isEqual(expected, body(message))) {
            throw new IOException("server Finished does not verify");
        }
        transcript.write(message, 0, message.length);

        byte[] masterSecret = Tls13KeySchedule.masterSecret(handshakeSecret);
        byte[] serverFinishedHash = transcriptHash();
        byte[] clientApplicationSecret =
                Tls13KeySchedule.deriveSecret(masterSecret, "c ap traffic", serverFinishedHash);
        byte[] serverApplicationSecret =
                Tls13KeySchedule.deriveSecret(masterSecret, "s ap traffic", serverFinishedHash);
        exporterMasterSecret =
                Tls13KeySchedule.deriveSecret(masterSecret, "exp master", serverFinishedHash);

        writeCipher = new Tls13RecordCipher(clientHandshakeSecret);
        ByteArrayOutputStream flight = new ByteArrayOutputStream();
        if (certificateRequestContext != null) {
            byte[] certificate = clientCertificate(certificateRequestContext, identity);
            transcript.write(certificate, 0, certificate.length);
            flight.write(certificate, 0, certificate.length);
            byte[] verify = clientCertificateVerify(identity, transcriptHash());
            transcript.write(verify, 0, verify.length);
            flight.write(verify, 0, verify.length);
        }
        byte[] finished = handshakeMessage(HS_FINISHED,
                Tls13KeySchedule.finished(clientHandshakeSecret, transcriptHash()));
        flight.write(finished, 0, finished.length);
        writeProtected(TYPE_HANDSHAKE, flight.toByteArray(), 0, flight.size());

        switchReadKeys(serverApplicationSecret);
        writeCipher = new Tls13RecordCipher(clientApplicationSecret);
        handshakeComplete = true;
    }

    private static byte[] clientHello(SecureRandom random, byte[] sessionId, ECPublicKey share) {
        byte[] clientRandom = new byte[32];
        random.nextBytes(clientRandom);
        byte[] point = encodePoint(share);
        byte[] extensions = Bytes.concat(
                extension(EXT_SUPPORTED_VERSIONS, new byte[] {2, 3, 4}),
                extension(EXT_SUPPORTED_GROUPS, vector16(u16(GROUP_SECP256R1))),
                extension(EXT_SIGNATURE_ALGORITHMS, vector16(Bytes.concat(
                        u16(SIG_RSA_PSS_RSAE_SHA256), u16(SIG_RSA_PSS_RSAE_SHA384),
                        u16(SIG_RSA_PSS_RSAE_SHA512)))),
                extension(EXT_KEY_SHARE, vector16(Bytes.concat(
                        u16(GROUP_SECP256R1), vector16(point)))));
        byte[] body = Bytes.concat(
                u16(TLS_1_2), clientRandom,
                new byte[] {(byte) sessionId.length}, sessionId,
                vector16(u16(SUITE_AES_128_GCM_SHA256)),
                new byte[] {1, 0},
                vector16(extensions));
        return handshakeMessage(1, body);
    }

    private static ECPoint parseServerHello(byte[] body, byte[] sessionId, ECParameterSpec curve)
            throws IOException {
        Reader reader = new Reader(body);
        if (reader.u16() != TLS_1_2) {
            throw new IOException("unexpected ServerHello version");
        }
        if (Arrays.equals(reader.bytes(32), HELLO_RETRY_REQUEST_RANDOM)) {
            throw new IOException("HelloRetryRequest is not supported");
        }
        if (!Arrays.equals(reader.bytes(reader.u8()), sessionId)) {
            throw new IOException("ServerHello does not echo the session id");
        }
        if (reader.u16() != SUITE_AES_128_GCM_SHA256 || reader.u8() != 0) {
            throw new IOException("server chose an unsupported cipher suite");
        }
        Reader extensions = new Reader(reader.bytes(reader.u16()));
        reader.expectEnd();
        boolean version = false;
        ECPoint point = null;
        while (!extensions.atEnd()) {
            int type = extensions.u16();
            Reader data = new Reader(extensions.bytes(extensions.u16()));
            if (type == EXT_SUPPORTED_VERSIONS && !version) {
                if (data.u16() != TLS_1_3) {
                    throw new IOException("server did not select TLS 1.3");
                }
                version = true;
            } else if (type == EXT_KEY_SHARE && point == null) {
                if (data.u16() != GROUP_SECP256R1) {
                    throw new IOException("server chose an unsupported group");
                }
                point = decodePoint(data.bytes(data.u16()), curve);
            } else {
                throw new IOException("unexpected ServerHello extension " + type);
            }
            data.expectEnd();
        }
        if (!version || point == null) {
            throw new IOException("ServerHello lacks a required extension");
        }
        return point;
    }

    private static void parseEncryptedExtensions(byte[] body) throws IOException {
        Reader reader = new Reader(body);
        Reader extensions = new Reader(reader.bytes(reader.u16()));
        reader.expectEnd();
        boolean groups = false;
        while (!extensions.atEnd()) {
            int type = extensions.u16();
            Reader data = new Reader(extensions.bytes(extensions.u16()));
            // The only extension of the ClientHello a server may answer here.
            if (type != EXT_SUPPORTED_GROUPS || groups) {
                throw new IOException("unexpected EncryptedExtensions extension " + type);
            }
            byte[] list = data.bytes(data.u16());
            data.expectEnd();
            if (list.length == 0 || list.length % 2 != 0) {
                throw new IOException("malformed supported_groups");
            }
            groups = true;
        }
    }

    /** Returns the request context, which the client's Certificate message has to echo. */
    private static byte[] parseCertificateRequest(byte[] body) throws IOException {
        Reader reader = new Reader(body);
        byte[] context = reader.bytes(reader.u8());
        if (context.length != 0) {
            throw new IOException("CertificateRequest in the handshake carries a context");
        }
        Reader extensions = new Reader(reader.bytes(reader.u16()));
        reader.expectEnd();
        boolean pssAccepted = false;
        List<Integer> seen = new ArrayList<>();
        while (!extensions.atEnd()) {
            int type = extensions.u16();
            Reader data = new Reader(extensions.bytes(extensions.u16()));
            if (seen.contains(type)) {
                throw new IOException("duplicate CertificateRequest extension " + type);
            }
            seen.add(type);
            if (type == EXT_SIGNATURE_ALGORITHMS) {
                Reader schemes = new Reader(data.bytes(data.u16()));
                data.expectEnd();
                while (!schemes.atEnd()) {
                    pssAccepted |= schemes.u16() == SIG_RSA_PSS_RSAE_SHA256;
                }
            }
        }
        if (!pssAccepted) {
            throw new IOException("server does not accept rsa_pss_rsae_sha256");
        }
        return context;
    }

    private static X509Certificate parseCertificate(byte[] body)
            throws IOException, GeneralSecurityException {
        Reader reader = new Reader(body);
        if (reader.u8() != 0) {
            throw new IOException("server certificate carries a request context");
        }
        Reader list = new Reader(reader.bytes(reader.u24()));
        reader.expectEnd();
        byte[] first = null;
        while (!list.atEnd()) {
            byte[] certificate = list.bytes(list.u24());
            list.bytes(list.u16());
            if (certificate.length == 0) {
                throw new IOException("empty certificate entry");
            }
            if (first == null) {
                first = certificate;
            }
        }
        if (first == null) {
            throw new IOException("server sent no certificate");
        }
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(first));
    }

    private static void verifyServerSignature(byte[] body, PublicKey key, byte[] transcriptHash)
            throws IOException, GeneralSecurityException {
        Reader reader = new Reader(body);
        int scheme = reader.u16();
        byte[] signature = reader.bytes(reader.u16());
        reader.expectEnd();
        if (!(key instanceof RSAPublicKey)
                || ((RSAPublicKey) key).getModulus().bitLength() < MIN_RSA_BITS) {
            throw new IOException("server key is not RSA with at least 2048 bits");
        }
        Signature verifier;
        if (scheme == SIG_RSA_PSS_RSAE_SHA256) {
            verifier = pss("SHA-256", 32);
        } else if (scheme == SIG_RSA_PSS_RSAE_SHA384) {
            verifier = pss("SHA-384", 48);
        } else if (scheme == SIG_RSA_PSS_RSAE_SHA512) {
            verifier = pss("SHA-512", 64);
        } else {
            throw new IOException("server used a signature scheme that was not offered");
        }
        verifier.initVerify(key);
        verifier.update(signedContent("TLS 1.3, server CertificateVerify", transcriptHash));
        if (!verifier.verify(signature)) {
            throw new IOException("server CertificateVerify does not verify");
        }
    }

    private static byte[] clientCertificate(byte[] context, AdbIdentity identity)
            throws GeneralSecurityException {
        byte[] entry = Bytes.concat(vector24(identity.certificate.getEncoded()), u16(0));
        return handshakeMessage(HS_CERTIFICATE, Bytes.concat(
                new byte[] {(byte) context.length}, context, vector24(entry)));
    }

    private static byte[] clientCertificateVerify(AdbIdentity identity, byte[] transcriptHash)
            throws GeneralSecurityException {
        Signature signer = pss("SHA-256", 32);
        signer.initSign(identity.privateKey);
        signer.update(signedContent("TLS 1.3, client CertificateVerify", transcriptHash));
        return handshakeMessage(HS_CERTIFICATE_VERIFY, Bytes.concat(
                u16(SIG_RSA_PSS_RSAE_SHA256), vector16(signer.sign())));
    }

    private static byte[] signedContent(String context, byte[] transcriptHash) {
        byte[] padding = new byte[64];
        Arrays.fill(padding, (byte) 0x20);
        return Bytes.concat(padding, context.getBytes(StandardCharsets.US_ASCII), new byte[1],
                transcriptHash);
    }

    /** Android names the algorithm by digest; the reference JDK only knows "RSASSA-PSS". */
    private static Signature pss(String digest, int saltLength) throws GeneralSecurityException {
        Signature signature;
        try {
            signature = Signature.getInstance(digest.replace("-", "") + "withRSA/PSS");
        } catch (NoSuchAlgorithmException e) {
            signature = Signature.getInstance("RSASSA-PSS");
        }
        signature.setParameter(new PSSParameterSpec(digest, "MGF1",
                new MGF1ParameterSpec(digest), saltLength, 1));
        return signature;
    }

    private static byte[] encodePoint(ECPublicKey key) {
        return Bytes.concat(new byte[] {4}, fixed(key.getW().getAffineX()),
                fixed(key.getW().getAffineY()));
    }

    private static ECPoint decodePoint(byte[] encoded, ECParameterSpec spec) throws IOException {
        if (encoded.length != 65 || encoded[0] != 4) {
            throw new IOException("malformed key share");
        }
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(encoded, 1, 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(encoded, 33, 65));
        EllipticCurve curve = spec.getCurve();
        BigInteger p = ((ECFieldFp) curve.getField()).getP();
        BigInteger right = x.pow(3).add(curve.getA().multiply(x)).add(curve.getB()).mod(p);
        if (x.compareTo(p) >= 0 || y.compareTo(p) >= 0 || !y.pow(2).mod(p).equals(right)) {
            throw new IOException("key share is not a point on the curve");
        }
        return new ECPoint(x, y);
    }

    private static byte[] fixed(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] out = new byte[32];
        int copy = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - copy, out, 32 - copy, copy);
        return out;
    }

    private byte[] transcriptHash() throws GeneralSecurityException {
        return Tls13KeySchedule.sha256(transcript.toByteArray());
    }

    /** Key changes happen on message boundaries; leftover bytes under the old keys are an attack. */
    private void switchReadKeys(byte[] secret) throws IOException, GeneralSecurityException {
        if (handshakeBuffer.size() != 0) {
            throw new IOException("handshake data straddles a key change");
        }
        readCipher = new Tls13RecordCipher(secret);
    }

    private byte[] expectHandshake(int type) throws IOException, GeneralSecurityException {
        byte[] message = readHandshakeMessage();
        requireType(message, type);
        return message;
    }

    private static void requireType(byte[] message, int type) throws IOException {
        if (type(message) != type) {
            throw new IOException("unexpected handshake message " + type(message)
                    + ", wanted " + type);
        }
    }

    private static int type(byte[] message) {
        return message[0] & 0xff;
    }

    private static byte[] body(byte[] message) {
        return Arrays.copyOfRange(message, 4, message.length);
    }

    /** Returns the next complete handshake message, header included. */
    private byte[] readHandshakeMessage() throws IOException, GeneralSecurityException {
        while (true) {
            byte[] buffered = takeBufferedHandshakeMessage();
            if (buffered != null) {
                return buffered;
            }
            byte[] record = readRecord();
            int type = record[record.length - 1] & 0xff;
            if (type == TYPE_ALERT) {
                throw alert(record);
            }
            if (type != TYPE_HANDSHAKE || record.length == 1) {
                throw new IOException("unexpected TLS record type " + type);
            }
            handshakeBuffer.write(record, 0, record.length - 1);
        }
    }

    private byte[] takeBufferedHandshakeMessage() throws IOException {
        byte[] buffer = handshakeBuffer.toByteArray();
        if (buffer.length < 4) {
            return null;
        }
        int length = ((buffer[1] & 0xff) << 16) | ((buffer[2] & 0xff) << 8) | (buffer[3] & 0xff);
        if (length > MAX_HANDSHAKE_MESSAGE) {
            throw new IOException("handshake message too long");
        }
        if (buffer.length < 4 + length) {
            return null;
        }
        handshakeBuffer.reset();
        handshakeBuffer.write(buffer, 4 + length, buffer.length - 4 - length);
        return Arrays.copyOf(buffer, 4 + length);
    }

    // ------------------------------------------------------------- record layer

    /** Reads one record and returns its content followed by its (real) content type. */
    private byte[] readRecord() throws IOException, GeneralSecurityException {
        while (true) {
            byte[] header = Bytes.readFully(rawIn, 5);
            int type = header[0] & 0xff;
            int length = ((header[3] & 0xff) << 8) | (header[4] & 0xff);
            if (header[1] != 3 || length > Tls13RecordCipher.MAX_PLAINTEXT + 256) {
                throw new IOException("malformed TLS record header");
            }
            byte[] payload = Bytes.readFully(rawIn, length);
            if (type == TYPE_CHANGE_CIPHER_SPEC) {
                // Middlebox compatibility: a single dummy message during the handshake.
                if (handshakeComplete || length != 1 || payload[0] != 1
                        || ++changeCipherSpecs > MAX_CHANGE_CIPHER_SPECS) {
                    throw new IOException("unexpected change_cipher_spec");
                }
                continue;
            }
            if (readCipher != null) {
                if (type != TYPE_APPLICATION_DATA) {
                    throw new IOException("unprotected TLS record after the handshake keys");
                }
                return readCipher.open(header, payload);
            }
            if ((type != TYPE_HANDSHAKE && type != TYPE_ALERT)
                    || length > Tls13RecordCipher.MAX_PLAINTEXT) {
                throw new IOException("unexpected plaintext TLS record " + type);
            }
            return Bytes.concat(payload, new byte[] {(byte) type});
        }
    }

    private void writePlainRecord(int type, byte[] content) throws IOException {
        rawOut.write(Bytes.concat(
                new byte[] {(byte) type, 3, 3, (byte) (content.length >> 8),
                        (byte) content.length},
                content));
        rawOut.flush();
    }

    private void writeProtected(int type, byte[] content, int offset, int length)
            throws IOException, GeneralSecurityException {
        int end = offset + length;
        do {
            int chunk = Math.min(end - offset, Tls13RecordCipher.MAX_PLAINTEXT);
            rawOut.write(writeCipher.seal(type, content, offset, chunk));
            offset += chunk;
        } while (offset < end);
        rawOut.flush();
    }

    private static IOException alert(byte[] record) {
        if (record.length != 3) {
            return new IOException("malformed TLS alert");
        }
        return new IOException("TLS alert " + (record[1] & 0xff) + " from peer");
    }

    /** Makes more application data available; returns false at a clean end of stream. */
    private boolean fillApplicationData() throws IOException {
        try {
            while (applicationOffset == applicationData.length) {
                if (endOfStream) {
                    return false;
                }
                byte[] record = readRecord();
                int type = record[record.length - 1] & 0xff;
                if (type == TYPE_APPLICATION_DATA && handshakeBuffer.size() != 0) {
                    throw new IOException("application data inside a handshake message");
                }
                if (type == TYPE_APPLICATION_DATA && record.length > 1) {
                    applicationData = Arrays.copyOf(record, record.length - 1);
                    applicationOffset = 0;
                    idleRecords = 0;
                    continue;
                }
                // Everything else carries no data for the caller; a peer must not be able to
                // keep the connection busy with it forever. This is stricter than TLS itself
                // and fits adbd, which sends a couple of session tickets and nothing else.
                if (++idleRecords > MAX_IDLE_RECORDS) {
                    throw new IOException("too many TLS records without application data");
                }
                if (type == TYPE_APPLICATION_DATA) {
                    continue;
                } else if (type == TYPE_HANDSHAKE && record.length > 1) {
                    handshakeBuffer.write(record, 0, record.length - 1);
                    for (byte[] m = takeBufferedHandshakeMessage(); m != null;
                            m = takeBufferedHandshakeMessage()) {
                        // Session tickets are of no use without resumption; anything else
                        // (KeyUpdate, post-handshake authentication) is not supported.
                        if (type(m) != HS_NEW_SESSION_TICKET) {
                            throw new IOException("unsupported post-handshake message "
                                    + type(m));
                        }
                    }
                } else if (type == TYPE_ALERT && record.length == 3
                        && record[1] == ALERT_CLOSE_NOTIFY) {
                    endOfStream = true;
                } else if (type == TYPE_ALERT && record.length == 3
                        && record[1] == ALERT_USER_CANCELED) {
                    // The only non-fatal alert besides close_notify (RFC 8446 section 6.1);
                    // a close_notify follows.
                    continue;
                } else if (type == TYPE_ALERT) {
                    throw alert(record);
                } else {
                    throw new IOException("unexpected TLS record type " + type);
                }
            }
            return true;
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    // --------------------------------------------------------------- TlsChannel

    @Override
    public InputStream input() {
        return new InputStream() {
            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                if (length == 0) {
                    return 0;
                }
                if (!fillApplicationData()) {
                    return -1;
                }
                int count = Math.min(length, applicationData.length - applicationOffset);
                System.arraycopy(applicationData, applicationOffset, buffer, offset, count);
                applicationOffset += count;
                return count;
            }
        };
    }

    @Override
    public OutputStream output() {
        return new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                write(new byte[] {(byte) value}, 0, 1);
            }

            @Override
            public void write(byte[] buffer, int offset, int length) throws IOException {
                if (length == 0) {
                    return;
                }
                try {
                    writeProtected(TYPE_APPLICATION_DATA, buffer, offset, length);
                } catch (GeneralSecurityException e) {
                    throw new IOException(e);
                }
            }
        };
    }

    @Override
    public byte[] exportKeyingMaterial(String label, int length) throws GeneralSecurityException {
        return Tls13KeySchedule.export(exporterMasterSecret, label, length);
    }

    /** The certificate the peer proved possession of; not validated against anything. */
    X509Certificate peerCertificate() {
        return peerCertificate;
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            // close_notify, best effort.
            writeProtected(TYPE_ALERT, new byte[] {1, ALERT_CLOSE_NOTIFY}, 0, 2);
        } catch (IOException | GeneralSecurityException e) {
            // The peer may already be gone.
        } finally {
            socket.close();
        }
    }

    // ------------------------------------------------------------------ helpers

    private static byte[] handshakeMessage(int type, byte[] body) {
        return Bytes.concat(new byte[] {(byte) type, (byte) (body.length >> 16),
                (byte) (body.length >> 8), (byte) body.length}, body);
    }

    private static byte[] extension(int type, byte[] data) {
        return Bytes.concat(u16(type), vector16(data));
    }

    private static byte[] u16(int value) {
        return new byte[] {(byte) (value >> 8), (byte) value};
    }

    private static byte[] vector16(byte[] data) {
        return Bytes.concat(u16(data.length), data);
    }

    private static byte[] vector24(byte[] data) {
        return Bytes.concat(new byte[] {(byte) (data.length >> 16), (byte) (data.length >> 8),
                (byte) data.length}, data);
    }

    /** Bounds-checked cursor over a received structure. */
    private static final class Reader {
        private final byte[] data;
        private int position;

        Reader(byte[] data) {
            this.data = data;
        }

        int u8() throws IOException {
            return bytes(1)[0] & 0xff;
        }

        int u16() throws IOException {
            byte[] b = bytes(2);
            return ((b[0] & 0xff) << 8) | (b[1] & 0xff);
        }

        int u24() throws IOException {
            byte[] b = bytes(3);
            return ((b[0] & 0xff) << 16) | ((b[1] & 0xff) << 8) | (b[2] & 0xff);
        }

        byte[] bytes(int length) throws IOException {
            if (length < 0 || length > data.length - position) {
                throw new EOFException("truncated TLS structure");
            }
            position += length;
            return Arrays.copyOfRange(data, position - length, position);
        }

        boolean atEnd() {
            return position == data.length;
        }

        void expectEnd() throws IOException {
            if (!atEnd()) {
                throw new IOException("trailing bytes in TLS structure");
            }
        }
    }
}
