package de.hohnepeople.keepadb.selfpair;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The pairing exchange against a server written here from the specification's other side
 * ("bob"), plus servers that misbehave.
 */
public class AdbPairingClientTest {
    private static final byte[] CLIENT = Bytes.concat(Bytes.ascii("adb pair client"), new byte[1]);
    private static final byte[] SERVER = Bytes.concat(Bytes.ascii("adb pair server"), new byte[1]);
    private static final byte[] EXPORTED = new byte[64];
    private static final byte[] KEY = Bytes.ascii("QUJD KeepADB\0");

    private enum Mode { HONEST, REFLECT, ANSWER_WITH_PUBLIC_KEY }

    @Test
    public void pairsWithAnHonestServer() throws Exception {
        assertEquals("QUJD KeepADB", run("123456", "123456", Mode.HONEST));
    }

    @Test
    public void wrongCodeIsReportedAsSuch() throws Exception {
        try {
            run("123456", "654321", Mode.HONEST);
            fail("expected a wrong-code failure");
        } catch (AdbPairingClient.WrongCodeException expected) {
            // The server could not decrypt either; that is the other half of the same fact.
        }
    }

    /** Review finding: both directions share key and nonce, so an echo decrypts. */
    @Test
    public void rejectsItsOwnPacketSentBack() throws Exception {
        try {
            run("123456", "000000", Mode.REFLECT);
            fail("a reflected packet must not count as pairing");
        } catch (AdbPairingClient.WrongCodeException e) {
            fail("reflection is not a wrong code");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("pairing server"));
        }
    }

    @Test(expected = IOException.class)
    public void rejectsAPeerInfoThatIsNotADeviceGuid() throws Exception {
        run("123456", "123456", Mode.ANSWER_WITH_PUBLIC_KEY);
    }

    /** Runs the client against a server thread; returns the key text the server received. */
    private static String run(String clientCode, String serverCode, Mode mode) throws Exception {
        PipedInputStream clientIn = new PipedInputStream(1 << 16);
        PipedOutputStream serverOut = new PipedOutputStream(clientIn);
        PipedInputStream serverIn = new PipedInputStream(1 << 16);
        PipedOutputStream clientOut = new PipedOutputStream(serverIn);
        CompletableFuture<String> server = CompletableFuture.supplyAsync(() -> {
            try {
                return serve(serverIn, serverOut, serverCode, mode);
            } catch (Exception e) {
                return "server failed: " + e;
            } finally {
                try {
                    serverOut.close();
                } catch (IOException e) {
                    // ignored
                }
            }
        }, TestThreads.EXECUTOR);
        TlsChannel channel = new TlsChannel() {
            @Override
            public InputStream input() {
                return clientIn;
            }

            @Override
            public OutputStream output() {
                return clientOut;
            }

            @Override
            public byte[] exportKeyingMaterial(String label, int length) {
                assertEquals("adb-label\0", label);
                return EXPORTED.clone();
            }

            @Override
            public void close() {
            }
        };
        try {
            AdbPairingClient.exchange(channel, clientCode, KEY);
        } finally {
            clientOut.close();
        }
        return server.get(10, TimeUnit.SECONDS);
    }

    private static String serve(InputStream in, OutputStream out, String code, Mode mode)
            throws Exception {
        byte[] password = Bytes.concat(Bytes.ascii(code), EXPORTED);
        byte[] passwordHash = MessageDigest.getInstance("SHA-512").digest(password);
        BigInteger scalar = Ed25519Reference.fromLittleEndian(passwordHash).mod(Ed25519Reference.L);
        BigInteger order = Ed25519Reference.L;
        for (int bit = 0; bit < 3; bit++) {
            if (scalar.testBit(bit)) {
                scalar = scalar.add(order);
            }
            order = order.shiftLeft(1);
        }
        Ed25519Reference.Point m = Ed25519Reference.decode(Bytes.hex(
                "5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e"));
        Ed25519Reference.Point n = Ed25519Reference.decode(Bytes.hex(
                "10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778"));
        BigInteger privateKey = BigInteger.valueOf(0x5eed).shiftLeft(3);
        byte[] ours = Ed25519Reference.encode(Ed25519Reference.add(Ed25519Reference.multiply(privateKey, Ed25519Reference.BASE),
                Ed25519Reference.multiply(scalar, n)));

        byte[] theirs = readPacket(in, 0);
        writePacket(out, 0, ours);
        Ed25519Reference.Point unmasked = Ed25519Reference.add(Ed25519Reference.decode(theirs),
                Ed25519Reference.negate(Ed25519Reference.multiply(scalar, m)));
        byte[] shared = Ed25519Reference.encode(Ed25519Reference.multiply(privateKey, unmasked));
        MessageDigest sha = MessageDigest.getInstance("SHA-512");
        for (byte[] part : new byte[][] {CLIENT, SERVER, theirs, ours, shared, passwordHash}) {
            sha.update(ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .putLong(part.length).array());
            sha.update(part);
        }
        PairingCipher cipher = new PairingCipher(sha.digest());

        byte[] sealed = readPacket(in, 1);
        if (mode == Mode.REFLECT) {
            writePacket(out, 1, sealed);
            return "reflected";
        }
        // Like adbd, send our peer info before looking at theirs.
        byte[] reply = new byte[8192];
        reply[0] = (byte) (mode == Mode.HONEST ? 1 : 0);
        reply[1] = 'g';
        writePacket(out, 1, cipher.encrypt(reply));
        byte[] info;
        try {
            info = cipher.decrypt(sealed);
        } catch (javax.crypto.AEADBadTagException e) {
            return "server could not decrypt";
        }
        int end = 1;
        while (info[end] != 0) {
            end++;
        }
        return new String(info, 1, end - 1, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static void writePacket(OutputStream out, int type, byte[] payload)
            throws IOException {
        out.write(ByteBuffer.allocate(6).put((byte) 1).put((byte) type).putInt(payload.length)
                .array());
        out.write(payload);
        out.flush();
    }

    private static byte[] readPacket(InputStream in, int type) throws IOException {
        ByteBuffer header = ByteBuffer.wrap(Bytes.readFully(in, 6));
        assertEquals(1, header.get());
        assertEquals(type, header.get());
        return Bytes.readFully(in, header.getInt());
    }
}
