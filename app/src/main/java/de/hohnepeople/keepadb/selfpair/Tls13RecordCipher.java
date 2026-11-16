package de.hohnepeople.keepadb.selfpair;

import java.io.IOException;
import java.security.GeneralSecurityException;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * TLS 1.3 record protection for one direction and one traffic secret (RFC 8446 section 5.2),
 * AES-128-GCM only (#962).
 */
final class Tls13RecordCipher {
    static final int TAG_LENGTH = 16;
    /** Largest TLSInnerPlaintext content this client sends or accepts. */
    static final int MAX_PLAINTEXT = 1 << 14;

    private final SecretKeySpec key;
    private final byte[] iv;
    private long sequence;

    Tls13RecordCipher(byte[] trafficSecret) throws GeneralSecurityException {
        key = new SecretKeySpec(Tls13KeySchedule.trafficKey(trafficSecret), "AES");
        iv = Tls13KeySchedule.trafficIv(trafficSecret);
    }

    /** Returns the complete record, header included, for {@code content} of {@code type}. */
    byte[] seal(int type, byte[] content, int offset, int length)
            throws GeneralSecurityException {
        if (length > MAX_PLAINTEXT) {
            throw new IllegalArgumentException("fragment too long");
        }
        byte[] inner = new byte[length + 1];
        System.arraycopy(content, offset, inner, 0, length);
        inner[length] = (byte) type;
        int sealedLength = inner.length + TAG_LENGTH;
        byte[] header = {23, 3, 3, (byte) (sealedLength >> 8), (byte) sealedLength};
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH * 8, nextNonce()));
        cipher.updateAAD(header);
        return Bytes.concat(header, cipher.doFinal(inner));
    }

    /**
     * Opens one protected record. Returns the content with its real content type appended as
     * the last byte.
     */
    byte[] open(byte[] header, byte[] sealed) throws IOException, GeneralSecurityException {
        if (sealed.length < TAG_LENGTH + 1 || sealed.length > MAX_PLAINTEXT + 256) {
            throw new IOException("bad TLS record length");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH * 8, nextNonce()));
        cipher.updateAAD(header);
        byte[] inner;
        try {
            inner = cipher.doFinal(sealed);
        } catch (AEADBadTagException e) {
            throw new IOException("TLS record failed authentication", e);
        }
        int end = inner.length;
        while (end > 0 && inner[end - 1] == 0) {
            end--;
        }
        if (end == 0) {
            throw new IOException("TLS record without content type");
        }
        if (end - 1 > MAX_PLAINTEXT) {
            throw new IOException("TLS record too long");
        }
        byte[] out = new byte[end];
        System.arraycopy(inner, 0, out, 0, end);
        return out;
    }

    private byte[] nextNonce() {
        if (sequence < 0) {
            throw new IllegalStateException("sequence number exhausted");
        }
        byte[] nonce = iv.clone();
        long value = sequence++;
        for (int i = 0; i < 8; i++) {
            nonce[IV_END - i] ^= (byte) (value >>> (8 * i));
        }
        return nonce;
    }

    private static final int IV_END = Tls13KeySchedule.IV_LENGTH - 1;
}
