package de.hohnepeople.keepadb.selfpair;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The TLS 1.3 key schedule (RFC 8446 section 7) for the one cipher suite the self-pairing client
 * speaks, TLS_AES_128_GCM_SHA256 (#962). Stateless helpers; all secrets are 32 bytes.
 */
final class Tls13KeySchedule {
    static final int HASH_LENGTH = 32;
    static final int KEY_LENGTH = 16;
    static final int IV_LENGTH = 12;

    private Tls13KeySchedule() {
    }

    static byte[] sha256(byte[] data) throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    static byte[] hmac(byte[] key, byte[]... parts) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        for (byte[] part : parts) {
            mac.update(part);
        }
        return mac.doFinal();
    }

    /** HKDF-Extract. */
    static byte[] extract(byte[] salt, byte[] inputKeyMaterial) throws GeneralSecurityException {
        return hmac(salt, inputKeyMaterial);
    }

    /** HKDF-Expand. */
    static byte[] expand(byte[] secret, byte[] info, int length) throws GeneralSecurityException {
        if (length > 255 * HASH_LENGTH) {
            throw new IllegalArgumentException("output too long");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] block = new byte[0];
        for (int counter = 1; out.size() < length; counter++) {
            block = hmac(secret, block, info, new byte[] {(byte) counter});
            out.write(block, 0, Math.min(block.length, length - out.size()));
        }
        return out.toByteArray();
    }

    /** HKDF-Expand-Label. */
    static byte[] expandLabel(byte[] secret, String label, byte[] context, int length)
            throws GeneralSecurityException {
        byte[] fullLabel = ("tls13 " + label).getBytes(StandardCharsets.US_ASCII);
        if (fullLabel.length > 255 || context.length > 255) {
            throw new IllegalArgumentException("label or context too long");
        }
        byte[] info = Bytes.concat(
                new byte[] {(byte) (length >> 8), (byte) length, (byte) fullLabel.length},
                fullLabel, new byte[] {(byte) context.length}, context);
        return expand(secret, info, length);
    }

    /** Derive-Secret with an already hashed transcript. */
    static byte[] deriveSecret(byte[] secret, String label, byte[] transcriptHash)
            throws GeneralSecurityException {
        return expandLabel(secret, label, transcriptHash, HASH_LENGTH);
    }

    static byte[] earlySecret() throws GeneralSecurityException {
        return extract(new byte[HASH_LENGTH], new byte[HASH_LENGTH]);
    }

    static byte[] handshakeSecret(byte[] sharedSecret) throws GeneralSecurityException {
        byte[] derived = deriveSecret(earlySecret(), "derived", sha256(new byte[0]));
        return extract(derived, sharedSecret);
    }

    static byte[] masterSecret(byte[] handshakeSecret) throws GeneralSecurityException {
        byte[] derived = deriveSecret(handshakeSecret, "derived", sha256(new byte[0]));
        return extract(derived, new byte[HASH_LENGTH]);
    }

    static byte[] trafficKey(byte[] trafficSecret) throws GeneralSecurityException {
        return expandLabel(trafficSecret, "key", new byte[0], KEY_LENGTH);
    }

    static byte[] trafficIv(byte[] trafficSecret) throws GeneralSecurityException {
        return expandLabel(trafficSecret, "iv", new byte[0], IV_LENGTH);
    }

    /** The verify_data of a Finished message. */
    static byte[] finished(byte[] trafficSecret, byte[] transcriptHash)
            throws GeneralSecurityException {
        byte[] key = expandLabel(trafficSecret, "finished", new byte[0], HASH_LENGTH);
        return hmac(key, transcriptHash);
    }

    /** The exporter of RFC 8446 section 7.5 with an empty context value. */
    static byte[] export(byte[] exporterMasterSecret, String label, int length)
            throws GeneralSecurityException {
        byte[] emptyHash = sha256(new byte[0]);
        byte[] secret = deriveSecret(exporterMasterSecret, label, emptyHash);
        return expandLabel(secret, "exporter", emptyHash, length);
    }
}
