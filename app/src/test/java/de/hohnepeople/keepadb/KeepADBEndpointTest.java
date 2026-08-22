package de.hohnepeople.keepadb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class KeepADBEndpointTest {

    @Test
    public void testNonBlockingSocketChannelScanWithEphemeralPort() throws Exception {
        int boundPort;
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            boundPort = serverSocket.getLocalPort();
            int startPort = boundPort - 20;
            int endPort = boundPort + 20;
            int totalPorts = endPort - startPort + 1;
            int numWorkers = 2;
            int chunkSize = (totalPorts + numWorkers - 1) / numWorkers;

            List<Integer> allOpen = Collections.synchronizedList(new ArrayList<>());
            List<Thread> threads = new ArrayList<>();

            for (int i = 0; i < numWorkers; i++) {
                int cs = startPort + i * chunkSize;
                int ce = Math.min(cs + chunkSize - 1, endPort);
                Thread t = new Thread(() -> {
                    byte[] loopbackBytes = new byte[]{127, 0, 0, 1};
                    try {
                        InetAddress loopback = InetAddress.getByAddress(loopbackBytes);
                        for (int p = cs; p <= ce; p++) {
                            try (SocketChannel ch = SocketChannel.open()) {
                                ch.configureBlocking(false);
                                boolean connected = ch.connect(new InetSocketAddress(loopback, p));
                                if (connected || ch.finishConnect()) {
                                    allOpen.add(p);
                                }
                            } catch (Exception ignored) {}
                        }
                    } catch (Exception ignored) {}
                });
                threads.add(t);
                t.start();
            }

            for (Thread t : threads) t.join();
            assertTrue(allOpen.contains(boundPort));
        }
    }

    @Test
    public void endpointConstantsArePlausible() {
        assertEquals("_adb-tls-connect._tcp.", KeepADBEndpoint.SERVICE_TYPE);
        assertEquals(30000, KeepADBEndpoint.PROBE_START_PORT);
        assertEquals(50000, KeepADBEndpoint.PROBE_END_PORT);
    }

    @Test
    public void formatEndpointHandlesIpv4AndIpv6() {
        assertEquals("192.168.178.50:41234", KeepADBEndpoint.formatEndpoint("192.168.178.50", 41234));
        assertEquals("[fe80::1]:41234", KeepADBEndpoint.formatEndpoint("fe80::1", 41234));
        assertEquals("[2001:db8::1]:41234", KeepADBEndpoint.formatEndpoint("[2001:db8::1]", 41234));
        assertEquals(":41234", KeepADBEndpoint.formatEndpoint(null, 41234));
    }

    @Test
    public void isLocalAddressHandlesLoopbackAndLinkLocal() throws Exception {
        InetAddress loopbackV4 = InetAddress.getByName("127.0.0.1");
        InetAddress loopbackV6 = InetAddress.getByName("::1");
        InetAddress linkLocalV6 = InetAddress.getByName("fe80::1");

        assertTrue(KeepADBEndpoint.isLocalAddress(null, loopbackV4));
        assertTrue(KeepADBEndpoint.isLocalAddress(null, loopbackV6));
        assertTrue(KeepADBEndpoint.isLocalAddress(null, linkLocalV6));
        assertFalse(KeepADBEndpoint.isLocalAddress(null, null));
    }
}
