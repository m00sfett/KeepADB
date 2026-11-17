package de.hohnepeople.keepadb.selfpair;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/** #962: tests the loopback ADB-over-TLS fallback scan against real loopback sockets. */
public class LoopbackAdbScanTest {
    private static final int A_STLS = 0x534c5453;

    @Test
    public void scanRangeFindsAdbTlsSocketAndIgnoresNonAdbPorts() throws Exception {
        try (ServerSocket adbServer = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
                ServerSocket noiseServer = new ServerSocket(0, 4, InetAddress.getLoopbackAddress())) {
            int adbPort = adbServer.getLocalPort();
            int noisePort = noiseServer.getLocalPort();

            Thread adbThread = new Thread(() -> serveStls(adbServer));
            Thread noiseThread = new Thread(() -> serveGarbage(noiseServer));
            adbThread.setDaemon(true);
            noiseThread.setDaemon(true);
            adbThread.start();
            noiseThread.start();

            int low = Math.min(adbPort, noisePort);
            int high = Math.max(adbPort, noisePort);
            List<Integer> found = LoopbackAdbScan.scanRange(low, high);

            assertEquals(List.of(adbPort), found);
            assertTrue(LoopbackAdbScan.offersAdbOverTls(adbPort));
            assertFalse(LoopbackAdbScan.offersAdbOverTls(noisePort));
        }
    }

    @Test
    public void scanRangeHandlesEmptyInvertedBounds() throws Exception {
        assertEquals(List.of(), LoopbackAdbScan.scanRange(5000, 4999));
    }

    @Test
    public void scanRangeReturnsEmptyWhenCancelled() throws Exception {
        try (ServerSocket adbServer = new ServerSocket(0, 4, InetAddress.getLoopbackAddress())) {
            int port = adbServer.getLocalPort();
            Thread adbThread = new Thread(() -> serveStls(adbServer));
            adbThread.setDaemon(true);
            adbThread.start();

            assertEquals(List.of(), LoopbackAdbScan.scanRange(port, port, () -> true));
        }
    }

    private static void serveStls(ServerSocket server) {
        while (!server.isClosed()) {
            try (Socket client = server.accept()) {
                InputStream in = client.getInputStream();
                OutputStream out = client.getOutputStream();
                byte[] header = new byte[24];
                if (in.read(header) <= 0) continue;
                ByteBuffer reply = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
                reply.putInt(A_STLS).putInt(0x01000000).putInt(0).putInt(0).putInt(0).putInt(~A_STLS);
                out.write(reply.array());
                out.flush();
            } catch (IOException ignored) {
                return;
            }
        }
    }

    private static void serveGarbage(ServerSocket server) {
        while (!server.isClosed()) {
            try (Socket client = server.accept()) {
                client.getOutputStream().write("HTTP/1.1 200 OK\r\n\r\n".getBytes());
                client.getOutputStream().flush();
            } catch (IOException ignored) {
                return;
            }
        }
    }
}
