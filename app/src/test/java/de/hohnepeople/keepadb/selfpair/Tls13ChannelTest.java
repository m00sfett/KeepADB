package de.hohnepeople.keepadb.selfpair;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.Provider;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Runs the own TLS 1.3 client against two independent servers: the JDK's (handshake with client
 * authentication, data in both directions) and, where the binary exists, OpenSSL's (exporter).
 */
public class Tls13ChannelTest {
    private static AdbIdentity client;
    private static AdbIdentity server;

    private static final List<Provider> REMOVED = new ArrayList<>();

    /**
     * Robolectric tests in the same JVM install Conscrypt (and BouncyCastle) ahead of the JDK's
     * providers. With Conscrypt first, the JDK's own TLS server no longer initialises
     * (sun.security.ssl.SignatureScheme fails in its static initialiser), so the reference peer
     * of these tests would be dead. Take the foreign providers out for this class only.
     */
    @BeforeClass
    public static void createIdentities() throws Exception {
        for (Provider provider : Security.getProviders()) {
            String name = provider.getName();
            if (name.equals("Conscrypt") || name.equals("BC")) {
                Security.removeProvider(name);
                REMOVED.add(provider);
            }
        }
        client = AdbIdentity.create();
        server = AdbIdentity.create();
    }

    @AfterClass
    public static void restoreProviders() {
        for (int i = 0; i < REMOVED.size(); i++) {
            Provider provider = REMOVED.get(i);
            if (provider.getName().equals("Conscrypt")) {
                Security.insertProviderAt(provider, 1);
            } else {
                Security.addProvider(provider);
            }
        }
        REMOVED.clear();
    }

