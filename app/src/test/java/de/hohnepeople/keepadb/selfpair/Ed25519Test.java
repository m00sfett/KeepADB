package de.hohnepeople.keepadb.selfpair;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Random;

/**
 * The constant-time {@link Ed25519} against known answers and against the independent
 * {@link Ed25519Reference}, which computes the same things with {@link BigInteger}.
 */
public class Ed25519Test {
    private static final String M = "5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e";
    private static final String N = "10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778";
    private static final byte[] IDENTITY = Ed25519Reference.encode(Ed25519Reference.IDENTITY);

    @Test
    public void encodeRoundTrips() {
        assertArrayEquals(Bytes.hex(M), Ed25519.encode(Ed25519.decode(Bytes.hex(M))));
        assertArrayEquals(Bytes.hex(N), Ed25519.encode(Ed25519.decode(Bytes.hex(N))));
        assertArrayEquals(IDENTITY, Ed25519.encode(Ed25519.identity()));
    }

    @Test
    public void basePointHasTheGroupOrder() {
        byte[] order = Bytes.toLittleEndian(Ed25519Reference.L, 32);
        assertArrayEquals(IDENTITY, Ed25519.encode(Ed25519.multiply(order, Ed25519.base())));
    }

    /** RFC 8032 section 7.1, test 1: public key for the scalar derived from a known seed. */
    @Test
    public void scalarMultiplicationMatchesRfc8032() throws Exception {
        byte[] seed = Bytes.hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60");
        byte[] scalar = Arrays.copyOf(MessageDigest.getInstance("SHA-512").digest(seed), 32);
        scalar[0] &= (byte) 248;
        scalar[31] &= 127;
        scalar[31] |= 64;
        assertArrayEquals(
                Bytes.hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"),
                Ed25519.encode(Ed25519.multiply(scalar, Ed25519.base())));
    }

    @Test
    public void scalarMultiplicationMatchesTheReference() {
        Random random = new Random(962);
        Ed25519Reference.Point[] referencePoints = {
            Ed25519Reference.BASE, Ed25519Reference.decode(Bytes.hex(M)),
            Ed25519Reference.decode(Bytes.hex(N))};
        long[][][] points = {
            Ed25519.base(), Ed25519.decode(Bytes.hex(M)), Ed25519.decode(Bytes.hex(N))};
        for (int i = 0; i < 60; i++) {
            byte[] scalar = new byte[32];
            random.nextBytes(scalar);
            if (i == 0) {
                Arrays.fill(scalar, (byte) 0);
            } else if (i == 1) {
                Arrays.fill(scalar, (byte) 0xff);
            } else if (i == 2) {
                Arrays.fill(scalar, (byte) 0);
                scalar[0] = 1;
            }
            int which = i % 3;
            assertArrayEquals("scalar " + i,
                    Ed25519Reference.encode(Ed25519Reference.multiply(
                            Ed25519Reference.fromLittleEndian(scalar), referencePoints[which])),
                    Ed25519.encode(Ed25519.multiply(scalar, points[which])));
        }
    }

    @Test
    public void additionAndNegationMatchTheReference() {
        Random random = new Random(1962);
        for (int i = 0; i < 20; i++) {
            byte[] a = new byte[32];
            byte[] b = new byte[32];
            random.nextBytes(a);
            random.nextBytes(b);
            Ed25519Reference.Point ra = Ed25519Reference.multiply(
                    Ed25519Reference.fromLittleEndian(a), Ed25519Reference.BASE);
            Ed25519Reference.Point rb = Ed25519Reference.multiply(
                    Ed25519Reference.fromLittleEndian(b), Ed25519Reference.BASE);
            long[][] pa = Ed25519.decode(Ed25519Reference.encode(ra));
            long[][] pb = Ed25519.decode(Ed25519Reference.encode(rb));
            assertArrayEquals(Ed25519Reference.encode(Ed25519Reference.add(ra, rb)),
                    Ed25519.encode(Ed25519.add(pa, pb)));
            assertArrayEquals(
                    Ed25519Reference.encode(Ed25519Reference.add(ra, Ed25519Reference.negate(rb))),
                    Ed25519.encode(Ed25519.add(pa, Ed25519.negate(pb))));
            assertArrayEquals(Ed25519Reference.encode(Ed25519Reference.add(ra, ra)),
                    Ed25519.encode(Ed25519.add(pa, pa)));
            assertArrayEquals(IDENTITY, Ed25519.encode(Ed25519.add(pa, Ed25519.negate(pa))));
        }
    }

    @Test
    public void reductionMatchesTheReference() {
        Random random = new Random(2962);
        for (int i = 0; i < 2000; i++) {
            byte[] wide = new byte[64];
            random.nextBytes(wide);
            if (i == 0) {
                Arrays.fill(wide, (byte) 0);
            } else if (i == 1) {
                Arrays.fill(wide, (byte) 0xff);
            } else if (i == 2) {
                wide = Bytes.toLittleEndian(Ed25519Reference.L, 64);
            } else if (i == 3) {
                wide = Bytes.toLittleEndian(Ed25519Reference.L.subtract(BigInteger.ONE), 64);
            } else if (i == 4) {
                // A value just above a high multiple of the order.
                wide = Bytes.toLittleEndian(Ed25519Reference.L.shiftLeft(258).add(
                        BigInteger.valueOf(5)), 64);
            }
            BigInteger expected = Ed25519Reference.fromLittleEndian(wide).mod(Ed25519Reference.L);
            assertArrayEquals("value " + i, Bytes.toLittleEndian(expected, 32),
                    Ed25519.reduce(wide));
        }
    }

    @Test
    public void conditionalOrderAdditionAndShiftMatchTheReference() {
        Random random = new Random(3962);
        for (int i = 0; i < 200; i++) {
            byte[] wide = new byte[64];
            random.nextBytes(wide);
            byte[] scalar = Ed25519.reduce(wide);
            BigInteger value = Ed25519Reference.fromLittleEndian(scalar);
            for (int shift = 0; shift < 3; shift++) {
                long condition = random.nextInt(2);
                Ed25519.addOrderIf(scalar, shift, condition);
                if (condition == 1) {
                    value = value.add(Ed25519Reference.L.shiftLeft(shift));
                }
                assertArrayEquals(Bytes.toLittleEndian(value, 32), scalar);
            }
            byte[] shifted = Ed25519.reduce(wide);
            Ed25519.shiftLeft3(shifted);
            assertArrayEquals(Bytes.toLittleEndian(
                    Ed25519Reference.fromLittleEndian(Ed25519.reduce(wide)).shiftLeft(3), 32),
                    shifted);
        }
    }

    @Test
    public void decodeAcceptsAndRejectsLikeTheReference() {
        Random random = new Random(4962);
        int accepted = 0;
        for (int i = 0; i < 400; i++) {
            byte[] candidate = new byte[32];
            random.nextBytes(candidate);
            byte[] expected;
            try {
                expected = Ed25519Reference.encode(Ed25519Reference.decode(candidate));
            } catch (IllegalArgumentException e) {
                expected = null;
            }
            byte[] actual;
            try {
                actual = Ed25519.encode(Ed25519.decode(candidate));
            } catch (IllegalArgumentException e) {
                actual = null;
            }
            assertArrayEquals("candidate " + i, expected, actual);
            accepted += expected == null ? 0 : 1;
        }
        // Roughly half of all strings are points; make sure both branches were exercised.
        org.junit.Assert.assertTrue(accepted > 100 && accepted < 300);
    }
}
