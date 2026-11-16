package de.hohnepeople.keepadb.selfpair;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * SPAKE2 over edwards25519 exactly as BoringSSL implements it in {@code spake25519.cc}, client
 * ("alice") role only (#962). ADB pairing uses this variant, including its historical quirks:
 * the private scalar is multiplied by the cofactor without reduction, and the password scalar is
 * made a multiple of eight by adding multiples of the group order instead of shifting.
 *
 * <p>All arithmetic on the password and on the private scalar goes through the constant-time
 * operations of {@link Ed25519}; secret buffers are overwritten once they are no longer needed.
 */
final class Spake2 {
    static final int MESSAGE_SIZE = 32;
    static final int KEY_SIZE = 64;

    private static final long[][] M = Ed25519.decode(Bytes.hex(
            "5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e"));
    private static final long[][] N = Ed25519.decode(Bytes.hex(
            "10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778"));

    private final byte[] myName;
    private final byte[] theirName;
    private byte[] privateKey;
    private byte[] passwordScalar;
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
        try {
            return generateMessage(password, random);
        } finally {
            Arrays.fill(random, (byte) 0);
        }
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
        privateKey = Ed25519.reduce(random);
        Ed25519.shiftLeft3(privateKey);

        passwordHash = sha512().digest(password);
        passwordScalar = Ed25519.reduce(passwordHash);
        // BoringSSL's "password scalar hack": clear the low three bits by adding l, 2l, 4l.
        // Each step looks at the bit the previous step may have changed.
        for (int bit = 0; bit < 3; bit++) {
            Ed25519.addOrderIf(passwordScalar, bit, (passwordScalar[0] >>> bit) & 1);
        }

        long[][] ephemeral = Ed25519.multiply(privateKey, Ed25519.base());
        long[][] mask = Ed25519.multiply(passwordScalar, M);
        long[][] masked = Ed25519.add(ephemeral, mask);
        myMessage = Ed25519.encode(masked);
        Ed25519.wipe(ephemeral);
        Ed25519.wipe(mask);
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
        try {
            long[][] theirs = Ed25519.decode(theirMessage);
            long[][] theirMask = Ed25519.multiply(passwordScalar, N);
            long[][] unmasked = Ed25519.add(theirs, Ed25519.negate(theirMask));
            long[][] product = Ed25519.multiply(privateKey, unmasked);
            byte[] shared = Ed25519.encode(product);
            Ed25519.wipe(theirMask);
            Ed25519.wipe(unmasked);
            Ed25519.wipe(product);

            MessageDigest sha = sha512();
            updateWithLength(sha, myName);
            updateWithLength(sha, theirName);
            updateWithLength(sha, myMessage);
            updateWithLength(sha, theirMessage);
            updateWithLength(sha, shared);
            updateWithLength(sha, passwordHash);
            Arrays.fill(shared, (byte) 0);
            return sha.digest();
        } finally {
            Arrays.fill(privateKey, (byte) 0);
            Arrays.fill(passwordScalar, (byte) 0);
            Arrays.fill(passwordHash, (byte) 0);
        }
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
