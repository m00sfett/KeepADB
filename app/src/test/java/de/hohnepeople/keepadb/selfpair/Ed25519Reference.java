package de.hohnepeople.keepadb.selfpair;

import java.math.BigInteger;

/**
 * The first, {@link BigInteger}-based edwards25519 arithmetic of the self-pairing branch (#962).
 * It is not constant time and therefore no longer ships; it stays as an independent oracle that
 * the constant-time {@link Ed25519} is tested against.
 */
final class Ed25519Reference {
    static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    /** Order of the prime-order subgroup. */
    static final BigInteger L = BigInteger.ONE.shiftLeft(252)
            .add(new BigInteger("27742317777372353535851937790883648493"));

    private static final BigInteger TWO = BigInteger.valueOf(2);
    private static final BigInteger D = BigInteger.valueOf(-121665)
            .multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P);
    private static final BigInteger D2 = D.shiftLeft(1).mod(P);
    private static final BigInteger SQRT_M1 =
            TWO.modPow(P.subtract(BigInteger.ONE).shiftRight(2), P);

    static final Point IDENTITY =
            new Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO);
    static final Point BASE = decode(hex(
            "5866666666666666666666666666666666666666666666666666666666666666"));

    private Ed25519Reference() {
    }

    /** A point in extended coordinates (X:Y:Z:T) with x = X/Z, y = Y/Z, xy = T/Z. */
    static final class Point {
        final BigInteger x;
        final BigInteger y;
        final BigInteger z;
        final BigInteger t;

        Point(BigInteger x, BigInteger y, BigInteger z, BigInteger t) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.t = t;
        }

        BigInteger affineX() {
            return x.multiply(z.modInverse(P)).mod(P);
        }

        BigInteger affineY() {
            return y.multiply(z.modInverse(P)).mod(P);
        }
    }

    /** Unified addition (add-2008-hwcd-3); complete on edwards25519, so it also doubles. */
    static Point add(Point a, Point b) {
        BigInteger aa = a.y.subtract(a.x).multiply(b.y.subtract(b.x)).mod(P);
        BigInteger bb = a.y.add(a.x).multiply(b.y.add(b.x)).mod(P);
        BigInteger cc = a.t.multiply(D2).mod(P).multiply(b.t).mod(P);
        BigInteger dd = a.z.shiftLeft(1).multiply(b.z).mod(P);
        BigInteger e = bb.subtract(aa);
        BigInteger f = dd.subtract(cc);
        BigInteger g = dd.add(cc);
        BigInteger h = bb.add(aa);
        return new Point(e.multiply(f).mod(P), g.multiply(h).mod(P),
                f.multiply(g).mod(P), e.multiply(h).mod(P));
    }

    static Point negate(Point a) {
        return new Point(P.subtract(a.x).mod(P), a.y, a.z, P.subtract(a.t).mod(P));
    }

    /** Multiplies by a non-negative scalar; the scalar is used as given, without reduction. */
    static Point multiply(BigInteger scalar, Point point) {
        if (scalar.signum() < 0) {
            throw new IllegalArgumentException("negative scalar");
        }
        Point result = IDENTITY;
        for (int i = scalar.bitLength() - 1; i >= 0; i--) {
            result = add(result, result);
            if (scalar.testBit(i)) {
                result = add(result, point);
            }
        }
        return result;
    }

    /** Standard 32-byte encoding: little-endian y with the sign of x in the top bit. */
    static byte[] encode(Point point) {
        byte[] out = toLittleEndian(point.affineY(), 32);
        if (point.affineX().testBit(0)) {
            out[31] |= (byte) 0x80;
        }
        return out;
    }

    /** Decodes a 32-byte point; throws if the bytes do not describe a point on the curve. */
    static Point decode(byte[] encoded) {
        if (encoded.length != 32) {
            throw new IllegalArgumentException("point must be 32 bytes");
        }
        byte[] yBytes = encoded.clone();
        boolean xOdd = (yBytes[31] & 0x80) != 0;
        yBytes[31] &= 0x7f;
        BigInteger y = fromLittleEndian(yBytes).mod(P);
        BigInteger yy = y.multiply(y).mod(P);
        BigInteger u = yy.subtract(BigInteger.ONE).mod(P);
        BigInteger v = D.multiply(yy).add(BigInteger.ONE).mod(P);
        // x = sqrt(u / v), candidate (u / v)^((p + 3) / 8).
        BigInteger x = u.multiply(v.modInverse(P)).mod(P)
                .modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P);
        BigInteger check = v.multiply(x).mod(P).multiply(x).mod(P);
        if (!check.equals(u)) {
            if (!check.equals(P.subtract(u).mod(P))) {
                throw new IllegalArgumentException("not a point on the curve");
            }
            x = x.multiply(SQRT_M1).mod(P);
        }
        if (x.testBit(0) != xOdd) {
            x = P.subtract(x).mod(P);
        }
        return new Point(x, y, BigInteger.ONE, x.multiply(y).mod(P));
    }

    static BigInteger fromLittleEndian(byte[] bytes) {
        byte[] reversed = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            reversed[i] = bytes[bytes.length - 1 - i];
        }
        return new BigInteger(1, reversed);
    }

    static byte[] toLittleEndian(BigInteger value, int length) {
        byte[] big = value.toByteArray();
        byte[] out = new byte[length];
        for (int i = 0; i < big.length; i++) {
            int index = big.length - 1 - i;
            if (i < length) {
                out[i] = big[index];
            } else if (big[index] != 0) {
                throw new IllegalArgumentException("value does not fit");
            }
        }
        return out;
    }

    static byte[] hex(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }
}
