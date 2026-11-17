package de.hohnepeople.keepadb.selfpair;

import java.math.BigInteger;
import java.util.Arrays;

/**
 * Minimal edwards25519 group arithmetic for {@link Spake2} (#962): decode, encode, add, negate,
 * scalar multiplication and scalar reduction. Not an Ed25519 signature scheme.
 *
 * <p>Written to be constant time with respect to secrets: no branch, array index or loop bound
 * depends on a secret value, and no {@link BigInteger} touches one. Field elements are sixteen
 * 16-bit limbs held in {@code long}s; conditional moves are done with masks; scalar
 * multiplication is a fixed 256-step ladder over the complete addition law. The structure
 * follows TweetNaCl. A managed runtime gives no hard timing guarantee (JIT, garbage collection),
 * so this removes the algorithmic leaks, not every conceivable one.
 *
 * <p>The one exception is {@link #decode}: it decides whether its input is a point on the
 * curve, and that input is always a public value.
 */
final class Ed25519 {
    static final int SCALAR_BYTES = 32;

    private static final int LIMBS = 16;
    private static final BigInteger P =
            BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));

    // Public curve constants, derived once from their definitions.
    private static final long[] ONE = fromBigInteger(BigInteger.ONE);
    private static final long[] D = fromBigInteger(BigInteger.valueOf(-121665)
            .multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P));
    private static final long[] D2 = fromBigInteger(BigInteger.valueOf(-121665)
            .multiply(BigInteger.valueOf(121666).modInverse(P)).shiftLeft(1).mod(P));
    private static final long[] SQRT_M1 = fromBigInteger(
            BigInteger.valueOf(2).modPow(P.subtract(BigInteger.ONE).shiftRight(2), P));

    /** Order of the prime-order subgroup, little-endian bytes, as TweetNaCl's {@code L}. */
    private static final long[] ORDER = {
        0xed, 0xd3, 0xf5, 0x5c, 0x1a, 0x63, 0x12, 0x58, 0xd6, 0x9c, 0xf7, 0xa2, 0xde, 0xf9,
        0xde, 0x14, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x10};

    private static final long[][] BASE = decode(Bytes.hex(
            "5866666666666666666666666666666666666666666666666666666666666666"));

    private Ed25519() {
    }

    // ------------------------------------------------------------------ points

    /** A fresh copy of the base point. Points are {X, Y, Z, T} with x = X/Z, y = Y/Z. */
    static long[][] base() {
        return copy(BASE);
    }

    static long[][] identity() {
        return new long[][] {new long[LIMBS], ONE.clone(), ONE.clone(), new long[LIMBS]};
    }

    /** Returns a + b; complete on edwards25519, so it also doubles. Inputs are not modified. */
    static long[][] add(long[][] a, long[][] b) {
        long[][] out = copy(a);
        addInto(out, b);
        return out;
    }

    static long[][] negate(long[][] a) {
        long[] zero = new long[LIMBS];
        return new long[][] {sub(zero, a[0]), a[1].clone(), a[2].clone(), sub(zero, a[3])};
    }

    /**
     * Multiplies {@code point} by a 256-bit little-endian scalar, used as given (no reduction).
     * Always 256 doublings and 256 additions, whatever the scalar.
     */
    static long[][] multiply(byte[] scalar, long[][] point) {
        if (scalar.length != SCALAR_BYTES) {
            throw new IllegalArgumentException("scalar must be 32 bytes");
        }
        long[][] p = identity();
        long[][] q = copy(point);
        for (int i = 255; i >= 0; i--) {
            long bit = (scalar[i >>> 3] >>> (i & 7)) & 1;
            swap(p, q, bit);
            addInto(q, p);
            addInto(p, p);
            swap(p, q, bit);
        }
        wipe(q);
        return p;
    }

    /** Standard 32-byte encoding: little-endian y with the sign of x in the top bit. */
    static byte[] encode(long[][] point) {
        long[] inverse = invert(point[2]);
        long[] x = mul(point[0], inverse);
        long[] y = mul(point[1], inverse);
        byte[] out = pack(y);
        out[31] ^= (byte) ((pack(x)[0] & 1) << 7);
        return out;
    }

    /**
     * Decodes a 32-byte point; throws if the bytes do not describe a point on the curve. Not
     * constant time: the input is public (a constant or the peer's message).
     */
    static long[][] decode(byte[] encoded) {
        if (encoded.length != 32) {
            throw new IllegalArgumentException("point must be 32 bytes");
        }
        long[] y = unpack(encoded);
        long[] yy = square(y);
        long[] numerator = sub(yy, ONE);
        long[] denominator = add(mul(yy, D), ONE);
        // x = num * den^3 * (num * den^7)^((p - 5) / 8)
        long[] den2 = square(denominator);
        long[] den4 = square(den2);
        long[] den6 = mul(den4, den2);
        long[] t = pow2523(mul(mul(den6, numerator), denominator));
        long[] x = mul(mul(mul(mul(t, numerator), denominator), denominator), denominator);
        if (!equal(mul(square(x), denominator), numerator)) {
            x = mul(x, SQRT_M1);
            if (!equal(mul(square(x), denominator), numerator)) {
                throw new IllegalArgumentException("not a point on the curve");
            }
        }
        if ((pack(x)[0] & 1) != ((encoded[31] >>> 7) & 1)) {
            x = sub(new long[LIMBS], x);
        }
        return new long[][] {x, y, ONE.clone(), mul(x, y)};
    }

    // ----------------------------------------------------------------- scalars

    /** Reduces a 512-bit little-endian value modulo the group order to 32 bytes. */
    static byte[] reduce(byte[] wide) {
        if (wide.length != 64) {
            throw new IllegalArgumentException("need 64 bytes");
        }
        long[] x = new long[64];
        for (int i = 0; i < 64; i++) {
            x[i] = wide[i] & 0xff;
        }
        for (int i = 63; i >= 32; i--) {
            long carry = 0;
            int j;
            for (j = i - 32; j < i - 12; j++) {
                x[j] += carry - 16 * x[i] * ORDER[j - (i - 32)];
                carry = (x[j] + 128) >> 8;
                x[j] -= carry << 8;
            }
            x[j] += carry;
            x[i] = 0;
        }
        long carry = 0;
        for (int j = 0; j < 32; j++) {
            x[j] += carry - (x[31] >> 4) * ORDER[j];
            carry = x[j] >> 8;
            x[j] &= 255;
        }
        for (int j = 0; j < 32; j++) {
            x[j] -= carry * ORDER[j];
        }
        byte[] out = new byte[SCALAR_BYTES];
        for (int i = 0; i < 32; i++) {
            x[i + 1] += x[i] >> 8;
            out[i] = (byte) x[i];
        }
        Arrays.fill(x, 0);
        return out;
    }

    /**
     * Adds {@code order << shift} to a 256-bit little-endian scalar if {@code condition} is 1,
     * and nothing if it is 0, in the same number of steps either way. The caller guarantees
     * that the sum stays below 2^256.
     */
    static void addOrderIf(byte[] scalar, int shift, long condition) {
        long mask = -condition;
        long carry = 0;
        long previous = 0;
        for (int i = 0; i < SCALAR_BYTES; i++) {
            long shifted = ((ORDER[i] << shift) | (previous >>> (8 - shift))) & 0xff;
            previous = ORDER[i];
            long sum = (scalar[i] & 0xff) + (shifted & mask) + carry;
            scalar[i] = (byte) sum;
            carry = sum >>> 8;
        }
    }

    /** Multiplies a 256-bit little-endian scalar by eight in place; the top bits must be free. */
    static void shiftLeft3(byte[] scalar) {
        int carry = 0;
        for (int i = 0; i < SCALAR_BYTES; i++) {
            int value = scalar[i] & 0xff;
            scalar[i] = (byte) ((value << 3) | carry);
            carry = value >>> 5;
        }
    }

    static void wipe(long[][] point) {
        for (long[] coordinate : point) {
            Arrays.fill(coordinate, 0);
        }
    }

    // ---------------------------------------------------------- field elements

    private static long[][] copy(long[][] point) {
        return new long[][] {
            point[0].clone(), point[1].clone(), point[2].clone(), point[3].clone()};
    }

    /** p += q (add-2008-hwcd-3, unified). {@code q} may be {@code p} itself. */
    private static void addInto(long[][] p, long[][] q) {
        long[] a = mul(sub(p[1], p[0]), sub(q[1], q[0]));
        long[] b = mul(add(p[0], p[1]), add(q[0], q[1]));
        long[] c = mul(mul(p[3], q[3]), D2);
        long[] d = mul(p[2], q[2]);
        d = add(d, d);
        long[] e = sub(b, a);
        long[] f = sub(d, c);
        long[] g = add(d, c);
        long[] h = add(b, a);
        p[0] = mul(e, f);
        p[1] = mul(h, g);
        p[2] = mul(g, f);
        p[3] = mul(e, h);
    }

    /** Swaps the two points if {@code bit} is 1, with the same memory accesses either way. */
    private static void swap(long[][] p, long[][] q, long bit) {
        long mask = -bit;
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < LIMBS; j++) {
                long t = mask & (p[i][j] ^ q[i][j]);
                p[i][j] ^= t;
                q[i][j] ^= t;
            }
        }
    }

    private static long[] add(long[] a, long[] b) {
        long[] out = new long[LIMBS];
        for (int i = 0; i < LIMBS; i++) {
            out[i] = a[i] + b[i];
        }
        return out;
    }

    private static long[] sub(long[] a, long[] b) {
        long[] out = new long[LIMBS];
        for (int i = 0; i < LIMBS; i++) {
            out[i] = a[i] - b[i];
        }
        return out;
    }

    private static long[] mul(long[] a, long[] b) {
        long[] t = new long[31];
        for (int i = 0; i < LIMBS; i++) {
            for (int j = 0; j < LIMBS; j++) {
                t[i + j] += a[i] * b[j];
            }
        }
        for (int i = 0; i < 15; i++) {
            t[i] += 38 * t[i + 16];
        }
        long[] out = Arrays.copyOf(t, LIMBS);
        carry(out);
        carry(out);
        return out;
    }

    private static long[] square(long[] a) {
        return mul(a, a);
    }

    private static void carry(long[] o) {
        for (int i = 0; i < LIMBS; i++) {
            o[i] += 1L << 16;
            long c = o[i] >> 16;
            if (i < 15) {
                o[i + 1] += c - 1;
            } else {
                o[0] += 38 * (c - 1);
            }
            o[i] -= c << 16;
        }
    }

    /** a^(p - 2), the inverse; the exponent is public, so branching on its bits is fine. */
    private static long[] invert(long[] a) {
        long[] c = a.clone();
        for (int i = 253; i >= 0; i--) {
            c = square(c);
            if (i != 2 && i != 4) {
                c = mul(c, a);
            }
        }
        return c;
    }

    /** a^((p - 5) / 8). */
    private static long[] pow2523(long[] a) {
        long[] c = a.clone();
        for (int i = 250; i >= 0; i--) {
            c = square(c);
            if (i != 1) {
                c = mul(c, a);
            }
        }
        return c;
    }

    /** Canonical 32-byte little-endian encoding, fully reduced. */
    private static byte[] pack(long[] n) {
        long[] t = n.clone();
        long[] m = new long[LIMBS];
        carry(t);
        carry(t);
        carry(t);
        for (int round = 0; round < 2; round++) {
            m[0] = t[0] - 0xffed;
            for (int i = 1; i < 15; i++) {
                m[i] = t[i] - 0xffff - ((m[i - 1] >> 16) & 1);
                m[i - 1] &= 0xffff;
            }
            m[15] = t[15] - 0x7fff - ((m[14] >> 16) & 1);
            long borrow = (m[15] >> 16) & 1;
            m[14] &= 0xffff;
            // Keep t - p unless the subtraction borrowed.
            long mask = -(1 - borrow);
            for (int i = 0; i < LIMBS; i++) {
                long x = mask & (t[i] ^ m[i]);
                t[i] ^= x;
                m[i] ^= x;
            }
        }
        byte[] out = new byte[32];
        for (int i = 0; i < LIMBS; i++) {
            out[2 * i] = (byte) t[i];
            out[2 * i + 1] = (byte) (t[i] >> 8);
        }
        return out;
    }

    private static long[] unpack(byte[] bytes) {
        long[] out = new long[LIMBS];
        for (int i = 0; i < LIMBS; i++) {
            out[i] = (bytes[2 * i] & 0xff) | ((long) (bytes[2 * i + 1] & 0xff) << 8);
        }
        out[15] &= 0x7fff;
        return out;
    }

    /** Only used on public values in {@link #decode}. */
    private static boolean equal(long[] a, long[] b) {
        return Arrays.equals(pack(a), pack(b));
    }

    private static long[] fromBigInteger(BigInteger value) {
        return unpack(Bytes.toLittleEndian(value, 32));
    }
}
