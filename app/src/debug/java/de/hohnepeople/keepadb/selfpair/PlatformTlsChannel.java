package de.hohnepeople.keepadb.selfpair;

import android.annotation.SuppressLint;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * STAGE 1 ONLY, NEVER SHIPPED (#962): the platform's TLS socket plus the restricted
 * {@code com.android.org.conscrypt.Conscrypt.exportKeyingMaterial}. Works only on a test device
 * where the hidden-API policy was relaxed over adb
 * ({@code settings put global hidden_api_policy 1}).
 */
final class PlatformTlsChannel implements TlsChannel {
    static final Factory FACTORY = PlatformTlsChannel::new;

    private final SSLSocket socket;

    private PlatformTlsChannel(Socket plain, AdbIdentity identity)
            throws IOException, GeneralSecurityException {
        SSLContext context = SSLContext.getInstance("TLSv1.3");
        context.init(new KeyManager[] {new IdentityKeyManager(identity)},
                new TrustManager[] {new AcceptAnyPeer()}, null);
        socket = (SSLSocket) context.getSocketFactory().createSocket(plain,
                plain.getInetAddress().getHostAddress(), plain.getPort(), true);
        socket.startHandshake();
    }

    @Override
    public InputStream input() {
        try {
            return socket.getInputStream();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public OutputStream output() {
        try {
            return socket.getOutputStream();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public byte[] exportKeyingMaterial(String label, int length) throws GeneralSecurityException {
        try {
            Class<?> conscrypt = Class.forName("com.android.org.conscrypt.Conscrypt");
            return (byte[]) conscrypt
                    .getMethod("exportKeyingMaterial", SSLSocket.class, String.class,
                            byte[].class, int.class)
                    .invoke(null, socket, label, null, length);
        } catch (ReflectiveOperationException e) {
            throw new GeneralSecurityException("restricted exporter not reachable", e);
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    private static final class IdentityKeyManager extends X509ExtendedKeyManager {
        private static final String ALIAS = "key";
        private final AdbIdentity identity;

        IdentityKeyManager(AdbIdentity identity) {
            this.identity = identity;
        }

        @Override
        public String chooseClientAlias(String[] keyTypes, Principal[] issuers, Socket socket) {
            return ALIAS;
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            return new X509Certificate[] {identity.certificate};
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            return identity.privateKey;
        }

        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return null;
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return null;
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return null;
        }
    }

    /**
     * {@code adbd} presents a self-signed certificate nobody can validate. Pairing is
     * authenticated by SPAKE2 bound to this TLS session instead.
     */
    @SuppressLint({"CustomX509TrustManager", "TrustAllX509TrustManager"})
    private static final class AcceptAnyPeer extends X509ExtendedTrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType,
                SSLEngine engine) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType,
                SSLEngine engine) {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
