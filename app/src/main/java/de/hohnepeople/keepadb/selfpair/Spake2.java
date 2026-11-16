package de.hohnepeople.keepadb.selfpair;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * SPAKE2 over edwards25519 exactly as BoringSSL implements it in {@code spake25519.cc}, client
 * ("alice") role only (#962). ADB pairing uses this variant, including its historical quirks:
 * the private scalar is multiplied by the cofactor without reduction, and the password scalar is
 * made a multiple of eight by adding multiples of the group order instead of shifting.
 */
final class Spake2 {
    static final int MESSAGE_SIZE = 32;
    static final int KEY_SIZE = 64;

    private static final Ed25519.Point M = Ed25519.decode(Ed25519.hex(
            "5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e"));
    private static final Ed25519.Point N = Ed25519.decode(Ed25519.hex(
            "10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778"));

    private final byte[] myName;
    private final byte[] theirName;
    private BigInteger privateKey;
    private BigInteger passwordScalar;
    private byte[] passwordHash;
    private byte[] myMessage;
    private boolean finished;

    Spake2(byte[] myName, byte[] theirName) {
        this.myName = myName.clone();
        this.theirName = theirName.clone();
    }

    /** Creates this side's 32-byte message from fresh randomness. */
    byte[] generateMessage(byte[] password) {
        byte[] random = new byte[64];
        new SecureRandom().nextBytes(random);
        return generateMessage(password, random);
    }

    /** Test seam: {@code random} stands in for BoringSSL's 64 bytes from {@code RAND_bytes}. */
    byte[] generateMessage(byte[] password, byte[] random) {
        if (myMessage != null) {
            throw new IllegalStateException("message already generated");
        }
        if (random.length != 64) {
            throw new IllegalArgumentException("need 64 random bytes");
        }
        // Reduce, then multiply by the cofactor so it is cleared on the peer's point later.
        privateKey = Ed25519.fromLittleEndian(random).mod(Ed25519.L).shiftLeft(3);

        passwordHash = sha512().digest(password);
        BigInteger scalar = Ed25519.fromLittleEndian(passwordHash).mod(Ed25519.L);
        // BoringSSL's "password scalar hack": clear the low three bits by adding l, 2l, 4l.
        BigInteger order = Ed25519.L;
        for (int bit = 0; bit < 3; bit++) {
            if (scalar.testBit(bit)) {
                scalar = scalar.add(order);
            }
            order = order.shiftLeft(1);
        }
        passwordScalar = scalar;

        Ed25519.Point masked = Ed25519.add(
                Ed25519.multiply(privateKey, Ed25519.BASE),
                Ed25519.multiply(passwordScalar, M));
        myMessage = Ed25519.encode(masked);
        return myMessage.clone();
    }

    /**
     * Consumes the peer's message and returns the 64-byte shared key. A wrong password does not
     * fail here; it yields a different key, which shows when the first encrypted packet arrives.
     */
    byte[] processMessage(byte[] theirMessage) {
        if (myMessage == null || finished) {
            throw new IllegalStateException("wrong state");
        }
        if (theirMessage.length != MESSAGE_SIZE) {
            throw new IllegalArgumentException("peer message must be 32 bytes");
        }
        finished = true;
        Ed25519.Point theirs = Ed25519.decode(theirMessage);
        Ed25519.Point unmasked = Ed25519.add(theirs,
                Ed25519.negate(Ed25519.multiply(passwordScalar, N)));
        byte[] shared = Ed25519.encode(Ed25519.multiply(privateKey, unmasked));

        MessageDigest sha = sha512();
        updateWithLength(sha, myName);
        updateWithLength(sha, theirName);
        updateWithLength(sha, myMessage);
        updateWithLength(sha, theirMessage);
        updateWithLength(sha, shared);
        updateWithLength(sha, passwordHash);
        privateKey = null;
        passwordScalar = null;
        return sha.digest();
    }

    private static void updateWithLength(MessageDigest sha, byte[] data) {
        byte[] length = new byte[8];
        long l = data.length;
        for (int i = 0; i < 8; i++) {
            length[i] = (byte) l;
            l >>>= 8;
        }
        sha.update(length);
        sha.update(data);
    }

    private static MessageDigest sha512() {
        try {
            return MessageDigest.getInstance("SHA-512");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
