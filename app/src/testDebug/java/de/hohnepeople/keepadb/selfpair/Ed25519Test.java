package de.hohnepeople.keepadb.selfpair;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.math.BigInteger;

public class Ed25519Test {
    private static final String M = "5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e";
    private static final String N = "10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778";

    /** Coordinates as documented next to the constants in BoringSSL's spake25519.cc. */
    @Test
    public void decodesSpakePointsToDocumentedCoordinates() {
        Ed25519.Point m = Ed25519.decode(Ed25519.hex(M));
        assertEquals(new BigInteger("3140653934272763312125028810305011356237537490022641521131"
                + "1216773867585644232"), m.affineX());
        assertEquals(new BigInteger("2117730835642395846683384503265885966629634176694266265023"
                + "2962324899758529114"), m.affineY());
        Ed25519.Point n = Ed25519.decode(Ed25519.hex(N));
        assertEquals(new BigInteger("4991873222178754473533178359203078742299150668987707963145"
                + "9872391322455579424"), n.affineX());
        assertEquals(new BigInteger("5462955443156546772083244594944104958131709454678806992622"
                + "8343916274969994000"), n.affineY());
    }

    @Test
    public void encodeRoundTrips() {
        assertArrayEquals(Ed25519.hex(M), Ed25519.encode(Ed25519.decode(Ed25519.hex(M))));
        assertArrayEquals(Ed25519.hex(N), Ed25519.encode(Ed25519.decode(Ed25519.hex(N))));
    }

    @Test
    public void basePointHasTheGroupOrder() {
        byte[] identity = Ed25519.encode(Ed25519.IDENTITY);
        assertArrayEquals(identity, Ed25519.encode(Ed25519.multiply(Ed25519.L, Ed25519.BASE)));
    }

    /** RFC 8032 section 7.1, test 1: public key for the all-derived scalar of a known seed. */
    @Test
    public void scalarMultiplicationMatchesRfc8032() throws Exception {
        byte[] seed = Ed25519.hex(
                "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60");
        byte[] h = java.security.MessageDigest.getInstance("SHA-512").digest(seed);
        byte[] scalar = java.util.Arrays.copyOf(h, 32);
        scalar[0] &= (byte) 248;
        scalar[31] &= 127;
        scalar[31] |= 64;
        assertArrayEquals(
                Ed25519.hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"),
                Ed25519.encode(Ed25519.multiply(Ed25519.fromLittleEndian(scalar), Ed25519.BASE)));
    }

    @Test
    public void addAndNegateCancel() {
        Ed25519.Point m = Ed25519.decode(Ed25519.hex(M));
        assertArrayEquals(Ed25519.encode(Ed25519.IDENTITY),
                Ed25519.encode(Ed25519.add(m, Ed25519.negate(m))));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsBytesThatAreNotAPoint() {
        byte[] bad = new byte[32];
        bad[0] = 2;
        Ed25519.decode(bad);
    }
}
