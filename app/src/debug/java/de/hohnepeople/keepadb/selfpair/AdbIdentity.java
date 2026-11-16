package de.hohnepeople.keepadb.selfpair;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAKeyGenParameterSpec;
import java.util.Base64;

/**
 * An ephemeral ADB client identity (#962): an RSA key that only ever lives in memory, the
 * self-signed certificate TLS needs for it, and the key in ADB's own public-key format.
 *
 * <p>Android has no public API that builds an X.509 certificate, so the few DER structures are
 * written out here. {@code adbd} never validates the certificate; it only extracts the key.
 */
final class AdbIdentity {
    private static final int MODULUS_BYTES = 256;
    private static final byte[] SHA256_WITH_RSA = Ed25519.hex("300d06092a864886f70d01010b0500");

    final PrivateKey privateKey;
    final RSAPublicKey publicKey;
    final X509Certificate certificate;

    private AdbIdentity(KeyPair pair, X509Certificate certificate) {
        this.privateKey = pair.getPrivate();
        this.publicKey = (RSAPublicKey) pair.getPublic();
        this.certificate = certificate;
    }

    static AdbIdentity create() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(new RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4));
        KeyPair pair = generator.generateKeyPair();
        byte[] der = selfSignedCertificate(pair);
        X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
        return new AdbIdentity(pair, certificate);
    }

    /**
     * The key as {@code adbd} stores it: base64 of the {@code RSAPublicKey} struct from
     * {@code libcrypto_utils/android_pubkey.cpp}, a space, a name and a terminating NUL.
     */
    byte[] adbPublicKey(String name) {
        BigInteger modulus = publicKey.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = r32.subtract(modulus.mod(r32).modInverse(r32));
        BigInteger rr = BigInteger.ONE.shiftLeft(MODULUS_BYTES * 8).modPow(BigInteger.valueOf(2), modulus);
        ByteBuffer buffer = ByteBuffer.allocate(8 + 2 * MODULUS_BYTES + 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(MODULUS_BYTES / 4);
        buffer.putInt(n0inv.intValue());
        buffer.put(Ed25519.toLittleEndian(modulus, MODULUS_BYTES));
        buffer.put(Ed25519.toLittleEndian(rr, MODULUS_BYTES));
        buffer.putInt(publicKey.getPublicExponent().intValue());
        return Bytes.concat(Base64.getEncoder().encode(buffer.array()),
                Bytes.ascii(" " + name), new byte[1]);
    }

    private static byte[] selfSignedCertificate(KeyPair pair) throws GeneralSecurityException {
        byte[] name = der(0x30, der(0x31, der(0x30,
                Ed25519.hex("0603550403"), der(0x0c, Bytes.ascii("KeepADB")))));
        byte[] validity = der(0x30,
                der(0x17, Bytes.ascii("700101000000Z")),
                der(0x17, Bytes.ascii("491231235959Z")));
        byte[] tbs = der(0x30,
                der(0xa0, der(0x02, new byte[] {2})),
                der(0x02, new byte[] {1}),
                SHA256_WITH_RSA,
                name,
                validity,
                name,
                pair.getPublic().getEncoded());
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(pair.getPrivate());
        signature.update(tbs);
        return der(0x30, tbs, SHA256_WITH_RSA,
                der(0x03, new byte[1], signature.sign()));
    }

    /** One DER TLV with definite length around the concatenated parts. */
    static byte[] der(int tag, byte[]... parts) {
        byte[] content = Bytes.concat(parts);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        if (content.length < 0x80) {
            out.write(content.length);
        } else if (content.length < 0x100) {
            out.write(0x81);
            out.write(content.length);
        } else if (content.length < 0x10000) {
            out.write(0x82);
            out.write(content.length >> 8);
            out.write(content.length);
        } else {
            throw new IllegalArgumentException("content too long");
        }
        out.write(content, 0, content.length);
        return out.toByteArray();
    }
}
