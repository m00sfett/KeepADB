package de.hohnepeople.keepadb;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;

import org.junit.Test;

/**
 * Behavior tests for #363: the quick probe's Wi-Fi-side reachability check must not accept a
 * foreign service purely because it answers a TCP connect() on our Wi-Fi address (the residual
 * gap #314 left open for a service bound to {@code 0.0.0.0}, "all interfaces").
 *
 * <p>{@link KeepADBEndpoint#looksLikeAdbTlsResponse} is exercised directly against fabricated
 * byte sequences (no socket needed), and {@link KeepADBEndpoint#probeAdbTlsPort(String, int,
 * int)} end-to-end against real loopback {@link ServerSocket} fakes that stand in for "a foreign
 * service" and "adbd's TLS listener" respectively.
 */
public class KeepADBEndpointTlsProbeTest {

    // ---- looksLikeAdbTlsResponse(): pure byte-level checks -----------------------------------

    @Test
    public void aTlsShapedResponseIsAccepted() {
        // A fabricated ServerHello-looking record: handshake(0x16), TLS 1.2(0x03,0x03), ...
        byte[] serverHello = { 0x16, 0x03, 0x03, 0x00, 0x02, (byte) 0xAB, (byte) 0xCD };
        byte[] sent = KeepADBEndpoint.PROBE_CLIENT_HELLO;
        assertTrue(KeepADBEndpoint.looksLikeAdbTlsResponse(serverHello, sent));
    }

    @Test
    public void aTlsAlertIsAlsoAccepted() {
        // A real TLS server may reject a malformed/unsupported ClientHello with an Alert record
        // (content type 0x15) instead of a ServerHello -- that still proves it speaks TLS.
        byte[] alert = { 0x15, 0x03, 0x03, 0x00, 0x02, 0x02, 0x46 };
        assertTrue(KeepADBEndpoint.looksLikeAdbTlsResponse(alert, KeepADBEndpoint.PROBE_CLIENT_HELLO));
    }

    @Test
    public void aForeignPlaintextResponseIsRejected() {
        byte[] httpBanner = "HTTP/1.1 400 Bad Request\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        assertFalse(KeepADBEndpoint.looksLikeAdbTlsResponse(httpBanner, KeepADBEndpoint.PROBE_CLIENT_HELLO));
    }

    @Test
    public void anEmptyOrTooShortResponseIsRejected() {
        assertFalse(KeepADBEndpoint.looksLikeAdbTlsResponse(null, KeepADBEndpoint.PROBE_CLIENT_HELLO));
        assertFalse(KeepADBEndpoint.looksLikeAdbTlsResponse(new byte[0], KeepADBEndpoint.PROBE_CLIENT_HELLO));
        assertFalse(KeepADBEndpoint.looksLikeAdbTlsResponse(new byte[] { 0x16 }, KeepADBEndpoint.PROBE_CLIENT_HELLO));
    }

    @Test
    public void aNaiveEchoOfOurOwnClientHelloIsRejected() {
        // A generic TCP echo service would bounce our ClientHello bytes right back, which would
        // otherwise pass the content-type/version check trivially since we chose those bytes.
        byte[] sent = KeepADBEndpoint.PROBE_CLIENT_HELLO;
        byte[] echoed = Arrays.copyOf(sent, sent.length);
        assertFalse("byte-identical echo of our own probe must not be accepted",
                KeepADBEndpoint.looksLikeAdbTlsResponse(echoed, sent));
    }

    // ---- probeAdbTlsPort(): end-to-end against real loopback sockets -------------------------

    @Test
    public void aForeignServiceOnTheExpectedAddressFailsTheProbe() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            int port = server.getLocalPort();
            Thread foreignService = new Thread(() -> {
                try (Socket client = server.accept()) {
                    // Read whatever the probe sent, then reply with something that is not
                    // TLS-shaped at all -- the "unrelated service that happens to be listening"
                    // case #363 is about.
                    readSomeBytes(client.getInputStream());
                    OutputStream out = client.getOutputStream();
                    out.write("NOT ADB\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    out.flush();
                } catch (Exception ignored) {
                }
            }, "FakeForeignService");
            foreignService.start();

            assertFalse("a non-TLS foreign service must not be accepted as the endpoint",
                    KeepADBEndpoint.probeAdbTlsPort("127.0.0.1", port, 1000));
            foreignService.join(2000);
        }
    }

    @Test
    public void aSilentForeignServiceThatNeverRepliesFailsTheProbe() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            int port = server.getLocalPort();
            Thread silentService = new Thread(() -> {
                try (Socket client = server.accept()) {
                    // Accept the connection and consume the ClientHello, but never respond --
                    // e.g. a service that only ever speaks after receiving its own magic bytes.
                    readSomeBytes(client.getInputStream());
                    Thread.sleep(1500);
                } catch (Exception ignored) {
                }
            }, "FakeSilentService");
            silentService.start();

            assertFalse("a service that never replies must not be accepted as the endpoint",
                    KeepADBEndpoint.probeAdbTlsPort("127.0.0.1", port, 300));
            silentService.join(3000);
        }
    }

    @Test
    public void aRealAdbLikeTlsResponseStillPassesTheProbe() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            int port = server.getLocalPort();
            Thread adbLikeService = new Thread(() -> {
                try (Socket client = server.accept()) {
                    readSomeBytes(client.getInputStream());
                    // Reply with a fabricated but TLS-record-shaped ServerHello, as adbd's
                    // wireless-debugging TLS listener would.
                    OutputStream out = client.getOutputStream();
                    out.write(new byte[] { 0x16, 0x03, 0x03, 0x00, 0x04, 0x02, 0x00, 0x00, 0x00 });
                    out.flush();
                } catch (Exception ignored) {
                }
            }, "FakeAdbTlsService");
            adbLikeService.start();

            assertTrue("a genuine TLS-shaped response must still be accepted",
                    KeepADBEndpoint.probeAdbTlsPort("127.0.0.1", port, 1000));
            adbLikeService.join(2000);
        }
    }

    private static void readSomeBytes(InputStream in) {
        try {
            byte[] buffer = new byte[256];
            in.read(buffer);
        } catch (Exception ignored) {
        }
    }
}
