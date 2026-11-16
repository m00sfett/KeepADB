package de.hohnepeople.keepadb.selfpair;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class AdbIdentityTest {
    @Test
    public void certificateIsSelfSignedForTheKey() throws Exception {
        AdbIdentity identity = AdbIdentity.create();
        identity.certificate.verify(identity.publicKey);
        assertEquals(identity.publicKey, identity.certificate.getPublicKey());
        assertEquals(3, identity.certificate.getVersion());
    }

    @Test
    public void adbPublicKeyFollowsAndroidPubkeyLayout() throws Exception {
        AdbIdentity identity = AdbIdentity.create();
        String text = new String(identity.adbPublicKey("KeepADB"), StandardCharsets.US_ASCII);
        assertEquals('\0', text.charAt(text.length() - 1));
        String[] parts = text.substring(0, text.length() - 1).split(" ");
        assertEquals("KeepADB", parts[1]);
        byte[] struct = Base64.getDecoder().decode(parts[0]);
        assertEquals(524, struct.length);
        ByteBuffer buffer = ByteBuffer.wrap(struct).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(64, buffer.getInt());
        long n0inv = buffer.getInt() & 0xffffffffL;
        byte[] modulus = new byte[256];
        buffer.get(modulus);
        byte[] rr = new byte[256];
        buffer.get(rr);
        assertEquals(65537, buffer.getInt());

        BigInteger n = identity.publicKey.getModulus();
        assertEquals(n, Ed25519Reference.fromLittleEndian(modulus));
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        // n0inv = -1 / n[0] mod 2^32
        assertEquals(r32.subtract(BigInteger.ONE),
                BigInteger.valueOf(n0inv).multiply(n).mod(r32));
        assertEquals(BigInteger.ONE.shiftLeft(4096).mod(n), Ed25519Reference.fromLittleEndian(rr));
    }
}
