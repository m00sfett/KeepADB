package de.hohnepeople.keepadb.selfpair;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.GeneralSecurityException;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The AES-128-GCM channel ADB pairing uses once SPAKE2 has produced a shared key (#962); mirrors
 * {@code pairing_auth/aes_128_gcm.cpp}. The nonce is a per-direction message counter.
 */
final class PairingCipher {
    private static final byte[] INFO = Bytes.ascii("adb pairing_auth aes-128-gcm key");
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private long encryptCounter;
    private long decryptCounter;

    PairingCipher(byte[] keyMaterial) throws GeneralSecurityException {
        key = new SecretKeySpec(hkdfSha256(keyMaterial, INFO, 16), "AES");
    }

    byte[] encrypt(byte[] plain) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce(encryptCounter)));
        byte[] out = cipher.doFinal(plain);
        encryptCounter++;
        return out;
    }

    /** Throws {@link javax.crypto.AEADBadTagException} if the peer used a different key. */
    byte[] decrypt(byte[] sealed) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce(decryptCounter)));
        byte[] out = cipher.doFinal(sealed);
        decryptCounter++;
        return out;
    }

    private static byte[] nonce(long counter) {
        return ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(counter).array();
    }

    /** HKDF-SHA256 (RFC 5869) with an empty salt; at most one hash block of output. */
    static byte[] hkdfSha256(byte[] keyMaterial, byte[] info, int length)
            throws GeneralSecurityException {
        if (length > 32) {
            throw new IllegalArgumentException("only one block supported");
        }
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
        byte[] prk = mac.doFinal(keyMaterial);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        mac.update(info);
        mac.update((byte) 1);
        byte[] block = mac.doFinal();
        byte[] out = new byte[length];
        System.arraycopy(block, 0, out, 0, length);
        return out;
    }
}