    @Test
    public void handshakesWithClientAuthenticationAndMovesData() throws Exception {
        byte[] big = new byte[70_000];
        new Random(962).nextBytes(big);
        try (SSLServerSocket listener = jdkServer()) {
            CompletableFuture<X509Certificate> seenClient = CompletableFuture.supplyAsync(() -> {
                try (SSLSocket peer = (SSLSocket) listener.accept()) {
                    peer.startHandshake();
                    assertEquals("TLSv1.3", peer.getSession().getProtocol());
                    byte[] received = Bytes.readFully(peer.getInputStream(), big.length);
                    peer.getOutputStream().write(received);
                    peer.getOutputStream().flush();
                    return (X509Certificate) peer.getSession().getPeerCertificates()[0];
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }, TestThreads.EXECUTOR);
            Socket socket = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
            socket.setSoTimeout(10_000);
            try (TlsChannel channel = Tls13Channel.FACTORY.handshake(socket, client)) {
                assertEquals(server.certificate, ((Tls13Channel) channel).peerCertificate());
                channel.output().write(big);
                channel.output().flush();
                assertArrayEquals(big, Bytes.readFully(channel.input(), big.length));
                assertEquals(client.certificate, seenClient.get(10, TimeUnit.SECONDS));
                // The server closed its side: a clean end of stream, not an error.
                assertEquals(-1, channel.input().read());
                assertEquals(64, channel.exportKeyingMaterial("adb-label\0", 64).length);
            }
        }
    }

    /** OpenSSL prints its own exporter output; ours must be identical. */
    @Test
    public void exporterMatchesOpenSsl() throws Exception {
        assumeTrue(new File("/usr/bin/openssl").canExecute());
        File dir = Files.createTempDirectory("selfpair-tls").toFile();
        File key = new File(dir, "key.pem");
        File cert = new File(dir, "cert.pem");
        Files.write(key.toPath(), pem("PRIVATE KEY", server.privateKey.getEncoded()));
        Files.write(cert.toPath(), pem("CERTIFICATE", server.certificate.getEncoded()));
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        Process openssl = new ProcessBuilder("/usr/bin/openssl", "s_server", "-accept",
                "127.0.0.1:" + port, "-tls1_3", "-key", key.getPath(), "-cert", cert.getPath(),
                "-Verify", "1", "-naccept", "1",
                "-keymatexport", "EXPORTER-keepadb-test", "-keymatexportlen", "64")
                .redirectErrorStream(true).start();
        try {
            byte[] exported = null;
            for (int attempt = 0; attempt < 50 && exported == null; attempt++) {
                try {
                    Socket socket = new Socket(InetAddress.getLoopbackAddress(), port);
                    socket.setSoTimeout(10_000);
                    try (TlsChannel channel = Tls13Channel.FACTORY.handshake(socket, client)) {
                        exported = channel.exportKeyingMaterial("EXPORTER-keepadb-test", 64);
                        channel.output().write("hello\n".getBytes(StandardCharsets.US_ASCII));
                        channel.output().flush();
                    }
                } catch (java.net.ConnectException e) {
                    Thread.sleep(100);
                }
            }
            assertTrue("openssl did not exit", openssl.waitFor(10, TimeUnit.SECONDS));
            String material = null;
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    openssl.getInputStream(), StandardCharsets.US_ASCII))) {
                for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                    output.append(line).append('\n');
                    if (line.trim().startsWith("Keying material: ")) {
                        material = line.trim().substring("Keying material: ".length());
                    }
                }
            }
            if (material == null) {
                fail("no keying material in openssl output:\n" + output);
            }
            assertArrayEquals(Bytes.hex(material.toLowerCase()), exported);
        } finally {
            openssl.destroyForcibly();
            key.delete();
            cert.delete();
            dir.delete();
        }
    }

    @Test(expected = IOException.class)
    public void failsWhenTheServerRejectsTheClientCertificate() throws Exception {
        try (SSLServerSocket listener = jdkServer(new RejectingTrustManager())) {
            CompletableFuture.runAsync(() -> {
                try (SSLSocket peer = (SSLSocket) listener.accept()) {
                    peer.startHandshake();
                } catch (IOException e) {
                    // expected: the handshake fails on the server side
                }
            }, TestThreads.EXECUTOR);
            Socket socket = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
            socket.setSoTimeout(10_000);
            try (TlsChannel channel = Tls13Channel.FACTORY.handshake(socket, client)) {
                channel.input().read();
            }
        }
    }

    @Test(expected = IOException.class)
    public void failsAgainstAServerThatOnlySpeaksTls12() throws Exception {
        try (SSLServerSocket listener = jdkServer()) {
            listener.setEnabledProtocols(new String[] {"TLSv1.2"});
            CompletableFuture.runAsync(() -> {
                try (SSLSocket peer = (SSLSocket) listener.accept()) {
                    peer.startHandshake();
                } catch (IOException e) {
                    // expected
                }
            }, TestThreads.EXECUTOR);
            Socket socket = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
            socket.setSoTimeout(10_000);
            Tls13Channel.FACTORY.handshake(socket, client).close();
        }
    }

    @Test(expected = IOException.class)
    public void failsOnGarbageInsteadOfAServerHello() throws Exception {
        try (ServerSocket listener = new ServerSocket(0)) {
            CompletableFuture.runAsync(() -> {
                try (Socket peer = listener.accept()) {
                    OutputStream out = peer.getOutputStream();
                    out.write(new byte[] {22, 3, 3, 0, 8, 2, 0, 0, 4, 1, 2, 3, 4});
                    out.flush();
                    InputStream in = peer.getInputStream();
                    while (in.read() >= 0) {
                        // drain until the client gives up
                    }
                } catch (IOException e) {
                    // expected
                }
            }, TestThreads.EXECUTOR);
            Socket socket = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
            socket.setSoTimeout(10_000);
            Tls13Channel.FACTORY.handshake(socket, client).close();
        }
    }

    /** #1040: the parser accepts an entry only with an empty extension block. */
    @Test
    public void acceptsACertificateEntryWithoutExtensions() throws Exception {
        assertEquals(server.certificate,
                Tls13Channel.parseCertificate(certificateBody(new byte[0])));
    }

    /** #1040: a vector too short to hold even one extension header (audit F-03). */
    @Test(expected = IOException.class)
    public void rejectsATruncatedCertificateEntryExtensionBlock() throws Exception {
        Tls13Channel.parseCertificate(certificateBody(new byte[] {0}));
    }

    /** #1040: a well-formed extension the client never offered is just as unacceptable. */
    @Test(expected = IOException.class)
    public void rejectsAWellFormedButUnofferedCertificateEntryExtension() throws Exception {
        // status_request (5) with empty data.
        Tls13Channel.parseCertificate(certificateBody(new byte[] {0, 5, 0, 0}));
    }

    /** Certificate message body: empty context, one entry with the given extension block. */
    private static byte[] certificateBody(byte[] extensions) throws Exception {
        byte[] der = server.certificate.getEncoded();
        byte[] entry = Bytes.concat(length24(der.length), der,
                new byte[] {(byte) (extensions.length >> 8), (byte) extensions.length},
                extensions);
        return Bytes.concat(new byte[] {0}, length24(entry.length), entry);
    }

    private static byte[] length24(int length) {
        return new byte[] {(byte) (length >> 16), (byte) (length >> 8), (byte) length};
    }

    private static SSLServerSocket jdkServer() throws Exception {
        return jdkServer(new AcceptingTrustManager());
    }

    private static SSLServerSocket jdkServer(TrustManager trust) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setKeyEntry("server", server.privateKey, new char[0],
                new Certificate[] {server.certificate});
        KeyManagerFactory keys = KeyManagerFactory.getInstance("SunX509", "SunJSSE");
        keys.init(store, new char[0]);
        SSLContext context = SSLContext.getInstance("TLSv1.3", "SunJSSE");
        context.init(keys.getKeyManagers(), new TrustManager[] {trust}, null);
        SSLServerSocket listener = (SSLServerSocket) context.getServerSocketFactory()
                .createServerSocket(0, 1, InetAddress.getLoopbackAddress());
        listener.setNeedClientAuth(true);
        return listener;
    }

    private static byte[] pem(String label, byte[] der) {
        return ("-----BEGIN " + label + "-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der)
                + "\n-----END " + label + "-----\n").getBytes(StandardCharsets.US_ASCII);
    }

    private static class AcceptingTrustManager implements X509TrustManager {
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

    private static final class RejectingTrustManager extends AcceptingTrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            throw new IllegalStateException("rejected on purpose");
        }
    }
}
